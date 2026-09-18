package com.luxera.companion.boundary.event;

import java.util.Set;

/**
 * V2.2 §5.4.6 —— 某类事件的<b>消费者</b>。
 *
 * <h2>为什么消费者是注册进来的, 不是 switch 出来的</h2>
 * 一个 {@code switch (event.type())} 会把"世界上能发生哪些事"写死在编译期 ——
 * 与 P4 禁止的枚举扩展机制是同一个错误, 只是换了个写法。{@link EventHandlerRegistry}
 * 让 handler 自己声明"我关心哪些类型", 于是加一个事件的代价从"改分派逻辑"变成
 * "多写一个类并注册"。
 *
 * <h2>三种 handler, 按它们被调用的时机区分</h2>
 * <table border="1">
 *   <tr><th>种类</th><th>{@link #timing()}</th><th>典型</th></tr>
 *   <tr>
 *     <td>入账型</td><td>{@code ON_ARRIVAL}</td>
 *     <td>把 A 类事件记进账本、把 C 类事件交给计划表。它们<b>必须</b>在事件到达时立刻跑,
 *         否则保暖值会少算一段、计划表会晚一 tick 才看到安排</td>
 *   </tr>
 *   <tr>
 *     <td>结算型</td><td>{@code ON_TICK}</td>
 *     <td>阈值检测(保暖值低于适中值 → 产生冷刺激)。它们<b>不能</b>在到达时跑,
 *         因为"低于适中值"这件事要等账本被结算过才知道</td>
 *   </tr>
 *   <tr>
 *     <td>审计型</td><td>{@code ON_ARRIVAL} 但 {@link #order()} 很大</td>
 *     <td>行为分析、调试日志。它们跑在最后, 且<b>不得</b>改变任何状态</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 handler 拿得到 {@link EventFabric}</h2>
 * 因为最常见的 handler 行为是"看到 A 就产生 B" —— 阈值检测看到保暖值低了要产生一条
 * 冷刺激({@link SensoryEvent})。若 handler 不能投递, 它就只能返回一个事件,
 * 而"返回一个事件"这个形状无法表达"一条事件引发多条", 也无法表达"这条 handler
 * 只在某些时候投递"。给它 fabric, 这两件事都自然了。
 *
 * <p>代价是 handler 可以无限递归地投递。这是调用方的责任, 不是接口能挡住的 ——
 * 但 {@link DefaultEventFabric} 会记录每条事件的因果链深度, 超过阈值时打 WARN。
 * <b>不抛异常</b>: 一条陷入循环的阈值检测不该让整个 agent 停摆, 该被看见然后修掉。
 */
public interface EventHandler<E extends WorldEvent> {

    /** 这个 handler 关心哪些类型。<b>返回订阅键</b>(不含版本, 见 {@link EventTypeId#subscriptionKey()})。 */
    Set<String> subscriptions();

    /** 收到一条事件。 */
    void handle(E event, EventFabric fabric);

    /**
     * 这个 handler 在什么时候跑。
     *
     * <p>默认 {@link Timing#ON_ARRIVAL} —— 绝大多数 handler 是入账型的, 让它成为默认
     * 可以减少"忘了标 ON_TICK 于是阈值检测每来一条事件就跑一次"这类错误。
     */
    default Timing timing() {
        return Timing.ON_ARRIVAL;
    }

    /**
     * 同一时机内的先后。小的先跑。
     *
     * <p>用整数不用浮点: 先后是一个<b>序</b>, 不是一个程度。用 double 会诱使人写
     * {@code 0.35} 这种"比 0.3 晚一点点"的值, 而那个差别在任何人眼里都不存在。
     */
    default int order() {
        return 100;
    }

    /**
     * 这个 handler 是否对给定的具体类型感兴趣。
     *
     * <p>默认实现按 {@link #subscriptions()} 里的订阅键匹配。需要更细粒度判断的
     * (比如"只在 agent 醒着时处理")应当覆盖它 —— 但那种判断更适合放在
     * {@link #handle} 的开头, 因为本方法会被<b>每条</b>事件调用一次, 而它不该有副作用。
     */
    default boolean accepts(EventTypeId typeId) {
        return subscriptions().contains(typeId.subscriptionKey());
    }

    /** 这个 handler 的名字, 用于日志。默认取类名 —— 覆盖它一般没有必要。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** handler 的调用时机。 */
    enum Timing {
        /** 事件到达时立刻 —— 入账型的 handler。 */
        ON_ARRIVAL,
        /** 仿真 tick 上 —— 结算型的 handler。 */
        ON_TICK
    }
}
