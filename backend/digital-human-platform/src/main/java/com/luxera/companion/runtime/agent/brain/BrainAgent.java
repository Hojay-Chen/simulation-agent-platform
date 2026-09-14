package com.luxera.companion.runtime.agent.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
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
 * Brain Executive(§41-§46, P4): 最终决策层。
 * 不是"回复驱动力 > 回避驱动力就回复" —— 综合当前活动/是否方便/情绪/消息重要程度/
 * 关系状态/精力/未完成任务/想分享的事/历史互动/人格/Drives, 允许 延迟/忽略/只看不回/回复一部分/稍后继续/主动联系/改变活动。
 *
 * Brain 不输出自然语言, 只输出 Action + Intent + Schedule。
 * 回退链: LLM 结构化决策(confidence ≥ 阈值) → 规则基准(InteractionPolicyEngine)。
 */
@Slf4j
@Component
public class BrainAgent implements Agent<BrainContext, BrainDecision> {

    public static final String NAME = "brain";

    private static final double LLM_CONFIDENCE_THRESHOLD = 0.55;

    private final LlmRouter llm;
    private final AgentTraceService traceService;
    private final SkillPromptComposer skillPrompts;
    private final DecisionValidator decisionValidator;

    public BrainAgent(LlmRouter llm, AgentTraceService traceService, SkillPromptComposer skillPrompts,
                      DecisionValidator decisionValidator) {
        this.llm = llm;
        this.traceService = traceService;
        this.skillPrompts = skillPrompts;
        this.decisionValidator = decisionValidator;
    }

    @Override
    public BrainDecision execute(BrainContext ctx) {
        long start = System.currentTimeMillis();
        String traceId = AgentTraceService.newTraceId();
        InteractionDecision baseline = ctx.baseline();

        BrainDecision llmDecision = tryLlm(ctx, traceId);
        if (llmDecision != null) {
            BrainDecision validated = applyValidation(llmDecision, ctx);
            trace(traceId, ctx, validated, System.currentTimeMillis() - start, "success");
            return validated;
        }

        BrainDecision fallback = mapBaseline(baseline, ctx);
        BrainDecision validatedFallback = applyValidation(fallback, ctx);
        trace(traceId, ctx, validatedFallback, System.currentTimeMillis() - start, "fallback");
        return validatedFallback;
    }

    /** §44 一致性校验: 决策违反当前状态约束时, 修正为可接受的替代动作 */
    private BrainDecision applyValidation(BrainDecision d, BrainContext ctx) {
        if (decisionValidator == null) return d;
        try {
            DecisionValidator.ValidationResult vr = decisionValidator.validate(d.action(), ctx);
            if (vr.valid() || vr.correctedAction() == null) return d;
            return new BrainDecision(vr.correctedAction(), d.priority() * 0.9,
                    concat(d.reasonFactors(), vr.reason()), d.expressionGoal(),
                    d.confidence() * 0.8, d.fallback(), d.baseline(),
                    d.desiredLength(), d.messageCount(), d.delayHint(), d.styleHint());
        } catch (Exception e) {
            return d;
        }
    }

    private static List<String> concat(List<String> list, String extra) {
        List<String> out = new ArrayList<>();
        if (list != null) out.addAll(list);
        if (extra != null) out.add(extra);
        return out;
    }

    /** 尝试 LLM 决策; 失败/Mock/低置信度返回 null */
    private BrainDecision tryLlm(BrainContext ctx, String traceId) {
        if (!llm.available() || llm.isMockActive()) return null;
        try {
            StructuredResult result = llm.structured(StructuredRequest.builder()
                    .system(buildSystem(ctx))
                    .user(buildUser(ctx))
                    .task("brain-decision")
                    .schemaHint("{\"action\":\"\",\"priority\":0,\"reasonFactors\":[],"
                            + "\"expressionIntent\":{\"goal\":\"\",\"desiredLength\":0,\"messageCount\":0,"
                            + "\"delayHint\":\"\",\"styleHint\":\"\"},\"confidence\":0}")
                    .build());
            return parse(result.getRaw(), ctx);
        } catch (Exception e) {
            log.warn("[BrainAgent] LLM 决策失败,回退规则: {}", e.getMessage());
            return null;
        }
    }

    private BrainDecision parse(String raw, BrainContext ctx) {
        try {
            JsonNode n = new ObjectMapper().readTree(raw);
            double confidence = n.path("confidence").asDouble(0);
            if (confidence < LLM_CONFIDENCE_THRESHOLD) return null;
            String action = n.path("action").asText("");
            if (!isValidAction(action)) return null;

            List<String> factors = new ArrayList<>();
            for (JsonNode f : n.path("reasonFactors")) factors.add(f.asText());
            String goal = n.path("expressionIntent").path("goal").asText("respond");
            // V9 §11 ResponseIntent: 表达意图(想多长/拆几条/节奏/语气)
            int desiredLength = n.path("expressionIntent").path("desiredLength").asInt(0);
            int messageCount = n.path("expressionIntent").path("messageCount").asInt(0);
            String delayHint = n.path("expressionIntent").path("delayHint").asText("");
            String styleHint = n.path("expressionIntent").path("styleHint").asText("");

            return new BrainDecision(action, n.path("priority").asDouble(0.5), factors,
                    goal, confidence, false, ctx.baseline(),
                    desiredLength, messageCount, delayHint, styleHint);
        } catch (Exception e) {
            return null;
        }
    }

    /** 规则基准 → BrainDecision */
    private static BrainDecision mapBaseline(InteractionDecision baseline, BrainContext ctx) {
        InteractionAction action = baseline.action;
        String brainAction;
        if (action == InteractionAction.IGNORE || action == InteractionAction.WAIT) {
            brainAction = BrainDecision.IGNORE;
        } else if (action == InteractionAction.DEFER) {
            brainAction = BrainDecision.READ_NO_REPLY;
        } else if (action == InteractionAction.END_CONVERSATION) {
            brainAction = BrainDecision.END_CONVERSATION;
        } else if (action == InteractionAction.SHORT_ACK) {
            brainAction = BrainDecision.SHORT_ACK;
        } else {
            brainAction = BrainDecision.REPLY;
        }
        return new BrainDecision(brainAction, baseline.confidence,
                List.of(baseline.reason == null ? "规则决策" : baseline.reason),
                "respond", baseline.confidence, true, baseline, 0, 0, null, null);
    }

    private static boolean isValidAction(String a) {
        return BrainDecision.REPLY.equals(a) || BrainDecision.SHORT_ACK.equals(a)
                || BrainDecision.CHECK_PHONE_FIRST.equals(a) || BrainDecision.READ_NO_REPLY.equals(a)
                || BrainDecision.IGNORE.equals(a) || BrainDecision.END_CONVERSATION.equals(a);
    }

    private void trace(String traceId, BrainContext ctx, BrainDecision d,
                       long latency, String status) {
        try {
            traceService.record(ctx.companionId(), traceId, null, NAME, "USER_MESSAGE",
                    "USER_MESSAGE", llm.isMockActive() ? "rules" : llm.activeProvider(),
                    truncate(ctx.messageText(), 200), d.action(), latency, 0, status);
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ── Prompt(硬编码过渡; P7 迁移到 skills/) ────────────────────
    private String buildSystem(BrainContext ctx) {
        String skillBase = skillPrompts == null ? null
                : skillPrompts.composeBase("brain", "执行决策 Brain");
        String taskPrompt = "你是" + "一个真实的人。你不是一个回答用户消息的聊天机器人, 而是一个有自己的生活状态、"
                + "注意力、情绪、记忆和正在做的事的人。用户消息只是进入你生活世界的一种外部事件。\n"
                + "不要通过单一分数决定行动。综合:\n"
                + "- 你当前正在做什么/是否方便\n"
                + "- 你当前情绪\n"
                + "- 用户消息的重要程度\n"
                + "- 关系状态\n"
                + "- 当前精力/压力\n"
                + "- 是否有未完成任务\n"
                + "- 是否有想分享的事\n"
                + "- 历史互动\n"
                + "- 人格倾向\n"
                + "允许: 延迟回复、忽略、只看不回、回复一部分、稍后继续、主动联系、改变当前活动。\n"
                + "你的决策不允许直接生成用户可见文本。\n"
                + "action 可选: REPLY / SHORT_ACK / CHECK_PHONE_FIRST / READ_NO_REPLY / IGNORE / END_CONVERSATION\n"
                + "输出 JSON: {\"action\":\"...\",\"priority\":0.0,\"reasonFactors\":[],"
                + "\"expressionIntent\":{\"goal\":\"...\",\"desiredLength\":0,\"messageCount\":0,"
                + "\"delayHint\":\"...\",\"styleHint\":\"...\"},\"confidence\":0.0}\n"
                + "expressionIntent 是你的回应意图(不直接生成文本): desiredLength 期望字数(0=自然), "
                + "messageCount 拆几条消息(0=自然), delayHint 节奏提示(如\"先缓一下\"/\"马上回\"), "
                + "styleHint 语气提示(如\"轻松一点\"/\"认真一点\")。";
        return skillBase == null ? taskPrompt
                : skillBase + "\n\n" + taskPrompt;
    }

    private String buildUser(BrainContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息: ").append(ctx.messageText()).append("\n");
        if (ctx.recentConversation() != null && !ctx.recentConversation().isEmpty()) {
            sb.append("最近对话:\n");
            for (String line : ctx.recentConversation()) sb.append("- ").append(line).append("\n");
        }
        sb.append("你现在: ").append(ctx.activityDesc()).append(" (").append(ctx.availability()).append(")\n");
        sb.append("精力 ").append(round(ctx.energy())).append(", 压力 ").append(round(ctx.stress()))
                .append(", 情绪 mood=").append(ctx.mood())
                .append(" (hurt ").append(round(ctx.hurt())).append(", anger ").append(round(ctx.anger()))
                .append(", sad ").append(round(ctx.sadness())).append(", anxious ").append(round(ctx.anxiety()))
                .append(", warm ").append(round(ctx.warmth())).append(")\n");
        sb.append("消息被注意概率 ").append(round(ctx.noticeProbability()))
                .append(", 打开手机概率 ").append(round(ctx.inspectProbability()))
                .append(", 手机在身边 ").append(ctx.phoneNearby())
                .append(", 手机模式 ").append(ctx.phoneMode()).append("\n");
        if (ctx.drives() != null) {
            sb.append("行为倾向(仅供参考,不要机械使用): reply=").append(round(ctx.drives().desireToReply()))
                    .append(" avoid=").append(round(ctx.drives().desireToAvoid()))
                    .append(" share=").append(round(ctx.drives().desireToShare()))
                    .append(" rest=").append(round(ctx.drives().desireToRest())).append("\n");
        }
        sb.append("关系: ").append(ctx.relationshipStage() == null ? "初识" : ctx.relationshipStage())
                .append(", 亲密度 ").append(round(ctx.closeness())).append("\n");
        sb.append("感知: intent=").append(ctx.perceptionIntent()).append(", emotion=")
                .append(ctx.perceptionEmotion()).append("\n");
        sb.append("消息是否已打开(checked): ").append(ctx.messageChecked()).append("\n");
        return sb.toString();
    }

    private static String round(double v) {
        return String.format("%.2f", v);
    }
}
