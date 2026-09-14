package com.luxera.companion.runtime;

import com.luxera.companion.state.AgentState;
import org.springframework.stereotype.Component;

/**
 * 情绪状态归约器(§61/§99 + §48/§49): EmotionDelta → AgentState。
 * 唯一允许修改情绪维度的入口。所有情绪变化必须经过这里, 可追踪。
 *
 * 增强:
 * - 状态惯性(§48): Emotion(t+1) = Emotion(t) + EventImpact - NaturalDecay,
 *   情绪不会因为事件结束就瞬间归零, 而是自然衰减。
 * - 边际递减(§49): 已有负面情绪很高时, 新的同向冲击影响递减(人不至于无限愤怒),
 *   同时允许多维叠加(开心+生气, 喜欢+委屈)。
 * - 身体状态(§50): 困倦/饥饿/身体不适会放大负面情绪, 抑制正面情绪。
 */
@Component
public class EmotionReducer implements StateReducer<EmotionDelta, AgentState> {

    /** 边际递减: 已有情绪越高, 新冲击权重越低 */
    private static final double DIMINISHING = 0.6;
    /** 身体状态对情绪的放大/抑制 */
    private static final double BODY_AMPLIFY = 0.35;

    @Override
    public AgentState apply(AgentState state, EmotionDelta delta) {
        if (state == null || delta == null || delta.isEmpty()) return state;

        // 身体状态调节: 困/饿/不舒服 → 负面冲击被放大, 正面被抑制
        double bodyNegative = Math.max(0, state.getSleepiness() * 0.5
                + state.getHunger() * 0.3 + state.getPhysicalDiscomfort() * 0.4);
        double negativeAmp = 1 + bodyNegative * BODY_AMPLIFY;
        double positiveAmp = 1 - bodyNegative * 0.3;

        // §49 边际递减: 已有情绪越高, 同向新冲击越小
        double hurtDelta = delta.hurt() * diminishing(state.getHurt()) * negativeAmp;
        double angerDelta = delta.anger() * diminishing(state.getAnger()) * negativeAmp;
        double sadDelta = delta.sadness() * diminishing(state.getSadness()) * negativeAmp;
        double anxietyDelta = delta.anxiety() * diminishing(state.getAnxiety()) * negativeAmp;
        double warmthDelta = delta.warmth() * positiveAmp;

        state.setAnger(clamp(state.getAnger() + angerDelta));
        state.setHurt(clamp(state.getHurt() + hurtDelta));
        state.setSadness(clamp(state.getSadness() + sadDelta));
        state.setAnxiety(clamp(state.getAnxiety() + anxietyDelta));
        state.setWarmth(clamp(state.getWarmth() + warmthDelta));

        // §49 情绪叠加: 正面情绪维度(joy/affection)与负面并存
        if (warmthDelta > 0) {
            state.setJoy(clamp(state.getJoy() + warmthDelta));
            state.setAffection(clamp(state.getAffection() + warmthDelta * 0.8));
            state.setLoneliness(clamp(state.getLoneliness() - warmthDelta * 0.5));
        }
        // 负面事件也加深孤独感(被忽视/被否定)
        double negativeTotal = hurtDelta + angerDelta + sadDelta + anxietyDelta;
        if (negativeTotal > 0) {
            state.setLoneliness(clamp(state.getLoneliness() + negativeTotal * 0.3));
        }

        // 压力联动: 高负面情绪 → 压力上升; 温暖 → 压力略降
        double negative = Math.max(0, angerDelta * 0.3 + hurtDelta * 0.25 + sadDelta * 0.2 + anxietyDelta * 0.3);
        double comfort = Math.max(0, warmthDelta);
        state.setStress(clamp(state.getStress() + negative - comfort * 0.15));

        // 身体状态联动: 困倦/不适 → 压力略升
        state.setStress(clamp(state.getStress() + bodyNegative * 0.05));

        // 情绪主导 mood
        state.setMood(dominantMood(state));
        return state;
    }

    /** 边际递减: 已有情绪越高, 新冲击权重越低 */
    private static double diminishing(double current) {
        return 1 - current * DIMINISHING;
    }

    private static String dominantMood(AgentState s) {
        double hurt = s.getHurt(), anger = s.getAnger(), sad = s.getSadness(),
                anxiety = s.getAnxiety(), warmth = s.getWarmth();
        if (anger > 0.35 && anger >= hurt && anger >= sad) return "有点生气";
        if (hurt > 0.35 && hurt >= sad && hurt >= anxiety) return "有点受伤";
        if (sad > 0.35 && sad >= anxiety) return "有点难过";
        if (anxiety > 0.35) return "有点不安";
        if (warmth > 0.4 && hurt < 0.2 && anger < 0.2) return "平静的";
        if (hurt < 0.15 && anger < 0.15 && sad < 0.15 && anxiety < 0.15) return "平静的";
        return s.getMood() != null ? s.getMood() : "平静的";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
