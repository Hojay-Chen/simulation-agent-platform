package com.luxera.companion.runtime.agent.expression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §56/§57 消息级时间模型测试:
 * - 短消息打字时长短, 复杂消息长
 * - 情绪复杂度增加打字时长
 * - 消息间间隔随段号/复杂度变化
 */
class TypingSimulationTest {

    private final TypingSimulationService typing = new TypingSimulationService();

    @Test
    void shortMessageHasShortTyping() {
        long d = typing.typingDurationMs("哈哈", 0.1);
        assertTrue(d >= 800 && d <= 2000, "短消息打字应 0.8~2s, 实际 " + d);
    }

    @Test
    void longMessageHasLongerTyping() {
        long shortD = typing.typingDurationMs("嗯", 0.1);
        long longD = typing.typingDurationMs("我今天想了很多, 我觉得这件事不能这么简单地看, 但我也不知道怎么跟你说。", 0.5);
        assertTrue(longD > shortD, "长消息打字应更久");
        assertTrue(longD <= 8000, "不应超过 8s 上限");
    }

    @Test
    void emotionalComplexityIncreasesTyping() {
        long calm = typing.typingDurationMs("好的知道了", 0.1);
        long emotional = typing.typingDurationMs("好的知道了", 0.9);
        assertTrue(emotional > calm, "情绪复杂时打字更慢(边想边说)");
    }

    @Test
    void messageGapIncreasesWithIndexAndComplexity() {
        long first = typing.messageGapMs(0.1, 0);
        long second = typing.messageGapMs(0.1, 1);
        assertTrue(second >= first, "后续消息间隔应 ≥ 第一条");
        long complex = typing.messageGapMs(0.9, 1);
        assertTrue(complex >= second, "复杂度高 → 间隔更长");
        assertTrue(complex <= 4000, "间隔上限 4s");
    }

    @Test
    void shortReplyIsQuick() {
        long d = typing.shortReplyDurationMs();
        assertTrue(d >= 900 && d <= 1500, "单句轻快回复应 0.9~1.5s");
    }
}
