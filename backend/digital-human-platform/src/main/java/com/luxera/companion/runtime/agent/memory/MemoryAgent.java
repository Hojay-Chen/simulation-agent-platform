package com.luxera.companion.runtime.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.runtime.Agent;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.skill.SkillPromptComposer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Memory Agent(§21-§25, P2): 检索 + 激活 + 显著性 + 时间推理 + 关系相关性的第二阶段。
 * 第一阶段廉价检索(向量/关键词) → 第二阶段 LLM 判断激活强度。
 * 回退: 用记忆的 retrievalStrength 特征排序。
 */
@Slf4j
@Component
public class MemoryAgent implements Agent<MemoryRecallContext, MemoryRecallResult> {

    public static final String NAME = "memory";

    private final LlmRouter llm;
    private final AgentTraceService traceService;
    private final SkillPromptComposer skillPrompts;

    public MemoryAgent(LlmRouter llm, AgentTraceService traceService, SkillPromptComposer skillPrompts) {
        this.llm = llm;
        this.traceService = traceService;
        this.skillPrompts = skillPrompts;
    }

    @Override
    public MemoryRecallResult execute(MemoryRecallContext ctx) {
        long start = System.currentTimeMillis();
        String traceId = AgentTraceService.newTraceId();
        if (ctx.candidates() == null || ctx.candidates().isEmpty()) {
            return MemoryRecallResult.empty();
        }

        MemoryRecallResult llmResult = tryLlm(ctx);
        if (llmResult != null) {
            trace(traceId, ctx, llmResult, System.currentTimeMillis() - start, "success");
            return llmResult;
        }

        MemoryRecallResult fallback = fallbackActivations(ctx);
        trace(traceId, ctx, fallback, System.currentTimeMillis() - start, "fallback");
        return fallback;
    }

    private MemoryRecallResult tryLlm(MemoryRecallContext ctx) {
        if (!llm.available() || llm.isMockActive() || ctx.candidates().isEmpty()) return null;
        try {
            StructuredResult result = llm.structured(StructuredRequest.builder()
                    .system(buildSystem(ctx))
                    .user(buildUser(ctx))
                    .task("memory-recall")
                    .schemaHint("{\"activations\":[{\"memoryId\":\"\",\"activation\":0.0,\"reason\":\"\"}]}")
                    .build());
            return parse(result.getRaw());
        } catch (Exception e) {
            log.warn("[MemoryAgent] 激活评估失败,回退检索强度: {}", e.getMessage());
            return null;
        }
    }

    private MemoryRecallResult parse(String raw) {
        try {
            JsonNode n = new ObjectMapper().readTree(raw);
            List<MemoryRecallResult.MemoryActivation> list = new ArrayList<>();
            for (JsonNode a : n.path("activations")) {
                list.add(new MemoryRecallResult.MemoryActivation(
                        a.path("memoryId").asText(),
                        Math.max(0, Math.min(1, a.path("activation").asDouble(0))),
                        a.path("reason").asText("")));
            }
            if (list.isEmpty()) return null;
            return new MemoryRecallResult(list, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 回退: 用现有检索强度特征排序并归一化为激活分 */
    private MemoryRecallResult fallbackActivations(MemoryRecallContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        double max = ctx.candidates().stream()
                .mapToDouble(m -> {
                    LocalDateTime base = m.getOccurredAt() != null ? m.getOccurredAt() : m.getCreatedAt();
                    int days = (int) ChronoUnit.DAYS.between(base, now);
                    return m.retrievalStrength(days);
                }).max().orElse(1.0);
        List<MemoryRecallResult.MemoryActivation> list = new ArrayList<>();
        for (Memory m : ctx.candidates()) {
            LocalDateTime base = m.getOccurredAt() != null ? m.getOccurredAt() : m.getCreatedAt();
            int days = (int) ChronoUnit.DAYS.between(base, now);
            double activation = max > 0 ? m.retrievalStrength(days) / max : 0;
            list.add(new MemoryRecallResult.MemoryActivation(m.getId(),
                    Math.max(0, Math.min(1, activation)), "检索强度排序"));
        }
        return new MemoryRecallResult(list, true);
    }

    private void trace(String traceId, MemoryRecallContext ctx, MemoryRecallResult r,
                       long latency, String status) {
        try {
            traceService.record(ctx.companionId(), traceId, null, NAME, "MEMORY_RECALL",
                    "USER_MESSAGE", llm.isMockActive() ? "rules" : llm.activeProvider(),
                    truncate(ctx.query(), 200), r.activations().size() + " 条", latency, 0, status);
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── Prompt(硬编码过渡; P7 迁移到 skills/) ────────────────────
    private String buildSystem(MemoryRecallContext ctx) {
        String skillBase = skillPrompts == null ? null
                : skillPrompts.composeBase("memory", "记忆召回");
        String taskPrompt = "你是" + (ctx.personalitySummary() == null ? "一个真实的人" : ctx.personalitySummary())
                + "。你在回忆过去。\n"
                + "判断每条候选记忆在当前线索下的激活强度(0-1): 当前事件是否与这条记忆高度一致、"
                + "是否引发强烈感受、是否与你们的关系有关。\n"
                + "你可以读取/更新记忆, 但不能决定用户是否值得原谅, 也不能擅自把记忆转化为情绪分数"
                + "('这个记忆让我生气所以 anger+0.3') —— 情绪归因由 Emotion Agent 负责。\n"
                + "输出 JSON: {\"activations\":[{\"memoryId\":\"m1\",\"activation\":0.0,\"reason\":\"\"}]}";
        return skillBase == null ? taskPrompt
                : skillBase + "\n\n" + taskPrompt;
    }

    private String buildUser(MemoryRecallContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前线索: ").append(ctx.query()).append("\n");
        sb.append("你当前情绪: ").append(ctx.currentEmotionSummary() == null ? "平静" : ctx.currentEmotionSummary()).append("\n");
        if (ctx.candidates() != null) {
            sb.append("候选记忆:\n");
            for (Memory m : ctx.candidates()) {
                sb.append("- [").append(m.getId()).append("] 强度=")
                        .append(String.format("%.2f", m.getImportance() * m.getConfidence()))
                        .append(" ").append(m.getContent()).append("\n");
            }
        }
        return sb.toString();
    }
}
