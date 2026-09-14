package com.luxera.companion.digitalhuman.life;

import com.luxera.companion.plan.Plan;
import com.luxera.companion.plan.PlanRepository;
import com.luxera.companion.plan.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V10 §7.3/§7.4 PLAN_REMINDER 集成测试:
 * 计划到点 → LifeScheduleJob 分发 → 计划激活(PLANNED → EXECUTING)。
 */
@ActiveProfiles("test")
@SpringBootTest
class PlanReminderDispatchTest {

    @Autowired
    LifeEventScheduler lifeEventScheduler;
    @Autowired
    LifeScheduleJob lifeScheduleJob;
    @Autowired
    LifeScheduleStore store;
    @Autowired
    PlanService planService;
    @Autowired
    PlanRepository planRepository;

    private final String companionId = "plan-rem-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        // 清理该测试人的残留排程
        for (LifeScheduleRecord r : store.dueEvents(LocalDateTime.now().plusDays(1))) {
            if (companionId.equals(r.getPersonId())) {
                store.markDone(r.getId());
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (LifeScheduleRecord r : store.dueEvents(LocalDateTime.now().plusDays(1))) {
            if (companionId.equals(r.getPersonId())) {
                store.markDone(r.getId());
            }
        }
        planRepository.findActive(companionId).forEach(planRepository::delete);
    }

    @Test
    void planReminderActivatesPlannedPlan() {
        // 排程一个已到点的计划提醒
        Plan plan = planService.create(companionId, "ACTIVITY", "跑步", 0.7, 0.4,
                LocalDateTime.now().minusMinutes(10), null, null);
        assertEquals(Plan.STATUS_PLANNED, plan.getStatus());

        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-plan-" + plan.getId(), companionId, LifeEventType.PLAN_REMINDER,
                LocalDateTime.now().minusMinutes(10),
                Map.of("planId", plan.getId(), "title", "跑步")));

        lifeScheduleJob.fireDueEvents();

        Plan after = planRepository.findById(plan.getId()).orElseThrow();
        assertEquals(Plan.STATUS_ACTIVE, after.getStatus(), "计划到点应被激活执行");
    }

    @Test
    void planReminderOnMissingPlanFailsGracefully() {
        // 不存在的 planId → 分发失败(不崩溃, 排程标记失败)
        lifeEventScheduler.schedule(ScheduledLifeEvent.of(
                "test-missing-" + UUID.randomUUID(), companionId, LifeEventType.PLAN_REMINDER,
                LocalDateTime.now().minusMinutes(1), Map.of("planId", "no-such-plan")));
        lifeScheduleJob.fireDueEvents();
        // 不抛异常即可(活动收尾等其他排程不受影响)
    }
}
