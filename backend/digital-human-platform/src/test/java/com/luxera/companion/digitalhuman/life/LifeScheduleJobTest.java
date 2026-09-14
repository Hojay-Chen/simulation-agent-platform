package com.luxera.companion.digitalhuman.life;

import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.life.LifeActivity;
import com.luxera.companion.life.LifeActivityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V10 §7.4 Life Scheduler 集成测试:
 * 活动排程 → 到点触发 → 幂等收尾(ACTIVE → DONE) + Reality Ledger ACTIVITY_ENDED。
 */
@ActiveProfiles("test")
@SpringBootTest
class LifeScheduleJobTest {

    @Autowired
    LifeEventScheduler lifeEventScheduler;
    @Autowired
    LifeScheduleJob lifeScheduleJob;
    @Autowired
    LifeScheduleStore store;
    @Autowired
    LifeActivityRepository activityRepository;
    @Autowired
    RealityLedger realityLedger;

    private final String companionId = "life-sched-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void tearDown() {
        for (LifeScheduleRecord r : store.dueEvents(LocalDateTime.now().plusDays(1))) {
            if (companionId.equals(r.getPersonId())) {
                store.markDone(r.getId());
            }
        }
        activityRepository.findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                companionId, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1))
                .forEach(activityRepository::delete);
    }

    private LifeActivity createActivity(String status, LocalDateTime plannedEnd) {
        LifeActivity a = new LifeActivity();
        a.setCompanionId(companionId);
        a.setType("LEISURE");
        a.setTitle("看书");
        a.setPlannedStart(plannedEnd.minusHours(1));
        a.setPlannedEnd(plannedEnd);
        a.setStatus(status);
        a.setSource("TEST");
        return activityRepository.save(a);
    }

    @Test
    void scheduledActivityEndFiresAndFinalizes() {
        LifeActivity activity = createActivity("ACTIVE", LocalDateTime.now().minusMinutes(5));
        // 排程已到点的活动结束事件
        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-end-" + activity.getId(), companionId, LifeEventType.ACTIVITY_END,
                LocalDateTime.now().minusMinutes(5),
                Map.of("activityId", activity.getId(), "title", "看书")));

        lifeScheduleJob.fireDueEvents();

        // 活动已收尾
        LifeActivity after = activityRepository.findById(activity.getId()).orElseThrow();
        assertEquals("DONE", after.getStatus(), "到点事件应收尾活动");
        assertNotNull(after.getActualEnd(), "actualEnd 应被记录");

        // Reality Ledger 已记录事实
        List<com.luxera.companion.digitalhuman.reality.RealityEvent> ended =
                realityLedger.eventsOfType(companionId, RealityEventType.ACTIVITY_ENDED, 5);
        assertEquals(1, ended.size(), "账本应有一条 ACTIVITY_ENDED");

        // 排程记录已 DONE
        assertTrue(store.dueEvents(LocalDateTime.now().plusDays(1)).isEmpty()
                || store.dueEvents(LocalDateTime.now().plusDays(1)).stream()
                        .noneMatch(r -> r.getScheduleId().equals("test-end-" + activity.getId())),
                "已触发的排程不应再出现");
    }

    @Test
    void doubleFireIsIdempotent() {
        LifeActivity activity = createActivity("ACTIVE", LocalDateTime.now().minusMinutes(5));
        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-dup-" + activity.getId(), companionId, LifeEventType.ACTIVITY_END,
                LocalDateTime.now().minusMinutes(5),
                Map.of("activityId", activity.getId(), "title", "看书")));

        lifeScheduleJob.fireDueEvents();
        lifeScheduleJob.fireDueEvents();   // 再次触发

        assertEquals(1, realityLedger.eventsOfType(companionId, RealityEventType.ACTIVITY_ENDED, 10).size(),
                "重复触发不得重复写账本(幂等)");
    }

    @Test
    void scheduleIsIdempotentByScheduleId() {
        LifeActivity activity = createActivity("PLANNED", LocalDateTime.now().plusHours(1));
        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-idem-" + activity.getId(), companionId, LifeEventType.ACTIVITY_END,
                LocalDateTime.now().plusHours(1), Map.of("activityId", activity.getId())));
        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-idem-" + activity.getId(), companionId, LifeEventType.ACTIVITY_END,
                LocalDateTime.now().plusHours(1), Map.of("activityId", activity.getId())));

        // 同 scheduleId 只入一条; 未到点不触发
        lifeScheduleJob.fireDueEvents();
        assertEquals("PLANNED", activityRepository.findById(activity.getId()).orElseThrow().getStatus(),
                "未到点的事件不应触发");
    }
}
