package com.luxera.companion.runtime;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 世界事件(§5/§62): 系统中发生的一件事。
 * 携带事件类型、时间戳、companionId 与结构化 payload。
 * 事件是认知层的唯一输入; 也是事件溯源的基础记录。
 */
public record WorldEvent(String eventId, String type, LocalDateTime timestamp,
                         String companionId, Map<String, Object> payload) {

    public static WorldEvent of(String type, String companionId, Map<String, Object> payload) {
        return new WorldEvent(null, type, LocalDateTime.now(), companionId, payload);
    }

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }
}
