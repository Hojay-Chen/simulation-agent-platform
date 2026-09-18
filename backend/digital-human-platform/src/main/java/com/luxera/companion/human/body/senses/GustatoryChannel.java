package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

/**
 * V2.2 §3.2.2 —— <b>味觉通道</b>。
 *
 * <table border="1">
 *   <tr><th>物理量</th><td>五味强度(甜/咸/酸/苦/鲜), 各自归一化 {@code [0, 1]}</td></tr>
 *   <tr><th>现实对接点(未来机器人)</th><td>电子舌</td></tr>
 *   <tr><th>典型刺激</th><td>今天只在"吃饭"这个活动里有值</td></tr>
 * </table>
 *
 * <h2>为什么它有一个"嘴巴闭着"的开关</h2>
 * 这是五条通道里唯一一条有<b>结构性前提</b>的: 味觉需要"东西在嘴里"。
 * 没有这个前提, 系统里会出现一种荒谬: 世界侧(或者某个第三方插件)投了一条味觉读数,
 * 而她正在开会 —— 于是"她尝到了甜味"。
 *
 * <p>所以在通道里放一个 {@link #setMouthContact(boolean)}, 由 {@code Life} 的活动
 * 上下文驱动(开始吃饭 → true, 吃完/中途放下 → false)。它<b>不是</b>权限控制
 * (没有恶意的一方要绕过它), 而是<b>物理前提的显式表达</b>: 一条在嘴巴闭着时到达的
 * 味觉读数会被丢弃并记账, 于是"她为什么没尝出来"有一个可查的答案。
 *
 * <h2>五味是读数, "好吃"是评价</h2>
 * 通道给的是五个分量, 不是"好吃/难吃"。理由与嗅觉通道完全相同, 但更明显:
 * 同一碗面, 饿的时候好吃, 刚吃完三碗的时候难吃 —— 差别在她, 不在这碗面。
 * 而"太咸了"这种判断确实需要阈值知识(咸味超过 0.7 就不适), 所以通道<b>会</b>给出
 * {@link #isOverwhelming(RawSignal.Taste)} 这样一个可查证的事实, 但它只是一条读数,
 * 不是"她决定不吃了"。
 *
 * <h2>为什么不做"阈值随时间下降"(味觉适应)</h2>
 * 味觉确实有适应(吃第一口最甜), 但它的时间尺度是<b>秒级</b>的, 而我们的 tick 是
 * 分钟级 —— 在这两个尺度之间建模适应只会得到一个"每 tick 都重置"的假状态。
 * 所以这里明确<b>不</b>建模, 并在文档里写下这个决定: 一个说不清自己为什么存在的
 * 状态量, 比没有这个状态量更糟。
 */
public final class GustatoryChannel extends SensoryChannel<RawSignal.Taste> {

    /** 咸味超过这个值就"齁"。 */
    public static final double SALTY_OVERWHELMING = 0.70;
    /** 苦味超过这个值就"苦得难以下咽"。 */
    public static final double BITTER_OVERWHELMING = 0.60;

    /** 味觉刺激的类别标签。 */
    public static final class Kinds {
        private Kinds() {
        }

        public static final String SWEET = "sweet";
        public static final String SALTY = "salty";
        public static final String SOUR = "sour";
        public static final String BITTER = "bitter";
        public static final String UMAMI = "umami";
        /** 五味都很弱 —— 白水、白饭。 */
        public static final String BLAND = "bland";
        /** 多种味道同时很强 —— "五味俱全"。 */
        public static final String COMPLEX = "complex";
    }

    private boolean mouthContact;

    public GustatoryChannel(String humanId) {
        this(humanId, SensoryAcuity.humanTongue(), DEFAULT_CAPACITY);
    }

    public GustatoryChannel(String humanId, SensoryAcuity acuity, int capacity) {
        super(new ChannelId(humanId, CoreEventCatalog.Modalities.GUSTATORY), acuity, capacity);
    }

    /**
     * 嘴里有没有东西 —— 见类注释"为什么它有一个'嘴巴闭着'的开关"。
     *
     * <p>默认 {@code false}: 一个刚装配好的 agent 没在吃东西, 而这正是默认值最安全的取向
     * (默认 true 会让"她尝到了味道"变成一个需要解释的默认行为)。
     */
    public void setMouthContact(boolean contact) {
        this.mouthContact = contact;
    }

    public boolean hasMouthContact() {
        return mouthContact;
    }

    @Override
    protected SensoryStimulus interpret(RawSignal.Taste raw, double perceivedIntensity) {
        if (!mouthContact) {
            discard("嘴里没有食物 —— 味觉需要接触前提, 收到 " + raw.describe());
            return null;
        }
        String kind = classify(raw);
        String detail = raw.describe()
                + (isOverwhelming(raw) ? " [过冲]" : "");
        return SensoryStimulus.external(modality(), kind, perceivedIntensity,
                GustatoryChannel.class.getSimpleName(), raw.sourceObjectId(), perceivedAt(raw), detail);
    }

    /**
     * 这一口的味道属于哪一类。
     *
     * <p>取<b>最强的那一味</b>作为主类别, 但"多种同时强"要被单独标出来 ——
     * 因为"甜"和"又甜又酸"在行为上是两件事(后者可能是坏了的水果)。
     */
    public String classify(RawSignal.Taste raw) {
        double max = raw.rawMagnitude();
        if (max < 0.20) {
            return Kinds.BLAND;
        }
        long strong = 0;
        for (double v : new double[]{raw.sweetness(), raw.saltiness(), raw.sourness(),
                raw.bitterness(), raw.umami()}) {
            if (v >= 0.4) {
                strong++;
            }
        }
        if (strong >= 2) {
            return Kinds.COMPLEX;
        }
        if (max == raw.sweetness()) {
            return Kinds.SWEET;
        }
        if (max == raw.saltiness()) {
            return Kinds.SALTY;
        }
        if (max == raw.sourness()) {
            return Kinds.SOUR;
        }
        if (max == raw.bitterness()) {
            return Kinds.BITTER;
        }
        return Kinds.UMAMI;
    }

    /**
     * 这一口是不是"过冲"了 —— 齁咸、极苦。
     *
     * <p>它是一条<b>读数级的事实</b>(阈值就在舌头里), 不是"她不想吃了"这个决定。
     * 那个决定需要知道她饿不饿, 属于 Mind。
     */
    public boolean isOverwhelming(RawSignal.Taste raw) {
        return raw.saltiness() >= SALTY_OVERWHELMING || raw.bitterness() >= BITTER_OVERWHELMING;
    }
}
