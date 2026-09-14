package com.luxera.companion.runtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 痕迹服务(§60): 记录/查询每次 Agent 调用。
 * 失败不阻塞主流程 —— trace 写失败仅记日志。
 */
@Service
public class AgentTraceService {

    private final AgentTraceRepository repo;

    public AgentTraceService(AgentTraceRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public AgentTrace record(AgentTrace t) {
        try {
            if (t.getId() == null) t.setId(UUID.randomUUID().toString());
            return repo.save(t);
        } catch (Exception e) {
            // trace 属于非关键路径, 失败不影响主流程
            return t;
        }
    }

    /** 一次 Agent 调用的便捷记录 */
    @Transactional
    public AgentTrace record(String companionId, String traceId, String parentTraceId, String agentName,
                             String eventType, String wakeReason, String model,
                             String inputSummary, String output, long latencyMs, int tokenUsage, String status) {
        AgentTrace t = new AgentTrace();
        t.setCompanionId(companionId);
        t.setTraceId(traceId);
        t.setParentTraceId(parentTraceId);
        t.setAgentName(agentName);
        t.setEventType(eventType);
        t.setWakeReason(wakeReason);
        t.setModel(model);
        t.setInputSummary(truncate(inputSummary, 2000));
        t.setOutput(truncate(output, 4000));
        t.setLatencyMs(latencyMs);
        t.setTokenUsage(tokenUsage);
        t.setStatus(status);
        return record(t);
    }

    @Transactional(readOnly = true)
    public List<AgentTrace> chain(String companionId, String traceId) {
        return repo.findByCompanionIdAndTraceIdOrderByCreatedAtAsc(companionId, traceId);
    }

    @Transactional(readOnly = true)
    public List<AgentTrace> recent(String companionId, int limit) {
        List<AgentTrace> all = repo.findTop50ByCompanionIdOrderByCreatedAtDesc(companionId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    @Transactional(readOnly = true)
    public List<AgentTrace> since(String companionId, LocalDateTime after) {
        return repo.findByCompanionIdAndCreatedAtAfterOrderByCreatedAtAsc(companionId, after);
    }

    public static String newTraceId() {
        return "t" + System.nanoTime();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
