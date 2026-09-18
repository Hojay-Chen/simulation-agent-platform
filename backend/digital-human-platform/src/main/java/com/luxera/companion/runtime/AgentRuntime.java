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
    /** Agent 开关: "这一个 agent 现在要不要跑"。见 {@code AgentSwitchService} 的类注释。 */
    private final com.luxera.companion.persona.AgentSwitchService agentSwitch;
    /** V11 §2.2.2: 送达主链(阶梯 → 判定 → 读)。默认 shadow, 只并跑记录。 */
    private final com.luxera.companion.runtime.v11.V11DeliveryPath v11Path;
    private final com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch;
    private final com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow;
    /** V11 §9.2: 她的心智(agent_mind_states)。写不写由 {@code MindStateService} 一处上闸。 */
    private final com.luxera.companion.mind.MindStateService mindStates;

    // ── V11 Phase 4: 认知决策(默认 shadow: 算出来只记账, 回复与否仍归老链) ──
    private final com.luxera.companion.cognition.MindDecisionPlanner mindPlanner;
    private final com.luxera.companion.runtime.v11.V11CognitionSwitch cognitionSwitch;
    private final com.luxera.companion.runtime.v11.CognitionDecisionRecorder cognitionRecorder;
    private final com.luxera.companion.cognition.ResponseContinuityGuard continuityGuard;

    // ── V11 Phase 5: 她的下次唤醒时刻(agent_schedule) ──
    /**
     * 只有一件事: 把一个 {@code nextWakeupAt} 变成一行闹钟。
     *
     * <p>它是 {@code cognitive/} 与 {@code wakeup/} 之间唯一的接线 —— 认知只产出
     * "什么时候该重新考虑", 不产生任何计时行为; 闹钟怎么响、谁来拆信是
     * {@code AgentWakeupJob} 与消费者的事。这条分界让 Phase 4 那句
     * "<em>一个没有复查时刻的 DEFER 与'永远不回'是同一件事</em>" 第一次有了执行者。
     */
    private final com.luxera.companion.wakeup.AgentWakeupService wakeups;

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
                          com.luxera.companion.digitalhuman.hotpath.V10HotpathGateway v10Hotpath,
                          com.luxera.companion.persona.AgentSwitchService agentSwitch,
                          com.luxera.companion.runtime.v11.V11DeliveryPath v11Path,
                          com.luxera.companion.runtime.v11.V11RuntimeSwitch v11Switch,
                          com.luxera.companion.runtime.v11.V11DeliveryShadow v11Shadow,
                          com.luxera.companion.mind.MindStateService mindStates,
                          com.luxera.companion.cognition.MindDecisionPlanner mindPlanner,
                          com.luxera.companion.runtime.v11.V11CognitionSwitch cognitionSwitch,
                          com.luxera.companion.runtime.v11.CognitionDecisionRecorder cognitionRecorder,
                          com.luxera.companion.cognition.ResponseContinuityGuard continuityGuard,
                          com.luxera.companion.wakeup.AgentWakeupService wakeups) {
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
        this.agentSwitch = agentSwitch;
        this.v11Path = v11Path;
        this.v11Switch = v11Switch;
        this.v11Shadow = v11Shadow;
        this.mindStates = mindStates;
        this.mindPlanner = mindPlanner;
        this.cognitionSwitch = cognitionSwitch;
        this.cognitionRecorder = cognitionRecorder;
        this.continuityGuard = continuityGuard;
        this.wakeups = wakeups;
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
                // Agent 开关: 闸门在**任务体内**, 不在提交处。
                //
                // 差别的全部意义在于"暂停之前已经排进邮箱的那几条"。邮箱是 FIFO 的,
                // 提交处检查只能拦住"按下开关之后才来的"消息; 而按下开关那一刻队列里
                // 可能正躺着几条(连发消息、或者 outbox 一次性补投的一批), 它们会在
                // 开关生效之后照常跑完整条认知链 —— 于是"我关了它, 它怎么还在回话"
                // 变成了一个真实会发生的问题。放在任务体里, 那一刻之后**一条都不跑**。
                //
                // 而且这条检查必须在业务代码之前、在 `userMessages == null` 之前:
                // 它是这个任务做的第一件事, 也是唯一一件不产生副作用的事。
                if (!agentSwitch.isRunnable(companionId)) {
                    log.debug("[AgentRuntime] agent {} 已暂停, 丢弃一次消息投递", companionId);
                    return;
                }
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
     *
     * <h2>V11 §2.2.2 在这里插入了什么</h2>
     * 老链把"到达 = 读取 = 处理"三步并成了一步 —— 下面那三行 {@code chatWorld.messages(...)}
     * 就是证据: 在问"她注意到了吗"之前, 整个会话的正文已经被拉进了这个进程。
     *
     * <p>V11 版本先只做判定({@link com.luxera.companion.runtime.v11.V11DeliveryPath#assess},
     * 不读正文、不写库), 由判定决定要不要去读。默认
     * ({@code app.v11.runtime.enabled=false, shadow=true}) 只<b>并跑并记录分歧</b>,
     * 执行仍然走老链 —— 因为这是本轮唯一一处会让 53 个 agent 行为真的变化的地方,
     * 它必须先在真实流量下被对比过, 而不是靠读代码觉得没问题。
     *
     * <p>老链那三行<b>一字未动</b>。开关关掉时, 本方法的行为与 V11 之前完全一致。
     */
    void onChatMessageDelivered(ExternalEvent event) {
        String companionId = event.personId();
        String conversationId = event.str("conversationId");
        String userId = event.str("userId");
        @SuppressWarnings("unchecked")
        List<String> messageIds = (List<String>) event.get("messageIds");
        if (conversationId == null || messageIds == null || messageIds.isEmpty()) return;

        // ── V11 送达主链(默认 shadow: 只判定与记录, 不接管) ──
        if (v11Path != null && v11Switch != null && v11Switch.isActive()) {
            try {
                LocalDateTime now = LocalDateTime.now();
                var assessment = v11Path.assess(userId, companionId, conversationId,
                        messageIds.size(), now);
                if (v11Switch.isEnabled()) {
                    if (v11Path.deliver(userId, companionId, conversationId, messageIds,
                            assessment, now,
                            msgs -> process(userId, companionId, conversationId, msgs))) {
                        return;   // V11 已接管, 不再走老链
                    }
                } else {
                    // shadow: 老链只要拿到正文就一定会处理, 所以它的"注意到"恒为 true。
                    // readCount 传 -1 = 没尝试读(shadow 不读正文), 见 V11DeliveryShadow。
                    v11Path.recordShadow(v11Shadow, companionId, messageIds.size(),
                            assessment, -1, now);
                    // Phase 3 的 shadow: 让回合状态机也跑一遍(只记数字, 不读正文、不写心智)。
                    // 它算出的合并率是切流判据, 而判据必须在切流之前就能读到。
                    v11Path.observeTurn(userId, companionId, conversationId, messageIds,
                            assessment, now);
                }
            } catch (Exception e) {
                // 判定出问题绝不能让送达本身失败 —— 消息已经在库里了, 那是既成事实。
                // 吞掉并继续走老链: 老链是今天真实在跑的那条路。
                log.warn("[AgentRuntime] V11 送达判定异常(忽略, 继续老链): {}", e.getMessage());
            }
        }

        // ── 老链(一字未动)──
        // 数字人"查看"整个会话, 再筛出这次送达的几条(顺序以会话内顺序为准)
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(messageIds);
        List<MessageView> messages = chatWorld.messages(conversationId).stream()
                .filter(m -> wanted.contains(m.getId()))
                .toList();
        if (messages.isEmpty()) return;
        process(userId, companionId, conversationId, messages);
    }

    /**
     * V11 §24.5 —— <b>认知入口的兼容适配器</b>("老名字")。
     *
     * <p>设计文档把这一步写成"{@code processUserMessage()} 降级为 compatibility adapter"。
     * 在本工程里,"{@code processUserMessage}" 那个名字对应的是这条链上的
     * {@code AgentRuntime.process} —— 感知/注意/决策/表达四件事都在这一个方法里。
     * 所以这里做的正是文档说的那件事: 真实现在 {@link #advanceMind}, 本方法只剩一句转发。
     *
     * <h2>为什么保留它, 而不是把所有调用点改名</h2>
     * 因为"改名"与"改行为"必须分开落地 —— 这是 §25.1 那条迁移纪律的具体形状。
     * 调用点有四个(邮箱投递、V11 回合封口、SSE 兼容路径、测试), 一次性全改的话,
     * 切流那天一旦出问题, 你无法判断是<b>新决策逻辑</b>写错了还是<b>某处调用改漏了</b>。
     * 保留这个名字等于留一条随时可用的回退路径, 代价是一个三行方法。
     *
     * <p>它将在 Phase 6(旧链清理)与 {@code MessagePipeline} 一起删除 —— 那时
     * 确认过全部调用点都已在用 {@code advanceMind}。
     */
    public void process(String userId, String companionId, String conversationId, List<MessageView> userMessages) {
        advanceMind(userId, companionId, conversationId, userMessages);
    }

    /**
     * V11 §11.1 / §24.5 —— <b>让她过一遍脑子</b>(认知入口的真实实现)。
     *
     * <p>旧名字叫 {@code processUserMessage}: 那个名字预设了"进来的东西是一条用户消息,
     * 要处理它"。新名字预设的是"她的心智往前走了一步" —— 消息只是让那一步发生的原因之一
     * (Phase 5 的唤醒、生活事件、未了之事都走同一个入口)。
     *
     * <h2>Phase 4 在原来的流程里插入了什么</h2>
     * 流程本身<b>一行未删</b>。插入的是两件事:
     * <ol>
     *   <li>在流水线跑完(她已经有了注意力/唤醒/可用性这些事实)之后, 算出一个
     *       {@link com.luxera.companion.cognition.CognitiveDecision} —— 也就是把
     *       "她决定做什么"从一个<b>流程走向</b>变成一个<b>值</b>。</li>
     *   <li>当 {@code app.v11.cognition.enabled=true} 时, 这个值<b>接管</b>"回不回":
     *       非回复类决策当场了结并返回, 老链那三个判断(低价值/没看到/押后)不再参与。
     *       默认 {@code shadow} 下只记账, 行为与 V11 之前完全一致。</li>
     * </ol>
     *
     * <h2>接管之后仍然有效的闸门(这一条必须写清楚)</h2>
     * 即使 {@code enabled=true}, 下面这些<b>依然</b>能拦下一次回复, 因为它们是
     * <b>执行失败</b>而不是决策, V11 今天还没有把它们建模成动作(§24.6 Action 化是后面的事):
     * {@code reply == null/"}(没写出来)、Reality 冲突、状态版本冲突、输出验证不通过。
     * 把它们也一并接管才是"认知重构"的完成态, 而今天接管它们只会让故障原因更难定位。
     */
    public void advanceMind(String userId, String companionId, String conversationId, List<MessageView> userMessages) {
        // Agent 开关的第二道入口检查。上面那个在 mailbox 任务体里, 这一处在 process 本身
        // —— 因为**不是所有认知都经过 mailbox**: `ChatStreamController` 的 SSE 兼容路径
        // 直接同步调用本方法。两处都要有, 否则那条路会绕过开关。
        if (!agentSwitch.isRunnable(companionId)) {
            log.debug("[AgentRuntime] agent {} 已暂停, 跳过一次认知处理", companionId);
            return;
        }
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
            String focus = null;
            try {
                focus = burstPerception != null && burstPerception.topic() != null
                        ? burstPerception.topic() : truncate(decisionText, 30);
                String thought = emotionalSignal
                        ? "他好像不太对劲,想多陪陪他"
                        : (urged ? "他连着找我,是不是有什么事" : "他刚说起" + focus);
                cognitiveSessionService.touchOnMessage(companionId, focus, thought,
                        emotionalSignal ? "有点担心他" : null);
            } catch (Exception ignored) { }

            // V11 §9.2: 同一次消息也写进她的心智表。
            // 与 cognitive_sessions 并存是刻意的 —— 那张表是"她此刻在想什么"(回合级),
            // 这张是"她手上挂着哪几条线"(消息级)。落库与否由 MindStateService 一处上闸,
            // 这里不判断开关: 调用方各自判一次, 就会出现"有的路径写、有的路径不写"。
            // 强度用的是认知链<b>已经算出来</b>的 wakeImportance, 不为填字段发明新指标。
            if (focus != null) {
                mindStates.noteFocus(companionId, conversationId, focus, wakeImportance, now);
            }

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

            // ── V11 Phase 4: 把"她决定做什么"变成一个值 ──
            // 位置是刻意的: 必须在流水线跑完之后 —— 决策的输入(注意力/唤醒/可用性)正是
            // 流水线算出来的那几个事实。若在流水线之前算, 这个决策就只能靠猜。
            // 返回 null = 开关没开(默认), 下面三个判断一字未动。
            com.luxera.companion.cognition.CognitiveDecision v11Decision =
                    decideInMind(userId, companionId, conversationId, decisionText, last,
                            pipelineResult, wake, sleeping, urged, emotionalSignal, now);

            if (v11Decision != null) {
                // enabled: V11 接管"回不回"。非回复类决策在这里了结 ——
                // 老链那三个判断(低价值/没看到/押后)不再参与, 否则它们会把
                // 新决策的 REPLY 再否一次, 于是"接管"变成了"多一道闸"。
                if (!v11Decision.type().producesOutboundMessage()) {
                    applyNonReplyDecision(userId, companionId, conversationId, decisionText,
                            userMessages, last, v11Decision, now);
                    return;
                }
                log.info("[AgentRuntime] {} V11 决策={} → 进入回复路径", companionId, v11Decision.type());
            } else {
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
            }
            // 4. 看到了但不回(DEFER) → 已读(整个会话), 后续复查; 但被连发催问时, 真人会被催着回
            if (v11Decision == null && pipelineResult.isDeferred() && !urged) {
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

            // ── V11 §13.2: 别把同一句话再说一遍 ──
            // 位置在 Reality 校验<b>之后</b>、状态版本提交<b>之前</b>, 理由有两条:
            // 前面 —— 一句与事实冲突的话根本不该送去重(它是错的, 不是重复的),
            //         而且去重的重生成会白花一次 LLM;
            // 后面 —— 去重可能触发第二次生成, 那会让"LLM 期间状态变了吗"的判断
            //         把这次重生成也算进去, 于是 tryCommit 会因为自己的重试而失败。
            if (continuityGuard != null && cognitionSwitch != null && cognitionSwitch.isActive()) {
                String continuityIssue = checkContinuity(companionId, reply, recent);
                if (continuityIssue != null && cognitionSwitch.isEnabled()) {
                    // 只重生成一次。第二次还重复就<b>不发</b> —— 真人想不出新说法时
                    // 就是不说话, 而不是把旧话再说一遍(那正是要治的病)。
                    outcome = runtime.generate(userId, companionId, conversationId,
                            last.getId(), decisionText, recent, null, decision,
                            expressionHint + " 注意:" + continuityIssue, path);
                    reply = outcome.reply();
                    if (reply == null || reply.isBlank()) return;
                    continuityIssue = checkContinuity(companionId, reply, recent);
                    if (continuityIssue != null) {
                        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                                Map.of("messageId", last.getId(), "status", "READ",
                                        "action", "REPEATED_EXPRESSION", "reason", continuityIssue));
                        log.info("[AgentRuntime] {} 重生成后仍然重复, 未发送: {}", companionId, continuityIssue);
                        return;
                    }
                }
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

    // ─────────────────────────── V11 Phase 4: 认知决策 ───────────────────────────

    /**
     * 算出"她决定做什么", 并按模式处理。
     *
     * @return 开关开着时的决策; <b>返回 null 表示"V11 不参与"</b> —— 默认情况。
     *         调用方据此决定走不走老链那三个判断。
     */
    private com.luxera.companion.cognition.CognitiveDecision decideInMind(
            String userId, String companionId, String conversationId, String decisionText,
            MessageView last, MessagePipeline.PipelineResult pipelineResult,
            CognitiveWakeupService.WakeLevel wake, boolean sleeping, boolean urged,
            boolean emotionalSignal, LocalDateTime now) {

        if (cognitionSwitch == null || !cognitionSwitch.isActive()) {
            return null;   // 两个开关都关: 连算都不算(生产默认以外的部署才可能走到)
        }
        com.luxera.companion.cognition.CognitiveDecision decision;
        try {
            var state = agentStateService.get(companionId);
            // emotionalSignal 只能给出 0 或 1: 上游那条链自己就只算了布尔值
            // (burstPerception.emotion() 是否落在"非平静"集合里), 这里不假装有更细的强度。
            // 决策输入留着 double 是因为 Phase 5 的情绪评估会给出真的强度,
            // 而现在给一个编出来的 0.7 只会让决策看起来比它实际的依据更精确。
            var input = com.luxera.companion.cognition.MindDecisionPlanner.DecisionInput.of(
                    wake, pipelineResult.attention(),
                    availabilityService.current(companionId, now, state),
                    sleeping, urged, emotionalSignal ? 1.0 : 0.0);
            decision = mindPlanner.decide(input, now);
        } catch (Exception e) {
            // 决策算不出来不能让这条链断掉 —— 老链仍然是唯一在回话的那条路。
            // 但要单独计数: "shadow 崩了"与"差异很多"必须在诊断上分得开,
            // 否则一个坏掉的 shadow 会表现为"差异率 0%, 可以切流了"。
            cognitionRecorder.recordError();
            log.warn("[AgentRuntime] {} V11 决策计算异常(忽略, 继续老链): {}",
                    companionId, e.getMessage());
            return null;
        }

        // 对照的基准是<b>老链的意图</b>(PipelineResult.Outcome), 不是"最后到底发出去了没有"。
        // 这个选择是有理由的: 回复最终发不出去还可能因为"没写出来/与事实冲突/状态版本变了",
        // 而那几种失败在 V11 世界里<b>一模一样地存在</b> —— 把它们算进差异率, 只会往
        // 切流判据里掺进一批与本次改动无关的噪声。
        cognitionRecorder.record(decision, pipelineResult.shouldReply(),
                String.valueOf(pipelineResult.outcome()));

        // V11 Phase 5: 把"什么时候重新考虑"变成一行闹钟。
        //
        // 放在这里、而不是放在 applyNonReplyDecision 里: 那是"她决定不做这件事"的执行处,
        // 而排闹钟对 REPLY 也可能是对的(她决定先回一句, 但还有件事要等)。决策是唯一的输入,
        // 所以接线点也只有一个。
        scheduleWakeup(companionId, conversationId, decision, now);

        if (cognitionSwitch.isEnabled()) {
            return decision;
        }
        // shadow: 记账到此为止。决策不进任何 if —— 一旦它能拦下一次回复,
        // 它就不再是"观察", 而是一次开关看起来还关着的静默上线。
        log.debug("[AgentRuntime] {} cognition shadow: planned={} old={} (不影响行为)",
                companionId, decision.type(), pipelineResult.outcome());
        return null;
    }

    /**
     * V11 Phase 5 —— <b>把一个决策的复查时刻落成一行闹钟</b>。
     *
     * <h2>为什么这件事必须在这里发生, 而不是"到点再看"</h2>
     * DEFER 的危险不在于它推迟了回复, 而在于<b>它没有任何人负责把它捡回来</b>。
     * 老链的 DEFER 就是这样的: 一个"待会儿回"的决定, 待会儿永远不会来。
     * 所以这里做的不是优化, 而是让那句"待会儿"有一个具体的时刻与一个具体的执行者。
     *
     * <h2>来源键用会话 id</h2>
     * 她"在等这个会话", 不是"在等这一条消息": 同一个会话里押后两次、第二次改了时刻,
     * 那是同一个闹钟被推后(见 {@code AgentWakeupService.schedule} 的语义),
     * 而不是两个闹钟。用消息 id 会让她在一段对话里攒下一串到点时会一起响的闹钟。
     *
     * <h2>{@code needsWakeup()} 为真时只记日志</h2>
     * 那个方法断言的是"押后类决策必须有复查时刻"。落了这条日志意味着<b>决策链有缺口</b>
     * (最可能是规划器给出了一个 follow-up 类的决策却没算出时刻),
     * 而不是运行期故障 —— 所以它不该混进 {@code cognitionRecorder.errors}:
     * 一个坏掉的 shadow 与一个如实报告"我还算不出这个时刻"的 shadow, 在切流判据上
     * 必须能分开。日志里那串 {@code 缺少复查时刻} 就是给人 grep 的。
     */
    private void scheduleWakeup(String companionId, String conversationId,
                                com.luxera.companion.cognition.CognitiveDecision decision,
                                LocalDateTime now) {
        if (wakeups == null || cognitionSwitch == null || !cognitionSwitch.isActive()) {
            return;
        }
        if (decision.needsWakeup()) {
            log.warn("[V11] {} 决策 {} 缺少复查时刻(理由 {}), 这个'待会儿'没有执行者",
                    companionId, decision.type(), decision.reason());
            return;
        }
        if (decision.nextWakeupAt() == null) {
            return;
        }
        String source = conversationId != null && !conversationId.isBlank()
                ? conversationId : companionId;
        try {
            wakeups.schedule(companionId, decision.nextWakeupAt(),
                    com.luxera.companion.world.AgentEventType.SCHEDULED_WAKEUP,
                    com.luxera.companion.wakeup.AgentWakeupService.key(
                            com.luxera.companion.wakeup.AgentWakeupService.SRC_DECISION, source),
                    "决策复查: " + decision.reason());
        } catch (Exception e) {
            // 排不上闹钟不能让这条已经算出来的回复作废 —— 那会用一个副作用去否决主链。
            log.warn("[AgentRuntime] {} 排闹钟失败(忽略): {}", companionId, e.getMessage());
        }
    }

    /**
     * 非回复类决策的执行 —— V11 版"她决定不做这件事"。
     *
     * <h2>为什么每一条都要<em>留下痕迹</em></h2>
     * 因为"不回复"在外部完全不可观测: 对方看到的是没有消息。如果这里什么都不做,
     * 一个 DEFER 与一次线程崩溃在现象上一模一样。所以每一类都至少发布一个事件
     * (前端因此能看到"对方已读"之类的状态)并把理由写进 Reality Ledger ——
     * 于是"她今天为什么不回我"是一个可以查的问题。
     */
    private void applyNonReplyDecision(String userId, String companionId, String conversationId,
                                       String decisionText, List<MessageView> userMessages,
                                       MessageView last,
                                       com.luxera.companion.cognition.CognitiveDecision decision,
                                       LocalDateTime now) {
        var type = decision.type();
        log.info("[AgentRuntime] {} V11 决策={} 理由={} → 不回复", companionId, type, decision.reason());
        try {
            switch (type) {
                case DO_NOTHING -> {
                    // 两种完全不同的"没反应", 理由分别是"没看到"与"不值得打断她"。
                    // 状态事件里的 action 直接带上理由, 于是前端与日志说的是同一件事。
                    chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "DELIVERED",
                                    "action", "V11_DO_NOTHING", "reason", decision.reason()));
                    appendReality(companionId, RealityEventType.MESSAGE_IGNORED,
                            Map.of("messageId", last.getId(), "conversationId", conversationId,
                                    "reason", decision.reason()), null, null);
                }
                case OBSERVE -> {
                    // 看到了, 不介入 —— 保持未读(与老链 IGNORE 一致: 真人不点开就不算已读)
                    chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "DELIVERED",
                                    "action", "V11_OBSERVE", "reason", decision.reason()));
                    appendReality(companionId, RealityEventType.MESSAGE_IGNORED,
                            Map.of("messageId", last.getId(), "conversationId", conversationId,
                                    "reason", decision.reason()), null, null);
                }
                case WAIT, DEFER -> {
                    // 与老链 DEFER 分支同样的落点: 全部已读 + 留一条意图 + 记复查时刻。
                    // 两者的区别在预期时长(WAIT 是"她不在场", DEFER 是"她待会儿再说"),
                    // 而落库的形状一样 —— 差异体现在 nextWakeupAt 上(Phase 5 会消费它)。
                    markAllConversationRead(companionId, conversationId, userMessages, now);
                    chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "READ",
                                    "action", "V11_" + type, "reason", decision.reason()));
                    appendReality(companionId, RealityEventType.MESSAGE_DEFERRED,
                            Map.of("messageId", last.getId(), "conversationId", conversationId,
                                    "reason", decision.reason(),
                                    "reviewAt", String.valueOf(decision.nextWakeupAt())), null, null);
                    try {
                        intentionService.create(companionId, userId,
                                "还没回复那句「" + truncate(decisionText, 30) + "」, 等忙完想补一句",
                                0.6, "内疚", "user",
                                decision.nextWakeupAt() != null ? decision.nextWakeupAt() : now.plusHours(2), 24);
                    } catch (Exception ignored) { }
                }
                case READ_MESSAGES, THINK -> {
                    // "读了但没说" / "想了想, 没有要说的" —— 她确实动了, 只是没有对外动作
                    markAllConversationRead(companionId, conversationId, userMessages, now);
                    chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                            Map.of("messageId", last.getId(), "status", "READ",
                                    "action", "V11_" + type, "reason", decision.reason()));
                }
                case CREATE_INTENTION -> {
                    try {
                        intentionService.create(companionId, userId,
                                "想起要跟他说: " + truncate(decisionText, 30),
                                0.5, "惦记", "user",
                                decision.nextWakeupAt() != null ? decision.nextWakeupAt() : now.plusHours(2), 24);
                    } catch (Exception ignored) { }
                }
                case PERFORM_ACTION -> {
                    // Phase 4 只把"在聊天之外动手"放进词表, 还没有任何一种具体的场外动作。
                    // 记一笔而不是静默: 静默会让"她做了事但没记录"变成一个无法发现的状态。
                    appendReality(companionId, RealityEventType.MESSAGE_IGNORED,
                            Map.of("conversationId", conversationId, "reason",
                                    "v11_perform_action_noop:" + decision.reason()), null, null);
                    log.info("[AgentRuntime] {} PERFORM_ACTION 尚无具体动作实现, 记一笔", companionId);
                }
                default -> chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                        Map.of("messageId", last.getId(), "status", "DELIVERED",
                                "action", "V11_" + type, "reason", decision.reason()));
            }
        } catch (Exception e) {
            // 非回复类决策的执行失败, 绝不能让异常逃到 PersonActor 的错误出口 ——
            // 那样这个回合既没有回复、也没有账目, 只留下一条无从追查的堆栈
            log.warn("[AgentRuntime] {} 非回复决策({})执行异常: {}", companionId, type, e.getMessage());
        }
    }

    /**
     * V11 §13.2 —— 去重检查。返回<b>给重新生成用的中文提示</b>, null 表示没问题。
     *
     * <p>只看<b>她最近说过的</b>话, 不看用户说的 —— 重复是她的问题, 不是用户的。
     * shadow 下也会调用(于是"有多少次回复被判定为重复"这个数字在切流之前就能读到),
     * 但返回值不会被用上, 因为调用方只在 {@code enabled} 时才拦。
     */
    private String checkContinuity(String companionId, String reply, List<MessageView> recent) {
        try {
            List<String> mine = new ArrayList<>();
            for (MessageView m : recent) {
                if ("companion".equals(m.getSenderType()) && m.getContent() != null) {
                    mine.add(m.getContent());
                }
            }
            var verdict = continuityGuard.check(reply, mine);
            if (verdict.isProblem()) {
                log.info("[AgentRuntime] {} 表达重复({}): {}", companionId, verdict.kind(), verdict.detail());
                return continuityGuard.rewriteHint(verdict);
            }
            return null;
        } catch (Exception e) {
            // 去重是"表达质量"设施, 它自己的异常绝不能变成"她不说话了"
            log.warn("[AgentRuntime] {} 去重检查异常(忽略): {}", companionId, e.getMessage());
            return null;
        }
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
