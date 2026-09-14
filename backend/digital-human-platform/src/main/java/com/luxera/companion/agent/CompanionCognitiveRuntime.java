package com.luxera.companion.agent;

import com.luxera.companion.contracts.api.MessageView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一认知内核(设计文档 §27): 用户消息与无人状态运行使用同一个内核。
 * 这是 最重要的架构统一。
 */
public interface CompanionCognitiveRuntime {

    /** V9 §14: 认知路径 —— FAST(轻量上下文, 寒暄/短消息) / DEEP(完整上下文, 复杂/重要消息) */
    String PATH_FAST = "FAST";
    String PATH_DEEP = "DEEP";

    /** 处理一条用户消息(同步生成回复, 异步学习) */
    CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                       String userMessageId, String userText,
                                       List<MessageView> recentMessages, Consumer<String> onDelta);

    /** 带交互决策(预算)的处理 */
    default CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                               String userMessageId, String userText,
                                               List<MessageView> recentMessages, Consumer<String> onDelta,
                                               com.luxera.companion.interaction.InteractionDecision interaction) {
        return processUserMessage(userId, companionId, conversationId, userMessageId, userText,
                recentMessages, onDelta);
    }

    /** 带表达策略提示的处理 */
    default CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                               String userMessageId, String userText,
                                               List<MessageView> recentMessages, Consumer<String> onDelta,
                                               com.luxera.companion.interaction.InteractionDecision interaction,
                                               String expressionHint) {
        return processUserMessage(userId, companionId, conversationId, userMessageId, userText,
                recentMessages, onDelta, interaction, expressionHint, PATH_DEEP);
    }

    /** V9: 带认知路径的处理(FAST 轻量 / DEEP 完整) */
    default CognitiveResult processUserMessage(String userId, String companionId, String conversationId,
                                               String userMessageId, String userText,
                                               List<MessageView> recentMessages, Consumer<String> onDelta,
                                               com.luxera.companion.interaction.InteractionDecision interaction,
                                               String expressionHint, String path) {
        return processUserMessage(userId, companionId, conversationId, userMessageId, userText,
                recentMessages, onDelta, interaction, expressionHint);
    }

    /** 无用户交互时的生命/思想/情绪/主动推进 */
    void tick(String companionId, LocalDateTime now);
}
