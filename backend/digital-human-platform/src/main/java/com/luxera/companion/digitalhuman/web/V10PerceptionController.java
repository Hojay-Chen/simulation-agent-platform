package com.luxera.companion.digitalhuman.web;

import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.perception.PerceptionDecisionOrchestrator;
import com.luxera.companion.digitalhuman.perception.PerceptionResult;
import com.luxera.companion.digitalhuman.perception.PerceptionRuntime;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V10 感知-决策诊断端点(验收可视化, V10 Phase 4/5 验收):
 * 同一事件在不同环境/活动/设备状态下, 感知等级与决策不同。
 *
 * GET /api/v10/perception/explain?companionId=xxx&eventType=DEVICE_NOTIFICATION&importance=0.6
 */
@RestController
@RequestMapping("/api/v10")
public class V10PerceptionController {

    private final PerceptionDecisionOrchestrator orchestrator;
    private final CompanionRepository companionRepository;

    public V10PerceptionController(PerceptionDecisionOrchestrator orchestrator,
                                   CompanionRepository companionRepository) {
        this.orchestrator = orchestrator;
        this.companionRepository = companionRepository;
    }

    @GetMapping("/perception/explain")
    public Map<String, Object> explain(@RequestParam String companionId,
                                       @RequestParam(defaultValue = "DEVICE_NOTIFICATION") String eventType,
                                       @RequestParam(defaultValue = "0.5") double importance) {
        Companion companion = companionRepository.findById(companionId).orElse(null);
        ExternalEvent event = ExternalEvent.of(companionId,
                ExternalEventType.valueOf(eventType), Map.of("source", "diagnostic"));
        PerceptionDecisionOrchestrator.PerceptionDecisionOutcome outcome = orchestrator.evaluate(
                event, companion != null ? companion.getUserId() : null, companionId, importance,
                LocalDateTime.now());

        Map<String, Object> result = new LinkedHashMap<>();
        PerceptionResult p = outcome.perception();
        result.put("eventType", eventType);
        result.put("perception", Map.of(
                "level", p.level().name(),
                "score", Math.round(p.score() * 100) / 100.0,
                "strategy", p.strategy(),
                "triggersCognition", p.triggersCognition()));
        result.put("decision", Map.of(
                "policy", outcome.decision().policy(),
                "type", outcome.decision().decision().getClass().getSimpleName(),
                "reason", outcome.decision().decision().reason()));
        result.put("life", Map.of(
                "activity", outcome.context().life().activityType(),
                "attentionDemand", outcome.context().life().attentionDemand(),
                "sleeping", outcome.context().life().sleeping(),
                "description", outcome.context().life().description()));
        result.put("device", Map.of(
                "notificationMode", outcome.context().device().notificationMode(),
                "doNotDisturb", outcome.context().device().doNotDisturb(),
                "phoneLocation", outcome.context().device().phoneLocation()));
        result.put("mind", Map.of(
                "focus", outcome.context().mind().focus(),
                "energy", outcome.context().mind().energy()));
        result.put("importance", importance);
        result.put("thresholds", "NONE<0.2 / 0.2<=SUBCONSCIOUS<0.5 / 0.5<=AWARE<0.8 / FOCUSED>=0.8");
        return result;
    }
}
