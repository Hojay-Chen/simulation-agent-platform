package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

/**
 * V2.2 §3.2.2 —— <b>听觉通道</b>。
 *
 * <table border="1">
 *   <tr><th>物理量</th><td>声压级(dB)、主频(Hz)、时长(ms)</td></tr>
 *   <tr><th>量程</th><td>0–120 dB, 20–20000 Hz</td></tr>
 *   <tr><th>现实对接点(未来机器人)</th><td>麦克风阵列 + 声源定位</td></tr>
 *   <tr><th>典型刺激</th><td>手机铃声、说话声、雨声、脚步声</td></tr>
 * </table>
 *
 * <h2>为什么它只做"分类"而不做"识别"</h2>
 * 这条通道能说的最多是"一段 800–2000 Hz 的窄带声音, 响度 0.6, 持续 800 毫秒",
 * 它<b>不会</b>说"这是微信的通知声"。那个判断属于 Perception(把刺激解释成
 * "手机发出了微信式通知声"), 而 Perception 需要的不只是这条通道的输出, 还有
 * 她的处境(昨天刚把微信提示音换成这个)。
 *
 * <p>把"这是什么"压在通道里会有一个具体的坏结果: 通道必须认识世界上的每一种声源,
 * 于是每加一个 App 的通知音都要改感官层 —— 而感官层是最不该知道 App 这个概念的地方。
 *
 * <h2>频带加权: "她为什么没听见"的第一个答案</h2>
 * 人耳对不同频率的敏感度差了几个数量级: 3 kHz 附近最敏感, 50 Hz 和 18 kHz 都很迟钝。
 * 所以本通道在阈值判定之后<b>再乘一个频率权重</b> —— 一条 60 dB 的 40 Hz 低频轰鸣,
 * 与一条 60 dB 的 2 kHz 铃声, 对她的"响度"完全不同。
 *
 * <p>这个权重是<b>通道</b>的属性而不是灵敏度的属性: 灵敏度管的是"整体多灵敏"
 * (可以随疲劳、年龄整体升降), 权重管的是"同一灵敏度下各频率的相对敏感度"(形状固定)。
 * 混在一起会得到一个没法单独调的形状 —— 而"这个 agent 听力不好"和"这个 agent 听不见低频"
 * 是两个不同的设定。
 *
 * <h2>它刻意<b>不</b>判断"这声音重要吗"</h2>
 * 与 {@code SensoryEvent} 没有 {@code salience()} 是同一条理由: 半夜的雨声和
 * 考试前夜的雨声, 对她的重要性差十倍, 而那取决于她正在做什么 —— 那属于 Mind。
 */
public final class AuditoryChannel extends SensoryChannel<RawSignal.Sound> {

    /** 人耳的痛阈。超过它的声音不只是"响", 是<b>疼</b>。 */
    public static final double PAIN_THRESHOLD_DB = 120.0;

    /** 最关键频段的下沿 —— 人耳在此附近最敏感(等响曲线的谷底)。 */
    public static final double MOST_SENSITIVE_LOW_HZ = 2000.0;
    /** 最关键频段的上沿。 */
    public static final double MOST_SENSITIVE_HIGH_HZ = 5000.0;

    /**
     * 听觉刺激的类别标签。
     *
     * <p>它们是<b>字符串常量</b>而不是枚举 —— 理由与 P4 一致: "这是哪一种声音"这个词表
     * 会一直长(将来接上真实机器人还会多出"电流声""机械共振"), 而加一个词不该需要
     * 重新编译平台。这里给的是<b>平台自带的默认词汇</b>, 不是封闭集合。
     */
    public static final class Kinds {
        private Kinds() {
        }

        /** 窄带、有明确音高、常见于设备提示音。 */
        public static final String ALERT_TONE = "alert-tone";
        /** 落在话音频带里的一段声音 —— 是不是人在说话由 Perception 判断。 */
        public static final String VOICE_LIKE = "voice-like";
        /** 短促、宽带、能量高。关门、掉落、撞击。 */
        public static final String IMPACT = "impact";
        /** 低频、持续。空调、引擎、远处交通。 */
        public static final String LOW_RUMBLE = "low-rumble";
        /** 没有主频的宽带声。雨声、风扇、人群。 */
        public static final String BROADBAND = "broadband";
    }

    public AuditoryChannel(String humanId) {
        this(humanId, SensoryAcuity.humanEar(), DEFAULT_CAPACITY);
    }

    public AuditoryChannel(String humanId, SensoryAcuity acuity, int capacity) {
        super(new ChannelId(humanId, CoreEventCatalog.Modalities.AUDITORY), acuity, capacity);
    }

    @Override
    protected SensoryStimulus interpret(RawSignal.Sound raw, double perceivedIntensity) {
        double weighted = perceivedIntensity * frequencyWeighting(raw.frequencyHz());
        if (weighted <= 0.0) {
            discard("频率权重把这条声音衰减到了零: " + raw.describe());
            return null;
        }
        String kind = classify(raw);
        String detail = raw.describe()
                + (raw.soundPressureDb() >= PAIN_THRESHOLD_DB ? " [痛阈以上]" : "")
                + (raw.durationMillis() > 0 ? String.format(" 时长 %.0fms", raw.durationMillis()) : "");
        return SensoryStimulus.external(modality(), kind, Math.min(1.0, weighted),
                AuditoryChannel.class.getSimpleName(), raw.sourceObjectId(), perceivedAt(raw), detail);
    }

    /**
     * 这条声音属于哪一类。
     *
     * <p>判断只用声学特征, <b>不用来源</b>。同样是 800 Hz 的窄带声, 来自手机和来自微波炉
     * 在这条通道里是同一个类别 —— 因为耳朵听到的确实是同一个东西。区分它们需要知道
     * 世界里有手机和微波炉, 而那正是这条通道不该知道的事。
     */
    public String classify(RawSignal.Sound raw) {
        double hz = raw.frequencyHz();
        if (hz <= 0.0) {
            return Kinds.BROADBAND;
        }
        if (hz >= 200.0 && hz <= 6000.0 && raw.soundPressureDb() >= 60.0
                && raw.durationMillis() > 0 && raw.durationMillis() <= 3000.0) {
            return Kinds.ALERT_TONE;
        }
        if (raw.inSpeechBand()) {
            return Kinds.VOICE_LIKE;
        }
        if (hz < 200.0) {
            return Kinds.LOW_RUMBLE;
        }
        if (hz > MOST_SENSITIVE_HIGH_HZ) {
            return Kinds.BROADBAND;
        }
        return raw.durationMillis() > 0 && raw.durationMillis() < 200
                ? Kinds.IMPACT : Kinds.BROADBAND;
    }

    /**
     * 频带权重 {@code [0, 1]}。
     *
     * <pre>{@code
     *   2000–5000 Hz  → 1.00   (最敏感)
     *   200–2000 Hz   → 0.75   线性过渡
     *   20–200 Hz     → 0.40   (低频要响得多才听得见)
     *   > 5000 Hz     → 0.60   递减, 16000 Hz 以上 0.15
     * }</pre>
     *
     * <p>数值取的是等响曲线的粗略形状(不是精确的 A 计权): 仿真里需要的不是声学精度,
     * 而是"低频的闷响对她没有高频铃声那么突出"这个<b>定性</b>事实 —— 因为它直接影响
     * 哪条刺激先被注意到。
     */
    public double frequencyWeighting(double frequencyHz) {
        if (frequencyHz <= 0.0) {
            return 0.85;                       // 宽带声按略低于最敏感段处理
        }
        if (frequencyHz >= MOST_SENSITIVE_LOW_HZ && frequencyHz <= MOST_SENSITIVE_HIGH_HZ) {
            return 1.0;
        }
        if (frequencyHz < 20.0) {
            return 0.05;                       // 次声: 基本听不见, 但也不是零(身体会感到压迫)
        }
        if (frequencyHz < MOST_SENSITIVE_LOW_HZ) {
            double t = (frequencyHz - 20.0) / (MOST_SENSITIVE_LOW_HZ - 20.0);
            return 0.40 + 0.60 * t;
        }
        if (frequencyHz <= 16000.0) {
            double t = (frequencyHz - MOST_SENSITIVE_HIGH_HZ) / (16000.0 - MOST_SENSITIVE_HIGH_HZ);
            return 1.0 - 0.40 * t;
        }
        return 0.15;                           // 超声: 成年人几乎听不见
    }

    /**
     * 这条声音有没有到"疼"的程度。
     *
     * <p>它<b>不</b>在本通道里产生痛觉刺激 —— 痛觉走 {@code TactileChannel}。
     * 提供一个判定方法是给 Perception 与诊断面板用的: "她被这声巨响吓了一跳"这件事
     * 需要一个可查证的依据。
     */
    public boolean isPainfullyLoud(RawSignal.Sound raw) {
        return raw.soundPressureDb() >= PAIN_THRESHOLD_DB;
    }
}
