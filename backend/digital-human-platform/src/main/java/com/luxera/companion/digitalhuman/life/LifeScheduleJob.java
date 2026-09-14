package com.luxera.companion.digitalhuman.life;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V10 §7.4 LifeScheduleJob: 时间触发轮询 —— 到点事件分发执行。
 * cron 配置化(app.scheduler.life-schedule-cron, 测试环境禁用)。
 */
@Slf4j
@Component
public class LifeScheduleJob {

    private final LifeScheduleStore store;
    private final LifeEventDispatcher dispatcher;

    public LifeScheduleJob(LifeScheduleStore store, LifeEventDispatcher dispatcher) {
        this.store = store;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "${app.scheduler.life-schedule-cron:*/20 * * * * *}")
    public void fireDueEvents() {
        List<LifeScheduleRecord> due = store.dueEvents(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        for (LifeScheduleRecord record : due) {
            try {
                boolean ok = dispatcher.dispatch(record);
                if (ok) {
                    store.markDone(record.getId());
                } else {
                    store.markFailed(record.getId(), "dispatch rejected");
                }
            } catch (Exception e) {
                log.warn("[LifeScheduler] 事件执行失败 schedule={}: {}", record.getScheduleId(), e.getMessage());
                store.markFailed(record.getId(), e.getMessage());
            }
        }
    }
}
