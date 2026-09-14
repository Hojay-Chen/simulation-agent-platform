package com.luxera.companion.digitalhuman.relationship;

import com.luxera.companion.digitalhuman.reality.RealityEvent;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V10 §17 RelationshipProjector: 从 Reality Ledger 事件流投影关系事实(纯函数)。
 *
 * 输入: 数字人的全部 Reality 事件; 输出: 互动摘要。
 * 不修改任何状态 —— 投影是只读计算, 可随时重放(幂等)。
 */
@Component
public class RelationshipProjector {

    /** 投影一个事件集合 → 互动摘要 */
    public InteractionSummary project(List<RealityEvent> events) {
        if (events == null || events.isEmpty()) {
            return InteractionSummary.empty();
        }
        long sent = 0;
        long read = 0;
        long deferred = 0;
        long ignored = 0;
        long activities = 0;
        java.time.Instant last = null;
        java.time.Instant first = null;

        for (RealityEvent event : events) {
            java.time.Instant at = event.occurredAt();
            if (first == null || at.isBefore(first)) {
                first = at;
            }
            if (last == null || at.isAfter(last)) {
                last = at;
            }
            switch (event.type()) {
                case MESSAGE_SENT -> sent++;
                case MESSAGE_READ -> read++;
                case MESSAGE_DEFERRED -> deferred++;
                case MESSAGE_IGNORED -> ignored++;
                case ACTIVITY_ENDED -> activities++;
                default -> { }
            }
        }
        return new InteractionSummary(events.size(), sent, read, deferred, ignored, activities,
                last, first);
    }

    /** 事件是否属于"与用户的互动"口径(用于 reconcile 过滤) */
    public static boolean isInteractionEvent(RealityEventType type) {
        return type == RealityEventType.MESSAGE_SENT
                || type == RealityEventType.MESSAGE_READ
                || type == RealityEventType.MESSAGE_DEFERRED
                || type == RealityEventType.MESSAGE_IGNORED;
    }
}
