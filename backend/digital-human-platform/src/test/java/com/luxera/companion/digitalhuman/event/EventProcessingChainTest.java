package com.luxera.companion.digitalhuman.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §9.2 事件处理链测试(Chain of Responsibility):
 * - Validation 失败短路(残缺事件不进入认知);
 * - 无路由事件终止;
 * - Deduplication 幂等(同 eventId 重放不重复触发, V10 MVP 验收 13)。
 */
@ActiveProfiles("test")
@SpringBootTest
class EventProcessingChainTest {

    @Autowired
    EventProcessingChain chain;
    @Autowired
    EventRouter eventRouter;
    @Autowired
    ProcessedEventRepository processedEventRepository;

    private static final String TEST_PERSON = "chain-test-" + UUID.randomUUID();

    @AfterEach
    void tearDown() {
        eventRouter.unregister(ExternalEventType.APPLICATION_EVENT);
        processedEventRepository.deleteAll();
    }

    @Test
    void validationRejectsIncompleteEvent() {
        ExternalEvent noSource = ExternalEvent.of(
                "evt-" + UUID.randomUUID(), TEST_PERSON, ExternalEventType.CHAT_MESSAGE_DELIVERED,
                Instant.now(), Map.of("messageIds", java.util.List.of("m1")), null);
        EventProcessingChain.ChainOutcome outcome = chain.process(noSource);
        assertEquals(EventProcessingChain.ChainOutcome.Status.REJECTED, outcome.status());
        assertTrue(outcome.note().contains("source"));
    }

    @Test
    void rejectsMissingPersonId() {
        ExternalEvent noPerson = new ExternalEvent("evt-x", "", ExternalEventType.TIME_EVENT,
                Instant.now(), Map.of(), null);
        assertEquals(EventProcessingChain.ChainOutcome.Status.REJECTED, chain.process(noPerson).status());
    }

    @Test
    void routesToRegisteredHandler() {
        AtomicInteger calls = new AtomicInteger();
        eventRouter.register(ExternalEventType.APPLICATION_EVENT, e -> calls.incrementAndGet());
        ExternalEvent event = ExternalEvent.of(TEST_PERSON, ExternalEventType.APPLICATION_EVENT,
                Map.of("source", "test-app"));
        EventProcessingChain.ChainOutcome outcome = chain.process(event);
        assertTrue(outcome.ok(), outcome.note());
        assertEquals(1, calls.get());
        assertTrue(processedEventRepository.existsByEventId(event.eventId()), "处理成功后应写入幂等记录");
    }

    @Test
    void deduplicationShortCircuitsReplay() {
        AtomicInteger calls = new AtomicInteger();
        eventRouter.register(ExternalEventType.APPLICATION_EVENT, e -> calls.incrementAndGet());
        ExternalEvent event = ExternalEvent.of(TEST_PERSON, ExternalEventType.APPLICATION_EVENT,
                Map.of("source", "test-app"));

        EventProcessingChain.ChainOutcome first = chain.process(event);
        assertTrue(first.ok(), first.note());
        // 同一 eventId 重放 → 幂等短路, 不重复触发
        EventProcessingChain.ChainOutcome second = chain.process(event);
        assertEquals(EventProcessingChain.ChainOutcome.Status.DEDUPLICATED, second.status(), second.note());
        assertEquals(1, calls.get(), "重放不得重复触发认知");
    }

    @Test
    void unmappedEventTypeTerminates() {
        // AgentRuntime 只注册了 CHAT_MESSAGE_DELIVERED, 其他类型无路由 → 终止
        ExternalEvent event = ExternalEvent.of(TEST_PERSON, ExternalEventType.ENVIRONMENT_EVENT,
                Map.of("source", "env"));
        EventProcessingChain.ChainOutcome outcome = chain.process(event);
        assertEquals(EventProcessingChain.ChainOutcome.Status.TERMINATED, outcome.status());
        assertTrue(outcome.note().contains("无该类型路由"));
    }

    @Test
    void deterministicEventIdStableForSameSource() {
        ExternalEvent e1 = ExternalEvent.withDeterministicId(TEST_PERSON,
                ExternalEventType.CHAT_MESSAGE_DELIVERED, "p-c-m1", Map.of("source", "chat-platform"));
        ExternalEvent e2 = ExternalEvent.withDeterministicId(TEST_PERSON,
                ExternalEventType.CHAT_MESSAGE_DELIVERED, "p-c-m1", Map.of("source", "chat-platform"));
        assertEquals(e1.eventId(), e2.eventId(), "同源事件的确定性 eventId 必须稳定(幂等基础)");
        assertFalse(e1.eventId().contains(" "), "eventId 不应含空白字符");
    }
}
