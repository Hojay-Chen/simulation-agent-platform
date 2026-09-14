package com.luxera.companion.runtime.agent.memory;

import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Memory Agent 两阶段召回测试(§23/§24): 候选记忆必须返回激活评分。 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "app.scheduler.event-simulation-cron=0 0 0 1 1 *",
        "app.scheduler.life-tick-cron=0 0 0 1 1 *",
        "app.scheduler.proactive-cron=0 0 0 1 1 *"
})
class MemoryAgentTest {

    @Autowired
    MemoryAgent memoryAgent;

    @Autowired
    MemoryRepository memoryRepo;

    @Test
    void returnsActivationsForCandidates() {
        String userId = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
        String companionId = "test-comp-" + UUID.randomUUID().toString().substring(0, 8);

        Memory m1 = new Memory();
        m1.setUserId(userId);
        m1.setCompanionId(companionId);
        m1.setContent("用户曾经因为被忽略而难过");
        m1.setImportance(0.8);
        m1.setConfidence(0.9);
        m1.setOccurredAt(LocalDateTime.now().minusDays(10));
        m1.setStatus("active");
        m1 = memoryRepo.save(m1);

        Memory m2 = new Memory();
        m2.setUserId(userId);
        m2.setCompanionId(companionId);
        m2.setContent("用户喜欢喝奶茶");
        m2.setImportance(0.5);
        m2.setConfidence(0.9);
        m2.setOccurredAt(LocalDateTime.now().minusDays(3));
        m2.setStatus("active");
        m2 = memoryRepo.save(m2);

        MemoryRecallContext ctx = new MemoryRecallContext(
                companionId, userId, "你是不是又不理我了", List.of(m1, m2),
                "一个敏感的人", "close", "有点难过");

        MemoryRecallResult result = memoryAgent.execute(ctx);
        assertNotNull(result);
        assertEquals(2, result.activations().size());
        assertTrue(result.activations().stream().allMatch(a -> a.activation() >= 0 && a.activation() <= 1));

        memoryRepo.delete(m2);
        memoryRepo.delete(m1);
    }

    @Test
    void emptyCandidatesReturnsEmpty() {
        MemoryRecallResult r = memoryAgent.execute(new MemoryRecallContext(
                "c", "u", "hi", List.of(), null, null, null));
        assertTrue(r.activations().isEmpty());
    }
}
