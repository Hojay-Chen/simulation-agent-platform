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
 *
 * <h2>它是这张表的<b>唯一</b>推进者, 而排期就在这张表上</h2>
 *
 * 每一轮的入口是 {@code pending_message_states.next_review_at <= now}。于是本类里每一条
 * "这次先不回"的出路都<b>必须</b>做同一件事: 把那一列往后挪 —— 否则那一行<b>下一分钟
 * 还在原地到期</b>, 而这一轮会连同模型调用一起重来一次, 每分钟一次, 永不结束。
 *
 * <p>三条出路, 三种挪法, 差别在于"这次算不算她想过了":
 * <ul>
 *   <li>{@code SLEEPING} → {@link PendingMessageService#postpone 推后一小时, 不计数}。
 *       她睡着了, 没想过这条消息 —— 用 {@code deferReview} 的话她睡三觉这条就"被放下"了。</li>
 *   <li>策略预筛/复查 Brain 说"稍后再回" → {@link PendingMessageService#deferReview 推后并记一次},
 *       到 {@link PendingMessageService#MAX_REVIEWS} 就放下("人偶尔会忘记")。</li>
 *   <li>Brain 说不回 → {@link PendingMessageService#markExpired 放下}。</li>
 * </ul>
 *
 * <h2>被暂停的 agent 不复查 —— 这一条与 {@code AgentWakeupJob} 的纪律不同, 是有意的</h2>
 *
 * {@code AgentWakeupJob} 对暂停中的 agent <b>照样投信</b>, 理由是那封信会躺在信箱里等她
 * 恢复, 暂停不等于"这段时间对她不存在"。本类不能照抄这条: 它投的不是信, 它<b>当场就调模型</b>
 * ({@code PerceptionEngine} 的向量化、{@code BrainAgent} 的决策、回复路径的生成)。
 * 为一个操作员刚刚明确停掉的 agent 付这笔钱, 正是 {@code scripts/agents-off.sh} 想止住的血。
 *
 * <p>而且"跳过"在这里<b>不丢事</b>: 行还是 PENDING, {@code next_review_at} 还是那个过去的时刻,
 * 于是她被恢复之后第一轮就把它捞起来 —— 与投信那条路殊途同归, 但没有中间那一笔开销。
 */
@Slf4j
@Component
public class PendingMessageReevaluationJob {

    private final PendingMessageService pendingService;
    private final BrainAgent brainAgent;
    private final CompanionRuntime runtime;
    private final ChatWorldPort chatWorld;
    private final MessageDeliveryService deliveryService;
    /** Agent 开关: 暂停中的 agent 不该被复查 —— 见类注释。 */
    private final com.luxera.companion.persona.AgentSwitchService agentSwitch;
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
                                         com.luxera.companion.persona.AgentSwitchService agentSwitch,
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
        this.agentSwitch = agentSwitch;
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
                // 抛异常的那条路径也没推后时刻, 于是它下一分钟还在原地到期 ——
                // 一条毒行会被每分钟捞一次, 永远。兜底推后一小时(**只在它仍到期时**,
                // 见 postponeIfOverdue: 已经正常延后过的不该被这一次兜底改慢)。
                try {
                    pendingService.postponeIfOverdue(p.getId(),
                            LocalDateTime.now().plusHours(1), "复查出错, 稍后再试");
                } catch (Exception nested) {
                    // 兜底自己失败只能记下来 —— 在调度线程上抛出去会吃掉整个 tick,
                    // 而那会让**其他** agent 的复查一起停摆(与 AgentWakeupJob 同一条纪律)。
                    log.warn("[已读复查] {} 兜底推后也失败: {}", p.getMessageId(), nested.getMessage());
                }
            }
        }
    }

    private void reevaluate(PendingMessageState p) {
        LocalDateTime now = LocalDateTime.now();
        String companionId = p.getCompanionId();
        String userId = p.getUserId();
        String conversationId = p.getConversationId();

        // 暂停中的 agent 不复查 —— 见类注释。行原地不动, 恢复后第一轮就回来。
        if (!agentSwitch.isRunnable(companionId)) {
            log.debug("[已读复查] {} 已暂停, 保留待复查行", companionId);
            return;
        }

        // 现在睡觉/勿扰 → 再延后。不计数: 她没想过这条消息, 醒来还要看(见类注释)。
        CompanionAvailability availability = availabilityService.current(companionId, now, null);
        if (availability == CompanionAvailability.SLEEPING) {
            pendingService.postpone(p.getId(), now.plusHours(1), "她睡着了");
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
                // 忙/疲惫 → 再延后(真人忙的时候想起也不会立刻回)。
                // 写回**本行**的 next_review_at 而不是另排一个动作: 排期只有一处, 见类注释。
                boolean kept = pendingService.deferReview(p.getId(), now.plusMinutes(delay.delayMinutes()),
                        "忙/疲惫, 稍后再看", null);
                log.info("[已读复查] {} {} 忙/疲惫, 策略延后 {} 分钟再复查",
                        companionId, p.getMessageId(), delay.delayMinutes());
                if (!kept) {
                    log.info("[已读复查] {} {} 复查 {} 次仍未回, 放下这件事",
                            companionId, p.getMessageId(), PendingMessageService.MAX_REVIEWS);
                }
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
            // 继续冷处理 → 再推后; §54: 她"想过要回但又被别的事打断" → 记"想回忘了"的摩擦。
            // noteWantedToReply 走的就是 deferReview(计数 + 到上限就放下), 推后 3 小时。
            if (!pendingService.noteWantedToReply(p.getId())) {
                log.info("[已读复查] {} {} 复查 {} 次仍未回, 放下这件事",
                        companionId, p.getMessageId(), PendingMessageService.MAX_REVIEWS);
            }
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
            // markReplied 就是"这一行退出队列"的全部: 排期在行自己身上, 行一走队列里就没有它了,
            // 没有第二处需要取消(曾经这里还 cancelPending 一个另存的动作表, 那张表已删)。
            pendingService.markReplied(p.getMessageId());
            // conversationId **必须**在这里 —— 它不是"顺带补的一个字段"。
            // 前端按它决定这条消息该不该插进**当前打开的那个**房间, 而它对缺字段的
            // 兜底是"认不出归属 → 算当前房间的"(见 chat-platform 的 `lib/roomEvents.ts`)。
            // 少了它, 一条补发给 A 会话的延迟回复会出现在用户正开着的 B 会话里 ——
            // 而更糟的是它**能**被插进去: 平台里另外三处 COMPANION_MESSAGE
            // (AgentRuntime 两处、ProactiveEngine 一处)都带这个字段, 只有这一处曾经没带。
            chatWorld.publishEvent(companionId, ChatEventTypes.COMPANION_MESSAGE,
                    Map.of("messageId", sent.getId(), "conversationId", conversationId,
                            "content", reply, "senderType", "companion",
                            "deferredReply", true));
            log.info("[已读复查] {} 回复了未回消息: {}", companionId, reply.substring(0, Math.min(40, reply.length())));
        } catch (Exception e) {
            log.warn("[已读复查] 回复失败,保持待复查: {}", e.getMessage());
            // 回复失败 → 一小时后再试。**必须**推后这一行: 不推的话它下一分钟还到期,
            // 于是"生成失败"会变成每分钟重试一次同一个模型错误("保持待复查"的本意
            // 是留住它, 不是每分钟再撞一次墙)。这条也吃一次复查预算。
            pendingService.deferReview(p.getId(), LocalDateTime.now().plusHours(1), "回复失败, 稍后重试", null);
        }
    }
}
