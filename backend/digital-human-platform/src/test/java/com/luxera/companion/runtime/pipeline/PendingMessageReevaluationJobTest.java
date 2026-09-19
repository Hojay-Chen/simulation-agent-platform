package com.luxera.companion.runtime.pipeline;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.behavior.DrivesService;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.digitalhuman.decision.DecisionPolicyEngine;
import com.luxera.companion.digitalhuman.decision.PersonDecision;
import com.luxera.companion.digitalhuman.perception.SnapshotFactory;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.AgentTraceService;
import com.luxera.companion.runtime.agent.brain.BrainAgent;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PendingMessageReevaluationJob} —— <b>"这次先不回"之后那一行去哪儿了</b>。
 *
 * <h2>这个 Job 的全部风险集中在一件事上: 那一条出路有没有推后 {@code next_review_at}</h2>
 *
 * 它是每分钟跑一次的循环, 而入口就是 {@code next_review_at <= now}。一条不推后时刻的
 * "延后"不会表现为"延后没生效", 而会表现为<b>这条消息从今往后每分钟被复查一次, 永不结束</b> ——
 * 而每一轮都要过一次 {@code PerceptionEngine}(向量化)和 {@code BrainAgent}(模型调用)。
 * 曾经两条"延后"分支把时刻写进了另一张表, 本表一个字没动, 于是 12 万行垃圾与
 * 每分钟十几行的日志刷屏持续了一个月。所以下面每一条都断言"走完之后这一行什么时候到期"。
 *
 * <h2>三条出路在测试里的分工</h2>
 * <ul>
 *   <li><b>睡着了</b> → {@code postpone}(推后, <b>不</b>计数)。测的是"不吃她的复查预算" ——
 *       否则她睡三觉这条消息就被"放下"了, 而她其实还没看过。</li>
 *   <li><b>忙/疲惫</b> → {@code deferReview}(推后 + 计数)。</li>
 *   <li><b>被暂停</b> → <b>什么都不做</b>。这一条与 {@code AgentWakeupJob} 的纪律相反
 *       (那个对暂停的 agent 照样投信), 因为本类投的不是信, 它当场就调模型 ——
 *       为一个操作员刚停掉的 agent 付这笔钱, 正是 {@code scripts/agents-off.sh} 要止住的血。
 *       而"什么都不做"在这里不丢事: 行还在, 恢复后第一轮就回来。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingMessageReevaluationJobTest {

    private static final String COMPANION = "c-1";
    private static final String PENDING_ID = "pm-1";

    @Mock private PendingMessageService pendingService;
    @Mock private BrainAgent brainAgent;
    @Mock private CompanionRuntime runtime;
    @Mock private ChatWorldPort chatWorld;
    @Mock private MessageDeliveryService deliveryService;
    @Mock private AgentSwitchService agentSwitch;
    @Mock private CompanionService companionService;
    @Mock private RelationshipService relationshipService;
    @Mock private AgentStateService agentStateService;
    @Mock private PhoneStateService phoneStateService;
    @Mock private AvailabilityService availabilityService;
    @Mock private CompanionSchedule schedule;
    @Mock private InteractionPolicyEngine interactionPolicy;
    @Mock private DrivesService drivesService;
    @Mock private AgentTraceService traceService;
    @Mock private PerceptionEngine perceptionEngine;
    @Mock private DecisionPolicyEngine decisionPolicyEngine;
    @Mock private SnapshotFactory snapshotFactory;

    private PendingMessageReevaluationJob job;

    @BeforeEach
    void setUp() {
        job = new PendingMessageReevaluationJob(pendingService, brainAgent, runtime, chatWorld,
                deliveryService, agentSwitch, companionService, relationshipService, agentStateService,
                phoneStateService, availabilityService, schedule, interactionPolicy, drivesService,
                traceService, perceptionEngine, decisionPolicyEngine, snapshotFactory);
        when(pendingService.dueForReview(any())).thenReturn(List.of(due()));
        when(agentSwitch.isRunnable(COMPANION)).thenReturn(true);
        when(availabilityService.current(eq(COMPANION), any(), any())).thenReturn(CompanionAvailability.AVAILABLE);
    }

    private PendingMessageState due() {
        PendingMessageState p = new PendingMessageState();
        p.setId(PENDING_ID);
        p.setMessageId("msg-1");
        p.setCompanionId(COMPANION);
        p.setConversationId("conv-1");
        p.setUserId("u-1");
        p.setSenderText("在吗");
        p.setNextReviewAt(LocalDateTime.now().minusMinutes(5));
        return p;
    }

    /** 策略说"忙, 30 分钟后再看" —— 预筛那条路(不打扰认知)。 */
    private void policySaysBusy() {
        when(decisionPolicyEngine.decide(any())).thenReturn(new DecisionPolicyEngine.DecisionOutcome(
                "busy", new PersonDecision.DelayReplyDecision("在忙", 30)));
    }

    /**
     * 抛异常的那条路径同样会把时刻留在过去 —— 于是这一条下一分钟还在到期。
     * "一条毒行被每分钟捞一次、每次都要过一次模型"正是这次修的那个故障形态,
     * 所以兜底也要钉住。
     */
    @Test
    @DisplayName("这一轮抛异常: 兜底把这一行推后一小时(不留一条每分钟复活的毒行)")
    void throwingRowIsPushedBackByTheCatchAll() {
        policySaysBusy();
        // 让"忙/疲惫"那条路自己炸掉 —— 于是 reevaluate 还没推任何时刻就出去了
        when(pendingService.deferReview(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("库抖了一下"));
        LocalDateTime before = LocalDateTime.now();

        job.run();

        verify(pendingService).postponeIfOverdue(eq(PENDING_ID), argThatAfter(before.plusMinutes(59)), anyString());
    }

    @Test
    @DisplayName("被暂停的 agent: 一个字都不做, 行留在原地(不推时刻、不计数、不调模型)")
    void pausedAgentIsSkippedEntirely() {
        when(agentSwitch.isRunnable(COMPANION)).thenReturn(false);

        job.run();

        verify(pendingService, never()).postpone(anyString(), any(), anyString());
        verify(pendingService, never()).postponeIfOverdue(anyString(), any(), anyString());
        verify(pendingService, never()).deferReview(anyString(), any(), any(), any());
        verify(pendingService, never()).markExpired(anyString());
        verify(pendingService, never()).markReplied(anyString());
        // 连"她现在醒着吗"都不问 —— 暂停是比作息更高的一层
        verify(availabilityService, never()).current(anyString(), any(), any());
        verify(brainAgent, never()).execute(any());
    }

    @Test
    @DisplayName("睡着了: 推后一小时, 而且**不**吃复查预算")
    void sleepingPostponesWithoutConsumingBudget() {
        when(availabilityService.current(eq(COMPANION), any(), any())).thenReturn(CompanionAvailability.SLEEPING);
        LocalDateTime before = LocalDateTime.now();

        job.run();

        verify(pendingService).postpone(eq(PENDING_ID), argThatAfter(before.plusMinutes(59)), anyString());
        verify(pendingService, never()).deferReview(anyString(), any(), any(), any());
        verify(pendingService, never()).markExpired(anyString());
        verify(brainAgent, never()).execute(any());
    }

    /**
     * 本测试文件里最该存在的一条: 预筛说"忙"之后, <b>这一行必须被推后</b>。
     * 旧实现正是在这里把时刻写去了另一张表, 于是这一行每分钟重新到期一次。
     */
    @Test
    @DisplayName("策略说忙: 把**这一行**推后 30 分钟(不是排去别处)")
    void busyPolicyDefersThisRow() {
        policySaysBusy();
        LocalDateTime before = LocalDateTime.now();

        job.run();

        verify(pendingService).deferReview(eq(PENDING_ID), argThatAfter(before.plusMinutes(29)), any(), any());
        verify(pendingService, never()).postpone(anyString(), any(), anyString());
        verify(brainAgent, never()).execute(any());
    }

    /**
     * 预筛到上限那一次: {@code deferReview} 返回 false(已 EXPIRED), 这一条消息不该再出现在
     * 下一轮里 —— 这是"她忘了"与"她还在想"的分界, 也是那个循环唯一的终点。
     */
    @Test
    @DisplayName("策略说忙但预算已尽: 不再推后(那一行已经成为 EXPIRED)")
    void busyPolicyRespectsBudgetExhaustion() {
        policySaysBusy();
        when(pendingService.deferReview(anyString(), any(), any(), any())).thenReturn(false);

        job.run();

        // 返回值是 false 时 job 不该再试图把它留在队列里 —— 没有第二次 postpone 兜底
        verify(pendingService, never()).postpone(anyString(), any(), anyString());
        verify(pendingService, never()).markExpired(anyString());
    }

    private static LocalDateTime argThatAfter(LocalDateTime floor) {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && t.isAfter(floor));
    }
}
