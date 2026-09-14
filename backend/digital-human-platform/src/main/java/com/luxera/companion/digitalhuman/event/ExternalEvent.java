package com.luxera.companion.digitalhuman.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * V10 §9.1 ExternalEvent: 进入 Digital Human 世界的唯一事件形态。
 *
 * 关键约束:
 * - 事件携带 eventId(幂等去重键) 与 correlationId(因果追踪);
 * - 事件 payload 只描述"外部世界发生了什么", 不含 Agent 内部概念;
 * - 同一个事件不能绕过事件链直接进入认知(V10 §1.2 因果链)。
 */
public record ExternalEvent(
        String eventId,
        String personId,
        ExternalEventType type,
        Instant occurredAt,
        Map<String, Object> payload,
        String correlationId
) {

    public static ExternalEvent of(String personId, ExternalEventType type, Map<String, Object> payload) {
        return new ExternalEvent(UUID.randomUUID().toString(), personId, type,
                Instant.now(), payload == null ? Map.of() : payload, null);
    }

    public static ExternalEvent of(String eventId, String personId, ExternalEventType type,
                                   Instant occurredAt, Map<String, Object> payload, String correlationId) {
        return new ExternalEvent(eventId, personId, type, occurredAt, payload, correlationId);
    }

    /** 确定性事件 id(用于幂等场景: 同源事件重放时 id 稳定) */
    public static ExternalEvent withDeterministicId(String personId, ExternalEventType type,
                                                    String sourceKey, Map<String, Object> payload) {
        String eventId = "ext-" + type.name().toLowerCase() + "-" + sanitize(sourceKey);
        return new ExternalEvent(eventId, personId, type, Instant.now(), payload, null);
    }

    private static String sanitize(String s) {
        if (s == null) return UUID.randomUUID().toString();
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }

    /** 浅拷贝并追加 payload 字段(链中节点传递上下文用) */
    public ExternalEvent withPayload(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.put(key, value);
        return new ExternalEvent(eventId, personId, type, occurredAt, merged, correlationId);
    }

    /** 标记该事件的因果来源 */
    public ExternalEvent withCorrelation(String newCorrelationId) {
        return new ExternalEvent(eventId, personId, type, occurredAt, payload, newCorrelationId);
    }
}
