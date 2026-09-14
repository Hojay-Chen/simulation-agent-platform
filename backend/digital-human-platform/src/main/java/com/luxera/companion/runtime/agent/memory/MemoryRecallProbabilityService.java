package com.luxera.companion.runtime.agent.memory;

import com.luxera.companion.memory.Memory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * §19 Memory Recall Probability: 记忆召回概率。
 * 人知道很多事情, 但不会每次都想起来 —— 只有超过阈值才进入人物当前认知。
 *
 * recallProbability = activation × salience 加权
 *  - activation: Memory Agent 的语义激活评分(0-1)
 *  - salience:   记忆自身的显著性(importance × confidence)
 *  - 阈值: 低于 threshold 的记忆不会进入当前认知(Brain 看不到它)
 */
@Component
public class MemoryRecallProbabilityService {

    /** 进入当前认知的最低召回概率 */
    public static final double DEFAULT_THRESHOLD = 0.35;

    /**
     * 过滤候选记忆: 返回达到召回概率阈值的记忆(保留原始顺序, 按激活降序)。
     */
    public List<Memory> filterAboveThreshold(List<Memory> candidates, MemoryRecallResult recall,
                                             double threshold) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (recall == null || recall.activations() == null || recall.activations().isEmpty()) {
            // 无激活信息 → 用记忆自身显著性兜底
            return candidates.stream()
                    .filter(m -> salience(m) >= threshold)
                    .toList();
        }

        // memoryId → activation
        java.util.Map<String, Double> activation = new java.util.HashMap<>();
        for (MemoryRecallResult.MemoryActivation a : recall.activations()) {
            activation.put(a.memoryId(), a.activation());
        }

        List<Memory> above = new ArrayList<>();
        for (Memory m : candidates) {
            double act = activation.getOrDefault(m.getId(), 0.0);
            double prob = recallProbability(act, m);
            if (prob >= threshold) {
                above.add(m);
            }
        }
        // 按激活降序
        above.sort((x, y) -> Double.compare(
                activation.getOrDefault(y.getId(), 0.0),
                activation.getOrDefault(x.getId(), 0.0)));
        return above;
    }

    /** 召回概率 = 激活 × 显著性(不高于 1) */
    public double recallProbability(double activation, Memory m) {
        double sal = salience(m);
        return Math.max(0, Math.min(1, activation * (0.5 + sal * 0.5)));
    }

    /** 记忆显著性: 重要性 × 置信度 */
    private static double salience(Memory m) {
        if (m == null) return 0;
        return m.getImportance() * m.getConfidence();
    }
}
