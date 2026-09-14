package com.luxera.companion.digitalhuman.expression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 §5 拟人化引擎单元测试(纯单元, 无 Spring)。
 */
class HumanLikenessEnginesTest {

    private final TypoEngine typoEngine = new TypoEngine();

    @Test
    void typoEnginePreservesNonChinese() {
        // 英文/数字/标点不产生错字
        String result = typoEngine.applyTypos("hello 123 !!!", new TypoEngine.TypoContext(0.5, 0.2, 0.2, 0.2));
        assertEquals("hello 123 !!!", result, "非中文字符不应产生错字");
    }

    @Test
    void typoEngineWithZeroRateReturnsUnchanged() {
        String original = "我今天很开心";
        String result = typoEngine.applyTypos(original, new TypoEngine.TypoContext(0.0, 1.0, 0.0, 0.0));
        assertEquals(original, result, "错字率为 0 时应原样返回");
    }

    @Test
    void typosOnlyChangeChineseChars() {
        String original = "我今天很开心";
        String result = typoEngine.applyTypos(original, new TypoEngine.TypoContext(0.3, 0.2, 0.5, 0.5));
        assertNotNull(result);
        // 长度可能少(漏字)但不会多
        assertTrue(result.length() <= original.length());
    }

    @Test
    void hesitationEngineRespectsNoticeLevel() {
        HesitationEngine engine = new HesitationEngine();
        // 高 notice level(1.0) → 极低概率犹豫
        var low = engine.computeHesitation(new HesitationEngine.HesitationContext(1.0, 1.0, "小满"));
        // 低 notice level(0.1) → 高概率犹豫(多次采样至少一次出现标志)
        boolean any = false;
        for (int i = 0; i < 50; i++) {
            var m = engine.computeHesitation(new HesitationEngine.HesitationContext(0.1, 0.1, "小满"));
            if (m.sentenceEnd != null || m.sentenceStart != null || m.weakening != null) any = true;
        }
        assertTrue(any, "低 notice/confidence 时应高频出现犹豫标志");
    }

    @Test
    void physioFilterComputesBudget() {
        PhysioExpressionFilter filter = new PhysioExpressionFilter();
        // 高精力低压力 → 较长回复
        var energetic = filter.computeBudget(new PhysioExpressionFilter.PhysioContext(
                1.0, 0.0, 0.0, 0.0, 0.0, java.time.LocalDateTime.of(2026, 9, 9, 15, 0)));
        // 低精力高睡眠压力 → 较短回复
        var tired = filter.computeBudget(new PhysioExpressionFilter.PhysioContext(
                0.1, 0.5, 0.9, 0.0, -0.15, java.time.LocalDateTime.of(2026, 9, 9, 15, 0)));
        assertTrue(energetic.maxChars > tired.maxChars, "精力高时回复应更长");
        assertTrue(tired.typoRateAdd > energetic.typoRateAdd, "疲惫时错字率应更高");
    }

    @Test
    void physioFilterWeekendLooseness() {
        PhysioExpressionFilter filter = new PhysioExpressionFilter();
        var weekday = filter.computeBudget(new PhysioExpressionFilter.PhysioContext(
                0.5, 0.3, 0.2, 0.0, 0.0, java.time.LocalDateTime.of(2026, 9, 9, 15, 0)));  // 周三
        var weekend = filter.computeBudget(new PhysioExpressionFilter.PhysioContext(
                0.5, 0.3, 0.2, 0.0, 0.0, java.time.LocalDateTime.of(2026, 9, 12, 15, 0)));  // 周六
        assertTrue(weekend.isWeekend);
        assertFalse(weekday.isWeekend);
        assertTrue(weekend.maxChars > weekday.maxChars, "周末回复应更松散");
    }
}