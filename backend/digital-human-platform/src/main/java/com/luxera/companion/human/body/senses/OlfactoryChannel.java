package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Duration;
import java.time.Instant;

/**
 * V2.2 §3.2.2 —— <b>嗅觉通道</b>。
 *
 * <table border="1">
 *   <tr><th>物理量</th><td>浓度(归一化 {@code [0, 1]})、种类标签</td></tr>
 *   <tr><th>现实对接点(未来机器人)</th><td>电子鼻(气体传感器阵列)</td></tr>
 *   <tr><th>典型刺激</th><td>饭香、烟味、臭味、雨后土腥味</td></tr>
 * </table>
 *
 * <h2>嗅觉最重要的性质是"会习惯", 而这件事必须被建模</h2>
 * 在饭香里待十分钟就闻不到了; 走进一个味道很重的房间, 两分钟后觉得"也没什么味"。
 * 这不是感觉器官坏了, 是<b>阈限被抬高了</b> —— 而它对行为的影响是实质性的:
 * <pre>
 *   12:00 她闻到饭香 → 阈值正常 → 注意到         ("该吃饭了")
 *   12:10 她还在厨房 → 阈值被抬高 → 闻不到了      ("她已经不觉得香了")
 *   12:20 换了一种味道(油烟) → 阈值重置 → 又注意到了 ("她立刻察觉")
 * </pre>
 * 最后一行是关键: <b>习惯是"对同一个味道"的习惯</b>。一个把所有气味一起衰减的模型
 * 会得出"她在厨房里待久了就闻不到煤气泄漏"这种荒谬且危险的结论。
 *
 * <h2>{@code kind} 是主观类别, 这是它与"浓度"同等重要的一半</h2>
 * 同一个分子, 在饿的人那里是"饭香", 在刚吃饱的人那里是"油腻味"。但<b>那属于 Mind</b> ——
 * 通道给的是中性的类别标签({@code food} / {@code smoke} / {@code waste} / {@code petro}),
 * 而"这味道好不好闻"是评价, 评价需要知道她的处境。
 *
 * <p>所以本通道刻意不做"好闻/难闻"的判断 —— 做了的话, "她为什么在闻到煤气味时无动于衷"
 * 就会有一个查不出来的根因: 因为通道把它标成了"难闻"而不是"危险"。
 */
public final class OlfactoryChannel extends SensoryChannel<RawSignal.Odor> {

    /** 习惯化的时间常数(秒)。约 3 分钟 —— 与真人的嗅觉适应速度同量级。 */
    public static final double HABITUATION_TAU_SECONDS = 180.0;

    /**
     * 习惯化能把阈限抬高多少倍。
     *
     * <p>取 20 是一个刻意的<b>有上限</b>的值: 完全习惯(阈限无穷大)会让"她被熏晕"
     * 这种事永远不能被发现, 而那是一条安全相关的通路。
     */
    public static final double MAX_HABITUATION_FACTOR = 20.0;

    /**
     * 换一种气味时, 习惯被重置的比例。
     *
     * <p>取 0.8(几乎全部重置)而不是 1.0: 从饭香换到油烟, 她对"气味"这件事本身
     * 仍处于略微迟钝的状态(鼻腔里的受体没有完全恢复)。这个 0.2 的残留没有任何
     * 精确的生理依据, 它的作用只是让"换味道"不是一个完美的开关。
     */
    public static final double HABITUATION_CARRY_OVER = 0.2;

    /** 嗅觉刺激的类别标签。 */
    public static final class Kinds {
        private Kinds() {
        }

        /** 没闻到任何气味。 */
        public static final String CLEAR = "clear";
        /** 浓度明显高于上一次。 */
        public static final String EMERGING = "emerging";
        /** 浓度明显低于上一次 —— 味道在散。 */
        public static final String FADING = "fading";
        /** 与上一次同一个味道且浓度相当。 */
        public static final String PERSISTENT = "persistent";
    }

    /** 当前正在习惯的那个味道。 */
    private String habituatedKind;
    private Instant habitStartedAt;

    /** 当前的习惯化倍数, {@code [1, MAX_HABITUATION_FACTOR]}。 */
    private double habituationFactor = 1.0;

    public OlfactoryChannel(String humanId) {
        this(humanId, SensoryAcuity.humanNose(), DEFAULT_CAPACITY);
    }

    public OlfactoryChannel(String humanId, SensoryAcuity acuity, int capacity) {
        super(new ChannelId(humanId, CoreEventCatalog.Modalities.OLFACTORY), acuity, capacity);
    }

    @Override
    protected SensoryStimulus interpret(RawSignal.Odor raw, double perceivedIntensity) {
        updateHabituation(raw.kind(), raw.observedAt());

        // 习惯化在阈值判定之后再次衰减 —— 与听觉的频带权重同一个位置、同一个理由:
        // 灵敏度管"整体多灵敏", 习惯管"对这一个味道多灵敏"。
        double weighted = perceivedIntensity / habituationFactor;
        if (weighted <= 0.0) {
            discard("已习惯这个味道(阈限抬高 " + String.format("%.1f", habituationFactor) + " 倍): "
                    + raw.describe());
            return null;
        }

        String kind = classify(raw);
        String detail = raw.describe() + String.format(" 习惯化 ×%.1f", habituationFactor);
        return SensoryStimulus.external(modality(), kind, Math.min(1.0, weighted),
                OlfactoryChannel.class.getSimpleName(), raw.sourceObjectId(), perceivedAt(raw), detail);
    }

    /**
     * 更新习惯化倍数。
     *
     * <p>同一个味道持续存在 → 阈限按指数曲线抬高到上限; 味道换了 → 大幅重置。
     * 这个状态机只有两个分支, 而它换来的是一条真人身上天天发生的现象。
     */
    private void updateHabituation(String kind, Instant at) {
        if (!kind.equals(habituatedKind)) {
            habituatedKind = kind;
            habitStartedAt = at;
            habituationFactor = 1.0 + (habituationFactor - 1.0) * HABITUATION_CARRY_OVER;
            return;
        }
        double seconds = habitStartedAt == null
                ? 0.0
                : Math.max(0.0, Duration.between(habitStartedAt, at).toMillis() / 1000.0);
        double target = MAX_HABITUATION_FACTOR;
        double alpha = 1.0 - Math.exp(-seconds / HABITUATION_TAU_SECONDS);
        habituationFactor = 1.0 + (target - 1.0) * alpha;
    }

    /**
     * 这条气味相对上一次是"冒出来""在散"还是"一直在"。
     *
     * <p>用种类与浓度的<b>变化</b>做判断, 与视觉通道同构: "变浓了"和"很浓"是两件事,
     * 而前者才是会惊动她的那件事 —— 一个一直存在的味道不该每 tick 都产生一条刺激。
     */
    public String classify(RawSignal.Odor raw) {
        return lastRaw()
                .filter(r -> r instanceof RawSignal.Odor)
                .map(r -> (RawSignal.Odor) r)
                .map(previous -> {
                    if (!previous.kind().equals(raw.kind())) {
                        return Kinds.EMERGING;
                    }
                    double ratio = raw.concentration() / Math.max(previous.concentration(), 1e-6);
                    if (ratio >= 1.3) {
                        return Kinds.EMERGING;
                    }
                    if (ratio <= 0.7) {
                        return Kinds.FADING;
                    }
                    return Kinds.PERSISTENT;
                })
                .orElse(Kinds.EMERGING);
    }

    /** 当前的习惯化倍数 —— 诊断面板用它回答"她为什么没闻到"。 */
    public double habituationFactor() {
        return habituationFactor;
    }

    /**
     * 强制重置习惯 —— <b>离开这个环境</b>时由 Body 调用。
     *
     * <p>为什么要显式重置而不是等它自然衰减: 因为"她走出厨房"这件事是一个<b>事件</b>
     * (地点变了), 而不是"时间过去了"。靠时间衰减会让"她在厨房门口站了一秒又回去"
     * 变得和"她从没离开过"一样 —— 而那与真人的体验不符。
     */
    public void resetHabituation() {
        habituatedKind = null;
        habitStartedAt = null;
        habituationFactor = 1.0;
    }
}
