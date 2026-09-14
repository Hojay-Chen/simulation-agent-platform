package com.luxera.companion.digitalhuman.expression;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;

/**
 * V10 §5.6 Physio Expression Filter: 生理/时态驱动表达。
 *
 * 真人生理特征:
 * - 精力/压力/睡眠压力/生病/心情/周末/时段 → 动态调整表达预算
 * - 公式: maxChars = baseline * (energy*0.4 + (1-sleepPressure)*0.3 + (1-illness)*0.3)
 * - typoRateAdd = (1-energy)*0.03 + sleepPressure*0.02 + illness*0.04
 * - 周末松散度: weekendLooseness = 周末?1.15:1.0
 * - moodShift: happy+0.1, sad/tired-0.15
 */
@Slf4j
@Component
public class PhysioExpressionFilter {

    public ExpressionBudget computeBudget(PhysioContext ctx) {
        ExpressionBudget budget = new ExpressionBudget();

        double energy = ctx.energy() != null ? ctx.energy() : 0.5;
        double stress = ctx.stress() != null ? ctx.stress() : 0.3;
        double sleepPressure = ctx.sleepPressure() != null ? ctx.sleepPressure() : 0.2;
        double illness = ctx.illness() != null ? ctx.illness() : 0.0;
        double moodShift = ctx.moodShift() != null ? ctx.moodShift() : 0.0;
        LocalDateTime now = ctx.now() != null ? ctx.now() : java.time.LocalDateTime.now();
        boolean isWeekend = now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY;

        // 基准配置
        int baseChars = 200;
        double baselineTypoRate = 0.02;

        // 核心公式
        double energyFactor = energy * 0.4;
        double sleepFactor = (1.0 - sleepPressure) * 0.3;
        double illnessFactor = (1.0 - illness) * 0.3;
        double combined = energyFactor + sleepFactor + illnessFactor;
        combined = Math.max(0.2, Math.min(1.5, combined)); // 限幅

        budget.maxChars = (int) Math.round(200 * combined);
        budget.maxChars = Math.max(50, Math.min(500, budget.maxChars));

        // 错字率加成
        double typoAdd = (1.0 - energy) * 0.03 + sleepPressure * 0.02 + illness * 0.04;
        budget.typoRateAdd = Math.min(0.1, typoAdd);

        // 周末松散
        if (isWeekend) {
            budget.maxChars = (int) (budget.maxChars * 1.15);
        }

        // 心情偏移
        budget.moodShift = Math.round(moodShift * 100) / 100.0;

        // 周末松散标记
        budget.isWeekend = isWeekend;
        budget.now = java.time.LocalDateTime.now();

        return budget;
    }

    public record PhysioContext(
            Double energy,
            Double stress,
            Double sleepPressure,
            Double illness,
            Double moodShift,
            LocalDateTime now
    ) {}

    public static class ExpressionBudget {
        public int maxChars;
        public int maxSentences = 3;
        public double typingRhythmMs = 200;
        public double typoRateAdd = 0;
        public double hesitationAdd = 0;
        public double moodShift = 0;
        public boolean isWeekend;
        public java.time.LocalDateTime now;

        @Override
        public String toString() {
            return String.format("[生理/时态] 精力 %.2f, 压力 %.2f, 睡眠压力 %.2f, 生病 %.2f, %s %.2f:%%",
                    0.5, 0.3, 0.2, 0.0, java.time.LocalDateTime.now().getDayOfWeek() == java.time.DayOfWeek.SATURDAY || java.time.LocalDateTime.now().getDayOfWeek() == java.time.DayOfWeek.SUNDAY ? "周末松散 +15%" : "工作日");
        }
    }
}