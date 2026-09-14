package com.luxera.companion.runtime.agent.memory;

import com.luxera.companion.memory.Memory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §19 Memory Recall Probability 测试:
 * - 召回概率 = 激活 × 显著性
 * - 低于阈值 → 不进入当前认知(过滤掉)
 * - 达到阈值 → 保留, 按激活降序
 * - 无激活信息 → 用显著性兜底
 */
class MemoryRecallProbabilityTest {

    private final MemoryRecallProbabilityService service = new MemoryRecallProbabilityService();

    private static Memory mem(String id, double importance, double confidence, String content) {
        Memory m = new Memory();
        m.setId(id);
        m.setImportance(importance);
        m.setConfidence(confidence);
        m.setContent(content);
        m.setOccurredAt(LocalDateTime.now().minusDays(1));
        return m;
    }

    @Test
    void recallProbabilityCombinesActivationAndSalience() {
        Memory m = mem("m1", 0.8, 0.9, "重要记忆");   // salience = 0.72
        double prob = service.recallProbability(0.8, m);
        // 0.8 × (0.5 + 0.72*0.5) = 0.8 × 0.86 = 0.688
        assertEquals(0.688, prob, 1e-9);
    }

    @Test
    void lowActivationFallsBelowThreshold() {
        Memory m = mem("m1", 0.8, 0.9, "重要记忆");   // salience 0.72, activation 0.2 → prob 0.172
        List<Memory> filtered = service.filterAboveThreshold(
                List.of(m),
                new MemoryRecallResult(List.of(new MemoryRecallResult.MemoryActivation("m1", 0.2, "弱相关")), false),
                MemoryRecallProbabilityService.DEFAULT_THRESHOLD);
        assertTrue(filtered.isEmpty(), "低召回概率的记忆不应进入当前认知");
    }

    @Test
    void highActivationPassesThreshold() {
        Memory m = mem("m1", 0.8, 0.9, "重要记忆");   // salience 0.72, activation 0.8 → prob 0.688
        List<Memory> filtered = service.filterAboveThreshold(
                List.of(m),
                new MemoryRecallResult(List.of(new MemoryRecallResult.MemoryActivation("m1", 0.8, "强相关")), false),
                MemoryRecallProbabilityService.DEFAULT_THRESHOLD);
        assertEquals(1, filtered.size(), "高召回概率的记忆应进入认知");
    }

    @Test
    void sortsByActivationDescending() {
        Memory m1 = mem("m1", 0.9, 0.9, "a");   // salience 0.81
        Memory m2 = mem("m2", 0.6, 0.5, "b");   // salience 0.3
        Memory m3 = mem("m3", 0.4, 0.4, "c");   // salience 0.16
        var recall = new MemoryRecallResult(List.of(
                new MemoryRecallResult.MemoryActivation("m1", 0.9, ""),   // prob 0.9*(0.5+0.405)=0.814
                new MemoryRecallResult.MemoryActivation("m2", 0.7, ""),   // prob 0.7*(0.5+0.15)=0.455
                new MemoryRecallResult.MemoryActivation("m3", 0.6, "")    // prob 0.6*(0.5+0.08)=0.348 → 低于0.35 过滤
        ), false);
        List<Memory> filtered = service.filterAboveThreshold(
                List.of(m1, m2, m3), recall, MemoryRecallProbabilityService.DEFAULT_THRESHOLD);
        assertEquals(List.of("m1", "m2"), filtered.stream().map(Memory::getId).toList(),
                "应过滤 m3 且按激活降序 m1,m2");
    }

    @Test
    void noActivationUsesSalienceFallback() {
        Memory m1 = mem("m1", 0.9, 0.9, "a");   // salience 0.81 ≥ 0.35
        Memory m2 = mem("m2", 0.2, 0.2, "b");   // salience 0.04 < 0.35
        List<Memory> filtered = service.filterAboveThreshold(List.of(m1, m2), null,
                MemoryRecallProbabilityService.DEFAULT_THRESHOLD);
        assertEquals(1, filtered.size(), "无激活信息时用显著性兜底过滤");
        assertEquals("m1", filtered.get(0).getId());
    }
}
