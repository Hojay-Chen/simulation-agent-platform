package com.luxera.companion.behavior;

/**
 * 行为决策(设计文档 §13.3): Runtime 决定"现在应该做什么", LLM 负责怎么说。
 */
public record BehaviorDecision(
        /** LISTEN/RESPOND/ASK/COMFORT/ADVISE/SHARE/TEASE/DISAGREE/SET_BOUNDARY/END_CONVERSATION/PROACTIVE_CONTACT/DO_NOTHING */
        String primaryAction,
        /** warm/reserved/playful/caring/neutral */
        String emotionalPosture,
        double initiative,
        boolean shouldAsk,
        boolean shouldAdvice,
        boolean shouldShareSelf,
        boolean shouldTease,
        boolean shouldDisagree,
        boolean shouldEndTopic,
        String proactiveCandidate,
        String reason,
        double confidence) {

    public static BehaviorDecision respond() {
        return new BehaviorDecision("RESPOND", "neutral", 0.5, false, false, false, false, false, false, null, "默认回应", 0.6);
    }

    public static BehaviorDecision nothing() {
        return new BehaviorDecision("DO_NOTHING", "neutral", 0, false, false, false, false, false, true, null, "无需要行动", 0.9);
    }

    public static BehaviorDecision proactive(String candidate, String reason, double confidence) {
        return new BehaviorDecision("PROACTIVE_CONTACT", "caring", 0.8, true, false, false, false, false, false,
                candidate, reason, confidence);
    }
}
