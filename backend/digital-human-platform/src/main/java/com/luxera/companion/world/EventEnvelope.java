package com.luxera.companion.world;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * V11 §5.2 —— 落进信箱的那一封信。<b>不可变, 且装不下聊天正文。</b>
 *
 * <h2>这一条是全书最要紧的边界</h2>
 * 设计文档 §2.2.2 指认了当前系统里最根子的一个错误: 消息正文被<b>过早读取</b>了。
 * 今天 {@code AgentRuntime.onChatMessageDelivered} 在"她是否注意到"这个问题被问出来
 * <b>之前</b>, 就已经 {@code chatWorld.messages(conversationId)} 把整个会话拉下来读了正文。
 * 一旦正文在手, 后面所有的"没注意到/在忙/已读不回"都只是<b>已经知道内容之后</b>找的借口 ——
 * 那不是注意力, 那是事后合理化。
 *
 * <p>所以本类把这条边界做成<b>结构性的</b>: {@link #references} 里出现正文类键名
 * 会在<b>构造时</b>抛 {@link IllegalArgumentException}。不是文档里写一句"请不要传正文",
 * 而是传不进去。文档会过期, 构造函数不会。
 *
 * <h2>它守的是形状, 不是保密</h2>
 * 说清楚这道闸不做什么: 它<b>不</b>阻止有人把正文塞进一个叫 {@code x} 的键里。
 * 它阻止的是"顺手把 {@code content} 带过来"这种<b>默认行为</b> —— 而那恰恰是
 * 一个代码库里唯一会真实发生的事。真正的强制不靠这里, 靠的是:
 * <ol>
 *   <li>投递路径上根本没有正文可拿(发送方只有 id 和元数据);</li>
 *   <li>{@code action/ReadMessagesAction} 是正文进入认知的唯一一道门。</li>
 * </ol>
 * 本类是那道门上的一块砖, 不是整扇门。
 *
 * <h2>为什么 references 是 Map 而不是强类型字段</h2>
 * 不同事件的指涉对象差别很大(消息要 {@code conversationId + messageIds}, 活动要
 * {@code activityId}, 关系要 {@code peerId}), 强类型会变成一堆只有一两个字段的 record,
 * 而它们的共同点恰恰是"这些是<b>坐标</b>, 不是内容"。用 Map 加白名单式的键名约束
 * 比二十个 record 更贴近这件事的本质。键名规范是 camelCase, 值只允许标量或标量列表。
 */
public record EventEnvelope(
        String eventId,
        String agentId,
        AgentEventType type,
        EventSource source,
        EventPriority priority,
        LocalDateTime occurredAt,
        Map<String, Object> references) {

    /**
     * 正文类键名 —— 归一化(去下划线、转小写)后比对, 因此 {@code sender_text}、
     * {@code SenderText}、{@code sendertext} 是同一个东西。
     *
     * <p>集合刻意<b>只收"装文字"的词</b>, 不收 {@code message} / {@code messages}:
     * 后者会被 {@code messageIds} 那样的正常键名误伤, 而一个会误报的守卫最后一定会
     * 被人加白名单绕过去, 那比没有守卫更糟。
     */
    private static final List<String> FORBIDDEN_REFERENCE_KEYS = List.of(
            "content", "text", "body", "preview", "snippet", "excerpt",
            "sendertext", "rawtext", "messagebody", "reply", "answer");

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件信封必须有 eventId —— 它是幂等键");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("事件信封必须有 agentId —— 它是投递对象");
        }
        Objects.requireNonNull(type, "事件信封必须有 type");
        source = source == null ? EventSource.SYSTEM : source;
        priority = priority == null ? EventPriority.NORMAL : priority;
        occurredAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
        references = checkReferences(references);
    }

    private static Map<String, Object> checkReferences(Map<String, Object> refs) {
        if (refs == null || refs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : refs.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("事件信封的 references 里有一个没有名字的键");
            }
            String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_REFERENCE_KEYS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "事件信封不许携带正文: 键 " + key + " 是内容类字段。"
                                + " 世界事件只带坐标(id / 计数 / 状态), 正文只能经 ReadMessagesAction 进入认知"
                                + " (V11 §2.2.2)");
            }
            copy.put(key, e.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 新事件: 自己生成 eventId。 */
    public static EventEnvelope of(String agentId, AgentEventType type, EventSource source,
                                   Map<String, Object> references) {
        return new EventEnvelope("env-" + UUID.randomUUID(), agentId, type, source,
                EventPriority.NORMAL, LocalDateTime.now(), references);
    }

    /**
     * <b>确定性 eventId</b> —— 幂等键的来源, 同一件事重复投递只会进信箱一次。
     *
     * <h2>为什么 id 里必须带 agentId</h2>
     * 这不是"更整齐", 而是一个会静默丢消息的坑。幂等是<b>全局</b>的
     * ({@code agent_inbox.event_id} 上有唯一约束, {@code AgentMailbox} 也用
     * {@code existsByEventId} 这个全局判断做短路), 所以只要两个 agent 用同一个
     * {@code sourceKey} 算出同一个 id, <b>后一个 agent 的信会被当成"已经收过了"直接丢掉</b> ——
     * 没有异常、没有日志, 收信人一辈子不知道有这件事。
     *
     * <p>而这个撞车是必然会发生的, 不是理论风险: 一次广播式的世界事件、或者
     * "今天九点的闹钟"这种给所有 agent 的定时唤醒, 每个 agent 拿到的 {@code sourceKey}
     * 天然一模一样。{@link com.luxera.companion.runtime.PersistentAgentRuntime#wake} 就是
     * 这个形状 —— 少了这个前缀, 它会在 Phase 5 里只叫醒一个 agent 而静默丢掉其余全部。
     *
     * <p>所以正确的幂等语义是"<b>同一个 agent 的同一个来源键</b>", 不是"同一个来源键"。
     *
     * <h2>与旧链的关系</h2>
     * 形状仍沿用 {@code ExternalEvent.withDeterministicId} 的约定(前缀 + 去噪键),
     * 但旧链写的是 {@code processed_event} 表, 与本表是两张不同的表、各自判各自的重复,
     * 因此不存在"两条链的 id 必须逐字相同"的约束。就本表而言, 跨 agent 撞车的代价
     * (静默丢信) 远大于 id 与旧表对齐的收益。
     */
    public static EventEnvelope withDeterministicId(String agentId, AgentEventType type,
                                                    EventSource source, String sourceKey,
                                                    Map<String, Object> references) {
        // event_id 列是 varchar(128), 而 agentId 与来源键都是外部传入的 —— 一并去噪并封顶,
        // 免得超长的输入把幂等键顶出列宽(那会变成一个 INSERT 失败, 不是丢消息)。
        // 预算: 4("env-") + 40 + 1 + 21(最长的 wire 名 USER_MESSAGE_NOTIFIED/DEFERRED) + 1 + 60 = 127
        String safeAgent = sanitize(agentId, 40);
        String safeKey = sanitize(sourceKey == null ? "none" : sourceKey, 60);
        String eventId = "env-" + safeAgent + "-" + type.wire().toLowerCase(Locale.ROOT) + "-" + safeKey;
        return new EventEnvelope(eventId, agentId, type, source, EventPriority.NORMAL,
                LocalDateTime.now(), references);
    }

    private static String sanitize(String raw, int max) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    public EventEnvelope withPriority(EventPriority newPriority) {
        return new EventEnvelope(eventId, agentId, type, source, newPriority, occurredAt, references);
    }

    public EventEnvelope withSource(EventSource newSource) {
        return new EventEnvelope(eventId, agentId, type, newSource, priority, occurredAt, references);
    }

    /** 追加一个指涉坐标。同名键覆盖 —— 走的是构造函数的检查, 所以追加不了正文。 */
    public EventEnvelope withReference(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(references);
        merged.put(key, value);
        return new EventEnvelope(eventId, agentId, type, source, priority, occurredAt, merged);
    }

    // ─────────────────────────── 读取 ───────────────────────────

    public Object get(String key) {
        return references.get(key);
    }

    public String str(String key) {
        Object v = references.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 取一个字符串列表(如 {@code messageIds})。类型不对或没有 → 空列表, 不抛。 */
    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        Object v = references.get(key);
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 这条事件是否属于意识阶梯上的某一级。 */
    public boolean onPerceptionLadder() {
        return type.isPerceptionLadder();
    }

    /** 日志/落库用的单行摘要。<b>只打坐标, 不打内容</b> —— 因为本来也没有内容。 */
    public String describe() {
        return type.wire() + "[" + source.wire() + "/" + priority.name() + "]@" + agentId
                + " " + references;
    }
}
