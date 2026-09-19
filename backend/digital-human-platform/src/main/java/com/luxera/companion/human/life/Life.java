package com.luxera.companion.human.life;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.activity.ActivityState;
import com.luxera.companion.human.life.plan.DefaultPlanScheduler;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanReplanner;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.PlanScheduler;
import com.luxera.companion.human.life.plan.PlanTrigger;
import com.luxera.companion.human.life.plan.PlanValidator;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.ReplanProposal;
import com.luxera.companion.human.life.plan.ReplanningContext;
import com.luxera.companion.human.life.plan.event.PlanEventPublisher;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * V2.2 §8.4 —— <b>她的"生活": 计划表 + 正在做的事</b>。
 *
 * <h2>它管什么</h2>
 * <pre>
 *   Life
 *    ├── PlanBoard     她的计划表（未来与过去的所有版本）
 *    ├── PlanScheduler 到点提醒（把"该做下一项了"变成一条刺激）
 *    ├── Activity      她<b>此刻</b>正在做的那一件事 —— 最多一个
 *    └── 已结束的活动   她今天做过的事（历史, 不可改）
 * </pre>
 *
 * <h2>它是计划表<b>唯一</b>的写入口 —— 这条比什么都重要</h2>
 * 用户要求打断必须"真的改变计划表, 让 agent 重新思考重排计划表"。
 * 要做到这一点, 前提是<b>所有</b>对计划表的改写都经过同一个关口,
 * 而这个关口上挂着校验、事件发布和审计。如果 Mind 一处、
 * 某个定时任务另一处、LLM 再来一处, 那么"她为什么改主意了"就有三个答案,
 * 而且它们随时可能不一致。
 *
 * <pre>
 *   Mind 决定"该重排了"
 *        ↓
 *   Life.replan(ctx, replanner)
 *        ├── ① 重排器提出改动清单（ReplanProposal）
 *        ├── ② 试算（PlanValidator.materialize）—— 不碰真的计划表
 *        ├── ③ 校验（PlanValidator.accept）—— 不通过就记一条可观测的失败
 *        └── ④ 校验通过 → PlanBoard.apply(同一份改动清单) ← 唯一的落地点
 * </pre>
 *
 * <p>注意 ④ 用的是 ① 的那份<b>原始改动清单</b>, 而不是 ② 算出来的那个版本。
 * 这是刻意的: 试算只为校验, 它的结果<b>被丢掉</b>。落地的永远只有一份
 * —— 于是"被校验的"和"被采纳的"是同一份意图, 不存在两个版本互相漂移的可能。
 *
 * <blockquote>
 * {@code PlanReplanner} 的注释里写这一步由 {@code HumanActor} 做。实现时收进了
 * 本类, 理由: {@code HumanActor} 是<b>驱动者</b>（跑时钟、调 Mind），
 * 而"谁有权改写她的未来"应当是一个能被单独测试、且不依赖运行时装配的对象。
 * </blockquote>
 *
 * <h2>它遵守 Human ⟂ World 的边界</h2>
 * 本类持有 {@link EventFabric} —— 那是 {@code boundary/} 里的<b>共享接口</b>,
 * 是允许的（{@code PlanValidator} 同样持有）。它<b>不</b>持有、也不 import
 * 任何 {@code world/} 里的类型。这一条由 §8.2.7 的 ArchUnit 规则强制。
 *
 * <h2>她的身体只有一个 —— 这条物理事实在这里被强制</h2>
 * {@link #begin} 在已有一件事正在进行时会<b>拒绝</b>。这不是防御性编程,
 * 而是把"一个人同时只能做一件事"变成一个不可能被违反的约束。
 * 如果允许两件事同时 RUNNING, 那么所有"她此刻在做什么"的判断
 * （注意力多少、手机够不够得着、能不能被打断）都会有两个互相矛盾的答案。
 */
@Slf4j
public final class Life {

    private final String humanId;
    private final PlanBoard plan;
    private final PlanScheduler scheduler;
    private final PlanValidator validator;
    private final EventFabric fabric;
    private final Supplier<PlanningContext.HumanSnapshot> snapshot;

    /** 她此刻正在做的那一件事。{@code null} 表示她手头没事。 */
    private Activity current;

    /** 今天做过的每一件事, 按开始时间。已结束的不可改, 所以这是纯粹的追加日志。 */
    private final List<Activity> concluded = new ArrayList<>();

    private long replanAttempts;
    private long replanRejections;

    /**
     * @param humanId  这是谁的生活
     * @param fabric   事件通道。允许为 {@code null}（测试里不需要总线）——
     *                 但那样 {@code plan.item-due}、{@code plan.item-finished}
     *                 这些事件就只在日志里, 而<b>日志不是行为分析的数据源</b>
     * @param snapshot 她身体状态的只读视图（{@link PlanningContext.HumanSnapshot}）。
     *                 用 {@code Supplier} 而不是直接持有 {@code Body}, 是为了让
     *                 本类只依赖那个<b>窄接口</b> —— 它需要的是"她现在保暖值多少",
     *                 而不是"她的身体有哪些内部状态"
     */
    public Life(String humanId, EventFabric fabric,
                Supplier<PlanningContext.HumanSnapshot> snapshot) {
        this(humanId, fabric, snapshot, null, null);
    }

    /**
     * 带历史的她 —— <b>V2.2 §8.5.6 第 ② 步的落点</b>。
     *
     * <h2>为什么"恢复出来的一版计划"走的是构造参数, 而不是一个 load 方法</h2>
     * 因为一个 {@code loadPlan(revision)} 方法会被用在<b>错误的时刻</b>:
     * 它必须在发布器挂上之前被调用, 而"记得按那个顺序"是一件只能写在别人脑子里的事。
     * 走构造参数之后这件事变成结构性的 —— 装载与挂载在同一个方法体里,
     * 顺序由代码的书写顺序保证, 而不是由调用方的纪律保证:
     *
     * <pre>{@code
     *   this.plan = new PlanBoard(restoredPlan);          // ① 先装载 —— 此刻还没有观察者
     *   if (fabric != null) {
     *       new PlanEventPublisher(fabric).attachTo(plan); // ② 后挂载 —— 从此每一次变更都发事件
     *   }
     * }</pre>
     *
     * <p>顺序反了会怎样: {@code PlanBoard} 的构造器在装入 {@code initial} 时
     * <b>不通知观察者</b>(见那里的说明), 而如果发布器先挂上、装载走一个"设置当前版本"
     * 的方法, 那一次装载就会被当成一次真实的计划变更发出去。于是每次重启,
     * 世界都会收到一遍她生前的全部 {@code plan.revision-created.v1} 与
     * {@code plan.item-scheduled.v1} —— 行为分析会把它们算成她刚刚做的决定,
     * 而它们看起来完全正常。
     *
     * <h2>为什么活动也走构造参数</h2>
     * 同一件事的另一半: 库里若有一行 {@code RUNNING} 的活动, 而 {@code Life} 不知道它,
     * 她会以为自己手头没事 —— 于是 {@link #begin} 不再拒绝, 同一段时间会被做两次,
     * 而"她今天做了两件事"里有一件是假的。两个参数<b>必须一起来</b>:
     * 一版计划里那一项是 {@code ACTIVE}, 而它对应的活动就是这一条。
     *
     * @param restoredPlan     从库里恢复回来的一版计划。{@code null} 表示她没有历史 ——
     *                         与 {@code new PlanBoard()} 的"她还没有任何计划"是同一件事
     * @param restoredActivity 恢复回来的、正在进行的那一件事。{@code null} 表示她手头没事
     */
    public Life(String humanId, EventFabric fabric,
                Supplier<PlanningContext.HumanSnapshot> snapshot,
                PlanRevision restoredPlan, Activity restoredActivity) {
        if (humanId == null || humanId.isBlank()) {
            throw new IllegalArgumentException(
                    "Life 必须知道它是谁的 —— 计划表与活动日志都是 per-human 的状态, "
                            + "一个不知道归属的 Life 会让两个 agent 共用一张计划表");
        }
        this.humanId = humanId;
        this.fabric = fabric;
        this.snapshot = Objects.requireNonNull(snapshot,
                "生活必须能看到身体状态 —— 规划与重排都要靠它");

        // ① 先装载。PlanBoard 的构造器不通知观察者, 所以这一刻发生的事不会变成事件。
        this.plan = new PlanBoard(restoredPlan);

        // ② 恢复回来的那一件事。它不进 concluded —— 它还没结束, 而 concluded 是
        //    "已结束"的日志。它结束时会走 concludeCurrent, 那就正常进日志了。
        this.current = restoredActivity;

        this.scheduler = new DefaultPlanScheduler(plan);
        this.validator = new PlanValidator(fabric);

        if (fabric != null) {
            // ③ 后挂载。此后每一次计划表变更都会变成世界事件。
            //    它是 PlanBoard 与 EventFabric 之间唯一的桥 —— 计划表本身不认识事件总线
            new PlanEventPublisher(fabric).attachTo(plan);
        }
    }

    // ─────────────────────────── 只读视图 ───────────────────────────

    public String humanId() {
        return humanId;
    }

    public PlanBoard plan() {
        return plan;
    }

    public PlanScheduler scheduler() {
        return scheduler;
    }

    /** 她此刻在做什么。空表示她手头没事 —— 那是一个合法的、常见的状态。 */
    public Optional<Activity> currentActivity() {
        return Optional.ofNullable(current);
    }

    /** 她今天已经做完（或放弃）了哪些事, 按开始时间。 */
    public List<Activity> activityLog() {
        return List.copyOf(concluded);
    }

    /** 一段时间里她做过的事 —— 行为分析与前端时间轴用。 */
    public List<Activity> activitiesBetween(Instant from, Instant to) {
        Objects.requireNonNull(from, "时间段起点不能为空");
        Objects.requireNonNull(to, "时间段终点不能为空");
        return concluded.stream()
                .filter(a -> !a.startedAt().isBefore(from) && a.startedAt().isBefore(to))
                .toList();
    }

    // ─────────────────────────── 时间推动 ───────────────────────────

    /**
     * 把生活推进到 {@code now}: 该开始的项会在这里被"提醒"。
     *
     * <p>它做两件事: 问调度器"现在该触发哪些项", 然后为每一项发一条
     * {@code plan.item-due.v1}。
     *
     * <h3>为什么"到点了"是一条<b>刺激</b>而不是"她就开始做了"</h3>
     * 这是本设计里一个刻意的选择, 也是 {@code ItemDue} 实现 {@code SensoryEvent}
     * 而不是 {@code ScheduledEvent} 的原因: 闹钟响了<b>不等于</b>她立刻起身。
     * 她可能正忙着别的事, 于是"到点了"变成一条需要 Mind 处理的信号。
     *
     * <p>如果把"到点"直接实现成"把计划项置为 ACTIVE", 那么任何一条
     * 到点提醒都会无条件地打断她手上的事 —— 而那恰恰是用户否定的
     * "简单把当前计划 event 更新剩余时间然后立马执行一个计划 event" 的翻版。
     *
     * @return 这一次触发了哪几项。返回值保留 {@link PlanTrigger} 而不是
     *         {@code boolean}, 因为调用方（{@code PlanSchedulerJob}）常需要
     *         为日志与指标知道"刚才到底触发了什么", 以及迟到了多久
     */
    public List<PlanTrigger> advanceTo(Instant now) {
        Objects.requireNonNull(now, "推进必须带仿真时刻 —— 生活不读系统时钟");
        List<PlanTrigger> triggers = scheduler.dueAt(now);
        for (PlanTrigger trigger : triggers) {
            publish(PlanEvents.ItemDue.of(trigger));
        }
        if (!triggers.isEmpty()) {
            log.info("[Life/{}] {} 到点了, 发了 {} 条提醒（她此刻{}）", humanId, now,
                    triggers.size(),
                    current == null ? "手头没事" : "正在做「" + current.intent().description() + "」");
        }
        return triggers;
    }

    // ─────────────────────────── 开始与结束 ───────────────────────────

    /**
     * 她开始做一件事。
     *
     * @param intent     要做什么
     * @param at         开始的仿真时刻
     * @param planItemId 它对应计划表上的哪一项。{@code null} 表示这是计划表上没有的事
     *                   （"突然想给妈妈打个电话"）—— 那是真实发生的, 不该被禁掉
     * @throws IllegalStateException 她手头已经有一件事在做
     */
    public Activity begin(PlanIntent intent, Instant at, PlanItemId planItemId) {
        Objects.requireNonNull(intent, "要做的事不能为空");
        Objects.requireNonNull(at, "开始时刻不能为空");
        if (current != null) {
            throw new IllegalStateException(
                    "她正在做「" + current.intent().description() + "」, 不能同时开始「"
                            + intent.description() + "」—— 她的身体只有一个。"
                            + "要换事, 请先 concludeCurrent(...) 或 interruptCurrent(...)");
        }
        current = ActivityFactory.start(intent, at, planItemId);
        log.info("[Life/{}] {} 开始「{}」({})", humanId, at, intent.description(),
                current.getClass().getSimpleName());
        return current;
    }

    /**
     * 当前这件事做完了（或她主动停了）。
     *
     * <p>它同时改两处状态, 而这两处必须一起改:
     * <ol>
     *   <li>活动收尾 —— 记下结束时刻与当时做到几成;</li>
     *   <li>计划表产生新版本 —— 那一项标记为 {@code DONE}（做完）或
     *       {@code CANCELLED}（放弃）。</li>
     * </ol>
     *
     * <p>两处分开改的后果是可预见的: 活动结束了而计划项还挂着 ACTIVE,
     * 于是调度器下一次 tick 会重新提醒她"该开始写作业了" —— 而她已经写完了。
     *
     * @return 收尾的那条活动。她手头本来就没做事时返回空
     */
    public Optional<Activity> concludeCurrent(Instant at, ActivityState outcome, String note) {
        Objects.requireNonNull(at, "收尾必须带时刻");
        Objects.requireNonNull(outcome, "收尾必须说明结局 —— 做完和放弃不是一回事");
        if (current == null) {
            log.debug("[Life/{}] {} 没有正在做的事, 收尾请求被忽略", humanId, at);
            return Optional.empty();
        }

        Activity finished = current.conclude(at, outcome, note);
        current = null;
        concluded.add(finished);

        // 计划表那一项也要收尾。做完 → DONE, 放弃 → CANCELLED
        finished.planItemId().ifPresent(itemId -> {
            PlanLifecycle target = outcome == ActivityState.ABANDONED
                    ? PlanLifecycle.CANCELLED
                    : PlanLifecycle.DONE;
            try {
                plan.conclude(at, itemId, target, note == null ? "" : note);
            } catch (RuntimeException e) {
                // 计划项可能已经在一次重排里被替代掉了（SUPERSEDED 不能再迁移）——
                // 那不是故障: 她做完了一件"计划表上已经没有的事", 而这完全可能发生
                log.info("[Life/{}] 计划项 {} 收尾时未能迁移（多半已被重排替代）: {}",
                        humanId, itemId.value(), e.getMessage());
            }
        });

        // 只有真的对应一个计划项的完成才发 finished —— 一件计划外的事没有"计划偏差"可言
        if (finished.planItemId().isPresent()) {
            plan.find(finished.planItemId().get()).ifPresent(item ->
                    publish(PlanEvents.ItemFinished.of(item, finished.startedAt(), at, note)));
        }

        log.info("[Life/{}] {} 「{}」{}（做了 {} 分钟）", humanId, at,
                finished.intent().description(), outcome.label(),
                finished.elapsedAt(at).toMinutes());
        return Optional.of(finished);
    }

    /**
     * 当前这件事被打断了。
     *
     * <h3>它做的三件事, 顺序不能变</h3>
     * <ol>
     *   <li>活动收尾（{@code CONCLUDED}）—— 记下"到被打断为止做到几成";</li>
     *   <li>发 {@code plan.item-interrupted.v1} —— 这是重排的<b>触发信号</b>,
     *       它的消费方是 {@code PlanReplanner}, 不是某个"暂停"处理器;</li>
     *   <li>把被打断的那一项留在计划表里（<b>不</b>动它）—— 怎么处置它是重排的事。</li>
     * </ol>
     *
     * <p>第 3 条是用户那条要求的落点: 打断<b>不</b>意味着"更新剩余时间然后继续"。
     * 所以本方法不碰计划表, 它只报告"发生了什么"。接下来在她手上的那件事
     * <b>是继续、是挪到后面、还是干脆删掉</b>, 由 {@link #replan} 重新想一遍 ——
     * 而它可能得出"今天不写了"这个结论。
     *
     * @param byStimulusSummary 一句人能读懂的"被什么打断"（"房间里的温度降下来了"）。
     *                          它会进 LLM context 与行为分析报告 ——
     *                          <b>不要传事件类型 id</b>
     * @param stimulusUrgency   打断它的那条刺激有多急（0..1）
     * @return 发出去的那条中断事件。她手头本来就没做事时返回空
     */
    public Optional<PlanEvents.ItemInterrupted> interruptCurrent(
            Instant at, String byStimulusSummary, double stimulusUrgency) {
        Objects.requireNonNull(at, "打断必须带时刻");
        Objects.requireNonNull(byStimulusSummary, "必须说明是被什么打断的");
        if (current == null) {
            return Optional.empty();
        }
        PlanItemId itemId = current.planItemId().orElse(null);
        if (itemId == null) {
            // 计划外的事被打断: 没有计划项可中断, 但活动仍然收尾 ——
            // 否则她会永远停在"正在做一件不该做的事"上
            concludeCurrent(at, ActivityState.CONCLUDED, "被「" + byStimulusSummary + "」打断");
            return Optional.empty();
        }

        Optional<com.luxera.companion.human.life.plan.PlanItem> item = plan.find(itemId);
        concludeCurrent(at, ActivityState.CONCLUDED, "被「" + byStimulusSummary + "」打断");
        if (item.isEmpty()) {
            log.warn("[Life/{}] 被打断的项 {} 已不在计划表里, 无法发中断事件",
                    humanId, itemId.value());
            return Optional.empty();
        }

        PlanEvents.ItemInterrupted event =
                PlanEvents.ItemInterrupted.of(item.get(), at, byStimulusSummary, stimulusUrgency);
        publish(event);
        log.info("[Life/{}] {}", humanId, event.describe());
        return Optional.of(event);
    }

    // ─────────────────────────── 重排 ───────────────────────────

    /**
     * 重新想一遍"接下来怎么办", 并把想的结果落到计划表上。
     *
     * <p>这是本类存在的核心理由 —— 见类注释里那张四步流程图。
     * 用户对打断的全部要求最终都收敛到这一个方法上。
     *
     * @param context   重排所需要的全部事实（见 {@link ReplanningContext} 关于
     *                  "它刻意不含什么"的说明）
     * @param replanner 谁来想。生产环境通常是规则重排器; LLM 不可用时也必须有一个
     * @return 落地后的新版本。没通过校验或者提案没有实质改动时返回空
     */
    public Optional<PlanRevision> replan(ReplanningContext context, PlanReplanner replanner) {
        Objects.requireNonNull(context, "重排必须带上下文");
        Objects.requireNonNull(replanner, "重排必须有一个重排器 —— 一个都没有的系统会变成不会重排的人");
        replanAttempts++;

        // ① 提出改动清单
        ReplanProposal proposal = replanner.replan(context);

        // ② 试算 —— 只为校验。它的结果会被丢掉, 不发布也不落库
        PlanRevision candidate = proposal.materialize(plan.current(), context.now());

        // ③ 校验。不通过时 PlanValidator 会记一条可观测的失败事件
        PlanningContext planning = context.planningContext();
        Optional<PlanRevision> accepted = validator.accept(candidate, planning,
                proposal.proposedBy(), snapshotOf(candidate));
        if (accepted.isEmpty()) {
            replanRejections++;
            // 校验没过, 计划表保持原样。注意这里不抛异常: 一次被拒绝的重排
            // 是一个<正常结果>, 不是故障 —— 抛出去会让调用方的整条 tick 断掉
            return Optional.empty();
        }

        // ④ 落地。用①那份原始清单, 而不是②算出来的版本
        if (proposal.mutations().isEmpty()) {
            log.debug("[Life/{}] 重排器（{}）认为没什么要改的, 不产生新版本 —— "
                            + "她没有被这次打扰改变主意",
                    humanId, proposal.proposedBy());
            return Optional.empty();
        }
        PlanRevision applied = plan.apply(context.now(), proposal.reason(), proposal.mutations());
        log.info("[Life/{}] 重排完成（{}）: {}", humanId, proposal.proposedBy(), proposal.reason());
        return Optional.of(applied);
    }

    /**
     * 被拒绝的那份计划长什么样 —— 进 {@code ValidationFailed} 的载荷。
     *
     * <p>为什么必须带上它: "LLM 错在哪"只有看到它提出的东西才能回答,
     * 而那是改进 prompt、改进工具描述、改进校验器的<b>唯一</b>输入。
     * 丢掉被拒绝的方案, 就等于丢掉了唯一的反馈信号。
     */
    private Map<String, Object> snapshotOf(PlanRevision candidate) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("revisionId", candidate.revisionId());
        snapshot.put("liveItemCount", candidate.liveItems().size());
        snapshot.put("items", candidate.liveItems().stream()
                .map(i -> i.window() + " " + i.intent().description())
                .toList());
        return snapshot;
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 发一条事件, 失败只记日志。
     *
     * <p>与 {@code PlanBoard} 对观察者的态度一致: 一条发不出去的事件
     * 不该让她的一天停下来。但失败必须<b>有声</b> —— 它会留下 ERROR 日志,
     * 因为它意味着计划表与行为分析的数据已经不一致了, 而那不会自己恢复。
     */
    private void publish(com.luxera.companion.boundary.event.WorldEvent event) {
        if (fabric == null) {
            log.debug("[Life/{}] 没有事件总线, {} 只留在日志里", humanId, event.typeId());
            return;
        }
        try {
            fabric.publish(event);
        } catch (RuntimeException e) {
            log.error("[Life/{}] 发布 {} 失败 —— 她的一天照常继续, "
                            + "但这次变化没进事件流, 行为分析会看到一个缺口",
                    humanId, event.typeId(), e);
        }
    }

    /** 她身体状态此刻的只读视图 —— 重排与校验都要看它。 */
    public PlanningContext.HumanSnapshot humanSnapshot() {
        return snapshot.get();
    }

    /** 重排过几次、被拒绝过几次 —— 诊断面板用。 */
    public long replanAttempts() {
        return replanAttempts;
    }

    public long replanRejections() {
        return replanRejections;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Life[").append(humanId).append(']');
        sb.append("\n  此刻: ").append(current == null
                ? "手头没事" : "正在做「" + current.intent().description() + "」");
        sb.append("\n  计划表: ").append(plan.describe());
        sb.append("\n  今天做过 ").append(concluded.size()).append(" 件事");
        sb.append("\n  重排 ").append(replanAttempts).append(" 次");
        if (replanRejections > 0) {
            sb.append("（其中 ").append(replanRejections).append(" 次未通过校验）");
        }
        sb.append("\n  身体: ").append(humanSnapshot().summary());
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Life[" + humanId + ", " + (current == null ? "空闲" :
                current.intent().description()) + ", " + concluded.size() + " 件已做]";
    }
}
