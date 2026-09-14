package com.luxera.companion.digitalhuman.outbox;

import com.luxera.companion.digitalhuman.event.EventProcessingChain;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.outbox.OutboxEvent;
import com.luxera.companion.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V10 §21.3 OutboxRelayJob: 可靠发布器 —— 轮询 outbox_event 并发布到事件链。
 *
 * 发布目标: EventProcessingChain(Validation → Dedup → Route) ——
 * 确定性 eventId + processed_event 幂等保证"重试不重复触发"。
 * 未来替换为 Kafka/MQ 时, 只改本类的发布目标。
 */
@Slf4j
@Component
public class OutboxRelayJob {

    private final OutboxPublisher publisher;
    private final EventProcessingChain eventProcessingChain;

    public OutboxRelayJob(OutboxPublisher publisher, EventProcessingChain eventProcessingChain) {
        this.publisher = publisher;
        this.eventProcessingChain = eventProcessingChain;
    }

    @Scheduled(cron = "${app.scheduler.outbox-relay-cron:*/5 * * * * *}")
    public void relay() {
        List<OutboxEvent> due = publisher.pendingDue(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        for (OutboxEvent outboxEvent : due) {
            try {
                ExternalEvent event = rebuild(outboxEvent);
                EventProcessingChain.ChainOutcome outcome = eventProcessingChain.process(event);
                if (outcome.status() == EventProcessingChain.ChainOutcome.Status.COMPLETED
                        || outcome.status() == EventProcessingChain.ChainOutcome.Status.DEDUPLICATED) {
                    // 已投递(或已处理过, 幂等短路): 发布成功
                    publisher.markPublished(outboxEvent.getEventId());
                } else {
                    // REJECTED/TERMINATED/FAILED: 事件无法投递, 走失败重试
                    publisher.markFailed(outboxEvent.getEventId(), outcome.note());
                }
            } catch (Exception e) {
                log.warn("[Outbox] 发布失败 event={}: {}", outboxEvent.getEventId(), e.getMessage());
                publisher.markFailed(outboxEvent.getEventId(), e.getMessage());
            }
        }
    }

    /** 从 Outbox 记录重建 ExternalEvent(确定性 eventId: dedupKey 一致则幂等短路) */
    private static ExternalEvent rebuild(OutboxEvent outboxEvent) {
        Map<String, Object> payload = outboxEvent.getPayload() == null ? Map.of() : outboxEvent.getPayload();
        String dedupKey = payload.get("dedupKey") == null
                ? outboxEvent.getEventKey() : payload.get("dedupKey").toString();
        return ExternalEvent.withDeterministicId(
                outboxEvent.getPersonId(),
                ExternalEventType.valueOf(outboxEvent.getEventType()),
                dedupKey, payload);
    }
}
