package com.luxera.companion.sleep;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.state.AgentStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §63 Sleep Runtime 场景测试:
 * - 场景1: 连续多天睡眠时间不同(emergent), 不是每天固定
 * - 场景2: 午睡后当晚睡眠推迟
 * - 场景3: 深夜高睡意 + 强动机 → STAY_AWAKE(意志克服睡意)
 * - 场景5: 洗澡(无手机)场景由 Phone Runtime 处理, 这里验证 SleepModel 基础
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.sleep-tick-cron=0 0 0 1 1 *",
        "app.scheduler.phone-tick-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *"
})
class SleepModelTest {

    @Autowired
    SleepModel sleepModel;
    @Autowired
    CircadianStateRepository circadianRepo;
    @Autowired
    SleepSessionRepository sleepRepo;
    @Autowired
    CompanionRepository companionRepository;
    @Autowired
    AgentStateService agentStateService;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("sleep-user");
        c.setName("测试");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void sleepPressureBuildsWhileAwake() {
        CircadianState c = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 8, 0));
        c.setSleepPressure(0.2);
        c.setLastWakeAt(LocalDateTime.of(2026, 8, 16, 8, 0));
        circadianRepo.save(c);

        // 醒着 10 小时后压力应上升
        sleepModel.tick(companionId, LocalDateTime.of(2026, 8, 16, 18, 0));
        double after = circadianRepo.findByCompanionId(companionId).orElseThrow().getSleepPressure();
        assertTrue(after > 0.2, "醒着越久压力应越高: " + after);
        assertTrue(after > 0.4, "醒 10 小时后应有明显睡意: " + after);
    }

    @Test
    void highSleepinessWithStrongMotivationStaysAwake() {
        CircadianState c = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 23, 0));
        c.setSleeping(false);   // 深夜初始化可能已入睡; 这里模拟"她醒着但很困"
        c.setSleepPressure(0.9);   // 高睡意
        c.setLastWakeAt(LocalDateTime.of(2026, 8, 16, 8, 0));
        circadianRepo.save(c);

        // 场景3: 深夜聊天, 用户重要 → 动机强 → STAY_AWAKE(意志克服睡意)
        var decision = sleepModel.decideSleep(companionId, LocalDateTime.of(2026, 8, 16, 23, 30),
                0.9, 0.9);
        assertTrue(decision == SleepModel.SleepDecision.STAY_AWAKE
                        || decision == SleepModel.SleepDecision.DELAY_SLEEP,
                "高睡意+强动机应能硬撑聊天, 实际: " + decision);
    }

    @Test
    void lowMotivationFallsAsleep() {
        CircadianState c = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 23, 0));
        c.setSleepPressure(0.95);   // 高睡意(0.95 压力确保综合 propensity ≥ 阈值)
        c.setLastWakeAt(LocalDateTime.of(2026, 8, 16, 8, 0));
        circadianRepo.save(c);

        // 无动机 + 高睡意 → SLEEP
        var decision = sleepModel.decideSleep(companionId, LocalDateTime.of(2026, 8, 16, 23, 0), 0, 0);
        assertEquals(SleepModel.SleepDecision.SLEEP, decision);
    }

    @Test
    void napDelaysNightSleep() {
        // 场景2: 14:00-19:00 午睡(5小时) → 当晚睡意大幅下降 → 不睡
        CircadianState c = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 8, 0));
        c.setSleepPressure(0.15);
        c.setLastWakeAt(LocalDateTime.of(2026, 8, 16, 8, 0));
        circadianRepo.save(c);

        // 午睡
        sleepModel.fallAsleep(companionId, LocalDateTime.of(2026, 8, 16, 14, 0), "NATURAL");
        sleepModel.tick(companionId, LocalDateTime.of(2026, 8, 16, 19, 0));   // 睡5小时
        sleepModel.wakeUp(circadianRepo.findByCompanionId(companionId).orElseThrow(),
                LocalDateTime.of(2026, 8, 16, 19, 0), "NATURAL");
        // 重置 lastWakeAt 为午睡醒
        c = circadianRepo.findByCompanionId(companionId).orElseThrow();
        c.setLastWakeAt(LocalDateTime.of(2026, 8, 16, 19, 0));
        circadianRepo.save(c);

        // 22:00 睡意应明显低(午睡后压力低)
        double propensity = sleepModel.sleepPropensity(companionId,
                LocalDateTime.of(2026, 8, 16, 22, 0), 0, 0);
        assertTrue(propensity < 0.6, "午睡后 22 点睡意应低, 实际 " + propensity);

        var decision = sleepModel.decideSleep(companionId, LocalDateTime.of(2026, 8, 16, 22, 0), 0, 0);
        assertNotEquals(SleepModel.SleepDecision.SLEEP, decision,
                "午睡后当晚不应强制入睡(场景2)");
    }

    @Test
    void sleepSessionRecorded() {
        // 深夜初始化可能已入睡; 先确保醒着, 再手动入睡
        CircadianState init = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 23, 0));
        init.setSleeping(false);
        circadianRepo.save(init);
        sleepModel.fallAsleep(companionId, LocalDateTime.of(2026, 8, 16, 23, 0), "NATURAL");
        var c = circadianRepo.findByCompanionId(companionId).orElseThrow();
        sleepModel.wakeUp(c, LocalDateTime.of(2026, 8, 17, 7, 0), "NATURAL");
        var sessions = sleepRepo.findByCompanionIdOrderByStartTimeDesc(companionId);
        assertEquals(1, sessions.size());
        assertEquals(8 * 60, sessions.get(0).getDurationMinutes(), "睡眠时长应约 8 小时");
    }

    @Test
    void wakeAfterSufficientSleep() {
        // 睡 7 小时后应自然醒
        CircadianState c = sleepModel.getOrCreate(companionId, LocalDateTime.of(2026, 8, 16, 23, 0));
        c.setSleeping(true);
        c.setSleepStartedAt(LocalDateTime.of(2026, 8, 16, 23, 0));
        circadianRepo.save(c);

        sleepModel.tick(companionId, LocalDateTime.of(2026, 8, 17, 6, 30));
        assertFalse(circadianRepo.findByCompanionId(companionId).orElseThrow().isSleeping(),
                "睡 7.5 小时后应自然醒");
    }
}
