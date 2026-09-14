package com.luxera.companion.runtime.agent.brain;

import com.luxera.companion.behavior.Drives;
import com.luxera.companion.interaction.InteractionDecision;

import java.util.List;

/**
 * Brain Agent 输入(§41): 世界状态摘要 + 消息 + 情绪摘要 + Drives + 关系 + 待办想法。
 * 所有都是摘要/特征, 不让 Brain 自己乱查数据库。
 * {@code baseline} 是规则基准决策(由 Runtime 用 InteractionPolicyEngine 计算),
 * 作为 LLM 失败时的回退 + 回复预算来源。
 */
public record BrainContext(
        String companionId,
        String userId,
        String messageId,
        String messageText,
        List<String> recentConversation,
        String activityDesc,
        String availability,
        double energy,
        double stress,
        double socialEnergy,
        double hurt,
        double anger,
        double sadness,
        double anxiety,
        double warmth,
        String mood,
        double noticeProbability,
        double inspectProbability,
        boolean phoneNearby,
        String phoneMode,
        String relationshipStage,
        double closeness,
        String perceptionIntent,
        String perceptionEmotion,
        Drives drives,
        boolean messageChecked,
        boolean hasPendingThoughts,
        InteractionDecision baseline) {
}
