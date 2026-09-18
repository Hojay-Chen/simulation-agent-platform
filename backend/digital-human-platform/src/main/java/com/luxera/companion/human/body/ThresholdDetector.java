package com.luxera.companion.human.body;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.EventHandler;
import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.TickAware;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.body.senses.TactileChannel;
import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.2.4 —— <b>阈值检测器</b>: 身体越界了, 于是她<b>感觉到了</b>。
 *
 * <h2>它是那条链的最后一环, 也是唯一一环会产生实时事件的地方</h2>
 * <pre>{@code
 *   environment 逐渐降温  ──A──>  保暖值下降  ──>  低于舒适带  ──B──>  "觉得冷"
 *      (持续影响, 账本)             (稳态模型)          (本类)         (实时事件)
 * }</pre>
 * 中间那一步(保暖值下降)<b>刻意不产生实时事件</b> —— 一天里她"觉得冷"只有一两次,
 * 但保暖值每个 tick 都在变。把变化本身做成实时事件, 她会被自己的体温吵死。
 * 这也是 {@code SensoryEvent} 类注释里那段论证的执行者。
 *
 * <h2>两条触发路径 —— 用户明确要求的两条</h2>
 * 用户原话: "这里变化产生的 <b>diff</b> 和 <b>低于适中值的程度</b>也能形成一个实时 event"。
 * 两个词对应两条完全不同的路径:
 * <table border="1">
 *   <tr><th></th><th>路径一: 阈值越界</th><th>路径二: 变化率</th></tr>
 *   <tr>
 *     <td>触发条件</td>
 *     <td>{@code warmth < 0.45}(跌破下沿)</td>
 *     <td>照当前速率, {@value #RATE_PATH_LOOKAHEAD_SECONDS} 秒内会跌破;
 *         或者已经跌破且<b>还在继续恶化</b></td>
 *   </tr>
 *   <tr>
 *     <td>语义</td>
 *     <td><b>已经发生的事</b>: 她冷了</td>
 *     <td><b>正在发生的事</b>: 她冷得很快 / 她在变得更冷</td>
 *   </tr>
 *   <tr>
 *     <td>时间性</td>
 *     <td>离散 —— 一次跌破只发生一次</td>
 *     <td>连续 —— 速率可以一直很大</td>
 *   </tr>
 *   <tr>
 *     <td>对应真人的什么</td>
 *     <td>"我觉得冷"</td>
 *     <td>"外面冷得不对劲, 我得赶紧想办法" —— 皮肤的温度感受器对<b>变化率</b>敏感,
 *         这正是手伸进凉水那一瞬间最冷、以及人在还没冻透时就会开始找衣服的原因</td>
 *   </tr>
 *   <tr>
 *     <td>去重手段</td>
 *     <td>锁存 + 回差(见下)</td>
 *     <td>冷却时间(见下)</td>
 *   </tr>
 * </table>
 * <b>只做路径一</b>会得到一个迟钝的系统: 外面骤降到零下, 她要十分钟后才"觉得冷",
 * 而那十分钟里她一直待在外面。<b>只做路径二</b>会得到一个永远不报警的系统:
 * 稳态的冷掉得慢, 速率永远不超阈值, 于是她冻到底也不吭声。
 *
 * <h2>幂等性: 这个类最容易被写错的地方</h2>
 * {@code TickAware} 的类注释专门警告过这件事 —— tick handler <b>必须自己保证幂等</b>,
 * 框架不会替你挡重复。这里的做法是<b>锁存({@code latch})加回差</b>:
 * <pre>{@code
 *   12:00  warmth 0.44  side=-1  latch=0  → 触发! latch=-1
 *   12:01  warmth 0.43  side=-1  latch=-1 → 不触发 (已经在冷的状态里了)
 *   12:02  warmth 0.50  side= 0  latch=-1 → 还没过回差线(0.45+0.05=0.50)... 边界, 不解除
 *   12:03  warmth 0.52  side= 0  latch=-1 → 过了 0.50, 解除 latch=0
 *   12:04  warmth 0.44  side=-1  latch=0  → <b>再触发</b> —— 这是一次新的"觉得冷", 应该的
 * }</pre>
 * 没有回差会怎样: 保暖值在 0.45 附近抖一下, 她就在"冷 / 不冷 / 冷"之间每 tick 翻转,
 * 一天产生几百条冷刺激, 把队列灌满, 并把真正重要的刺激挤掉 —— 而这一切看起来
 * 只是"她好像有点烦躁"。
 *
 * <p>路径二用<b>冷却时间</b>而不是锁存, 因为它没有"状态"可言 —— 速率是连续量,
 * 锁存不了。{@value #RATE_PATH_COOLDOWN_MINUTES} 分钟的冷却是个折中:
 * 太短则刷屏, 太长则"她一直冷得很快"这件事在下一次该提醒她的时候哑掉。
 *
 * <h2>它读的是<b>刚被推进的状态</b>, 不是上一次的</h2>
 * 因此本类的注册顺序必须<b>严格大于</b> {@link Body} 的 —— 见 {@link #TICK_ORDER}
 * 与 {@code EventHandlerRegistry.tickHandlers()}(它按 {@code order()} 升序调用)。
 * 顺序反了的症状非常隐蔽: 检测的永远是上一 tick 的状态, 于是所有刺激都晚一个 tick,
 * 而在一个分钟级的仿真里这几乎看不出来 —— 除了在"她跳进冰水里"这种突变场景下,
 * 报警会晚一整分钟。
 */
public final class ThresholdDetector implements EventHandler<WorldEvent>, TickAware {

    /**
     * 本检测器在 tick 链上的顺序。
     *
     * <p>{@link Body} 用 10, 本类用 20 —— <b>先算状态, 再判断越界</b>。
     * 顺序是这套设计里唯一一处"靠约定而不是靠类型"保证的正确性, 所以它被写成常量,
     * 并在两个类里各写一遍为什么。
     */
    public static final int TICK_ORDER = 20;

    /** 路径二的冷却时间(分钟)。 */
    public static final int RATE_PATH_COOLDOWN_MINUTES = 10;

    /**
     * 路径二的预测视野(秒) —— 照当前速率, 这么久之内会越界就提前提醒。
     *
     * <p>取 120 秒: 足够她<b>做点什么</b>(去拿衣服、关窗), 又不至于把"她正在缓慢变冷"
     * 变成每十分钟一次的唠叨。这个数必须大于一次行动所需的时间, 否则提醒就没有意义 ——
     * 一个"两秒后你会冷"的预警, 与没有预警是一样的。
     */
    public static final double RATE_PATH_LOOKAHEAD_SECONDS = 120.0;

    /** 路径二的冷却。 */
    public static final Duration RATE_PATH_COOLDOWN = Duration.ofMinutes(RATE_PATH_COOLDOWN_MINUTES);

    /** {@code body.cold-stimulus.v1} 的类型 id —— 与目录里的登记必须一致。 */
    public static final EventTypeId COLD_STIMULUS = EventTypeId.of("body", "cold-stimulus");
    /** {@code body.heat-stimulus.v1}。 */
    public static final EventTypeId HEAT_STIMULUS = EventTypeId.of("body", "heat-stimulus");
    /** {@code body.hunger-stimulus.v1}。 */
    public static final EventTypeId HUNGER_STIMULUS = EventTypeId.of("body", "hunger-stimulus");
    /** {@code body.energy-depleted.v1}。 */
    public static final EventTypeId ENERGY_DEPLETED = EventTypeId.of("body", "energy-depleted");

    static {
        // 四条内置刺激的类型名各校验一次 —— 见 ClothingSet.ClothingChanged 里同一做法的说明:
        // 拼错的事件类型不会报错, 它只会安静地没有任何接收者。
        for (EventTypeId type : new EventTypeId[]{COLD_STIMULUS, HEAT_STIMULUS,
                HUNGER_STIMULUS, ENERGY_DEPLETED}) {
            if (!CoreEventCatalog.isCore(type)) {
                throw new IllegalStateException("刺激类型 " + type + " 不在 CoreEventCatalog 里");
            }
        }
    }

    /** 检测器服务的那具身体 —— 它只读, 从不改写(改写是 {@link Body} 自己的事)。 */
    private final Body body;

    /** 通道 → 当前锁存的方向({@code -1} 偏低 / {@code +1} 偏高 / 缺省 = 未锁存)。 */
    private final Map<String, Integer> latched = new HashMap<>();

    /** 通道 → 最近一次"路径二"报警的时刻, 用于冷却。 */
    private final Map<String, Instant> lastRateAlert = new HashMap<>();

    private final List<String> recentAlerts = new ArrayList<>();

    private static final int RECENT_ALERT_LIMIT = 32;

    public ThresholdDetector(Body body) {
        this.body = Objects.requireNonNull(body,
                "检测器必须挂在具体某一具身体上 —— 否则它不知道要读谁的状态");
    }

    // ═══════════════════════ EventHandler: 它为什么"订阅为空" ═══════════════════════

    /**
     * <b>空集合</b> —— 本类不订阅任何事件类型。
     *
     * <p>这是刻意的, 而且它回答了一个常见的设计误用: "身体越界"不是一条<b>到达的事件</b>,
     * 而是账本结算之后才成立的<b>状态</b>。若让它去订阅 {@code environment.temperature-changed.v1},
     * 就会退化成"每次环境刷新检查一次" —— 而环境刷新的频率决定了它检查的频率,
     * 于是"她冷得多快"又一次变成了实现细节(见 {@code ContinuousEffectLedger} 类注释里
     * 对同一个错误的警告)。
     *
     * <p>所以本类只挂在 tick 上, 每个 tick 都看一眼刚算出来的状态 —— 与刷新频率无关。
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
        return "ThresholdDetector[" + body.id() + "]";
    }

    /**
     * 永远不会被调用 —— 本类订阅为空, 所以没有任何到达事件会被路由过来。
     *
     * <p>留一个会抛异常的实现, 而不是空实现: 如果有一天有人给它加了订阅却忘了实现
     * 这个分支, 那一定是一个装配错误, 而静默的空实现会让这个错误一直藏着。
     */
    @Override
    public void handle(WorldEvent event, EventFabric fabric) {
        throw new IllegalStateException(
                "ThresholdDetector[" + body.id() + "] 不订阅任何事件, 却收到了一条 " + event.typeId()
                        + " —— 这是装配错误: 越界是结算后的状态, 不是到达的事件");
    }

    // ═══════════════════════ 主流程 ═══════════════════════

    @Override
    public void onTick(ContinuousEffectLedger.Settlement settlement,
                       HumanRuntimeContext ctx,
                       EventFabric fabric) {
        Objects.requireNonNull(ctx, "tick 上下文不能为空 —— 它给出仿真时刻");
        Objects.requireNonNull(fabric, "事件总线不能为空 —— 刺激要投给它才有人收得到");

        PhysiologicalState state = body.state();
        PhysiologicalState.Delta delta = body.lastDelta().orElse(null);
        Instant now = ctx.now();

        for (String channel : PhysiologicalState.CHANNELS) {
            if (CoreEventCatalog.Channels.COMFORT.equals(channel)) {
                // 舒适度是<b>派生量</b>(由其余九条算出), 检测它等于把同一个越界检测两遍,
                // 而她的感觉不会因为"综合舒适度低"而多一条 —— 她只会因为某条具体的通道难受。
                continue;
            }
            inspect(channel, state, delta, now, fabric);
        }
        body.onThresholdSweepCompleted(now);
    }

    /**
     * 检查一条通道的两条路径。
     *
     * <p>顺序是<b>先解除锁存, 再判断触发</b> —— 反过来会让"她刚缓过来又立刻冷下去"
     * 这个场景少触发一次, 因为锁存还挂在那里。
     */
    private void inspect(String channel,
                         PhysiologicalState state,
                         PhysiologicalState.Delta delta,
                         Instant now,
                         EventFabric fabric) {
        ComfortBand band = ComfortBand.forChannel(channel);
        double value = state.value(channel);
        int side = band.side(value);

        // ── 先看锁存要不要解除(回差) ──
        Integer previous = latched.get(channel);
        if (previous != null) {
            double release = band.releaseValue(previous);
            boolean recovered = previous < 0 ? value >= release : value <= release;
            if (recovered) {
                latched.remove(channel);
                previous = null;
            }
        }

        // ── 路径一: 阈值越界(边沿触发) ──
        if (side != 0 && (previous == null || previous != side)) {
            latched.put(channel, side);
            double deviation = band.deviation(value);
            alert(channel, side, value, deviation, band, "越界", now, fabric, true);
        }

        // ── 路径二: 变化率 ──
        if (delta == null) {
            return;
        }
        double rate = delta.rate(channel);
        String reason;
        if (band.convergingOnBoundary(value, rate, RATE_PATH_LOOKAHEAD_SECONDS)) {
            reason = String.format("照当前速率 %.5f/s, %.0f 秒内会越界", rate, RATE_PATH_LOOKAHEAD_SECONDS);
        } else if (band.deteriorating(value, rate)) {
            reason = String.format("已越界且仍在恶化(%.5f/s)", rate);
        } else {
            return;
        }
        Instant lastAlert = lastRateAlert.get(channel);
        if (lastAlert != null && Duration.between(lastAlert, now).compareTo(RATE_PATH_COOLDOWN) < 0) {
            // 冷却中 —— 这正是"不刷屏"的保证。她还在变冷, 但她 5 分钟前已经被告知过了。
            return;
        }
        lastRateAlert.put(channel, now);
        int direction = side != 0 ? side : (rate < 0 ? -1 : 1);
        alert(channel, direction, value, band.deviation(value), band, reason, now, fabric, false);
    }

    /**
     * 真的产生一条刺激 —— <b>两件事同时发生, 而它们不是重复</b>。
     *
     * <ol>
     *   <li>{@link TactileChannel#feelInternal} —— 把它放进她<b>自己的体感通道</b>。
     *       这是"她感觉到了"这件事本身, 也是"又饿又冷时先处理哪个"能成立的前提
     *       (两条刺激在同一条通道、同一个缓冲里按紧迫度竞争);</li>
     *   <li>{@link EventFabric#publish} —— 把它作为一条 {@code SensoryEvent} 投出去,
     *       于是它进入 {@code RealtimeEventQueue}、落进历史、能被重放与被行为分析读到。</li>
     * </ol>
     * 两者形状相同({@code SensoryStimulus} 与 {@code SensoryEvent}), 但用途不同:
     * 前者是她此刻的<b>知觉</b>, 后者是系统里的<b>事实</b>。
     *
     * <p><b>给宿主应用的警告</b>: 这两种投递方式若是同时被 Mind 读走, 她会收到两次同样的信号。
     * 所以部署时必须二选一 —— 进程内嵌看 {@code Body.drainSensoryStimuli()},
     * 事件溯源架构看 {@code EventFabric.stimuli()}。这件事没有"都对"的选项, 只有"一致"的选项。
     *
     * @param fromThreshold {@code true} = 路径一(越界), {@code false} = 路径二(变化率)
     */
    private void alert(String channel,
                       int side,
                       double value,
                       double deviation,
                       ComfortBand band,
                       String reason,
                       Instant now,
                       EventFabric fabric,
                       boolean fromThreshold) {
        String kind = internalKind(channel, side);
        String description = String.format("%s: %s = %.3f (舒适带 [%.2f, %.2f], 偏离 %.3f) —— %s%s",
                fromThreshold ? "越界" : "趋势", channel, value, band.low(), band.high(),
                deviation, reason,
                fromThreshold ? "" : " [变化率路径]");
        double intensity = Math.min(1.0, fromThreshold ? deviation
                : Math.max(deviation, 0.35));   // 路径二在还没越界时也要有足够的存在感

        // ① 她的体感
        body.tactile().feelInternal(kind, intensity,
                fromThreshold ? -deviation : deviation, channel, now, description);

        // ② 系统里的事实
        double urgency = urgencyOf(channel, side, fromThreshold);
        SensoryEvent event = buildStimulus(channel, side, value, band, deviation, urgency,
                description, now, fabric);
        fabric.publish(event);

        recentAlerts.add(description);
        if (recentAlerts.size() > RECENT_ALERT_LIMIT) {
            recentAlerts.remove(0);
        }
    }

    /**
     * 按通道与方向挑一条目录里已有的刺激类型。
     *
     * <p>只有四条通道有内置类型(冷/热/饿/精力耗尽)。其余通道走
     * {@link ThresholdBreach} —— 也就是<b>派生的类型名</b> {@code body.<名字>-threshold-breached.v1}。
     * 这不是绕过目录, 而是 P4 的正面用法: 类型名是数据, 目录只是"平台已知清单"。
     * 第三方接入一个 {@code body.glucose} 通道时, 它的越界刺激自然会有
     * {@code body.glucose-threshold-breached.v1} 这个名字, 全程不需要改平台源码。
     *
     * <p>这四条内置通道<b>不</b>走通用路径, 因为它们的载荷里带着只有它们才有的东西
     * (冷的 {@code contributors}、饿的 {@code lastMealAt}) —— 那些信息属于"她的处境",
     * 而通用越界事件给不出。
     */
    private SensoryEvent buildStimulus(String channel,
                                       int side,
                                       double value,
                                       ComfortBand band,
                                       double deviation,
                                       double urgency,
                                       String description,
                                       Instant now,
                                       EventFabric fabric) {
        String humanId = body.id();
        if (CoreEventCatalog.Channels.WARMTH.equals(channel) && side < 0) {
            // deficit 是<b>原始差额</b>(0.45 − 0.28 = 0.17), 不是归一化后的偏离程度。
            // 两个数都有用: 差额是"还差多少才能舒服" —— 直接对应"多穿一件够不够";
            // 而 0~1 的偏离程度已经进了 {@code SensoryStimulus.intensity}, 不重复放。
            double deficit = band.below(value) ? band.low() - value : 0.0;
            return new ColdStimulus(value, band.low(), deficit,
                    contributorsOf(fabric), humanId, now, urgency);
        }
        if (CoreEventCatalog.Channels.WARMTH.equals(channel) && side > 0) {
            return new HeatStimulus(value, band.high(), deviation, humanId, now, urgency);
        }
        if (CoreEventCatalog.Channels.HUNGER.equals(channel) && side > 0) {
            return new HungerStimulus(value, body.lastMealAt(), humanId, now, urgency);
        }
        if (CoreEventCatalog.Channels.ENERGY.equals(channel) && side < 0) {
            return new EnergyDepleted(value, band.low(), humanId, now, urgency);
        }
        return new ThresholdBreach(channel, side, value, band.low(), band.high(), band.mid(),
                deviation, humanId, now, urgency, description);
    }

    /** 谁把这具身体弄成这样的 —— 账本账目的类型名, 给行为分析一条可读的因果线。 */
    private List<String> contributorsOf(EventFabric fabric) {
        return body.lastSettlement()
                .map(s -> s.why(CoreEventCatalog.Channels.WARMTH).stream()
                        .map(e -> e.typeId().toString())
                        .distinct()
                        .toList())
                .orElse(List.of());
    }

    /**
     * 这条刺激有多急 —— {@code RealtimeEventQueue} 用它定序。
     *
     * <p>它不是"多重要"(那是 Mind 的 salience), 而是"<b>能不能等</b>"。
     * 冷与热都是 0.9 一档: 体温问题不能等, 因为它会死人, 而且它不会自己好。
     * 饿是 0.6 —— 饿一小时不会出事。精力耗尽是 0.7。
     * 路径二<b>降一档</b>: "照这个速度你会冷"终究不如"你已经冷了"来得急。
     */
    private double urgencyOf(String channel, int side, boolean fromThreshold) {
        double base;
        if (CoreEventCatalog.Channels.WARMTH.equals(channel)) {
            base = 0.9;
        } else if (CoreEventCatalog.Channels.HUNGER.equals(channel)) {
            base = 0.6;
        } else if (CoreEventCatalog.Channels.ENERGY.equals(channel)) {
            base = 0.7;
        } else if (CoreEventCatalog.Channels.PAIN.equals(channel)) {
            base = 0.85;
        } else {
            base = 0.5;
        }
        return fromThreshold ? base : Math.max(0.2, base - 0.2);
    }

    /** 通道 + 方向 → {@link TactileChannel.Kinds} 里的类别标签。 */
    private String internalKind(String channel, int side) {
        if (CoreEventCatalog.Channels.WARMTH.equals(channel)) {
            return side < 0 ? TactileChannel.Kinds.COLD : TactileChannel.Kinds.HEAT;
        }
        if (CoreEventCatalog.Channels.HUNGER.equals(channel)) {
            return TactileChannel.Kinds.HUNGER;
        }
        if (CoreEventCatalog.Channels.THIRST.equals(channel)) {
            return TactileChannel.Kinds.THIRST;
        }
        if (CoreEventCatalog.Channels.FATIGUE.equals(channel)) {
            return TactileChannel.Kinds.FATIGUE;
        }
        if (CoreEventCatalog.Channels.SLEEP_PRESSURE.equals(channel)) {
            return TactileChannel.Kinds.SLEEPY;
        }
        if (CoreEventCatalog.Channels.ENERGY.equals(channel)) {
            return TactileChannel.Kinds.ENERGY_DEPLETED;
        }
        if (CoreEventCatalog.Channels.PAIN.equals(channel)) {
            return TactileChannel.Kinds.PAIN;
        }
        return TactileChannel.Kinds.WORSENING;
    }

    // ═══════════════════════ 状态查询(诊断与测试) ═══════════════════════

    /**
     * 某条通道现在是不是处在"已经报警过、还没缓过来"的状态。
     *
     * <p>它是<b>判断</b>, 不是<b>状态</b>: 这个锁存只用来做去重, 不参与任何决策。
     * 她冷不冷这个问题永远由 {@code state + band} 现场算出, 见 {@link PhysiologicalState} 的说明。
     */
    public boolean isLatched(String channel) {
        return latched.containsKey(channel);
    }

    /** 当前被锁存的全部通道。 */
    public Set<String> latchedChannels() {
        return Set.copyOf(latched.keySet());
    }

    /** 最近产生的刺激(最多 {@value #RECENT_ALERT_LIMIT} 条) —— 给诊断面板。 */
    public List<String> recentAlerts() {
        return List.copyOf(recentAlerts);
    }

    /** 全部锁存与冷却的摘要。 */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        latched.forEach((channel, side) -> m.put(channel, side < 0 ? "已报警(偏低)" : "已报警(偏高)"));
        lastRateAlert.forEach((channel, at) ->
                m.put(channel + "#rate", "最近一次趋势提醒 " + at));
        return Map.copyOf(m);
    }

    /** 清空锁存与冷却 —— <b>只给测试与"重新初始化"用</b>。 */
    public void reset() {
        latched.clear();
        lastRateAlert.clear();
        recentAlerts.clear();
    }

    // ═══════════════════════ 四条内置刺激 ═══════════════════════

    /**
     * 觉得冷 —— 用户那条链的终点。
     *
     * <p>载荷与 §5.4 的 {@code body.cold-stimulus.v1} 完全一致:
     * {@code warmth, comfortLow, deficit, contributors}。
     * {@code contributors} 是<b>账本上的账目类型名</b> —— 它让"她为什么冷"有一个
     * 不需要推理的答案: 是 {@code environment.temperature-changed} 还是
     * {@code environment.wind-started}, 一眼可见。
     */
    public record ColdStimulus(double warmth,
                               double comfortLow,
                               double deficit,
                               List<String> contributors,
                               String humanId,
                               Instant occurredAt,
                               double urgency) implements SensoryEvent {

        public ColdStimulus {
            contributors = contributors == null ? List.of() : List.copyOf(contributors);
            Objects.requireNonNull(occurredAt, "刺激必须带发生时刻");
        }

        @Override
        public EventTypeId typeId() {
            return COLD_STIMULUS;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.TACTILE;
        }

        /**
         * 持续存在 —— 冷不是"响一下就完了"的事, 它会一直持续到她做点什么。
         *
         * <p>这正是 {@code ongoing} 存在的理由: 队列按 {@link #foldingKey()} 把它折叠,
         * 而不是让"她还在冷"每 tick 塞一条新刺激进来。注意它与本检测器的锁存是
         * <b>两道独立的去重</b>: 锁存挡住"重复报警", folding 挡住"重复入队"。
         */
        @Override
        public boolean ongoing() {
            return true;
        }

        @Override
        public String foldingKey() {
            return "tactile:cold:" + humanId;
        }

        @Override
        public String sourceObjectId() {
            return null;   // 冷没有外部来源 —— 它是她自己的身体状态
        }

        @Override
        public String describe() {
            return String.format("觉得冷: 保暖值 %.3f 低于舒适下沿 %.2f (差 %.3f), 原因 %s",
                    warmth, comfortLow, deficit, contributors);
        }
    }

    /** 觉得热 —— 与冷对称。 */
    public record HeatStimulus(double warmth,
                               double comfortHigh,
                               double excess,
                               String humanId,
                               Instant occurredAt,
                               double urgency) implements SensoryEvent {

        public HeatStimulus {
            Objects.requireNonNull(occurredAt, "刺激必须带发生时刻");
        }

        @Override
        public EventTypeId typeId() {
            return HEAT_STIMULUS;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.TACTILE;
        }

        @Override
        public boolean ongoing() {
            return true;
        }

        @Override
        public String foldingKey() {
            return "tactile:heat:" + humanId;
        }

        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public String describe() {
            return String.format("觉得热: 保暖值 %.3f 高于舒适上沿 %.2f (超出 %.3f)",
                    warmth, comfortHigh, excess);
        }
    }

    /** 饿了 —— {@code lastMealAt} 为空表示"系统不知道她上顿什么时候吃的", 而不是"她没吃过"。 */
    public record HungerStimulus(double hunger,
                                 Instant lastMealAt,
                                 String humanId,
                                 Instant occurredAt,
                                 double urgency) implements SensoryEvent {

        public HungerStimulus {
            Objects.requireNonNull(occurredAt, "刺激必须带发生时刻");
        }

        @Override
        public EventTypeId typeId() {
            return HUNGER_STIMULUS;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.TACTILE;
        }

        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public String describe() {
            return "饿了: 饥饿值 " + String.format("%.3f", hunger)
                    + (lastMealAt == null ? " (上一餐时间未知)" : ", 上一餐 " + lastMealAt);
        }
    }

    /** 精力见底。 */
    public record EnergyDepleted(double energy,
                                 double threshold,
                                 String humanId,
                                 Instant occurredAt,
                                 double urgency) implements SensoryEvent {

        public EnergyDepleted {
            Objects.requireNonNull(occurredAt, "刺激必须带发生时刻");
        }

        @Override
        public EventTypeId typeId() {
            return ENERGY_DEPLETED;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.TACTILE;
        }

        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public String describe() {
            return String.format("精力见底: %.3f 低于阈值 %.2f", energy, threshold);
        }
    }

    /**
     * 派生刺激 —— 平台没有为这条通道定过类型时用它。
     *
     * <h2>类型名是<b>算出来</b>的, 这会不会失控</h2>
     * 不会, 因为名字的来源是<b>账本上真实存在的通道</b>, 而通道只能由一条真实的事件带来。
     * 换句话说: 谁能产生这条刺激, 谁就必须先拥有一条能产生账目的通道;
     * 而一个能投递持续影响事件的三方组件, 本来就有权声明自己的通道。
     *
     * <p>反过来, 若这里改成"未知通道一律不产生刺激", 后果是: 第三方接入的
     * {@code body.glucose} 越界时她<b>什么都感觉不到</b> —— 而那正是 P4 想避免的那类
     * "平台作者没想到的领域, 就永远进不来"。
     *
     * <p>唯一需要小心的是<b>名字冲突</b>: {@code body.energy} 派生出的
     * {@code body.energy-threshold-breached.v1} 与内置的 {@code body.energy-depleted.v1}
     * 并存 —— 这不是冲突, 因为前者永远不会被产生(精力通道走的是内置分支)。
     */
    public record ThresholdBreach(String channel,
                                  int side,
                                  double value,
                                  double bandLow,
                                  double bandHigh,
                                  double bandMid,
                                  double deviation,
                                  String humanId,
                                  Instant occurredAt,
                                  double urgency,
                                  String description) implements SensoryEvent {

        /** 派生类型名 —— {@code body.<短名>-threshold-breached}。 */
        public static EventTypeId typeFor(String channel) {
            String shortName = channel.startsWith("body.") ? channel.substring("body.".length()) : channel;
            String safe = shortName.replace('.', '-');
            return EventTypeId.of("body", safe + "-threshold-breached");
        }

        public ThresholdBreach {
            Objects.requireNonNull(channel, "越界事件必须知道自己守的是哪条通道");
            Objects.requireNonNull(occurredAt, "刺激必须带发生时刻");
        }

        @Override
        public EventTypeId typeId() {
            return typeFor(channel);
        }

        @Override
        public String modality() {
            // 内部越界一律走触觉 —— 见 TactileChannel 的说明("饿是胃里的感觉")。
            // 一条第三方通道若确实属于别的感官, 它的生产者应当自己投递, 而不是借道这里。
            return CoreEventCatalog.Modalities.TACTILE;
        }

        @Override
        public boolean ongoing() {
            return true;
        }

        @Override
        public String foldingKey() {
            return "tactile:" + channel + ":" + humanId;
        }

        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public String describe() {
            return String.format("%s 越界: %.3f 在 [%.2f, %.2f] 之外 (%s), %s",
                    channel, value, bandLow, bandHigh, side < 0 ? "偏低" : "偏高", description);
        }
    }
}
