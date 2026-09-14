package com.luxera.companion.runtime.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.runtime.Agent;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Event Simulation Agent(§26-§31, P6): 根据生活活动/状态/环境/历史经验生成少量可能发生的事件候选。
 * 不是"每 10 分钟一个剧情" —— NORMAL/NOTHING HAPPENS 拥有最高基础概率。
 */
@Slf4j
@Component
public class EventSimulationAgent implements Agent<EventSimulationContext, EventSimulationResult> {

    public static final String NAME = "event";

    private final LlmRouter llm;
    private final AgentTraceService traceService;
    private final SkillPromptComposer skillPrompts;

    public EventSimulationAgent(LlmRouter llm, AgentTraceService traceService, SkillPromptComposer skillPrompts) {
        this.llm = llm;
        this.traceService = traceService;
        this.skillPrompts = skillPrompts;
    }

    @Override
    public EventSimulationResult execute(EventSimulationContext ctx) {
        long start = System.currentTimeMillis();
        String traceId = AgentTraceService.newTraceId();

        EventSimulationResult llmResult = tryLlm(ctx);
        if (llmResult != null) {
            trace(traceId, ctx, llmResult, System.currentTimeMillis() - start, "success");
            return llmResult;
        }

        EventSimulationResult fallback = defaultCandidates();
        trace(traceId, ctx, fallback, System.currentTimeMillis() - start, "fallback");
        return fallback;
    }

    private EventSimulationResult tryLlm(EventSimulationContext ctx) {
        if (!llm.available() || llm.isMockActive()) return null;
        try {
            StructuredResult result = llm.structured(StructuredRequest.builder()
                    .system(buildSystem())
                    .user(buildUser(ctx))
                    .task("event-simulation")
                    .schemaHint("{\"candidates\":[{\"eventType\":\"\",\"probability\":0.0,\"trigger\":\"\",\"consequences\":[]}]}")
                    .build());
            return parse(result.getRaw());
        } catch (Exception e) {
            log.warn("[EventSimulationAgent] 事件候选失败,回退默认: {}", e.getMessage());
            return null;
        }
    }

    private EventSimulationResult parse(String raw) {
        try {
            JsonNode n = new ObjectMapper().readTree(raw);
            List<EventSimulationResult.EventCandidate> list = new ArrayList<>();
            for (JsonNode c : n.path("candidates")) {
                List<String> cons = new ArrayList<>();
                for (JsonNode cj : c.path("consequences")) cons.add(cj.asText());
                list.add(new EventSimulationResult.EventCandidate(
                        c.path("eventType").asText("NORMAL"),
                        c.path("probability").asDouble(0.01),
                        c.path("trigger").asText(""), cons));
            }
            if (list.isEmpty()) return null;
            return new EventSimulationResult(list, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 默认候选: 正常/无事发生 最高基础概率(§29) */
    static EventSimulationResult defaultCandidates() {
        List<EventSimulationResult.EventCandidate> list = List.of(
                new EventSimulationResult.EventCandidate(EventSimulationResult.NORMAL, 0.82, "", List.of()),
                new EventSimulationResult.EventCandidate(EventSimulationResult.FORGOT_UMBRELLA, 0.05, "rain", List.of("return_home", "get_wet", "buy_umbrella")),
                new EventSimulationResult.EventCandidate(EventSimulationResult.MEET_ACQUAINTANCE, 0.04, "outside", List.of("small_chat", "recall_old_times")),
                new EventSimulationResult.EventCandidate(EventSimulationResult.SUDDEN_PLAN_CHANGE, 0.04, "external", List.of("reschedule", "cancel_plan")),
                new EventSimulationResult.EventCandidate(EventSimulationResult.WORK_INTERRUPTION, 0.04, "work", List.of("extra_task", "stay_late")),
                new EventSimulationResult.EventCandidate(EventSimulationResult.GOOD_NEWS, 0.03, "external", List.of("feel_happy", "want_share"))
        );
        return new EventSimulationResult(list, true);
    }

    private void trace(String traceId, EventSimulationContext ctx, EventSimulationResult r,
                       long latency, String status) {
        try {
            traceService.record(ctx.companionId(), traceId, null, NAME, "EVENT_SIMULATION",
                    "SCHEDULED_THOUGHT", llm.isMockActive() ? "rules" : llm.activeProvider(),
                    ctx.activityDesc(), r.candidates().size() + " 候选", latency, 0, status);
        } catch (Exception ignored) {
        }
    }

    private String buildSystem() {
        String skillBase = skillPrompts == null ? null
                : skillPrompts.composeBase("event", "事件模拟");
        String taskPrompt = "你是一个真实的人, 你在过自己的生活。根据你当前的活动、状态、环境和过去的经验, "
                + "生成少量今天可能发生的小事候选(3-6 个)。\n"
                + "要求:\n"
                + "- 大部分时候什么都不会发生(NORMAL 概率最高, 不低于 0.7)。\n"
                + "- 不要为了'真人感'每 10 分钟制造一个剧情。\n"
                + "- 候选概率要合理(单个非 NORMAL 事件 0.01-0.1)。\n"
                + "输出 JSON: {\"candidates\":[{\"eventType\":\"NORMAL\",\"probability\":0.82,\"trigger\":\"\",\"consequences\":[]}]}";
        return skillBase == null ? taskPrompt
                : skillBase + "\n\n" + taskPrompt;
    }

    private String buildUser(EventSimulationContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你现在的活动: ").append(ctx.activityDesc()).append("\n");
        sb.append("环境: ").append(ctx.environmentDesc() == null ? "未知" : ctx.environmentDesc()).append("\n");
        sb.append("时间: ").append(ctx.timeDesc()).append("\n");
        sb.append("精力 ").append(String.format("%.2f", ctx.energy())).append(", 压力 ")
                .append(String.format("%.2f", ctx.stress())).append("\n");
        sb.append("你当前情绪: ").append(ctx.currentEmotionSummary() == null ? "平静" : ctx.currentEmotionSummary()).append("\n");
        if (ctx.personalitySummary() != null && !ctx.personalitySummary().isBlank()) {
            sb.append("你的性格: ").append(ctx.personalitySummary()).append("\n");
        }
        if (ctx.recentEventTypes() != null && !ctx.recentEventTypes().isEmpty()) {
            sb.append("近期已经发生过: ").append(String.join(",", ctx.recentEventTypes())).append("(避免重复)\n");
        }
        return sb.toString();
    }
}
