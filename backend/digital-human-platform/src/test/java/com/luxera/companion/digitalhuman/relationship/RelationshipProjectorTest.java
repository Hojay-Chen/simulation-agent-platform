package com.luxera.companion.digitalhuman.relationship;

import com.luxera.companion.digitalhuman.reality.RealityEvent;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §17 RelationshipProjector 测试: 从 Reality 事件流投影互动摘要(纯函数)。
 */
class RelationshipProjectorTest {

    private final RelationshipProjector projector = new RelationshipProjector();

    private static RealityEvent ev(RealityEventType type, String id, Instant at) {
        return new RealityEvent(id, "p1", type, at, Map.of(), null, null);
    }

    @Test
    void emptyEventsProduceEmptySummary() {
        InteractionSummary s = projector.project(List.of());
        assertTrue(s.noInteraction());
        assertNull(s.lastInteractionAt());
        assertEquals(0, s.replyRate());
    }

    @Test
    void countsEachEventType() {
        Instant t1 = Instant.parse("2026-08-19T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-19T11:00:00Z");
        List<RealityEvent> events = List.of(
                ev(RealityEventType.MESSAGE_READ, "e1", t1),
                ev(RealityEventType.MESSAGE_SENT, "e2", t2),
                ev(RealityEventType.MESSAGE_READ, "e3", t2),
                ev(RealityEventType.MESSAGE_DEFERRED, "e4", t1),
                ev(RealityEventType.MESSAGE_IGNORED, "e5", t1),
                ev(RealityEventType.ACTIVITY_ENDED, "e6", t1));

        InteractionSummary s = projector.project(events);
        assertEquals(6, s.totalEvents());
        assertEquals(1, s.messagesSentByPerson());
        assertEquals(2, s.messagesRead());
        assertEquals(1, s.messagesDeferred());
        assertEquals(1, s.messagesIgnored());
        assertEquals(1, s.activitiesEnded());
        assertEquals(t2, s.lastInteractionAt(), "最近互动取最大时间");
        assertEquals(t1, s.firstInteractionAt(), "首次互动取最小时间");
    }

    @Test
    void replyRateIsSentOverRead() {
        List<RealityEvent> events = List.of(
                ev(RealityEventType.MESSAGE_READ, "e1", Instant.now()),
                ev(RealityEventType.MESSAGE_SENT, "e2", Instant.now()),
                ev(RealityEventType.MESSAGE_READ, "e3", Instant.now()),
                ev(RealityEventType.MESSAGE_SENT, "e4", Instant.now()));
        InteractionSummary s = projector.project(events);
        assertEquals(1.0, s.replyRate(), 0.001, "2 读 2 回 → 回复率 100%");
    }

    @Test
    void interactionEventFilterMatchesLedgerTypes() {
        assertTrue(RelationshipProjector.isInteractionEvent(RealityEventType.MESSAGE_SENT));
        assertTrue(RelationshipProjector.isInteractionEvent(RealityEventType.MESSAGE_READ));
        assertTrue(RelationshipProjector.isInteractionEvent(RealityEventType.MESSAGE_DEFERRED));
        assertTrue(RelationshipProjector.isInteractionEvent(RealityEventType.MESSAGE_IGNORED));
        assertTrue(!RelationshipProjector.isInteractionEvent(RealityEventType.ACTIVITY_ENDED));
        assertTrue(!RelationshipProjector.isInteractionEvent(RealityEventType.LIFE_EVENT));
    }
}
