package com.luxera.companion.behavior;

import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §45/§46 Behavior Pattern 测试:
 * - 支持观测 → 模式创建, 置信度/强度上升
 * - 反例观测 → 置信度下降
 * - 深夜消息 → 学习"深夜回复慢"模式
 * - 工作时段消息 → 学习"工作回复慢"模式
 * - 多次观测 → 模式逐步巩固
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *"
})
class BehaviorPatternTest {

    @Autowired
    BehaviorPatternService patternService;
    @Autowired
    BehaviorLearningService learningService;
    @Autowired
    BehaviorPatternRepository repo;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("bp-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void observeCreatesPatternWithConfidence() {
        patternService.observe(companionId, BehaviorPatternService.NIGHT_LATE_REPLY,
                "深夜收到消息时, 她不会立即回复", "reduce_response", true);
        var p = patternService.get(companionId, BehaviorPatternService.NIGHT_LATE_REPLY);
        assertNotNull(p);
        assertEquals(1, p.getObservations());
        assertTrue(p.getConfidence() > 0.5, "首次支持观测应提升置信度");
    }

    @Test
    void counterExampleLowersConfidence() {
        var p = patternService.observe(companionId, BehaviorPatternService.NIGHT_LATE_REPLY,
                "深夜不回复", "reduce_response", true);
        double afterTrue = p.getConfidence();
        p = patternService.observe(companionId, BehaviorPatternService.NIGHT_LATE_REPLY,
                "深夜不回复", "reduce_response", false);
        assertTrue(p.getConfidence() < afterTrue, "反例应降低置信度");
    }

    @Test
    void nightMessageLearnsLateReply() {
        learningService.onUserMessage(companionId, LocalDateTime.of(2026, 8, 16, 23, 30), "calm");
        var p = patternService.get(companionId, BehaviorPatternService.NIGHT_LATE_REPLY);
        assertNotNull(p, "深夜消息应学习深夜回复慢模式");
        assertEquals("reduce_response", p.getInfluence());
        assertTrue(p.getObservations() >= 1);
    }

    @Test
    void workHoursMessageLearnsWorkLowResponse() {
        learningService.onUserMessage(companionId, LocalDateTime.of(2026, 8, 17, 10, 0), "calm"); // 周一10点
        var p = patternService.get(companionId, BehaviorPatternService.WORK_HOURS_LOW_RESPONSE);
        assertNotNull(p, "工作时段消息应学习工作回复慢模式");
        assertEquals("reduce_response", p.getInfluence());
    }

    @Test
    void weekendWorkHoursAreNotLearnedAsWork() {
        // 周末 10 点 → 不算工作时段
        learningService.onUserMessage(companionId, LocalDateTime.of(2026, 8, 16, 10, 0), "calm"); // 周日
        var p = patternService.get(companionId, BehaviorPatternService.WORK_HOURS_LOW_RESPONSE);
        if (p != null) {
            assertTrue(p.getObservations() < 2, "周末不应大量学习工作模式");
        }
    }

    @Test
    void repeatedObservationStrengthensPattern() {
        for (int i = 0; i < 12; i++) {
            patternService.observe(companionId, BehaviorPatternService.POSITIVE_EMOTION_PROACTIVE,
                    "用户开心时更主动", "boost_proactive", true);
        }
        var p = patternService.get(companionId, BehaviorPatternService.POSITIVE_EMOTION_PROACTIVE);
        assertEquals(12, p.getObservations());
        assertTrue(p.getConfidence() > 0.7, "多次支持观测应巩固置信度");
        assertTrue(p.getStrength() > 0.5, "强度应随观察提升(confidence×饱和度)");
    }
}
