package com.luxera.companion.digitalhuman.reality;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * V10 §8.2 RealityEvent: 已经真实发生过的事件的唯一事实源。
 *
 * 对齐方案定义: eventId / personId / type / occurredAt / payload /
 * correlationId / causationId。事件不可修改 —— 事实变化写入新事件。
 */
public record RealityEvent(
        String eventId,
        String personId,
        RealityEventType type,
        Instant occurredAt,
        Map<String, Object> payload,
        String correlationId,
        String causationId
) {

    public static RealityEvent of(String personId, RealityEventType type, Map<String, Object> payload) {
        return new RealityEvent(UUID.randomUUID().toString(), personId, type,
                Instant.now(), payload == null ? Map.of() : payload, null, null);
    }

    public static RealityEvent of(String personId, RealityEventType type, Map<String, Object> payload,
                                  String correlationId, String causationId) {
        return new RealityEvent(UUID.randomUUID().toString(), personId, type,
                Instant.now(), payload == null ? Map.of() : payload, correlationId, causationId);
    }

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }
}
