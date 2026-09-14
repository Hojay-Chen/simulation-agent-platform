package com.luxera.companion.digitalhuman.integration;

import com.luxera.companion.contracts.api.ConversationView;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.contracts.spi.SimulatorAccessPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * G2→G3 过渡: 仓 1(chat-platform) 尚未以 HTTP 适配器接入时, 仿真 Agent 平台对
 * {@link ChatWorldPort} / {@link ApplicationRuntimePort} / {@link SimulatorAccessPort}
 * 的依赖先落在这份占位实现上。住在 digitalhuman.integration 包 —— 两个服务
 * (server 8091 功能服务 / openapi 8092 对外 API 服务)都扫 com.luxera.companion 树,
 * 共享这一份占位; 测试里它同样兜底(DigitalHumanTestApplication 不再需要自带桩)。
 *
 * <p>与仓 1 {@code AgentPlatformIntegration} 的占位哲学完全对称: chat 平台缺席时
 * 仿真 Agent 平台不残废 —— 生活/认知/记忆照常运转, 只是读不到聊天世界、没有应用可玩。
 * 与 chat 侧的唯一差别是方向: 那边是"DH 不在场", 这边是"chat 不在场"。
 *
 * <p>方法分两类, 与仓 1 占位同构:
 * <ul>
 *   <li><b>读</b>(messages/read/capabilities/…): 返回空 —— 与"世界暂时是空的"等价,
 *     fire-and-forget 语义下这不是失败;</li>
 *   <li><b>写/变更</b>(append/ensureSession/execute/…): 抛错 —— 沉默地假装写成功
 *     比诚实地说做不到糟糕得多(与仓 1 requireOwned 不放行同理)。</li>
 * </ul>
 *
 * <p>G3 落地后本类删除, 由 HTTP 适配器取而代之(与仓 1 HttpCompanionDirectoryAdapter
 * 同一密钥体系: {@code app.agent-platform.internal-service-key})。留
 * {@code @ConditionalOnMissingBean} 是为了将来切换零改动: 新适配器注册后本占位自动退位。
 */
@Configuration
public class ChatPlatformIntegration {

    @Bean
    @ConditionalOnMissingBean(ChatWorldPort.class)
    ChatWorldPort placeholderChatWorld(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBase) {
        return new ChatWorldPort() {
            private IllegalStateException unavailable(String what) {
                return new IllegalStateException("chat 平台尚未接入(" + what + "), 计划地址 " + chatBase);
            }

            @Override public List<MessageView> messages(String conversationId) { return List.of(); }
            @Override public List<MessageView> recentMessages(String conversationId, int limit) { return List.of(); }
            @Override public Optional<MessageView> message(String messageId) { return Optional.empty(); }
            @Override public List<MessageView> userMessagesSince(String companionId, LocalDateTime since) { return List.of(); }
            @Override public List<MessageView> messagesBetween(String companionId, LocalDateTime since, LocalDateTime until) { return List.of(); }
            @Override public List<MessageView> recentByCompanionAndKind(String companionId, String kind, int limit) { return List.of(); }
            @Override public long countByCompanionAndKindSince(String companionId, String kind, LocalDateTime since) { return 0; }
            @Override public Optional<ConversationView> conversation(String conversationId) { return Optional.empty(); }
            @Override public List<ConversationView> conversations(String userId, String companionId) { return List.of(); }
            @Override public List<ConversationView> conversationsOf(String companionId) { return List.of(); }
            @Override public Optional<ConversationView> conversationFor(String userId, String companionId) { return Optional.empty(); }

            @Override public ConversationView ensureConversation(String userId, String companionId, String companionName) {
                throw unavailable("ensureConversation");
            }
            @Override public MessageView append(MessageAppendCommand command) { throw unavailable("append"); }
            @Override public void markRead(String companionId, Collection<String> messageIds) { /* fire-and-forget */ }
            @Override public void updateDeliveryStatus(String companionId, Collection<String> messageIds, String status) { /* fire-and-forget */ }
            @Override public void updatePerception(String messageId, String intent, String emotion, String topic) { /* fire-and-forget */ }
            @Override public void publishEvent(String companionId, String type, Map<String, Object> payload) { /* fire-and-forget */ }
            @Override public void recordBoundary(String companionId, String conversationId, String type, String reason) { /* fire-and-forget */ }
            @Override public void touchThread(String companionId, String conversationId, String topic, String emotion) { /* fire-and-forget */ }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ApplicationRuntimePort.class)
    ApplicationRuntimePort placeholderApplicationRuntime(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBase) {
        return new ApplicationRuntimePort() {
            private IllegalStateException unavailable(String what) {
                return new IllegalStateException("应用平台尚未接入(" + what + "), 计划地址 " + chatBase);
            }

            @Override public List<CapabilityView> capabilities() { return List.of(); }
            @Override public List<ApplicationView> applicationsFor(String capabilityId) { return List.of(); }
            @Override public List<ActionSpec> actionsOf(String applicationId) { return List.of(); }
            @Override public Optional<ResourceView> read(String resourceUri) { return Optional.empty(); }
            @Override public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) { return List.of(); }
            @Override public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) { return List.of(); }

            @Override public ActionResponse execute(ActionRequest request, InvocationContext ctx) {
                throw unavailable("execute");
            }
            @Override public String ensureSession(String applicationId, InvocationContext ctx) {
                throw unavailable("ensureSession");
            }
            @Override public String joinByInvitation(String token, InvocationContext ctx) {
                throw unavailable("joinByInvitation");
            }
            @Override public void joinSession(String sessionId, InvocationContext ctx) {
                throw unavailable("joinSession");
            }
            @Override public void leaveSession(String sessionId, InvocationContext ctx) {
                throw unavailable("leaveSession");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(SimulatorAccessPort.class)
    SimulatorAccessPort placeholderSimulatorAccess(
            @Value("${app.chat-platform.base-url:http://127.0.0.1:8081}") String chatBase) {
        // 设备令牌由 chat 平台签发; 平台缺席时设备一概未知 —— ChatSimulatorConnector
        // 对这个答案的既有处理就是"拿不到 token 不连", 链路安全。
        return (deviceId, secret) -> Optional.empty();
    }
}
