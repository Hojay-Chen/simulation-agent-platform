package com.luxera.companion.digitalhuman.perception;

/**
 * PerceptionResult: 感知输出 —— 等级 + 原始评分 + 生效策略。
 */
public record PerceptionResult(PerceptionLevel level, double score, String strategy) {

    public static PerceptionResult of(PerceptionLevel level, double score, String strategy) {
        return new PerceptionResult(level, score, strategy);
    }

    /** 是否进入 Awareness(感知到) */
    public boolean perceived() {
        return level != PerceptionLevel.NONE;
    }

    /** 是否足以触发认知 */
    public boolean triggersCognition() {
        return level.triggersCognition();
    }
}
