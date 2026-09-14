package com.luxera.companion.intention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §35-§36 Intention Runtime 测试:
 * - 创建意图(ACTIVE + 基础激活概率)
 * - 到 expected_time 附近激活概率升高
 * - 过期 → FORGOTTEN(真人会忘)
 * - markActed 标记执行
 * - activatable 按阈值过滤
 * - remind 突然想起(概率提升)
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.sleep-tick-cron=0 0 0 1 1 *",
        "app.scheduler.phone-tick-cron=0 0 0 1 1 *"
})
class IntentionServiceTest {

    @Autowired
    IntentionService service;
    @Autowired
    IntentionRepository repo;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
    }

    @Test
    void createIntentionIsActive() {
        Intention i = service.create(companionId, "u1", "想告诉他一件事", 0.7, "想分享",
                "user", LocalDateTime.now().plusHours(2), 24);
        assertEquals("ACTIVE", i.getStatus());
        assertTrue(i.getActivationProbability() > 0.5, "重要性 0.7 应有较高激活概率");
        assertNotNull(i.getExpiryTime());
    }

    @Test
    void probabilityRisesNearExpectedTime() {
        LocalDateTime expected = LocalDateTime.now().plusMinutes(60);
        Intention i = service.create(companionId, "u1", "该回复他了", 0.6, "内疚",
                "user", expected, 24);

        // 刚开始(还有 60 分钟): 概率中等
        double early = service.decay(companionId, LocalDateTime.now())
                .stream().filter(x -> x.getId().equals(i.getId())).findFirst()
                .map(Intention::getActivationProbability).orElse(0.0);

        // 到点后(过 30 分钟): 概率更高
        double after = service.decay(companionId, expected.plusMinutes(30))
                .stream().filter(x -> x.getId().equals(i.getId())).findFirst()
                .map(Intention::getActivationProbability).orElse(0.0);

        assertTrue(after >= early - 0.05, "到点后激活概率不应显著低于早期: early=" + early + " after=" + after);
    }

    @Test
    void expiredIntentionIsForgotten() {
        Intention i = service.create(companionId, "u1", "随口想说的话", 0.3, null,
                "user", LocalDateTime.now().minusHours(30), 24);
        service.decay(companionId, LocalDateTime.now());
        assertEquals("FORGOTTEN", repo.findById(i.getId()).orElseThrow().getStatus(),
                "过期意图应被遗忘(真人会忘)");
    }

    @Test
    void markActedSetsStatus() {
        Intention i = service.create(companionId, "u1", "想跟他分享今天的事", 0.8, "开心",
                "user", LocalDateTime.now().plusHours(1), 12);
        service.markActed(i.getId());
        assertEquals("ACTED", repo.findById(i.getId()).orElseThrow().getStatus());
    }

    @Test
    void activatableFiltersByThreshold() {
        service.create(companionId, "u1", "重要的事要说", 0.9, null, "user",
                LocalDateTime.now().plusMinutes(30), 12);   // 高概率
        service.create(companionId, "u1", "不重要的事", 0.2, null, "user",
                LocalDateTime.now().plusHours(10), 24);     // 低概率
        var activatable = service.activatable(companionId, 0.6);
        assertFalse(activatable.isEmpty(), "高概率意图应可激活");
        assertTrue(activatable.stream().allMatch(i -> i.getActivationProbability() >= 0.6));
    }

    @Test
    void remindBoostsProbability() {
        Intention i = service.create(companionId, "u1", "忘了要回复他", 0.5, "内疚",
                "user", LocalDateTime.now().minusHours(3), 24);
        double before = repo.findById(i.getId()).orElseThrow().getActivationProbability();
        service.remind(i.getId());
        double after = repo.findById(i.getId()).orElseThrow().getActivationProbability();
        assertTrue(after > before, "突然想起 → 激活概率提升");
    }
}
