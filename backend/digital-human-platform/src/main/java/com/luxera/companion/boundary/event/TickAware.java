package com.luxera.companion.boundary.event;

import com.luxera.companion.boundary.HumanRuntimeContext;

/**
 * V2.2 §5.4.6 —— <b>结算型 handler</b>: 它不是在"某件事发生"时被调用,
 * 而是在"时间往前走了一格"时被调用。
 *
 * <h2>为什么这需要一个单独的接口, 而不是给 {@link EventHandler} 加个方法</h2>
 * 因为两者的<b>入参根本不是同一个东西</b>:
 * <table border="1">
 *   <tr><th></th><th>{@link EventHandler#handle}</th><th>{@link #onTick}</th></tr>
 *   <tr><td>回答的问题</td><td>"发生了什么"</td><td>"过了这一段, 世界变成什么样了"</td></tr>
 *   <tr><td>入参</td><td>一条 {@link WorldEvent}</td>
 *     <td>一份 {@link ContinuousEffectLedger.Settlement} 快照</td></tr>
 *   <tr><td>调用次数</td><td>每条事件一次</td><td>每 tick 一次, 没有事件时也调</td></tr>
 * </table>
 *
 * <p>硬把两者合进一个接口, 会得到 {@code handle(WorldEvent, Settlement, Context, Fabric)}
 * 这样一个每个实现都只用到一半参数的方法 —— 而"用不到的参数"在代码里会迅速变成
 * "顺手也读一下"的对象, 于是结算型 handler 开始依赖具体事件, 入账型 handler 开始
 * 依赖账本快照。那正是本设计要分开的东西。
 *
 * <h2>典型的结算型 handler 长什么样</h2>
 * <pre>{@code
 * // 阈值检测 —— 用户描述的那条链的最后一环
 * class ColdThresholdDetector implements EventHandler<WorldEvent>, TickAware {
 *     public Set<String> subscriptions() { return Set.of(); }  // 不订阅任何事件
 *     public Timing timing() { return Timing.ON_TICK; }
 *     public void handle(WorldEvent e, EventFabric f) { }  // tick 型, 不会走到这里
 *
 *     public void onTick(Settlement s, HumanRuntimeContext ctx, EventFabric fabric) {
 *         double warmth = s.totals().getOrDefault("body.warmth", 0.0);
 *         if (warmth < COMFORT_LOW && !alreadyCold) {
 *             fabric.publish(new ColdStimulus(ctx.now(), warmth, s.why("body.warmth")));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>它必须自己保证幂等</h2>
 * tick 会一直跑, 而"她冷"这件事一旦成立会一直成立。一个每 tick 都投一条冷刺激的
 * 阈值检测, 会在一分钟内把 {@link RealtimeEventQueue} 灌满 —— 表现是"她被自己的
 * 体温吵死了"。上面例子里的 {@code alreadyCold} 就是这个职责所在。
 *
 * <p>本接口<b>不</b>提供状态管理。沉降状态(她冷不冷、上次报警是什么时候)是
 * handler 自己的事, 因为它取决于那个 handler 在监测什么。
 */
public interface TickAware {

    /**
     * 时间往前走了一格。
     *
     * @param settlement 账本在这一刻的结算快照 —— <b>它是不可变的</b>,
     *                   可以安全地存下来做趋势比较(而这正是"diff"的来源)
     * @param context    谁、几点
     * @param fabric     用来投递新事件的通道。阈值检测几乎总是要投点什么
     */
    void onTick(ContinuousEffectLedger.Settlement settlement,
                HumanRuntimeContext context,
                EventFabric fabric);
}
