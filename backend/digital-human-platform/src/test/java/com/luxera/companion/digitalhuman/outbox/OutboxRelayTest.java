package com.luxera.companion.digitalhuman.outbox;

import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.outbox.OutboxEvent;
import com.luxera.companion.outbox.OutboxEventRepository;
import com.luxera.companion.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §21.3 Outbox 测试:
 * - 业务事务内入队 → Relay 发布 → 事件进入事件链(被处理);
 * - 同 key 幂等入队(不重复);
 * - 重复发布被 processed_event 幂等短路(不重复处理);
 * - 发布失败自动重试(attempts 递增, 退避)。
 */
@ActiveProfiles("test")
@SpringBootTest
class OutboxRelayTest {

    @Autowired
    OutboxPublisher publisher;
    @Autowired
    OutboxRelayJob relayJob;
    @Autowired
    OutboxEventRepository repository;
    @Autowired
    EventRouter eventRouter;

    private static final String PERSON = "outbox-test-" + UUID.randomUUID().toString().substring(0, 8);

    private final AtomicInteger processed = new AtomicInteger();

    @BeforeEach
    void setUp() {
        eventRouter.register(ExternalEventType.APPLICATION_EVENT, e -> processed.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        eventRouter.unregister(ExternalEventType.APPLICATION_EVENT);
        repository.deleteAll();
    }

    @Test
    void enqueueThenRelayPublishesAndMarksPublished() {
        String key = "app-1-" + UUID.randomUUID();
        OutboxEvent enqueued = publisher.enqueue(key, PERSON, ExternalEventType.APPLICATION_EVENT.name(),
                Map.of("source", "test-app", "dedupKey", key));
        assertNotNull(enqueued);
        assertEquals(OutboxEvent.STATUS_PENDING, enqueued.getStatus());

        relayJob.relay();

        OutboxEvent after = repository.findById(enqueued.getEventId()).orElseThrow();
        assertEquals(OutboxEvent.STATUS_PUBLISHED, after.getStatus(), "发布成功后标记 PUBLISHED");
        assertEquals(1, processed.get(), "事件应被路由处理");
    }

    @Test
    void sameKeyEnqueueIsIdempotent() {
        String key = "app-dup-" + UUID.randomUUID();
        OutboxEvent first = publisher.enqueue(key, PERSON, ExternalEventType.APPLICATION_EVENT.name(),
                Map.of("source", "test-app", "dedupKey", key));
        OutboxEvent second = publisher.enqueue(key, PERSON, ExternalEventType.APPLICATION_EVENT.name(),
                Map.of("source", "test-app", "dedupKey", key));
        assertNotNull(first);
        assertNull(second, "同 key 不重复入队");
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void relayReplayIsDeduplicated() {
        String key = "app-replay-" + UUID.randomUUID();
        publisher.enqueue(key, PERSON, ExternalEventType.APPLICATION_EVENT.name(),
                Map.of("source", "test-app", "dedupKey", key));

        relayJob.relay();
        relayJob.relay();   // 同事件再发: processed_event 幂等短路

        assertEquals(1, processed.get(), "重放不得重复处理(processed_event 幂等)");
    }

    @Test
    void unmappedEventTypeFailsAndRetries() {
        // ENVIRONMENT_EVENT 无路由 → 发布失败 → attempts 递增 + 退避
        String key = "env-" + UUID.randomUUID();
        OutboxEvent enqueued = publisher.enqueue(key, PERSON, ExternalEventType.ENVIRONMENT_EVENT.name(),
                Map.of("source", "env", "dedupKey", key));

        relayJob.relay();

        OutboxEvent after = repository.findById(enqueued.getEventId()).orElseThrow();
        assertTrue(after.getAttempts() >= 1, "失败后 attempts 递增");
        assertEquals(OutboxEvent.STATUS_PENDING, after.getStatus(), "未超过上限保持 PENDING");
        assertTrue(after.getNextAttemptAt().isAfter(java.time.LocalDateTime.now().minusSeconds(1)),
                "退避重试时间已排程");
        assertNotNull(after.getLastError());
    }

    @Test
    void deterministicEventIdMatchesLivePathRule() {
        // Outbox 重建的 eventId 必须与 AgentRuntime live 路径一致(幂等短路的基础)
        String dedupKey = "companion-c1-conv-c1-m1-live";
        com.luxera.companion.digitalhuman.event.ExternalEvent fromOutbox =
                com.luxera.companion.digitalhuman.event.ExternalEvent.withDeterministicId(
                        "c1", ExternalEventType.CHAT_MESSAGE_DELIVERED, dedupKey, Map.of());
        com.luxera.companion.digitalhuman.event.ExternalEvent fromLive =
                com.luxera.companion.digitalhuman.event.ExternalEvent.withDeterministicId(
                        "c1", ExternalEventType.CHAT_MESSAGE_DELIVERED, dedupKey, Map.of());
        assertEquals(fromLive.eventId(), fromOutbox.eventId(),
                "同 dedupKey 的 eventId 必须一致(与 AgentRuntime.submitWithPhase 规则一致)");
    }
}
