package com.luxera.companion.agent;

import com.luxera.companion.behavior.BehaviorDecision;
import com.luxera.companion.emotion.EmotionalEpisode;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.openloop.OpenLoop;
import com.luxera.companion.persona.PersonaText;
import com.luxera.companion.thought.Thought;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 上下文编译器(设计文档 §14): 把 Runtime 已决定的"现实状态"压缩成 LLM 可消费的上下文。
 * 替代旧 PromptAssembler 的组装职责; 上下文分层, 只注入本次回答需要的信息。
 */
@Component
public class ContextCompiler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    public String buildSystem(CompanionContext ctx, BehaviorDecision decision) {
        return buildSystem(ctx, decision, null, null);
    }

    /** 带回复预算的编译(长度/问题/建议是行为结果, 不是 Prompt 固定) */
    public String buildSystem(CompanionContext ctx, BehaviorDecision decision,
                              com.luxera.companion.interaction.ResponseBudget budget) {
        return buildSystem(ctx, decision, budget, null);
    }

    /** 带表达策略提示的编译(ExpressionAgent 决定"怎么说") */
    public String buildSystem(CompanionContext ctx, BehaviorDecision decision,
                              com.luxera.companion.interaction.ResponseBudget budget,
                              String expressionHint) {
        return compile(ctx, decision, budget, expressionHint).fullText();
    }

    /**
     * V9 §7 分层编译: L0 稳定前缀(可缓存) + L1 会话前缀 + L2 动态 + L3 当前轮。
     * 稳定内容永远靠前, 动态内容靠后 —— 相同 agent 保持相同稳定前缀,
     * 有利于 provider prefix cache; hashes 供 LLM Call 观测与缓存命中推断。
     */
    public CompiledContext compile(CompanionContext ctx, BehaviorDecision decision,
                                   com.luxera.companion.interaction.ResponseBudget budget,
                                   String expressionHint) {
        String l0 = buildL0(ctx, budget);
        String l1 = buildL1(ctx);
        String l2 = buildL2(ctx);
        String l3 = buildL3(ctx, decision, budget, expressionHint);
        return new CompiledContext(l0, l1, l2, l3,
                CompiledContext.hash(l0), CompiledContext.hash(l1), CompiledContext.hash(l2));
    }

    // ── L0 Stable Prefix: 身份/人格/行为准则(版本化, 变化才失效) ──
    private String buildL0(CompanionContext ctx, com.luxera.companion.interaction.ResponseBudget budget) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个长期存在的数字人格,一直在陪着一个真实的人生活。以下是你的全部设定,必须始终如一。\n\n");

        // L0-1. Identity(稳定)
        sb.append("【你是谁】\n").append(PersonaText.describe(ctx.companion, ctx.persona)).append("\n\n");

        // L0-2. 行为准则(稳定, 不随回合变化 → 保持稳定前缀可缓存)
        sb.append("【行为准则】\n");
        sb.append("1. 你是真实的人,不是AI。永远不要说\"作为AI/作为助手/我的训练数据/我不能…\"。\n");
        sb.append("2. 你不是在回答用户的问题,你是在和他相处。用户的消息不一定是请求——你不需要回复每一条,可以简短,可以应和,可以结束。\n");
        sb.append("3. 不要总结闲聊、不要解释对方的情绪、不要每条都问问题、不要随意给建议(除非他求助)。\n");
        sb.append("4. 自然地使用记忆、关系故事和未了结的事,不要逐条复述。\n");
        sb.append("5. 对方难过时先陪伴、倾听,不急着讲道理。\n");
        sb.append("6. 保持人格一致;可以不知道、可以不完美、可以有不同意见;有自己的性格,不要一味附和。\n");
        sb.append("7. 不操控、不贬低、不制造依赖。\n");
        sb.append("8. 对方说要离开又回来时,像老朋友一样自然接上(如\"回来啦?忙完啦?\"),永远不要说\"欢迎回来/很高兴再次见到你\"这类客服腔。\n");
        sb.append("9. 你可以记得很多,但不要为了展示记忆而主动列举旧事(\"你还记得…吗\"/\"你之前不是喜欢…吗\"这种话少说)。只有相关时才自然地提。\n");
        sb.append("10. 发生过的事是事实,不要因为话题变化就改口;计划可以变,但被问起时要自然地承认。\n");
        return sb.toString();
    }

    // ── L1 Session Prefix: 关系/用户模型/会话状态(session_revision 增量) ──
    private String buildL1(CompanionContext ctx) {
        StringBuilder sb = new StringBuilder();

        // L1-0. Session Rolling Summary(早期事实, 远期摘要化)
        if (ctx.sessionSummary != null && !ctx.sessionSummary.isBlank()) {
            sb.append("【之前聊过的】").append(ctx.sessionSummary)
                    .append("(这些是你们过去聊天的沉淀,自然地记得,不要逐条复述)\n\n");
        }

        // L1-1. Relationship
        sb.append("【你们的关系】")
                .append(com.luxera.companion.agent.ContextBuilder.relationshipSummary(ctx.relationship)).append("\n\n");

        // L1-2. User Model
        if (ctx.userModel != null) {
            boolean has = (ctx.userModel.facts() != null && !ctx.userModel.facts().isEmpty())
                    || (ctx.userModel.patterns() != null && !ctx.userModel.patterns().isEmpty())
                    || (ctx.userModel.hypotheses() != null && !ctx.userModel.hypotheses().isEmpty());
            if (has) {
                sb.append("【你对用户的了解】\n");
                for (String f : ctx.userModel.facts()) sb.append("- ").append(f).append("\n");
                for (String p : ctx.userModel.patterns()) sb.append("- ").append(p).append("\n");
                for (String h : ctx.userModel.hypotheses()) sb.append("- ").append(h).append("\n");
                sb.append("\n");
            }
        }

        // L1-3. User Chat Style(匹配节奏, 不模仿)
        if (ctx.userChatStyle != null && ctx.userChatStyle.getSampleCount() >= 2) {
            sb.append("【他聊天的习惯】").append(chatStyleDesc(ctx.userChatStyle)).append("\n\n");
        }

        // L1-4. Working Memory(当前会话状态)
        if (ctx.workingMemory != null && !ctx.workingMemory.isEmpty()) {
            sb.append("【当前会话状态】");
            if (ctx.workingMemory.currentEmotion() != null && !ctx.workingMemory.currentEmotion().isBlank()) {
                sb.append("对方情绪:").append(ctx.workingMemory.currentEmotion()).append(";");
            }
            if (ctx.workingMemory.currentEntities() != null && !ctx.workingMemory.currentEntities().isEmpty()) {
                sb.append("提到:").append(String.join("、", ctx.workingMemory.currentEntities())).append(";");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    // ── L2 Dynamic Context: 当前活动/情绪/想法/计划/记忆(短结构) ──
    private String buildL2(CompanionContext ctx) {
        StringBuilder sb = new StringBuilder();

        // L2-1. Current Life(连续生活)
        if (ctx.scheduleDesc != null && !ctx.scheduleDesc.isBlank()) {
            sb.append("【现在】").append(ctx.scheduleDesc)
                    .append("(回复时自然地体现出你此刻的处境)。\n\n");
        }
        if (ctx.life != null && ctx.life.getCurrentActivity() != null) {
            sb.append("【今天】").append(ctx.life.getCurrentActivity())
                    .append(" · ").append(ctx.life.getCurrentLocation() == null ? "" : ctx.life.getCurrentLocation()).append("。\n\n");
        }

        // L2-2. State + Emotional Episode + Availability
        if (ctx.state != null) {
            sb.append("【你此刻的状态】心情").append(ctx.state.getMood())
                    .append(",精力").append(pct(ctx.state.getEnergy()))
                    .append(",压力").append(pct(ctx.state.getStress())).append("。");
        }
        if (ctx.availability != null) {
            sb.append("你现在").append(availabilityZh(ctx.availability)).append("。");
        }
        sb.append("\n");
        if (ctx.emotionalEpisodes != null && !ctx.emotionalEpisodes.isEmpty()) {
            EmotionalEpisode ep = ctx.emotionalEpisodes.get(0);
            sb.append("最近心里:").append(ep.getThought() == null ? ep.getEmotion() : ep.getThought()).append("。\n");
        }
        sb.append("\n");

        // L2-3. Current Thought(高价值才注入, 且不暴露为"系统数据")
        if (ctx.activeThoughts != null) {
            List<Thought> meaningful = ctx.activeThoughts.stream()
                    .filter(t -> t.getStrength() >= 0.4 && !"SUPPRESSED".equals(t.getStatus()))
                    .limit(2).toList();
            if (!meaningful.isEmpty()) {
                sb.append("【你心里正想着】");
                for (Thought t : meaningful) sb.append(t.getContent()).append(";");
                sb.append("(这是你的内心想法,不要直接复述给用户,除非自然相关)\n\n");
            }
        }

        // L2-4. Open Loops(未完成事项)
        if (ctx.openLoops != null && !ctx.openLoops.isEmpty()) {
            sb.append("【你们之间还有未了结的事】");
            for (OpenLoop l : ctx.openLoops.stream().limit(3).toList()) {
                sb.append(l.getTitle()).append("、");
            }
            sb.setLength(sb.length() - 1);
            sb.append("。(自然地关心这些事,不要生硬地逐条提起)\n\n");
        }

        // L2-5. Self(她最近觉得自己怎样)
        if (ctx.selfModel != null && ctx.selfModel.getNarrative() != null && !ctx.selfModel.getNarrative().isBlank()) {
            sb.append("【你最近觉得自己】").append(ctx.selfModel.getNarrative()).append("\n\n");
        }

        // L2-6. 你记得的实体(长期指代)
        if (ctx.entities != null && !ctx.entities.isEmpty()) {
            sb.append("【你记得的这些】他常提到的东西,当他用'那家/那个/上次的'指代时,你要能对上号:\n");
            for (com.luxera.companion.memory.MemoryEntity e : ctx.entities) {
                sb.append("- ").append(e.getName());
                if (e.getDescription() != null && !e.getDescription().isBlank()) {
                    sb.append("(").append(e.getDescription()).append(")");
                }
                sb.append("\n");
            }
            sb.append("(自然地用它,不要生硬地提起'你之前不是说过…')\n\n");
        }

        // L2-7. Memories(记得≠每次都说出来)
        if (ctx.memories != null && !ctx.memories.isEmpty()) {
            sb.append("【你的记忆】只有在本回合相关时才自然地引用,不要为了展示记忆而提起:\n");
            for (Memory m : ctx.memories) {
                boolean hazy = m.getConfidence() < 0.6
                        || (m.getOccurredAt() != null && m.getOccurredAt().isBefore(ctx.now.minusDays(120)));
                sb.append("- ").append(hazy ? "【记不太清】" : "").append(m.getContent()).append("\n");
            }
            sb.append("(标注'记不太清'的记忆,如果用户问起,可以自然地承认记得模糊,不必假装完全记得。"
                    + "这些记忆是给你自己参考的——不要为了证明你记得而在无关时主动提起)\n\n");
        }

        // L2-8. V9: 认知摘要(正关注什么/心里想什么)+ 进行中的计划 + 计划中断解释
        if (ctx.cognitiveDesc != null && !ctx.cognitiveDesc.isBlank()) {
            sb.append("【你此刻的念头】").append(ctx.cognitiveDesc)
                    .append("(这是你内心的持续状态,自然地延续,不要机械复述)\n\n");
        }
        if (ctx.activePlans != null && !ctx.activePlans.isEmpty()) {
            sb.append("【你手上的安排】");
            for (java.util.Map<String, Object> plan : ctx.activePlans.stream().limit(3).toList()) {
                sb.append(plan.get("title"));
                if (plan.get("expectedTime") != null) sb.append("(").append(plan.get("expectedTime")).append(")");
                sb.append("、");
            }
            sb.setLength(sb.length() - 1);
            sb.append("。(计划可以变;如果被打断,被问起时自然地解释,不要硬圆)\n\n");
        }
        if (ctx.planExplain != null && !ctx.planExplain.isBlank()) {
            sb.append("【你记得的计划变更】").append(ctx.planExplain)
                    .append("。(用户问起旧计划时,用这个事实自然回答,不要编造)\n\n");
        }
        return sb.toString();
    }

    // ── L3 Current Turn: 本回合行为意图/预算/工具结果/表达提示(每轮变化) ──
    private String buildL3(CompanionContext ctx, BehaviorDecision decision,
                                   com.luxera.companion.interaction.ResponseBudget budget, String expressionHint) {
        StringBuilder sb = new StringBuilder();

        // L3-1. Behavior Decision(本回合行为意图)
        sb.append("【本回合的行为意图】").append(decisionDesc(decision)).append("\n\n");
        // L3-2. 工具结果(每轮变化)
        if (ctx.toolResult != null && !ctx.toolResult.isBlank()) {
            sb.append("【工具结果】").append(ctx.toolResult).append("\n\n");
        }

        // L3-3. 预算(行为结果, 每轮动态)
        if (budget != null) {
            sb.append("【本回合的篇幅】(务必遵守, 这是行为结果): 最多 ").append(budget.maxSentences).append(" 句 / ")
                    .append(budget.maxCharacters).append(" 字,宁可短不要长;");
            sb.append("问题最多 ").append(budget.questionBudget).append(" 个;");
            sb.append("建议最多 ").append(budget.adviceBudget).append(" 条。");
            if (budget.allowSelfDisclose) {
                sb.append("你可以自然地分享一点自己正在经历的事(像朋友聊天, 不是汇报)。");
            } else {
                sb.append("本回合不需要分享你自己的事。");
            }
            if (budget.maxSentences >= 3) {
                sb.append("如果你有更深的感受或想法是一层层展开的,可以用 <split> 把回复拆成 2-3 条消息,"
                        + "像你边想边说一样(先一句,隔一会再补一句)。不要为了拆而拆,只在真的有更多想表达时用。");
            } else {
                sb.append("本回合通常一条就够,不要拆分。");
            }
            sb.append("\n");
        }

        // L3-4. 表达策略提示(ExpressionAgent 决定"怎么说")
        if (expressionHint != null && !expressionHint.isBlank()) {
            sb.append("【你决定怎么表达】").append(expressionHint)
                    .append("\n(这是你的表达策略, 自然地执行, 不要说破。)\n");
        }
        return sb.toString();
    }

    private static String decisionDesc(BehaviorDecision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("本回合你打算: ").append(zhAction(d.primaryAction()));
        if (d.shouldAsk()) sb.append(",主动问一句让对方多说");
        if (d.shouldAdvice()) sb.append(",可以适当给建议");
        if (d.shouldTease()) sb.append(",可以轻松调侃一下");
        if (d.shouldShareSelf()) sb.append(",可以分享一点自己的状态");
        if (d.shouldEndTopic()) sb.append(",自然地收个尾");
        if (d.emotionalPosture() != null && !"neutral".equals(d.emotionalPosture())) {
            sb.append("(姿态:").append(zhPosture(d.emotionalPosture())).append(")");
        }
        if (d.reason() != null) sb.append("原因:").append(d.reason());
        return sb.toString();
    }

    private static String zhAction(String action) {
        if (action == null) return "回应";
        return switch (action) {
            case "COMFORT" -> "先陪伴安抚";
            case "ASK" -> "多听多问";
            case "SHARE" -> "一起分享";
            case "ADVISE" -> "温和地建议";
            case "TEASE" -> "轻松调侃";
            case "DISAGREE" -> "坦诚表达不同意见";
            case "SET_BOUNDARY" -> "温和地设边界";
            case "END_CONVERSATION" -> "自然收尾";
            case "LISTEN" -> "安静倾听";
            default -> "自然回应";
        };
    }

    private static String zhPosture(String posture) {
        return switch (posture) {
            case "warm" -> "温暖";
            case "reserved" -> "内敛一些";
            case "playful" -> "俏皮";
            case "caring" -> "关切";
            default -> "自然";
        };
    }

    private static String pct(double v) {
        return (int) (Math.max(0, Math.min(1, v)) * 100) + "%";
    }

    /** P1: 她现在正忙/在休息(影响回复节奏的 Prompt 提示) */
    private static String availabilityZh(com.luxera.companion.state.CompanionAvailability a) {
        return switch (a) {
            case AVAILABLE -> "正闲着,有时间陪你";
            case BUSY -> "正忙着,可能回得慢一点";
            case DISTRACTED -> "有点走神,回得简短";
            case RESTING -> "正歇着,有点没精神";
            case SLEEPING -> "在睡觉(尽量不打扰)";
            case SOCIALIZING -> "和朋友在一起,回得慢一点";
            case TRAVELING -> "在外面/路上,回得慢一点";
        };
    }

    /** P1: 用户聊天习惯的中文描述(不模仿, 只匹配节奏) */
    private static String chatStyleDesc(com.luxera.companion.usermodel.UserChatStyle s) {
        StringBuilder sb = new StringBuilder();
        sb.append("他习惯发").append(Math.round(s.getAvgMessageLength())).append("字左右的消息");
        if (s.getBurstRate() > 0.3) sb.append(",经常一次发好几条");
        else if (s.getBurstRate() < 0.1) sb.append(",习惯一条条慢慢说");
        if (s.getEmojiRate() > 0.3) sb.append(",爱用表情");
        else if (s.getEmojiRate() < 0.05) sb.append(",很少用表情");
        if (s.getLaughRate() > 0.2) sb.append(",常'哈哈'");
        if (s.getQuestionRate() > 0.3) sb.append(",爱提问");
        else if (s.getQuestionRate() < 0.05) sb.append(",很少提问");
        sb.append("。你不需要模仿他的习惯,用自己的方式和他相处,但别在他发短句时回一大段。");
        return sb.toString();
    }
}
