package com.luxera.companion.digitalhuman.life;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V10 §7.4 ScheduledLifeEvent: 一条已排程的生活事件。
 */
public record ScheduledLifeEvent(
        String scheduleId,
        String personId,
        LifeEventType type,
        LocalDateTime fireAt,
        Map<String, Object> payload
) {

    public static ScheduledLifeEvent of(String scheduleId, String personId, LifeEventType type,
                                        LocalDateTime fireAt, Map<String, Object> payload) {
        return new ScheduledLifeEvent(scheduleId, personId, type, fireAt, payload);
    }

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String str(String key) {
        Object v = get(key);
        return v == null ? null : v.toString();
    }
}
