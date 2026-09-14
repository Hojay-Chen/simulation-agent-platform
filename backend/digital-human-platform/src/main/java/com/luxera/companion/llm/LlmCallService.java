package com.luxera.companion.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * V9 §22 LLM Call 观测服务: 记录模型调用(独立事务, 失败不影响业务)。
 * prefix cache 命中估计: 与上一条同 agent 调用 stableHash 相同 → 估计命中。
 */
@Slf4j
@Service
public class LlmCallService {

    private final LlmCallRepository repo;

    public LlmCallService(LlmCallRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(String task, String provider, String model,
                       Integer promptTokens, Integer completionTokens,
                       long latencyMs, String status, Map<String, String> meta) {
        try {
            String companionId = meta == null ? null : meta.get("companionId");
            if (companionId == null) return;
            String stableHash = meta == null ? null : meta.get("stableHash");
            String sessionHash = meta == null ? null : meta.get("sessionHash");
            String dynamicHash = meta == null ? null : meta.get("dynamicHash");

            LlmCallRecord r = new LlmCallRecord();
            r.setCompanionId(companionId);
            r.setTask(task);
            r.setPath(meta == null ? null : meta.get("path"));
            r.setModel(model);
            r.setProvider(provider);
            r.setPromptTokens(promptTokens);
            r.setCompletionTokens(completionTokens);
            r.setLatencyMs(latencyMs);
            r.setStatus(status);
            r.setContextHash(stableHash + "/" + sessionHash + "/" + dynamicHash);

            // prefix cache 估计: 上一条同 agent 调用 stableHash 相同 → 前缀可复用
            if (stableHash != null && !stableHash.isBlank()) {
                repo.findFirstByCompanionIdOrderByIdDesc(companionId).ifPresent(prev -> {
                    String prevCtx = prev.getContextHash();
                    if (prevCtx != null && prevCtx.startsWith(stableHash + "/")) {
                        r.setCacheEstimated(true);
                    }
                });
            }
            repo.save(r);
        } catch (Exception e) {
            log.debug("[LlmCall] 记录失败: {}", e.getMessage());
        }
    }
}
