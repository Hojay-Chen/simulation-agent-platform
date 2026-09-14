package com.luxera.companion.runtime.agent.emotion;

import com.luxera.companion.memory.Memory;
import com.luxera.companion.state.AgentState;

import java.util.List;

/**
 * Emotion Agent 输入(§16): 消息 + 近况 + 关系 + 当前情绪 + 活动 + 注意力 + 人格 + 记忆候选。
 * 由 Context Builder 组装, Agent 不允许自己乱查数据库。
 */
public record EmotionContext(
        String companionId,
        String userId,
        String messageId,
        String messageText,
        List<String> recentConversation,
        String relationshipStage,
        double closeness,
        double trust,
        AgentState currentEmotion,
        String activityDesc,
        String availability,
        double attentionFocus,
        double attentionPhoneAwareness,
        String personalitySummary,
        List<Memory> memoryCandidates,
        String perceptionIntent,
        String perceptionEmotion) {
}
