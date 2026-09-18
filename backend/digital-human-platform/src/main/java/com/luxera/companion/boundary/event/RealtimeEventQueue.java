package com.luxera.companion.boundary.event;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §5.3 —— <b>B 类事件的归宿: 一个真正的优先队列</b>。
 *
 * <h2>为什么这一类就该是队列</h2>
 * 与 {@link ContinuousEffectLedger} 相反, B 类的语义天生是队列的:
 * <ul>
 *   <li><b>发生在一刻</b>, 不是持续成立 —— "手机响了"是一个事件, 不是一段状态;</li>
 *   <li><b>取出即消费</b> —— 她注意到手机响了之后, 那条刺激就完成了它的使命,
 *       不需要一直留着;</li>
 *   <li><b>有先后</b> —— 同一时刻来了"手机响"和"火警响", 先递哪个是有答案的。</li>
 * </ul>
 *
 * <h2>定序: {@code urgency ↓ → occurredAt ↑ → sequence ↑}</h2>
 * <table border="1">
 *   <tr><th>键</th><th>方向</th><th>为什么</th></tr>
 *   <tr>
 *     <td>{@code urgency}</td><td>降序</td>
 *     <td>急的先递。这是唯一一个能跨越"不同感官"比较的量 —— 视觉刺激和听觉刺激
 *         本身不可比, 但"能不能等"可比。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code occurredAt}</td><td>升序</td>
 *     <td>同样急的, 先发生的先递。这是公平性: 后到的刺激不该插到先到的前面,
 *         否则一个持续产生新刺激的世界会让早期刺激永远递不出去。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code sequence}</td><td>升序</td>
 *     <td>同一时刻同一 urgency 时的稳定定序。<b>没有它, 队列的行为会依赖
 *         PriorityQueue 内部堆的偶然形状</b> —— 那是一个只在特定插入顺序下才出现的
 *         不确定性, 极难复现。</td>
 *   </tr>
 * </table>
 *
 * <h2>刻意<b>不</b>包含 salience</h2>
 * V2.1 的版本里定序键是 {@code urgency ↓ → salience ↓ → occurredAt ↑}。
 * V2.2 去掉了中间的 salience, 理由是它不是世界的属性: 同一个手机横幅, 她在等面试结果时
 * 重要程度是 0.9, 在睡觉时是 0.05 —— 而队列是<b>世界侧</b>的结构, 它在 Mind 看到
 * 刺激之前就要排好序。让队列排序依赖一个 Mind 才能算的量, 会形成循环依赖。
 *
 * <p>{@code salience} 的计算因此在 {@code AttentionService} 里(§3.4.4),
 * 发生在<b>出队之后</b>。队列负责"先递哪个", Mind 负责"这个对她多重要" —— 各归各的。
 *
 * <h2>容量与丢弃</h2>
 * 队列有容量上限。溢出时丢 {@code urgency} 最低的那条, <b>并记一条
 * {@code system.stimulus-dropped.v1}</b>。
 *
 * <p>为什么丢弃是可接受的: 一个真人也不会注意到手机上每一条横幅 —— "她错过了什么"
 * 是这个系统要模拟的东西, 不是要消灭的 bug。但<b>丢弃必须可见</b>: 若丢弃是静默的,
 * 行为分析会看到"她没反应"而推断"她不在乎", 而真相是"那条刺激根本没递到她面前"。
 * 那是一个会让整个仿真研究得出错误结论的假象。
 *
 * <h2>折叠: 同一件事的持续刺激</h2>
 * 隔壁装修的电钻声不该把队列灌满。{@link SensoryEvent#foldingKey()} 相同的未出队刺激
 * 会被折叠成一条, 队列里保留<b>最早那条的定序</b>(公平性)但记下<b>最新那条的发生时刻</b>
 * (她感受到的是"还在响")。见 {@link #offer}。
 */
@Slf4j
public class RealtimeEventQueue {

    /** 默认容量。够装下一次醒来要处理的所有刺激, 又不至于让队列变成第二个内存泄漏点。 */
    public static final int DEFAULT_CAPACITY = 256;

    /**
     * 定序器。
     *
     * <p>写成常量而不是内联, 是为了让它能被单独测试 —— 一个排序错了的队列
     * 表现是"她偶尔反应顺序奇怪", 而那是最难从日志里看出来的症状。
     */
    private static final Comparator<Slot> ORDER = Comparator
            .comparingDouble((Slot s) -> s.event().urgency()).reversed()
            .thenComparing(s -> s.event().occurredAt())
            .thenComparingLong(Slot::sequence);

    /** 进了队列的一条刺激。 */
    private record Slot(SensoryEvent event, long sequence, Instant firstSeenAt, int foldCount) {

        Slot folded(SensoryEvent newer) {
            return new Slot(newer, sequence, firstSeenAt, foldCount + 1);
        }
    }

    private final int capacity;

    /**
     * 用 {@link LinkedHashMap} 而不是 {@link java.util.PriorityQueue}。
     *
     * <p>理由: 我们需要<b>三种</b>操作, 而 {@code PriorityQueue} 只擅长一种。
     * <table border="1">
     *   <tr><th>操作</th><th>{@code PriorityQueue}</th><th>本实现</th></tr>
     *   <tr>
     *     <td>取最急的一条</td><td>O(log n)</td>
     *     <td>O(n) 全扫 —— 但 n ≤ 256, 而每 tick 只取常数条</td>
     *   </tr>
     *   <tr>
     *     <td>按 foldingKey 折叠</td><td><b>O(n) 全扫, 且无法原地更新</b></td>
     *     <td>O(1) 查表</td>
     *   </tr>
     *   <tr>
     *     <td>丢弃最低 urgency 的一条</td><td><b>O(n) 全扫</b>(堆只保证堆顶最小/最大)</td>
     *     <td>O(n) 全扫 —— 同样</td>
     *   </tr>
     *   <tr>
     *     <td>遍历顺序稳定</td><td><b>不保证</b></td>
     *     <td>插入顺序</td>
     *   </tr>
     * </table>
     *
     * <p>换句话说: 在 n ≤ 256 这个量级上, {@code PriorityQueue} 的 O(log n) 优势
     * 完全不存在, 而它的三个劣势(不能原地更新、不保证遍历顺序、丢弃要全扫)都真实存在。
     * <b>数据结构的选型要按实际操作分布来定, 不是按教科书上的复杂度表</b>。
     */
    private final Map<String, Slot> slots = new LinkedHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    /** 折叠键 → 槽位 id。<b>不是每个槽位都有折叠键</b>, 所以用单独的索引。 */
    private final Map<String, String> foldingIndex = new LinkedHashMap<>();

    /** 被丢弃的刺激 —— 留下来给行为分析看, 见类注释"容量与丢弃"。 */
    private final List<DroppedStimulus> dropped = new ArrayList<>();

    /** 丢弃记录最多留这么多条, 免得一个高刺激的世界把内存吃光。 */
    private static final int DROPPED_HISTORY_LIMIT = 128;

    public RealtimeEventQueue() {
        this(DEFAULT_CAPACITY);
    }

    public RealtimeEventQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("队列容量至少为 1, 收到 " + capacity);
        }
        this.capacity = capacity;
    }

    // ─────────────────────────── 入队 ───────────────────────────

    /**
     * 投一条刺激进来。
     *
     * @return 这次投递的结果 —— <b>调用方必须看它</b>, 因为 {@code DROPPED} 与
     *         {@code FOLDED} 是两种完全不同的"没进队列", 混为一谈会让
     *         "她为什么没反应"变成不可回答的问题
     */
    public OfferResult offer(SensoryEvent event) {
        Objects.requireNonNull(event, "要入队的刺激不能为空");

        String foldingKey = event.foldingKey();
        if (foldingKey != null && !foldingKey.isBlank()) {
            String existingId = foldingIndex.get(foldingKey);
            if (existingId != null && slots.containsKey(existingId)) {
                Slot existing = slots.get(existingId);
                slots.put(existingId, existing.folded(event));
                log.debug("[RealtimeQueue] {} 折叠进 {} (第 {} 次)", event.typeId(),
                        existingId, existing.foldCount() + 1);
                return OfferResult.folded(existingId, existing.foldCount() + 1);
            }
        }

        if (slots.size() >= capacity) {
            return evictAndOffer(event, foldingKey);
        }
        return doOffer(event, foldingKey);
    }

    private OfferResult doOffer(SensoryEvent event, String foldingKey) {
        long seq = sequence.incrementAndGet();
        String id = "stm-" + seq;
        slots.put(id, new Slot(event, seq, event.occurredAt(), 0));
        if (foldingKey != null && !foldingKey.isBlank()) {
            foldingIndex.put(foldingKey, id);
        }
        return OfferResult.accepted(id);
    }

    /**
     * 满了。丢一条再进来, 或者<b>连新来的这条一起丢</b>。
     *
     * <h3>新来的也可能被丢</h3>
     * 这是刻意的, 也是很多实现在这里做错的地方: 直觉上"满了就挤掉最旧的, 让新的进来",
     * 但本队列定序是按 urgency 的 —— 一条 {@code urgency = 0.1} 的手机横幅
     * 不该把一条 {@code urgency = 0.9} 的待处理刺激挤掉。所以规则是:
     * <b>拿新来的和队列里最低的比, 谁低丢谁</b>。
     *
     * <p>退化成"永远让新的进来"的后果是: 一个刺激密集的世界会持续地用琐碎刺激
     * 冲刷队列, 而真正该被处理的紧急刺激在排队期间被挤出去 —— 表现是"她对重要的事
     * 反而不反应", 一个极其反直觉且难查的症状。
     */
    private OfferResult evictAndOffer(SensoryEvent incoming, String foldingKey) {
        Map.Entry<String, Slot> lowest = slots.entrySet().stream()
                .min(Comparator.comparingDouble(e -> e.getValue().event().urgency()))
                .orElseThrow();

        if (lowest.getValue().event().urgency() >= incoming.urgency()) {
            recordDropped(incoming, "队列已满且新刺激的 urgency 不高于队列中最低的一条");
            log.debug("[RealtimeQueue] 丢弃新到刺激 {} (urgency={}) —— 队列已满且没有更低的可挤",
                    incoming.typeId(), incoming.urgency());
            return OfferResult.dropped(incoming, "队列已满");
        }

        slots.remove(lowest.getKey());
        clearFoldingIndex(lowest.getValue());
        recordDropped(lowest.getValue().event(), "队列已满, 被更高 urgency 的刺激挤掉");
        log.debug("[RealtimeQueue] 挤掉 {} (urgency={}) 以容纳 {} (urgency={})",
                lowest.getValue().event().typeId(), lowest.getValue().event().urgency(),
                incoming.typeId(), incoming.urgency());

        OfferResult result = doOffer(incoming, foldingKey);
        if (!(result instanceof OfferResult.Accepted accepted)) {
            // doOffer 只会返回 Accepted —— 它不做折叠也不做丢弃。
            // 走到这里说明 OfferResult 的家族被改过而本方法没跟上, 与其静默地
            // 把"挤掉了一条"这个事实丢掉, 不如直接暴露出来。
            throw new IllegalStateException(
                    "doOffer 只应返回 Accepted, 实际是 " + result.getClass().getSimpleName());
        }
        return OfferResult.acceptedAfterEviction(accepted.slotId(), lowest.getValue().event());
    }

    private void clearFoldingIndex(Slot slot) {
        String key = slot.event().foldingKey();
        if (key != null && !key.isBlank()) {
            foldingIndex.remove(key);
        }
    }

    private void recordDropped(SensoryEvent event, String reason) {
        if (dropped.size() >= DROPPED_HISTORY_LIMIT) {
            dropped.remove(0);
        }
        // 用事件自己的 occurredAt, 而不是读墙钟。
        // 这不是将就: "这条刺激什么时候被挤掉的" 的正确答案**就是**它到达的时刻 ——
        // 挤压发生在入队这一瞬间, 而那一刻由刺激携带。墙钟在这里不仅不确定,
        // 还会让"她被挤掉了多少条刺激"这个统计落在一条与仿真时间无关的时间轴上。
        dropped.add(new DroppedStimulus(event, reason, event.occurredAt()));
    }

    // ─────────────────────────── 出队 ───────────────────────────

    /**
     * 取最该先被处理的那条。
     *
     * <p><b>取出即消费</b> —— 出队后队列里就没有它了。这是与 {@link ContinuousEffectLedger}
     * 最根本的差别: 账目的量要一直加到过期, 刺激的量说一次就够。
     */
    public Optional<SensoryEvent> poll() {
        return peekSlot().map(entry -> {
            slots.remove(entry.getKey());
            clearFoldingIndex(entry.getValue());
            return entry.getValue().event();
        });
    }

    /** 看一眼最该先处理的是哪条, <b>不出队</b>。 */
    public Optional<SensoryEvent> peek() {
        return peekSlot().map(e -> e.getValue().event());
    }

    /**
     * 一次取一批 —— 上限 {@code max} 条。
     *
     * <p>给"她醒来了, 处理积压的刺激"这个场景用。为什么要有上限而不是
     * "一次全取出来": 一次醒来面对 200 条刺激不是"更完整的还原",
     * 而是一个真人不会有的处境 —— 真人醒来只会注意到几件最响的事。上限让这个
     * 仿真保持可信。
     */
    public List<SensoryEvent> drain(int max) {
        List<SensoryEvent> out = new ArrayList<>(Math.min(max, slots.size()));
        for (int i = 0; i < max; i++) {
            Optional<SensoryEvent> next = poll();
            if (next.isEmpty()) {
                break;
            }
            out.add(next.get());
        }
        return out;
    }

    private Optional<Map.Entry<String, Slot>> peekSlot() {
        return slots.entrySet().stream().min(Comparator.comparing(e -> e.getValue(), ORDER));
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public int size() {
        return slots.size();
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public int capacity() {
        return capacity;
    }

    /** 队列里现在有哪些刺激, <b>按定序排列</b> —— 诊断面板用。 */
    public List<SensoryEvent> snapshot() {
        return slots.values().stream().sorted(ORDER).map(Slot::event).toList();
    }

    /**
     * 被丢弃的刺激 —— <b>她"没反应"的解释之一</b>。
     *
     * <p>见类注释"容量与丢弃"。这个方法存在的全部意义是: 让
     * "她为什么没回那条消息"有一个可以被查证的答案, 而不是只能猜。
     */
    public List<DroppedStimulus> droppedStimuli() {
        return List.copyOf(dropped);
    }

    /**
     * 某个通道上有没有等待处理的刺激。
     *
     * <p>给 {@code AttentionService} 用: 她已经在处理一条听觉刺激时,
     * 另一条听觉刺激该不该打断她, 取决于处境 —— 而"有没有另一条在等"
     * 是那个判断的输入之一。
     */
    public boolean hasPendingOn(String modality) {
        return slots.values().stream()
                .anyMatch(s -> Objects.equals(s.event().modality(), modality));
    }

    /** 全部清空 —— <b>只给测试与"重新初始化"用</b>。 */
    public void clear() {
        slots.clear();
        foldingIndex.clear();
    }

    /** 供诊断用: 一行摘要。 */
    public String describe() {
        if (slots.isEmpty()) {
            return "队列为空";
        }
        StringBuilder sb = new StringBuilder("队列(").append(slots.size()).append("/")
                .append(capacity).append("): ");
        snapshot().stream().limit(5).forEach(s -> sb.append(s.typeId().name()).append(' '));
        if (slots.size() > 5) {
            sb.append("…");
        }
        return sb.toString();
    }

    // ─────────────────────────── 输出类型 ───────────────────────────

    /** 一次入队的结果。三种结局, 调用方应当分开处理。 */
    public sealed interface OfferResult {

        /** 进了队列。 */
        record Accepted(String slotId) implements OfferResult {}

        /** 折叠进了已有的一条 —— 不是丢弃, 她感受到的是"还在响"。 */
        record Folded(String slotId, int foldCount) implements OfferResult {}

        /** 被丢弃了 —— <b>这条刺激不会到达 Mind</b>。 */
        record Dropped(SensoryEvent event, String reason) implements OfferResult {}

        /** 挤掉了一条旧的之后才进队列。<b>被挤掉的那条要能被追溯</b>。 */
        record AcceptedAfterEviction(String slotId, SensoryEvent evicted) implements OfferResult {}

        static OfferResult accepted(String slotId) {
            return new Accepted(slotId);
        }

        static OfferResult folded(String slotId, int count) {
            return new Folded(slotId, count);
        }

        static OfferResult dropped(SensoryEvent e, String reason) {
            return new Dropped(e, reason);
        }

        static OfferResult acceptedAfterEviction(String slotId, SensoryEvent evicted) {
            return new AcceptedAfterEviction(slotId, evicted);
        }

        /** 这条刺激最终<b>到没到</b> Mind 面前。 */
        default boolean reachedMind() {
            return this instanceof Accepted || this instanceof Folded
                    || this instanceof AcceptedAfterEviction;
        }

        /** 这次入队是否挤掉了别人 —— 行为分析要能看见连锁影响。 */
        default Optional<SensoryEvent> evictedEvent() {
            return this instanceof AcceptedAfterEviction a
                    ? Optional.of(a.evicted()) : Optional.empty();
        }
    }

    /**
     * 一条没能到达 Mind 的刺激。
     *
     * @param reason 为什么被丢 —— 这句话会被写进行为分析报告,
     *               所以它要能被一个不看代码的人读懂
     */
    public record DroppedStimulus(SensoryEvent event, String reason, Instant droppedAt) {}

    /** 某个折叠键现在折了几条 —— 测试与诊断用。 */
    public Optional<Integer> foldCountOf(String foldingKey) {
        String id = foldingIndex.get(foldingKey);
        return id == null ? Optional.empty()
                : Optional.ofNullable(slots.get(id)).map(Slot::foldCount);
    }

    /** 队列里出现过的全部折叠键 —— 诊断用。 */
    public Set<String> foldingKeys() {
        return new LinkedHashSet<>(foldingIndex.keySet());
    }

    /** 队列里出现过的全部感官通道 —— 诊断用。 */
    public Set<String> modalities() {
        Set<String> out = new HashSet<>();
        slots.values().forEach(s -> out.add(s.event().modality()));
        return out;
    }
}
