package com.luxera.companion.runtime;

import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.runtime.pipeline.PendingMessageService;
import com.luxera.companion.runtime.pipeline.PendingMessageState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 诊断端点(只读): 观察运行时内部 —— Agent 痕迹 / 排程动作 / 待复查消息 / 世界事件 / 已注册 Agent。
 * 用于验证与调试, 不影响主流程。
 */
@RestController
@RequestMapping("/api/companions/{companionId}/v5")
public class DiagnosticController {

    private final CurrentUser currentUser;
    private final CompanionService companionService;
    private final AgentTraceService traceService;
    private final ScheduledActionService scheduledActionService;
    private final PendingMessageService pendingMessageService;
    private final WorldEventLogService worldEventLogService;
    private final AgentRegistry agentRegistry;
    private final com.luxera.companion.llm.LlmCallRepository llmCallRepository;
    private final com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository;
    private final com.luxera.companion.plan.PlanRepository planRepository;

    public DiagnosticController(CurrentUser currentUser, CompanionService companionService,
                                  AgentTraceService traceService, ScheduledActionService scheduledActionService,
                                  PendingMessageService pendingMessageService,
                                  WorldEventLogService worldEventLogService, AgentRegistry agentRegistry,
                                  com.luxera.companion.llm.LlmCallRepository llmCallRepository,
                                  com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository,
                                  com.luxera.companion.plan.PlanRepository planRepository) {
        this.currentUser = currentUser;
        this.companionService = companionService;
        this.traceService = traceService;
        this.scheduledActionService = scheduledActionService;
        this.pendingMessageService = pendingMessageService;
        this.worldEventLogService = worldEventLogService;
        this.agentRegistry = agentRegistry;
        this.llmCallRepository = llmCallRepository;
        this.cognitiveSessionRepository = cognitiveSessionRepository;
        this.planRepository = planRepository;
    }

    private void requireOwned(String userId, String companionId) {
        companionService.requireOwned(userId, companionId);
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return Map.of("registered", agentRegistry.all().keySet().stream().sorted().toList());
    }

    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return traceService.recent(companionId, 50).stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("agent", t.getAgentName());
            m.put("event", t.getEventType());
            m.put("wake", t.getWakeReason());
            m.put("status", t.getStatus());
            m.put("input", t.getInputSummary());
            m.put("output", t.getOutput());
            m.put("latency", t.getLatencyMs());
            m.put("at", t.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/scheduled")
    public List<Map<String, Object>> scheduled(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return scheduledActionService.pending(companionId).stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("type", a.getActionType());
            m.put("executeAt", a.getExecuteAt());
            m.put("payload", a.getPayload());
            m.put("retry", a.getRetryCount());
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/pending-messages")
    public List<Map<String, Object>> pendingMessages(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return pendingMessageService.pendingFor(companionId).stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("messageId", p.getMessageId());
            m.put("content", p.getSenderText());
            m.put("nextReviewAt", p.getNextReviewAt());
            m.put("reason", p.getReason());
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("/world-events")
    public List<Map<String, Object>> worldEvents(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);
        return worldEventLogService.recent(companionId, 50).stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("type", e.getEventType());
            m.put("at", e.getOccurredAt());
            m.put("payload", e.getPayload());
            return m;
        }).collect(Collectors.toList());
    }

}