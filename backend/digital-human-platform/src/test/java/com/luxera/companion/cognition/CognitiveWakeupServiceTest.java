package com.luxera.companion.cognition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §22-§23 Cognitive Wakeup 测试:
 * - 普通消息("哈哈") → MICRO_WAKE(不调 LLM)
 * - 强烈情绪(被裁/难过) → DELIBERATION/DEEP_THINKING
 * - 低价值通知 → NO_WAKE
 * - requiresCognition 判断是否需 LLM
 */
class CognitiveWakeupServiceTest {

    private final CognitiveWakeupService service = new CognitiveWakeupService();

    @Test
    void casualMessageIsMicroWake() {
        var level = service.evaluate(new CognitiveWakeupService.WakeupInput(
                "MESSAGE", "哈哈", 0.2, 0.3, 0.1, 0.1, 0.2));
        assertEquals(CognitiveWakeupService.WakeLevel.MICRO_WAKE, level);
        assertFalse(service.requiresCognition(level), "普通消息不应唤醒 LLM");
    }

    @Test
    void distressMessageIsDeliberation() {
        var level = service.evaluate(new CognitiveWakeupService.WakeupInput(
                "MESSAGE", "我今天被老板骂了,好难过", 0.6, 0.5, 0.7, 0.4, 0.8));
        assertEquals(CognitiveWakeupService.WakeLevel.DELIBERATION, level);
        assertTrue(service.requiresCognition(level), "强烈情绪应唤醒认知");
    }

    @Test
    void majorEventIsDeepThinking() {
        var level = service.evaluate(new CognitiveWakeupService.WakeupInput(
                "MESSAGE", "我失业了,公司把我裁了", 0.9, 0.8, 0.9, 0.5, 0.9));
        assertEquals(CognitiveWakeupService.WakeLevel.DEEP_THINKING, level);
    }

    @Test
    void lowValueNotificationIsNoWake() {
        var level = service.evaluate(new CognitiveWakeupService.WakeupInput(
                "NOTIFICATION", "优惠券到账", 0.1, 0.1, 0.05, 0, 0));
        assertEquals(CognitiveWakeupService.WakeLevel.NO_WAKE, level);
        assertFalse(service.requiresCognition(level));
    }

    @Test
    void highScoreComboReachesAttention() {
        // 中高重要性 + 情绪 + 社交 → ATTENTION(值得看一眼)
        var level = service.evaluate(new CognitiveWakeupService.WakeupInput(
                "MESSAGE", "你最近在忙什么呢", 0.7, 0.4, 0.3, 0.3, 0.6));
        assertEquals(CognitiveWakeupService.WakeLevel.ATTENTION, level);
        assertTrue(service.requiresCognition(level));
    }
}
