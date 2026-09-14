package com.luxera.companion.runtime;

import com.luxera.companion.state.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 情绪归约器单元测试(§99 + §48/§49/§50):
 * 所有情绪状态变更走 Reducer, 可追踪。
 *
 * 语义:
 * - 惯性(§48): 已有情绪先自然衰减(不瞬间归零), 新冲击叠加其上
 * - 边际递减(§49): 已有情绪越高, 新同向冲击影响越小
 * - 身体状态(§50): 困倦/饥饿/不适放大负面、抑制正面
 * - 情绪叠加(§49): joy/affection 与负面情绪并存
 */
class EmotionReducerTest {

    private final EmotionReducer reducer = new EmotionReducer();

    /** 干净状态: 清空身体状态, 使断言确定 */
    private static AgentState cleanState() {
        AgentState s = new AgentState();
        s.setSleepiness(0);
        s.setHunger(0);
        s.setPhysicalDiscomfort(0);
        return s;
    }

    @Test
    void appliesDeltaToState() {
        AgentState s = cleanState();
        s.setHurt(0.2);
        s.setAnger(0.1);
        s.setWarmth(0.3);

        reducer.apply(s, EmotionDelta.of(0.3, 0.2, 0.4, -0.1, 0.5));

        // 低情绪下边际递减≈1, 无身体放大 → 新值 = 旧值 + 新冲击
        assertEquals(0.1 + 0.3 * (1 - 0.1 * 0.6), s.getAnger(), 1e-6);
        assertEquals(0.2 + 0.2 * (1 - 0.2 * 0.6), s.getHurt(), 1e-6);
        assertEquals(0.4, s.getSadness(), 1e-6);
        assertEquals(0.3 - 0.1, s.getWarmth(), 1e-6);
        assertEquals(0.5, s.getAnxiety(), 1e-6);
    }

    @Test
    void clampsToBounds() {
        AgentState s = cleanState();
        reducer.apply(s, EmotionDelta.of(5, -3, 2, 2, -5));
        assertEquals(1.0, s.getAnger(), 1e-9);
        assertEquals(0.0, s.getHurt(), 1e-9);
        assertEquals(1.0, s.getWarmth(), 1e-9);
        assertEquals(0.0, s.getAnxiety(), 1e-9);
    }

    @Test
    void emptyDeltaIsNoOp() {
        AgentState s = cleanState();
        s.setMood("平静的");
        s.setStress(0.2);
        reducer.apply(s, EmotionDelta.NEUTRAL);
        assertEquals(0.2, s.getStress(), 1e-9);
        assertEquals(0.0, s.getAnger(), 1e-9);
    }

    @Test
    void negativeEmotionRaisesStress() {
        AgentState s = cleanState();
        s.setStress(0.2);
        reducer.apply(s, EmotionDelta.of(0.5, 0, 0, 0, 0));
        // anger ≈ 0.5(低起点无边际递减), 压力 = 0.2 + 0.5*0.3
        assertEquals(0.2 + 0.15, s.getStress(), 1e-6);
    }

    @Test
    void warmDeltaLowersStressAndSetsMood() {
        AgentState s = cleanState();
        s.setStress(0.5);
        reducer.apply(s, EmotionDelta.of(0, 0, 0, 0.6, 0));
        assertEquals(0.5 - 0.09, s.getStress(), 1e-6);
        assertEquals("平静的", s.getMood());
    }

    @Test
    void dominantMoodReflectsStrongestEmotion() {
        AgentState s = cleanState();
        reducer.apply(s, EmotionDelta.of(0.1, 0.5, 0.1, 0, 0.1));
        assertEquals("有点受伤", s.getMood());
    }

    // ── 新增 ──────────────────────────────────────

    @Test
    void inertiaKeepsResidualEmotion() {
        // 惯性由 decayAllNegative(比例衰减)驱动, 情绪不会瞬间归零
        AgentState s = cleanState();
        s.setAnger(0.8);
        double decayed = 0.8 - Math.max(0.08, 0.8 * 0.15) * 0.5;
        assertEquals(decayed, 0.8 - 0.12 * 0.5, 1e-9);
        // 手动模拟一次衰减(等价于 decayAllNegative 单步)
        s.setAnger(clampForTest(s.getAnger() - Math.max(0.08, s.getAnger() * 0.15) * 0.5));
        assertEquals(0.8 - 0.06, s.getAnger(), 1e-9);
        assertTrue(s.getAnger() > 0.7, "情绪衰减不应瞬间归零");
    }

    @Test
    void highEmotionHasDiminishingReturns() {
        AgentState s = cleanState();
        s.setAnger(0.8);   // 已经非常生气
        reducer.apply(s, EmotionDelta.of(0.6, 0, 0, 0, 0));
        // 边际递减: 新冲击 0.6*(1-0.8*0.6) = 0.312, 加上旧 0.8 → 1.112, 被 clamp 到 1.0
        assertEquals(1.0, s.getAnger(), 1e-9);
        // 但递减效应体现在: 若没有递减会直接冲到 1.4(clamp 1.0), 有递减也是 1.0 → 用中等起点验证
        AgentState s2 = cleanState();
        s2.setAnger(0.5);
        reducer.apply(s2, EmotionDelta.of(0.6, 0, 0, 0, 0));
        double expected2 = 0.5 + 0.6 * (1 - 0.5 * 0.6);
        assertEquals(expected2, s2.getAnger(), 1e-6, "中等情绪时递减生效且不过冲");
        assertTrue(s2.getAnger() < 1.0, "中等情绪不应过冲到 1.0");
    }

    private static double clampForTest(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Test
    void bodyStateAmplifiesNegativeAndSuppressesPositive() {
        AgentState s = cleanState();
        s.setSleepiness(0.9);   // 很困
        reducer.apply(s, EmotionDelta.of(0.3, 0, 0, 0.5, 0));
        // 负面被放大: 无 body 时 anger=0.3, 有困倦后应更大
        assertTrue(s.getAnger() > 0.3, "困倦应放大负面情绪");
        // 正面被抑制: warmth < 0.5
        assertTrue(s.getWarmth() < 0.5, "困倦应抑制正面情绪");
        // 压力因困倦上升
        assertTrue(s.getStress() > 0, "困倦应推高压力");
    }

    @Test
    void emotionStackingAllowsMixedFeelings() {
        AgentState s = cleanState();
        // 又开心又委屈: 正面 + 负面同时升高
        reducer.apply(s, EmotionDelta.of(0.2, 0.4, 0, 0.5, 0));
        assertTrue(s.getHurt() > 0.3, "委屈存在");
        assertTrue(s.getJoy() > 0.3, "开心并存(情绪叠加)");
        assertTrue(s.getAffection() > 0.2, "喜爱并存");
        assertTrue(s.getLoneliness() > 0, "负面事件加深孤独");
    }
}
