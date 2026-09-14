package com.luxera.companion.digitalhuman.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * V10 §5.1 Typing Rhythm Engine: 真实的打字节奏与延迟分布。
 *
 * 真人打字特征:
 * - 思考间隔: 消息生成后 200-1500ms 才有首字符
 * - 字符级速度: 中文 25-40 字/分钟, 英文 60-80 wpm, 按 persona 浮动
 * - 回改行为: 每 ~80 字出现一次 backspace 修正(真实感)
 * - 段间隔修正: ×(1.0 + stress*0.5 - intimacy*0.2) —— 紧张时慢, 熟悉时快
 */
@Slf4j
@Component
public class TypingRhythmEngine {

    /** 计算完整的打字计划(输入为简单原语, 便于单测) */
    public TypingPlan computePlan(double stress, double intimacy, boolean chineseDominant) {
        TypingPlan plan = new TypingPlan();
        plan.thinkingDelayMs = computeThinkingDelay(stress, intimacy);
        plan.charactersPerMinute = computeCpm(chineseDominant);
        plan.typoCorrectionInterval = computeTypoCorrectionInterval();
        plan.segmentIntervalMultiplier = computeSegmentMultiplier(stress, intimacy);
        return plan;
    }

    private long computeThinkingDelay(double stress, double intimacy) {
        long base = ThreadLocalRandom.current().nextLong(200, 1501);
        double multiplier = 1.0 + stress * 0.5 - intimacy * 0.2; // 紧张慢, 熟悉快
        return Math.round(base * multiplier);
    }

    private int computeCpm(boolean chineseDominant) {
        int baseCpm = chineseDominant
                ? ThreadLocalRandom.current().nextInt(25, 41)
                : ThreadLocalRandom.current().nextInt(60, 81);
        return Math.max(10, baseCpm);
    }

    private int computeTypoCorrectionInterval() {
        return ThreadLocalRandom.current().nextInt(60, 101);
    }

    private double computeSegmentMultiplier(double stress, double intimacy) {
        return 1.0 + stress * 0.5 - intimacy * 0.2;
    }

    public static class TypingPlan {
        public long thinkingDelayMs;
        public int charactersPerMinute;
        public int typoCorrectionInterval;
        public double segmentIntervalMultiplier;
    }
}