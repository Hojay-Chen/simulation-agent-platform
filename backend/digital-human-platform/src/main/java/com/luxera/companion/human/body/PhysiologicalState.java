package com.luxera.companion.human.body;

import com.luxera.companion.registry.CoreEventCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * V2.2 §3.2.3 —— <b>某一瞬间她身体的状态</b>, 一个不可变快照。
 *
 * <h2>为什么它必须是 {@code record} / 不可变</h2>
 * 因为 {@code TickAware.onTick(ContinuousEffectLedger.Settlement, ...)} 这个签名里
 * 有一个容易被忽略的承诺: <b>结算快照可以被安全地持有</b>(见 {@code TickAware} 的说明)。
 * 同一个逻辑必须对 {@code PhysiologicalState} 成立, 否则"趋势/diff"这件事根本没法做:
 * <pre>{@code
 *   // 她这个 tick 掉了多少 —— 这正是用户要求的"变化产生的 diff"
 *   PhysiologicalState.Delta delta =
 *           PhysiologicalState.Delta.between(snapshotAtLastTick, current, elapsedSeconds);
 *
 *   // 若 PhysiologicalState 是可变的, 下面这一行会同时改掉 previous ——
 *   // 因为两者是同一个对象。diff 恒等于 0, 而系统不会报任何错。
 *   this.current = model.step(this.current, settlement, dt, ...);
 * }</pre>
 * 在这套模型里, "上一刻"与"这一刻"必须是<b>两个对象</b>, 这不是性能取舍, 是正确性前提。
 *
 * <h2>为什么历史快照要<b>有界</b>保留</h2>
 * 因为行为分析里最常见的三个问题都需要"过去":
 * <table border="1">
 *   <tr><th>问题</th><th>需要多少历史</th></tr>
 *   <tr><td>她刚才那一下为什么突然觉得冷</td><td>最近几个 tick 的 diff</td></tr>
 *   <tr><td>她是不是在缓慢失温</td><td>十几分钟的走势</td></tr>
 *   <tr><td>她今天过得怎么样</td><td>整天的极值与总量, 不是逐 tick 明细</td></tr>
 * </table>
 * 前两个用<b>环形缓冲</b>解决(见 {@code Body} 的 {@code SNAPSHOT_HISTORY_LIMIT}),
 * 第三个用落库解决。无限保留逐 tick 快照是最糟的选择: 一个跑一天的仿真会产生
 * 几万条快照, 而其中 99% 永远不会被读 —— 它换来的是内存增长与一次 OOM,
 * 而不是任何一个新问题的答案。
 *
 * <h2>它<b>不</b>存放"我现在是不是觉得冷"</h2>
 * 这是本类最重要的边界。{@code ThresholdDetector} 必须能从<b>这份状态 + 舒适带</b>
 * 重新算出结论, 而不是读一个缓存下来的布尔值。理由同 {@code ContinuousEffectLedger}:
 * <b>"冷不冷"不是一个事实, 是一个结算</b>。把她冷不冷写成字段, 就必然要回答
 * "谁负责在什么时候把它改回去", 而那个问题没有好答案 ——
 * 这正是"她明明暖和了却还觉得自己冷"这类 bug 的产地。
 *
 * <h2>通道是开放的, 但都在 {@code [0, 1]}</h2>
 * {@link #extraChannels()} 让第三方可以塞进 {@code body.glucose} 这类通道
 * (见 {@code ComfortBand.forChannel} 的说明)。它们也被要求归一化,
 * 因为一个量程未知的通道没有舒适带可言, 而没有舒适带就无法参与"越界"这件事。
 */
public record PhysiologicalState(double warmth,
                                 double wetness,
                                 double energy,
                                 double fatigue,
                                 double hunger,
                                 double thirst,
                                 double sleepPressure,
                                 double pain,
                                 double stress,
                                 double comfort,
                                 double coreTemperature,
                                 double heartRate,
                                 double breathingRate,
                                 Map<String, Double> extraChannels) {

    /**
     * Body <b>拥有</b>的十条通道, 顺序即诊断面板的展示顺序。
     *
     * <h3>为什么 {@code mind.attention-load} 与 {@code mind.mood} 不在这个表里</h3>
     * 因为它们的名字是 {@code mind.*}, 而 {@code Mind} 是 Body 的<b>兄弟</b>, 不是下游
     * (见 P3 与 {@code body}/{@code mind} 的包划分)。Body 会在结算时看到它们
     * ——账本是按通道名求和的, 它不区分前缀—— 但它<b>不</b>把这两个数抄进自己的状态。
     * 抄进来会造成一个具体的坏结果: 注意力负荷有了两份真相, 一份在她身上、
     * 一份在 Mind 里, 而它们会在某次"Mind 改了但我没同步"之后永久分叉。
     */
    public static final List<String> CHANNELS = List.of(
            CoreEventCatalog.Channels.WARMTH,
            CoreEventCatalog.Channels.WETNESS,
            CoreEventCatalog.Channels.ENERGY,
            CoreEventCatalog.Channels.FATIGUE,
            CoreEventCatalog.Channels.HUNGER,
            CoreEventCatalog.Channels.THIRST,
            CoreEventCatalog.Channels.SLEEP_PRESSURE,
            CoreEventCatalog.Channels.PAIN,
            CoreEventCatalog.Channels.STRESS,
            CoreEventCatalog.Channels.COMFORT);

    /** 归一化通道里"极值"的约定边界 —— 见 {@link ComfortBand#deviation(double)}。 */
    public static final double MIN_VALUE = 0.0;
    public static final double MAX_VALUE = 1.0;

    /** 健康成年人的核心体温(℃)。 */
    public static final double NORMAL_CORE_TEMPERATURE = 36.8;
    /** 静息心率(bpm)。 */
    public static final double RESTING_HEART_RATE = 68.0;
    /** 静息呼吸频率(次/分)。 */
    public static final double RESTING_BREATHING_RATE = 14.0;

    private static final double CORE_TEMPERATURE_FLOOR = 25.0;
    private static final double CORE_TEMPERATURE_CEILING = 45.0;
    private static final double HEART_RATE_FLOOR = 20.0;
    private static final double HEART_RATE_CEILING = 220.0;
    private static final double BREATHING_FLOOR = 4.0;
    private static final double BREATHING_CEILING = 60.0;

    public PhysiologicalState {
        requireUnit(warmth, CoreEventCatalog.Channels.WARMTH);
        requireUnit(wetness, CoreEventCatalog.Channels.WETNESS);
        requireUnit(energy, CoreEventCatalog.Channels.ENERGY);
        requireUnit(fatigue, CoreEventCatalog.Channels.FATIGUE);
        requireUnit(hunger, CoreEventCatalog.Channels.HUNGER);
        requireUnit(thirst, CoreEventCatalog.Channels.THIRST);
        requireUnit(sleepPressure, CoreEventCatalog.Channels.SLEEP_PRESSURE);
        requireUnit(pain, CoreEventCatalog.Channels.PAIN);
        requireUnit(stress, CoreEventCatalog.Channels.STRESS);
        requireUnit(comfort, CoreEventCatalog.Channels.COMFORT);
        requireRange(coreTemperature, CORE_TEMPERATURE_FLOOR, CORE_TEMPERATURE_CEILING, "核心体温");
        requireRange(heartRate, HEART_RATE_FLOOR, HEART_RATE_CEILING, "心率");
        requireRange(breathingRate, BREATHING_FLOOR, BREATHING_CEILING, "呼吸频率");

        Map<String, Double> extra = new LinkedHashMap<>();
        if (extraChannels != null) {
            extraChannels.forEach((channel, value) -> {
                Objects.requireNonNull(channel, "扩展通道名不能为空");
                if (CHANNELS.contains(channel)) {
                    throw new IllegalArgumentException(
                            "扩展通道 " + channel + " 与平台自带通道重名 —— "
                                    + "两份真相必然分叉, 见 PhysiologicalState 的通道表");
                }
                requireUnit(value, channel);
                extra.put(channel, value);
            });
        }
        extraChannels = Map.copyOf(extra);
    }

    private static void requireUnit(double value, String channel) {
        if (Double.isNaN(value) || value < MIN_VALUE || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "生理通道 " + channel + " 必须归一化到 [" + MIN_VALUE + ", " + MAX_VALUE
                            + "], 收到 " + value + " —— 量程之外的通道没有舒适带可言");
        }
    }

    private static void requireRange(double value, double floor, double ceiling, String what) {
        if (Double.isNaN(value) || value < floor || value > ceiling) {
            throw new IllegalArgumentException(
                    what + "必须落在 [" + floor + ", " + ceiling + "], 收到 " + value
                            + " —— 超出这个范围的值不是「极端」, 而是模型已经发散");
        }
    }

    /**
     * 一个健康的初始状态 —— 每个 agent 的生命从这一刻开始。
     *
     * <p>刻意<b>不</b>从"全部为 0"开始: 0 意味着"体温 0℃、精力 0、饿到极点",
     * 而那是一个濒死的人。从濒死开始跑仿真, 会让她在最初几分钟里产生一串
     * 与剧情无关的求救刺激, 而那些刺激会盖住你真正想观察的东西。
     */
    public static PhysiologicalState initial() {
        return new PhysiologicalState(
                0.72,   // warmth: 舒适带内偏上
                0.05,   // wetness: 干爽
                0.85,   // energy: 精力充沛
                0.15,   // fatigue: 不累
                0.20,   // hunger: 不饿
                0.15,   // thirst: 不渴
                0.20,   // sleepPressure: 刚睡醒
                0.0,    // pain: 不疼
                0.15,   // stress: 平静
                0.80,   // comfort: 舒服
                NORMAL_CORE_TEMPERATURE,
                RESTING_HEART_RATE,
                RESTING_BREATHING_RATE,
                Map.of());
    }

    // ─────────────────────────── 读写通道 ───────────────────────────

    /** 全部通道名 —— 平台自带的十条 + 第三方的扩展通道。 */
    public Set<String> channels() {
        if (extraChannels.isEmpty()) {
            return Set.copyOf(CHANNELS);
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>(CHANNELS);
        all.addAll(extraChannels.keySet());
        return Set.copyOf(all);
    }

    /**
     * 读一条通道。
     *
     * @throws IllegalArgumentException 通道名不属于 Body —— 见类注释里关于 {@code mind.*} 的说明
     */
    public double value(String channel) {
        Objects.requireNonNull(channel, "通道名不能为空");
        Double extra = extraChannels.get(channel);
        if (extra != null) {
            return extra;
        }
        int index = CHANNELS.indexOf(channel);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "通道 \"" + channel + "\" 不属于 Body。Body 拥有 " + CHANNELS
                            + "; 以 mind. 开头的通道属于 Mind(兄弟组件, 见 P3)");
        }
        return switch (index) {
            case 0 -> warmth;
            case 1 -> wetness;
            case 2 -> energy;
            case 3 -> fatigue;
            case 4 -> hunger;
            case 5 -> thirst;
            case 6 -> sleepPressure;
            case 7 -> pain;
            case 8 -> stress;
            default -> comfort;
        };
    }

    /** 读一条通道, 不认识就返回空 —— 给"遍历账本结算里的任意通道"这种场景用。 */
    public OptionalDouble maybeValue(String channel) {
        if (channel == null) {
            return OptionalDouble.empty();
        }
        if (extraChannels.containsKey(channel) || CHANNELS.contains(channel)) {
            return OptionalDouble.of(value(channel));
        }
        return OptionalDouble.empty();
    }

    /**
     * 换一条通道的值, 返回<b>新的</b>快照。
     *
     * <p>不在 {@code CHANNELS} 里也不在扩展通道里时<b>会创建</b>它 ——
     * 这正是第三方通道的接入方式(见 {@code ComfortBand.forChannel})。
     * 但 {@code mind.*} 会被挡下: 那是 Mind 的地盘, 见类注释。
     *
     * <h3>越界的值会被<b>夹住</b>, 而不是抛异常</h3>
     * 这与构造器里的校验不矛盾, 而是同一条原则的两半: 构造器挡的是<b>有人写错数据</b>
     * (一条 {@code warmth = 3.7} 的种子数据必须当场失败), 而这里是稳态模型在推进 ——
     * 一个 {@code warmth + Δ} 的中间结果越界是<b>正常的</b>, 收敛回来是模型自己的事。
     *
     * <p>若这里也抛异常, 那么一次 {@code Δt} 特别大的推进(比如仿真时间跳了一小时)
     * 会直接崩掉一次 tick, 而正确的行为显然是"她冷到底了", 不是"程序出错"。
     */
    public PhysiologicalState with(String channel, double newValue) {
        Objects.requireNonNull(channel, "通道名不能为空");
        if (channel.startsWith("mind.")) {
            throw new IllegalArgumentException(
                    "Body 不能改写 " + channel + " —— Mind 是兄弟组件, 它的通道由它自己维护");
        }
        int index = CHANNELS.indexOf(channel);
        if (index < 0) {
            Map<String, Double> merged = new LinkedHashMap<>(extraChannels);
            merged.put(channel, clamp01(newValue));
            return new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate,
                    breathingRate, merged);
        }
        double v = clamp01(newValue);
        return switch (index) {
            case 0 -> new PhysiologicalState(v, wetness, energy, fatigue, hunger, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 1 -> new PhysiologicalState(warmth, v, energy, fatigue, hunger, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 2 -> new PhysiologicalState(warmth, wetness, v, fatigue, hunger, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 3 -> new PhysiologicalState(warmth, wetness, energy, v, hunger, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 4 -> new PhysiologicalState(warmth, wetness, energy, fatigue, v, thirst,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 5 -> new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, v,
                    sleepPressure, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 6 -> new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                    v, pain, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 7 -> new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                    sleepPressure, v, stress, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            case 8 -> new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                    sleepPressure, pain, v, comfort, coreTemperature, heartRate, breathingRate, extraChannels);
            default -> new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                    sleepPressure, pain, stress, v, coreTemperature, heartRate, breathingRate, extraChannels);
        };
    }

    // ─────────────────────────── 派生量 ───────────────────────────

    /** 核心体温(℃) —— 派生量, 见 {@code HomeostasisModel}。越界会被夹到物理量程内。 */
    public PhysiologicalState withCoreTemperature(double celsius) {
        return new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                sleepPressure, pain, stress, comfort,
                clamp(celsius, CORE_TEMPERATURE_FLOOR, CORE_TEMPERATURE_CEILING),
                heartRate, breathingRate, extraChannels);
    }

    /** 心率(bpm) —— 派生量。 */
    public PhysiologicalState withHeartRate(double bpm) {
        return new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                sleepPressure, pain, stress, comfort, coreTemperature,
                clamp(bpm, HEART_RATE_FLOOR, HEART_RATE_CEILING), breathingRate, extraChannels);
    }

    /** 呼吸频率(次/分) —— 派生量。 */
    public PhysiologicalState withBreathingRate(double perMinute) {
        return new PhysiologicalState(warmth, wetness, energy, fatigue, hunger, thirst,
                sleepPressure, pain, stress, comfort, coreTemperature, heartRate,
                clamp(perMinute, BREATHING_FLOOR, BREATHING_CEILING), extraChannels);
    }

    /**
     * 她此刻整体上舒服吗 —— 十条通道<b>全部</b>在舒适带内。
     *
     * <p>这是一个"与"而不是"或": 只要有一条越界, 她就有理由去做点什么。
     * 用或的话, "又冷又饿"会被判成舒服, 因为饿还没到最严重的程度 ——
     * 而那显然不是我们要的。
     *
     * <p>它用 {@link ComfortBand#forChannel} 取带, 所以第三方通道(宽松带)
     * 不会让它恒为 false。
     */
    public boolean comfortable() {
        for (String channel : CHANNELS) {
            if (!ComfortBand.forChannel(channel).inside(value(channel))) {
                return false;
            }
        }
        for (String channel : extraChannels.keySet()) {
            if (!ComfortBand.forChannel(channel).inside(value(channel))) {
                return false;
            }
        }
        return true;
    }

    /** 把全部通道限制在 {@code [0, 1]}, 派生量限制在物理量程内 —— 见 {@link HomeostasisModel}。 */
    public PhysiologicalState clamped() {
        PhysiologicalState s = this;
        for (String channel : channels()) {
            s = s.with(channel, clamp01(s.value(channel)));
        }
        return s.withCoreTemperature(clamp(coreTemperature, CORE_TEMPERATURE_FLOOR, CORE_TEMPERATURE_CEILING))
                .withHeartRate(clamp(heartRate, HEART_RATE_FLOOR, HEART_RATE_CEILING))
                .withBreathingRate(clamp(breathingRate, BREATHING_FLOOR, BREATHING_CEILING));
    }

    public String describe() {
        return String.format(
                "保暖 %.2f 潮湿 %.2f 精力 %.2f 疲劳 %.2f 饿 %.2f 渴 %.2f 睡眠压力 %.2f 痛 %.2f 压力 %.2f 舒适 %.2f "
                        + "| 体温 %.1f℃ 心率 %.0f 呼吸 %.0f%s",
                warmth, wetness, energy, fatigue, hunger, thirst, sleepPressure, pain, stress,
                comfort, coreTemperature, heartRate, breathingRate,
                extraChannels.isEmpty() ? "" : " | 扩展 " + extraChannels);
    }

    // ═══════════════════════════ 两个快照之间 ═══════════════════════════

    /**
     * <b>变化量</b> —— 用户那句"变化产生的 diff"在代码里的落点。
     *
     * <h2>为什么它必须是独立类型, 而不是一个 {@code double[]}</h2>
     * 因为"每秒变化多少"和"一共变了多少"是两个不同的量, 而它们会被用在不同的判断里:
     * <ul>
     *   <li>{@link ComfortBand#deteriorating(double, double)} 要的是<b>速率</b>
     *       —— "掉得多快";</li>
     *   <li>行为日志与落库要的往往是<b>增量</b> —— "这一小时掉了 0.3"。</li>
     * </ul>
     * 把两者混成一个数, 会让"仿真跑快了 10 倍"变成"她冷得快了 10 倍" ——
     * 而这个错误在 {@code ContinuousEffectLedger} 的类注释里已经被警告过一次
     * (刷新频率不该影响她冷得多快)。<b>速率是强度的正确表达, 增量不是。</b>
     *
     * @param perSecondRates 每个通道的每秒变化量(可为负)
     * @param elapsedSeconds 这段变化经过了多少仿真秒
     */
    public record Delta(Map<String, Double> perSecondRates, double elapsedSeconds) {

        public Delta {
            perSecondRates = perSecondRates == null ? Map.of() : Map.copyOf(perSecondRates);
            if (elapsedSeconds < 0.0) {
                throw new IllegalArgumentException("时间不能倒流, elapsedSeconds = " + elapsedSeconds);
            }
        }

        /**
         * 两个快照之间的差, 折算成每秒。
         *
         * @param previous       上一刻
         * @param current        这一刻
         * @param elapsedSeconds 两者之间经过的仿真秒 —— 为 0 时返回全零(而不是除零)
         */
        public static Delta between(PhysiologicalState previous, PhysiologicalState current,
                                    double elapsedSeconds) {
            Objects.requireNonNull(previous, "上一个快照不能为空");
            Objects.requireNonNull(current, "当前快照不能为空");
            if (elapsedSeconds < 0.0) {
                throw new IllegalArgumentException("时间不能倒流, elapsedSeconds = " + elapsedSeconds);
            }
            Map<String, Double> rates = new LinkedHashMap<>();
            if (elapsedSeconds > 0.0) {
                for (String channel : current.channels()) {
                    double now = current.value(channel);
                    double before = previous.maybeValue(channel).orElse(now);
                    rates.put(channel, (now - before) / elapsedSeconds);
                }
            } else {
                for (String channel : current.channels()) {
                    rates.put(channel, 0.0);
                }
            }
            return new Delta(rates, elapsedSeconds);
        }

        /**
         * 某条通道的每秒变化量。没有这个通道时返回 {@code 0.0}。
         *
         * <p>为什么返回 0 而不是抛异常: 一个刚出现的第三方通道在上一刻不存在,
         * 而"从不存在到存在"它的变化率<b>确实是未知的</b>, 用 0 表达这个未知
         * 比让一次 tick 崩掉更合适 —— 而且 0 不会触发任何"恶化"判断, 这正是我们想要的保守行为。
         */
        public double rate(String channel) {
            return perSecondRates.getOrDefault(channel, 0.0);
        }

        /** 变化最快的那条通道(按绝对值) —— 给日志用: "她这个 tick 主要是哪条通道在动"。 */
        public Optional<String> steepestChannel() {
            return perSecondRates.entrySet().stream()
                    .max(java.util.Comparator.comparingDouble(e -> Math.abs(e.getValue())))
                    .map(Map.Entry::getKey);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder("Δt=").append(String.format("%.1fs", elapsedSeconds));
            perSecondRates.forEach((channel, rate) -> {
                if (Math.abs(rate) >= 1e-6) {
                    sb.append(String.format(" %s=%+.5f/s", channel, rate));
                }
            });
            return sb.toString();
        }
    }

    private static double clamp01(double v) {
        return clamp(v, MIN_VALUE, MAX_VALUE);
    }

    private static double clamp(double v, double floor, double ceiling) {
        return v < floor ? floor : (v > ceiling ? ceiling : v);
    }
}
