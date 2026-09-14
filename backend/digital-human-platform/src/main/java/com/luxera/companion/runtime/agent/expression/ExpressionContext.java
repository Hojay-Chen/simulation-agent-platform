package com.luxera.companion.runtime.agent.expression;

import com.luxera.companion.interaction.InteractionDecision;

import java.util.List;

/**
 * Expression Agent 输入(§33): Brain 已决定"要不要说/想表达什么", Expression 决定"怎么说/说几条/什么时候发"。
 */
public record ExpressionContext(
        String companionId,
        String userId,
        String messageText,
        String expressionGoal,
        String emotionSummary,
        String personalitySummary,
        String relationshipStage,
        double closeness,
        String activityDesc,
        List<String> recentConversation,
        double energy,
        double urgency,
        InteractionDecision baseline,
        String responseIntent) {
}
