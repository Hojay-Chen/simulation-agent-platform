package com.luxera.companion.runtime;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.agent.WorkingMemory;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.behavior.BehaviorLearningService;
import com.luxera.companion.cognition.CognitiveWakeupService;
import com.luxera.companion.digitalhuman.conversation.ChatMessageDraft;
import com.luxera.companion.digitalhuman.conversation.ConversationOutputValidator;
import com.luxera.companion.digitalhuman.event.EventProcessingChain;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.interaction.ResponseLatencyEngine;
import com.luxera.companion.phone.PhoneNotification;
import com.luxera.companion.phone.PhoneNotificationService;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionContext;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.agent.expression.ExpressionResult;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.pipeline.MessagePipeline;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.usermodel.UserChatStyleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §10-§21 Agent Runtime: 通信解耦。
 *
 * 用户发消息 → POST /messages 立即持久化返回 → Agent Runtime 异步处理。
 * Agent 是否看到/是否回复/什么时候回复, 全部由 Runtime 决定,
 * 回复通过事件总线推送(前端 GET /events 长连接接收)。
 *
 * 用户发送消息永远不会被 Agent 阻塞(§18 工程约束)。
 */
@Slf4j
@Service
public class AgentRuntime {

    private static final String SPLIT = "<split>";

    /** V10 §3.1: 数字人访问聊天世界的唯一入口(读消息/写消息/发事件)。 */
    private final ChatWorldPort chatWorld;
    private final PerceptionEngine perceptionEngine;
    private final WorkingMemory workingMemory;
    private final UserChatStyleService userChatStyleService;
    private final BehaviorLearningService behaviorLearningService;
    private final MessagePipeline messagePipeline;
    private final ExpressionAgent expressionAgent;
    private final InteractionPolicyEngine interactionPolicy;
    private final ResponseLatencyEngine latencyEngine;
    private final AgentStateService agentStateService;
    private final AvailabilityService availabilityService;
    private final RelationshipService relationshipService;
    private final PhoneNotificationService phoneNotificationService;
    private final CognitiveWakeupService cognitiveWakeupService;
    private final IntentionService intentionService;
    private final CompanionSchedule schedule;
    private final CompanionRuntime runtime;
    private final com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService;
    private final com.luxera.companion.reality.RealityConsistencyChecker realityChecker;
    /** V10 §21.1: 状态版本门(LLM 旧结果不能覆盖新状态) */
    private final com.luxera.companion.digitalhuman.state.StateVersionGate stateVersionGate;
    /** V10 §20: Person Actor 注册表(per-person mailbox FIFO + 统一串行互斥) */
    private final com.luxera.companion.digitalhuman.actor.PersonActorRegistry personActorRegistry;

    /** V10: Reality Ledger(真实经历 append-only 账本) */
    private final RealityLedger realityLedger;
    /** V10: 外部事件处理链(Validation → Dedup → Route) */
    private final EventProcessingChain eventProcessingChain;
    /** V10: 事件路由注册表 */
    private final EventRouter eventRouter;
    /** V10: 聊天输出质量闸门(禁旁白/舞台动作/AI 腔) */
    private final ConversationOutputValidator outputValidator;
    /** V10 §4: Strangler 入口 — V10 感知决策影子对比 + 短路 */
    private final com.luxera.companion.digitalhuman.hotpath.V10HotpathGateway v10Hotpath;

    // V10 §14/LAP: 应用动作的执行入口已迁出本类 —— 见 AgentApplicationFlow。
    // 认知链不再持有 ActionRuntime / LlmRouter / ObjectMapper: 它不认识任何应用,
    // 也就不该有"读局面/评估/落子"这类需要的工具。

    public AgentRuntime(ChatWorldPort chatWorld, PerceptionEngine perceptionEngine,
                          WorkingMemory workingMemory,
                          UserChatStyleService userChatStyleService, BehaviorLearningService behaviorLearningService,
                          MessagePipeline messagePipeline,
                          ExpressionAgent expressionAgent,
                          InteractionPolicyEngine interactionPolicy, ResponseLatencyEngine latencyEngine,
                          AgentStateService agentStateService, AvailabilityService availabilityService,
                          RelationshipService relationshipService, PhoneNotificationService phoneNotificationService,
                          CognitiveWakeupService cognitiveWakeupService, IntentionService intentionService,
                          CompanionSchedule schedule, CompanionRuntime runtime,
                          com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService,
                          com.luxera.companion.reality.RealityConsistencyChecker realityChecker,
                          com.luxera.companion.digitalhuman.state.StateVersionGate stateVersionGate,
                          com.luxera.companion.digitalhuman.actor.PersonActorRegistry personActorRegistry,
                          RealityLedger realityLedger,
                          EventProcessingChain eventProcessingChain, EventRouter eventRouter,
                          ConversationOutputValidator outputValidator,
                          com.luxera.companion.digitalhuman.hotpath.V10HotpathGateway v10Hotpath) {
        this.chatWorld = chatWorld;
        this.perceptionEngine = perceptionEngine;
        this.workingMemory = workingMemory;
        this.userChatStyleService = userChatStyleService;
        this.behaviorLearningService = behaviorLearningService;
        this.messagePipeline = messagePipeline;
        this.expressionAgent = expressionAgent;
        this.interactionPolicy = interactionPolicy;
        this.latencyEngine = latencyEngine;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.relationshipService = relationshipService;
        this.phoneNotificationService = phoneNotificationService;
        this.cognitiveWakeupService = cognitiveWakeupService;
        this.intentionService = intentionService;
        this.schedule = schedule;
        this.runtime = runtime;
        this.cognitiveSessionService = cognitiveSessionService;
        this.realityChecker = realityChecker;
        this.stateVersionGate = stateVersionGate;
        this.personActorRegistry = personActorRegistry;
        this.realityLedger = realityLedger;
        this.eventProcessingChain = eventProcessingChain;
        this.eventRouter = eventRouter;
        this.outputValidator = outputValidator;
        this.v10Hotpath = v10Hotpath;
    }

    /**
     * V10 §9: 注册消息送达事件路由 —— 用户消息先成为外部事件,
     * 经事件链(Validation → Dedup → Route)进入数字人认知, 而不是直接注入消息内容。
     */
    @PostConstruct
    public void registerEventRoutes() {
        eventRouter.register(ExternalEventType.CHAT_MESSAGE_DELIVERED, this::onChatMessageDelivered);
        // APPLICATION_EVENT 由 AgentApplicationFlow 以 subscribe 挂载 —— 认知链只保留消息送达这条主路由
    }

    /**
     * V10 §1.2 因果链入口: 接收**已持久化**的用户消息, 转为外部事件后进入事件链。
     * 消息落库已由 chat 平台在请求事务内完成(经 ChatWorldPort.append);
     * 这里只做 Agent 认知处理(感知/流水线/回复), 永不参与消息的持久化。
     * 立即返回(不阻塞); Agent 的回复通过事件总线推送。
     *
     * V10 边界: 事件 payload 只携带 messageIds 引用, 不含消息内容 ——
     * 数字人通过 Simulator Client 的 ReadMessagesCapability 自行"查看"消息内容。
     */
    public void submit(String userId, String companionId, String conversationId, List<MessageView> userMessages) {
        submitWithPhase(userId, companionId, conversationId, userMessages, "live");
    }

    /**
     * 带处理阶段的事件提交。阶段纳入确定性 eventId:
     * - live(实时送达)与 catchup(醒来补处理)是两次独立处理时机, 互不短路;
     * - 同一阶段的同批重试/重放仍被幂等短路。
     *
     * V10 §20: 提交进入 Person Actor mailbox(严格 FIFO) —— 同 Person 处理顺序
     * = 提交顺序, 不同 Person 并行; 空闲回收线程, 不累积。
     */
    public void submitWithPhase(String userId, String companionId, String conversationId,
                                List<MessageView> userMessages, String phase) {
        personActorRegistry.tell(companionId, () -> {
            try {
                if (userMessages == null || userMessages.isEmpty()) return;
                String lastMessageId = userMessages.get(userMessages.size() - 1).getId();
                // 确定性事件 id: 同阶段同批消息重试/重放时幂等短路
                ExternalEvent event = ExternalEvent.withDeterministicId(
                        companionId, ExternalEventType.CHAT_MESSAGE_DELIVERED,
                        companionId + "-" + conversationId + "-" + lastMessageId + "-" + phase,
                        Map.of(
                                "userId", userId,
                                "companionId", companionId,
                                "conversationId", conversationId,
                                "messageIds", userMessages.stream().map(MessageView::getId).toList(),
                                "source", "chat-platform",
                                "phase", phase));
                eventProcessingChain.process(event);
            } catch (Exception e) {
                log.error("[AgentRuntime] 处理消息失败 companion={}: {}", companionId, e.getMessage());
            }
        });
    }

    /**
     * V10 §9.2 事件路由终点: 消息送达事件 → 数字人"查看"消息(Simulator Capability)
     * → 进入完整认知链。消息内容始终通过 Simulator 读取, 不直接注入。
     */
    void onChatMessageDelivered(ExternalEvent event) {
        String companionId = event.personId();
        String conversationId = event.str("conversationId");
        String userId = event.str("userId");
        @SuppressWarnings("unchecked")
        List<String> messageIds = (List<String>) event.get("messageIds");
        if (conversationId == null || messageIds == null || messageIds.isEmpty()) return;

        // 数字人"查看"整个会话, 再筛出这次送达的几条(顺序以会话内顺序为准)
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(messageIds);
        List<MessageView> messages = chatWorld.messages(conversationId).stream()
                .filter(m -> wanted.contains(m.getId()))
                .toList();
        if (messages.isEmpty()) return;
        process(userId, companionId, conversationId, messages);
    }

    /** Agent 异步处理已入库的用户消息(完整认知链) */
    public void process(String userId, String companionId, String conversationId, List<MessageView> userMessages) {
        if (userMessages == null || userMessages.isEmpty()) return;
        // V10 §20: per-person 统一串行设施(与 PersonActor mailbox 同一互斥体;
        // 同步调用路径与异步 mailbox 路径互斥, 状态修改永不走并发)
        Object lock = personActorRegistry.lockOf(companionId);
        synchronized (lock) {
            LocalDateTime now = LocalDateTime.now();
            List<String> contents = new ArrayList<>();
            for (MessageView um : userMessages) {
                if (um.getContent() != null && !um.getContent().isBlank()) contents.add(um.getContent());
            }
            if (contents.isEmpty()) return;
            String decisionText = String.join("。", contents);
            MessageView last = userMessages.get(userMessages.size() - 1);

            // ── V10 §4-§6 Strangler hotpath 门控(防御式: 任何异常不阻断 V9 主链路) ──
            if (v10Hotpath != null && (v10Hotpath.isShadow() || v10Hotpath.isEnabled())) {
                try {
                    var event = ExternalEvent.withDeterministicId(companionId,
                            ExternalEventType.CHAT_MESSAGE_DELIVERED,
                            companionId + "-" + conversationId + "-" + last.getId() + "-process",
                            Map.of("userId", userId, "companionId", companionId,
                                    "conversationId", conversationId,
                                    "messageIds", userMessages.stream().map(MessageView::getId).toList()));
                    var outcome = v10Hotpath.shadowEvaluate(event, userId, companionId, 0.5, now);
                    if (v10Hotpath.isEnabled() && outcome != null) {
                        var sc = v10Hotpath.shortCircuitDecision(outcome);
                        if (sc == com.luxera.companion.digitalhuman.hotpath.V10HotpathGateway.ShortCircuitDecision.SKIP_PROCESS) {
                            // V10 感知未到(NOT_PERCEIVED) → 短路, 不进入 V9 pipeline
                            log.info("[AgentRuntime] {} V10 hotpath: NOT_PERCEIVED 短路, 不处理(companion={})",
                                    companionId, companionId);
                            return;
                        }
                        if (sc == com.luxera.companion.digitalhuman.hotpath.V10HotpathGateway.ShortCircuitDecision.DEFER_AND_TRY_LATER) {
                            // V10 决策 DELAY → 短路, 保留在 pending 复查队列(不立即回复)
                            log.info("[AgentRuntime] {} V10 hotpath: DELAY 短路, 延迟回复", companionId);
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[AgentRuntime] V10 hotpath 门控异常(忽略, 继续 V9): {}", e.getMessage());
                }
            }

            // 1. 消息已在请求线程落库(chat 平台)。这里只做感知后处理:
            //    工作记忆 + 聊天风格 + 行为学习(会话/Exchange 归属由 chat 平台自己维护)
            for (MessageView m : userMessages) {
                PerceptionEngine.Perception p = perceptionEngine.perceive(m.getContent());
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("user", m.getContent(), m.getCreatedAt()), p);
                userChatStyleService.record(companionId, userId, m.getContent(), m.getCreatedAt());
                try {
                    behaviorLearningService.onUserMessage(companionId, now, p.emotion());
                } catch (Exception ignored) { }
            }

            // 2. 唤醒评估(前置): 决定"睡着时是否被重要消息吵醒" + 低价值消息不打扰。
            // 关系权重: 亲密的人发来的消息 → 更敏感(关系影响认知)。
            PerceptionEngine.Perception burstPerception = perceptionEngine.perceive(decisionText);
            Relationship wakeRel = relationshipService.find(userId, companionId);
            double relWeight = wakeRel != null
                    ? (wakeRel.getIntimacy() * 0.6 + wakeRel.getAffection() * 0.4) : 0.3;
            boolean sleeping = schedule.activityFor(companionId, now) == CompanionSchedule.Activity.SLEEP;
            boolean urged = beingUrged(conversationId, now, decisionText);   // 追问词/连发(被催问)
            boolean emotionalSignal = burstPerception != null && burstPerception.emotion() != null
                    && !List.of("neutral", "calm", "happy").contains(burstPerception.emotion());
            double wakeImportance = 0.35 + (urged ? 0.25 : 0) + (emotionalSignal ? 0.2 : 0);
            CognitiveWakeupService.WakeLevel wake = cognitiveWakeupService.evaluate(
                    new CognitiveWakeupService.WakeupInput(
                            "MESSAGE", decisionText, wakeImportance, 0.5,
                            emotionalSignal ? 0.4 : 0.1, 0.3, 0.5), relWeight);
            // 被连发催问 → 认真对待(真人被连着问会坐不住)
            if (urged && wake == CognitiveWakeupService.WakeLevel.ATTENTION) {
                wake = CognitiveWakeupService.WakeLevel.DELIBERATION;
            }

            // V9 §4.3: 更新连续心智 —— 他刚才在说什么, 我心里在想什么(不打断处理主流程)
            try {
                String focus = burstPerception != null && burstPerception.topic() != null
                        ? burstPerception.topic() : truncate(decisionText, 30);
                String thought = emotionalSignal
                        ? "他好像不太对劲,想多陪陪他"
                        : (urged ? "他连着找我,是不是有什么事" : "他刚说起" + focus);
                cognitiveSessionService.touchOnMessage(companionId, focus, thought,
                        emotionalSignal ? "有点担心他" : null);
            } catch (Exception ignored) { }

            // V9 §14: Fast/Deep 路径 —— 重要消息(DELIBERATION+)走 Deep 完整上下文, 其余走 Fast 轻量
            String path = (wake == CognitiveWakeupService.WakeLevel.DELIBERATION
                    || wake == CognitiveWakeupService.WakeLevel.DEEP_THINKING)
                    ? com.luxera.companion.agent.CompanionCognitiveRuntime.PATH_DEEP
                    : com.luxera.companion.agent.CompanionCognitiveRuntime.PATH_FAST;

            boolean forceNoticed = false;
            if (sleeping) {
                if (wake == CognitiveWakeupService.WakeLevel.DELIBERATION
                        || wake == CognitiveWakeupService.WakeLevel.DEEP_THINKING) {
                    // 重要消息(深夜的"在吗"/连发催问/情绪强烈)→ 她会被吵醒, 强制造注到
                    forceNoticed = true;
                } else {
                    // 睡着且不是重要消息 → 不打扰(保持未读), 符合真人
                    chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "ASLEEP"));
                    log.info("[AgentRuntime] {} 睡着且消息不紧急, 不打扰 (wake={})", companionId, wake);
                    return;
                }
            }
            log.info("[AgentRuntime] {} sleeping={} urged={} wake={} forceNoticed={} text={}",
                    companionId, sleeping, urged, wake, forceNoticed, truncate(decisionText, 20));

            // 3. 消息流水线(forceNoticed: 被吵醒时跳过"没看到"判定)
            MessagePipeline.PipelineResult pipelineResult = messagePipeline.process(
                    userId, companionId, conversationId, userMessages, decisionText, burstPerception, now,
                    forceNoticed);
            log.info("[AgentRuntime] {} pipeline outcome={} reason={}", companionId,
                    pipelineResult.outcome(), pipelineResult.reason());

            // §15-§17 Phone Notification: 消息到达 → 手机通知 → heard/seen/opened/read 逐步推进
            PhoneNotification phoneNotif = null;
            try {
                phoneNotif = phoneNotificationService.create(companionId, conversationId, last.getId(),
                        truncate(decisionText, 50), true, true, now);
                AttentionService.Attention att = pipelineResult.attention();
                boolean phoneAvailable = att != null && att.inspectProbability() > 0;
                phoneNotif = phoneNotificationService.advance(phoneNotif, att, phoneAvailable, now);
            } catch (Exception ignored) { }

            // 会话线程(状态由 chat 平台维护, 这里只报告"这次聊的是什么")
            try {
                chatWorld.touchThread(companionId, conversationId,
                        burstPerception != null ? burstPerception.topic() : null,
                        burstPerception != null ? burstPerception.emotion() : null);
            } catch (Exception ignored) { }

            // 低价值消息(前置评估的 wake 已含关系权重与催问信号)→ 不唤醒认知
            if (!cognitiveWakeupService.requiresCognition(wake)) {
                // 低价值消息(如"哈哈"): 消息已入库, 但不打断她的生活 → 不生成回复
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "MICRO_WAKE"));
                return;
            }

            // 3. 没看到 → 保持未读(被催问时不算"没看到": 真人被催问会看一眼)
            if (pipelineResult.isIgnored() && !urged) {
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED", "action", "IGNORE"));
                appendReality(companionId, RealityEventType.MESSAGE_IGNORED,
                        Map.of("messageId", last.getId(), "conversationId", conversationId,
                                "reason", pipelineResult.reason()), null, null);
                return;
            }
            // 4. 看到了但不回(DEFER) → 已读(整个会话), 后续复查; 但被连发催问时, 真人会被催着回
            if (pipelineResult.isDeferred() && !urged) {
                // 她看到了全部未读消息 → 全部已读, 但决定稍后回
                markAllConversationRead(companionId, conversationId, userMessages, now);
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "DEFER"));
                appendReality(companionId, RealityEventType.MESSAGE_DEFERRED,
                        Map.of("messageId", last.getId(), "conversationId", conversationId,
                                "reason", pipelineResult.reason(),
                                "reviewAt", now.plusMinutes(60).toString()), null, null);
                // §35-§36: 创建意图"该回复他" → 之后可能突然想起(Intention Activation)
                try {
                    intentionService.create(companionId, userId,
                            "还没回复那句「" + truncate(decisionText, 30) + "」, 等忙完想补一句",
                            0.6, "内疚", "user",
                            now.plusHours(2), 24);
                } catch (Exception ignored) { }
                return;
            }

            // 5. 回复路径(被催问的 IGNORE/DEFER 也落在这里: 用交互策略重新决策)
            InteractionDecision decision = null;
            if (pipelineResult.brainDecision() != null
                    && !pipelineResult.brainDecision().isDefer()
                    && !BrainDecision.IGNORE.equals(pipelineResult.brainDecision().action())) {
                decision = pipelineResult.brainDecision().baseline();
            }
            if (decision == null) {
                decision = interactionPolicy.decide(buildInteractionInput(userId, companionId, decisionText, now, pipelineResult));
            }
            AttentionService.Attention attention = pipelineResult.attention();
            var state = agentStateService.get(companionId);

            List<MessageView> recent = chatWorld.recentMessages(conversationId, 40);
            String kind = decision.action == InteractionAction.SHORT_ACK ? "SHORT_ACK" : "NORMAL";

            // Expression: 决定怎么说 + 打字节奏
            ExpressionResult expression = expressionAgent.execute(buildExpressionContext(
                    userId, companionId, decisionText, pipelineResult, decision, recent, now));

            // 已读延迟(忙/疲劳 → 慢)
            long readDelay = attention != null ? attention.inspectDelayMs() : 0;
            if (readDelay > 0) sleep(readDelay);
            // 她拿起手机看到整个会话 → 本批次 + 该会话其余未读用户消息全部变已读(真人行为)
            markAllConversationRead(companionId, conversationId, userMessages, now);
            // 通知标记已读
            if (phoneNotif != null) {
                try {
                    phoneNotificationService.markRead(last.getId(), now);
                } catch (Exception ignored) { }
            }
            chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                    Map.of("messageId", last.getId(), "status", "READ"));

            // typing + 延迟
            long latency = latencyEngine.computeDelayMs(decision, decisionText,
                    state != null ? state.getEnergy() : 0.6,
                    state != null ? state.getStress() : 0.3, now,
                    availabilityService.current(companionId, now, state));
            // §八: 关系影响回复节奏 —— 熟悉/亲密 → 略快(更随意); 张力高/心情低落 → 更慢
            Relationship latencyRel = relationshipService.find(userId, companionId);
            if (latencyRel != null) {
                double famIntim = latencyRel.getFamiliarity() * 0.5 + latencyRel.getIntimacy() * 0.5;
                latency = (long) (latency * (1.15 - famIntim * 0.35 + latencyRel.getTension() * 0.3));
            }
            boolean showTyping = decision.commitment.level >= com.luxera.companion.interaction.ResponseCommitment.CASUAL.level;
            if (showTyping) {
                chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_TYPING, Map.of("typing", true));
            }
            if (latency > 0) sleep(latency);
            if (showTyping) {
                chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_TYPING, Map.of("typing", false));
            }

            // 生成
            String expressionHint = describeExpression(expression);
            // V10 §21.1: LLM 调用前快照状态版本 —— 返回时版本已变则结果作废(不覆盖新状态)
            long stateVersionBeforeLlm = stateVersionGate.snapshot(companionId);
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    last.getId(), decisionText, recent, null, decision, expressionHint, path);
            String reply = outcome.reply();
            if (reply == null || reply.isBlank()) return;

            // V9 §10: Reality 一致性校验 —— 表达与当前现实冲突(编造事实)时禁止直接发送
            String conflict = null;
            try {
                conflict = realityChecker.check(reply, companionId,
                        schedule.describe(companionId, "她", now), now);
                if (conflict != null) {
                    // 重新生成一次, 提示冲突(最多一次)
                    outcome = runtime.generate(userId, companionId, conversationId,
                            last.getId(), decisionText, recent, null, decision,
                            expressionHint + " 注意:" + conflict, path);
                    reply = outcome.reply();
                    conflict = reply == null || reply.isBlank() ? null
                            : realityChecker.check(reply, companionId,
                                    schedule.describe(companionId, "她", now), now);
                }
            } catch (Exception ignored) { }
            if (reply == null || reply.isBlank()) return;
            if (conflict != null) {
                // 与事实冲突的文本不发送 —— 像真人一样"没说出口", 保持 Reality 一致
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "REALITY_CONFLICT",
                                "reason", conflict));
                log.info("[AgentRuntime] {} 回复与 Reality 冲突, 未发送: {}", companionId, conflict);
                return;
            }

            // V10 §21.1: 提交前校验状态版本 —— LLM 期间状态已变(其他线程/任务) → 结果作废
            if (!stateVersionGate.tryCommit(companionId, stateVersionBeforeLlm)) {
                // 旧结果不能覆盖新状态(MVP 验收 12): 像真人一样"想了半天但情况已经变了"
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "STATE_VERSION_CONFLICT"));
                log.info("[AgentRuntime] {} 状态版本冲突, 丢弃基于旧状态的回复", companionId);
                return;
            }

            // 拆分回复段(V10 §15.3: 发送前必须通过输出质量闸门)
            List<String> chunks = splitReply(reply);
            String first = chunks.get(0).trim();
            String validationIssue = outputValidator.validate(ChatMessageDraft.of(0, first));
            if (validationIssue != null) {
                // 未通过: 像真人一样"没说出口", 不发送(或在此重新生成 —— 由调用方决定)
                log.info("[AgentRuntime] {} 回复未通过输出验证, 未发送: {}", companionId, validationIssue);
                chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "READ", "action", "OUTPUT_REJECTED",
                                "reason", validationIssue));
                return;
            }
            // V10 §4.3: 发送统一走 ChatWorldPort(Command Pattern), 数字人不直接写 Chat 存储
            MessageView assistant;
            try {
                assistant = chatWorld.append(MessageAppendCommand
                        .of(conversationId, "companion", first)
                        .withKind(kind)
                        .withIdempotencyKey("agent-reply-" + last.getId() + "-0"));
            } catch (Exception e) {
                log.warn("[AgentRuntime] 回复发送失败: {}", e.getMessage());
                return;
            }
            if (assistant == null) {
                return;
            }
            workingMemory.record(companionId, conversationId,
                    new WorkingMemory.RecentLine("companion", first, assistant.getCreatedAt()), null);
            chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_MESSAGE,
                    Map.of("messageId", assistant.getId(), "conversationId", conversationId,
                            "content", first, "senderType", "companion"));
            appendReality(companionId, RealityEventType.MESSAGE_SENT,
                    Map.of("messageId", assistant.getId(), "conversationId", conversationId,
                            "replyTo", last.getId(), "text", first), null, null);

            // 后续段: 延迟后逐条发送(像真人隔一下又补一句)
            for (int i = 1; i < chunks.size(); i++) {
                String seg = chunks.get(i).trim();
                if (seg.isEmpty()) continue;
                String segValidation = outputValidator.validate(ChatMessageDraft.of(i, seg));
                if (segValidation != null) {
                    log.info("[AgentRuntime] {} 后续段未通过输出验证, 跳过: {}", companionId, segValidation);
                    continue;
                }
                sleep(900 + (long) (Math.random() * 900));
                MessageView m;
                try {
                    m = chatWorld.append(MessageAppendCommand
                            .of(conversationId, "companion", seg)
                            .withKind(kind)
                            .withIdempotencyKey("agent-reply-" + last.getId() + "-" + i));
                } catch (Exception e) {
                    log.warn("[AgentRuntime] 后续段发送失败: {}", e.getMessage());
                    continue;
                }
                if (m == null) continue;
                workingMemory.record(companionId, conversationId,
                        new WorkingMemory.RecentLine("companion", seg, m.getCreatedAt()), null);
                chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_MESSAGE,
                        Map.of("messageId", m.getId(), "conversationId", conversationId,
                                "content", seg, "senderType", "companion"));
            }

            // 对方要走 → 记录边界
            if (decision.action == InteractionAction.END_CONVERSATION) {
                chatWorld.recordBoundary(companionId, conversationId, "SOFT_END", decision.reason);
            }
        }
    }

    private InteractionPolicyEngine.InteractionInput buildInteractionInput(
            String userId, String companionId, String decisionText, LocalDateTime now,
            MessagePipeline.PipelineResult pr) {
        var state = agentStateService.get(companionId);
        Relationship rel = relationshipService.find(userId, companionId);
        com.luxera.companion.appraisal.AppraisalService.AppraisalResult appraisal =
                com.luxera.companion.appraisal.AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3);
        boolean intimate = rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage());
        return new InteractionPolicyEngine.InteractionInput(
                decisionText, null, null,
                state != null ? state.getEnergy() : 0.6, state != null ? state.getStress() : 0.3,
                rel != null ? rel.getRelationshipStage() : "new", intimate, false,
                availabilityService.current(companionId, now, state), appraisal,
                state != null ? state.getEmotionalCloseness() : 0.3,
                rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0);
    }

    private ExpressionContext buildExpressionContext(String userId, String companionId, String decisionText,
                                                     MessagePipeline.PipelineResult pr, InteractionDecision decision,
                                                     List<MessageView> recent, LocalDateTime now) {
        var state = agentStateService.get(companionId);
        String mood = state != null ? state.getMood() : "平静";
        return new ExpressionContext(
                companionId, userId, decisionText, "respond", mood, null, "close", 0.5,
                schedule.describe(companionId, "她", now),
                List.of("用户: " + decisionText), 0.6, 0.4, decision,
                describeResponseIntent(pr));
    }

    /** V9 §11: Brain 的回应意图 → Expression(Brain 决定想多长/拆几条/节奏, Expression 决定怎么说) */
    private static String describeResponseIntent(MessagePipeline.PipelineResult pr) {
        var bd = pr == null ? null : pr.brainDecision();
        if (bd == null) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (bd.messageCount() > 0) {
            parts.add("想拆 " + bd.messageCount() + " 条消息");
        }
        if (bd.desiredLength() > 0) {
            parts.add("期望约 " + bd.desiredLength() + " 字");
        }
        if (bd.delayHint() != null && !bd.delayHint().isBlank()) {
            parts.add("节奏:" + bd.delayHint());
        }
        if (bd.styleHint() != null && !bd.styleHint().isBlank()) {
            parts.add("语气:" + bd.styleHint());
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    private static String describeExpression(ExpressionResult e) {
        if (e == null || e.strategy() == null) return null;
        return "语气 " + e.strategy().tone() + ", 直接 " + Math.round(e.strategy().directness() * 100) + "%";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 是否被催问: 消息含追问词(在吗/怎么不回/回我/醒了吗/急事… —— 真人被催着回) */
    private boolean beingUrged(String conversationId, LocalDateTime now, String decisionText) {
        if (decisionText != null && containsAny(decisionText,
                "在吗", "怎么不", "不回", "回我", "理我", "醒了吗", "忙吗", "看到吗", "看见吗", "人呢", "急事", "紧急")) {
            return true;
        }
        return false;
    }

    /**
     * 她拿起手机看到整个会话 → 该会话全部未读用户消息一并变已读(真人行为, 不是只读最新一条)。
     * V10 §4.3: 已读是客户端动作, 通过 Simulator Capability(UpdateDeliveryStatus) 落库,
     * 并逐条发布 message_read 事件(前端实时更新勾勾)。
     */
    private void markAllConversationRead(String companionId, String conversationId,
                                         List<MessageView> userMessages, LocalDateTime now) {
        try {
            java.util.Set<String> unread = new java.util.LinkedHashSet<>();
            for (MessageView m : chatWorld.messages(conversationId)) {
                if (!"user".equals(m.getSenderType())) continue;
                if (m.getDeliveryStatus() == null
                        || "READ".equals(m.getDeliveryStatus())
                        || "IGNORED".equals(m.getDeliveryStatus())) continue;  // 已读/已忽略跳过
                unread.add(m.getId());
            }
            if (unread.isEmpty()) return;
            chatWorld.updateDeliveryStatus(companionId, unread, "READ");
            for (String messageId : unread) {
                chatWorld.publishEvent(companionId, ChatEventTypes.MESSAGE_READ, Map.of("messageId", messageId));
                try {
                    phoneNotificationService.markRead(messageId, now);
                } catch (Exception ignored) { }
            }
            appendReality(companionId, RealityEventType.MESSAGE_READ,
                    Map.of("messageIds", unread, "conversationId", conversationId,
                            "count", unread.size()), null, null);
        } catch (Exception e) {
            log.debug("[AgentRuntime] 批量已读失败: {}", e.getMessage());
        }
    }

    /** V10 §8: 真实行为写入 Reality Ledger(append-only; 失败不阻断主流程) */
    private void appendReality(String companionId, RealityEventType type, Map<String, Object> payload,
                               String correlationId, String causationId) {
        try {
            realityLedger.append(companionId, type, payload, correlationId, causationId);
        } catch (Exception e) {
            log.warn("[AgentRuntime] Reality Ledger 写入失败 {} {}: {}", companionId, type, e.getMessage());
        }
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static List<String> splitReply(String reply) {
        List<String> out = new ArrayList<>();
        if (reply == null || reply.isBlank()) { out.add(""); return out; }
        if (reply.contains(SPLIT)) {
            for (String s : reply.split(SPLIT)) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out.isEmpty() ? List.of(reply.trim()) : out;
        }
        out.add(reply.trim());
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
