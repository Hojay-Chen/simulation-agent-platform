package com.luxera.companion.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 排程动作服务测试(§64/§65): 持久化 + 到期 + 状态流转。 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.proactive-cron=0 0 0 1 1 *"
})
class ScheduledActionServiceTest {

    @Autowired
    ScheduledActionService service;

    @Autowired
    ScheduledActionRepository repo;

    @Test
    void scheduleAndDue() {
        String companionId = "test-sched-" + System.nanoTime();
        var action = service.schedule(companionId, ScheduledAction.RE_EVALUATE_MESSAGE,
                LocalDateTime.now().minusSeconds(5), Map.of("pendingMessageId", "m1"));

        List<ScheduledAction> due = service.dueActions(LocalDateTime.now());
        assertTrue(due.stream().anyMatch(a -> a.getId().equals(action.getId())),
                "过期的排程动作应到期");

        service.markDone(action.getId());
        assertEquals(ScheduledAction.STATUS_DONE, repo.findById(action.getId()).orElseThrow().getStatus());
    }

    @Test
    void cancelPendingByType() {
        String companionId = "test-cancel-" + System.nanoTime();
        service.schedule(companionId, ScheduledAction.RE_EVALUATE_MESSAGE,
                LocalDateTime.now().plusHours(1), Map.of("pendingMessageId", "m1"));
        service.schedule(companionId, ScheduledAction.SEND_MESSAGE,
                LocalDateTime.now().plusHours(1), Map.of());

        service.cancelPending(companionId, ScheduledAction.RE_EVALUATE_MESSAGE);

        assertTrue(service.pending(companionId, ScheduledAction.RE_EVALUATE_MESSAGE).isEmpty());
        assertFalse(service.pending(companionId, ScheduledAction.SEND_MESSAGE).isEmpty());
    }
}
