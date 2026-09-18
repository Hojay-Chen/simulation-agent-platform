package com.luxera.companion.boundary.event;

/**
 * V2.2 §5.3 —— <b>C 类事件的落点</b>, 以接口形式放在边界层。
 *
 * <h2>为什么是一个接口, 而不是直接依赖 PlanBoard</h2>
 * 这是包依赖方向的问题, 而且它是<b>硬约束</b>不是风格问题:
 * <pre>
 *   boundary/  ←──  human/      两边都依赖边界
 *   boundary/  ←──  world/
 *
 *   如果 EventFabric 直接持有 human.life.plan.PlanBoard:
 *     boundary/ ──→ human/      ← 边界依赖了 Human
 *     world/ ──→ boundary/ ──→ human/   ← 于是 World 间接依赖了 Human
 * </pre>
 * 最后那一步会直接推翻 P3(Human 与 World 零交互), 而且 ArchUnit 的
 * {@code worldMustNotDependOnHuman} 会红 —— <b>这正是那条测试存在意义的一个实例</b>:
 * 它挡住的不只是有人显式地 {@code import} 了 Human 的类, 也挡住"经由边界绕一圈"的
 * 间接依赖。
 *
 * <h2>谁来注册</h2>
 * {@code PlanBoard} 实现本接口, 由 {@code HumanActor} 在装配时注册进
 * {@link EventFabric}。<b>方向是 Human 主动把实现交出去</b>, 不是边界层去拿 ——
 * 依赖倒置的标准形状。
 *
 * <h2>实现者的义务</h2>
 * <ol>
 *   <li><b>不得直接执行它。</b>收到一条安排不等于现在去做那件事。本方法的语义是
 *       "有一件事被安排了, 请纳入考虑", 实现者应当把它变成一次计划表变更
 *       ({@code PlanBoard} 的 {@code PlanRevision}), 而不是一次立即执行。</li>
 *   <li><b>不得拒绝。</b>她可以决定<b>不做</b>某件事, 但那个决定必须体现为计划表上
 *       没有这一项, 而不是"事件被丢掉了"。所以本方法没有返回值 ——
 *       拒绝的语义在 Human 侧, 不在这里。</li>
 *   <li><b>不得假设顺序。</b>安排可以乱序到达(日历同步、闹钟先于会议被投递)。
 *       入板的先后不代表时间的先后。</li>
 * </ol>
 *
 * <h2>与应用户描述的对应</h2>
 * 用户说"还有一类 event 其实是安排 event...可以理解为这类 event 其实是计划表"。
 * 本接口就是那句话里的那个"是" —— 世界侧产生一条 {@link ScheduledEvent},
 * 边界把它交给计划表, 计划表决定它变成哪一条 {@code PlanItem}(或者不变成)。
 */
public interface ScheduledEventSink {

    /**
     * 收到一条安排。
     *
     * @param event 世界产生的安排事件。实现者<b>只应读它的时间窗口与意图类型</b>,
     *              不应该去读它的具体实现类 —— 那会让计划表需要认识每一种日历来源,
     *              而"不认识具体来源"正是它能接受任意第三方日历的原因
     */
    void onScheduled(ScheduledEvent event);

    /**
     * 这个 sink 是否接受来自给定来源的安排。
     *
     * <p>给"她关掉了某个日历的同步"这类场景留的位置。<b>默认全部接受</b> ——
     * 一个默认拒绝的实现会让"接了一个新日历但什么都没发生"变成一个静默故障。
     */
    default boolean accepts(EventTypeId intentType) {
        return true;
    }
}
