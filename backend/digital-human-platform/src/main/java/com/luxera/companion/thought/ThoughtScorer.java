package com.luxera.companion.thought;

import org.springframework.stereotype.Component;

/** 想法评分(设计文档 §6.2) */
@Component
public class ThoughtScorer {

    /**
     * 候选想法强度 = 重要性 × 情绪权重 × 关系权重 × 时效性。
     * 只有高价值 Thought 才进入后续行为链。
     */
    public double score(double importance, double emotionalWeight, double relationshipWeight, double timeliness) {
        return importance * emotionalWeight * relationshipWeight * timeliness;
    }
}
