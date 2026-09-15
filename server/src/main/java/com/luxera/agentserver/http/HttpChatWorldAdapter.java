package com.luxera.agentserver.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ChatWorldPort} 的 HTTP 适配器 —— 仓 2 数字人认知链读/写聊天世界的唯一通道。
 *
 * <p>语义按 G2 占位哲学对称落地:
 * <ul>
 *   <li><b>读</b>(messages/conversation/…): chat 平台缺席 → 空（"世界暂时是空的"，
 *     fire-and-forget 下不是失败）；404 → {@code Optional.empty()}（"没有这条消息"）。</li>
 *   <li><b>写/变更</b>(append/ensureConversation): chat 平台缺席 → 抛
 *     {@link IllegalStateException}（沉默地假装写成功比诚实地说做不到糟糕得多）。
 *     append 的幂等由仓 1 的 {@code client_message_id} 唯一约束兜底（{@code Idempotency-Key}
 *     头直传）。</li>
 *   <li><b>fire-and-forget</b>(markRead/deliveryStatus/updatePerception/publishEvent/…):
 *     任何失败都吞掉只 log —— chat 缺席时认知链照常跑，世界状态由 outbox/重试补。</li>
 * </ul>
 *
 * <p>构造函数签名含全部依赖，便于将来由 @Configuration 装配而不必硬编码配置键名。
 */
@Slf4j
public class HttpChatWorldAdapter extends HttpClientSupport implements ChatWorldPort {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public HttpChatWorldAdapter(String baseUrl, String serviceKey, ObjectMapper objectMapper,
                                int timeoutMillis) {
        super(baseUrl, serviceKey, objectMapper, timeoutMillis);
    }

    @Override protected org.slf4j.Logger log() { return log; }

    // ── 读 ─────────────────────────────────────────────────────────────────

    @Override
    public List<MessageView> messages(String conversationId) {
        return getList("/internal/world/conversations/" + conversationId + "/messages",
                listOf(MessageView.class));
    }

    @Override
    public List<MessageView> recentMessages(String conversationId, int limit) {
        return getList("/internal/world/conversations/" + conversationId + "/messages?limit=" + limit,
                listOf(MessageView.class));
    }

    @Override
    public Optional<MessageView> message(String messageId) {
        return get("/internal/world/messages/" + messageId, MessageView.class);
    }

    @Override
    public List<MessageView> userMessagesSince(String companionId, LocalDateTime since) {
        return getList("/internal/world/companions/" + companionId + "/user-messages?since="
                + since.format(ISO), listOf(MessageView.class));
    }

    @Override
    public List<MessageView> messagesBetween(String companionId, LocalDateTime since,
                                             LocalDateTime until) {
        return getList("/internal/world/companions/" + companionId + "/window?since="
                + since.format(ISO) + "&until=" + until.format(ISO), listOf(MessageView.class));
    }

    @Override
    public List<MessageView> recentByCompanionAndKind(String companionId, String kind, int limit) {
        return getList("/internal/world/companions/" + companionId + "/by-kind?kind=" + kind
                + "&limit=" + limit, listOf(MessageView.class));
    }

    @Override
    public long countByCompanionAndKindSince(String companionId, String kind, LocalDateTime since) {
        return get("/internal/world/companions/" + companionId + "/count-by-kind?kind=" + kind
                        + "&since=" + since.format(ISO), CountResponse.class)
                .map(CountResponse::count).orElse(0L);
    }

    @Override
    public Optional<ConversationView> conversation(String conversationId) {
        return get("/internal/world/conversations/" + conversationId, ConversationView.class);
    }

    @Override
    public List<ConversationView> conversations(String userId, String companionId) {
        return getList("/internal/world/threads?userId=" + userId + "&companionId=" + companionId,
                listOf(ConversationView.class));
    }

    @Override
    public List<ConversationView> conversationsOf(String companionId) {
        return getList("/internal/world/threads-of/" + companionId, listOf(ConversationView.class));
    }

    @Override
    public Optional<ConversationView> conversationFor(String userId, String companionId) {
        return get("/internal/world/thread-for?userId=" + userId + "&companionId=" + companionId,
                ConversationView.class);
    }

    // ── 写 ─────────────────────────────────────────────────────────────────

    @Override
    public ConversationView ensureConversation(String userId, String companionId,
                                               String companionName) {
        return post("/internal/world/conversations/ensure",
                Map.of("userId", userId, "companionId", companionId, "companionName", companionName),
                ConversationView.class);
    }

    @Override
    public MessageView append(MessageAppendCommand command) {
        return post("/internal/world/messages", command, MessageView.class);
    }

    // ── fire-and-forget ─────────────────────────────────────────────────────

    @Override
    public void markRead(String companionId, Collection<String> messageIds) {
        postFireAndForget("/internal/world/messages/mark-read",
                Map.of("companionId", companionId, "messageIds", messageIds));
    }

    @Override
    public void updateDeliveryStatus(String companionId, Collection<String> messageIds,
                                     String status) {
        postFireAndForget("/internal/world/messages/delivery-status",
                Map.of("companionId", companionId, "messageIds", messageIds, "status", status));
    }

    @Override
    public void updatePerception(String messageId, String intent, String emotion, String topic) {
        patch("/internal/world/messages/" + messageId + "/perception",
                Map.of("intent", nullToEmpty(intent), "emotion", nullToEmpty(emotion),
                        "topic", nullToEmpty(topic)), Void.class);
    }

    @Override
    public void publishEvent(String companionId, String type, Map<String, Object> payload) {
        postFireAndForget("/internal/world/events",
                Map.of("companionId", companionId, "type", type,
                        "payload", payload == null ? Map.of() : payload));
    }

    @Override
    public void recordBoundary(String companionId, String conversationId, String type,
                               String reason) {
        postFireAndForget("/internal/world/boundaries",
                Map.of("companionId", companionId, "conversationId", conversationId,
                        "type", type, "reason", reason));
    }

    @Override
    public void touchThread(String companionId, String conversationId, String topic,
                            String emotion) {
        postFireAndForget("/internal/world/threads/touch",
                Map.of("companionId", companionId, "conversationId", conversationId,
                        "topic", nullToEmpty(topic), "emotion", nullToEmpty(emotion)));
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private com.fasterxml.jackson.databind.JavaType listOf(Class<?> elementType) {
        return com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance()
                .constructCollectionType(List.class, elementType);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private record CountResponse(long count) {}
}
