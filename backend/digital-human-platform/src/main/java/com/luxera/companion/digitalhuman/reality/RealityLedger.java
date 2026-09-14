package com.luxera.companion.digitalhuman.reality;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * V10 §8 Reality Ledger: 数字人真实经历的 append-only 事实账本。
 *
 * 设计要点:
 * - 只有真实发生的行为(发送/已读/延迟/活动/计划执行)才能写入;
 * - 事件不可修改、不可删除(实体 @Immutable, 禁止 UPDATE);
 * - Memory 不能覆盖 Reality(V10 MVP 验收 10): 记忆系统只能投影账本,
 *   不能改写账本;
 * - 全部真实经历可在账本回放(V10 MVP 验收 9)。
 *
 * 写路径约定: 所有 append 必须发生在 Person Actor 的串行上下文中
 * (同 Person 的事件顺序 = 真实时间顺序)。
 */
@Slf4j
@Service
public class RealityLedger {

    private final RealityEventRepository repository;

    public RealityLedger(RealityEventRepository repository) {
        this.repository = repository;
    }

    /** 追加一条事实事件(唯一写入口) */
    @Transactional
    public RealityEvent append(String personId, RealityEventType type, Map<String, Object> payload) {
        return append(RealityEvent.of(personId, type, payload));
    }

    /** 追加带因果链的事实事件 */
    @Transactional
    public RealityEvent append(String personId, RealityEventType type, Map<String, Object> payload,
                               String correlationId, String causationId) {
        return append(RealityEvent.of(personId, type, payload, correlationId, causationId));
    }

    /** 追加(幂等: 同 eventId 已存在则不重复写入) */
    @Transactional
    public RealityEvent append(RealityEvent event) {
        if (event == null || event.personId() == null) return null;
        if (repository.existsById(event.eventId())) {
            log.debug("[RealityLedger] 事件已存在, 跳过: {}", event.eventId());
            return event;
        }
        repository.save(RealityEventRecord.from(event));
        log.debug("[RealityLedger] {} +{} {}", event.personId(), event.type(), event.eventId());
        return event;
    }

    // ── 读路径(只读投影) ──────────────────────

    /** 最近 N 条事实(时间倒序) */
    @Transactional(readOnly = true)
    public List<RealityEvent> recent(String personId, int limit) {
        return repository.findByPersonIdOrderByOccurredAtDesc(personId,
                        PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "occurredAt")))
                .stream().map(RealityEventRecord::toEvent).toList();
    }

    /**
     * 指定时间之后的全部事实(时间正序, 回放用)。
     * after 按系统本地时间解释; 存储统一使用 UTC(与 RealityEventRecord 一致)。
     */
    @Transactional(readOnly = true)
    public List<RealityEvent> since(String personId, java.time.LocalDateTime after) {
        java.time.LocalDateTime utcAfter = after == null ? java.time.LocalDateTime.MIN
                : java.time.LocalDateTime.ofInstant(
                        after.atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneOffset.UTC);
        return repository.findByPersonIdAndOccurredAtAfterOrderByOccurredAtAsc(personId, utcAfter)
                .stream().map(RealityEventRecord::toEvent).toList();
    }

    /** 某类型最近事件 */
    @Transactional(readOnly = true)
    public List<RealityEvent> eventsOfType(String personId, RealityEventType type, int limit) {
        List<RealityEventRecord> records = repository.findByPersonIdAndType(personId, type.name());
        return records.stream().limit(Math.max(0, limit)).map(RealityEventRecord::toEvent).toList();
    }

    /** 全部事实(时间倒序) */
    @Transactional(readOnly = true)
    public List<RealityEvent> all(String personId) {
        return repository.findByPersonIdOrderByOccurredAtDesc(personId)
                .stream().map(RealityEventRecord::toEvent).toList();
    }

    /** 事实总数 */
    @Transactional(readOnly = true)
    public long count(String personId) {
        return repository.countByPersonId(personId);
    }
}
