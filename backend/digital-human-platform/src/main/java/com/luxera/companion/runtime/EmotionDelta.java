package com.luxera.companion.runtime;

/**
 * 情绪增量(§17): Emotion Agent 的结构化输出之一。
 * 不是"情绪标签", 而是方向和程度 —— 由 StateReducer 应用到状态。
 * 语义: 正值增加该情绪, 负值缓解该情绪。
 */
public record EmotionDelta(double anger, double hurt, double sadness,
                           double warmth, double anxiety) {

    public static final EmotionDelta NEUTRAL = new EmotionDelta(0, 0, 0, 0, 0);

    public static EmotionDelta of(double anger, double hurt, double sadness,
                                  double warmth, double anxiety) {
        return new EmotionDelta(
                clamp(anger), clamp(hurt), clamp(sadness),
                clamp(warmth), clamp(anxiety));
    }

    public EmotionDelta scale(double factor) {
        return new EmotionDelta(anger * factor, hurt * factor, sadness * factor,
                warmth * factor, anxiety * factor);
    }

    public boolean isEmpty() {
        return Math.abs(anger) < 1e-9 && Math.abs(hurt) < 1e-9 && Math.abs(sadness) < 1e-9
                && Math.abs(warmth) < 1e-9 && Math.abs(anxiety) < 1e-9;
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
