package com.luxera.companion.runtime.agent.emotion;

import com.luxera.companion.runtime.EmotionDelta;

import java.util.List;

/**
 * Emotion Agent 输出(§17): 结构化 JSON。
 * Appraisal(意义/期待违背/意图) + Delta(方向与程度) + MemoryTriggers(激活记忆) + Confidence + Reason。
 * 不是简单的 emotion label —— Runtime 需要知道"为什么/变化多少/是否确定/哪些记忆参与"。
 */
public record EmotionAppraisalResult(
        Appraisal appraisal,
        EmotionDelta delta,
        List<MemoryTrigger> memoryTriggers,
        double confidence,
        String reason,
        boolean fallback) {

    public record Appraisal(String target, String eventMeaning,
                            double expectationViolation, String perceivedIntent) {
        public static final Appraisal EMPTY = new Appraisal("none", "", 0, "uncertain");
    }

    public record MemoryTrigger(String memoryId, double activation, String reason) {
    }

    public static EmotionAppraisalResult neutral(String reason, boolean fallback) {
        return new EmotionAppraisalResult(Appraisal.EMPTY, EmotionDelta.NEUTRAL,
                List.of(), 0.1, reason, fallback);
    }
}
