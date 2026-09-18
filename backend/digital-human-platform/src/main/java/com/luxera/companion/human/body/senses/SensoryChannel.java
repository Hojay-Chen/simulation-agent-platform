package com.luxera.companion.human.body.senses;

import com.luxera.companion.registry.CoreEventCatalog;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.2.2 —— <b>一条感官通道</b>: 把物理刺激衰减成她感觉得到的东西。
 *
 * <h2>它的入口只依赖两件东西: 原始信号 + 灵敏度</h2>
 * 这是本类最重要的一条设计决定, 也是"将来接入真实机器人"这件事在代码里的落点:
 * <pre>{@code
 *   receive(T rawSignal)      ← 只认物理量(声压级/lux/浓度/牛顿/摄氏度)
 *   acuity()                  ← 只认一组标定常数(阈限/增益/饱和/可觉差)
 * }</pre>
 * 它<b>不认识</b> {@code WorldEvent}、不认识 {@code EventTypeId}、不认识
 * {@code Phone} 或 {@code Environment}。这不是洁癖, 是三条硬约束的交点:
 * <table border="1">
 *   <tr><th>约束</th><th>如果通道认识事件类型会怎样</th></tr>
 *   <tr>
 *     <td><b>P3: Human 与 World 零交互</b></td>
 *     <td>{@code human/} 里出现 {@code import ...world...} —— §8.2.7 的 ArchUnit
 *         编译期就红。感官通道是 Human 最边缘的一层, 它必须是依赖方向的最下游</td>
 *   </tr>
 *   <tr>
 *     <td><b>用户: 将来接真实机器人</b></td>
 *     <td>通道认识的是<b>我们模拟器造的</b>事件类型, 而真实麦克风不产生那个类型 ——
 *         于是"接机器人"变成"重写感官层"。而现在它只是换一个 {@code RawSignal} 的来源</td>
 *   </tr>
 *   <tr>
 *     <td><b>可解释性: "她为什么没听见"</b></td>
 *     <td>如果衰减逻辑散在事件处理里, "没听见"的原因就散在世界的各个角落。
 *         收在一条通道里, 这个问题只有一个答案: 阈限、视野、适应、缓冲溢出, 四选一</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么是抽象类而不是接口</h2>
 * 文档 §3.2.2 给的是接口形状({@code id() / receive() / drain()}), 本实现保留这三个
 * 方法<b>一个不改</b>, 但把它做成抽象类, 因为五条通道要共享四样<b>有状态</b>的东西:
 * <ol>
 *   <li>待取走的刺激缓冲({@link #drain()} 的语义要求它存在);</li>
 *   <li>当前灵敏度({@link #adapt(SensoryAcuity)} 会改它 —— 嗅觉适应与暗适应都长这样);</li>
 *   <li>丢弃计数与最近一次丢弃的原因({@link #discardedCount()} —— "她为什么没感觉到"
 *       的另一半答案);</li>
 *   <li>上一次的读数(温差、可觉差判断都要它)。</li>
 * </ol>
 * 做成接口就要么写一个 {@code AbstractSensoryChannel}, 要么让五条通道各写一遍同样的
 * 四十行 —— 而<b>重复的状态管理是 bug 的温床</b>: 只要有一条通道忘了在适当时刻清缓冲,
 * 她的听觉就会在几小时后"积压了几万条刺激"。
 *
 * <h2>两个入口, 一个出口 —— 见 {@link #receive(RawSignal)} 与 {@link #feel(SensoryStimulus)}</h2>
 * <pre>
 *   外部世界 ──rawSignal──> receive() ──┐
 *                                       ├──> pending ──drain()──> Perception
 *   身体内部 ──stimulus───> feel()   ──┘
 * </pre>
 * 两条入口在 {@code TactileChannel} 上都会用到, 而它们的产物是同一个类型, 因为
 * <b>她自己分不清</b> —— 详见 {@link SensoryStimulus#interoceptive()}。
 */
public abstract class SensoryChannel<T extends RawSignal> {

    /**
     * 一条通道的身份: <b>谁的 + 哪条</b>。
     *
     * <p>为什么要带 {@code humanId}: 因为多 agent 仿真里"听觉通道"不是一个全局对象。
     * 一个把 humanId 剥掉的 {@code SensoryChannelId} 会让日志里的"听觉通道丢弃了 3 条"
     * 无法回答"是谁的耳朵"。
     */
    public record ChannelId(String humanId, String modality) {

        public ChannelId {
            Objects.requireNonNull(humanId, "通道必须知道它属于谁 —— 感官是 per-human 的, 不是一个全局对象");
            Objects.requireNonNull(modality, "通道必须知道自己对应哪条感官");
            if (!CoreEventCatalog.Modalities.isStandard(modality)) {
                throw new IllegalArgumentException("未知的感官通道 \"" + modality + "\"");
            }
        }

        @Override
        public String toString() {
            return humanId + "/" + modality;
        }
    }

    /**
     * 默认缓冲容量。
     *
     * <p>64 是刻意的小值。缓冲区不是历史 —— 它是"还没被取走的感觉", 而一个真人
     * 的未处理感觉是<b>很少</b>的: 被取走的节奏(每 tick 一次 drain)远快于产生的节奏。
     * 容量给到几千只会掩盖"没有人 drain"这个真实的装配错误。
     */
    public static final int DEFAULT_CAPACITY = 64;

    private final ChannelId id;
    private final int capacity;
    private final Deque<SensoryStimulus> pending;

    /** 当前灵敏度。可以随适应被替换 —— 见 {@link #adapt(SensoryAcuity)}。 */
    private SensoryAcuity acuity;

    /** 上一次收到的原始读数 —— 温差、可觉差判断的基准。 */
    private RawSignal lastRaw;

    private int discardedCount;
    private String lastDiscardReason;

    /**
     * @param id       身份
     * @param acuity   初始灵敏度
     * @param capacity 缓冲容量, 见 {@link #DEFAULT_CAPACITY}
     */
    protected SensoryChannel(ChannelId id, SensoryAcuity acuity, int capacity) {
        this.id = Objects.requireNonNull(id, "通道身份不能为空");
        this.acuity = Objects.requireNonNull(acuity,
                "通道灵敏度不能为空 —— 没有灵敏度的通道收不到任何东西, 而它的表现是'她聋了'却没有报错");
        if (capacity <= 0) {
            throw new IllegalArgumentException("通道缓冲容量必须为正, 收到 " + capacity);
        }
        this.capacity = capacity;
        this.pending = new ArrayDeque<>(Math.min(capacity, 16));
    }

    // ─────────────────────────── 身份与灵敏度 ───────────────────────────

    public ChannelId id() {
        return id;
    }

    public String modality() {
        return id.modality();
    }

    public SensoryAcuity acuity() {
        return acuity;
    }

    /**
     * 换一组灵敏度 —— <b>适应</b>。
     *
     * <p>它服务的不是"调参", 而是三种真实存在的现象:
     * <ul>
     *   <li><b>嗅觉适应</b>: 在饭香里待十分钟就闻不到了 —— 阈限升高;</li>
     *   <li><b>暗适应</b>: 关灯五分钟后能看见东西 —— 阈限降低;</li>
     *   <li><b>疲劳/酒精</b>: 增益下降。</li>
     * </ul>
     * 三种都是"灵敏度这个值本身变了", 而不是"通道的逻辑变了"。这也是为什么灵敏度
     * 要收成一个可整体替换的值对象(见 {@link SensoryAcuity} 的说明)。
     *
     * <p>它是 {@code public} 的: 适应既可能由通道自己触发(视觉通道按环境照度自动适应),
     * 也可能由外部触发(睡眠、药物) —— 而外部触发那一方不该需要知道通道的内部结构。
     */
    public void adapt(SensoryAcuity newAcuity) {
        this.acuity = Objects.requireNonNull(newAcuity, "新的灵敏度不能为空");
    }

    // ─────────────────────────── 入口一: 外部原始信号 ───────────────────────────

    /**
     * 一条外部物理刺激到达。
     *
     * <h3>它做三件事, 顺序是有讲究的</h3>
     * <pre>
     *   ① 量纲核对: 这条读数是不是走我这条通道 (错了就丢弃并记账, 不静默)
     *   ② 灵敏度衰减: acuity.perceivedIntensity(raw) == 0 → 低于阈限, 丢弃并记账
     *   ③ 解释: interpret(raw, intensity) → SensoryStimulus → 入缓冲
     * </pre>
     *
     * <h3>为什么"丢弃"必须记账</h3>
     * 因为 {@link #discardedCount()} 与 {@link #lastDiscardReason()} 是
     * <b>"她为什么没反应"这个问题唯一的答案来源</b>。没有它们, 行为分析会看到
     * "手机响了但她没动"并推断"她不在乎", 而真相可能是"那声铃声低她这条通道的阈限
     * 三倍" —— 一个会让整个仿真研究得出错误结论的假象。这与
     * {@code system.stimulus-dropped.v1} 存在的理由完全相同, 只是那一层管的是队列,
     * 这一层管的是神经末梢。
     *
     * <h3>传导延迟在这里体现</h3>
     * 刺激的时刻不是 {@code raw.observedAt()}, 而是它<b>加上</b>
     * {@link SensoryAcuity#latencyMillis()}。差别在"先看到闪电再听到雷声"这类
     * 排序问题上是实质性的 —— 而那是真实感官的一个基本事实, 不该被仿真省掉。
     */
    public void receive(T rawSignal) {
        Objects.requireNonNull(rawSignal, "原始信号不能为空");
        if (!modality().equals(rawSignal.modality())) {
            discard("量纲不符: 把一条 " + rawSignal.modality() + " 读数投给了 " + modality() + " 通道");
            return;
        }

        SensoryAcuity current = this.acuity;
        double intensity = current.perceivedIntensity(rawSignal.rawMagnitude());
        if (intensity <= 0.0) {
            // 低于绝对阈限 —— 不是"很微弱的感觉", 是"没有感觉"
            discard("低于绝对阈限: " + formatRaw(rawSignal) + " / " + current.describe());
            return;
        }

        SensoryStimulus stimulus = interpret(rawSignal, intensity);
        this.lastRaw = rawSignal;
        if (stimulus == null) {
            // 子类可以拒绝一条过了阈限的读数(比如视野外、没在吃东西)。
            // 它<b>不</b>调用 discard(), 因为拒绝的理由是子类特有的, 由子类自己记
            return;
        }
        enqueue(stimulus);
    }

    /**
     * 把"过了阈限的读数"解释成一个具体的感觉。
     *
     * <p>子类在这里做自己特有的判断: 视野、频带、材质、适应、是否需要嘴巴接触。
     * 它<b>可以返回 null</b> —— 表示"这次读数我虽然收到了, 但它不是一个她能意识到的东西",
     * 比如视野之外的一束光。返回 null 时子类<b>应当</b>调用
     * {@link #discard(String)} 记下理由。
     *
     * @param raw                 原始读数(有完整量纲, 不是标量)
     * @param perceivedIntensity  已经过灵敏度衰减的强度 {@code (0, 1]}
     */
    protected abstract SensoryStimulus interpret(T raw, double perceivedIntensity);

    // ─────────────────────────── 入口二: 内部生理越界 ───────────────────────────

    /**
     * 一个<b>身体自己产生</b>的感觉。
     *
     * <p>它<b>不</b>经过灵敏度衰减 —— 因为这不是"传感器收到了信号", 而是"身体知道
     * 自己怎么了"。保暖值跌破舒适带不是一种"被传感器检测到的现象", 它本身就是事实。
     * 把它也过一遍阈限, 会得到"她冷到一定程度才觉得自己冷"这种奇怪的二次衰减。
     *
     * <p>它由 {@code ThresholdDetector} 调用(见 §3.2.4 的链条), 而不是由世界侧调用 ——
     * 世界侧根本不该知道她有"内部感觉"这回事。
     *
     * @throws IllegalArgumentException 刺激的 modality 与本通道不符
     */
    public final void feel(SensoryStimulus stimulus) {
        Objects.requireNonNull(stimulus, "内部刺激不能为空");
        if (!modality().equals(stimulus.modality())) {
            throw new IllegalArgumentException(
                    "一条 " + stimulus.modality() + " 的内部刺激被投给了 " + modality() + " 通道 ("
                            + id + ") —— 这一定是装配错误, 因为内部刺激不经过世界, 不存在'投错人'的可能");
        }
        enqueue(stimulus);
    }

    // ─────────────────────────── 出口 ───────────────────────────

    /**
     * 取走当前积压的全部刺激, 并清空缓冲。
     *
     * <p>语义是"感知取走了它们" —— 取走之后通道不再持有, 所以这个方法不是幂等的。
     * 返回不可变列表: 调用方会遍历它并在遍历中继续收新的刺激(一个刺激引发一次回忆,
     * 回忆又产生新的内部刺激), 返回活视图会抛 {@code ConcurrentModificationException}。
     */
    public List<SensoryStimulus> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<SensoryStimulus> out = new ArrayList<>(pending);
        pending.clear();
        return List.copyOf(out);
    }

    /** 看一眼积压的刺激, <b>不取走</b>。给诊断面板与测试用。 */
    public List<SensoryStimulus> peekAll() {
        return List.copyOf(pending);
    }

    public int pendingCount() {
        return pending.size();
    }

    public int capacity() {
        return capacity;
    }

    /** 是否积压了走某条通道的刺激 —— {@code RealtimeEventQueue.hasPendingOn} 的同构问题。 */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    // ─────────────────────────── 可解释性 ───────────────────────────

    /** 被这条通道丢弃的刺激条数(低于阈限、量纲不符、视野外……)。 */
    public int discardedCount() {
        return discardedCount;
    }

    /** 最近一次丢弃的原因。空 = 从来没丢过。 */
    public Optional<String> lastDiscardReason() {
        return Optional.ofNullable(lastDiscardReason);
    }

    /**
     * 丢弃一条读数并记账。
     *
     * <p>做成 {@code protected} 而不是私有: 子类的 {@code interpret()} 需要用它报告
     * "我拒绝了一条过了阈限的读数"(视野外、没在吃东西)。
     */
    protected final void discard(String reason) {
        discardedCount++;
        lastDiscardReason = reason;
    }

    /** 上一次收到的原始读数 —— 温差与可觉差判断的基准。 */
    protected final Optional<RawSignal> lastRaw() {
        return Optional.ofNullable(lastRaw);
    }

    /** 清空缓冲与统计 —— 只给测试与"重新初始化"用, 生产路径上没有这个操作。 */
    public void clear() {
        pending.clear();
        discardedCount = 0;
        lastDiscardReason = null;
        lastRaw = null;
    }

    public String describe() {
        return id + " [" + acuity.describe() + "] 积压 " + pending.size() + "/" + capacity
                + ", 已丢弃 " + discardedCount
                + lastDiscardReason().map(r -> " (最近: " + r + ")").orElse("");
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 入缓冲。满了就丢<b>最旧的</b>。
     *
     * <h3>为什么丢最旧的, 而不是拒绝新的</h3>
     * 因为缓冲里装的是"还没被取走的感觉", 而她活在此刻。一个塞满旧刺激的缓冲,
     * 表现是"她还在处理十分钟前的事" —— 而真实的人恰恰相反: 被淹没时最先丢掉的
     * 是旧的。这与 {@code RealtimeEventQueue} 溢出时丢 urgency 最低的那条不同,
     * 因为队列知道每条刺激的紧迫度, 而一条通道不知道(它只知道物理强度, 不知道
     * 她此刻的处境 —— 那个判断属于 Mind, 见 {@code SensoryEvent.urgency()} 的说明)。
     */
    private void enqueue(SensoryStimulus stimulus) {
        while (pending.size() >= capacity) {
            SensoryStimulus evicted = pending.pollFirst();
            if (evicted == null) {
                break;
            }
            discard("缓冲溢出, 丢弃最旧的一条: " + evicted.kind());
        }
        pending.addLast(stimulus);
    }

    /**
     * 感知时刻 = 采集时刻 + 传导延迟。
     *
     * <p>子类在造刺激时应当用它, 而不是直接用 {@code raw.observedAt()} ——
     * 这样"延迟"这个物理事实只需要在灵敏度里配一次, 五条通道都自动带上。
     */
    protected final Instant perceivedAt(RawSignal raw) {
        return raw.observedAt().plusMillis((long) acuity.latencyMillis());
    }

    private static String formatRaw(RawSignal raw) {
        return raw.describe() + " (主强度 " + String.format("%.4f", raw.rawMagnitude())
                + ", 来源 " + (raw.sourceObjectId() == null ? "内部" : raw.sourceObjectId()) + ")";
    }
}
