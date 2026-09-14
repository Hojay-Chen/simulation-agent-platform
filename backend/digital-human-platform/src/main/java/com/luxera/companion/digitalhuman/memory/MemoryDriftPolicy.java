package com.luxera.companion.digitalhuman.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §5.4 Memory Drift Policy: 记忆引用克制。
 *
 * 真人记忆特征:
 * - 非亲密关系阈值 0.7 才允许主动引用过去
 * - 同一记忆 24h 内不重复引用
 * - 引用必须 < 30 字摘要而非完整原文(避免复读机)
 */
@Slf4j
@Component
public class MemoryDriftPolicy {

    private final ConcurrentHashMap<String, Long> lastReferenceTime = new ConcurrentHashMap<>();

    /** 判断是否允许在当前对话中引用指定记忆 */
    public boolean allowReference(String companionId, String memoryId,
                                  double relationshipIntimacy, boolean userInitiated) {
        // 非亲密关系阈值 0.7 才允许主动引用
        if (relationshipIntimacy < 0.7 && !userInitiated) {
            log.debug("[MemoryDrift] 亲密度 {} < 0.7, 禁止主动引用记忆 {}", relationshipIntimacy, memoryId);
            return false;
        }
        // 24h 内不重复引用
        String key = companionId + ":" + memoryId;
        Long lastTime = lastReferenceTime.get(key);
        if (lastTime != null && (System.currentTimeMillis() - lastTime) < 24 * 60 * 60 * 1000L) {
            log.debug("[MemoryDrift] 记忆 {} 24h 内已引用过", memoryId);
            return false;
        }
        return true;
    }

    /** 生成记忆引用摘要(≤ 30 字) */
    public String summarizeForReference(String memoryContent) {
        if (memoryContent == null || memoryContent.length() <= 30) return memoryContent;
        return memoryContent.substring(0, 30) + "...";
    }

    /** 记录一次引用 */
    public void recordReference(String companionId, String memoryId) {
        lastReferenceTime.put(companionId + ":" + memoryId, System.currentTimeMillis());
    }
}