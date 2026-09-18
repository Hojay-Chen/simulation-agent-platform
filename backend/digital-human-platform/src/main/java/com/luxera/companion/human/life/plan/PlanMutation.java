package com.luxera.companion.human.life.plan;

import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.6 —— <b>重排对计划表做的改动</b>。
 *
 * <h2>为什么重排必须产出"一串改动"而不是"一个新计划表"</h2>
 * 用户的要求是"他是<b>真的改变了计划表</b>, 让 agent 重新思考重排计划表"。
 * 那么下一个问题就是: <b>改变了什么?</b>
 *
 * <p>如果重排只产出"新的计划表是这些", 那么"她改了什么主意"这个问题就只能靠
 * <b>比对两个 Revision 的差异</b>来回答。而差异比对在有 id 的情况下看起来可以做,
 * 实际上会丢信息:
 * <pre>{@code
 * 老: item-3 写作业 12:00-13:00
 * 新: item-7 运动   12:25-13:25
 *
 * 差异比对能得出: 删了一项, 加了一项
 * 但答不出: 她是"把写作业换成了运动"(REPLACE, 一个决定),
 *          还是"删掉写作业"+"另起炉灶加了运动"(REMOVE + INSERT, 两个决定)?
 * }</pre>
 *
 * <p>这两者的区别在行为分析里是本质的 —— 前者是"改主意", 后者是"放弃之后另找事做"。
 * <b>把意图显式记成 mutation, 就是拒绝让这两者退化成同一个差异。</b>
 *
 * <h2>为什么是 sealed interface</h2>
 * 六种改动是一个<b>封闭</b>集合: 任何"改变一个时间段安排"的操作, 拆到底都只能是
 * 这六种（插入/删除/移动/改时长/替换/不动）。这不是"假装封闭", 而是数学上封闭的。
 *
 * <p>{@code sealed} 带来的实际好处是可以穷尽匹配 —— {@link PlanBoard} 应用改动时
 * 写一个 {@code switch}, 编译器会保证六种都被处理。<b>将来加第七种时,
 * 编译会直接在那个 switch 上失败</b>, 而不是新改动被静默忽略。
 *
 * <h2>用户点名的五种 + 一种</h2>
 * 用户在否定"暂停/恢复"时, 逐条给出了他想要的语义。下表把他的原话映射到本类的成员:
 * <table border="1">
 *   <tr><th>用户原话</th><th>对应</th></tr>
 *   <tr><td>"把穿衣服 event 排在第一位"</td><td>{@link Insert}</td></tr>
 *   <tr><td>"把写作业直接从计划表删掉"</td><td>{@link Remove}</td></tr>
 *   <tr><td>"把写作业的启动时间设定为穿衣服执行结束的时间"</td><td>{@link Move}</td></tr>
 *   <tr><td>"插入去运动的计划 event"</td><td>{@link Insert}</td></tr>
 *   <tr><td>"想立马执行就把其触发事件调成现在"</td><td>{@link Move}（窗口起点 = now）</td></tr>
 *   <tr><td>（写作业 1h 改成 40min）</td><td>{@link Resize}</td></tr>
 *   <tr><td>（写作业 → 运动, 一个决定）</td><td>{@link Replace}</td></tr>
 *   <tr><td>"继续写作业"（收到无关紧要的消息时）</td><td>{@link KeepActive}</td></tr>
 * </table>
 */
public sealed interface PlanMutation {

    /**
     * 为什么做这个改动 —— <b>必填, 而且会一直跟着这条 mutation 进历史</b>。
     *
     * <p>这句话的最终去处是行为分析的时间轴和 LLM 的 context。用户追问
     * "你不是说要去跑步吗"时, 系统能给出的答案就来自这里。
     *
     * <p>所以它应该是<b>第一人称、面向人的</b>: "有点冷, 先加件衣服" 而不是
     * {@code "COLD_SENSATION"}。后者是把枚举搬回来了 —— 只不过换成了字符串。
     */
    String reason();

    /**
     * 这条改动影响到的计划项。
     *
     * <p>给诊断与审计用: "这条 Revision 一共动了三项, 分别是哪三项"。
     * {@link Insert} 返回的是被插入项的 id（它已经存在了 —— 见 {@link PlanItemId}
     * 关于"id 在落库前就存在"的说明）。
     */
    java.util.List<PlanItemId> affectedItems();

    /** 一行摘要, 给历史视图与日志用。 */
    String describe();

    // ─────────────────────────── 六种改动 ───────────────────────────

    /**
     * 插入一个新的计划项。
     *
     * <p>用户例子: 插入"穿衣服", 并把它的窗口起点设为 {@code now}（"想立马执行就把其
     * 触发事件调成现在"）。<b>插入本身不隐含"排在第一位"</b> —— "第一位"是它的窗口
     * 起点决定的。所以 {@link Insert} 只带一个 {@link PlanItem}, 而那个 item 的
     * {@code window} 已经表达了它什么时候做。
     */
    record Insert(PlanItem item, String reason) implements PlanMutation {

        public Insert {
            Objects.requireNonNull(item, "要插入的计划项不能为空");
            Objects.requireNonNull(reason, "插入必须有理由 —— 它要进历史");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(item.id());
        }

        @Override
        public String describe() {
            return "插入 " + item.window() + " " + item.intent().description() + " —— " + reason;
        }
    }

    /**
     * 删除一个计划项 —— 对应 {@link PlanLifecycle#CANCELLED}。
     *
     * <p>用户明确要求这个操作存在: "但其<b>也可以完全不再继续写作业</b>,
     * 把写作业直接从计划表删掉"。这一条是与"暂停/恢复"思路决裂的地方 ——
     * 暂停意味着"它还在那儿", 而删除意味着"她真的不打算做了"。
     *
     * <p>注意它<b>不是</b>物理删除: 那一项仍然留在历史 Revision 里,
     * 只是新 Revision 里不再包含它。历史永远可查。
     */
    record Remove(PlanItemId itemId, String reason) implements PlanMutation {

        public Remove {
            Objects.requireNonNull(itemId, "要删除的计划项 id 不能为空");
            Objects.requireNonNull(reason, "删除必须有理由 —— '她为什么放弃这件事'是最有价值的行为数据之一");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(itemId);
        }

        @Override
        public String describe() {
            return "删除 " + itemId.value() + " —— " + reason;
        }
    }

    /**
     * 改变一个计划项的时间窗口。
     *
     * <p>用户例子: "把写作业的启动时间设定为穿衣服执行结束的时间"。
     * 注意这是<b>改起点、保时长</b>（{@link TimeWindow#startingAt} 做的正是这件事）,
     * 而不是"改起点保终点" —— 用户说的是"启动时间", 时长 1 小时没有变。
     *
     * <p>与 {@link Resize} 分开的理由: 它们是两个不同的决定。"因为要穿衣服所以晚 10 分钟开始"
     * 是 Move; "今天的作业比预计的少, 只要 40 分钟" 是 Resize。
     * 合成一个"改窗口"的操作会让行为分析分不出这两者。
     *
     * <p>{@link #alsoResizeTo} 提供了组合的可能 —— 因为真实的决定有时确实兼有两者。
     */
    record Move(PlanItemId itemId, TimeWindow newWindow, String reason) implements PlanMutation {

        public Move {
            Objects.requireNonNull(itemId, "要移动的计划项 id 不能为空");
            Objects.requireNonNull(newWindow, "新的时间窗口不能为空");
            Objects.requireNonNull(reason, "移动必须有理由");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(itemId);
        }

        @Override
        public String describe() {
            return "移动 " + itemId.value() + " → " + newWindow + " —— " + reason;
        }
    }

    /**
     * 改变一个计划项的时长, 起点不变。
     *
     * <p>用户没有直接说这个操作, 但它是"重排"这个概念成立的必要条件 ——
     * 一个只能说"改时间"和"删掉"的重排器, 面对"今天作业少, 40 分钟就够"
     * 这种判断时只能选择把整项删掉重插, 而那会丢掉它的历史身份。
     */
    record Resize(PlanItemId itemId, Duration newLength, String reason) implements PlanMutation {

        public Resize {
            Objects.requireNonNull(itemId, "要改时长的计划项 id 不能为空");
            Objects.requireNonNull(newLength, "新的时长不能为空");
            if (newLength.isNegative() || newLength.isZero()) {
                throw new IllegalArgumentException(
                        "新的时长必须为正, 收到 " + newLength
                                + " —— 要表达'不做了'请用 Remove, 那才是它该有的形状");
            }
            Objects.requireNonNull(reason, "改时长必须有理由");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(itemId);
        }

        @Override
        public String describe() {
            return "改时长 " + itemId.value() + " → " + newLength + " —— " + reason;
        }
    }

    /**
     * 用一项替换另一项 —— 一个决定, 不是两个。
     *
     * <p>用户例子: "把写作业直接从计划表删掉, 然后插入去运动的计划 event"。
     * 用户把它描述成两步, 但语义上<b>它是一个决定</b>: "我今天不写作业了, 去运动"。
     *
     * <p>为什么这值得单独一个类型: 它让"她有几次是<b>换了个计划</b>而不是
     * <b>砍掉一个计划</b>"变成一个可以直接查询的量。而这个量在行为分析里
     * 对应一个很不同的人格特征 —— 前者是灵活, 后者是放弃。
     *
     * <p>被替换掉的那一项在新 Revision 里被标成 {@link PlanLifecycle#SUPERSEDED} 而
     * <b>不是</b> {@link PlanLifecycle#CANCELLED} —— 因为它从来没有被"取消"过,
     * 它被另一个决定替代了。这个区别正是 {@link PlanBoard} 保留全部项（而不是
     * 把结束的项剔除）的理由: 单个 Revision 里就能看出
     * "12:00-13:00 本来要写作业, 后来换成了运动"。
     */
    record Replace(PlanItemId removed, PlanItem inserted, String reason) implements PlanMutation {

        public Replace {
            Objects.requireNonNull(removed, "被替换掉的计划项 id 不能为空");
            Objects.requireNonNull(inserted, "用来替换的计划项不能为空");
            if (removed.equals(inserted.id())) {
                throw new IllegalArgumentException(
                        "替换的两端是同一个 id (" + removed.value() + ") —— "
                                + "这不是替换, 而是就地把一项改掉, 那正是本设计要避免的可变模型。"
                                + "如果你确实只想改时间, 请用 Move");
            }
            Objects.requireNonNull(reason, "替换必须有理由");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(removed, inserted.id());
        }

        @Override
        public String describe() {
            return "用 " + inserted.intent().description() + " 替换 " + removed.value() + " —— " + reason;
        }
    }

    /**
     * 什么也不改 —— 明确地"继续做当前这件事"。
     *
     * <p>这个"空操作"<b>必须存在</b>, 而且必须被记下来。理由:
     *
     * <p>用户描述的场景里有一条: 收到无关紧要的消息时她选择继续写作业。如果这种情形
     * 不产生任何 Revision, 那么"她今天被多少条通知打断过"这个问题就<b>无法回答</b> ——
     * 因为没被打断的那些次没有留下痕迹。
     *
     * <p>而"打扰了她但她选择忽略"与"根本没打扰到她"是行为分析里必须分开的两件事。
     * 前者说明她的抗干扰能力, 后者说明通知根本没送到。
     *
     * <p>注意它与"暂停/恢复"的本质区别（设计文档 §3.5.6 专门强调过）:
     * <b>这里没有任何 {@code remainingDuration} 状态</b>。计划表里始终是一个完整的
     * {@code [start_at, end_at]} 窗口。她的年龄、作息、疲劳累积都在继续走 ——
     * 只是她决定不改计划。
     */
    record KeepActive(PlanItemId itemId, String reason) implements PlanMutation {

        public KeepActive {
            Objects.requireNonNull(itemId, "要保留的计划项 id 不能为空");
            Objects.requireNonNull(reason, "保留也必须有理由 —— 它是'她被打扰但选择忽略'的证据");
        }

        @Override
        public java.util.List<PlanItemId> affectedItems() {
            return java.util.List.of(itemId);
        }

        @Override
        public String describe() {
            return "保持 " + itemId.value() + " 不变 —— " + reason;
        }
    }

    // ─────────────────────────── 工厂与工具 ───────────────────────────

    /**
     * 排一项"立刻开始"的插入。
     *
     * <p>对应用户那句"<b>想立马执行就把其触发事件调成现在</b>"。做成工厂是为了让这个
     * 意图在调用处一眼可见 —— 否则它会在每个重排点重复三行窗口构造代码。
     *
     * <p>{@code now} <b>必须由调用方传入</b>而不是在本方法里取系统时间: 重排要可复现
     * （见 {@link PlanningContext}）, 而读系统时间会让同一份输入产出不同的计划表。
     */
    static Insert startAt(java.time.Instant now, PlanIntent intent, Duration length, String reason) {
        Objects.requireNonNull(now, "立刻执行的'现在'必须由调用方给出 —— 重排里不能读系统时间");
        Objects.requireNonNull(intent, "意图不能为空");
        Objects.requireNonNull(length, "时长不能为空");
        PlanItem item = new PlanItem(PlanItemId.generate(), intent,
                TimeWindow.startingAt(now, length), intent.expectedDuration(),
                java.util.List.of(), PlanPriority.DEFAULT, PlanLifecycle.PENDING,
                PlanOrigin.SELF, java.util.List.of(), false, 0, "");
        return new Insert(item, reason);
    }

    /** 一条改动的可读摘要 —— 用于日志与行为分析报告。 */
    default String toTimelineEntry() {
        return describe();
    }

    /** 这条改动是不是"什么都没变" —— 用于统计"打扰但被忽略"的次数。 */
    default boolean isNoop() {
        return this instanceof KeepActive;
    }

    /** 这条改动删掉/替换掉了什么。用于回答"她今天放弃了哪几件事"。 */
    default Optional<PlanItemId> removedItem() {
        if (this instanceof Remove r) {
            return Optional.of(r.itemId());
        }
        if (this instanceof Replace r) {
            return Optional.of(r.removed());
        }
        return Optional.empty();
    }
}
