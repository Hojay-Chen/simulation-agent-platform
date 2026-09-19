package com.luxera.companion.human.body;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.EventHandler;
import com.luxera.companion.boundary.event.TickAware;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.body.clothing.ClothingSet;
import com.luxera.companion.human.body.clothing.WearableObject;
import com.luxera.companion.human.body.senses.AuditoryChannel;
import com.luxera.companion.human.body.senses.GustatoryChannel;
import com.luxera.companion.human.body.senses.OlfactoryChannel;
import com.luxera.companion.human.body.senses.RawSignal;
import com.luxera.companion.human.body.senses.SensoryAcuity;
import com.luxera.companion.human.body.senses.SensoryChannel;
import com.luxera.companion.human.body.senses.SensoryStimulus;
import com.luxera.companion.human.body.senses.TactileChannel;
import com.luxera.companion.human.body.senses.VisualChannel;
import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.2.1 —— <b>身体</b>: 五感、生理状态、稳态、以及穿在身上的东西。
 *
 * <h2>它是"她"与世界之间的那层膜, 而膜的厚度是零</h2>
 * <pre>{@code
 *   世界 ──WorldEvent──> EventFabric ──> 账本(持续影响) ──> Body.onTick
 *                                       队列(实时刺激) ──> Mind(不经过 Body)
 *   传感器 ──RawSignal──> Body.apply()  ──> 五条感官通道 ──> 刺激
 *   Body ──SensoryEvent──> EventFabric ──> 队列/历史 ──> Mind
 * }</pre>
 *
 * <h2>它认识什么, 不认识什么 —— 这是本类最重要的一节</h2>
 * <table border="1">
 *   <tr><th>认识</th><th>不认识(而且是刻意的)</th></tr>
 *   <tr>
 *     <td>{@code EventFabric} / {@code Settlement} / {@code HumanRuntimeContext}</td>
 *     <td><b>World 的任何东西</b> —— 没有 {@code import ...world...}, 一个都没有。
 *         连"外面几度"这个数她都不是直接读的, 而是从账本结算里<b>推</b>出来的:
 *         {@code environment.temperature-changed.v1} 的 effectChannel 就是
 *         {@code body.warmth}, 它落在账本上, 而账本只说"保暖通道被推了多少"</td>
 *   </tr>
 *   <tr>
 *     <td>{@code RawSignal}(声压级、lux、浓度、牛顿、摄氏度)</td>
 *     <td>{@code WorldEvent} 的具体类型 —— 感官通道只吃物理量。
 *         见 {@code SensoryChannel} 的类注释: 这是"将来接真实机器人"的落点</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Channels.*} 这些<b>字符串</b></td>
 *     <td>{@code Channels} 这个类代表的<b>语义</b>之外的东西 ——
 *         她不知道 {@code body.warmth} 与天气有任何关系。她只知道有一条通道被推了</td>
 *   </tr>
 * </table>
 * "不认识 World"不是洁癖: 一个身体若需要知道"现在外面几度、有没有风、湿度多少"才能
 * 算出自己冷不冷, 那么把她接到真实传感器上时, 这一整套逻辑就全废了 ——
 * 因为真实传感器不产生 {@code environment.temperature-changed.v1}。
 *
 * <h2>为什么它<b>同时</b>是 {@code EventHandler} 和 {@code TickAware}</h2>
 * 因为 {@code EventHandlerRegistry} 只收 {@code EventHandler}, 而 tick 调用只发给
 * {@code TickAware}(见 {@code EventHandler.Timing} 与 {@code TickAware} 的分工)。
 * 一个只实现 {@code TickAware} 的类<b>注册不进去</b> —— 这不是缺陷, 而是刻意的:
 * 注册表持有一组"会被调用的东西", 而"会在 tick 上被调用"是那组东西的一个属性。
 *
 * <p>于是本类两个接口都实现, 而 {@code handle()} 永远不会被调用 —— 它的
 * {@code subscriptions()} 是空的(理由见 {@link ThresholdDetector#subscriptions()},
 * 完全同构)。留一个抛异常的实现而不是空实现, 理由也一样。
 *
 * <h2>它<b>不</b>存放"我现在是不是觉得冷"</h2>
 * 见 {@link PhysiologicalState} 的说明。她冷不冷是 {@link ThresholdDetector} 每次
 * 从 {@link #state()} 与 {@link ComfortBand} 现场算出来的判断, 不是一个字段。
 * 本类存放的只有<b>状态</b>(保暖值是多少)与<b>事实</b>(账本上挂着什么)。
 *
 * <h2>它<b>不</b>产生 {@code body.warmth-changed.v1}</h2>
 * 这是一个必须写下来的坑。目录里 {@code body.warmth-changed.v1} 被登记为
 * {@code STATE_EFFECT} 且 {@code channel = WARMTH}, 而它的语义写着"它是账本结算的产物,
 * 不是入账"。这两句话在当前的 {@code DefaultEventFabric} 下<b>无法同时成立</b>:
 * 路由是按 {@code instanceof StateEffectEvent} 判定的, 不看目录的分类 ——
 * 所以一旦把它投出去, 它会被<b>重新记进账本</b>, 于是保暖值的下降本身又变成了
 * 一个新的降温源, 形成正反馈。
 *
 * <p>本类的选择是:<b>不产生这条事件</b>, 并把它记为目录与路由的一处不一致(见交付报告)。
 * 行为分析想要的那条时间线, 由 {@link #history()} 提供 —— 而那条线更准确,
 * 因为它记的是真正的状态序列, 而不是"事件恰好被投递的那些时刻"。
 */
public final class Body implements EventHandler<WorldEvent>, TickAware {

    /**
     * 本类在 tick 链上的顺序。
     *
     * <p>{@code 10} —— 必须<b>小于</b> {@link ThresholdDetector#TICK_ORDER}(20)。
     * {@code EventHandlerRegistry.tickHandlers()} 按 {@code order()} 升序调用,
     * 所以这个数字决定的是"她先被推进, 然后才判断她是不是越界了"。
     * 反过来的症状见 {@code ThresholdDetector} 的类注释。
     */
    public static final int TICK_ORDER = 10;

    /**
     * 保留多少个历史快照。
     *
     * <p>{@code 128} 这个数不是随便取的: 一个分钟级的 tick 意味着它覆盖约两小时 ——
     * 足够回答"她刚才那一下为什么突然觉得冷"与"她是不是在缓慢失温",
     * 而这两个问题正是行为分析最常问的(见 {@link PhysiologicalState} 的说明)。
     *
     * <p>为什么不无限保留: 一个跑一天的仿真会产生 1440 条快照, 而其中 99% 永远不会被读。
     * 无限保留换来的是内存增长与一次 OOM, 而不是任何一个新问题的答案。
     * 需要更长历史时, 正确做法是<b>落库</b>({@code EventStore}), 不是在内存里堆。
     */
    public static final int SNAPSHOT_HISTORY_LIMIT = 128;

    private final String id;

    /** 五条感官通道, 按 {@code CoreEventCatalog.Modalities} 的模态名索引。 */
    private final Map<String, SensoryChannel<?>> channels;

    private final ClothingSet clothing;

    private final HomeostasisModel homeostasis = new HomeostasisModel();

    private final ThresholdDetector detector;

    /** 有界历史 —— 见 {@link #SNAPSHOT_HISTORY_LIMIT}。 */
    private final Deque<PhysiologicalState> history = new ArrayDeque<>();

    /** 当前状态。它是本类唯一的"她会变成什么"的真相。 */
    private PhysiologicalState state = PhysiologicalState.initial();

    /** 上一次推进之前的状态 —— diff 的来源。 */
    private PhysiologicalState previous = state;

    private PhysiologicalState.Delta lastDelta;

    private ContinuousEffectLedger.Settlement lastSettlement;

    private Instant lastTickAt;

    private Instant lastThresholdSweepAt;

    /** 上一餐的时刻。{@code null} = 系统还不知道 —— 与"她没吃过"是两件事。 */
    private Instant lastMealAt;

    private EventFabric fabric;

    public Body(String humanId) {
        this(humanId, SensoryChannel.DEFAULT_CAPACITY);
    }

    /**
     * @param humanId        她是谁
     * @param channelCapacity 五条感官通道各自的缓冲容量, 见 {@code SensoryChannel}
     */
    public Body(String humanId, int channelCapacity) {
        if (humanId == null || humanId.isBlank()) {
            throw new IllegalArgumentException(
                    "身体必须属于某个人 —— 无主的身体在日志里无法与另一个 agent 区分");
        }
        this.id = humanId;
        this.clothing = new ClothingSet(humanId);

        Map<String, SensoryChannel<?>> map = new LinkedHashMap<>();
        map.put(CoreEventCatalog.Modalities.AUDITORY,
                new AuditoryChannel(humanId, SensoryAcuity.humanEar(),
                        channelCapacity));
        map.put(CoreEventCatalog.Modalities.VISUAL,
                new VisualChannel(humanId, SensoryAcuity.humanEye(),
                        channelCapacity));
        map.put(CoreEventCatalog.Modalities.OLFACTORY,
                new OlfactoryChannel(humanId, SensoryAcuity.humanNose(),
                        channelCapacity));
        map.put(CoreEventCatalog.Modalities.GUSTATORY,
                new GustatoryChannel(humanId, SensoryAcuity.humanTongue(),
                        channelCapacity));
        map.put(CoreEventCatalog.Modalities.TACTILE,
                new TactileChannel(humanId, SensoryAcuity.humanSkin(),
                        channelCapacity));
        this.channels = Map.copyOf(map);

        this.detector = new ThresholdDetector(this);
        history.addLast(state);
    }

    // ═══════════════════════ 身份与装配 ═══════════════════════

    public String id() {
        return id;
    }

    /**
     * 把她接到事件总线上 —— <b>这是她与世界的唯一接口</b>。
     *
     * <p>注册两个 tick handler:
     * <ol>
     *   <li>{@code this}(order 10) —— 先推进生理状态;</li>
     *   <li>{@code detector}(order 20) —— 再在新鲜的状态上判断越界。</li>
     * </ol>
     * 两者都不订阅任何事件类型(见 {@code subscriptions()} 的说明), 所以它们
     * 只会出现在 tick 链上, 不会出现在到达路由里。
     *
     * <p>本方法可以在构造之后任何时候调用, 但<b>穿着事件需要一个总线才有接收者</b> ——
     * 所以 {@link #wear} 在没接线时会拒绝执行, 而不是安静地丢掉那条影响。
     */
    public void registerOn(EventFabric eventFabric) {
        this.fabric = Objects.requireNonNull(eventFabric, "事件总线不能为空");
        eventFabric.subscribe(this);
        eventFabric.subscribe(detector);
    }

    /**
     * 这条总线是不是<b>就是</b>她已经接上的那一条。
     *
     * <h2>这个方法存在的唯一理由: 让 {@code Human} 的装配守卫问得出来</h2>
     * 漏掉 {@link #registerOn} 是本层最隐蔽的一种装配错误 —— 它<b>不抛异常、
     * 不打日志、不影响任何计数</b>, 因为 {@code Human.acceptClockTicked} 会
     * <b>自己</b>调 {@link #advance}(所以状态照样推进、{@code lastTickAt} 照样前进),
     * 而 {@code fabric.tick} 只是在一张空处理器链上正常返回。
     * 真正消失的是 {@link ThresholdDetector} —— 它不在链上, 于是
     * <b>一条感官刺激都产不出来</b>: 她永远不冷、不饿、不累。
     *
     * <p>外面看不出来, 所以这个事实必须能在装配那一刻被问一次。
     * 用 {@code ==} 而不是 {@code equals}: 这里要的正是同一个对象 ——
     * 一个按值相等的比较会让"接在 A 总线上、装进 B 总线"通过检查,
     * 而那时候 tick 链上跑的仍然不是她。
     *
     * <p>返回布尔而不是抛异常: 判断"这样装对不对"是装配者的事
     * ({@code Human} 的构造器), 身体只回答事实。
     */
    public boolean isWiredTo(EventFabric eventFabric) {
        return this.fabric == eventFabric;
    }

    private EventFabric fabricOrFail(String operation) {
        if (fabric == null) {
            throw new IllegalStateException(
                    "身体还没有接到事件总线上, 无法 " + operation
                            + " —— 没有总线时那条持续影响没有接收者, 而'她穿了衣服但没变暖'"
                            + "是一个查不出来的故障。请先调用 registerOn(fabric)");
        }
        return fabric;
    }

    // ═══════════════════════ EventHandler / TickAware ═══════════════════════

    /**
     * <b>空集合</b> —— 理由与 {@link ThresholdDetector#subscriptions()} 完全相同:
     * 她的状态由账本结算驱动, 不由某条具体事件的到达驱动。
     *
     * <p>这条设计有一个值得说清楚的推论: <b>她不会因为"收到了降温事件"而变冷。</b>
     * 她会冷, 是因为那条事件在账本上留下了一条持续影响, 而那条影响在结算时被推进成了状态。
     * 两者的区别在"刷新频率"上暴露得非常清楚 —— 见 {@code ContinuousEffectLedger} 的类注释。
     */
    @Override
    public Set<String> subscriptions() {
        return Set.of();
    }

    @Override
    public Timing timing() {
        return Timing.ON_TICK;
    }

    @Override
    public int order() {
        return TICK_ORDER;
    }

    @Override
    public String name() {
        return "Body[" + id + "]";
    }

    /** 永远不会被调用 —— 见类注释。抛异常而不是空实现, 理由同 {@code ThresholdDetector}。 */
    @Override
    public void handle(WorldEvent event, EventFabric eventFabric) {
        throw new IllegalStateException(
                "Body[" + id + "] 不订阅任何事件, 却收到了一条 " + event.typeId()
                        + " —— 这是装配错误: 她的状态由账本结算推进, 不由事件到达推进");
    }

    /**
     * 一个 tick。
     *
     * <h3>它只做三件事, 顺序固定</h3>
     * <pre>
     *   ① 算出这一 tick 有多长 (Δt) —— 全部生理过程都按秒积分
     *   ② 用账本结算推进状态 (HomeostasisModel)
     *   ③ 把这一次推进记进有界历史, 并算出 diff
     * </pre>
     * 检测越界<b>不</b>在这里做 —— 那是 {@link ThresholdDetector#onTick} 的事,
     * 它在 order=20 上读本方法刚写好的状态。把两者合在一起会让 {@link Body}
     * 同时承担"变化"与"判断变化", 而那正是 {@code PhysiologicalState} 明确拒绝的
     * ("冷不冷是一个结算, 不是一个事实")。
     */
    @Override
    public void onTick(ContinuousEffectLedger.Settlement settlement,
                       HumanRuntimeContext ctx,
                       EventFabric eventFabric) {
        Objects.requireNonNull(ctx, "tick 上下文不能为空 —— 它给出仿真时刻");
        advance(settlement, ctx.now(), eventFabric);
    }

    /**
     * 推进到某个时刻。
     *
     * <p>{@code now} 必须来自仿真时钟(通常是 {@code HumanRuntimeContext.now()}),
     * <b>不能</b>是 {@code Instant.now()}。理由见 {@code ContinuousEffectLedger.book}:
     * 一个读墙上时钟的身体, 在把仿真加速 60 倍时会表现出完全不同的生理过程。
     *
     * <p>第一次调用时 Δt 记为 0(还没有"上一次"), 于是这次调用只建立时间基准、
     * 不推进状态。这是刻意的: 否则"她第一次 tick 就老了一分钟"会成为一个
     * 需要解释的默认行为。
     *
     * @param fabric 结算用的总线 —— 本方法不用它投递任何东西, 只为与 {@code EventFabric}
     *               的 tick 契约保持签名一致, 以及给将来的"状态落库"留位置
     */
    public void advance(ContinuousEffectLedger.Settlement settlement, Instant now, EventFabric fabric) {
        Objects.requireNonNull(settlement, "账本结算不能为空");
        Objects.requireNonNull(now, "仿真时刻不能为空");

        double deltaSeconds = 0.0;
        if (lastTickAt != null) {
            deltaSeconds = java.time.Duration.between(lastTickAt, now).toMillis() / 1000.0;
            if (deltaSeconds < 0.0) {
                throw new IllegalArgumentException(
                        "仿真时间倒流: 上一次 tick 在 " + lastTickAt + ", 这一次在 " + now);
            }
        }
        lastTickAt = now;
        lastSettlement = settlement;

        if (deltaSeconds == 0.0) {
            return;
        }

        this.previous = this.state;
        this.state = homeostasis.step(this.state, settlement, deltaSeconds, this.clothing);
        this.lastDelta = PhysiologicalState.Delta.between(this.previous, this.state, deltaSeconds);

        history.addLast(this.state);
        while (history.size() > SNAPSHOT_HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    // ═══════════════════════ 状态读取 ═══════════════════════

    /** 此刻的她。 */
    public PhysiologicalState state() {
        return state;
    }

    /** 上一个 tick 的她 —— 想自己算 diff 时用。 */
    public PhysiologicalState previousState() {
        return previous;
    }

    /**
     * 上一次推进产生的 diff —— <b>用户要求的那个"变化"</b>。
     *
     * <p>返回 {@code Optional} 而不是零值: "还没有任何变化"与"变化量为 0"是两件事,
     * 而前者发生在第一个 tick 上。让调用方看见这个区别, 胜过给他一个看起来正常的 0。
     */
    public Optional<PhysiologicalState.Delta> lastDelta() {
        return Optional.ofNullable(lastDelta);
    }

    /** 上一次账本结算 —— 它是"她为什么变冷"的证据来源({@code Settlement.why})。 */
    public Optional<ContinuousEffectLedger.Settlement> lastSettlement() {
        return Optional.ofNullable(lastSettlement);
    }

    /**
     * 有界的历史快照, <b>最旧的在前</b>。
     *
     * <p>它是 {@code body.warmth-changed.v1} 那条不能投递的事件(见类注释)的真正替代品:
     * 行为分析要的是"她 12:15 保暖值 0.42, 12:25 变成 0.81"这条线, 而这条线
     * 由真正的状态序列给出, 比由"事件恰好被投递的那些时刻"给出更准确。
     */
    public List<PhysiologicalState> history() {
        return List.copyOf(history);
    }

    /** 最后一次 tick 的仿真时刻。 */
    public Optional<Instant> lastTickAt() {
        return Optional.ofNullable(lastTickAt);
    }

    /** 上一餐的时刻 —— 见 {@link #lastMealAt()}. */
    public Instant lastMealAt() {
        return lastMealAt;
    }

    /** 记下"她刚吃了一顿" —— 由 Life 的活动上下文调用(吃完了 → 传吃的那一刻)。 */
    public void markMeal(Instant at) {
        this.lastMealAt = Objects.requireNonNull(at, "上一餐的时刻不能为空");
        this.state = this.state.with(CoreEventCatalog.Channels.HUNGER,
                Math.max(0.0, this.state.hunger() - 0.6));
    }

    /** {@link ThresholdDetector} 在每次扫完所有通道后回调一次 —— 只是记个时间戳, 用于诊断。 */
    void onThresholdSweepCompleted(Instant at) {
        this.lastThresholdSweepAt = at;
    }

    // ═══════════════════════ 五感 ═══════════════════════

    /**
     * 一条<b>原始物理刺激</b>到达 —— 感官层的入口。
     *
     * <h3>它为什么不是 {@code WorldEvent}</h3>
     * 因为真实世界的传感器产生的是物理量, 不是我们的事件类型。一台麦克风输出的是
     * 声压级, 不是 {@code device.phone.notification-raised.v1}。
     * 若本方法的入口是一个 {@code WorldEvent}, 那么"把 human 概念接到真实机器人上"
     * 这件事就会退化成"给机器人写一个假的 digital world" —— 而那显然不是用户要的。
     *
     * <p>落到哪条通道<b>由信号自己声明</b>({@code RawSignal.modality()}), 而不是由调用方
     * 指定 —— 调用方指定意味着调用方可能指定错, 而"投错感官"这种错误在行为分析里
     * 表现为"她看见了声音", 一条谁也不会去查的日志。
     *
     * @throws IllegalArgumentException 信号声明的模态没有对应的通道
     */
    public void apply(RawSignal rawSignal) {
        Objects.requireNonNull(rawSignal, "原始信号不能为空");
        SensoryChannel<?> channel = channels.get(rawSignal.modality());
        if (channel == null) {
            throw new IllegalArgumentException(
                    "没有 " + rawSignal.modality() + " 这条感官通道 —— 她的模态只有 " + channels.keySet()
                            + ", 而 " + CoreEventCatalog.Modalities.ALL + " 是平台的封闭集合");
        }
        receiveUnchecked(channel, rawSignal);
    }

    /**
     * 把一个<b>已经解释过的刺激</b>放进对应的感官通道。
     *
     * <p>用于内部产生的感觉(见 {@link SensoryStimulus#interoceptive()})与
     * "世界已经把这条刺激解释好了, 只想让她感觉到"的场景。经过 {@code feel()} 而不是
     * {@code receive()} —— 因为它不应该再被灵敏度衰减一次, 见 {@code SensoryChannel.feel}。
     */
    public void feel(SensoryStimulus stimulus) {
        Objects.requireNonNull(stimulus, "刺激不能为空");
        SensoryChannel<?> channel = channels.get(stimulus.modality());
        if (channel == null) {
            throw new IllegalArgumentException(
                    "没有 " + stimulus.modality() + " 这条感官通道, 无法接收 " + stimulus.describe());
        }
        channel.feel(stimulus);
    }

    /**
     * 泛型分发的唯一一处不安全转换。
     *
     * <h3>为什么它是安全的</h3>
     * {@code channels} 这张表的键与值是一起构造的: {@code "auditory"} 对应的那个值
     * 一定是 {@code AuditoryChannel}, 而后者的 {@code interpret} 参数类型一定是
     * {@code RawSignal.Sound}。再加上 {@code apply()} 在入口处已经用
     * {@code rawSignal.modality()} 选过通道, 所以这次转换在<b>构造上</b>成立,
     * 不是因为运气。
     *
     * <p>换成在 {@code apply()} 里写五个 {@code if (channel instanceof AuditoryChannel a)}
     * 也能达到同样的效果 —— 但那意味着<b>每加一条通道就要改一次 {@code apply()}</b>,
     * 而"加一条通道"恰恰是这套设计要求可以不改平台源码就做到的事。
     */
    @SuppressWarnings("unchecked")
    private static <T extends RawSignal> void receiveUnchecked(SensoryChannel<T> channel, RawSignal raw) {
        channel.receive((T) raw);
    }

    /** 取走五条通道里积压的全部刺激 —— <b>取走后通道不再持有</b>。 */
    public List<SensoryStimulus> drainSensoryStimuli() {
        List<SensoryStimulus> all = new ArrayList<>();
        for (SensoryChannel<?> channel : channels.values()) {
            all.addAll(channel.drain());
        }
        return List.copyOf(all);
    }

    /** 某条模态的通道。 */
    public SensoryChannel<?> channel(String modality) {
        SensoryChannel<?> channel = channels.get(modality);
        if (channel == null) {
            throw new IllegalArgumentException(
                    "没有 " + modality + " 这条感官通道, 她只有 " + channels.keySet());
        }
        return channel;
    }

    /** 全部通道 —— 给诊断面板遍历。 */
    public List<SensoryChannel<?>> channels() {
        return List.copyOf(channels.values());
    }

    public AuditoryChannel auditory() {
        return (AuditoryChannel) channels.get(CoreEventCatalog.Modalities.AUDITORY);
    }

    public VisualChannel visual() {
        return (VisualChannel) channels.get(CoreEventCatalog.Modalities.VISUAL);
    }

    public OlfactoryChannel olfactory() {
        return (OlfactoryChannel) channels.get(CoreEventCatalog.Modalities.OLFACTORY);
    }

    public GustatoryChannel gustatory() {
        return (GustatoryChannel) channels.get(CoreEventCatalog.Modalities.GUSTATORY);
    }

    /** 触觉 —— 唯一一条有<b>两个来源</b>的通道, 见 {@link TactileChannel}。 */
    public TactileChannel tactile() {
        return (TactileChannel) channels.get(CoreEventCatalog.Modalities.TACTILE);
    }

    // ═══════════════════════ 穿衣 ═══════════════════════

    public ClothingSet clothing() {
        return clothing;
    }

    /**
     * 穿上一件 —— <b>产生一条持续影响事件</b>, 而不是直接改保暖值。
     *
     * <p>这是用户那句话在代码上的完整落点:
     * <blockquote>温度低应该让 human 的 body 持续降低保暖值, 除非其多穿衣服
     * (因此衣服可能也要抽象成对象), 多穿衣服也是一个 event, 能够持续影响 body 的保暖值的</blockquote>
     * 三个要点各对应一处实现:
     * <ul>
     *   <li>"多穿衣服也是一个 event" → 本方法投 {@code body.clothing-changed.v1};</li>
     *   <li>"能够<b>持续</b>影响" → 它的类型是 {@code StateEffectEvent}(A 类),
     *       进账本而不是队列 —— 影响一直挂着, 直到被替换;</li>
     *   <li>"除非其多穿衣服" → 替换而非叠加({@code cancellationKey = body.thermal-insulation})。</li>
     * </ul>
     *
     * <p><b>注意本方法不会立刻改变保暖值。</b> 它只是让账本上多了一条影响,
     * 而保暖值要等下一个 tick 结算时才动 —— 用户明确要求过这一点:
     * "影响是渐进的, 而不是'穿上就暖和了'"。
     *
     * @return 这次是不是真的穿上了(重复穿同一件返回 {@code false}, 也不产生事件)
     */
    public boolean wear(WearableObject item) {
        Objects.requireNonNull(item, "要穿的衣服不能为空");
        EventFabric bus = fabricOrFail("穿衣服");
        if (!clothing.wear(item)) {
            return false;
        }
        Instant at = currentSimulationTime();
        bus.publish(clothing.changedEvent(List.of(item), List.of(), at));
        return true;
    }

    /**
     * 脱下。
     *
     * @return 被脱下的那件; 本来就没穿则返回空, 也<b>不</b>产生事件
     */
    public Optional<WearableObject> takeOff(String objectId) {
        EventFabric bus = fabricOrFail("脱衣服");
        Optional<WearableObject> removed = clothing.takeOff(objectId);
        removed.ifPresent(item -> bus.publish(
                clothing.changedEvent(List.of(), List.of(item), currentSimulationTime())));
        return removed;
    }

    /** 一次穿一整套(比如"换冬装") —— 只产生<b>一条</b>影响, 见 {@code ClothingSet} 的说明。 */
    public List<WearableObject> wearAll(List<WearableObject> items) {
        Objects.requireNonNull(items, "要穿的衣服列表不能为空");
        EventFabric bus = fabricOrFail("穿衣服");
        List<WearableObject> added = new ArrayList<>();
        for (WearableObject item : items) {
            if (clothing.wear(item)) {
                added.add(item);
            }
        }
        if (added.isEmpty()) {
            return List.of();
        }
        bus.publish(clothing.changedEvent(added, List.of(), currentSimulationTime()));
        return List.copyOf(added);
    }

    /**
     * 当前仿真时刻。
     *
     * <p>优先用上一次 tick 的时刻 —— 因为那是<b>已知的仿真时间</b>。只有在还没有过
     * 任何 tick 时才回退到墙上时钟, 并且这是一个<b>应当尽量避免</b>的路径:
     * 一个在 tick 之前发生的穿衣事件, 其时间戳会与账本的时间轴对不齐。
     */
    private Instant currentSimulationTime() {
        if (lastTickAt != null) {
            return lastTickAt;
        }
        throw new IllegalStateException(
                "还没有发生过任何 tick, 无法为穿衣事件定时刻 —— "
                        + "先让它跑一个 tick(EventFabric.tick), 再让她穿衣服");
    }

    // ═══════════════════════ 诊断 ═══════════════════════

    public ThresholdDetector detector() {
        return detector;
    }

    /** 一行摘要 —— 日志与诊断面板用。 */
    public String describe() {
        return String.format("Body[%s] %s | %s | 通道: %s | 账本: %s",
                id, state.describe(), clothing.describe(),
                channels.values().stream().map(SensoryChannel::describe).toList(),
                lastSettlement == null ? "(尚未结算)" : lastSettlement.totals().toString());
    }

    /** 详细的诊断快照 —— 出问题时第一个要看的东西。 */
    public Map<String, String> diagnostic() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", state.describe());
        if (lastDelta != null) {
            m.put("delta", lastDelta.describe());
        }
        m.put("clothing", clothing.describe());
        channels.forEach((modality, channel) -> m.put("sense." + modality, channel.describe()));
        m.put("thresholds", detector.snapshot().toString());
        m.put("historySize", String.valueOf(history.size()));
        m.put("lastTickAt", String.valueOf(lastTickAt));
        m.put("lastThresholdSweepAt", String.valueOf(lastThresholdSweepAt));
        m.put("lastMealAt", String.valueOf(lastMealAt));
        return Map.copyOf(m);
    }
}
