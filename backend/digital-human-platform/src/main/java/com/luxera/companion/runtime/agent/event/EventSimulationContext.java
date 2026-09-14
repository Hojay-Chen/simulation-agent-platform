package com.luxera.companion.runtime.agent.event;

import com.luxera.companion.memory.Memory;

import java.util.List;

/**
 * Event Simulation Agent 输入(§27): 当前活动 + 环境 + 人格 + 情绪 + 相关记忆 + 关系 + 时间 + 近期事件历史。
 */
public record EventSimulationContext(
        String companionId,
        String activityDesc,
        String environmentDesc,
        String personalitySummary,
        double energy,
        double stress,
        String currentEmotionSummary,
        List<Memory> relevantMemories,
        String relationshipStage,
        String timeDesc,
        List<String> recentEventTypes) {
}
