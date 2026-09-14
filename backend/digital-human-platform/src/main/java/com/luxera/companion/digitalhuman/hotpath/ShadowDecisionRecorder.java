package com.luxera.companion.digitalhuman.hotpath;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.perception.PerceptionDecisionOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §4 Shadow Decision Recorder: 记录 V10 vs V9 决策对比。
 * 轻量: 内存暂存, 不阻塞主链路。
 */
@Slf4j
@Component
public class ShadowDecisionRecorder {

    private final ConcurrentHashMap<String, DecisionShadow> buffer = new ConcurrentHashMap<>();

    /** 记录 V10 决策 */
    public void record(ExternalEvent event, String companionId,
                       PerceptionDecisionOrchestrator.PerceptionDecisionOutcome v10,
                       String v9Outcome) {
        DecisionShadow s = new DecisionShadow();
        s.companionId = companionId;
        s.eventId = event != null ? event.eventId() : "unknown";
        s.v10Outcome = v10 != null ? v10.perception().level().name() : "N/A";
        s.v10Decision = v10 != null ? v10.decision().decision().getClass().getSimpleName() : "N/A";
        s.v10Reason = v10 != null ? extractReason(v10.decision().decision()) : "";
        s.v9Outcome = v9Outcome != null ? v9Outcome : "not-yet";
        s.createdAt = LocalDateTime.now();
        buffer.put(companionId + "-" + System.nanoTime(), s);
    }

    /** 查询最近 N 条 shadow 记录 */
    public List<DecisionShadow> recent(String companionId, int limit) {
        return buffer.values().stream()
                .filter(s -> s.companionId.equals(companionId))
                .sorted((a, b) -> b.createdAt.compareTo(a.createdAt))
                .limit(limit)
                .toList();
    }

    /** 统计 */
    public Map<String, Object> stats(String companionId) {
        var all = buffer.values().stream()
                .filter(s -> s.companionId.equals(companionId)).toList();
        return Map.of("total", all.size(), "shadowRecords", buffer.size());
    }

    private String extractReason(com.luxera.companion.digitalhuman.decision.PersonDecision decision) {
        if (decision == null) return "";
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.IgnoreDecision d) return d.reason();
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.InspectDeviceDecision d) return d.reason();
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.ReplyDecision d) return d.reason();
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.DelayReplyDecision d) return d.reason();
        if (decision instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.ChangeActivityDecision d) return d.reason();
        return "";
    }

    public static class DecisionShadow {
        public String companionId, eventId, v10Outcome, v10Decision, v10Reason, v9Outcome;
        public LocalDateTime createdAt;
    }
}