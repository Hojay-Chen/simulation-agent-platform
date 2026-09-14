package com.luxera.companion.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §50-§51 Behavioral Entropy 测试:
 * - 过于规律的样本(每天同一时刻) → tooRegular
 * - 有习惯 + 适度波动的样本 → 正常(真人)
 * - 方差过大(完全随机) → 不规律但非"机械"
 */
class BehavioralEntropyEvaluatorTest {

    private final BehavioralEntropyEvaluator evaluator = new BehavioralEntropyEvaluator();

    @Test
    void perfectlyRegularIsTooRegular() {
        // 连续 7 天 23:00 整入睡(方差 0) → 机械感
        var r = evaluator.evaluateTimes(List.of(23.0, 23.0, 23.0, 23.0, 23.0, 23.0, 23.0), "睡眠");
        assertTrue(r.tooRegular(), "每天完全同时刻 → 过于规律");
        assertTrue(r.summary().contains("过于规律"));
    }

    @Test
    void variedButTrendingIsHuman() {
        // 23:00, 23:15, 22:45, 23:30, 23:10, 22:50, 23:20 → 有习惯 + 波动
        var r = evaluator.evaluateTimes(
                List.of(23.0, 23.25, 22.75, 23.5, 23.17, 22.83, 23.33), "睡眠");
        assertFalse(r.tooRegular(), "有习惯均值 + 适度波动 → 符合真人");
        assertTrue(r.summary().contains("符合真人"));
    }

    @Test
    void insufficientSamplesNotAssessed() {
        var r = evaluator.evaluateTimes(List.of(23.0, 23.5), "睡眠");
        assertFalse(r.tooRegular(), "样本不足不应判定");
    }

    @Test
    void detectRegularityAcrossMultipleDimensions() {
        // 回复时间极其规律(秒回) + 睡眠有波动
        var patterns = evaluator.detectRegularity(
                List.of("回复", "睡眠"),
                List.of(
                        List.of(0.1, 0.1, 0.1, 0.1),              // 回复时间固定 → 过于规律
                        List.of(23.0, 23.2, 22.8, 23.1, 23.3)));  // 睡眠有波动
        assertEquals(1, patterns.size(), "只应检测到回复时间过于规律");
        assertTrue(patterns.get(0).contains("回复"));
    }
}
