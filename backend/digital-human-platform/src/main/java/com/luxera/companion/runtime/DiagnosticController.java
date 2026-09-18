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
import java.util.LinkedHashMap;
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
    private final com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch;
    private final com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow;

    public DiagnosticController(CurrentUser currentUser, CompanionService companionService,
                                  AgentTraceService traceService, ScheduledActionService scheduledActionService,
                                  PendingMessageService pendingMessageService,
                                  WorldEventLogService worldEventLogService, AgentRegistry agentRegistry,
                                  com.luxera.companion.llm.LlmCallRepository llmCallRepository,
                                  com.luxera.companion.cognitive.CognitiveSessionRepository cognitiveSessionRepository,
                                  com.luxera.companion.plan.PlanRepository planRepository,
                                  com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch,
                                  com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow) {
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
        this.v11Switch = v11Switch;
        this.v11Shadow = v11Shadow;
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

    /**
     * V11 §25 —— 送达主链的 shadow 对比。切流之前, 这个端点就是"该不该切"的全部依据。
     *
     * <p>它存在的理由值得写在这里: V10 的 {@code ShadowDecisionRecorder} 记了几十万条,
     * 而它的 {@code stats()} / {@code recent()} <b>在整仓里没有任何调用者</b> ——
     * 没有端点也没有测试。于是那次 shadow 是纯成本: 一直写, 从没被看, 顺带漏内存。
     * 一个读不到的对比不是对比, 所以 V11 的对比数据在这里有唯一的读出口。
     *
     * <p>返回里 {@code v11WouldSkip} 是关键: 老链处理了、而新门认为她<b>根本不会注意到</b>
     * 的那些送达。它不是 bug, 它就是这次改动的内容 —— 但这个比例决定了切流值不值、
     * 以及阈值要不要先调。
     */
    @GetMapping("/v11")
    public Map<String, Object> v11(@PathVariable String companionId) {
        String userId = currentUser.requireUserId();
        requireOwned(userId, companionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", v11Switch.isEnabled());
        out.put("shadow", v11Switch.isShadow());
        out.put("overall", v11Shadow.stats());
        out.put("thisCompanion", v11Shadow.perAgentStats().get(companionId));
        out.put("recent", v11Shadow.recentFor(companionId, 20));
        if (!v11Switch.isActive()) {
            // 说出来比返回一堆 0 好: 否则读到全 0 的人会以为"没有分歧", 而事实是"没在看"
            out.put("note", "V11 送达主链未启用(app.v11.runtime.enabled/shadow 皆为 false), 上面的数字无意义");
        }
        return out;
    }

}