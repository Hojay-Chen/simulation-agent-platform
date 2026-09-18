package com.luxera.companion.human.mind.memory;

import com.luxera.companion.human.mind.relationship.PersonId;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.4.1 —— <b>{@link MemoryStore} 的内存实现</b>。
 *
 * <h2>它的定位: 一个"够用到持久化落地"的实现, 不是一个玩具</h2>
 * 它被写出来的理由不是"先凑合一下", 而是<b>这五种检索的语义必须先在一个
 * 没有数据库参与的地方被确定下来</b>。一旦先写了 Postgres 版本, 表结构与索引
 * 会反过来定义语义(比如"按人检索"会被实现成一次 JOIN, 于是"一个人可能有多个
 * 标识"这类问题会在 SQL 里被糊过去), 而语义本该由设计决定。
 *
 * <p>所以它是<b>参考实现</b>: 后来的持久化实现只要让同一批测试变绿, 就算对。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不做衰减与遗忘</b>。{@link MemoryRecord#importance()} 是一个<b>入账时</b>的性质,
 *       不是随时间的函数。遗忘策略应当是一个独立的、可调参的组件 ——
 *       放在这里会让"她记不清了"成为读取的副作用(读一次老一点),
 *       而那会让同一份历史读两次得到不同结果;</li>
 *   <li><b>不保证线程安全</b>。一个 Human 的事件严格串行(§3.1 的 actor 模型),
 *       所以在记忆上加锁是把一个不存在的并发问题<被>解决</被>掉 —— 代价是每一次
 *       检索都要抢锁, 而收益是零。将来真的并行化了, 该改的是整个 Human 的模型,
 *       而不是在这里补一个 {@code synchronized};</li>
 *   <li><b>不排序 content</b>。所有检索只看线索、人物、时刻、重要度 ——
 *       见 {@link MemoryRecord} 的"不保存正文"。</li>
 * </ul>
 */
@Slf4j
public final class InMemoryMemoryStore implements MemoryStore {

    private final List<MemoryRecord> records = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final Map<String, List<SemanticMemory.FactRevision>> factRevisions = new LinkedHashMap<>();
    private final Map<String, List<SelfMemory.SelfRevision>> selfRevisions = new LinkedHashMap<>();

    public InMemoryMemoryStore() {
    }

    // ─────────────────────────── 统一写入 ───────────────────────────

    @Override
    public void store(MemoryRecord record) {
        Objects.requireNonNull(record, "要写入的记忆不能为空");
        record.requireSubject();
        records.add(record);
        log.debug("[Memory] 记下一条 {}: {}", record.kind().label(), record.content());
    }

    // ─────────────────────────── 情景记忆 ───────────────────────────

    @Override
    public void remember(MemoryRecord episode) {
        requireKind(episode, MemoryKind.EPISODIC, "remember");
        store(episode);
    }

    @Override
    public List<MemoryRecord> between(Instant from, Instant to) {
        requireRange(from, to);
        return slice(MemoryKind.EPISODIC, from, to);
    }

    @Override
    public List<MemoryRecord> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return records.stream()
                .filter(r -> r.kind() == MemoryKind.EPISODIC)
                .sorted(Comparator.comparing(MemoryRecord::occurredAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public int episodeCount() {
        return (int) records.stream().filter(r -> r.kind() == MemoryKind.EPISODIC).count();
    }

    // ─────────────────────────── 语义记忆 ───────────────────────────

    @Override
    public Optional<String> assertFact(String cue, String value, MemoryRecord source) {
        if (cue == null || cue.isBlank()) {
            throw new IllegalArgumentException("事实必须有线索 —— 没有线索的事实检索不出来");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "事实的值不能为空 —— 要表达'这个线索不再成立'请用一条新的值, "
                            + "而不是把它抹掉: 抹掉之后「她什么时候不再知道了」就没有答案");
        }
        Instant at = source == null ? Instant.EPOCH : source.recordedAt();
        String previous = facts.put(cue, value);
        factRevisions.computeIfAbsent(cue, k -> new ArrayList<>())
                .add(new SemanticMemory.FactRevision(cue, previous, value, at));
        if (source != null) {
            store(source);
        }
        if (previous != null && !previous.equals(value)) {
            log.info("[Memory] 事实修正 {}: {} → {}", cue, previous, value);
        }
        return Optional.ofNullable(previous);
    }

    @Override
    public Optional<String> fact(String cue) {
        return cue == null ? Optional.empty() : Optional.ofNullable(facts.get(cue));
    }

    @Override
    public List<MemoryRecord> search(Set<String> cues, int limit) {
        return recall(cues, limit).stream()
                .filter(r -> r.kind() == MemoryKind.SEMANTIC)
                .toList();
    }

    @Override
    public List<SemanticMemory.FactRevision> revisions(String cue) {
        return List.copyOf(factRevisions.getOrDefault(cue, List.of()));
    }

    @Override
    public Set<String> cues() {
        return Set.copyOf(facts.keySet());
    }

    // ─────────────────────────── 共享记忆 ───────────────────────────

    @Override
    public void share(MemoryRecord record) {
        requireKind(record, MemoryKind.SHARED, "share");
        store(record);
    }

    @Override
    public List<MemoryRecord> with(PersonId person, int limit) {
        List<MemoryRecord> all = with(person, Instant.EPOCH, Instant.MAX);
        return limit <= 0 || all.size() <= limit ? all : all.subList(0, limit);
    }

    @Override
    public List<MemoryRecord> with(PersonId person, Instant from, Instant to) {
        Objects.requireNonNull(person, "必须指明是哪个人");
        requireRange(from, to);
        return slice(MemoryKind.SHARED, from, to).stream()
                .filter(r -> person.value().equals(r.subject()))
                .toList();
    }

    @Override
    public int sharedCount(PersonId person) {
        Objects.requireNonNull(person, "必须指明是哪个人");
        return (int) records.stream()
                .filter(r -> r.kind() == MemoryKind.SHARED && person.value().equals(r.subject()))
                .count();
    }

    @Override
    public Optional<MemoryRecord> lastWith(PersonId person) {
        List<MemoryRecord> all = with(person, 0);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    // ─────────────────────────── 关系记忆 ───────────────────────────

    @Override
    public void record(MemoryRecord change) {
        requireKind(change, MemoryKind.RELATIONSHIP, "record");
        store(change);
    }

    @Override
    public List<MemoryRecord> historyOf(PersonId person) {
        Objects.requireNonNull(person, "必须指明是哪个人");
        return records.stream()
                .filter(r -> r.kind() == MemoryKind.RELATIONSHIP
                        && person.value().equals(r.subject()))
                .sorted(Comparator.comparing(MemoryRecord::occurredAt))
                .toList();
    }

    @Override
    public List<MemoryRecord> historyOf(PersonId person, Instant from, Instant to) {
        requireRange(from, to);
        return historyOf(person).stream()
                .filter(r -> !r.occurredAt().isBefore(from) && !r.occurredAt().isAfter(to))
                .toList();
    }

    @Override
    public Optional<MemoryRecord> lastChangeOf(PersonId person) {
        List<MemoryRecord> history = historyOf(person);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }

    @Override
    public Optional<String> impressionNote(PersonId person) {
        return lastChangeOf(person).map(MemoryRecord::content);
    }

    // ─────────────────────────── 自我记忆 ───────────────────────────

    @Override
    public void conclude(MemoryRecord selfKnowledge) {
        requireKind(selfKnowledge, MemoryKind.SELF, "conclude");
        String topic = selfKnowledge.subject();
        String previous = facts.get(selfTopicKey(topic));
        facts.put(selfTopicKey(topic), selfKnowledge.content());
        selfRevisions.computeIfAbsent(topic, k -> new ArrayList<>())
                .add(new SelfMemory.SelfRevision(topic, previous, selfKnowledge.content(),
                        selfKnowledge.recordedAt()));
        store(selfKnowledge);
    }

    @Override
    public Optional<MemoryRecord> about(String topic) {
        return records.stream()
                .filter(r -> r.kind() == MemoryKind.SELF && topic != null && topic.equals(r.subject()))
                .max(Comparator.comparing(MemoryRecord::recordedAt));
    }

    @Override
    public Set<String> topics() {
        Set<String> out = new LinkedHashSet<>();
        for (MemoryRecord r : records) {
            if (r.kind() == MemoryKind.SELF) {
                out.add(r.subject());
            }
        }
        return Set.copyOf(out);
    }

    @Override
    public List<MemoryRecord> mostSalient(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return records.stream()
                .filter(r -> r.kind() == MemoryKind.SELF)
                .sorted(Comparator.comparingDouble(MemoryRecord::importance).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<SelfMemory.SelfRevision> selfRevisions(String topic) {
        return List.copyOf(selfRevisions.getOrDefault(topic, List.of()));
    }

    /** 自我认知与语义事实共用一张键值表, 但不共用键空间 —— 见下。 */
    private static String selfTopicKey(String topic) {
        return "self::" + topic;
    }

    // ─────────────────────────── 通用检索 ───────────────────────────

    @Override
    public List<MemoryRecord> recall(Set<String> cues, int limit) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        List<MemoryRecord> hits = new ArrayList<>();
        for (MemoryRecord r : records) {
            if (r.cueHits(cues) > 0) {
                hits.add(r);
            }
        }
        hits.sort(Comparator
                .comparingInt((MemoryRecord r) -> -r.cueHits(cues))
                .thenComparing(Comparator.comparingDouble(MemoryRecord::importance).reversed())
                .thenComparing(Comparator.comparing(MemoryRecord::occurredAt).reversed()));
        return limit <= 0 || hits.size() <= limit ? List.copyOf(hits) : List.copyOf(hits.subList(0, limit));
    }

    @Override
    public List<MemoryRecord> slice(MemoryKind kind, Instant from, Instant to) {
        Objects.requireNonNull(kind, "必须指明是哪一种记忆");
        requireRange(from, to);
        return records.stream()
                .filter(r -> r.kind() == kind)
                .filter(r -> !r.occurredAt().isBefore(from) && !r.occurredAt().isAfter(to))
                .sorted(Comparator.comparing(MemoryRecord::occurredAt))
                .toList();
    }

    @Override
    public List<MemoryRecord> all() {
        return List.copyOf(records);
    }

    @Override
    public int size() {
        return records.size();
    }

    @Override
    public void clear() {
        records.clear();
        facts.clear();
        factRevisions.clear();
        selfRevisions.clear();
    }

    /** 按种类统计 —— 诊断面板与"她最近在记事吗"这类检查用。 */
    public Map<MemoryKind, Integer> countByKind() {
        Map<MemoryKind, Integer> counts = new LinkedHashMap<>();
        for (MemoryKind kind : MemoryKind.values()) {
            counts.put(kind, 0);
        }
        for (MemoryRecord r : records) {
            counts.merge(r.kind(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("InMemoryMemoryStore[共 ").append(records.size()).append(" 条: ");
        countByKind().forEach((k, v) -> sb.append(k.label()).append(' ').append(v).append("  "));
        sb.append("| 事实线索 ").append(facts.size()).append(" 条]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 内部检查 ───────────────────────────

    private static void requireKind(MemoryRecord record, MemoryKind expected, String method) {
        Objects.requireNonNull(record, "要写入的记忆不能为空");
        if (record.kind() != expected) {
            throw new IllegalArgumentException(
                    method + "() 只接受" + expected.label() + ", 收到 " + record.kind().label()
                            + " —— 用错方法的后果不是报错, 而是这条记忆去了一个检索不到它的地方, "
                            + "表现是「她明明记得却不记得」");
        }
    }

    private static void requireRange(Instant from, Instant to) {
        Objects.requireNonNull(from, "时间段起点不能为空");
        Objects.requireNonNull(to, "时间段终点不能为空");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("时间段终点 " + to + " 早于起点 " + from
                    + " —— 一个反向的区间会静默地返回空, 而那看起来像「她那段时间什么都没经历」");
        }
    }
}
