package com.luxera.companion.digitalhuman.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.dhcp.DhcpFrame;
import com.luxera.companion.contracts.simulator.CapabilityResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V10 §4 ChatSimulatorClient — 数字人访问聊天平台的 WS 客户端门面。
 *
 * 底层通过 {@link ChatSimulatorConnector} 走 WebSocket DHCP v1 协议, 而非直接调
 * ConversationService(JPA 层) —— 也就是 DH 侧完全不碰 chat 的任何类。
 *
 * 启用条件: app.simulator.backend=websocket(默认 inprocess, 向前兼容)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.simulator.backend", havingValue = "websocket")
public class ChatSimulatorClient {

    private final ChatSimulatorConnector connector;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatSimulatorClient(ChatSimulatorConnector connector) {
        this.connector = connector;
    }

    // ── 会话管理 ─────────────────────────────

    public void ensureConnected(String companionId, String deviceId, String accountId, String secret) {
        if (!connector.isConnected(companionId)) {
            connector.connect(companionId, deviceId, accountId, secret);
        }
    }

    public void disconnect(String companionId) {
        connector.disconnect(companionId);
    }

    public boolean isConnected(String companionId) {
        return connector.isConnected(companionId);
    }

    // ── 命令执行 ─────────────────────────────

    /**
     * 发送消息(幂等: 同 idempotencyKey 不重复发送)
     * @return CapabilityResult, 成功时 data 含 messageId/conversationId/senderType/createdAt
     */
    public CapabilityResult sendMessage(String companionId, String conversationId,
                                        String senderType, String content, String messageKind,
                                        String idempotencyKey) {
        ChatSimulatorConnector.DeviceConnection dc = connector.get(companionId);
        if (dc == null) return CapabilityResult.fail("Simulator 未连接: " + companionId);

        try {
            var args = mapper.createObjectNode()
                    .put("conversationId", conversationId)
                    .put("senderType", senderType)
                    .put("content", content)
                    .put("messageKind", messageKind != null ? messageKind : "NORMAL")
                    .put("clientMessageId", idempotencyKey);
            DhcpFrame result = dc.sendCommand("chat.sendMessage", idempotencyKey, args, 10);
            if (result == null || result.payload() == null) {
                return CapabilityResult.fail("WS 命令超时");
            }
            JsonNode payload = result.payload();
            if (!payload.has("ok") || !payload.get("ok").asBoolean()) {
                String errMsg = payload.has("error") && payload.get("error").has("message")
                        ? payload.get("error").get("message").asText() : "未知错误";
                return CapabilityResult.fail("发送失败: " + errMsg);
            }
            JsonNode data = payload.get("data");
            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("messageId", data.get("messageId").asText());
            resultData.put("conversationId", data.get("conversationId").asText());
            resultData.put("senderType", data.get("senderType").asText());
            resultData.put("createdAt", data.get("createdAt").asText());
            return CapabilityResult.ok("消息已发送", resultData);
        } catch (Exception e) {
            log.error("[ChatSimulatorClient] sendMessage 失败: companion={}", companionId, e);
            return CapabilityResult.fail("发送失败: " + e.getMessage());
        }
    }

    /**
     * 读取最近 N 条消息
     * @return CapabilityResult, 成功时 data 含 messages 数组 + count
     */
    public CapabilityResult readMessages(String companionId, String conversationId, int limit) {
        ChatSimulatorConnector.DeviceConnection dc = connector.get(companionId);
        if (dc == null) return CapabilityResult.fail("Simulator 未连接: " + companionId);

        try {
            var args = mapper.createObjectNode()
                    .put("conversationId", conversationId)
                    .put("limit", limit);
            String idemKey = "read-" + conversationId + "-" + System.nanoTime();
            DhcpFrame result = dc.sendCommand("chat.readMessages", idemKey, args, 10);
            if (result == null || result.payload() == null) {
                return CapabilityResult.fail("WS 命令超时");
            }
            JsonNode payload = result.payload();
            if (!payload.has("ok") || !payload.get("ok").asBoolean()) {
                return CapabilityResult.fail("读取失败");
            }
            JsonNode data = payload.get("data");
            int count = data.get("count").asInt();
            List<Map<String, Object>> messages = new ArrayList<>();
            if (data.has("messages")) {
                for (JsonNode m : data.get("messages")) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("id", m.get("id").asText());
                    msg.put("conversationId", m.get("conversationId").asText());
                    msg.put("senderType", m.get("senderType").asText());
                    msg.put("content", m.get("content").asText());
                    msg.put("deliveryStatus", m.get("deliveryStatus").asText());
                    msg.put("createdAt", m.get("createdAt").asText());
                    if (m.has("messageKind")) msg.put("messageKind", m.get("messageKind").asText());
                    messages.add(msg);
                }
            }
            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("messages", messages);
            resultData.put("count", count);
            return CapabilityResult.ok("读取 " + count + " 条消息", resultData);
        } catch (Exception e) {
            log.error("[ChatSimulatorClient] readMessages 失败: companion={}", companionId, e);
            return CapabilityResult.fail("读取失败: " + e.getMessage());
        }
    }

    /**
     * 更新消息投递状态(已读/忽略等)
     * @param messageIds 要更新的消息 ID 集合
     * @param status 目标状态(DELIVERED/READ/DEFERRED/IGNORED)
     */
    public CapabilityResult updateDeliveryStatus(String companionId, Set<String> messageIds,
                                                  String status) {
        ChatSimulatorConnector.DeviceConnection dc = connector.get(companionId);
        if (dc == null) return CapabilityResult.fail("Simulator 未连接: " + companionId);

        try {
            var arr = mapper.createArrayNode();
            for (String id : messageIds) arr.add(id);
            var args = mapper.createObjectNode()
                    .put("status", status)
                    .set("messageIds", arr);
            String idemKey = "status-" + status + "-" + System.nanoTime();
            DhcpFrame result = dc.sendCommand("chat.updateDeliveryStatus", idemKey, args, 10);
            if (result == null || result.payload() == null) {
                return CapabilityResult.fail("WS 命令超时");
            }
            JsonNode payload = result.payload();
            if (!payload.has("ok") || !payload.get("ok").asBoolean()) {
                return CapabilityResult.fail("状态更新失败");
            }
            int updated = payload.get("data").get("updated").asInt();
            return CapabilityResult.ok("已更新 " + updated + " 条消息状态为 " + status,
                    Map.of("updated", updated, "status", status));
        } catch (Exception e) {
            log.error("[ChatSimulatorClient] updateDeliveryStatus 失败: companion={}", companionId, e);
            return CapabilityResult.fail("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 列出会话
     */
    public CapabilityResult listConversations(String companionId, String userId, String companionIdFilter) {
        ChatSimulatorConnector.DeviceConnection dc = connector.get(companionId);
        if (dc == null) return CapabilityResult.fail("Simulator 未连接: " + companionId);

        try {
            var args = mapper.createObjectNode()
                    .put("userId", userId)
                    .put("companionId", companionIdFilter != null ? companionIdFilter : "");
            String idemKey = "list-" + System.nanoTime();
            DhcpFrame result = dc.sendCommand("chat.listConversations", idemKey, args, 10);
            if (result == null || result.payload() == null) {
                return CapabilityResult.fail("WS 命令超时");
            }
            JsonNode payload = result.payload();
            if (!payload.has("ok") || !payload.get("ok").asBoolean()) {
                return CapabilityResult.fail("列会话失败");
            }
            List<Map<String, Object>> conversations = new ArrayList<>();
            if (payload.get("data").has("conversations")) {
                for (JsonNode c : payload.get("data").get("conversations")) {
                    Map<String, Object> conv = new LinkedHashMap<>();
                    conv.put("id", c.get("id").asText());
                    conv.put("title", c.get("title").asText());
                    conv.put("lastMessageAt", c.get("lastMessageAt").asText());
                    conv.put("messageCount", c.get("messageCount").asInt());
                    conv.put("unread", c.get("unread").asBoolean());
                    conversations.add(conv);
                }
            }
            return CapabilityResult.ok("OK", Map.of("conversations", conversations));
        } catch (Exception e) {
            log.error("[ChatSimulatorClient] listConversations 失败: companion={}", companionId, e);
            return CapabilityResult.fail("列会话失败: " + e.getMessage());
        }
    }
}