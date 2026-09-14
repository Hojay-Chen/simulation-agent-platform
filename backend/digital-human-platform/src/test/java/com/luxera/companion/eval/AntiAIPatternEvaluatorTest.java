package com.luxera.companion.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §71/§72 Anti-AI Pattern Evaluation 测试:
 * - 正常行为 → 无反 AI 模式, 高真人感
 * - 每次秒回 → 检测到
 * - 从不忽略/延迟 → 检测到"永远积极回应"
 * - 从不主动结束 → 检测到
 * - 从不遗忘 → 检测到
 * - 主动消息过多 → 检测到
 * - 回复过长 → 检测到
 */
class AntiAIPatternEvaluatorTest {

    private final AntiAIPatternEvaluator evaluator = new AntiAIPatternEvaluator();

    @Test
    void healthyBehaviorPasses() {
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                100, 10, 15, 5, 8, 6, 20, 3, 45.0);
        var r = evaluator.evaluate(stats);
        assertTrue(r.passes(), "正常行为不应触发反 AI 模式: " + r.summary());
        assertTrue(r.humanLikenessScore() > 0.8, "真人感应较高");
    }

    @Test
    void instantReplyPatternDetected() {
        // 100 条回复, 95 条秒回 → 秒回比例 95% > 80%
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                100, 95, 2, 1, 5, 3, 10, 1, 50.0);
        var r = evaluator.evaluate(stats);
        assertFalse(r.passes());
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("秒回")),
                "应检测到秒回模式: " + r.summary());
    }

    @Test
    void alwaysResponsivePatternDetected() {
        // 从不忽略/延迟 + 大量消息
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                50, 5, 0, 0, 2, 1, 0, 0, 30.0);
        var r = evaluator.evaluate(stats);
        assertFalse(r.passes());
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("从不忽略")),
                "应检测到永远积极回应");
    }

    @Test
    void neverEndsPatternDetected() {
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                40, 4, 5, 2, 3, 0, 3, 1, 40.0);
        var r = evaluator.evaluate(stats);
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("主动结束")),
                "应检测到永远不主动结束");
    }

    @Test
    void neverForgetsPatternDetected() {
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                50, 5, 8, 3, 4, 2, 5, 0, 50.0);
        var r = evaluator.evaluate(stats);
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("记得所有上下文")),
                "应检测到永远记得上下文");
    }

    @Test
    void proactiveSpamPatternDetected() {
        // 主动消息 80 条 vs 回复 20 条 → 比例 80% > 50%
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                20, 2, 3, 1, 80, 2, 4, 1, 40.0);
        var r = evaluator.evaluate(stats);
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("主动消息过多")),
                "应检测到主动消息过多");
    }

    @Test
    void overlyLongRepliesPatternDetected() {
        var stats = new AntiAIPatternEvaluator.BehaviorStats(
                20, 2, 3, 1, 2, 1, 3, 1, 200.0);
        var r = evaluator.evaluate(stats);
        assertTrue(r.aiPatterns().stream().anyMatch(p -> p.contains("都很完整")),
                "应检测到回复过长");
    }
}
