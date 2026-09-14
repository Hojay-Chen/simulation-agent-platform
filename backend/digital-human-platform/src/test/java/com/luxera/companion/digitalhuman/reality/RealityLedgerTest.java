package com.luxera.companion.digitalhuman.reality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §8 Reality Ledger 测试:
 * - append-only(事件只追加, 事实变化写新事件);
 * - 幂等(同 eventId 不重复写入);
 * - 回放(全部真实经历可回放, V10 MVP 验收 9);
 * - Memory 不能覆盖 Reality(账本只读投影, V10 MVP 验收 10)。
 */
@ActiveProfiles("test")
@SpringBootTest
class RealityLedgerTest {

    @Autowired
    RealityLedger realityLedger;
    @Autowired
    RealityEventRepository repository;

    private String personId;

    @BeforeEach
    void setUp() {
        personId = "reality-test-" + UUID.randomUUID().toString().substring(0, 8);
        repository.deleteAll(repository.findByPersonIdOrderByOccurredAtDesc(personId));
    }

    @Test
    void appendWritesImmutableEvent() {
        RealityEvent event = realityLedger.append(personId, RealityEventType.MESSAGE_SENT,
                Map.of("text", "你好", "conversationId", "c1"));
        assertNotNull(event.eventId());
        assertEquals(1, realityLedger.count(personId));

        RealityEventRecord saved = repository.findById(event.eventId()).orElseThrow();
        assertEquals("MESSAGE_SENT", saved.getEventType());
        assertEquals("你好", saved.getPayload().get("text"));
    }

    @Test
    void appendIsIdempotentByEventId() {
        RealityEvent event = RealityEvent.of(personId, RealityEventType.MESSAGE_READ, Map.of("messageId", "m1"));
        realityLedger.append(event);
        realityLedger.append(event);   // 同 eventId 重放
        assertEquals(1, realityLedger.count(personId), "同 eventId 不得重复写入");
    }

    @Test
    void appendOnlyRealityChangeWritesNewEvent() {
        // 事实变化: 不是修改旧事件, 而是追加新事件(V10 §8.1)
        realityLedger.append(personId, RealityEventType.ACTIVITY_STARTED, Map.of("activity", "WORKING"));
        realityLedger.append(personId, RealityEventType.ACTIVITY_ENDED, Map.of("activity", "WORKING"));
        assertEquals(2, realityLedger.count(personId));
        List<RealityEvent> events = realityLedger.all(personId);
        assertEquals(2, events.size());
        // 事件类型各不相同且都保留(无 UPDATE/DELETE)
        assertTrue(events.stream().anyMatch(e -> e.type() == RealityEventType.ACTIVITY_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.type() == RealityEventType.ACTIVITY_ENDED));
    }

    @Test
    void replayReturnsTimelineInOrder() {
        realityLedger.append(personId, RealityEventType.MESSAGE_SENT, Map.of("seq", 1));
        realityLedger.append(personId, RealityEventType.MESSAGE_DEFERRED, Map.of("seq", 2));
        realityLedger.append(personId, RealityEventType.MESSAGE_SENT, Map.of("seq", 3));

        List<RealityEvent> replay = realityLedger.since(personId, LocalDateTime.now().minusMinutes(5));
        assertEquals(3, replay.size(), "时间正序回放应包含全部事件");
        assertEquals(1, ((Number) replay.get(0).get("seq")).intValue());
        assertEquals(3, ((Number) replay.get(2).get("seq")).intValue());
    }

    @Test
    void recentReturnsLatestFirstWithLimit() {
        for (int i = 1; i <= 5; i++) {
            realityLedger.append(personId, RealityEventType.LIFE_EVENT, Map.of("seq", i));
        }
        List<RealityEvent> recent = realityLedger.recent(personId, 2);
        assertEquals(2, recent.size());
        assertEquals(5, ((Number) recent.get(0).get("seq")).intValue());
        assertEquals(4, ((Number) recent.get(1).get("seq")).intValue());
    }

    @Test
    void eventsOfTypeFiltersByType() {
        realityLedger.append(personId, RealityEventType.MESSAGE_SENT, Map.of());
        realityLedger.append(personId, RealityEventType.MESSAGE_IGNORED, Map.of());
        realityLedger.append(personId, RealityEventType.MESSAGE_SENT, Map.of());
        List<RealityEvent> sent = realityLedger.eventsOfType(personId, RealityEventType.MESSAGE_SENT, 10);
        assertEquals(2, sent.size());
    }

    @Test
    void carriesCorrelationAndCausation() {
        RealityEvent event = realityLedger.append(personId, RealityEventType.MESSAGE_SENT,
                Map.of("text", "x"), "corr-1", "cause-1");
        RealityEvent loaded = realityLedger.recent(personId, 1).get(0);
        assertEquals("corr-1", loaded.correlationId());
        assertEquals("cause-1", loaded.causationId());
        assertFalse(loaded.eventId().isEmpty());
    }
}
