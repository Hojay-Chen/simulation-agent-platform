package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.behavior.Drives;
import com.luxera.companion.behavior.DrivesService;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.memory.Memory;
import com.luxera.companion.memory.MemoryService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.phone.PhoneState;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.ScheduledActionService;
import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.runtime.agent.brain.BrainContext;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.agent.emotion.EmotionAgent;
import com.luxera.companion.runtime.agent.emotion.EmotionAppraisalResult;
import com.luxera.companion.runtime.agent.emotion.EmotionContext;
import com.luxera.companion.runtime.agent.memory.MemoryAgent;
import com.luxera.companion.runtime.agent.memory.MemoryRecallContext;
import com.luxera.companion.runtime.agent.memory.MemoryRecallProbabilityService;
import com.luxera.companion.runtime.agent.memory.MemoryRecallResult;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.thought.ThoughtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息流水线(P1): 用户消息 → WorldEvent → Emotion → Attention → Brain → (DEFER | REPLY)。
 * 这是 最重要的流程。消息不再"直接进入 Brain"。
 *
 * 流水线:
 * 1. EmotionAgent 评估(状态经 Reducer 更新)
 * 2. Phone/Attention: 通知是否出现 → 是否注意到 → 是否打开手机(确定性 Runtime 规则)
 * 3. BrainAgent 决策: REPLY / SHORT_ACK / END_CONVERSATION / READ_NO_REPLY / IGNORE
 * 4. READ_NO_REPLY → 标记已读+DEFERRED + 创建 PendingMessageState + 排程复查
 * 5. 其余 → 进入回复路径(由调用方执行 Expression + 发送)
 */
@Slf4j
@Service
public class MessagePipeline {

    /** 注意概率阈值(低于 → 她根本没看到) */
    private static final double NOTICE_THRESHOLD = 0.3;
    /** 打开手机概率阈值(Runtime 决定她会不会真去拿手机) */
    private static final double CHECK_THRESHOLD = 0.45;

    private final EmotionAgent emotionAgent;
    private final BrainAgent brainAgent;
    private final MemoryAgent memoryAgent;
    private final AppraisalService appraisalService;
    private final AttentionService attentionService;
    private final PhoneStateService phoneStateService;
    private final AgentStateService agentStateService;
    private final AvailabilityService availabilityService;
    private final RelationshipService relationshipService;
    private final CompanionService companionService;
    private final CompanionSchedule schedule;
    private final InteractionPolicyEngine interactionPolicy;
    private final DrivesService drivesService;
    private final MemoryService memoryService;
    private final MessageDeliveryService deliveryService;
    private final PendingMessageService pendingMessageService;
    private final ScheduledActionService scheduledActionService;
    private final ChatWorldPort chatWorld;
    private final AgentTraceService traceService;
    private final ThoughtService thoughtService;
    private final MemoryRecallProbabilityService recallProbabilityService;

    public MessagePipeline(EmotionAgent emotionAgent, BrainAgent brainAgent, MemoryAgent memoryAgent,
                             AppraisalService appraisalService, AttentionService attentionService,
                             PhoneStateService phoneStateService, AgentStateService agentStateService,
                             AvailabilityService availabilityService, RelationshipService relationshipService,
                             CompanionService companionService, CompanionSchedule schedule,
                             InteractionPolicyEngine interactionPolicy, DrivesService drivesService,
                             MemoryService memoryService, MessageDeliveryService deliveryService,
                             PendingMessageService pendingMessageService, ScheduledActionService scheduledActionService,
                             ChatWorldPort chatWorld, AgentTraceService traceService,
                             ThoughtService thoughtService,
                             MemoryRecallProbabilityService recallProbabilityService) {
        this.emotionAgent = emotionAgent;
        this.brainAgent = brainAgent;
        this.memoryAgent = memoryAgent;
        this.appraisalService = appraisalService;
        this.attentionService = attentionService;
        this.phoneStateService = phoneStateService;
        this.agentStateService = agentStateService;
        this.availabilityService = availabilityService;
        this.relationshipService = relationshipService;
        this.companionService = companionService;
        this.schedule = schedule;
        this.interactionPolicy = interactionPolicy;
        this.drivesService = drivesService;
        this.memoryService = memoryService;
        this.deliveryService = deliveryService;
        this.pendingMessageService = pendingMessageService;
        this.scheduledActionService = scheduledActionService;
        this.chatWorld = chatWorld;
        this.traceService = traceService;
        this.thoughtService = thoughtService;
        this.recallProbabilityService = recallProbabilityService;
    }

    /**
     * 处理一批用户消息, 返回结果。
     * @param userMessages 已入库的用户消息(连发归并)
     * @param decisionText 合并后的文本
     * @param perception 合并文本的感知
     */
    @Transactional
    public PipelineResult process(String userId, String companionId, String conversationId,
                                  List<MessageView> userMessages, String decisionText,
                                  PerceptionEngine.Perception perception, LocalDateTime now) {
        return process(userId, companionId, conversationId, userMessages, decisionText, perception, now, false);
    }

    /**
     * 处理一批用户消息(带"强制注意到"支持)。
     * @param forceNoticed 为 true 时, 即使注意力模型判定"没看到"(睡着/在忙), 也视为已注意到
     *                     —— 用于"重要消息把她吵醒"的场景(真人深夜会被重要消息吵醒)。
     */
    @Transactional
    public PipelineResult process(String userId, String companionId, String conversationId,
                                  List<MessageView> userMessages, String decisionText,
                                  PerceptionEngine.Perception perception, LocalDateTime now,
                                  boolean forceNoticed) {
        MessageView last = userMessages.get(userMessages.size() - 1);
        AgentState state = agentStateService.get(companionId);
        PhoneState phone = phoneStateService.current(companionId, now);
        Relationship rel = relationshipService.find(userId, companionId);
        Companion companion = companionService.requireOwned(userId, companionId);
        Persona persona = companionService.getPersona(companionId);
        String personality = persona != null && persona.getPersonality() != null
                ? persona.getPersonality().getSummary() : null;
        String relationshipStage = rel != null ? rel.getRelationshipStage() : "new";
        double closeness = state != null ? state.getEmotionalCloseness() : 0.3;

        // 0. 记录世界事件
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", last.getId(), "status", MessageLifecycle.DELIVERED));

        // 1. EmotionAgent: 消息先改变内部状态
        List<String> recent = summarizeRecent(chatWorld.recentMessages(conversationId, 10));
        // 被吵醒(forceNoticed): 她刚被消息弄醒, 不是 SLEEPING —— 用 DISTRACTED(迷迷糊糊但醒着)
        CompanionAvailability availabilityNow = forceNoticed
                ? CompanionAvailability.DISTRACTED : availabilityService.current(companionId, now, state);
        String activityDesc = forceNoticed
                ? "刚被消息吵醒,还没完全清醒" : schedule.describe(companionId, companion.getName(), now);
        EmotionAppraisalResult emotion = emotionAgent.execute(new EmotionContext(
                companionId, userId, last.getId(), decisionText, recent,
                relationshipStage, closeness, rel != null ? rel.getTrust() : 0,
                state, activityDesc,
                availabilityNow.name(), 0.6, 0.4, personality,
                retrieveMemories(userId, companionId, decisionText),
                perception != null ? perception.intent() : null,
                perception != null ? perception.emotion() : null));

        // 2. 通知 + 注意力(Runtime 确定性): 她有没有看到?
        double salience = 0.3 + emotion.delta().warmth() * 0.3
                + (emotion.delta().hurt() + emotion.delta().anger()) * 0.3;
        AttentionService.Attention attention = attentionService.compute(
                schedule.activityFor(companionId, now), state, phone, salience);
        double notificationFactor = phoneNotificationFactor(phone);
        boolean notified = notificationFactor > 0;
        double noticeProb = attention.noticeProbability();
        // 早期关系(刚认识, 消息很少): 用户发来的消息会更重视 —— 保底注意(真人刚认识时会看消息)
        if (rel != null && rel.getMessageCount() < 8) {
            noticeProb = Math.max(noticeProb, 0.55);
        }
        // forceNoticed(被重要消息吵醒): 即使手机静音/勿扰/放别的房间, 她也会醒来去拿手机
        boolean noticed = forceNoticed || (notified && noticeProb >= NOTICE_THRESHOLD);

        if (!noticed) {
            // 她根本没看到(静音/勿扰/手机不在身边/在忙)—— 保持 DELIVERED, 不打扰
            return new PipelineResult(PipelineResult.Outcome.IGNORE_NOT_NOTICED, null, emotion,
                    null, last, "她没注意到消息(" + (notified ? "在忙/分心" : "静音/勿扰/手机不在身边") + ")", attention);
        }
        deliveryService.noticed(companionId, last.getId());
        chatWorld.publishEvent(companionId, ChatEventTypes.USER_MESSAGE_STATUS,
                Map.of("messageId", last.getId(), "status", MessageLifecycle.NOTICED));

        // 3. Runtime 决定她是否打开手机查看
        boolean checked = attention.inspectProbability() >= CHECK_THRESHOLD;
        if (checked) {
            deliveryService.checked(companionId, last.getId());
        }

        // 4. 记忆二段召回: 相关记忆激活(给 Brain 参考)
        List<Memory> memoryCandidates = retrieveMemories(userId, companionId, decisionText);
        MemoryRecallResult recall = memoryAgent.execute(new MemoryRecallContext(
                companionId, userId, decisionText, memoryCandidates, personality,
                relationshipStage, emotion.delta().isEmpty() ? state != null ? state.getMood() : "平静" : "有情绪变化"));
        List<Memory> activatedMemories = applyActivation(memoryCandidates, recall);

        // 5. Brain 基线决策(规则, 作为回退 + 预算来源)
        AppraisalService.AppraisalResult appraisalForDrives = AppraisalService.AppraisalResult.fromValues(
                emotion.delta().hurt(), emotion.delta().anger(), emotion.delta().warmth(),
                emotion.appraisal() != null ? emotion.appraisal().expectationViolation() : 0,
                emotion.delta().warmth() > 0 ? 0.2 : (emotion.delta().hurt() + emotion.delta().anger()) > 0.3 ? -0.2 : 0,
                Math.abs(emotion.delta().hurt()) + Math.abs(emotion.delta().anger()) + Math.abs(emotion.delta().sadness()));
        CompanionAvailability availability = forceNoticed
                ? CompanionAvailability.DISTRACTED : availabilityService.current(companionId, now, state);
        boolean intimate = rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage());
        boolean busy = schedule.activityFor(companionId, now) == CompanionSchedule.Activity.WORK_BUSY
                || schedule.activityFor(companionId, now) == CompanionSchedule.Activity.WORK_AFTERNOON;

        InteractionDecision baseline = interactionPolicy.decide(new InteractionPolicyEngine.InteractionInput(
                decisionText, perception != null ? perception.intent() : null,
                perception != null ? perception.emotion() : null,
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                relationshipStage, intimate, busy, availability, appraisalForDrives,
                closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0));

        Drives drives = drivesService.compute(appraisalForDrives,
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0,
                availability, decisionText, decisionText.length());

        // 6. Brain 最终决策
        BrainDecision brainDecision = brainAgent.execute(new BrainContext(
                companionId, userId, last.getId(), decisionText, recent,
                forceNoticed ? "刚被消息吵醒,还没完全清醒" : schedule.describe(companionId, companion.getName(), now),
                availability.name(), state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                state != null ? state.getSocialEnergy() : 0.6,
                state != null ? state.getHurt() : 0,
                state != null ? state.getAnger() : 0,
                state != null ? state.getSadness() : 0,
                state != null ? state.getAnxiety() : 0,
                state != null ? state.getWarmth() : 0,
                state != null ? state.getMood() : "平静",
                attention.noticeProbability(), attention.inspectProbability(),
                phone != null && phone.isDoNotDisturb() ? false : phone != null,
                phone != null ? phone.getNotificationMode() : "vibrate",
                relationshipStage, closeness, perception != null ? perception.intent() : null,
                perception != null ? perception.emotion() : null, drives, checked, false, baseline));

        // 7. 执行 Brain 决策
        if (brainDecision.isDefer()) {
            // 看到了但不回: 已读 + DEFERRED + 待复查
            deliveryService.read(companionId, last.getId());
            deliveryService.deferred(companionId, last.getId());
            LocalDateTime reviewAt = now.plusMinutes(reviewDelayMinutes(brainDecision));
            pendingMessageService.defer(last, companionId, userId, brainDecision.reasonFactors() == null
                    || brainDecision.reasonFactors().isEmpty() ? "暂时不想回" : String.join(";", brainDecision.reasonFactors()), reviewAt);
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    reviewAt, Map.of("pendingMessageId", last.getId()));
            // §31 Unfinished Thought: "想回复但被打断/暂时没回" → 记入未完成想法, 稍后可能主动回来补
            recordUnfinishedThought(companionId, decisionText, brainDecision);
            return new PipelineResult(PipelineResult.Outcome.DEFERRED, brainDecision, emotion,
                    recall, last, "看到了但不回, 稍后复查", attention);
        }

        if (BrainDecision.IGNORE.equals(brainDecision.action())) {
            deliveryService.ignored(companionId, last.getId());
            return new PipelineResult(PipelineResult.Outcome.IGNORE, brainDecision, emotion,
                    recall, last, "决定不回(忽略)", attention);
        }

        // 回复路径
        return new PipelineResult(PipelineResult.Outcome.REPLY, brainDecision, emotion,
                recall, last, "进入回复路径", attention);
    }

    /** 复查等待时间(分钟): 由决策优先级决定, 30min~4h */
    private static long reviewDelayMinutes(BrainDecision d) {
        if (d == null) return 60;
        double p = d.priority();
        if (p >= 0.7) return 30;
        if (p >= 0.5) return 90;
        return 180;
    }

    /** 记忆激活: 用 MemoryAgent 的结果重新排序 + §19 召回概率阈值过滤(回退时保持原序) */
    private List<Memory> applyActivation(List<Memory> candidates, MemoryRecallResult recall) {
        if (candidates == null || candidates.isEmpty()) return candidates;

        // §19: 召回概率过滤 —— 只有"会被真正想起"的记忆才进入当前认知
        List<Memory> aboveThreshold = recallProbabilityService.filterAboveThreshold(
                candidates, recall, MemoryRecallProbabilityService.DEFAULT_THRESHOLD);

        if (recall == null || recall.activations() == null || recall.activations().isEmpty()) {
            return aboveThreshold;
        }
        Map<String, Double> activation = new HashMap<>();
        for (MemoryRecallResult.MemoryActivation a : recall.activations()) {
            activation.put(a.memoryId(), a.activation());
        }
        List<Memory> sorted = new ArrayList<>(aboveThreshold);
        sorted.sort((x, y) -> Double.compare(
                activation.getOrDefault(y.getId(), 0.0),
                activation.getOrDefault(x.getId(), 0.0)));
        return sorted;
    }

    private List<Memory> retrieveMemories(String userId, String companionId, String query) {
        try {
            return memoryService.retrieve(userId, companionId, query, 8);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> summarizeRecent(List<MessageView> messages) {
        List<String> out = new ArrayList<>();
        for (MessageView m : messages) {
            String prefix = "user".equals(m.getSenderType()) ? "用户: " : "我: ";
            out.add(prefix + m.getContent());
        }
        return out;
    }

    /** §31: 延迟回复时记录"想回复但暂时没回"的未完成想法(由激活 Job 决定未来是否想起) */
    private void recordUnfinishedThought(String companionId, String messageText, BrainDecision d) {
        try {
            String content = d != null && d.expressionGoal() != null && !d.expressionGoal().isBlank()
                    ? "想回应那句「" + truncate(messageText, 30) + "」—— " + d.expressionGoal()
                    : "还没回那句「" + truncate(messageText, 30) + "」, 等忙完想补一句";
            double priority = d != null ? Math.max(0.3, d.priority()) : 0.4;
            // 未完成想法有效期: 4~24h(太久就忘了)
            java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now()
                    .plusHours((long) (4 + (1 - priority) * 20));
            thoughtService.createUnfinished(companionId, content, "CONVERSATION",
                    null, priority, expiresAt);
        } catch (Exception ignored) {
            // 未完成想法记录失败不影响主流程
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 手机通知触达(与 AttentionService 一致) */
    private static double phoneNotificationFactor(PhoneState phone) {
        if (phone == null) return 0.5;
        if (phone.isDoNotDisturb()) return 0;
        String mode = phone.getNotificationMode() == null ? "vibrate" : phone.getNotificationMode();
        double base = switch (mode) {
            case "dnd" -> 0.0;
            case "silent" -> 0.2;
            case "vibrate" -> 0.5;
            case "sound" -> 0.75;
            default -> 0.5;
        };
        String loc = phone.getPhoneLocation() == null ? "hand" : phone.getPhoneLocation();
        switch (loc) {
            case "hand" -> base += 0.25;
            case "bag" -> base -= 0.1;
            case "other_room" -> base -= 0.3;
            default -> { }
        }
        return Math.max(0, Math.min(1, base));
    }

    /** 流水线结果 */
    public record PipelineResult(Outcome outcome, BrainDecision brainDecision,
                                 EmotionAppraisalResult emotion, MemoryRecallResult recall,
                                 MessageView lastMessage, String reason,
                                 AttentionService.Attention attention) {

        public enum Outcome {
            /** 她根本没看到(静音/勿扰/在忙) */
            IGNORE_NOT_NOTICED,
            /** 决定不回 */
            IGNORE,
            /** 看到了但不回(待复查) */
            DEFERRED,
            /** 进入回复路径 */
            REPLY
        }

        public boolean shouldReply() {
            return outcome == Outcome.REPLY;
        }

        public boolean isDeferred() {
            return outcome == Outcome.DEFERRED;
        }

        public boolean isIgnored() {
            return outcome == Outcome.IGNORE || outcome == Outcome.IGNORE_NOT_NOTICED;
        }
    }
}
