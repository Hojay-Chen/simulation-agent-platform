package com.luxera.companion.life;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6/§32 Activity 具体化 + Interrupt 中断系统测试:
 * - 活动类型映射为具体属性(会议注意力高/可打断性低)
 * - 高可打断性活动可被消息中断
 * - 低可打断性活动(开会/睡觉)不可被中断
 * - 中断后恢复
 * - 活动进度推进
 * - canCheckPhone / canReply 随活动属性变化
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *"
})
class LifeInterruptTest {

    @Autowired
    LifeInterruptService interruptService;
    @Autowired
    LifeActivityRepository activityRepo;
    @Autowired
    ActivitySpecProvider specProvider;
    @Autowired
    LifeSimulationService simulationService;

    private String companionId;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 14, 0);

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
    }

    private LifeActivity createActivity(String type, LocalDateTime start, LocalDateTime end,
                                        double interruptibility, double attentionDemand) {
        LifeActivity a = new LifeActivity();
        a.setCompanionId(companionId);
        a.setType(type);
        a.setTitle(type);
        a.setPlannedStart(start);
        a.setPlannedEnd(end);
        a.setStatus("ACTIVE");
        a.setInterruptibility(interruptibility);
        a.setAttentionDemand(attentionDemand);
        a.setPhoneAvailability(0.6);
        return activityRepo.save(a);
    }

    @Test
    void activitySpecMappingIsConcrete() {
        var meeting = specProvider.specFor("MEETING");
        assertEquals(0.92, meeting.attentionDemand(), 0.01);
        assertEquals(0.12, meeting.interruptibility(), 0.01);
        var leisure = specProvider.specFor("LEISURE");
        assertEquals(0.2, leisure.attentionDemand(), 0.01);
        assertEquals(0.8, leisure.interruptibility(), 0.01);
        var shower = specProvider.specFor("SHOWER");
        assertEquals(0.0, shower.phoneAvailability(), 0.01);
    }

    @Test
    void interruptibleActivityCanBeInterrupted() {
        createActivity("HOUSEWORK", NOW.minusMinutes(30), NOW.plusMinutes(30), 0.65, 0.4);
        boolean interrupted = interruptService.interrupt(companionId, NOW, "用户消息到达");
        assertTrue(interrupted, "可打断性高的活动应可被中断");
        var active = interruptService.currentActive(companionId, NOW);
        assertNotNull(active);
        assertTrue(active.isInterrupted());
        assertEquals("用户消息到达", active.getInterruptReason());
    }

    @Test
    void lowInterruptibilityActivityCannotBeInterrupted() {
        createActivity("MEETING", NOW.minusMinutes(30), NOW.plusMinutes(30), 0.12, 0.92);
        boolean interrupted = interruptService.interrupt(companionId, NOW, "用户消息到达");
        assertFalse(interrupted, "开会(可打断性低)不应被中断");
    }

    @Test
    void interruptedActivityCanResume() {
        createActivity("HOUSEWORK", NOW.minusMinutes(30), NOW.plusMinutes(30), 0.65, 0.4);
        interruptService.interrupt(companionId, NOW, "用户消息");
        interruptService.resume(companionId, NOW.plusMinutes(5));
        var active = interruptService.currentActive(companionId, NOW.plusMinutes(5));
        assertNotNull(active);
        assertFalse(active.isInterrupted(), "中断后应能恢复");
        assertNull(active.getInterruptReason());
    }

    @Test
    void progressAdvances() {
        createActivity("STUDY", NOW.minusMinutes(30), NOW.plusMinutes(90), 0.25, 0.8);
        interruptService.advanceProgress(companionId, NOW, 0.2);
        assertEquals(0.2, interruptService.currentActive(companionId, NOW).getProgress(), 0.01);
        interruptService.advanceProgress(companionId, NOW, 0.3);
        assertEquals(0.5, interruptService.currentActive(companionId, NOW).getProgress(), 0.01);
    }

    @Test
    void canCheckPhoneAndCanReplyFollowActivity() {
        LifeActivity meeting = createActivity("MEETING", NOW.minusMinutes(30), NOW.plusMinutes(30), 0.12, 0.92);
        assertFalse(interruptService.canCheckPhone(meeting), "会议中不适合看手机");
        assertFalse(interruptService.canReply(meeting), "会议中不适合回复");
        LifeActivity leisure = createActivity("LEISURE", NOW.minusMinutes(30), NOW.plusMinutes(30), 0.8, 0.2);
        assertTrue(interruptService.canCheckPhone(leisure), "休闲时适合看手机");
        assertTrue(interruptService.canReply(leisure), "休闲时适合回复");
    }

    @Test
    void dayPlanningAppliesSpecs() {
        simulationService.ensureDayPlanned(companionId, NOW.toLocalDate());
        var acts = activityRepo
                .findByCompanionIdAndPlannedStartGreaterThanEqualAndPlannedStartLessThanOrderByPlannedStartAsc(
                        companionId, NOW.toLocalDate().atStartOfDay(), NOW.toLocalDate().plusDays(1).atStartOfDay());
        assertFalse(acts.isEmpty(), "应生成一天的活动");
        // 不再生成固定 SLEEP 块(睡眠是 emergent); 验证社会活动 spec 正确
        LifeActivity work = acts.stream().filter(a -> "WORK".equals(a.getType())).findFirst().orElse(null);
        if (work != null) {
            assertTrue(work.getAttentionDemand() >= 0.8, "工作注意力占用应高");
            assertTrue(work.getInterruptibility() <= 0.25, "工作可打断性应低");
        }
        LifeActivity leisure = acts.stream().filter(a -> "LEISURE".equals(a.getType())).findFirst().orElse(null);
        if (leisure != null) {
            assertTrue(leisure.getAttentionDemand() <= 0.3, "休闲注意力占用应低");
            assertTrue(leisure.getInterruptibility() >= 0.7, "休闲可打断性应高");
        }
        assertTrue(acts.stream().noneMatch(a -> "SLEEP".equals(a.getType())),
                "睡眠不应作为固定排程活动");
    }
}
