package com.luxera.companion.human.life.plan;

import com.luxera.companion.registry.DomainType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.2 —— <b>"她打算在某个时间段做某件事"</b>。
 *
 * <h2>它是不可变的, 这一点是硬要求</h2>
 * 设计文档 §3.5.2 里给的是一个<b>可变</b>的 {@code final class}（有私有字段和
 * 隐含的 setter 语义）。本实现刻意改成不可变的 record, 理由是它必须成立的那条链:
 *
 * <pre>
 *   PlanRevision 是"某一刻她对未来的完整安排"
 *            ↓ 要能回答"她 12:15 时计划的是什么"
 *   Revision 必须能长期保存且不被后续修改影响
 *            ↓
 *   Revision 里的每一项都必须不可变
 *            ↓
 *   PlanItem 不可变
 * </pre>
 *
 * <p>如果 {@code PlanItem} 可变, 那么"把写作业推到 12:25"就只需改一个字段 —— 看起来
 * 省事, 但 Revision 17 和 Revision 18 会指向<b>同一个对象</b>, 于是 Revision 17 的内容
 * 随着时间一起变成了 Revision 18 的样子。<b>用户要求"真的改变计划表"，
 * 而一个会被后来修改污染的历史, 恰恰回答不了"她当时计划的是什么"。</b>
 *
 * <p>所以: 任何改动都产生<b>新的</b> {@link PlanItem}（新的 {@link PlanItemId}）,
 * 旧的被标成 {@link PlanLifecycle#SUPERSEDED} 留在历史里。见 {@link PlanMutation}。
 *
 * <h2>为什么 {@link #expectedDuration} 和 {@link #window} 是两个字段</h2>
 * 它们看起来冗余（{@code window.duration()} 就是时长）, 但表达的是不同的事:
 * <table border="1">
 *   <tr><th></th><th>{@code window}</th><th>{@code expectedDuration}</th></tr>
 *   <tr><td>回答</td><td>"这段时间<b>她归这一项</b>"</td><td>"她觉得<b>要做多久</b>"</td></tr>
 *   <tr><td>用途</td><td>调度、冲突检测</td><td>计划与实际对比</td></tr>
 *   <tr><td>谁给的</td><td>排定结果</td><td>{@link PlanIntent#expectedDuration()}</td></tr>
 * </table>
 *
 * <p>一个计划窗口可以比预计时长长（留了缓冲）。行为分析要能看出
 * <b>她是给自己留了余量, 还是每次都低估</b> —— 而那需要两个数都在。
 *
 * <h2>{@link #fixed()} 与 {@link PlanIntent#movableInTime()}</h2>
 * 两者都为真时这一项才真正不可移动。前面那个是"这一次安排不能动"（14:00 的考试）,
 * 后面那个是"这个意图天生不能动"。分开的理由见 {@code PlanIntent.movableInTime()} 的说明。
 *
 * @param id               身份。跨 Revision 的引用靠它 —— 见 {@link PlanItemId}
 * @param intent           要做什么（多态 —— 见 {@link PlanIntent}）
 * @param window           什么时候做（时间段, 不是时刻）
 * @param expectedDuration 预计多久（与 window 时长可能不同, 见类注释）
 * @param constraints      这一项自己附加的约束（全局约束在 {@link PlanRevision} 上）
 * @param priority         重要性
 * @param lifecycle        生命周期位置
 * @param origin           谁安排的
 * @param dependencies     依赖哪些别的项先完成
 * @param fixed            这一次安排是否不可移动
 * @param createdInRevision 这一项是在第几个 Revision 里被创建的。
 *                          用于回答"它是原计划的, 还是重排时新插进来的"
 * @param note             一句话备注（完成情况、放弃原因…）, 进行为分析时间轴
 */
public record PlanItem(
        PlanItemId id,
        PlanIntent intent,
        TimeWindow window,
        Duration expectedDuration,
        List<PlanConstraint> constraints,
        PlanPriority priority,
        PlanLifecycle lifecycle,
        PlanOrigin origin,
        List<PlanItemId> dependencies,
        boolean fixed,
        long createdInRevision,
        String note) {

    public PlanItem {
        Objects.requireNonNull(id, "计划项必须有身份 —— 跨 Revision 的引用全靠它");
        Objects.requireNonNull(intent, "计划项必须有意图 —— 没有意图的计划项无法被描述或执行");
        Objects.requireNonNull(window, "计划项必须有时间窗口 —— 这是它与'待办事项'的根本差别");
        Objects.requireNonNull(priority, "优先级不能为空 —— 用 PlanPriority.DEFAULT 表达'默认'");
        Objects.requireNonNull(lifecycle, "生命周期状态不能为空");
        Objects.requireNonNull(origin, "来源不能为空 —— 用 PlanOrigin.SELF 表达'她自己安排的'");
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        note = note == null ? "" : note;

        if (expectedDuration == null) {
            expectedDuration = intent.expectedDuration();
        }
        if (expectedDuration.isNegative() || expectedDuration.isZero()) {
            throw new IllegalArgumentException(
                    "预计时长必须为正, 收到 " + expectedDuration
                            + " —— 一个预计零时长的意图会让'她今天安排了 12 件事'这类统计失去意义");
        }
        if (dependencies.contains(id)) {
            throw new IllegalArgumentException(
                    "计划项 " + id + " 依赖它自己 —— 一个自环会让依赖检查要么死循环, "
                            + "要么被当成'永远不满足'而静默地永不执行");
        }
    }

    // ─────────────────────────── 创建 ───────────────────────────

    /**
     * 排定一个新项。
     *
     * <p>默认是 {@link PlanLifecycle#PENDING}、{@link PlanOrigin#SELF}、
     * {@link PlanPriority#DEFAULT} —— 也就是最常见的形状: 她自己安排的、
     * 重要程度普通、还没到时间。
     */
    public static PlanItem schedule(PlanIntent intent, TimeWindow window) {
        Objects.requireNonNull(intent, "意图不能为空");
        return new PlanItem(PlanItemId.generate(), intent, window, intent.expectedDuration(),
                List.of(), PlanPriority.DEFAULT, PlanLifecycle.PENDING, PlanOrigin.SELF,
                List.of(), false, 0, "");
    }

    public static PlanItem schedule(PlanIntent intent, TimeWindow window, PlanPriority priority) {
        return schedule(intent, window).withPriority(priority);
    }

    // ─────────────────────────── 派生（全部返回新对象） ───────────────────────────

    public PlanItem withWindow(TimeWindow newWindow) {
        Objects.requireNonNull(newWindow, "新的时间窗口不能为空");
        return new PlanItem(id, intent, newWindow, expectedDuration, constraints, priority,
                lifecycle, origin, dependencies, fixed, createdInRevision, note);
    }

    public PlanItem withLifecycle(PlanLifecycle newLifecycle) {
        Objects.requireNonNull(newLifecycle, "新的生命周期状态不能为空");
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                newLifecycle, origin, dependencies, fixed, createdInRevision, note);
    }

    public PlanItem withPriority(PlanPriority newPriority) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, newPriority,
                lifecycle, origin, dependencies, fixed, createdInRevision, note);
    }

    public PlanItem withOrigin(PlanOrigin newOrigin) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                lifecycle, newOrigin, dependencies, fixed, createdInRevision, note);
    }

    public PlanItem withNote(String newNote) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                lifecycle, origin, dependencies, fixed, createdInRevision, newNote);
    }

    public PlanItem withConstraints(List<PlanConstraint> extra) {
        List<PlanConstraint> merged = new java.util.ArrayList<>(constraints);
        merged.addAll(extra);
        return new PlanItem(id, intent, window, expectedDuration, merged, priority,
                lifecycle, origin, dependencies, fixed, createdInRevision, note);
    }

    public PlanItem withDependencies(List<PlanItemId> deps) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                lifecycle, origin, deps, fixed, createdInRevision, note);
    }

    public PlanItem withCreatedInRevision(long revisionNumber) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                lifecycle, origin, dependencies, fixed, revisionNumber, note);
    }

    public PlanItem withFixed(boolean isFixed) {
        return new PlanItem(id, intent, window, expectedDuration, constraints, priority,
                lifecycle, origin, dependencies, isFixed, createdInRevision, note);
    }

    /**
     * 复制成<b>新的一项</b> —— 新的 id, 其余照抄。
     *
     * <p>这是 {@link PlanMutation.Move}、{@link PlanMutation.Replace} 内部用的关键操作。
     * 用户要求"把写作业的启动时间设定为穿衣服执行结束的时间"时, 数据上发生的事情是:
     * 老的写作业项（id=item-3）被标成 {@link PlanLifecycle#SUPERSEDED},
     * 而新版本里的写作业是 <b>id=item-7</b>。
     *
     * <p><b>为什么不复用 id 只改窗口</b>: 那样 Revision 17 里的 item-3 和 Revision 18 里的
     * item-3 会是"同一个 id 指两个不同的时间段", 于是"她 12:15 计划几点写作业"
     * 这个问题只能靠"查当时那个 Revision 里的对象"来回答 —— 而那个对象如果被就地改过,
     * 答案就错了。新的 id 让"移动"这件事在数据上不可否认地发生过。
     *
     * <p>{@code note} 会被带上, 并追加一句说明这次拷贝的来由。
     */
    public PlanItem copyAsNew(String why) {
        String carried = note.isEmpty() ? "" : note + " | ";
        return new PlanItem(PlanItemId.generate(), intent, window, expectedDuration, constraints,
                priority, PlanLifecycle.PENDING, origin, dependencies, fixed, createdInRevision,
                carried + why);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public boolean isActive() {
        return lifecycle == PlanLifecycle.ACTIVE;
    }

    public boolean isPending() {
        return lifecycle == PlanLifecycle.PENDING;
    }

    public boolean isTerminated() {
        return lifecycle.terminated();
    }

    /** 这一项在给定时刻是否"就是她该在做的事"。 */
    public boolean covers(Instant moment) {
        return lifecycle.occupiesFuture() && window.contains(moment);
    }

    /** 现在能不能改动它的时间。 */
    public boolean movable() {
        return !fixed && intent.movableInTime() && lifecycle == PlanLifecycle.PENDING;
    }

    /**
     * 到点了吗（给定时刻是否应该触发它）。
     *
     * <p>注意判断里含 {@link PlanLifecycle#PENDING} —— 已经 ACTIVE 或已结束的项
     * 不该被重复触发。少了这个条件, 调度器会在同一项上反复发"开始了"事件,
     * 而症状是"她一小时里开始了十七次写作业"。
     */
    public boolean dueAt(Instant moment) {
        return lifecycle == PlanLifecycle.PENDING && !window.start().isAfter(moment);
    }

    /** 这一项是不是已经过了它自己的时间窗却还没结束 —— 行为分析关心的"拖延"信号。 */
    public boolean overdueAt(Instant moment) {
        return lifecycle.occupiesFuture() && moment.isAfter(window.end());
    }

    public Optional<Duration> runningLateBy(Instant moment) {
        return overdueAt(moment) ? Optional.of(Duration.between(window.end(), moment)) : Optional.empty();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(id.value()).append("] ")
                .append(window).append(' ')
                .append(intent.description())
                .append(" (").append(lifecycle.label());
        if (!origin.isSelf()) {
            sb.append(", 来源=").append(origin.describe());
        }
        if (priority.level() != PlanPriority.DEFAULT.level()) {
            sb.append(", 优先级=").append(priority.describe());
        }
        sb.append(')');
        if (!note.isEmpty()) {
            sb.append(" —— ").append(note);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
