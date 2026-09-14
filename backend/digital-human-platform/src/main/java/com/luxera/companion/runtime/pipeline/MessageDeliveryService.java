package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息投递服务(§11): 消息生命周期的状态转换 + 事件发布。
 * 让"送达 / 通知 / 注意到 / 打开 / 读到 / 回复"各阶段可追踪。
 *
 * <p>V10: 状态本身存在 chat 平台(消息是那边的东西), DH 只负责"我走到哪一步了"的推进与广播。
 */
@Service
public class MessageDeliveryService {

    private final ChatWorldPort chatWorld;

    public MessageDeliveryService(ChatWorldPort chatWorld) {
        this.chatWorld = chatWorld;
    }

    public void setStatus(String companionId, String messageId, String status) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), status);
        String event = MessageLifecycle.READ.equals(status) ? ChatEventTypes.MESSAGE_READ
                : ChatEventTypes.USER_MESSAGE_STATUS;
        chatWorld.publishEvent(companionId, event, Map.of("messageId", messageId, "status", status,
                "at", LocalDateTime.now().toString()));
    }

    public void delivered(String companionId, MessageView m) {
        chatWorld.updateDeliveryStatus(companionId, List.of(m.getId()), MessageLifecycle.DELIVERED);
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", m.getId(), "status", MessageLifecycle.DELIVERED));
    }

    public void notified(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.NOTIFIED);
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.NOTIFIED));
    }

    public void noticed(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.NOTICED);
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.NOTICED));
    }

    public void checked(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.CHECKED);
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", messageId, "status", MessageLifecycle.CHECKED));
    }

    public void read(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.READ);
        chatWorld.publishEvent(companionId, ChatEventTypes.MESSAGE_READ, Map.of("messageId", messageId));
    }

    public void responded(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.RESPONDED);
    }

    public void deferred(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.DEFERRED);
    }

    public void ignored(String companionId, String messageId) {
        chatWorld.updateDeliveryStatus(companionId, List.of(messageId), MessageLifecycle.IGNORED);
    }
}
