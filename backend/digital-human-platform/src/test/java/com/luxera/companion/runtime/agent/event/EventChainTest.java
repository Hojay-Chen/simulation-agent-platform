package com.luxera.companion.runtime.agent.event;

import com.luxera.companion.experience.ExperienceRepository;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.runtime.WorldEventLog;
import com.luxera.companion.runtime.WorldEventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §21 Event Chain 因果链测试:
 * - 因果链逐层记录(出门吃饭 → 下雨 → 忘带伞 → 淋雨)
 * - 深度限制 maxDepth=3(不会无限延续)
 * - 主事件 + 后果都落到 world_events
 * - 情绪显著事件产生想法(经历)
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.unfinished-thought-cron=0 0 0 1 1 *",
        "app.scheduler.thread-maintenance-cron=0 0 0 1 1 *",
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *"
})
class EventChainTest {

    @Autowired
    EventChainService eventChainService;
    @Autowired
    WorldEventLogRepository worldEventLogRepository;
    @Autowired
    ExperienceRepository experienceRepository;
    @Autowired
    CompanionRepository companionRepository;

    private String companionId;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 12, 0);

    @BeforeEach
    void setUp() {
        companionId = UUID.randomUUID().toString();
        Companion c = new Companion();
        c.setId(companionId);
        c.setUserId("event-user");
        c.setName("小满");
        c.setGender("female");
        companionRepository.save(c);
    }

    @Test
    void chainRecordsEachLevel() {
        eventChainService.applyChain(companionId, "FORGOT_UMBRELLA",
                List.of("淋了点雨", "心情有点低落"), NOW);
        var events = worldEventLogRepository
                .findTop200ByCompanionIdOrderByOccurredAtDesc(companionId);
        // 主事件 + 2 层后果 = 至少 3 条世界事件记录
        assertTrue(events.size() >= 3, "因果链每层都应记录, 实际 " + events.size());
    }

    @Test
    void chainDepthIsLimitedToMaxDepth() {
        // 构造大量后果(每条后果作为下一层事件), 验证:
        // 1) 后果数量不导致深度失控(深度由链嵌套决定, 而非后果数量)
        // 2) 记录的事件数 = 主事件 + 每层后果
        List<String> many = List.of("后果1", "后果2", "后果3", "后果4", "后果5",
                "后果6", "后果7", "后果8", "后果9", "后果10");
        eventChainService.applyChain(companionId, "GOOD_NEWS", many, NOW);
        var events = worldEventLogRepository
                .findTop200ByCompanionIdOrderByOccurredAtDesc(companionId);
        // 主事件 + 10 条直接后果 = 11 条(后果并行, 深度 2)
        assertEquals(1 + many.size(), events.size(), "后果应作为下一层并行事件, 不无限加深");
        // 任何事件的 chainDepth 不超过 MAX_DEPTH
        for (var e : events) {
            if (e.getPayload() != null && e.getPayload().contains("chainDepth")) {
                int depth = Integer.parseInt(
                        e.getPayload().replaceAll(".*chainDepth.:(\\d+).*", "$1"));
                assertTrue(depth <= EventChainService.MAX_DEPTH,
                        "链深度应 ≤ " + EventChainService.MAX_DEPTH + ", 实际 " + depth);
            }
        }
    }

    @Test
    void noConsequencesOnlyRecordsMainEvent() {
        eventChainService.applyChain(companionId, "MEET_ACQUAINTANCE", null, NOW);
        var events = worldEventLogRepository
                .findTop200ByCompanionIdOrderByOccurredAtDesc(companionId);
        assertEquals(1, events.size(), "无后果时只记录主事件");
    }

    @Test
    void emotionallySignificantEventCreatesExperience() {
        // 先清空该伴侣经历
        eventChainService.applyChain(companionId, "FORGOT_UMBRELLA",
                List.of("淋了点雨"), NOW);
        var experiences = experienceRepository.findByCompanionIdOrderByOccurredAtDesc(companionId);
        assertFalse(experiences.isEmpty(), "情绪显著事件应产生经历记录");
    }
}
