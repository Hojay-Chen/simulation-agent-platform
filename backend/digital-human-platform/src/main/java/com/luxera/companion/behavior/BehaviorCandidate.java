package com.luxera.companion.behavior;

import com.luxera.companion.proactive.ProactiveDecision;

/**
 * §三十五 行为候选: 一次"她此刻可能做什么"的候选。
 * score 为综合效用(价值 - 打断成本 + 关系/人格/随机修正)。
 */
public record BehaviorCandidate(
        BehaviorAction action,
        String trigger,          // 触发理由(人类可读)
        double score,            // 综合效用
        double baseUtility,      // 基础效用
        double interruptionCost, // 打断成本
        ProactiveDecision proactive // 仅 SEND_PROACTIVE_MESSAGE 携带(内容/价值/成本)
) {

    public static BehaviorCandidate of(BehaviorAction action, String trigger, double score) {
        return new BehaviorCandidate(action, trigger, score, score, 0, null);
    }

    public static BehaviorCandidate proactive(ProactiveDecision d, double score) {
        return new BehaviorCandidate(BehaviorAction.SEND_PROACTIVE_MESSAGE, d.title(), score,
                d.expectedValue(), d.interruptionCost(), d);
    }
}
