package com.luxera.companion.runtime.agent.expression;

import java.util.List;

/**
 * Expression Agent 输出(§34): 表达策略 + 消息计划(分段/延迟)。
 * 表达是"怎么说", 与"说什么"分离 —— 文本由生成阶段填充。
 */
public record ExpressionResult(
        ExpressionStrategy strategy,
        List<MessageSegment> segments,
        boolean stopAfter,
        boolean fallback) {

    public record ExpressionStrategy(String tone, double directness, double warmth,
                                     double playfulness, double vulnerability) {
        public static final ExpressionStrategy NEUTRAL =
                new ExpressionStrategy("natural", 0.5, 0.5, 0.3, 0.2);
    }

    public record MessageSegment(String purpose, long delayMs, int maxChars, long typingDurationMs) {

        /** 兼容构造(默认打字时长由 TypingSimulation 填充) */
        public MessageSegment(String purpose, long delayMs, int maxChars) {
            this(purpose, delayMs, maxChars, 0);
        }
    }

    /** 单段回退(默认) */
    public static ExpressionResult single(boolean fallback) {
        return new ExpressionResult(ExpressionStrategy.NEUTRAL,
                List.of(new MessageSegment("reply", 0, 0)), true, fallback);
    }
}
