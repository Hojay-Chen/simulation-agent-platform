package com.luxera.companion.boundary.event;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §5.3 —— <b>A 类事件的归宿: 一本账, 不是一个队列</b>。
 *
 * <h2>为什么它不是队列 —— 用一次具体的失败说明</h2>
 * 假设外面 3 度, 环境每 10 分钟刷新一次。若把"降温"塞进队列:
 * <pre>
 *   12:00  入队 降温(-0.02)   → 取出 → warmth 0.90 → 0.88
 *   12:10  入队 降温(-0.02)   → 取出 → warmth 0.88 → 0.86   ← 这只是"又刷新了一次", 不是又冷了一倍
 *   12:20  ...
 * </pre>
 * 问题不在数值, 在<b>语义</b>: 队列把"外面一直是 3 度"这件<b>持续成立的事实</b>, 降解成了
 * "每 10 分钟发生一次降温<b>事件</b>"。于是:
 * <ul>
 *   <li><b>她冷得多快取决于刷新频率</b> —— 把刷新从 10 分钟改成 1 分钟, 她就冷 10 倍快。
 *       这是一个纯粹的、荒谬的实现细节泄漏到了仿真语义里。</li>
 *   <li><b>她不会"一直冷"</b> —— 两次刷新之间队列是空的, 如果这期间她去看了保暖值,
 *       看到的是一条已经结清的账。而现实里那 10 分钟她一直在冷。</li>
 * </ul>
 *
 * <p>账本的语义是: <b>"外面 3 度"这条影响从 12:00 起一直挂在账上, 每 tick 结算一次,
 * 直到它过期或被替换</b>。于是她冷得多快取决于<b>结算频率</b>(一个我们可以定义的量),
 * 而"外面 3 度"这件事的强度不随时间重复累加。
 *
 * <h2>结算模型</h2>
 * <pre>
 *   净效应(channel) = Σ 该 channel 上所有未过期影响的 magnitude
 *
 *   每 tick:
 *     1. 清掉已过期的账目(expiresAt 已过 / 入账时刻 + duration 已过)
 *     2. 对每个 channel 求和
 *     3. 交给 Body 把它应用到 PhysiologicalState 上
 * </pre>
 *
 * <p>求和而<b>不是</b>相乘或取最大: 因为"穿羽绒服(+0.85)"和"外面 3 度(-0.02)"在
 * 保暖这件事上确实是可加的 —— 衣服挡住多少、天气拿走多少, 是两个独立的通量。
 * 相乘会让"不穿衣服时的保暖值是 0"导致所有乘法归零, 取最大则会让羽绒服的效果
 * 完全淹没天气。加法是唯一在这三种情形下都给出合理结果的运算。
 *
 * <h2>替换与撤销: {@link StateEffectEvent#cancellationKey()}</h2>
 * <pre>
 *   12:00  穿 T恤   key=body.thermal  +0.20
 *   12:05  穿羽绒服  key=body.thermal  +0.85   → T恤那条被挤掉, 不是叠加
 *   （她不会因为"先后穿过两件"而得到 +1.05 的保暖值 —— 那不叫穿衣服, 那叫堆衣服）
 * </pre>
 * <b>被替换掉的那条不是被删除, 而是被标记为失效并留在历史里</b> ——
 * "她 12:05 换了件厚的"和"她从没穿过 T恤"是两件事, 行为分析要能分开它们。
 *
 * <h2>线程模型</h2>
 * 刻意<b>不加锁</b>。理由: 账本只在仿真 tick 的单线程上下文里被读写 ——
 * 加锁会给人"它能在多线程下用"的错觉, 而一个真的被两个 tick 并发结算的账本,
 * 即使每个方法都加锁也仍然是错的(两次结算之间的读数不一致)。
 * 这类问题应当在<b>调度层</b>解决(同一 agent 的 tick 串行), 不是在账本内部糊一层锁。
 * 如果将来真需要并发, 正确的做法是整个 {@code HumanActor} 单线程化, 而不是给账本加锁。
 */
@Slf4j
public class ContinuousEffectLedger {

    /**
     * 一条入账的影响。
     *
     * <p>{@code sequence} 是同一时刻入账时的稳定定序 —— {@link Instant} 的精度
     * 足以区分真实事件, 但测试里同一纳秒入账两条是常事, 而没有稳定定序会让
     * "谁替换了谁"变得不确定, 那是一个只在 CI 上偶发的不稳定测试。
     */
    private record Entry(
            String entryId,
            StateEffectEvent event,
            Instant bookedAt,
            Instant expiresAt,
            long sequence,
            boolean superseded) {

        boolean isExpired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        Entry supersededBy(StateEffectEvent replacement, Instant at, long seq) {
            return new Entry(entryId, event, bookedAt, expiresAt, sequence, true);
        }
    }

    /** 同一 cancellationKey 上, 谁是"当前生效"的那一条。 */
    private final Map<String /* channel */, Map<String /* cancellationKey */, String /* entryId */>>
            activeByKey = new LinkedHashMap<>();

    /** 全部账目, 按 entryId 索引, <b>插入顺序</b>保持 —— 遍历顺序稳定, 便于复现。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    // ─────────────────────────── 入账 ───────────────────────────

    /**
     * 记一笔。返回这条账目的 id。
     *
     * <h3>入账时刻的确定</h3>
     * 用 {@code bookedAt} 参数而不是 {@code Instant.now()} —— 仿真时钟可以跑在
     * 加速或减速的时间轴上, 账本必须跟着那个时钟走。让账本自己读墙上时钟,
     * 会让"把仿真加速 60 倍"变成一个改变她会不会觉得冷的操作。
     *
     * <h3>替换的判定范围</h3>
     * 替换只在<b>同一 channel + 同一 cancellationKey</b> 内发生。两条影响即使
     * 有相同的 key, 只要通道不同就各算各的 —— "胸口暖了"和"脚还是冷"能同时成立。
     */
    public String book(StateEffectEvent event, Instant bookedAt) {
        Objects.requireNonNull(event, "要入账的持续影响事件不能为空");
        Objects.requireNonNull(bookedAt, "入账时刻不能为空 —— 账本不读墙上时钟");

        Instant expiresAt = resolveExpiry(event, bookedAt);
        long seq = sequence.incrementAndGet();
        String entryId = "eff-" + seq;

        String key = event.cancellationKey();
        if (key != null && !key.isBlank()) {
            String previous = activeByKey
                    .getOrDefault(event.effectChannel(), Map.of())
                    .get(key);
            if (previous != null) {
                Entry old = entries.get(previous);
                if (old != null && !old.isExpired(bookedAt)) {
                    // 标记失效而不是删除 —— 见类注释"替换与撤销"
                    entries.put(previous, old.supersededBy(event, bookedAt, seq));
                    log.debug("[EffectLedger] {} 上的 {} 被 {} 替换", event.effectChannel(), key,
                            event.typeId());
                }
            }
            activeByKey.computeIfAbsent(event.effectChannel(), c -> new LinkedHashMap<>())
                    .put(key, entryId);
        }

        entries.put(entryId, new Entry(entryId, event, bookedAt, expiresAt, seq, false));
        log.debug("[EffectLedger] 入账 {} ({})", entryId, event.effectDescribe());
        return entryId;
    }

    /**
     * 显式撤销一个 key 上的影响 —— 产生一条 {@code magnitude = 0} 的同 key 影响。
     *
     * <h3>为什么"撤销"也是入账, 而不是删除</h3>
     * 因为"她脱掉了羽绒服"必须能被看见。<b>删除</b>会让历史里这件事消失,
     * 于是"她 12:30 之后为什么开始觉得冷"这个问题在账本里找不到答案 ——
     * 只能看到保暖值在某个时刻掉了, 看不到原因。
     *
     * <p>所以撤销是一次<b>状态转移</b>, 记为新账目; 旧的那条留在账上, 标记为已失效。
     * 这也是用户那句"想立马执行就把其触发事件调成现在"在本层的同构做法:
     * 不是回到过去改数据, 而是在现在插入一条新的。
     */
    public String cancel(String channel, String cancellationKey, Instant at) {
        Objects.requireNonNull(channel, "通道不能为空");
        if (cancellationKey == null || cancellationKey.isBlank()) {
            throw new IllegalArgumentException("撤销必须指定 cancellationKey —— 否则无法知道要撤销什么");
        }
        String previous = activeByKey.getOrDefault(channel, Map.of()).get(cancellationKey);
        if (previous == null) {
            log.debug("[EffectLedger] 撤销 {}:{} 时账上没有这条 —— 忽略", channel, cancellationKey);
            return null;
        }
        // 复用上一条的类型标识, 让"谁撤销了谁"在历史里可追溯
        StateEffectEvent origin = entries.get(previous).event();
        return book(new Cancellation(origin, channel, cancellationKey, at), at);
    }

    // ─────────────────────────── 结算 ───────────────────────────

    /**
     * 结算: 清过期账目, 然后按通道求和。
     *
     * <p>返回的是一个<b>快照</b>, 不是活视图 —— 调用方拿到它之后账本可以继续变,
     * 而 {@code Body} 基于这个快照做一次生理状态推进。若返回活视图,
     * 一次结算的中途入账会让它拿到半个世界。
     */
    public Settlement settle(Instant now) {
        Objects.requireNonNull(now, "结算时刻不能为空");

        List<Entry> expired = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.superseded() && e.isExpired(now)) {
                expired.add(e);
            }
        }
        for (Entry e : expired) {
            entries.put(e.entryId(), new Entry(e.entryId(), e.event(), e.bookedAt(),
                    e.expiresAt(), e.sequence(), true));
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, List<StateEffectEvent>> contributors = new LinkedHashMap<>();
        for (Entry e : entries.values()) {
            if (e.superseded() || e.isExpired(now)) {
                continue;
            }
            String channel = e.event().effectChannel();
            totals.merge(channel, e.event().magnitude(), Double::sum);
            contributors.computeIfAbsent(channel, c -> new ArrayList<>()).add(e.event());
        }

        return new Settlement(Map.copyOf(totals), deepCopy(contributors), now, expired.size());
    }

    /** 某个通道当前的净效应。没有账目时返回 {@code 0.0}(不是空 —— 零效应是确定的事实)。 */
    public double netEffect(String channel, Instant now) {
        return settle(now).totals().getOrDefault(channel, 0.0);
    }

    // ─────────────────────────── 查询 ───────────────────────────

    /** 当前未失效、未过期的账目条数。 */
    public int activeCount(Instant now) {
        return (int) entries.values().stream()
                .filter(e -> !e.superseded() && !e.isExpired(now))
                .count();
    }

    /**
     * 账目历史 —— <b>含已失效的</b>, 按入账顺序。
     *
     * <p>这是给行为分析/重放用的。刻意与 {@link #activeCount} 分开: 一个只想
     * 渲染当前状态的前端不该被历史淹没, 而一个想回答"她为什么冷"的分析必须能看到历史。
     */
    public List<EffectRecord> history() {
        return entries.values().stream()
                .sorted(Comparator.comparingLong(Entry::sequence))
                .map(e -> new EffectRecord(e.entryId(), e.event(), e.bookedAt(), e.expiresAt(),
                        e.superseded()))
                .toList();
    }

    /** 账本上现在有哪些通道。用于诊断"她身上到底挂着几条影响"。 */
    public java.util.Set<String> channels(Instant now) {
        return settle(now).totals().keySet();
    }

    /** 全部清空 —— <b>只给测试与"重新初始化"用</b>。生产路径上没有这个操作。 */
    public void clear() {
        entries.clear();
        activeByKey.clear();
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private static Instant resolveExpiry(StateEffectEvent event, Instant bookedAt) {
        // 绝对到期优先: 它表达的是"已知这件事什么时候结束"(雨停), 比相对时长更具体
        if (event.expiresAt() != null) {
            return event.expiresAt();
        }
        return event.duration() == null ? null : bookedAt.plus(event.duration());
    }

    private static Map<String, List<StateEffectEvent>> deepCopy(
            Map<String, List<StateEffectEvent>> src) {
        Map<String, List<StateEffectEvent>> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Map.copyOf(out);
    }

    // ─────────────────────────── 输出类型 ───────────────────────────

    /**
     * 一次结算的结果。
     *
     * @param totals       每个通道的净效应
     * @param contributors 每个通道上, 是谁贡献了这些量 —— <b>解释性</b>。
     *                     没有它, "保暖值为什么是 -0.02" 就答不出来,
     *                     而这是一个调试仿真系统时最常问的问题。
     * @param settledAt    这次结算用的时刻
     * @param expiredCount 这次结算清掉了几条过期账目
     */
    public record Settlement(Map<String, Double> totals,
                             Map<String, List<StateEffectEvent>> contributors,
                             Instant settledAt,
                             int expiredCount) {

        public Settlement {
            totals = totals == null ? Map.of() : Map.copyOf(totals);
            contributors = contributors == null ? Map.of() : Map.copyOf(contributors);
        }

        public boolean isEmpty() {
            return totals.isEmpty();
        }

        /** 某个通道是谁造成的。空表示这个通道当前没有账目。 */
        public List<StateEffectEvent> why(String channel) {
            return contributors.getOrDefault(channel, List.of());
        }
    }

    /**
     * 历史里的一条账目。
     *
     * @param superseded 是否已被同 key 的新影响取代(被取代的仍在历史里, 见类注释)
     */
    public record EffectRecord(String entryId, StateEffectEvent event, Instant bookedAt,
                               Instant expiresAt, boolean superseded) {

        public boolean active(Instant now) {
            return !superseded && (expiresAt == null || now.isBefore(expiresAt));
        }
    }

    /**
     * "撤销"这条影响本身。
     *
     * <p>它是一个真正的 {@link StateEffectEvent}({@code magnitude = 0}),
     * 因此会走完整的入账流程、被记进历史、能被重放 —— 这与"删掉一条记录"的区别
     * 正是本类要保住的东西。
     */
    record Cancellation(StateEffectEvent origin, String channel, String cancellationKey,
                        Instant at) implements StateEffectEvent {

        static final EventTypeId TYPE = EventTypeId.of("system", "effect-cancelled");

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public Instant occurredAt() {
            return at;
        }

        @Override
        public String sourceObjectId() {
            return null;
        }

        @Override
        public double magnitude() {
            return 0.0;
        }

        @Override
        public String effectChannel() {
            return channel;
        }

        @Override
        public String cancellationKey() {
            return cancellationKey;
        }

        @Override
        public String describe() {
            return "撤销 " + channel + ":" + cancellationKey + " (原 " + origin.typeId() + ")";
        }
    }

    /** 供诊断面板用: 一行摘要。 */
    public String describe(Instant now) {
        Settlement s = settle(now);
        if (s.isEmpty()) {
            return "账本为空";
        }
        StringBuilder sb = new StringBuilder("账本(").append(s.totals().size()).append(" 通道): ");
        s.totals().forEach((ch, v) -> sb.append(ch).append('=').append(String.format("%.3f", v))
                .append(' '));
        return sb.toString().trim();
    }

    /** 便捷: 空账本。 */
    public static ContinuousEffectLedger empty() {
        return new ContinuousEffectLedger();
    }

    /** 找一条账目 —— 只给测试与诊断用。 */
    Optional<StateEffectEvent> activeOn(String channel, String cancellationKey) {
        String id = activeByKey.getOrDefault(channel, Map.of()).get(cancellationKey);
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id)).map(Entry::event);
    }
}
