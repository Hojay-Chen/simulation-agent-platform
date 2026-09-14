package com.luxera.companion.runtime;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * V9 §22/§23 观测端点: LLM call / cache 估计 / 认知会话 / 计划状态。
 * 路径: GET /api/companions/{id}/v9/metrics
 */
@RestController
@RequestMapping("/api/companions/{companionId}/v9")
public class V9MetricsController {

    private final CurrentUser currentUser;
    private final CompanionService companionService;
    private final com.luxera.companion.llm.LlmCallRepository llmCallRepository;
    private final com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository;
    private final com.luxera.companion.plan.PlanRepository planRepository;

    public V9MetricsController(CurrentUser currentUser, CompanionService companionService,
                               com.luxera.companion.llm.LlmCallRepository llmCallRepository,
                               com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository,
                               com.luxera.companion.plan.PlanRepository planRepository) {
        this.currentUser = currentUser;
        this.companionService = companionService;
        this.llmCallRepository = llmCallRepository;
        this.cognitiveSessionRepository = cognitiveSessionRepository;
        this.planRepository = planRepository;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@PathVariable String companionId) {
        companionService.requireOwned(currentUser.requireUserId(), companionId);
        Map<String, Object> out = new HashMap<>();
        var calls = llmCallRepository.findTop20ByCompanionIdOrderByIdDesc(companionId);
        long total = llmCallRepository.countByCompanionId(companionId);
        long cacheHits = llmCallRepository.countCacheHit(companionId);
        out.put("llmCalls", calls);
        out.put("llmCallTotal", total);
        out.put("llmCallCacheHit", cacheHits);
        out.put("cacheHitRate", total == 0 ? 0 : Math.round(cacheHits * 1000.0 / total) / 10.0);
        out.put("avgLatencyMs", Math.round(llmCallRepository.avgLatency(companionId)));
        out.put("promptTokensTotal", llmCallRepository.sumPromptTokens(companionId));
        cognitiveSessionRepository.findByCompanionId(companionId).ifPresent(cs -> {
            out.put("cognitive", Map.of(
                    "currentFocus", cs.getCurrentFocus() == null ? "" : cs.getCurrentFocus(),
                    "currentThought", cs.getCurrentThought() == null ? "" : cs.getCurrentThought(),
                    "stateVersion", cs.getStateVersion()));
        });
        var plans = planRepository.findActive(companionId);
        out.put("activePlans", plans.stream().map(p -> Map.of(
                "title", p.getTitle() == null ? "" : p.getTitle(),
                "status", p.getStatus(),
                "confidence", p.getConfidence(),
                "expectedTime", p.getExpectedTime() == null ? "" : p.getExpectedTime().toString())).toList());
        return out;
    }
}
