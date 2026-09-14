package com.luxera.companion.agent;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.sleep.CircadianStateRepository;
import com.luxera.companion.sleep.SleepModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 作息测试: 作息是 Emergent Behavior, 不是 Schedule。
 * - 夜班(酒吧)伴侣: 23 点醒着上班(即使生物钟偏向晚睡)
 * - 白班伴侣: 23 点是否睡由 SleepModel 决定(不是固定 22 点)
 * - 睡眠状态由 SleepModel 控制, 不是 activityFor 硬编码
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.sleep-tick-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *"
})
class NightWorkerScheduleTest {

    @Autowired
    CompanionSchedule schedule;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    PersonaService personaService;
    @Autowired
    SleepModel sleepModel;
    @Autowired
    CircadianStateRepository circadianRepo;

    private String nightCompanionId;
    private String dayCompanionId;

    @BeforeEach
    void setUp() {
        nightCompanionId = UUID.randomUUID().toString();
        dayCompanionId = UUID.randomUUID().toString();

        saveCompanion(nightCompanionId);
        saveCompanion(dayCompanionId);

        Persona night = new Persona();
        night.setPersonality(persona("温柔体贴,在酒吧工作让她见惯世故却依然保持真诚"));
        personaService.saveInitial(nightCompanionId, night);

        Persona day = new Persona();
        day.setPersonality(persona("一个安静的上班族,喜欢看书"));
        personaService.saveInitial(dayCompanionId, day);
    }

    private void saveCompanion(String id) {
        Companion c = new Companion();
        c.setId(id);
        c.setUserId("night-test-user");
        c.setName("测试");
        c.setGender("female");
        companionRepository.save(c);
    }

    private Persona.Personality persona(String summary) {
        Persona.Personality p = new Persona.Personality();
        p.setSummary(summary);
        return p;
    }

    @Test
    void nightWorkerDetectedFromBarPersona() {
        assertTrue(schedule.isNightWorker(nightCompanionId), "酒吧工作应识别为夜班");
        assertFalse(schedule.isNightWorker(dayCompanionId), "普通上班族不应识别为夜班");
    }

    @Test
    void nightWorkerIsAwakeAndWorkingAtNight() {
        // 23:00 夜班伴侣: 即使有 SleepModel, 未入睡时在酒吧上班
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        assertEquals(CompanionSchedule.Activity.WORK_BUSY, schedule.activityFor(nightCompanionId, nightTime),
                "酒吧夜班伴侣 23 点应在上班(未入睡时)");
    }

    @Test
    void sleepingIsControlledBySleepModel() {
        // 手动让她入睡 → isSleeping=true → activityFor 返回 SLEEP(即使 23 点本该上班)
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 23, 0);
        sleepModel.fallAsleep(nightCompanionId, now, "EXHAUSTION");
        assertTrue(sleepModel.isSleeping(nightCompanionId, now));
        assertEquals(CompanionSchedule.Activity.SLEEP, schedule.activityFor(nightCompanionId, now),
                "入睡后应返回 SLEEP, 睡眠优先于社会活动");
        // 唤醒 → 恢复社会活动
        var c = sleepModel.getOrCreate(nightCompanionId, now);
        sleepModel.wakeUp(c, now.plusMinutes(10), "NATURAL");
        assertFalse(sleepModel.isSleeping(nightCompanionId, now));
        assertEquals(CompanionSchedule.Activity.WORK_BUSY, schedule.activityFor(nightCompanionId, now.plusMinutes(10)));
    }

    @Test
    void dayWorkerSleepIsEmergentNotScheduled() {
        // 白班伴侣 23 点: 睡眠由 SleepModel 决定(压力/节律/动机), 不是固定"22 点必睡"
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        // 显式设为"醒着但压力低"(模拟刚睡醒或午睡后) → 23 点不应强制 SLEEP
        var c = sleepModel.getOrCreate(dayCompanionId, nightTime);
        c.setSleeping(false);
        c.setSleepPressure(0.2);   // 低压力
        circadianRepo.save(c);
        assertNotEquals(CompanionSchedule.Activity.SLEEP, schedule.activityFor(dayCompanionId, nightTime),
                "睡眠压力低时不应强制睡眠(emergent)");
    }

    @Test
    void nightWorkerDescribeReflectsBarWork() {
        LocalDateTime nightTime = LocalDateTime.of(2026, 8, 16, 23, 0);
        String desc = schedule.describe(nightCompanionId, "林晚晴", nightTime);
        assertTrue(desc.contains("忙") || desc.contains("工作"), "描述应体现上班: " + desc);
    }
}
