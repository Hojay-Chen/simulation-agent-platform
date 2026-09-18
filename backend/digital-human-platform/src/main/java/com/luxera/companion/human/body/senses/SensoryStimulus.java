package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.2.2 —— <b>一个已经被感觉到的刺激</b>: 感官通道的输出, Body 的传入神经。
 *
 * <h2>它不是 {@code SensoryEvent}, 两者差在哪</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code SensoryStimulus}(本类)</th>
 *       <th>{@code boundary.event.SensoryEvent}</th></tr>
 *   <tr>
 *     <td>属于谁</td><td><b>她的</b> —— 是她身体里的一次神经冲动</td>
 *     <td><b>边界上的</b> —— 是一条被记录、可审计、可重放的消息</td>
 *   </tr>
 *   <tr>
 *     <td>强度怎么来</td>
 *     <td>已经过灵敏度衰减({@link SensoryAcuity}), 是<b>感知强度</b></td>
 *     <td>带上 {@code urgency}, 由世界侧给出, 用于队列定序</td>
 *   </tr>
 *   <tr>
 *     <td>归宿</td>
 *     <td>{@code Body.drainSensoryStimuli()} → Perception</td>
 *     <td>{@code RealtimeEventQueue} → Mind</td>
 *   </tr>
 *   <tr>
 *     <td>能不能重放</td>
 *     <td>不能, 也不需要 —— 感觉是不可重放的, 事实才是</td>
 *     <td>能, 而且必须能(§9 验收标准: 事件可审计)</td>
 *   </tr>
 * </table>
 *
 * <p>为什么不干脆合成一个类型省事: 因为<b>同一次刺激在两条路上的命运不同</b>。
 * 队列会丢(容量满了丢 urgency 最低的), 而"她身体里发生过一次感觉"这件事不该
 * 因为队列满就消失 —— 它是行为分析里"她当时到底感觉到了什么"的唯一答案。
 * 反过来, 让通道去产出 {@code SensoryEvent} 会让感官层必须知道
 * {@code EventTypeId}、{@code urgency} 和队列容量 —— 那是把边界的复杂度灌进神经末梢。
 *
 * <h2>{@code kind} 为什么是字符串</h2>
 * 与 {@link RawSignal.Odor#kind()} 同一个理由, 但更硬: 这里要能表达<b>还没有名字</b>
 * 的感觉。一个接上真实机器人的仿真, 一定会遇到"某个传感器读数变了, 但我还不知道
 * 那是什么"的时刻 —— 那时 {@code kind} 应当能写 {@code "unknown-pressure-anomaly"},
 * 而不是被迫先改一个枚举、重新编译整个平台。
 *
 * <p>它与 {@link #modality()} 的关系: modality 是<b>哪条通道</b>(物理封闭, 五种),
 * kind 是<b>这条通道里的哪一种感觉</b>(开放, 随时会长)。
 *
 * <h2>两条来源, 一个类型</h2>
 * <pre>{@code
 *   外部:  SensoryChannel.receive(RawSignal)  → 灵敏度衰减 → SensoryStimulus.external(...)
 *   内部:  ThresholdDetector                 → SensoryChannel.feel(...) → SensoryStimulus.interoceptive(...)
 * }</pre>
 * {@link #interoceptive()} 是<b>唯一</b>能区分两者的地方, 而它存在的理由不是给 Mind
 * 分门别类用的 —— 见下面的说明。
 */
public record SensoryStimulus(String modality,
                              String kind,
                              double intensity,
                              double delta,
                              String origin,
                              String sourceChannel,
                              String sourceObjectId,
                              Instant observedAt,
                              String description) {

    /** 刺激的两种来源 —— 见 {@link #interoceptive()}。 */
    public static final class Origins {
        private Origins() {
        }

        /** 外部物理世界来的: 手机响了、灯亮了、有人碰了她。 */
        public static final String EXTERNAL = "external";
        /** 身体内部来的: 保暖值跌破舒适带而"她觉得冷"。 */
        public static final String INTEROCEPTIVE = "interoceptive";

        /** 这个来源标签是不是认识。 */
        public static boolean isKnown(String origin) {
            return EXTERNAL.equals(origin) || INTEROCEPTIVE.equals(origin);
        }
    }

    public SensoryStimulus {
        Objects.requireNonNull(modality, "刺激必须属于一条感官通道 —— 否则它不知道该被谁处理");
        if (!CoreEventCatalog.Modalities.isStandard(modality)) {
            throw new IllegalArgumentException(
                    "未知的感官通道 \"" + modality + "\" —— 一条投进没有接收者的通道的刺激, "
                            + "表现是'她没听见', 且没有任何报错。标准通道见 CoreEventCatalog.Modalities");
        }
        Objects.requireNonNull(kind, "刺激必须有类别标签 —— 没有它无法解释'她感觉到了什么'");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("刺激类别标签不能是空白串");
        }
        Objects.requireNonNull(origin, "刺激必须有来源 —— 见 Origins");
        if (!Origins.isKnown(origin)) {
            throw new IllegalArgumentException(
                    "未知的刺激来源 \"" + origin + "\" —— 只允许 " + Origins.EXTERNAL
                            + " 与 " + Origins.INTEROCEPTIVE);
        }
        Objects.requireNonNull(observedAt, "刺激必须带时刻 —— 行为分析的时间线靠它对齐");
        if (intensity < 0.0 || intensity > 1.0) {
            throw new IllegalArgumentException(
                    "感知强度必须归一化到 [0, 1], 收到 " + intensity + " —— "
                            + "未归一化的强度会让不同通道之间的刺激无法比较, 而它们是同一个队列里的邻居");
        }
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 外部刺激 —— 由 {@link SensoryChannel#receive(RawSignal)} 产出。 */
    public static SensoryStimulus external(String modality, String kind, double intensity,
                                           String sourceChannel, String sourceObjectId,
                                           Instant observedAt, String description) {
        return new SensoryStimulus(modality, kind, intensity, 0.0, Origins.EXTERNAL,
                sourceChannel, sourceObjectId, observedAt, description);
    }

    /**
     * 内部刺激 —— 由阈值检测(生理越界)产出。
     *
     * @param delta 这一步偏离了多少 —— 用户明确要求"变化产生的 diff ... 也能形成
     *              一个实时 event", 所以 diff 与强度一样是这个刺激的一等公民
     */
    public static SensoryStimulus interoceptive(String modality, String kind, double intensity,
                                                double delta, String sourceChannel,
                                                Instant observedAt, String description) {
        return new SensoryStimulus(modality, kind, intensity, delta, Origins.INTEROCEPTIVE,
                sourceChannel, null, observedAt, description);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    /**
     * 这个刺激是不是身体自己产生的。
     *
     * <h2>它<b>不</b>是给 Mind 用来分门别类的</h2>
     * 因为真人也分不清 —— "她觉得冷"这件事, 原因是外面降温还是自己在发烧,
     * 她<b>感觉上</b>是分不出来的。所以 Mind 侧的决策不该看这个字段。
     *
     * <p>它存在是为了两件<b>旁观者</b>的事:
     * <ul>
     *   <li>行为分析: "她今天觉得冷的次数里, 有多少次是她真的穿少了"这个问题,
     *       只有能区分两条来源才答得出来;</li>
     *   <li>调试: 一个"她莫名觉得冷"的 bug, 第一件事就是看这条刺激是不是
     *       {@code INTEROCEPTIVE} —— 如果是, 那么去查账本而不是去查设备。</li>
     * </ul>
     */
    public boolean interoceptive() {
        return Origins.INTEROCEPTIVE.equals(origin);
    }

    /** 刺激在变强还是在变弱 —— {@code delta} 的符号。0 表示"这一步没有变化"。 */
    public boolean worsening() {
        return delta > 0.0;
    }

    public String describe() {
        return String.format("[%s/%s] 强度%.2f Δ%.3f %s%s", modality, kind, intensity, delta,
                interoceptive() ? "(内部) " : "", description == null ? "" : description);
    }
}
