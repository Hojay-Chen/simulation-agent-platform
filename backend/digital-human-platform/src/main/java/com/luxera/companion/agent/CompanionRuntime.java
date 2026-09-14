package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 伴侣运行时(Strangler Pattern): 委托给统一认知内核 CompanionCognitiveRuntime。
 * 旧模块退化为薄门面, 保留对外签名以兼容 ChatController。
 */
@Slf4j
@Component
public class CompanionRuntime {

    private final CompanionCognitiveRuntime cognitiveRuntime;

    public CompanionRuntime(CompanionCognitiveRuntime cognitiveRuntime) {
        this.cognitiveRuntime = cognitiveRuntime;
    }

    public ChatOutcome generate(String userId, String companionId, String conversationId, String userMessageId,
                                String userText, List<MessageView> recentMessages, Consumer<String> onDelta) {
        return generate(userId, companionId, conversationId, userMessageId, userText, recentMessages, onDelta, null);
    }

    /** 带交互决策(预算)的生成 */
    public ChatOutcome generate(String userId, String companionId, String conversationId, String userMessageId,
                                String userText, List<MessageView> recentMessages, Consumer<String> onDelta,
                                com.luxera.companion.interaction.InteractionDecision interaction) {
        return generate(userId, companionId, conversationId, userMessageId, userText, recentMessages,
                onDelta, interaction, null);
    }

    /** 带表达策略提示的生成(ExpressionAgent 决定"怎么说") */
    public ChatOutcome generate(String userId, String companionId, String conversationId, String userMessageId,
                                String userText, List<MessageView> recentMessages, Consumer<String> onDelta,
                                com.luxera.companion.interaction.InteractionDecision interaction,
                                String expressionHint) {
        return generate(userId, companionId, conversationId, userMessageId, userText, recentMessages,
                onDelta, interaction, expressionHint, CompanionCognitiveRuntime.PATH_DEEP);
    }

    /** V9: 带认知路径的生成(FAST 轻量上下文 / DEEP 完整上下文) */
    public ChatOutcome generate(String userId, String companionId, String conversationId, String userMessageId,
                                String userText, List<MessageView> recentMessages, Consumer<String> onDelta,
                                com.luxera.companion.interaction.InteractionDecision interaction,
                                String expressionHint, String path) {
        CognitiveResult result = cognitiveRuntime.processUserMessage(
                userId, companionId, conversationId, userMessageId, userText, recentMessages, onDelta,
                interaction, expressionHint, path);
        return new ChatOutcome(result.reply(), result.rawReply(), result.perception(), result.context());
    }

    /** 无用户交互时的内核推进 */
    public void tick(String companionId) {
        cognitiveRuntime.tick(companionId, LocalDateTime.now());
    }

    /** 一次对话的产物 */
    public record ChatOutcome(String reply, String rawReply,
                              PerceptionEngine.Perception perception, CompanionContext context) {}
}
