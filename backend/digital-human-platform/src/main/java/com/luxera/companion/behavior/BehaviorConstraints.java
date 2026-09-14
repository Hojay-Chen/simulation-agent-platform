package com.luxera.companion.behavior;

import java.util.List;

/**
 * 行为约束(设计文档 §13/§20): 编码"什么情境不该做什么",
 * 让行为来自 State/Emotion/Relationship, 而非随机。
 */
public final class BehaviorConstraints {

    private static final List<String> LOW_ADVICE_EMOTIONS = List.of("sad", "anxious", "tired", "lonely");
    private static final List<String> LOW_TEASE_EMOTIONS = List.of("sad", "angry", "anxious", "tired");
    private static final List<String> INTIMATE_STAGES = List.of("close", "deeply_connected");

    private BehaviorConstraints() {}

    /** 情绪低落时不要急着给建议 */
    public static boolean canAdvice(String emotion) {
        return emotion == null || !LOW_ADVICE_EMOTIONS.contains(emotion);
    }

    /** 情绪低落/愤怒时不要调侃 */
    public static boolean canTease(String emotion) {
        return emotion == null || !LOW_TEASE_EMOTIONS.contains(emotion);
    }

    /** 对方低落时少追问, 最多一个开放问题 */
    public static boolean shouldAskGently(String emotion) {
        return emotion != null && LOW_ADVICE_EMOTIONS.contains(emotion);
    }

    /** 低能量/高压力 → 主动与篇幅收敛(关系摩擦) */
    public static double initiativeMultiplier(double energy, double stress) {
        return (energy > 0.6 && stress < 0.5) ? 1.0 : 0.7;
    }

    /** 关系越亲密越可以分享自我 */
    public static boolean canShareSelf(String relationshipStage) {
        return relationshipStage != null && INTIMATE_STAGES.contains(relationshipStage);
    }
}
