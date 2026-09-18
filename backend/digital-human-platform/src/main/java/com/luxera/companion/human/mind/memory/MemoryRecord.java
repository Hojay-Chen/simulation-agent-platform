package com.luxera.companion.human.mind.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.1 —— <b>记忆里的一条</b>。五种记忆共用它。
 *
 * <h2>为什么五种记忆共用一个记录类型, 而不是各写一个</h2>
 * 因为它们要能<b>一起被检索</b>。"她那天为什么心情不好"这个问题的答案很可能
 * 同时落在情景记忆(发生了什么)、关系记忆(那个人是谁)与自我记忆(她一贯受不了什么)里。
 * 五种记录类型意味着五种排序规则、五种时间语义、五次合并 ——
 * 而其中至少三次会在实现时分叉。
 *
 * <p>共用不等于混同: 区分它们的是 {@link #kind()} 与
 * {@link #subject()} / {@link #cues()}, 而这两组字段<b>由 kind 决定该怎么填</b>
 * (见 {@link MemoryKind} 的 retrieval)。这个约束在 {@link #requireSubject()}
 * 里被写成了一条可执行的检查。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不是实体</b>。没有 id 生成策略、没有版本、没有 {@code @Entity}。
 *       它是值, 持久化是 {@link MemoryStore} 的事(见那里的"端口"说明)。</li>
 *   <li><b>不保存正文</b>。<b>这一条是硬性的</b>: 聊天正文只能在
 *       {@code Mind} 的工作记忆里出现, 出现在这里就等于它被永久留下,
 *       而"她不该在读到之前就知道内容"这条约束会被一条几周前的记忆绕过。
 *       要记住"那次对话", 记的是<b>她的理解</b>("他说他要换工作了"), 不是原文。</li>
 *   <li><b>不做衰减</b>。遗忘是 {@link MemoryStore} 的策略, 不是记录自己的行为 ——
 *       一条记录不知道自己该不该被忘掉, 就像一个人不知道自己的记性好不好。</li>
 * </ul>
 */
public record MemoryRecord(
        String id,
        MemoryKind kind,
        String subject,
        String content,
        Set<String> cues,
        double importance,
        Instant occurredAt,
        Instant recordedAt,
        Map<String, Object> attributes) {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public MemoryRecord {
        Objects.requireNonNull(id, "记忆必须有身份 —— 没有它, '忘掉那一条'无从说起");
        Objects.requireNonNull(kind, "记忆必须属于某一种 —— 见 MemoryKind: 它决定怎么检索");
        Objects.requireNonNull(content, "记忆必须有内容 —— 空的记忆会在检索结果里占一个位置");
        if (content.isBlank()) {
            throw new IllegalArgumentException(
                    "记忆内容不能是空白 —— 一条空记忆会让'她记得这件事'与'她什么都不知道'看起来一样");
        }
        subject = subject == null ? "" : subject;
        cues = cues == null ? Set.of() : Set.copyOf(cues);
        if (importance < 0.0 || importance > 1.0) {
            throw new IllegalArgumentException(
                    "重要程度必须归一化到 [0, 1], 收到 " + importance);
        }
        Objects.requireNonNull(occurredAt, "记忆必须带'那件事发生'的时刻 —— 行为分析的时间线靠它");
        Objects.requireNonNull(recordedAt, "记忆必须带'她记下它'的时刻 —— 它与发生时刻不是一回事, "
                + "而两者之差正是'她过了多久才想明白'");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * 记下一条 —— id 与记下时刻由调用方给。
     *
     * <p>时刻是参数而不是 {@code Instant.now()}: 这是 {@code human/} 的硬规则
     * (见 {@code V22BoundaryArchitectureTest}), 也是这个平台能回放的前提。
     */
    public static MemoryRecord of(MemoryKind kind, String subject, String content,
                                  Set<String> cues, double importance,
                                  Instant occurredAt, Instant recordedAt) {
        return new MemoryRecord("mem-" + SEQUENCE.incrementAndGet(), kind, subject, content,
                cues, importance, occurredAt, recordedAt, Map.of());
    }

    /** 情景记忆的快捷构造 —— "那时候发生了什么"。 */
    public static MemoryRecord episode(String content, Instant occurredAt, Instant recordedAt,
                                       double importance) {
        return of(MemoryKind.EPISODIC, "", content, Set.of(), importance, occurredAt, recordedAt);
    }

    /** 关于某个人的记忆的快捷构造 —— 共享/关系记忆都走它。 */
    public static MemoryRecord aboutPerson(MemoryKind kind, String personId, String content,
                                           double importance, Instant occurredAt,
                                           Instant recordedAt) {
        if (kind != MemoryKind.SHARED && kind != MemoryKind.RELATIONSHIP) {
            throw new IllegalArgumentException(
                    "按人检索的只有共享记忆与关系记忆(" + MemoryKind.SHARED.label() + " / "
                            + MemoryKind.RELATIONSHIP.label() + "), 收到 " + kind.label()
                            + " —— 其余几种请用 of(...) 显式给出 subject");
        }
        if (personId == null || personId.isBlank()) {
            throw new IllegalArgumentException("按人检索的记忆必须给出人 —— 否则它检索不出来");
        }
        return of(kind, personId, content, Set.of(), importance, occurredAt, recordedAt);
    }

    /** 这条记忆挂在谁身上。只有共享/关系记忆有值。 */
    public Optional<String> personSubject() {
        if (kind != MemoryKind.SHARED && kind != MemoryKind.RELATIONSHIP) {
            return Optional.empty();
        }
        return subject.isBlank() ? Optional.empty() : Optional.of(subject);
    }

    /**
     * 这一种记忆<b>必须</b>有 subject 吗?
     *
     * <p>写成方法而不是在构造器里检查, 是因为"必须"只对其中两种成立 ——
     * 情景记忆的时间就是它的主体, 语义记忆的线索就是它的主体。
     * 把它们也强行要求一个 subject, 只会逼调用方填一个没意义的字符串,
     * 而没意义的字符串会污染按 subject 的检索。
     */
    public boolean requiresSubject() {
        return kind == MemoryKind.SHARED || kind == MemoryKind.RELATIONSHIP
                || kind == MemoryKind.SELF;
    }

    /** 该有 subject 却没有时抛异常 —— 由 {@link MemoryStore} 的实现调用。 */
    public void requireSubject() {
        if (requiresSubject() && subject.isBlank()) {
            throw new IllegalArgumentException(
                    kind.label() + "必须说明它关于谁/关于什么(见 MemoryKind 的检索入口), "
                            + "而这条记录的 subject 是空的 —— 它一旦落库, 就再也检索不出来了");
        }
    }

    public double importance() {
        return importance;
    }

    /** 带上额外属性 —— 保持不可变。 */
    public MemoryRecord withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new MemoryRecord(id, kind, subject, content, cues, importance,
                occurredAt, recordedAt, merged);
    }

    /** 命中线索数 —— 供检索排序用。它<b>不</b>读 content, 只看线索集合。 */
    public int cueHits(Set<String> query) {
        if (query == null || query.isEmpty() || cues.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String q : query) {
            if (cues.contains(q)) {
                hits++;
            }
        }
        return hits;
    }

    public String describe() {
        return "[" + kind.label() + "] " + (subject.isEmpty() ? "" : subject + ": ") + content
                + " (重要度 " + Math.round(importance * 100) / 100.0
                + ", 发生于 " + occurredAt + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
