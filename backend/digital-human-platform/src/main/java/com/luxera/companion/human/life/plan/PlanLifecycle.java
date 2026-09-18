package com.luxera.companion.human.life.plan;

/**
 * V2.2 §3.5.2 —— 一个计划项在它自己的生命周期里的位置。
 *
 * <h2>为什么这里<b>可以</b>用枚举（而 {@code PlanType} 不可以）</h2>
 * 本设计的总原则是"不要用枚举做<b>领域扩展机制</b>"。判断标准是那条分界线:
 *
 * <blockquote>
 * 这个东西的取值集合是由<b>本设计的内部逻辑</b>决定的, 还是由<b>外部世界的多样性</b>决定的？
 * </blockquote>
 *
 * <p>{@code PlanType}（写作业/上课/做实验/…）属于后者 —— 世界上有多少种事情做不完,
 * 所以它必须是开放的。而本枚举属于前者: "已排定 / 正在做 / 做完 / 取消 / 被替代"
 * 是<b>调度器内部状态机</b>的五个位置, 它由 {@link PlanScheduler} 与
 * {@link PlanBoard} 的代码逻辑决定。第三方不会带来第六种生命周期位置 ——
 * 如果它声称带来了, 那说明它想表达的是别的东西（比如"等待外部条件"，
 * 那是一个 {@link PlanConstraint}, 不是生命周期）。
 *
 * <p>换一个说法: 枚举在这里是<b>安全</b>的, 因为它是封闭的;
 * 枚举在 {@code PlanType} 那里是<b>危险</b>的, 因为它是假装封闭的。
 *
 * <h2>五态之间的迁移</h2>
 * <pre>
 *                 ┌──────────────► CANCELLED   （她放弃了 / 被删掉了）
 *                 │
 *   PENDING ──────┼──────────────► ACTIVE ──────┬──► DONE        （做完了）
 *      │          │                  │          │
 *      │          │                  │          └──► CANCELLED   （做到一半不做了）
 *      │          │                  │
 *      │          │                  └──────────────► SUPERSEDED（被新 Revision 替代）
 *      │          │
 *      └──────────┴──────────────► SUPERSEDED      （还没开始就被替代）
 * </pre>
 *
 * <h2>{@link #SUPERSEDED} 是这个设计里最重要的一个状态</h2>
 * 它正是用户要求的那件事在数据上的落点。用户否定"暂停/恢复"时说得很清楚:
 * <blockquote>
 * 他<b>是真的改变了计划表</b>, 让 agent 重新思考重排计划表
 * </blockquote>
 *
 * <p>当写作业被"推后到穿衣服之后"时, Revision 17 里那一项的生命周期变成
 * {@code SUPERSEDED} —— 它<b>不是</b> {@code CANCELLED}（她没有放弃写作业），
 * <b>也不是</b> {@code PENDING}（那一项已经不属于当前计划了）。
 * Revision 18 里出现的是一个<b>新的</b> {@link PlanItem}（新的 id, 新的窗口）。
 *
 * <p>为什么必须是新的 id 而不是"同一个 id 改了时间": 因为那样 Revision 17 和
 * Revision 18 会指向同一个可变对象, 于是"她 12:15 时计划的是什么"这个问题的答案
 * 会随着时间改变 —— 历史就死了。
 *
 * <p>而没有 {@code SUPERSEDED} 这个状态, 上述区分就无处安放, 而"她是改主意了
 * 还是放弃了"就答不出来 —— 那正是行为分析最想知道的。
 */
public enum PlanLifecycle {

    /**
     * 已经排进计划表, 还没到时间。
     *
     * <p>一个新插入的项（比如被打断后插入的"穿衣服"）如果触发时间在未来,
     * 就是这个状态。
     */
    PENDING("已排定"),

    /**
     * 到点了, 正在做。
     *
     * <p>由 {@link PlanScheduler} 在 {@code dueAt(t)} 返回它时置位。
     * 同一时刻<b>至多有一项</b>处于 ACTIVE —— 因为她的身体只有一个。
     * 这一条不是靠约定, 而是靠 {@code PlanItem} 的窗口不重叠约束保证的。
     */
    ACTIVE("正在做"),

    /** 做完了。完成时刻与完成时的实际时长会被记下来, 供行为分析对比计划与实际。 */
    DONE("已完成"),

    /**
     * 被放弃了。
     *
     * <p>两种进入方式: 她自己决定不做了（用户说的"把写作业直接从计划表删掉"）;
     * 或者约束不再成立（比如"去实验室"但实验室关门了）。
     *
     * <p>{@link PlanMutation.Remove} 就是产生这个状态的唯一操作。
     */
    CANCELLED("已取消"),

    /**
     * 被新的 Revision 替代 —— <b>见类注释</b>。
     *
     * <p>这个状态<b>只出现在历史 Revision 里</b>。当前 Revision 里的项永远不会是它,
     * 因为当前 Revision 的定义就是"替代之后的结果"。
     */
    SUPERSEDED("已被新版本替代");

    private final String label;

    /** 中文标签。诊断面板与行为分析报告直接用它 —— 那些读者不该被迫翻译英文常量。 */
    PlanLifecycle(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 这一项是否还占着未来的时间。用于"她今天还剩多少事"这类统计。 */
    public boolean occupiesFuture() {
        return this == PENDING || this == ACTIVE;
    }

    /** 这一项是否已经结束（无论何种原因）。 */
    public boolean terminated() {
        return this == DONE || this == CANCELLED || this == SUPERSEDED;
    }

    /**
     * 从这个状态能不能迁到那个状态。
     *
     * <p>写成方法是刻意的: 让"非法迁移"在一个地方被拦住, 而不是散落在
     * {@link PlanBoard} 的各个写入点。少了它, 一个"把已完成的项重新激活"的 bug
     * 会表现为"她今天做了两次数学作业", 而那看起来像是数据错乱而不是状态机漏了检查。
     */
    public boolean canTransitionTo(PlanLifecycle target) {
        if (this == target) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == ACTIVE || target == CANCELLED || target == SUPERSEDED;
            case ACTIVE -> target == DONE || target == CANCELLED || target == SUPERSEDED;
            // 终态不能复活。要重新做同一件事, 那是一个新的 PlanItem
            case DONE, CANCELLED, SUPERSEDED -> false;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
