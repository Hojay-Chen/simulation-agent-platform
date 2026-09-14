package com.luxera.companion.eval;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * §50-§51 Behavioral Entropy: 行为熵。
 * 真人不是没有规律, 而是规律不会直接决定每一次行为。
 *
 * 判断"是否过于规律":
 * - 回复时间方差过小(每天同一秒回) → 机械感
 * - 睡眠时间方差过小(每天固定 23:00 睡) → 机械感
 * - 但也不能无限随机(方差过大 → 不稳定)
 *
 * 目标区间: 有明显习惯均值 + 适度方差(±30~90 分钟)。
 */
@Component
public class BehavioralEntropyEvaluator {

    public record BehaviorSamples(
            List<Double> responseDelays,     // 每次回复延迟(分钟)
            List<Double> sleepBedtimes,      // 每天入睡时间(小时, 0-24)
            List<Double> proactiveTimes,     // 每次主动消息时间(小时)
            List<Double> replyLengths) {     // 每次回复长度(字)
    }

    /** 熵评估结果 */
    public record EntropyResult(double variance, String summary, boolean tooRegular) {}

    private static final double MIN_VARIANCE = 1.0;      // 最小方差(分钟): 低于此 = 过于规律
    private static final double MAX_VARIANCE = 180;      // 最大方差(分钟): 高于此 = 太不稳定

    /** 评估一组时间样本的方差(分钟), 判断是否过于规律 */
    public EntropyResult evaluateTimes(List<Double> timesInHours, String label) {
        if (timesInHours == null || timesInHours.size() < 3) {
            return new EntropyResult(0, label + ": 样本不足(" + (timesInHours == null ? 0 : timesInHours.size()) + "), 无法评估", false);
        }
        double mean = timesInHours.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = timesInHours.stream()
                .mapToDouble(t -> Math.pow(t - mean, 2))
                .average().orElse(0);
        double stdDevMinutes = Math.sqrt(variance) * 60;

        String summary;
        boolean tooRegular;
        if (stdDevMinutes < MIN_VARIANCE) {
            tooRegular = true;
            summary = String.format("%s: 过于规律(标准差 %.0f 分钟 < %d), 机械感强", label, stdDevMinutes, (int) MIN_VARIANCE);
        } else if (stdDevMinutes > MAX_VARIANCE) {
            tooRegular = false;
            summary = String.format("%s: 方差过大(标准差 %.0f 分钟 > %d), 缺乏习惯", label, stdDevMinutes, (int) MAX_VARIANCE);
        } else {
            tooRegular = false;
            summary = String.format("%s: 有习惯均值 + 适度波动(标准差 %.0f 分钟), 符合真人", label, stdDevMinutes);
        }
        return new EntropyResult(stdDevMinutes, summary, tooRegular);
    }

    /** 综合评估: 返回是否存在"过于规律"的反 AI 模式 */
    public List<String> detectRegularity(List<String> labels, List<List<Double>> samples) {
        java.util.List<String> patterns = new java.util.ArrayList<>();
        if (samples == null) return patterns;
        for (int i = 0; i < samples.size(); i++) {
            EntropyResult r = evaluateTimes(samples.get(i), labels.get(i));
            if (r.tooRegular()) {
                patterns.add(r.summary());
            }
        }
        return patterns;
    }
}
