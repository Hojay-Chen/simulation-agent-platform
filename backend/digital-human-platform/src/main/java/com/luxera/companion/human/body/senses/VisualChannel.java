package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * V2.2 §3.2.2 —— <b>视觉通道</b>。
 *
 * <table border="1">
 *   <tr><th>物理量</th><td>照度(lux)、色温(K)、方位角(°)</td></tr>
 *   <tr><th>量程</th><td>0.001–100000 lux, 视野 180°</td></tr>
 *   <tr><th>现实对接点(未来机器人)</th><td>摄像头 + 目标检测</td></tr>
 *   <tr><th>典型刺激</th><td>屏幕亮起、天黑、有人走近</td></tr>
 * </table>
 *
 * <h2>本通道有<b>状态</b>, 而这是它最要紧的设计点</h2>
 * 其他四条通道本质上是无状态的: 收到 → 衰减 → 解释 → 输出。视觉不是 ——
 * 它有一个<b>适应</b>过程:
 * <ul>
 *   <li><b>暗适应</b>: 从亮处进暗处, 前几分钟几乎什么都看不见, 之后才慢慢看清。
 *       生理上是视紫红质再生, 时间常数约 5–10 分钟;</li>
 *   <li><b>明适应</b>: 从暗处到亮处, 几秒内就刺眼得睁不开, 然后迅速恢复。</li>
 * </ul>
 * 这个不对称是<b>真实存在且可观测的</b>: "她刚关了灯所以没看见手机屏亮了一下"
 * 是一个只有在通道里建模适应才解释得通的现象。做成无状态通道, 这条解释就永远丢了。
 *
 * <h2>视野: "她为什么没看见"的第一个答案</h2>
 * 人眼水平视野约 180°, 但<b>清晰区只有中央 60°</b> 左右, 边缘只能察觉运动与亮暗。
 * 所以本通道:
 * <pre>{@code
 *   |方位角| <= 60°   → 权重 1.0      (看得清)
 *   60° < |方位| < 90° → 线性衰减到 0.35 (余光, 只能察觉"那边有东西")
 *   |方位| >= 90°      → 丢弃并记账    (视野之外, 她确实没看见)
 * }</pre>
 * 与听觉的频带权重一样, 这是"同一个刺激对不同朝向的她强度不同"的落点, 而不是
 * "她决定不看" —— 决定不看是 Mind 的注意力, 在更下游。
 *
 * <h2>它做两件"减法"而不做"加法"</h2>
 * 通道只能让刺激<b>变弱或消失</b>(衰减阈值、视野外丢弃), 永远不会让它变强。
 * 这条约束让"她看到了本来不存在的东西"在架构上不可能发生 —— 而那正是仿真系统里
 * 最难查的一类 bug。
 */
public final class VisualChannel extends SensoryChannel<RawSignal.Light> {

    /** 清晰视野的半角。 */
    public static final double SHARP_FIELD_DEGREES = 60.0;
    /** 视野的半角 —— 超过它的读数她确实看不见。 */
    public static final double FIELD_OF_VIEW_DEGREES = 90.0;
    /** 余光里的最低权重。 */
    public static final double PERIPHERAL_FLOOR = 0.35;

    /** 暗适应的目标阈限 —— 星光级别。 */
    public static final double DARK_ADAPTED_THRESHOLD_LUX = 0.0002;
    /** 暗适应的时间常数(秒)。取 5 分钟, 与视紫红质的再生速度同量级。 */
    public static final double DARK_ADAPTATION_TAU_SECONDS = 300.0;
    /** 明适应的时间常数(秒) —— 比暗适应快一个数量级, 这是真的事实。 */
    public static final double LIGHT_ADAPTATION_TAU_SECONDS = 20.0;

    private static final double DARK_LUX = 1.0;

    /**
     * 视觉刺激的类别标签 —— 字符串而不是枚举, 理由同 {@code AuditoryChannel.Kinds}。
     */
    public static final class Kinds {
        private Kinds() {
        }

        /** 环境光整体变亮(天亮、开灯)。 */
        public static final String ILLUMINATION_RISE = "illumination-rise";
        /** 环境光整体变暗(天黑、关灯)。 */
        public static final String ILLUMINATION_DROP = "illumination-drop";
        /** 视野里出现了一个点光源。手机屏亮起是它的典型。 */
        public static final String POINT_LIGHT = "point-light";
        /** 余光里有东西 —— 强度很低, 但"那边有动静"是一件她可能转头去看的事。 */
        public static final String PERIPHERAL_MOTION = "peripheral-motion";
        /** 什么都没有变。 */
        public static final String STEADY = "steady";
    }

    /** 上一次适应发生的时刻。 */
    private Instant lastAdaptedAt;

    public VisualChannel(String humanId) {
        this(humanId, SensoryAcuity.humanEye(), DEFAULT_CAPACITY);
    }

    public VisualChannel(String humanId, SensoryAcuity acuity, int capacity) {
        super(new ChannelId(humanId, CoreEventCatalog.Modalities.VISUAL), acuity, capacity);
    }

    @Override
    protected SensoryStimulus interpret(RawSignal.Light raw, double perceivedIntensity) {
        adaptToAmbient(raw.illuminanceLux(), raw.observedAt());

        double fovFactor = fieldOfViewFactor(raw.azimuthDegrees());
        if (fovFactor <= 0.0) {
            discard("视野之外: 方位 " + raw.azimuthDegrees() + "°, 视野半角 " + FIELD_OF_VIEW_DEGREES + "°");
            return null;
        }

        double weighted = perceivedIntensity * fovFactor;
        String kind = classify(raw, fovFactor);
        String detail = raw.describe() + String.format(" 视野权重 %.2f", fovFactor)
                + (raw.illuminanceLux() >= DARK_LUX ? "" : " [暗环境]");
        return SensoryStimulus.external(modality(), kind, Math.min(1.0, weighted),
                VisualChannel.class.getSimpleName(), raw.sourceObjectId(), perceivedAt(raw), detail);
    }

    /**
     * 视野权重 {@code [0, 1]} —— 见类注释。
     *
     * <p>{@code 0} 表示"视野之外"。调用方据此丢弃这条读数, 而不是把它当成一个
     * 强度为零的刺激 —— 两者的区别见 {@link SensoryAcuity#perceivedIntensity(double)} 的说明。
     */
    public double fieldOfViewFactor(double azimuthDegrees) {
        double abs = Math.abs(azimuthDegrees);
        if (abs >= FIELD_OF_VIEW_DEGREES) {
            return 0.0;
        }
        if (abs <= SHARP_FIELD_DEGREES) {
            return 1.0;
        }
        double t = (abs - SHARP_FIELD_DEGREES) / (FIELD_OF_VIEW_DEGREES - SHARP_FIELD_DEGREES);
        return 1.0 - (1.0 - PERIPHERAL_FLOOR) * t;
    }

    /**
     * 按环境照度调整灵敏度 —— <b>本通道唯一的有状态行为</b>。
     *
     * <p>用指数趋近而不是"到点切换": 光线是连续变化的, 而一个"低于 1 lux 就瞬间获得
     * 夜视能力"的模型会让"她进地下室"变成一个瞬时的能力跳变, 那是假的。
     *
     * <p>时间常数不对称(暗适应 300 秒, 明适应 20 秒), 因为现实不对称。
     * 这个不对称有真实的行为后果: 从亮处走进暗处, 她有五分钟是"瞎"的;
     * 反过来只要二十秒。
     */
    private void adaptToAmbient(double illuminanceLux, Instant at) {
        double deltaSeconds = 0.0;
        if (lastAdaptedAt != null) {
            deltaSeconds = Math.max(0.0, Duration.between(lastAdaptedAt, at).toMillis() / 1000.0);
        }
        lastAdaptedAt = at;

        double target = illuminanceLux < DARK_LUX
                ? DARK_ADAPTED_THRESHOLD_LUX
                : SensoryAcuity.humanEye().detectionThreshold();
        double current = acuity().detectionThreshold();
        if (Math.abs(target - current) < 1e-9) {
            return;
        }
        double tau = target < current ? DARK_ADAPTATION_TAU_SECONDS : LIGHT_ADAPTATION_TAU_SECONDS;
        double alpha = 1.0 - Math.exp(-deltaSeconds / tau);
        adapt(acuity().withDetectionThreshold(current + (target - current) * alpha));
    }

    /**
     * 这条光属于哪一类。
     *
     * <p>判断依据是<b>照度相对上一次读数的变化</b>与视野权重, 不是绝对照度 ——
     * 因为"变亮了"和"很亮"是两件事: 正午在户外, 照度翻倍她毫无感觉; 半夜床头灯亮起,
     * 照度只涨了几十 lux 却足够把她弄醒。
     */
    public String classify(RawSignal.Light raw, double fovFactor) {
        if (fovFactor < 1.0) {
            return Kinds.PERIPHERAL_MOTION;
        }
        Double previousLux = lastRaw()
                .filter(r -> r instanceof RawSignal.Light)
                .map(RawSignal::rawMagnitude)
                .orElse(null);
        if (previousLux == null) {
            return raw.illuminanceLux() >= DARK_LUX ? Kinds.POINT_LIGHT : Kinds.STEADY;
        }
        double ratio = raw.illuminanceLux() / Math.max(previousLux, 1e-6);
        if (ratio >= 1.5) {
            return Kinds.ILLUMINATION_RISE;
        }
        if (ratio <= 0.67) {
            return Kinds.ILLUMINATION_DROP;
        }
        return isPointSource(raw) ? Kinds.POINT_LIGHT : Kinds.STEADY;
    }

    /**
     * 这是不是一个"点光源"(屏幕、指示灯)而不是环境光。
     *
     * <p>今天用色温做粗略判断 —— 屏幕偏冷(6500 K 以上)。这个判据<b>在接上真实摄像头后
     * 必须换掉</b>(换成"画面里有没有一小块高亮区域"), 而换掉它只需要改这一个方法:
     * 通道的入口、缓冲、衰减、记账全都不用动。这正是把"解释"关在
     * {@link SensoryChannel#interpret(RawSignal, double)} 里的收益。
     */
    public boolean isPointSource(RawSignal.Light raw) {
        return raw.colorTemperatureK() >= 5500.0 && raw.illuminanceLux() >= DARK_LUX;
    }

    /** 诊断: 当前是哪一种适应状态。 */
    public Map<String, Object> adaptationState() {
        return Map.of(
                "detectionThresholdLux", acuity().detectionThreshold(),
                "darkAdapted", acuity().detectionThreshold() < SensoryAcuity.humanEye().detectionThreshold(),
                "lastAdaptedAt", lastAdaptedAt == null ? "" : lastAdaptedAt.toString());
    }
}
