package com.luxera.companion.runtime.agent.memory;

import java.util.List;

/**
 * Memory Agent 输出(§24 + §19): 候选记忆的激活评分 + 召回概率。
 * 代码只提供基础特征, LLM 判断语义相关性; 回退时用检索强度。
 *
 * §19: recallProbability 表示"这条记忆在当前时刻被真正想起的概率"。
 * 人知道很多事情, 但不会每次都想起来 —— 只有概率超过阈值才进入当前认知。
 */
public record MemoryRecallResult(
        List<MemoryActivation> activations,
        boolean fallback) {

    public record MemoryActivation(String memoryId, double activation, String reason) {
    }

    public static MemoryRecallResult empty() {
        return new MemoryRecallResult(List.of(), true);
    }
}
