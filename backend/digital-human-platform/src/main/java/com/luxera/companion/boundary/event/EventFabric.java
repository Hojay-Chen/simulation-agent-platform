package com.luxera.companion.boundary.event;

import com.luxera.companion.boundary.HumanRuntimeContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V2.2 §5.1 —— <b>World → Human 的唯一通道</b>, 即用户所说的"类消息队列"。
 *
 * <h2>它同时是"队列"和"不是队列"</h2>
 * 用户的原话:
 * <blockquote>
 *   human 和 world 之间有一个类似消息队列的设定……能够存储各种 world 发生什么导致产生的
 *   event, 这些 event 是用来影响和输入到 human 的
 * </blockquote>
 *
 * <p>他说的"类消息队列"是一个<b>语义</b>上的说法(世界发生的事排队进入她的感知),
 * 不是一个数据结构上的说法。而他紧接着就自己指出了这一点:
 * <blockquote>
 *   "至于这几个 event 你要用几个队列存储, 甚至于像计划表这种可能用队列还不好实现,
 *    你得想想什么数据结构实现能很好满足要求"
 * </blockquote>
 *
 * <p>所以 V2.2 的做法是: <b>保留"类消息队列"这个统一入口, 但内部按事件性质分成三个
 * 结构</b>。{@link #publish} 是那个统一入口, 三个结构是它的实现细节 —— 世界侧只需要
 * 知道"我把事件投进去了", 不需要知道它被存进了账本还是队列还是计划表。
 *
 * <pre>
 *                          ┌──────────────────────────────┐
 *   World ──publish(e)──→  │        EVENT FABRIC          │
 *                          │                              │
 *                          │  e instanceof StateEffectEvent ──→ ContinuousEffectLedger (账本)
 *                          │  e instanceof SensoryEvent     ──→ RealtimeEventQueue     (队列)
 *                          │  e instanceof ScheduledEvent   ──→ ScheduledEventSink     (计划表)
 *                          │                              │
 *                          │  另外: 分派给 EventHandler     │
 *                          └──────────────────────────────┘
 * </pre>
 *
 * <h2>一个事件可以同时进多个结构</h2>
 * 这是接口而非枚举分类的价值所在: 一条"她穿着羽绒服"的事件可以既是
 * {@link StateEffectEvent}(保暖 +0.85)又是 {@link ScheduledEvent}(14:00 要去干洗)。
 * 用枚举做分类就只能二选一, 而那个选择没有正确答案。
 *
 * <h2>为什么是每个 Human 一个 EventFabric, 不是全局一个</h2>
 * 账本、队列、计划表都是<b>她的</b>: 她身上的衣服不会让另一个 agent 变暖。
 * 全局一个 fabric 会把这些状态混在一起, 而"每个 agent 有自己的世界"是这个系统的
 * 基本设定。世界侧要投递时, 先知道自己投给谁 —— 这也正是
 * {@link #humanId()} 存在的理由。
 *
 * <h2>它<b>不</b>负责什么</h2>
 * <ul>
 *   <li><b>不做决策。</b>它只分类和投递。"这条刺激重不重要""该不该打断她"
 *       是 Mind 的事(§3.4)。一个开始做判断的 EventFabric 会迅速变成一个上帝对象。</li>
 *   <li><b>不做持久化。</b>事件的落库是 {@code EventStore} 的事。混在一起会让
 *       "内存里跑仿真"这件事必须先有一个数据库。</li>
 *   <li><b>不知道 Human 的内部结构。</b>它只知道"有人会注册 handler"。
 *       这条由 {@link ScheduledEventSink} 的接口形状和 §8.2.7 的 ArchUnit 规则共同保证。</li>
 * </ul>
 */
public interface EventFabric {

    /** 这是谁的通道。世界侧投递时用它确认投对了人。 */
    String humanId();

    /**
     * 投一条事件进来 —— <b>世界侧通往 Human 的唯一入口</b>。
     *
     * <h3>publish 是同步的</h3>
     * 方法返回时, 入账型 handler 已经跑完、事件已经进了该进的结构。这不是实现细节的
     * 偶然, 是刻意的: 世界侧的代码常常需要"我投了这条事件之后, 她的状态是什么"
     * (比如环境刷新完之后要看她冷不冷)。异步投递会让那个问题无法回答。
     *
     * <p>慢的部分(阈值检测、LLM 调用)在 {@link EventHandler.Timing#ON_TICK} 上,
     * 不在 publish 路径上 —— <b>把慢的东西移出关键路径的方法是换时机, 不是换线程</b>。
     *
     * <h3>同一个事件重复投递会怎样</h3>
     * 会进两次。去重是<b>投递方</b>的责任({@code EventEnvelope.withDeterministicId}
     * 那套幂等键的机制), 不是本层的 —— 因为"这两条事件是不是同一件事"这个问题
     * 只有知道业务语义的那一层才能回答。本层若擅自按 {@code typeId + occurredAt} 去重,
     * 会静默地吃掉"她同一秒被同一个人连发两条消息"这种真实发生的事。
     */
    void publish(WorldEvent event);

    /**
     * 注册一个消费者。
     *
     * <p>{@code ON_ARRIVAL} 的 handler 会<b>立刻</b>收到已经投递过的事件吗?
     * <b>不会。</b>见 {@link #replay} —— 重放是一个显式的动作, 不是注册的副作用。
     * 一个"注册就会收到历史事件"的设计, 会让重放和正常投递无法区分,
     * 而那正是重放功能失效的最常见原因。
     */
    void subscribe(EventHandler<?> handler);

    /**
     * <b>显式</b>重放一段历史给某个 handler。
     *
     * <p>用途: 新注册一个行为分析 handler 时, 让它看一眼最近发生的事。
     * 刻意做成显式方法而不是注册的副作用 —— 见 {@link #subscribe}。
     */
    void replay(EventHandler<?> handler, Instant from, Instant to);

    // ─────────────────────────── 三个结构 ───────────────────────────

    /** A 类事件的落点。见 {@link ContinuousEffectLedger}。 */
    ContinuousEffectLedger effects();

    /** B 类事件的落点。见 {@link RealtimeEventQueue}。 */
    RealtimeEventQueue stimuli();

    /** 注册 C 类事件的落点(计划表)。Human 侧在装配时调用。 */
    void registerScheduledSink(ScheduledEventSink sink);

    // ─────────────────────────── 运行 ───────────────────────────

    /**
     * 跑一次仿真 tick —— 结算型 handler 在这里被调用。
     *
     * <p>顺序刻意是"先结算再分派": 账本先按当前时刻清掉过期账目并求和, 然后
     * {@code ON_TICK} 的 handler 才拿到一个自洽的世界。反过来会让一个 handler
     * 在"上一条影响其实已经过期了"的状态下做判断。
     *
     * @return 这次 tick 的摘要, 给诊断与测试用
     */
    TickReport tick(Instant now, HumanRuntimeContext context);

    /** 最近投递过的事件, 按时间倒序。给诊断面板与行为分析用。 */
    List<WorldEvent> recentEvents(int limit);

    /** 找一条投递过的事件。重放与因果追踪用。 */
    Optional<WorldEvent> findEvent(String eventId);

    // ─────────────────────────── 摘要 ───────────────────────────

    /**
     * 一次 tick 的结果。
     *
     * @param arrivedHandlers 这次 tick 前到达并被入账型 handler 处理的事件数
     * @param tickHandlers    这次 tick 跑了几个结算型 handler
     * @param settlement      账本的结算快照 —— 她是"为什么冷"的答案
     * @param pendingStimuli  tick 后还有多少刺激没被处理 —— 大于 0 说明她还没注意到
     * @param at              这次 tick 的时刻
     */
    record TickReport(int arrivedHandlers, int tickHandlers,
                      ContinuousEffectLedger.Settlement settlement,
                      int pendingStimuli, Instant at) {

        public boolean quiet() {
            return arrivedHandlers == 0 && pendingStimuli == 0;
        }
    }

    /**
     * 这个 Human 的通道在她<b>此刻</b>看来是什么样。给诊断面板用。
     *
     * <h2>为什么必须带时刻, 而不是自己读一个"现在"</h2>
     * 这个方法原来是无参的, 内部用 {@code Instant.now()} 补齐了账本结算所需的时刻。
     * 那是一个真实的 bug, 而不是风格问题:
     *
     * <pre>
     *   回放上周三的数据:
     *     账本说温暖 = -0.42   ← 由事件算出, 与回放时刻无关, 正确
     *     结算时刻 = 今天 04:12 ← 读的墙钟, 与那天差了六天
     * </pre>
     *
     * <p>于是同一条日志里, 一半是那天的真实状态, 一半是"现在"。{@code ContinuousEffectLedger}
     * 的结算<b>依赖时刻</b>（持续效应随时间衰减）, 所以这个错配会让账本算出一个
     * 那天的她从未有过的值 —— 而它印在一行看起来很正常的日志里。
     *
     * <p>账本要时刻是合理的（它必须知道自己结算在哪个瞬间）; 不合理的是
     * <b>由账本的调用方去发明那个时刻</b>。所以参数从本方法一路传到账本,
     * 而这条链上没有一处读墙钟。
     */
    default String describe(Instant at) {
        return "EventFabric[" + humanId() + "] " + effects().describe(at)
                + " | " + stimuli().describe();
    }
}
