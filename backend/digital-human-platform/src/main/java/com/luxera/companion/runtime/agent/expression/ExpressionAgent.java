package com.luxera.companion.runtime.agent.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.interaction.ResponseCommitment;
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
 * Expression Agent(§32-§35, P5): 决定"怎么说/说几条/什么时候发/什么时候停"。
 * 不是把完整回答拆句 —— 而是先有 communication intent, 再表达策略, 再消息计划。
 * Brain 决定"我要说", Expression 决定"我怎么说"。
 * 回退: 单段(默认自然表达)。
 */
@Slf4j
@Component
public class ExpressionAgent implements Agent<ExpressionContext, ExpressionResult> {

    public static final String NAME = "expression";

    private final LlmRouter llm;
    private final AgentTraceService traceService;
    private final SkillPromptComposer skillPrompts;
    private final TypingSimulationService typing;

    public ExpressionAgent(LlmRouter llm, AgentTraceService traceService, SkillPromptComposer skillPrompts,
                           TypingSimulationService typing) {
        this.llm = llm;
        this.traceService = traceService;
        this.skillPrompts = skillPrompts;
        this.typing = typing;
    }

    @Override
    public ExpressionResult execute(ExpressionContext ctx) {
        long start = System.currentTimeMillis();
        String traceId = AgentTraceService.newTraceId();

        ExpressionResult llmPlan = tryLlm(ctx);
        if (llmPlan != null) {
            trace(traceId, ctx, llmPlan, System.currentTimeMillis() - start, "success");
            return llmPlan;
        }

        // §57 回退: 单段 + 打字时长由 TypingSimulation 估算
        ExpressionResult fallback = ExpressionResult.single(true);
        long typingMs = typing.typingDurationMs(ctx.messageText(), ctx.urgency());
        ExpressionResult timed = new ExpressionResult(fallback.strategy(),
                List.of(new ExpressionResult.MessageSegment("reply", 0, 0, typingMs)),
                true, true);
        trace(traceId, ctx, timed, System.currentTimeMillis() - start, "fallback");
        return timed;
    }

    private ExpressionResult tryLlm(ExpressionContext ctx) {
        if (!llm.available() || llm.isMockActive()) return null;
        try {
            StructuredResult result = llm.structured(StructuredRequest.builder()
                    .system(buildSystem(ctx))
                    .user(buildUser(ctx))
                    .task("expression-generation")
                    .schemaHint("{\"strategy\":{},\"segments\":[{\"purpose\":\"\",\"delayMs\":0,\"maxChars\":0,\"typingDurationMs\":0}],\"stopAfter\":true}")
                    .build());
            return parse(result.getRaw(), ctx);
        } catch (Exception e) {
            log.warn("[ExpressionAgent] 表达规划失败,回退单段: {}", e.getMessage());
            return null;
        }
    }

    private ExpressionResult parse(String raw, ExpressionContext ctx) {
        try {
            JsonNode n = new ObjectMapper().readTree(raw);
            JsonNode strategyNode = n.path("strategy");
            ExpressionResult.ExpressionStrategy strategy = new ExpressionResult.ExpressionStrategy(
                    strategyNode.path("tone").asText("natural"),
                    strategyNode.path("directness").asDouble(0.5),
                    strategyNode.path("warmth").asDouble(0.5),
                    strategyNode.path("playfulness").asDouble(0.3),
                    strategyNode.path("vulnerability").asDouble(0.2));

            List<ExpressionResult.MessageSegment> segments = new ArrayList<>();
            for (JsonNode s : n.path("segments")) {
                long typingMs = s.path("typingDurationMs").asLong(0);
                if (typingMs <= 0) {
                    typingMs = typing.typingDurationMs(ctx.messageText(), ctx.urgency());
                }
                segments.add(new ExpressionResult.MessageSegment(
                        s.path("purpose").asText("reply"),
                        s.path("delayMs").asLong(0),
                        s.path("maxChars").asInt(0),
                        typingMs));
            }
            if (segments.isEmpty()) {
                return ExpressionResult.single(true);
            }
            boolean stopAfter = n.path("stopAfter").asBoolean(true);
            return new ExpressionResult(strategy, segments, stopAfter, false);
        } catch (Exception e) {
            return null;
        }
    }

    private void trace(String traceId, ExpressionContext ctx, ExpressionResult r,
                       long latency, String status) {
        try {
            traceService.record(ctx.companionId(), traceId, null, NAME, "EXPRESSION_PLAN",
                    "USER_MESSAGE", llm.isMockActive() ? "rules" : llm.activeProvider(),
                    truncate(ctx.messageText(), 200),
                    r.segments().size() + " 段:" + r.strategy().tone(), latency, 0, status);
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── Prompt(硬编码过渡; P7 迁移到 skills/) ────────────────────
    private String buildSystem(ExpressionContext ctx) {
        String skillBase = skillPrompts == null ? null
                : skillPrompts.composeBase("expression", "表达 Agent");
        String taskPrompt = "你是一个真实的人。你在回应用户的一条消息, 但你不是在'回答'而是在'相处'。\n"
                + "你决定: 用什么样的语气/直接程度/温度/俏皮/脆弱来表达, 以及分几条说、每条之间隔多久。\n"
                + "规则:\n"
                + "- 普通信息: 通常 1 条。\n"
                + "- 深度情绪表达: 1-3 条, 边想边说(先一句, 停一下, 再补一句), 但不要为了拆而拆。\n"
                + "- 每条消息的自然间隔 delayMs 在 800-3500ms 之间。\n"
                + "输出 JSON: {\"strategy\":{\"tone\":\"...\",\"directness\":0.0,\"warmth\":0.0,"
                + "\"playfulness\":0.0,\"vulnerability\":0.0},"
                + "\"segments\":[{\"purpose\":\"...\",\"delayMs\":0,\"maxChars\":0}],\"stopAfter\":true}";
        return skillBase == null ? taskPrompt
                : skillBase + "\n\n" + taskPrompt;
    }

    private String buildUser(ExpressionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户在说: ").append(ctx.messageText()).append("\n");
        sb.append("你想表达的目标: ").append(ctx.expressionGoal() == null ? "respond" : ctx.expressionGoal()).append("\n");
        if (ctx.responseIntent() != null && !ctx.responseIntent().isBlank()) {
            sb.append("你的回应意图(Brain 已确认,不要改变): ").append(ctx.responseIntent()).append("\n");
        }
        sb.append("你当前情绪: ").append(ctx.emotionSummary()).append("\n");
        if (ctx.personalitySummary() != null && !ctx.personalitySummary().isBlank()) {
            sb.append("你的性格: ").append(ctx.personalitySummary()).append("\n");
        }
        sb.append("你们的关系: ").append(ctx.relationshipStage() == null ? "初识" : ctx.relationshipStage())
                .append(", 亲密度 ").append(String.format("%.2f", ctx.closeness())).append("\n");
        sb.append("你现在: ").append(ctx.activityDesc()).append("\n");
        sb.append("精力 ").append(String.format("%.2f", ctx.energy())).append(", 紧迫感 ")
                .append(String.format("%.2f", ctx.urgency())).append("\n");
        if (ctx.baseline() != null) {
            sb.append("本回合篇幅约束: 最多 ").append(ctx.baseline().budget.maxCharacters).append(" 字 / ")
                    .append(ctx.baseline().budget.maxSentences).append(" 句\n");
        }
        return sb.toString();
    }
}
