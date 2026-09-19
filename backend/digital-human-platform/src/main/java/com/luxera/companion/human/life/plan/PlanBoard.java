package com.luxera.companion.human.life.plan;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.3 —— <b>计划表</b>。
 *
 * <h2>为什么它不是队列</h2>
 * 用户直接问过这个问题:
 * <blockquote>
 * 至于这几个 event 你要用几个队列存储, 甚至于像计划表这种可能用队列还不好实现,
 * 你得想想什么数据结构实现能很好满足要求
 * </blockquote>
 *
 * <p>队列只能表达 {@code A → B → C} 的顺序。而真实计划是:
 * <pre>
 * 12:00 A
 * 12:30 B
 * 13:30 C
 * </pre>
 * 并且 {@code B} 可以移动、{@code C} 可以删除、{@code A} 上可以插入新的项、
 * 两项可以同时存在（一个窗口包含另一个）。
 * <b>队列的"只能看队首、取出即消费"语义, 与"随时能改未来任意一段"是直接冲突的。</b>
 *
 * <p>所以计划表是<b>版本化的时间轴</b>: 当前版本 + 永不删除的历史版本链。
 * 时间轴索引在 {@link PlanRevision} 里。
 *
 * <h2>改动一律走 {@link #apply} + {@link PlanMutation}</h2>
 * 本类<b>不提供</b> {@code addItem} / {@code removeItem} / {@code moveItem} 这类
 * 单个操作的 API。原因是那会诱导调用方零敲碎打地改计划表 ——
 * 而"她改了什么主意"就会散落成一堆单点修改, 再也拼不出一个完整的决定。
 *
 * <p>{@link #apply} 要求调用方一次给出<b>一整套</b>改动和<b>一个理由</b>。
 * 那正是用户描述的语义: "他是真的改变了计划表, 让 agent <b>重新思考</b>重排计划表"。
 *
 * <h2>新版本如何表达"变化"</h2>
 * 关键的设计选择: <b>新 Revision 保留全部项, 变化通过生命周期状态表达。</b>
 * <pre>
 * Revision 17:  item-3 写作业 12:00-13:00  ACTIVE
 *
 *        ↓ Move(item-3 → 12:25-13:10, "有点冷, 先加件衣服") + Insert(穿衣服 12:15-12:25)
 *
 * Revision 18:  item-3 写作业 12:00-13:00  SUPERSEDED   ← 留在表里, 带着它原来的窗口
 *               item-7 穿衣服 12:15-12:25  ACTIVE
 *               item-8 写作业 12:25-13:10  PENDING       ← 新的 id, 新的窗口
 * </pre>
 *
 * <p>为什么保留而不是剔除: 这样<b>单个 Revision 就能回答"她这一天发生了什么"</b> ——
 * 被替代的项带着它原来的时间段留在那里, 一眼能看出"12:00-13:00 本来要写作业,
 * 被加衣服打断了"。如果剔除, 想知道这件事就必须去翻旧版本做差异比对 ——
 * 而差异比对分不出"移动"和"删掉再加一项"（见 {@link PlanMutation} 的说明）。
 *
 * <p>读"当前有效的计划"用 {@link PlanRevision#liveItems()} /
 * {@link PlanRevision#activeAt} / {@link PlanRevision#dueAt} —— 它们都按
 * {@link PlanLifecycle#occupiesFuture()} 过滤。
 *
 * <h2>观察者</h2>
 * 计划表本身不认识 {@code EventFabric}（它只是数据结构）。要产生
 * {@code plan.revision-created.v1} 这类事件, 注册一个 {@link Observer} ——
 * 见 {@code human.life.plan.event.PlanEventPublisher}。这样计划表可以在没有
 * 事件总线的测试里单独跑。
 */
@Slf4j
public class PlanBoard {

    /**
     * 历史版本保留上限。
     *
     * <p>设计文档说"旧版本永不删除"—— 语义上确实如此, <b>但内存里不能真的无限留</b>:
     * 一个每秒重排一次的 agent 一天会产生 86400 个版本, 每个带着完整的项列表。
     *
     * <p>所以这里是"内存里留最近 {@value} 个, 更早的必须已经落库"。
     * 落库由 {@code PlanRevisionRepository} 负责 —— 也就是说
     * <b>"永不删除"这条承诺由数据库承担, 内存只承担"最近的可查"</b>。
     * 把这条边界写清楚, 比含糊地说"永不删除"然后某天 OOM 要好。
     */
    public static final int IN_MEMORY_HISTORY_LIMIT = 512;

    private final List<Observer> observers = new ArrayList<>();

    private final List<PlanRevision> revisionHistory = new ArrayList<>();

    private PlanRevision current;

    /** 已触发过的项 —— 防止同一项在一个 tick 里被触发两次。 */
    private final Map<PlanItemId, Instant> triggered = new LinkedHashMap<>();

    public PlanBoard() {
        this(null);
    }

    /**
     * <b>构造时装入的这一版不会通知任何观察者 —— 这是一个刻意的承诺, 不是一个遗漏。</b>
     *
     * <h2>为什么必须如此: 它是"恢复"与"重排"的分界线</h2>
     * {@code initial} 的用途只有两个: ① 她还没有计划({@code null});
     * ② 从库里恢复回来的一版计划。第二种情况下, 那一版是<b>在她上一次活着的时候</b>
     * 产生的 —— 它的事件早就发过了, 而世界也已经记下了。
     *
     * <p>如果构造器在这里通知观察者, 重启就会<b>重播一遍她生前的全部计划变更</b>:
     * {@code plan.revision-created.v1} 与逐条的 {@code plan.item-scheduled.v1}
     * 会被当成"她刚刚安排的"发出去。那不是"多几条日志" —— 行为分析会把它们
     * 算成真实的重排次数, 前端时间轴会多出一条根本不存在的变更, 而 LLM 的 context 里
     * 会出现"她刚刚决定……"这种她从未做过的决定。库里那一版计划与这些事件之间
     * 没有任何矛盾能让人发现这件事: 它们<b>看起来完全正常</b>。
     *
     * <p>所以这条承诺的正确用法是: <b>先 {@code new PlanBoard(restored)},
     * 后 {@code attachTo(board)}</b> —— 装载发生在一个还没有观察者的时刻。
     * 反过来(先挂发布器, 再想办法把版本塞进去)会需要一个"装载"方法,
     * 而那个方法的存在本身就是这个 bug 的入口: 它必须被记得"不要通知",
     * 而构造器不需要被记得任何事。
     *
     * <h2>这个承诺不可能靠"先装观察者、再造表"来验证</h2>
     * 观察者只能挂在<b>一张已经存在的表</b>上, 于是本构造器跑的那一刻它必然是空集 ——
     * 那样的断言只是在证明"观察者坏了", 它证明不了任何关于本构造器的事。
     * 能钉住它的只有后果: {@code PlanBoardTest} 先用 {@code initial} 造一张表,
     * <b>然后</b>才挂上 {@link com.luxera.companion.human.life.plan.event.PlanEventPublisher},
     * 断言出去的事件<b>一条都没有</b>; 紧接一次 {@link #apply} 断言事件<b>出现</b>。
     * 后半句是必需的 —— 没有它, 前半句就成了一条"这个发布器根本不会发事件"的证明。
     *
     * <p>装载与挂载的先后在 {@code Life} 那里由代码的书写顺序保证, 而不是靠这里:
     * 本构造器只承诺"装载是静默的", 而"发布器在那之后才挂上"是那个构造器的事
     * （见 {@code Life} 的重载构造器与 {@code HumanAssembly.assemble}）。
     *
     * @param initial 初始版本。为 {@code null} 表示"她还没有任何计划" ——
     *                此时 {@link #current()} 返回一个空 Revision, 而不是
     *                {@code Optional.empty()}。理由见 {@link #current()} 的说明
     */
    public PlanBoard(PlanRevision initial) {
        this.current = initial != null ? initial
                : PlanRevision.initial(Instant.EPOCH, List.of(), "还没有任何计划");
        this.revisionHistory.add(this.current);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 当前生效的版本。
     *
     * <p>返回空 Revision 而不是 {@link Optional#empty()} 的理由: "她今天没有计划"
     * 与"计划系统还没启动"是两件不同的事, 而调用方（调度器、重排器、界面）
     * 在两种情况下要做的事<b>完全一样</b> —— 遍历一个空列表。
     * 让它们每次都判空, 只会得到一堆 {@code if (board.current().isPresent())}。
     *
     * <p>而"没有计划"这件事本身是<b>有意义的行为数据</b> —— 见
     * {@code plan.revision-created.v1} 的语义。它应该表现为"当前版本是空的",
     * 而不是"没有当前版本"。
     */
    public PlanRevision current() {
        return current;
    }

    /**
     * 按 id 找一项 —— <b>在当前版本里</b>找。
     *
     * <p>看上去只是 {@code current().find(id)} 的一层转发, 而它值得存在的原因是
     * 一个已经被踩到的错: 调用方（{@code Life}）写的是 {@code board.find(id)},
     * 心里想的是"计划表上那一项", 而 {@link PlanBoard} 上根本没有这个方法 ——
     * 因为"哪一项"这个问题必须指明<b>在哪个版本里</b>, 而那是版本链存在的全部意义。
     *
     * <p>于是这里必须把那个前提写进名字旁边的说明里: 它找的是<b>当前版本</b>的项。
     * 若某个调用方其实想问"她最初打算做什么", 该走 {@link #original()} ——
     * 这两个问题在重排之后会给出不同的答案, 而它们的差别正是行为分析要看的东西。
     *
     * <p>老版本里的项不会丢（{@code SUPERSEDED} 的项照样在, 见 {@link PlanMutation}）,
     * 所以要查"这一项的历史"应当遍历 {@link #history()} 而不是指望这里回退。
     */
    public Optional<PlanItem> find(PlanItemId id) {
        return current.find(id);
    }

    /** 版本链, 旧到新。{@link #IN_MEMORY_HISTORY_LIMIT} 之内。 */
    public List<PlanRevision> history() {
        return Collections.unmodifiableList(revisionHistory);
    }

    public Optional<PlanRevision> revisionById(String revisionId) {
        return revisionHistory.stream().filter(r -> r.revisionId().equals(revisionId)).findFirst();
    }

    /** 最初的那一版 —— 回答"她原本打算干什么"。 */
    public PlanRevision original() {
        return revisionHistory.get(0);
    }

    /** 到现在为止她一共重排了几次。 */
    public int revisionCount() {
        return revisionHistory.size();
    }

    /**
     * 现在该触发哪些项。
     *
     * <p><b>触发过的不再返回</b> —— 去重在这里做, 而不是让调用方自己做。
     * 理由: 调用方是 {@link PlanScheduler} 的 tick 循环, 而它每秒都会问一次。
     * 如果去重放在调用方, 每一个调用方都要自己维护"我见过哪些了" ——
     * 而漏一个的症状是"她一小时里开始了十七次写作业"。
     */
    public List<PlanItem> dueAt(Instant moment) {
        List<PlanItem> due = current.dueAt(moment).stream()
                .filter(i -> !triggered.containsKey(i.id()))
                .toList();
        due.forEach(i -> triggered.put(i.id(), moment));
        return due;
    }

    /** 现在她归哪一项。 */
    public List<PlanItem> activeAt(Instant moment) {
        return current.activeAt(moment);
    }

    // ─────────────────────────── 写入 ───────────────────────────

    /**
     * 把一整套改动应用到计划表上, 产出一个新版本。
     *
     * <p>这是本类唯一的写入口（见类注释）。
     *
     * @param at        这次重排发生的仿真时刻
     * @param reason    为什么重排。第一人称、面向人 —— 它要进行为分析与 LLM context
     * @param mutations 相对当前版本的全部改动。<b>空列表是合法的</b> ——
     *                  那表示"她想过了, 决定什么都不改", 而这恰恰是
     *                  {@link PlanMutation.KeepActive} 想记录的事;
     *                  如果她连想都没想, 调用方就不该调这个方法
     * @return 新的当前版本
     */
    public PlanRevision apply(Instant at, String reason, List<PlanMutation> mutations) {
        Objects.requireNonNull(at, "重排必须带时刻 —— 重排是关于时间的");
        Objects.requireNonNull(reason, "重排必须说明理由");
        Objects.requireNonNull(mutations, "改动列表不能为空 —— 想表达'什么都不改'请传空列表, 而不是 null");

        long nextNumber = current.revisionNumber() + 1;
        Map<PlanItemId, PlanItem> working = new LinkedHashMap<>();
        current.items().forEach(i -> working.put(i.id(), i));

        List<PlanMutation> applied = new ArrayList<>();
        for (PlanMutation mutation : mutations) {
            applyOne(mutation, working, nextNumber, at);
            applied.add(mutation);
        }

        PlanRevision next = new PlanRevision(nextNumber, current.revisionId(), reason, at,
                working.values(), current.constraints(), applied);

        remember(next);
        current = next;
        log.info("[PlanBoard] {} → {} 「{}」, {} 项改动, 现存 {} 项",
                next.previousRevisionId().orElse("-"), next.revisionId(), reason,
                applied.size(), next.liveItems().size());
        notifyObservers(next);
        return next;
    }

    private void applyOne(PlanMutation mutation, Map<PlanItemId, PlanItem> working,
                          long nextNumber, Instant at) {
        // 用 instanceof 链而不是 switch 的模式匹配: 后者在 Java 17 还是 preview 特性。
        // 这里刻意把每个分支写全, 而不是合并成"能处理的处理、剩下的不管" ——
        // 将来 PlanMutation 加了第七种改动时, 这个方法的默认分支会记一条 WARN,
        // 而不是静默地让新改动消失
        if (mutation instanceof PlanMutation.Insert insert) {
            PlanItem item = insert.item().withCreatedInRevision(nextNumber);
            // 到点就立刻进入 ACTIVE —— 用户说的"想立马执行就把其触发事件调成现在"，
            // 这种情况下它必须当场就是"正在做", 否则调度器要等下一个 tick 才发现它
            if (item.window().contains(at)) {
                item = item.withLifecycle(PlanLifecycle.ACTIVE);
                triggered.put(item.id(), at);
            }
            working.put(item.id(), item);

        } else if (mutation instanceof PlanMutation.Remove remove) {
            requirePresent(working, remove.itemId(), "删除");
            PlanItem item = working.get(remove.itemId());
            transition(item, PlanLifecycle.CANCELLED, remove.reason(), working);

        } else if (mutation instanceof PlanMutation.Move move) {
            PlanItem old = requirePresent(working, move.itemId(), "移动");
            if (old.fixed()) {
                // 不抛异常: "她试图移动一个不能移动的项"是一个<行为>, 不是故障。
                // 记一条日志并保持原样 —— 重排器应当先检查 movable(), 但即使它忘了,
                // 世界也不该因此崩掉
                log.warn("[PlanBoard] {} 是固定的, 拒绝移动到 {} —— 重排器应当先检查 movable()",
                        old.id().value(), move.newWindow());
                return;
            }
            transition(old, PlanLifecycle.SUPERSEDED, move.reason(), working);
            PlanItem moved = old.withWindow(move.newWindow()).copyAsNew(
                            "由 " + old.window() + " 移至 " + move.newWindow() + ": " + move.reason())
                    .withCreatedInRevision(nextNumber);
            if (moved.window().contains(at)) {
                moved = moved.withLifecycle(PlanLifecycle.ACTIVE);
                triggered.put(moved.id(), at);
            }
            working.put(moved.id(), moved);

        } else if (mutation instanceof PlanMutation.Resize resize) {
            PlanItem old = requirePresent(working, resize.itemId(), "改时长");
            transition(old, PlanLifecycle.SUPERSEDED, resize.reason(), working);
            PlanItem resized = old.withWindow(old.window().lasting(resize.newLength()))
                    .copyAsNew("时长由 " + old.window().duration() + " 改为 "
                            + resize.newLength() + ": " + resize.reason())
                    .withCreatedInRevision(nextNumber);
            working.put(resized.id(), resized);

        } else if (mutation instanceof PlanMutation.Replace replace) {
            requirePresent(working, replace.removed(), "替换");
            PlanItem old = working.get(replace.removed());
            transition(old, PlanLifecycle.SUPERSEDED, replace.reason(), working);
            PlanItem inserted = replace.inserted().withCreatedInRevision(nextNumber);
            if (inserted.window().contains(at)) {
                inserted = inserted.withLifecycle(PlanLifecycle.ACTIVE);
                triggered.put(inserted.id(), at);
            }
            working.put(inserted.id(), inserted);

        } else if (mutation instanceof PlanMutation.KeepActive keep) {
            PlanItem item = requirePresent(working, keep.itemId(), "保持");
            // 唯一的"不改动"分支: 它的价值全在把这次打扰记进 mutations 里。
            // 注意这里<没有> any remainingDuration 状态 —— 见 PlanMutation.KeepActive
            if (item.lifecycle() == PlanLifecycle.PENDING && item.window().contains(at)) {
                working.put(item.id(), item.withLifecycle(PlanLifecycle.ACTIVE));
            }

        } else {
            log.warn("[PlanBoard] 不认识的改动类型 {} —— 它被忽略了。"
                            + "如果 PlanMutation 新增了成员, 请在这里补上对应分支",
                    mutation.getClass().getName());
        }
    }

    /**
     * 状态迁移 + 一句话记进 note。
     *
     * <p>非法迁移在这里被拦住并记 WARN。见 {@link PlanLifecycle#canTransitionTo} ——
     * 让"非法迁移"只在一个地方被检查, 比散落在各个 switch 分支里可靠。
     */
    private void transition(PlanItem item, PlanLifecycle target, String why,
                            Map<PlanItemId, PlanItem> working) {
        if (!item.lifecycle().canTransitionTo(target)) {
            log.warn("[PlanBoard] {} 不能从 {} 迁到 {} —— 忽略这次迁移 ({})",
                    item.id().value(), item.lifecycle().label(), target.label(), why);
            return;
        }
        working.put(item.id(), item.withLifecycle(target).withNote(why));
    }

    private PlanItem requirePresent(Map<PlanItemId, PlanItem> working, PlanItemId id, String what) {
        PlanItem item = working.get(id);
        if (item == null) {
            // 这是一个真正的编程错误(重排器引用了一个不存在的项), 而不是一种处境。
            // 抛出去而不是静默跳过 —— 静默跳过的后果是"她的计划表少了一项却没人知道"
            throw new IllegalArgumentException(
                    "要" + what + "的计划项 " + id.value() + " 不在当前计划表里。"
                            + "当前有: " + working.keySet());
        }
        return item;
    }

    /**
     * 记一个已完成/已放弃的项。
     *
     * <p>由 runtime 在活动结束时调用。它产生的是<b>新版本</b>还是就地改状态？
     * <b>新版本</b> —— 因为"她 13:05 做完了作业"是一个关于未来的重新安排
     * （未来不再包含那一项了）, 而不是一个与计划无关的旁注。
     */
    public PlanRevision conclude(Instant at, PlanItemId id, PlanLifecycle outcome, String note) {
        Objects.requireNonNull(outcome, "结束状态不能为空");
        if (!outcome.terminated()) {
            throw new IllegalArgumentException(
                    "conclude 只能用于终态, 收到 " + outcome.label()
                            + " —— 把一项置回 PENDING/ACTIVE 会让历史无法解释");
        }
        PlanItem item = current.find(id).orElseThrow(() -> new IllegalArgumentException(
                "要结束的计划项 " + id.value() + " 不在当前计划表里"));
        PlanMutation mutation = switch (outcome) {
            case DONE -> new PlanMutation.Remove(id, "完成: " + note);
            case CANCELLED -> new PlanMutation.Remove(id, "放弃: " + note);
            default -> new PlanMutation.Remove(id, note);
        };

        long nextNumber = current.revisionNumber() + 1;
        Map<PlanItemId, PlanItem> working = new LinkedHashMap<>();
        current.items().forEach(i -> working.put(i.id(), i));
        working.put(id, item.withLifecycle(outcome).withNote(note));

        PlanRevision next = new PlanRevision(nextNumber, current.revisionId(),
                item.intent().description() + " → " + outcome.label(), at,
                working.values(), current.constraints(), List.of(mutation));
        remember(next);
        current = next;
        notifyObservers(next);
        return next;
    }

    private void remember(PlanRevision revision) {
        revisionHistory.add(revision);
        while (revisionHistory.size() > IN_MEMORY_HISTORY_LIMIT) {
            // 只从内存里丢最老的 —— 数据库里那份不受影响。见 IN_MEMORY_HISTORY_LIMIT
            revisionHistory.remove(0);
        }
    }

    // ─────────────────────────── 观察者 ───────────────────────────

    /** 计划表的观察者。用来把"计划变了"变成世界事件, 而不让计划表依赖事件总线。 */
    public interface Observer {

        /** 产生了一个新版本。 */
        void onRevision(PlanRevision revision);

        /**
         * 某一项的状态变了。
         *
         * <p><b>顺序: 先 {@link #onRevision}, 后本方法。</b>
         * 消费方读下来会先看到"这是一次重排, 理由是……", 再看到它具体动了哪些项 ——
         * 反过来的话, 每条迁移都要自己去找它属于哪一次重排。
         *
         * <p>与 {@link #onRevision} 一样, 本方法与版本事件<b>描述的是同一件事</b>:
         * {@code plan.revision-created.v1} 的载荷里已经带了改动清单与理由。
         * 默认实现 {@code PlanEventPublisher} 因此只记数、不发事件 ——
         * 两条描述同一件事的记录迟早会不一致, 而不一致时无法判断该信哪条。
         *
         * @param why 这次迁移为什么发生。取的是<b>那一版的 reason</b>
         *            （面向人的第一人称句子）, 不是某个枚举 ——
         *            见 {@code ReplanProposal#reason()} 关于"她必须说得出为什么"的论证
         */
        void onItemTransition(PlanItemId id, PlanLifecycle from, PlanLifecycle to, String why);
    }

    public void addObserver(Observer observer) {
        Objects.requireNonNull(observer, "观察者不能为空");
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    /** 一版相对上一版, 某一项的状态变了。 */
    public record ItemTransition(PlanItemId id, PlanLifecycle from, PlanLifecycle to) {

        public ItemTransition {
            Objects.requireNonNull(id, "迁移必须指名是哪一项");
            Objects.requireNonNull(from, "迁移必须有起点状态");
            Objects.requireNonNull(to, "迁移必须有终点状态");
        }

        public String describe() {
            return id.value() + " " + from + " → " + to;
        }
    }

    /**
     * {@code revision} 相对它的上一版, 哪些项的状态变了。
     *
     * <h2>为什么按 id 比, 而不是按"改动清单"推</h2>
     * 改动清单（{@link PlanMutation}）说的是<b>意图</b> —— "把写作业推后"。
     * 而状态迁移是那个意图<b>落地的形状</b> —— 旧项 PENDING → SUPERSEDED、
     * 新项以新 id 出现。两者在 Move / Replace 这些"旧项让位、新项顶上"的
     * 改动上并不一一对应, 而从意图反推形状是一段容易写错的逻辑。
     *
     * <p>直接比两个版本更笨, 但它是<b>自明的</b>: 它对每一种改动类型都成立,
     * 包括将来新增的第七种 —— 而不用改动清单的人记得同时更新这里。
     *
     * <h2>只报"同一个 id 的前后状态不同"</h2>
     * 本版<b>新建</b>的项不在结果里: 它没有"上一版的状态", 所以它不是一次迁移,
     * 是一次出现。把它算成迁移会让"她今天改了几次主意"里混进"她今天安排了几件事" ——
     * 而那是两个不同的数（前者是 {@code plan.revision-created} 的语义,
     * 后者是 {@code plan.item-scheduled} 的）。
     *
     * <p>其余情况一律是迁移, 包括 {@code PENDING → SUPERSEDED}（被推后）、
     * {@code ACTIVE → DONE}（做完了）、{@code PENDING → CANCELLED}（不做了）。
     */
    public List<ItemTransition> transitionsInto(PlanRevision revision) {
        Objects.requireNonNull(revision, "要比较的版本不能为空");
        Optional<PlanRevision> previous =
                revision.previousRevisionId().flatMap(this::revisionById);
        if (previous.isEmpty()) {
            // 最初的那一版没有"上一版"可比。注意这里也可能是因为上一版已经被
            // 内存上限挤掉了 —— 两种情况的处置一样(报不了迁移), 但原因不同,
            // 所以日志里要能分开。见 IN_MEMORY_HISTORY_LIMIT
            if (revision.previousRevisionId().isPresent()) {
                log.debug("[PlanBoard] {} 的上一版已不在内存里, 本次报不出状态迁移",
                        revision.revisionId());
            }
            return List.of();
        }

        List<ItemTransition> out = new ArrayList<>();
        for (PlanItem item : revision.items()) {
            PlanItem before = previous.get().find(item.id()).orElse(null);
            if (before == null) {
                continue;   // 本版新建的项 —— 是"出现", 不是"迁移"
            }
            if (before.lifecycle() != item.lifecycle()) {
                out.add(new ItemTransition(item.id(), before.lifecycle(), item.lifecycle()));
            }
        }
        return List.copyOf(out);
    }

    private void notifyObservers(PlanRevision revision) {
        for (Observer observer : List.copyOf(observers)) {
            try {
                observer.onRevision(revision);
            } catch (RuntimeException e) {
                // 一个坏观察者不该让她的计划表无法更新。与 ActionFabric 对第三方能力的态度一致
                log.error("[PlanBoard] 观察者 {} 处理新版本时抛异常",
                        observer.getClass().getName(), e);
            }
            // 再逐项播报状态迁移 —— 见 Observer#onItemTransition 关于顺序的说明
            for (PlanBoard.ItemTransition t : transitionsInto(revision)) {
                try {
                    observer.onItemTransition(t.id(), t.from(), t.to(), revision.reason());
                } catch (RuntimeException e) {
                    log.error("[PlanBoard] 观察者 {} 处理状态迁移 {}({} → {}) 时抛异常",
                            observer.getClass().getName(), t.id().value(), t.from(), t.to(), e);
                }
            }
        }
    }

    /** 只给测试用 —— 重置成"她还没有任何计划"。 */
    public void reset() {
        revisionHistory.clear();
        triggered.clear();
        current = PlanRevision.initial(Instant.EPOCH, List.of(), "还没有任何计划");
        revisionHistory.add(current);
    }

    /** 已触发过的项数 —— 诊断用。 */
    public int triggeredCount() {
        return triggered.size();
    }

    /**
     * 到现在为止, 她有多少时间被计划占用了。
     *
     * <p>给"她今天是不是排太满了"这类判断用。注意它算的是<b>并集</b>而不是简单相加 ——
     * 简单相加在窗口重叠时会算出大于 24 小时的结果。
     */
    public Duration plannedSpan(Instant from, Instant to) {
        List<PlanItem> live = current.liveItems().stream()
                .filter(i -> i.window().overlaps(TimeWindow.of(from, to)))
                .toList();
        if (live.isEmpty()) {
            return Duration.ZERO;
        }
        List<TimeWindow> windows = new ArrayList<>();
        for (PlanItem item : live) {
            item.window().intersect(TimeWindow.of(from, to)).ifPresent(windows::add);
        }
        windows.sort(java.util.Comparator.comparing(TimeWindow::start));
        Duration total = Duration.ZERO;
        Instant cursorStart = null;
        Instant cursorEnd = null;
        for (TimeWindow w : windows) {
            if (cursorStart == null) {
                cursorStart = w.start();
                cursorEnd = w.end();
            } else if (w.start().isAfter(cursorEnd)) {
                total = total.plus(Duration.between(cursorStart, cursorEnd));
                cursorStart = w.start();
                cursorEnd = w.end();
            } else if (w.end().isAfter(cursorEnd)) {
                cursorEnd = w.end();
            }
        }
        if (cursorStart != null) {
            total = total.plus(Duration.between(cursorStart, cursorEnd));
        }
        return total;
    }

    public String describe() {
        return "PlanBoard[" + current.revisionId() + ", 共 " + revisionHistory.size()
                + " 版, 现存 " + current.liveItems().size() + " 项]";
    }
}
