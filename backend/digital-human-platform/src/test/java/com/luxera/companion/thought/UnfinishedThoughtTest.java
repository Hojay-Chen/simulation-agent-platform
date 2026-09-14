package com.luxera.companion.thought;

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
 * §31 Unfinished Thought 测试:
 * - 创建未完成想法(type=UNFINISHED, ACTIVE)
 * - 过期 → 遗忘(EXPIRED)
 * - 冷却期后激活(优先级达标 → 强度提升)
 * - 优先级过低 → 不激活
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *"
})
class UnfinishedThoughtTest {

    @Autowired
    ThoughtService thoughtService;
    @Autowired
    ThoughtRepository repo;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("thought-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void createUnfinishedThoughtIsActive() {
        Thought t = thoughtService.createUnfinished(companionId, "想补一句:刚在忙,没顾上回你",
                "CONVERSATION", null, 0.7, LocalDateTime.now().plusHours(12));
        assertEquals("UNFINISHED", t.getType());
        assertEquals("ACTIVE", t.getStatus());
        assertTrue(t.getStrength() > 0, "未完成想法应有强度");
    }

    @Test
    void expiredUnfinishedThoughtIsForgotten() {
        Thought t = thoughtService.createUnfinished(companionId, "想问但忘了",
                "CONVERSATION", null, 0.6, LocalDateTime.now().minusHours(1));
        thoughtService.activateUnfinished(companionId, LocalDateTime.now());
        assertEquals("EXPIRED", repo.findById(t.getId()).orElseThrow().getStatus(),
                "过期的未完成想法应被遗忘");
    }

    @Test
    void highPriorityThoughtIsActivatedAfterCooldown() {
        Thought t = thoughtService.createUnfinished(companionId, "想解释为什么没及时回复",
                "CONVERSATION", null, 0.9, LocalDateTime.now().plusHours(24));
        // 冷却期内(30 分钟内)不激活
        thoughtService.activateUnfinished(companionId, LocalDateTime.now());
        double strengthBefore = repo.findById(t.getId()).orElseThrow().getStrength();
        // 指定冷却时间 0 → 立即可激活(高优先级 0.9 达标)
        var reactivated = thoughtService.activateUnfinished(companionId, LocalDateTime.now(), 0);
        double strengthAfter = repo.findById(t.getId()).orElseThrow().getStrength();
        assertFalse(reactivated.isEmpty(), "高优先级未完成想法应被激活");
        assertTrue(strengthAfter > strengthBefore, "激活应提升强度");
    }

    @Test
    void lowPriorityThoughtNotActivated() {
        Thought t = thoughtService.createUnfinished(companionId, "随口想说的话",
                "CONVERSATION", null, 0.2, LocalDateTime.now().plusHours(6));
        var reactivated = thoughtService.activateUnfinished(companionId, LocalDateTime.now(), 0);
        assertTrue(reactivated.isEmpty(), "低优先级未完成想法不应被激活");
    }
}
