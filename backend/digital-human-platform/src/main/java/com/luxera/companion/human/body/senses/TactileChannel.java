package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;

/**
 * V2.2 §3.2.2 —— <b>触觉通道</b>: 五条通道里唯一有<b>两个来源</b>的一条。
 *
 * <table border="1">
 *   <tr><th>物理量</th><td>温度(℃)、压力(N)、材质、痛觉 {@code [0, 1]}</td></tr>
 *   <tr><th>现实对接点(未来机器人)</th><td>电子皮肤(温度/压力/振动传感阵列)</td></tr>
 *   <tr><th>典型刺激</th><td>冷、热、震动、被触碰、疼</td></tr>
 * </table>
 *
 * <h2>两个来源, 而它们的产物必须长得一模一样</h2>
 * <pre>{@code
 *   // 来源一: 外部物理接触 —— 手机震动、碰到热水杯、被人拍了一下
 *   tactileChannel.receive(RawSignal.Contact.touch(4.0, 1.2, "phone", now));
 *
 *   // 来源二: 内部生理越界 —— HomeostasisModel / ThresholdDetector 生成的
 *   tactileChannel.feel(SensoryStimulus.interoceptive(
 *           Modalities.TACTILE, Kinds.COLD, intensity /*越界程度* /, delta /*这一步掉了多少* /,
 *           Channels.WARMTH, now, "保暖值 0.28, 低于舒适下沿 0.45"));
 * }</pre>
 *
 * <p>Mind 侧<b>看不出区别</b>, 而这正是要的效果: 用户要求"environment 导致保暖值变化,
 * 这里变化产生的 diff 和低于适中值的程度也能形成一个实时 event"。真人分不清
 * "觉得冷"是因为外面降温还是因为自己在发烧 —— 通道这一层若把两者标成不同的东西,
 * 下游就会有人开始写 {@code if (source == INTERNAL)} 的分支, 而那个分支在真人身上
 * 没有对应物。
 *
 * <p><b>唯一</b>能看出区别的地方是 {@link SensoryStimulus#interoceptive()}, 而它存在的
 * 理由与 Mind 无关(那是给行为分析与调试用的), 见该方法的说明。
 *
 * <h2>为什么内部越界要经过触觉通道, 而不是直接投进队列</h2>
 * 因为它<b>确实是一种体感</b>。"饿"是胃里的感觉, "冷"是皮肤与核心温度的感觉 ——
 * 它们不是抽象的数字事件, 而是她身体的一部分。把它挂在触觉通道上有三个具体好处:
 * <ol>
 *   <li>五感是"Body 的唯一输出口"这条设计得以保持 —— Body 不需要一条旁路;</li>
 *   <li>饥饿、寒冷、疼痛会<b>互相竞争注意力</b>(它们在同一条通道、同一个队列里按
 *       urgency 排序), 而"她又饿又冷时先处理哪个"是一个真实的行为问题;</li>
 *   <li>将来接真实机器人时, 电子皮肤上的温度读数与"内部觉得冷"会自然地汇进同一条
 *       通路 —— 因为它们在解剖上确实是汇进同一条的。</li>
 * </ol>
 *
 * <h2>温度判断靠<b>温差</b>, 不靠绝对值</h2>
 * 手放进 32℃ 的水里和 34℃ 的水里, 你分不出来 —— 但手从 32℃ 的水换到 34℃ 的水里,
 * 你立刻知道"变热了"。所以本通道用上一次读数做基准算温差, 而不是把"25℃"当成一个
 * 绝对的"冷"。这也是为什么 {@link RawSignal.Contact#rawMagnitude()} 刻意<b>不</b>用温度
 * 作为主强度 —— 见该方法的说明。
 */
public final class TactileChannel extends SensoryChannel<RawSignal.Contact> {

    /** 能察觉到的温差(℃) —— 与 {@code SensoryAcuity.humanSkin()} 的可觉差同量级但更宽松。 */
    public static final double NOTICEABLE_TEMPERATURE_DELTA = 0.5;

    /** 低于这个温度算"冷"。 */
    public static final double COLD_CELSIUS = 15.0;
    /** 高于这个温度算"烫"。 */
    public static final double HOT_CELSIUS = 42.0;

    /**
     * 触觉刺激的类别标签。
     *
     * <h3>前四个是外部接触, 后三个是内部越界</h3>
     * 它们共用一个词表, 因为它们对下游是同一类东西。加一个新的类别(将来真实机器人
     * 会带来"电流感""机械挤压")只是加一个字符串常量 —— 不需要改任何枚举、任何 switch。
     */
    public static final class Kinds {
        private Kinds() {
        }

        // ── 外部接触 ──
        /** 被碰到了(有压力, 不疼)。 */
        public static final String CONTACT = "contact";
        /** 震动 —— 手机震动的典型。 */
        public static final String VIBRATION = "vibration";
        /** 温度变化(摸到凉的/热的表面)。 */
        public static final String TEMPERATURE_SHIFT = "temperature-shift";
        /** 疼 —— 外部原因造成的。 */
        public static final String PAIN = "pain";

        // ── 内部生理越界(由 ThresholdDetector 产生) ──
        /**
         * 觉得冷。
         *
         * <p>这是用户那条链的终点: "保暖值远低于合适值产生了触觉实时 event 即觉得冷,
         * 然后 agent 需要立马处理这个实时 event"。
         */
        public static final String COLD = "cold";
        /** 觉得热。 */
        public static final String HEAT = "heat";
        /** 饿 —— 胃里的感觉。 */
        public static final String HUNGER = "hunger";
        /** 渴。 */
        public static final String THIRST = "thirst";
        /** 累。 */
        public static final String FATIGUE = "fatigue";
        /** 困。 */
        public static final String SLEEPY = "sleepy";
        /** 精力见底 —— 与"饿"不同, 它是"突然使不上劲"。 */
        public static final String ENERGY_DEPLETED = "energy-depleted";
        /** 持续恶化的趋势(还没越界, 但掉得太快) —— 见 {@code ThresholdDetector} 的第二条路径。 */
        public static final String WORSENING = "worsening";
    }

    public TactileChannel(String humanId) {
        this(humanId, SensoryAcuity.humanSkin(), DEFAULT_CAPACITY);
    }

    public TactileChannel(String humanId, SensoryAcuity acuity, int capacity) {
        super(new ChannelId(humanId, CoreEventCatalog.Modalities.TACTILE), acuity, capacity);
    }

    @Override
    protected SensoryStimulus interpret(RawSignal.Contact raw, double perceivedIntensity) {
        String kind = classify(raw);
        double intensity = perceivedIntensity;
        if (Kinds.TEMPERATURE_SHIFT.equals(kind)) {
            // 温差越小越弱 —— 3℃ 的温差与 0.6℃ 的温差不是同一件事
            intensity *= temperatureDeltaFactor(raw);
        }
        String detail = raw.describe() + String.format(" [%s]", kind);
        return SensoryStimulus.external(modality(), kind, Math.min(1.0, intensity),
                TactileChannel.class.getSimpleName(), raw.sourceObjectId(), perceivedAt(raw), detail);
    }

    /**
     * 这次接触属于哪一类。
     *
     * <p>优先级: <b>疼 &gt; 振动 &gt; 温差 &gt; 冷热极端 &gt; 普通接触</b>。
     * 顺序不是随手排的 —— 它是"同一次物理接触同时满足多个条件时, 她首先感觉到什么"的答案:
     * 被烫到的时候, 她首先感觉到的是疼, 不是"温度变化了 12 度"。
     */
    public String classify(RawSignal.Contact raw) {
        if (raw.isPainful()) {
            return Kinds.PAIN;
        }
        if (isVibration(raw)) {
            return Kinds.VIBRATION;
        }
        Double previousCelsius = lastRaw()
                .filter(r -> r instanceof RawSignal.Contact)
                .map(r -> ((RawSignal.Contact) r).celsius())
                .orElse(null);
        if (previousCelsius != null
                && Math.abs(raw.celsius() - previousCelsius) >= NOTICEABLE_TEMPERATURE_DELTA) {
            return Kinds.TEMPERATURE_SHIFT;
        }
        if (raw.celsius() <= COLD_CELSIUS || raw.celsius() >= HOT_CELSIUS) {
            return Kinds.TEMPERATURE_SHIFT;
        }
        return Kinds.CONTACT;
    }

    /**
     * 这是不是一次振动。
     *
     * <p>今天用压力大小做粗略判断(手机的振动马达约 1–3 N, 拍一下肩膀约 10 N 以上),
     * 这在接上真实电子皮肤后<b>必须</b>换成频谱判断 —— 而那个改动只落在本方法里。
     * 这正是把"解释"关在一个方法内的收益: 换传感器不用改通道的骨架。
     */
    public boolean isVibration(RawSignal.Contact raw) {
        return raw.newtons() > 0.0 && raw.newtons() <= 3.0 && "phone".equals(raw.material());
    }

    /** 温差越大越显著, 但封顶 —— 5℃ 以上的温差不比 20℃ 的温差更"更明显"。 */
    private double temperatureDeltaFactor(RawSignal.Contact raw) {
        Double previousCelsius = lastRaw()
                .filter(r -> r instanceof RawSignal.Contact)
                .map(r -> ((RawSignal.Contact) r).celsius())
                .orElse(null);
        if (previousCelsius == null) {
            return 1.0;
        }
        double delta = Math.abs(raw.celsius() - previousCelsius);
        return Math.min(1.0, 0.4 + delta / 5.0 * 0.6);
    }

    // ─────────────────────────── 内部越界的便捷入口 ───────────────────────────

    /**
     * 一个内部生理越界引起的体感 —— 由 {@code ThresholdDetector} 调用。
     *
     * <p>为什么要有这个便捷方法, 而不是让检测器自己拼 {@link SensoryStimulus}:
     * 因为其中一个参数({@code sourceChannel})必须是本通道认识的通道名, 而另一个
     * ({@code kind})必须取自 {@link Kinds} —— 把这两个约束收在一个方法签名里,
     * 检测器就不可能把 {@code Channels.HUNGER} 配上一个 {@code Kinds.COLD}。
     *
     * @param kind          见 {@link Kinds} 里"内部生理越界"那一组
     * @param intensity     越界程度 {@code [0, 1]} —— "低于适中值的程度"
     * @param delta         这一步偏离了多少 —— 用户要求的那个 diff
     * @param sourceChannel 哪条生理通道越界了, 取自 {@code CoreEventCatalog.Channels}
     * @param at            感知时刻
     * @param description   一行解释, 会进日志与行为分析
     */
    public void feelInternal(String kind, double intensity, double delta, String sourceChannel,
                             Instant at, String description) {
        feel(SensoryStimulus.interoceptive(modality(), kind, intensity, delta, sourceChannel,
                at, description));
    }

    /**
     * 这条通道现在有没有"身体在报警"这件事挂着。
     *
     * <p>给 Mind 侧的注意力算分用: 一个已经有 {@link Kinds#COLD} 挂着的身体,
     * 再来一条 {@code CONTACT} 的优先级应当降低 —— 因为她已经在处理这件事了。
     * 这不是去重(去重是 {@code RealtimeEventQueue.foldingKey} 的事), 是处境。
     */
    public boolean hasInternalAlert() {
        return peekAll().stream().anyMatch(SensoryStimulus::interoceptive);
    }
}
