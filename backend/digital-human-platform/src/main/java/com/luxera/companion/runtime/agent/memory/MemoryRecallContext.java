package com.luxera.companion.runtime.agent.memory;

import com.luxera.companion.memory.Memory;

import java.util.List;

/**
 * Memory Agent 输入(§23): 第一阶段廉价检索的候选记忆 + 当前线索。
 * 第二阶段由 MemoryAgent 判断每个候选的"激活强度"(不是固定公式)。
 */
public record MemoryRecallContext(
        String companionId,
        String userId,
        String query,
        List<Memory> candidates,
        String personalitySummary,
        String relationshipStage,
        String currentEmotionSummary) {
}
