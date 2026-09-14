package com.luxera.companion.digitalhuman.life;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V10 §7.4 LifeScheduleStore: LifeEventScheduler 的持久化实现。
 *
 * schedule: 入表(同 scheduleId 幂等, 不重复排程);
 * cancel: 删除/作废;
 * dueEvents: 到点事件(轮询取数)。
 */
@Slf4j
@Service
public class LifeScheduleStore implements LifeEventScheduler {

    private final LifeScheduleRepository repository;

    public LifeScheduleStore(LifeScheduleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void schedule(ScheduledLifeEvent event) {
        if (event == null || event.scheduleId() == null || event.personId() == null) {
            return;
        }
        if (repository.existsByScheduleId(event.scheduleId())) {
            log.debug("[LifeScheduler] 重复排程跳过: {}", event.scheduleId());
            return;
        }
        LifeScheduleRecord record = new LifeScheduleRecord();
        record.assignIdIfMissing();
        record.setScheduleId(event.scheduleId());
        record.setPersonId(event.personId());
        record.setEventType(event.type().name());
        record.setFireAt(event.fireAt());
        record.setPayload(event.payload());
        record.setStatus(LifeScheduleRecord.STATUS_PENDING);
        repository.save(record);
        log.debug("[LifeScheduler] {} 排程 {} @ {}", event.personId(), event.type(), event.fireAt());
    }

    @Override
    @Transactional
    public void cancel(String scheduleId) {
        repository.findByScheduleId(scheduleId).ifPresent(record -> {
            if (LifeScheduleRecord.STATUS_PENDING.equals(record.getStatus())) {
                record.setStatus(LifeScheduleRecord.STATUS_FAILED);
                record.setLastError("cancelled");
                repository.save(record);
            }
        });
    }

    /** 到期待触发事件(升序) */
    @Transactional(readOnly = true)
    public List<LifeScheduleRecord> dueEvents(LocalDateTime now) {
        return repository.findTop50ByStatusAndFireAtLessThanEqualOrderByFireAtAsc(
                LifeScheduleRecord.STATUS_PENDING, now);
    }

    @Transactional
    public void markDone(String id) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(LifeScheduleRecord.STATUS_DONE);
            record.setFiredAt(LocalDateTime.now());
            repository.save(record);
        });
    }

    @Transactional
    public void markFailed(String id, String error) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(LifeScheduleRecord.STATUS_FAILED);
            record.setLastError(error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)));
            repository.save(record);
        });
    }
}
