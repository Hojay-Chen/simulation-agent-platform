package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.appraisal.AppraisalService;
import com.luxera.companion.behavior.DrivesService;
import com.luxera.companion.contracts.api.MessageAppendCommand;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.ScheduledActionService;
import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.runtime.agent.brain.BrainContext;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 已读不回复查 Job(§79/§80): 到复查点的消息唤醒 Brain 重新评估。
 * 可能回复 / 继续冷处理 / 放下这件事 —— 这就是行为连续性。
 */
@Slf4j
@Component
public class PendingMessageReevaluationJob {

    private final PendingMessageService pendingService;
    private final BrainAgent brainAgent;
    private final CompanionRuntime runtime;
    private final ChatWorldPort chatWorld;
    private final MessageDeliveryService deliveryService;
    private final ScheduledActionService scheduledActionService;
    private final CompanionService companionService;
    private final RelationshipService relationshipService;
    private final AgentStateService agentStateService;
    private final PhoneStateService phoneStateService;
    private final AvailabilityService availabilityService;
    private final CompanionSchedule schedule;
    private final InteractionPolicyEngine interactionPolicy;
    private final DrivesService drivesService;
    private final AgentTraceService traceService;
    private final PerceptionEngine perceptionEngine;
    /** V10 §13: 决策策略(复查预筛 —— 忙/疲惫时直接延后, 不打扰认知) */
    private final com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine decisionPolicyEngine;
    /** V10: 状态快照工厂(策略输入) */
    private final com.luxera.companion.digitalhuman.perception.SnapshotFactory snapshotFactory;

    public PendingMessageReevaluationJob(PendingMessageService pendingService, BrainAgent brainAgent,
                                         CompanionRuntime runtime, ChatWorldPort chatWorld,
                                         MessageDeliveryService deliveryService,
                                         ScheduledActionService scheduledActionService,
                                         CompanionService companionService,
                                         RelationshipService relationshipService, AgentStateService agentStateService,
                                         PhoneStateService phoneStateService, AvailabilityService availabilityService,
                                         CompanionSchedule schedule, InteractionPolicyEngine interactionPolicy,
                                         DrivesService drivesService, AgentTraceService traceService,
                                         PerceptionEngine perceptionEngine,
                                         com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine decisionPolicyEngine,
                                         com.luxera.companion.digitalhuman.perception.SnapshotFactory snapshotFactory) {
        this.pendingService = pendingService;
        this.brainAgent = brainAgent;
        this.runtime = runtime;
        this.chatWorld = chatWorld;
        this.deliveryService = deliveryService;
        this.scheduledActionService = scheduledActionService;
        this.companionService = companionService;
        this.relationshipService = relationshipService;
        this.agentStateService = agentStateService;
        this.phoneStateService = phoneStateService;
        this.availabilityService = availabilityService;
        this.schedule = schedule;
        this.interactionPolicy = interactionPolicy;
        this.drivesService = drivesService;
        this.traceService = traceService;
        this.perceptionEngine = perceptionEngine;
        this.decisionPolicyEngine = decisionPolicyEngine;
        this.snapshotFactory = snapshotFactory;
    }

    @Scheduled(cron = "${app.scheduler.pending-recheck-cron:0 */1 * * * *}")
    public void run() {
        List<PendingMessageState> due;
        try {
            due = pendingService.dueForReview(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[已读复查] 查询到期消息失败: {}", e.getMessage());
            return;
        }
        if (due.isEmpty()) return;
        // 逐条独立处理: 单条异常不污染其他条目(不设批量 @Transactional,
        // 各 service 的写方法已各自维护事务, 避免一条失败→整批 rollback-only)
        for (PendingMessageState p : due) {
            try {
                reevaluate(p);
            } catch (Exception e) {
                log.warn("[已读复查] {} 失败: {}", p.getMessageId(), e.getMessage());
            }
        }
    }

    private void reevaluate(PendingMessageState p) {
        LocalDateTime now = LocalDateTime.now();
        String companionId = p.getCompanionId();
        String userId = p.getUserId();
        String conversationId = p.getConversationId();

        // 现在睡觉/勿扰 → 再延后
        CompanionAvailability availability = availabilityService.current(companionId, now, null);
        if (availability == CompanionAvailability.SLEEPING) {
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    now.plusHours(1), Map.of("pendingMessageId", p.getMessageId()));
            return;
        }

        var state = agentStateService.get(companionId);
        var rel = relationshipService.find(userId, companionId);
        String relationshipStage = rel != null ? rel.getRelationshipStage() : "new";
        double closeness = state != null ? state.getEmotionalCloseness() : 0.3;
        PerceptionEngine.Perception perception = perceptionEngine.perceive(p.getSenderText());

        // V10 §13: 决策策略预筛 —— 她记得这条消息(FOCUSED); 忙/疲惫 → 策略直接判定
        // "稍后再回"(DelayReply), 不打扰认知(省一次 LLM 调用); 其余情况走原有 Brain 决策。
        try {
            double importance = reviewImportance(perception, p.getSenderText());
            com.luxera.companion.digitalhuman.decision.DecisionContext decisionContext =
                    com.luxera.companion.digitalhuman.decision.DecisionContext.of(
                            companionId, null, com.luxera.companion.digitalhuman.perception.PerceptionLevel.FOCUSED,
                            importance,
                            snapshotFactory.life(companionId, now),
                            snapshotFactory.mind(companionId),
                            relationshipSnapshot(rel));
            com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine.DecisionOutcome policyDecision =
                    decisionPolicyEngine.decide(decisionContext);
            if (policyDecision.decision() instanceof com.luxera.companion.digitalhuman.decision.PersonDecision.DelayReplyDecision delay) {
                // 忙/疲惫 → 再延后(真人忙的时候想起也不会立刻回)
                scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                        now.plusMinutes(delay.delayMinutes()),
                        Map.of("pendingMessageId", p.getMessageId()));
                log.info("[已读复查] {} 忙/疲惫, 策略延后 {} 分钟再复查", companionId, delay.delayMinutes());
                return;
            }
        } catch (Exception e) {
            log.debug("[已读复查] 策略预筛失败, 走原决策: {}", e.getMessage());
        }

        InteractionDecision baseline = interactionPolicy.decide(new InteractionPolicyEngine.InteractionInput(
                p.getSenderText(), perception.intent(), perception.emotion(),
                state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                relationshipStage,
                rel != null && List.of("close", "deeply_connected").contains(rel.getRelationshipStage()),
                false, availability,
                AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3),
                closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0));

        BrainDecision decision = brainAgent.execute(new BrainContext(
                companionId, userId, p.getMessageId(), p.getSenderText(),
                List.of("(一条之前没回的消息)"), "复查未回消息",
                availability.name(), state != null ? state.getEnergy() : 0.6,
                state != null ? state.getStress() : 0.3,
                state != null ? state.getSocialEnergy() : 0.6,
                state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                state != null ? state.getSadness() : 0, state != null ? state.getAnxiety() : 0,
                state != null ? state.getWarmth() : 0, state != null ? state.getMood() : "平静",
                1.0, 1.0, true, "vibrate",
                relationshipStage, closeness, perception.intent(), perception.emotion(),
                drivesService.compute(AppraisalService.AppraisalResult.fromValues(
                        state != null ? state.getHurt() : 0, state != null ? state.getAnger() : 0,
                        state != null ? state.getWarmth() : 0, 0.2, -0.1, 0.3),
                        state != null ? state.getEnergy() : 0.6, state != null ? state.getStress() : 0.3,
                        closeness, rel != null ? rel.getFamiliarity() : 0, rel != null ? rel.getIntimacy() : 0,
                        availability, p.getSenderText(), p.getSenderText().length()),
                true, false, baseline));

        if (decision.shouldReply()) {
            reply(p, userId, decision);
        } else if (decision.isDefer()) {
            // 继续冷处理 → 再延后; §54: 她"想过要回但又被别的事打断" → 记录想回忘了的摩擦
            pendingService.noteWantedToReply(p.getId());
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    LocalDateTime.now().plusHours(2), Map.of("pendingMessageId", p.getMessageId()));
        } else {
            // 放下这件事(人偶尔会忘记回)
            pendingService.markExpired(p.getMessageId());
        }
    }

    /** 复查重要性: 情绪信号/催问词/长度 → 高; 否则中 */
    private static double reviewImportance(PerceptionEngine.Perception perception, String text) {
        String emotion = perception == null ? null : perception.emotion();
        boolean emotional = emotion != null
                && !List.of("neutral", "calm", "happy").contains(emotion);
        boolean urgent = text != null && containsAny(text,
                "在吗", "怎么不", "不回", "回我", "急事", "紧急", "忙吗");
        boolean longText = text != null && text.length() > 60;
        if (emotional || urgent) return 0.7;
        if (longText) return 0.6;
        return 0.5;
    }

    private static com.luxera.companion.digitalhuman.decision.RelationshipSnapshot relationshipSnapshot(
            Relationship rel) {
        if (rel == null) {
            return com.luxera.companion.digitalhuman.decision.RelationshipSnapshot.of(
                    "new", 0, 0, 0, 0);
        }
        return com.luxera.companion.digitalhuman.decision.RelationshipSnapshot.of(
                rel.getRelationshipStage(), rel.getIntimacy(), rel.getFamiliarity(),
                rel.getTension(), rel.getConnectionPressure());
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s != null && s.contains(k)) return true;
        }
        return false;
    }

    private void reply(PendingMessageState p, String userId, BrainDecision decision) {
        String companionId = p.getCompanionId();
        String conversationId = p.getConversationId();
        List<MessageView> recent = chatWorld.recentMessages(conversationId, 30);
        InteractionDecision interaction = decision.baseline();

        try {
            CompanionRuntime.ChatOutcome outcome = runtime.generate(userId, companionId, conversationId,
                    p.getMessageId(), p.getSenderText(), recent, null, interaction);
            String reply = outcome.reply();
            if (reply == null || reply.isBlank()) {
                pendingService.markExpired(p.getMessageId());
                return;
            }
            MessageView sent = chatWorld.append(MessageAppendCommand
                    .of(conversationId, "companion", reply)
                    .withKind("DEFERRED_REPLY")
                    .withProactive(true)
                    .withIdempotencyKey("deferred-reply-" + p.getMessageId()));
            deliveryService.responded(companionId, p.getMessageId());
            pendingService.markReplied(p.getMessageId());
            scheduledActionService.cancelPending(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE);
            chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_MESSAGE,
                    Map.of("messageId", sent.getId(), "content", reply, "senderType", "companion",
                            "deferredReply", true));
            log.info("[已读复查] {} 回复了未回消息: {}", companionId, reply.substring(0, Math.min(40, reply.length())));
        } catch (Exception e) {
            log.warn("[已读复查] 回复失败,保持待复查: {}", e.getMessage());
            scheduledActionService.schedule(companionId, ScheduledActionService.RE_EVALUATE_MESSAGE,
                    LocalDateTime.now().plusHours(1), Map.of("pendingMessageId", p.getMessageId()));
        }
    }
}
