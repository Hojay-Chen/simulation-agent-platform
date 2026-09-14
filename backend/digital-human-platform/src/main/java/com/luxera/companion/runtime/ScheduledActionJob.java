package com.luxera.companion.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排程动作轮询(§5 next wake-up): 周期性检查到期动作并分发。
 * 到点才唤醒相关处理; 而不是每 tick 调用 LLM。
 */
@Slf4j
@Component
public class ScheduledActionJob {

    private final ScheduledActionService service;
    private final ScheduledActionDispatcher dispatcher;

    public ScheduledActionJob(ScheduledActionService service, ScheduledActionDispatcher dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "${app.scheduler.scheduled-action-cron:*/20 * * * * *}")
    public void processDue() {
        List<ScheduledAction> due = service.dueActions(LocalDateTime.now());
        if (due.isEmpty()) return;
        for (ScheduledAction action : due) {
            boolean ok = dispatcher.dispatch(action);
            if (ok) {
                service.markDone(action.getId());
            } else {
                service.markFailed(action.getId());
            }
        }
    }
}
