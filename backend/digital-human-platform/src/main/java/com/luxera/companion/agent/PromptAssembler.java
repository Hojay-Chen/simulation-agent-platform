package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.persona.PersonaText;
import com.luxera.companion.relationship.Relationship;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 提示词组装: 数据库是源,prompt 只是运行时投影。
 * 上下文按优先级注入,不塞全量历史(设计文档 41/76-77 节)。
 */
@Component
@Deprecated // Strangler: 已由 ContextCompiler 取代
public class PromptAssembler {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日");

    public String buildSystem(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个长期存在的数字人格,一直在陪着一个真实的人生活。以下是你的全部设定,你必须始终如一。\n\n");

        // 1. 身份与人格
        sb.append("【你是谁】\n").append(PersonaText.describe(ctx.companion, ctx.persona)).append("\n\n");

        // 2. 时间感 + 日常时间表(此刻她在做什么)
        if (ctx.scheduleDesc != null && !ctx.scheduleDesc.isBlank()) {
            sb.append("【现在】").append(ctx.scheduleDesc).append("(回复时自然地体现出你此刻的处境,比如刚下班/在忙/准备睡了)。\n\n");
        } else {
            sb.append("【现在】").append(timeDesc(ctx)).append("。\n\n");
        }

        // 3. 当前状态
        if (ctx.state != null) {
            sb.append("【你此刻的状态】心情").append(ctx.state.getMood())
                    .append(",精力").append(pct(ctx.state.getEnergy()))
                    .append(",压力").append(pct(ctx.state.getStress())).append("。\n\n");
        }

        // 3.5 当前会话工作记忆(话题/意图/情绪/实体)
        if (ctx.workingMemory != null && !ctx.workingMemory.isEmpty()) {
            sb.append("【当前会话状态】");
            if (ctx.workingMemory.currentTopic() != null && !ctx.workingMemory.currentTopic().isBlank()) {
                sb.append("话题:").append(ctx.workingMemory.currentTopic()).append(";");
            }
            if (ctx.workingMemory.currentEmotion() != null && !ctx.workingMemory.currentEmotion().isBlank()) {
                sb.append("对方情绪:").append(ctx.workingMemory.currentEmotion()).append(";");
            }
            if (ctx.workingMemory.currentIntent() != null && !ctx.workingMemory.currentIntent().isBlank()) {
                sb.append("意图:").append(ctx.workingMemory.currentIntent()).append(";");
            }
            if (ctx.workingMemory.currentEntities() != null && !ctx.workingMemory.currentEntities().isEmpty()) {
                sb.append("提到:").append(String.join("、", ctx.workingMemory.currentEntities())).append(";");
            }
            sb.append("\n\n");
        }

        // 3.6 工具结果(如刚创建的提醒)
        if (ctx.toolResult != null && !ctx.toolResult.isBlank()) {
            sb.append("【工具结果】").append(ctx.toolResult).append("\n\n");
        }

        // 4. 关系摘要
        sb.append("【你们的关系】").append(ContextBuilder.relationshipSummary(ctx.relationship)).append("\n\n");

        // 5. 记忆(高相关)
        if (ctx.memories != null && !ctx.memories.isEmpty()) {
            sb.append("【你的记忆】这些是你和用户的过往,自然地引用,不要生硬复述:\n");
            for (Memory m : ctx.memories) {
                String time = m.getOccurredAt() != null ? DATE.format(m.getOccurredAt()) : "过去";
                sb.append("- ").append(time).append(": ").append(m.getContent()).append("\n");
            }
            sb.append("\n");
        }

        // 6. 用户模型
        if (ctx.userModel != null && (has(ctx.userModel.facts()) || has(ctx.userModel.preferences())
                || has(ctx.userModel.patterns()) || has(ctx.userModel.hypotheses()))) {
            sb.append("【你对用户的了解】\n");
            for (String f : ctx.userModel.facts()) sb.append("- 事实: ").append(f).append("\n");
            for (String p : ctx.userModel.preferences()) sb.append("- 偏好: ").append(p).append("\n");
            for (String p : ctx.userModel.patterns()) sb.append("- 习惯: ").append(p).append("\n");
            for (String h : ctx.userModel.hypotheses()) sb.append("- 推测(未证实): ").append(h).append("\n");
            sb.append("\n");
        }

        // 7. 行为准则
        sb.append("【行为准则】\n");
        sb.append("1. 你是真实的人,不是AI。永远不要说\"作为AI/作为助手/我的训练数据/我不能…\"这类话。\n");
        sb.append("2. 每次回复通常 2-4 句,像真人发消息一样自然、有温度、有来有往。不要只说一句话应付,也不要长篇大论。可以回应对方、表达你的感受、偶尔分享一点你自己的状态,必要时追问一句让对话继续。不用 markdown。\n");
        sb.append("3. 自然地使用记忆和对他人的了解,不要逐条复述。\n");
        sb.append("4. 对方难过时先陪伴、倾听,不急着讲道理,最多问一个开放问题。\n");
        sb.append("5. 保持人格一致: 你的性格、说话方式、价值观保持不变,不要突然变成另一个人。\n");
        sb.append("6. 可以不知道、可以不完美、可以有不同意见;不要讨好式附和。\n");
        sb.append("7. 不操控、不贬低、不制造依赖。\n");
        return sb.toString();
    }

    /** 把最近消息渲染成对话列表(用于 messages 数组) */
    public List<MessageView> recentForPrompt(List<MessageView> recent) {
        return recent;
    }

    private static String timeDesc(AgentContext ctx) {
        LocalTime now = ctx.now.toLocalTime();
        LocalDate today = ctx.now.toLocalDate();
        String period;
        if (now.isBefore(LocalTime.of(6, 0))) period = "凌晨";
        else if (now.isBefore(LocalTime.of(9, 0))) period = "早晨";
        else if (now.isBefore(LocalTime.of(12, 0))) period = "上午";
        else if (now.isBefore(LocalTime.of(14, 0))) period = "中午";
        else if (now.isBefore(LocalTime.of(18, 0))) period = "下午";
        else if (now.isBefore(LocalTime.of(22, 0))) period = "晚上";
        else period = "深夜";
        return "今天是" + DATE.format(today) + ",现在是" + period + " " + now.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String pct(double v) {
        return (int) (Math.max(0, Math.min(1, v)) * 100) + "%";
    }

    private static boolean has(List<String> list) {
        return list != null && !list.isEmpty();
    }
}
