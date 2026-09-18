package com.luxera.companion.human.mind.percept;

import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.human.body.senses.SensoryStimulus;

import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.4.3 —— <b>把感官刺激解释成"我感知到了什么"</b>。
 *
 * <pre>
 *   SensoryEvent(device.phone.notification-raised, modality=auditory, urgency=0.68)
 *       ↓ Perception.explain
 *   Percept(modality=AUDITORY, content="手机发出了一声提示音",
 *           salience=0.674, urgency=0.68, source=她的手机, occurredAt=…)
 * </pre>
 *
 * <h2>它做的唯一一件事: 把"物理量"换成"她的话"</h2>
 * 三个动作, 没有第四个:
 * <ol>
 *   <li><b>认通道</b> —— {@code "auditory"} → {@link Modality#AUDITORY}。认不出来就是
 *       {@link Modality#UNKNOWN}, 显著度自然是 0;</li>
 *   <li><b>算显著度</b> —— 见下;</li>
 *   <li><b>说一句话</b> —— 交给 {@link PerceptLexicon}。</li>
 * </ol>
 *
 * <h2>显著度怎么算 —— 以及它为什么<b>只看刺激</b></h2>
 * <pre>{@code
 *   salience = clamp01( 0.30 + 0.55 × urgency ) × modality.weight()
 * }</pre>
 * <p>三个数字各有出处, 而<b>没有一个是她的处境</b>:
 * <ul>
 *   <li>{@code 0.30} —— 底数。任何到达她感官的刺激都已经"发生"了, 不会是 0。
 *       完全无感的刺激根本不会变成 {@code SensoryEvent};</li>
 *   <li>{@code 0.55 × urgency} —— 世界说"这条能不能等", 她说"那它有多突出"。
 *       这是一个<b>单调映射</b>, 不是新的判断;</li>
 *   <li>{@link Modality#weight()} —— 通道之间的相对灵敏度。见 {@link Modality}。</li>
 * </ul>
 * <b>这个方法的签名里没有 {@code AttentionContext}, 也没有她。</b>这不是省略,
 * 是设计: 唯一的构造函数只收一个词表和一个来源解析器, 所以在这里写不出
 * "因为她在睡觉所以打个折"这类代码 —— 编译器不给。而"她此刻的状态"那一半折扣,
 * 在 {@link com.luxera.companion.human.mind.attention.AttentionService} 里, 且只乘一次。
 *
 * <h3>与文档 §3.4.3 的一处措辞差异</h3>
 * §3.4.3 说 salience 是"响度、亮度、强度"。本实现拿不到强度 ——
 * {@link SensoryEvent} 这份契约上只有 {@code urgency()}, 没有 {@code intensity()}
 * (那是刻意的, 见该接口的 javadoc: 队列的定序发生在 Mind 之前)。
 * 所以这里是 <b>urgency 的单调映射</b>, 而不是对强度的一次读数。
 * 等 {@code SensoryEvent} 长出 {@code intensity} 时, 应当只改 {@link #salienceOf}
 * 这一个方法 —— 这也是把公式收在一个静态方法里的原因。
 *
 * <h3>与文档 §3.4.4 的任何一处都不同: 时间戳与关系数<b>不</b>参与显著度</h3>
 * §3.4.4 的括号里写着 salience 由"消息连发条数、关系数值、时间戳算出来的"。
 * <b>本实现刻意不这么做</b>, 理由就是那条边界铁律自己:
 * 时间戳一旦进了 salience, 一条深夜的消息在<b>源头</b>就已经被折扣了一次;
 * 而 Attention 又会因为"她在睡觉"再折扣一次 —— 这正是"两边都打折就是双罚"
 * 的字面复现。所以这里只认刺激本身。连发条数与关系数值同理: 前者是队列的折叠
 * (见 {@code RealtimeEventQueue} 的 foldingKey), 后者属于
 * {@code AttentionContext.relevance}。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不判断重不重要</b>。它算的是"多突出", 不是"要不要管"。</li>
 *   <li><b>不丢东西</b>。认不出的通道照样产出一个 Percept(显著度为 0),
 *       让上层有机会把它记进日志 —— 静默丢弃会让"她没反应"变得不可解释。</li>
 *   <li><b>不认识人</b>。它只产出 {@link SourceRef}(物体), 见那里的禁令。</li>
 * </ul>
 */
public final class Perception {

    /** 显著度的底数 —— 见类注释。 */
    public static final double SALIENCE_BASE = 0.30;

    /** 紧迫度对显著度的折算系数 —— 见类注释。 */
    public static final double SALIENCE_PER_URGENCY = 0.55;

    private final PerceptLexicon lexicon;
    private final SourceResolver sources;

    /** 默认装配: 通用词表 + "照搬世界对象 id, 但不声称认识它"。 */
    public Perception() {
        this(PerceptLexicon.generic(), SourceResolver.objectsOnly());
    }

    public Perception(PerceptLexicon lexicon) {
        this(lexicon, SourceResolver.objectsOnly());
    }

    public Perception(PerceptLexicon lexicon, SourceResolver sources) {
        this.lexicon = Objects.requireNonNull(lexicon,
                "词表不能为 null —— 没有它, 感知的内容会退化成一句类型 id, 而那句话对模型和人都没有信息");
        this.sources = Objects.requireNonNull(sources, "来源解析器不能为 null");
    }

    /**
     * 把一条来自世界的感官事件解释成一个感知。
     *
     * <p>不抛异常、不返回 {@code null}。任何一条到达这里的事件都会变成一个 Percept ——
     * 见类注释"不丢东西"。
     */
    public Percept explain(SensoryEvent event) {
        Objects.requireNonNull(event, "要解释的刺激事件不能为空");
        Modality modality = Modality.fromChannel(event.modality());
        double salience = salienceOf(event, modality);
        return new PerceptRecord(
                PerceptId.generate(),
                modality,
                lexicon.describe(event),
                salience,
                clamp01(event.urgency()),
                sources.resolve(event),
                event.occurredAt());
    }

    /**
     * 身体内部的感受通道 —— 饿、冷、疼、累。
     *
     * <p>它走的是另一条输入: 不是世界事件, 而是 {@code Body} 的
     * {@link SensoryStimulus}(见 {@code human.body.senses} 的类注释:
     * 内部感受与外部刺激在通道上汇合, 但来源不同)。
     * 这里保留它, 是因为"她注意到自己饿了"与"她听到手机响了"在后续处理上完全同构,
     * 而如果只剩事件入口, 内部感受就得先绕一圈变成事件才能被感知 ——
     * 那一圈会让"她饿不饿"和"世界发生了什么"在时间线上错位。
     */
    public Percept explainInteroceptive(SensoryStimulus stimulus) {
        Objects.requireNonNull(stimulus, "要解释的内部感受不能为空");
        Modality modality = Modality.fromChannel(stimulus.modality());
        double salience = clamp01((SALIENCE_BASE + SALIENCE_PER_URGENCY * clamp01(stimulus.intensity()))
                * modality.weight());
        String content = stimulus.description() == null || stimulus.description().isBlank()
                ? "身体上有了感觉(" + stimulus.kind() + ")"
                : stimulus.description();
        SourceRef source = stimulus.interoceptive()
                ? SourceRef.selfBody(stimulus.sourceChannel())
                : SourceRef.ofObject(stimulus.sourceObjectId());
        return new PerceptRecord(PerceptId.generate(), modality, content, salience,
                clamp01(stimulus.intensity()), source, stimulus.observedAt());
    }

    /**
     * 显著度公式 —— 收在一个静态方法里, 因为它是本类<b>唯一</b>会变的东西。
     *
     * <p>见类注释。参数里没有她, 这是刻意的。
     */
    public static double salienceOf(SensoryEvent event, Modality modality) {
        return clamp01((SALIENCE_BASE + SALIENCE_PER_URGENCY * clamp01(event.urgency()))
                * modality.weight());
    }

    static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    public PerceptLexicon lexicon() {
        return lexicon;
    }

    public String describe() {
        return "Perception[词表=" + lexicon.getClass().getSimpleName()
                + ", 来源解析=" + sources.getClass().getSimpleName() + "]";
    }

    /**
     * 世界对象 id → {@link SourceRef} 的翻译。
     *
     * <p>独立成一个接口, 而不是让 Perception 直接 {@code new SourceRef(...)}:
     * "她认不认得这个东西"是<b>她的知识</b>, 而这句话由别人持有(设备装配方知道
     * {@code "phone-1"} 就是她自己的手机)。让它可替换, 才能既保持核心包干净,
     * 又让"她认得出自己手机的声音"这件事被如实建模。
     */
    @FunctionalInterface
    public interface SourceResolver {

        SourceRef resolve(SensoryEvent event);

        /** 默认解析器: 照搬 id, 不声称认识。见 {@link SourceRef#ofObject}。 */
        static SourceResolver objectsOnly() {
            return event -> SourceRef.ofObject(event.sourceObjectId());
        }

        /**
         * 按类型 id 前缀判定的解析器 —— 装配方用它把"来自我这台设备的刺激"
         * 标成 {@link SourceRef#ownDevice}。
         *
         * <p>它<b>不是</b>核心包的默认行为: 前缀表是装配方给的, 核心包里没有
         * 任何一个具体设备的名字。
         */
        static SourceResolver byTypePrefix(String typePrefix, String ownObjectId, String label) {
            Objects.requireNonNull(typePrefix, "前缀不能为 null");
            return event -> event.typeId().toString().startsWith(typePrefix)
                    ? SourceRef.ownDevice(ownObjectId, label)
                    : SourceRef.ofObject(event.sourceObjectId());
        }
    }

    /**
     * 手搓一个感知 —— 给<b>测试</b>与装配脚本用。
     *
     * <p>为什么不把 {@code PerceptRecord} 本身做成公开的: 见那里"为什么是包私有"。
     * 而这个静态工厂是安全的, 因为它<b>造不出</b>新的字段形状 ——
     * 参数表就是 {@link Percept} 的那七个属性, 想加第八个得先改这里, 那是看得见的改动。
     *
     * <p>{@code salience} 与 {@code urgency} 都会被夹到 {@code [0, 1]}。
     */
    public static Percept percept(Modality modality, String content, double salience,
                                  double urgency, SourceRef source, Instant occurredAt) {
        return new PerceptRecord(PerceptId.generate(), modality, content,
                clamp01(salience), clamp01(urgency), source, occurredAt);
    }

    /**
     * {@link Percept} 的唯一实现。
     *
     * <p>做成包私有的 record, 而不是让调用方自己实现 {@code Percept} ——
     * 因为"一个感知恰好有这七个属性"这件事, 是本设计的一个结论, 不是扩展点。
     * 让它可以被实现, 就等于允许别人往里加第八个属性(比如一个 sender 字段),
     * 而 §3.4.3 的整段禁令防的正是这个。
     */
    record PerceptRecord(
            PerceptId id,
            Modality modality,
            String content,
            double salience,
            double urgency,
            SourceRef source,
            Instant occurredAt) implements Percept {

        PerceptRecord {
            Objects.requireNonNull(id, "感知必须有身份");
            Objects.requireNonNull(modality, "感知必须走某条通道 —— 用 Modality.UNKNOWN 表达'认不出'");
            Objects.requireNonNull(content, "感知必须有一句可读的内容 —— 没有它这条感知进不了 LLM context");
            Objects.requireNonNull(source, "感知必须有来源 —— 用 SourceRef.unknown() 表达'说不上来'");
            Objects.requireNonNull(occurredAt, "感知必须带时刻 —— 行为分析的时间线靠它对齐。"
                    + "时刻一律由调用方传入: human/ 里不许读系统时钟");
        }
    }
}
