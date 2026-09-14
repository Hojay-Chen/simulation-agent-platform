package com.luxera.companion.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V10 §21.3 OutboxPublisher: 事件入队(与业务状态同事务提交)。
 *
 * 用法: 业务事务内调用 enqueue → 业务提交时事件已可靠持久化;
 * 后台 OutboxRelayJob 负责发布, 发布失败自动退避重试。
 *
 * 禁止: 业务先提交、再发布事件(双写问题) —— 事件必须在业务事务内入队。
 */
@Slf4j
@Service
public class OutboxPublisher {

    private final OutboxEventRepository repository;

    public OutboxPublisher(OutboxEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 入队(幂等: 同 eventKey 不重复入队)。必须在业务事务内调用。
     *
     * @param eventType event-type name; the chat platform passes its own vocabulary
     *                  ({@code CHAT_MESSAGE_DELIVERED}) and the digital human resolves it back
     *                  into its own enum on relay, so neither module imports the other's types.
     */
    @Transactional
    public OutboxEvent enqueue(String eventKey, String personId, String eventType,
                               Map<String, Object> payload) {
        if (eventKey == null || personId == null || eventType == null) {
            return null;
        }
        if (repository.existsByEventKey(eventKey)) {
            log.debug("[Outbox] 重复入队跳过: {}", eventKey);
            return null;
        }
        OutboxEvent event = new OutboxEvent();
        event.assignIdIfMissing();
        event.setEventKey(eventKey);
        event.setPersonId(personId);
        event.setEventType(eventType);
        event.setPayload(payload == null ? Map.of() : payload);
        event.setStatus(OutboxEvent.STATUS_PENDING);
        event.setAttempts(0);
        event.setNextAttemptAt(LocalDateTime.now());
        return repository.save(event);
    }

    /** 到期待发事件(Relay 轮询用) */
    @Transactional(readOnly = true)
    public List<OutboxEvent> pendingDue(LocalDateTime now) {
        return repository.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxEvent.STATUS_PENDING, now);
    }

    /** 发布成功 */
    @Transactional
    public void markPublished(String eventId) {
        repository.findById(eventId).ifPresent(e -> {
            e.setStatus(OutboxEvent.STATUS_PUBLISHED);
            e.setPublishedAt(LocalDateTime.now());
            repository.save(e);
        });
    }

    /** 发布失败: 退避重试(10s * 2^attempts), 超过上限标记 FAILED */
    @Transactional
    public void markFailed(String eventId, String error) {
        repository.findById(eventId).ifPresent(e -> {
            int attempts = e.getAttempts() + 1;
            e.setAttempts(attempts);
            e.setLastError(error == null ? "unknown" : truncate(error, 500));
            if (attempts >= OutboxEvent.MAX_ATTEMPTS) {
                e.setStatus(OutboxEvent.STATUS_FAILED);
                log.error("[Outbox] {} 重试 {} 次仍失败, 标记 FAILED: {}", eventId, attempts, error);
            } else {
                e.setNextAttemptAt(LocalDateTime.now().plusSeconds(10L * (1L << Math.min(attempts, 5))));
            }
            repository.save(e);
        });
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
