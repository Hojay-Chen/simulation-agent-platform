package com.luxera.companion.runtime.agent.emotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.runtime.Agent;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import com.luxera.companion.runtime.EmotionDelta;
import com.luxera.companion.state.AgentStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Emotion Agent(§15-§20, P0 优先级):
 * 不再用"关键词直接决定情绪"。关键词最多作为 cheap signal(回退),
 * LLM 负责复杂情绪解释: 这件事对人物意味着什么 / 是否违背期待 / 意图是否确定 /
 * 当前关系如何影响解释 / 当前状态是否放大削弱 / 激活哪些旧记忆 / 最终方向和程度。
 *
 * 回退链: LLM 结构化输出(confidence ≥ 0.5) → 关键词 cheap signal(现有 AppraisalService)。
 */
@Slf4j
@Component
public class EmotionAgent implements Agent<EmotionContext, EmotionAppraisalResult> {

    public static final String NAME = "emotion";

    private static final double LLM_CONFIDENCE_THRESHOLD = 0.5;

    private final AppraisalService appraisalService;
    private final LlmRouter llm;
    private final AgentStateService agentStateService;
    private final AgentTraceService traceService;
    private final SkillPromptComposer skillPrompts;

    public EmotionAgent(AppraisalService appraisalService, LlmRouter llm,
                        AgentStateService agentStateService, AgentTraceService traceService,
                        SkillPromptComposer skillPrompts) {
        this.appraisalService = appraisalService;
        this.llm = llm;
        this.agentStateService = agentStateService;
        this.traceService = traceService;
        this.skillPrompts = skillPrompts;
    }

    @Override
    public EmotionAppraisalResult execute(EmotionContext ctx) {
        long start = System.currentTimeMillis();
        String traceId = AgentTraceService.newTraceId();

        // 1. cheap signal: 关键词评估(只计算+记录, 不改状态)
        PerceptionEngine.Perception perception = new PerceptionEngine.Perception(
                ctx.perceptionIntent(), ctx.perceptionEmotion(), null);
        AppraisalService.AppraisalResult cheap = appraisalService.computeAppraisal(
                ctx.companionId(), ctx.userId(), ctx.messageId(), ctx.messageText(), perception);

        // 2. LLM 复杂情绪解释
        EmotionAppraisalResult llmResult = tryLlm(ctx, traceId);
        if (llmResult != null) {
            // 3. 应用状态(经 Reducer) + 关系微调
            applyStateAndRelationship(ctx, llmResult, cheap);
            trace(traceId, ctx, llmResult, System.currentTimeMillis() - start, "success",
                    "llm", (int) (0));
            return llmResult;
        }

        // 4. 回退: cheap signal → delta
        EmotionDelta delta = cheapDelta(cheap);
        EmotionAppraisalResult fallback = new EmotionAppraisalResult(
                cheapAppraisal(cheap), delta, List.of(),
                0.3, "关键词评估回退", true);
        applyStateAndRelationship(ctx, fallback, cheap);
        trace(traceId, ctx, fallback, System.currentTimeMillis() - start, "fallback", "rules", 0);
        return fallback;
    }

    /** 尝试 LLM 结构化评估; 失败/不满足置信度返回 null */
    private EmotionAppraisalResult tryLlm(EmotionContext ctx, String traceId) {
        if (!llm.available() || llm.isMockActive()) {
            // Mock 网关返回低置信度占位 → 走规则回退(与 行为兼容)
            return null;
        }
        try {
            StructuredResult result = llm.structured(StructuredRequest.builder()
                    .system(buildSystem(ctx))
                    .user(buildUser(ctx))
                    .task("emotion-appraisal")
                    .schemaHint("{\"appraisal\":{},\"emotionDelta\":{},\"memoryTriggers\":[],\"confidence\":0,\"reason\":\"\"}")
                    .build());
            return parse(result.getRaw());
        } catch (Exception e) {
            log.warn("[EmotionAgent] LLM 评估失败,回退规则: {}", e.getMessage());
            return null;
        }
    }

    private EmotionAppraisalResult parse(String raw) {
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            double confidence = n.path("confidence").asDouble(0);
            if (confidence < LLM_CONFIDENCE_THRESHOLD) return null;

            JsonNode delta = n.path("emotionDelta");
            EmotionDelta d = EmotionDelta.of(
                    delta.path("anger").asDouble(0),
                    delta.path("hurt").asDouble(0),
                    delta.path("sadness").asDouble(0),
                    delta.path("warmth").asDouble(0),
                    delta.path("anxiety").asDouble(0));

            JsonNode appraisal = n.path("appraisal");
            EmotionAppraisalResult.Appraisal a = new EmotionAppraisalResult.Appraisal(
                    appraisal.path("target").asText("relationship"),
                    appraisal.path("eventMeaning").asText(""),
                    appraisal.path("expectationViolation").asDouble(0),
                    appraisal.path("perceivedIntent").asText("uncertain"));

            List<EmotionAppraisalResult.MemoryTrigger> triggers = new ArrayList<>();
            for (JsonNode t : n.path("memoryTriggers")) {
                triggers.add(new EmotionAppraisalResult.MemoryTrigger(
                        t.path("memoryId").asText(), t.path("activation").asDouble(0),
                        t.path("reason").asText("")));
            }
            return new EmotionAppraisalResult(a, d, triggers, confidence,
                    n.path("reason").asText(""), false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 应用情绪状态(经 Reducer)与关系微调 */
    private void applyStateAndRelationship(EmotionContext ctx, EmotionAppraisalResult result,
                                           AppraisalService.AppraisalResult cheap) {
        try {
            agentStateService.applyEmotionDelta(ctx.companionId(), result.delta());
            appraisalService.applyRelationshipImpact(ctx.userId(), ctx.companionId(), cheap);
        } catch (Exception e) {
            log.warn("[EmotionAgent] 状态应用失败: {}", e.getMessage());
        }
    }

    /** cheap signal → EmotionDelta */
    private static EmotionDelta cheapDelta(AppraisalService.AppraisalResult cheap) {
        double hurt = cheap.hurt();
        double anger = cheap.anger();
        double warmth = cheap.warmth();
        double impact = cheap.emotionalImpact();
        double urgency = cheap.urgency();
        // 高情绪冲击且不是愤怒 → 难过成分; 高紧迫 → 焦虑成分
        double sadness = anger < 0.1 && impact >= 0.6 ? impact * 0.3 : 0;
        double anxiety = urgency >= 0.5 ? urgency * 0.2 : 0;
        return EmotionDelta.of(anger, hurt, sadness, warmth, anxiety);
    }

    private static EmotionAppraisalResult.Appraisal cheapAppraisal(AppraisalService.AppraisalResult cheap) {
        String meaning = cheap.relationshipImpact() < -0.05 ? "possible_neglect"
                : cheap.emotionalImpact() >= 0.6 ? "emotional_event"
                : cheap.warmth() > 0.4 ? "warm_event" : "ordinary_event";
        String intent = cheap.relationshipImpact() < -0.05 ? "uncertain"
                : cheap.warmth() > 0.4 ? "positive" : "neutral";
        return new EmotionAppraisalResult.Appraisal("relationship", meaning,
                cheap.relationshipImpact() < -0.05 ? 0.5 : 0.1, intent);
    }

    private void trace(String traceId, EmotionContext ctx, EmotionAppraisalResult result,
                       long latency, String status, String model, int tokens) {
        try {
            traceService.record(ctx.companionId(), traceId, null, NAME, "USER_MESSAGE",
                    "USER_MESSAGE", model, truncate(ctx.messageText(), 200),
                    result.reason(), latency, tokens, status);
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── Prompt(硬编码过渡; P7 迁移到 skills/) ────────────────────
    private String buildSystem(EmotionContext ctx) {
        String skillBase = skillPrompts == null ? null
                : skillPrompts.composeBase("emotion", "情绪评估 Agent");
        String taskPrompt = "你是" + (ctx.personalitySummary() == null ? "一个真实的人" : ctx.personalitySummary())
                + "。你的任务不是判断用户这句话属于什么情绪。\n"
                + "你的任务是判断:\n"
                + "1. 这个事件对你意味着什么\n"
                + "2. 是否违反你当前的期待\n"
                + "3. 用户意图是否确定\n"
                + "4. 当前关系状态如何影响你的解释\n"
                + "5. 你当前的身体/生活状态是否放大或削弱情绪\n"
                + "6. 是否会激活过去经历(给出记忆触发)\n"
                + "7. 最终情绪发生什么方向和程度的变化(emotionDelta)\n"
                + "禁止: 根据单个关键词直接决定情绪; 把不确定意图当作事实; 擅自决定是否回复用户。\n"
                + "输出 JSON: {\"appraisal\":{\"target\":\"relationship\",\"eventMeaning\":\"...\","
                + "\"expectationViolation\":0.0,\"perceivedIntent\":\"...\"},"
                + "\"emotionDelta\":{\"anger\":0,\"hurt\":0,\"sadness\":0,\"warmth\":0,\"anxiety\":0},"
                + "\"memoryTriggers\":[{\"memoryId\":\"m1\",\"activation\":0.0,\"reason\":\"\"}],"
                + "\"confidence\":0.0,\"reason\":\"...\"}";
        return skillBase == null ? taskPrompt
                : skillBase + "\n\n" + taskPrompt;
    }

    private String buildUser(EmotionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(ctx.messageText()).append("\n");
        if (ctx.recentConversation() != null && !ctx.recentConversation().isEmpty()) {
            sb.append("最近对话:\n");
            for (String line : ctx.recentConversation()) sb.append("- ").append(line).append("\n");
        }
        sb.append("你们的关系: ").append(ctx.relationshipStage() == null ? "初识" : ctx.relationshipStage())
                .append(" (亲密度 ").append(round(ctx.closeness())).append(")\n");
        if (ctx.currentEmotion() != null) {
            sb.append("你当前情绪: hurt=").append(round(ctx.currentEmotion().getHurt()))
                    .append(" anger=").append(round(ctx.currentEmotion().getAnger()))
                    .append(" sadness=").append(round(ctx.currentEmotion().getSadness()))
                    .append(" anxiety=").append(round(ctx.currentEmotion().getAnxiety()))
                    .append(" warmth=").append(round(ctx.currentEmotion().getWarmth())).append("\n");
        }
        sb.append("你当前: ").append(ctx.activityDesc()).append(" (").append(ctx.availability()).append(")\n");
        sb.append("注意力: 对外专注 ").append(round(ctx.attentionFocus()))
                .append(", 对手机感知 ").append(round(ctx.attentionPhoneAwareness())).append("\n");
        if (ctx.memoryCandidates() != null && !ctx.memoryCandidates().isEmpty()) {
            sb.append("相关记忆候选:\n");
            for (var m : ctx.memoryCandidates()) {
                sb.append("- [").append(m.getId()).append("] ").append(m.getContent()).append("\n");
            }
        }
        sb.append("感知: intent=").append(ctx.perceptionIntent()).append(", emotion=")
                .append(ctx.perceptionEmotion()).append("\n");
        return sb.toString();
    }

    private static String round(double v) {
        return String.format("%.2f", v);
    }
}
