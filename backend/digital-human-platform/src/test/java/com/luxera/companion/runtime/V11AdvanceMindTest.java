package com.luxera.companion.runtime;

import com.luxera.companion.agent.CompanionRuntime;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.cognition.CognitiveWakeupService;
import com.luxera.companion.runtime.agent.expression.ExpressionAgent;
import com.luxera.companion.runtime.agent.expression.ExpressionResult;
import com.luxera.companion.agent.PerceptionEngine;
import com.luxera.companion.usermodel.UserChatStyleService;
import com.luxera.companion.agent.WorkingMemory;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.behavior.BehaviorLearningService;
import com.luxera.companion.cognition.DecisionType;
import com.luxera.companion.cognition.MindDecisionPlanner;
import com.luxera.companion.cognition.ResponseContinuityGuard;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.contracts.events.ChatEventTypes;
import com.luxera.companion.contracts.spi.ChatWorldPort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.intention.IntentionService;
import com.luxera.companion.interaction.InteractionAction;
import com.luxera.companion.interaction.InteractionDecision;
import com.luxera.companion.interaction.InteractionPolicyEngine;
import com.luxera.companion.interaction.ResponseBudget;
import com.luxera.companion.interaction.ResponseCommitment;
import com.luxera.companion.interaction.ResponseLatencyEngine;
import com.luxera.companion.mind.MindStateService;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.phone.PhoneNotification;
import com.luxera.companion.phone.PhoneNotificationService;
import com.luxera.companion.relationship.RelationshipService;
import com.luxera.companion.runtime.agent.brain.BrainDecision;
import com.luxera.companion.runtime.pipeline.MessagePipeline;
import com.luxera.companion.runtime.v11.CognitionDecisionRecorder;
import com.luxera.companion.runtime.v11.V11CognitionSwitch;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateService;
import com.luxera.companion.state.AvailabilityService;
import com.luxera.companion.state.CompanionAvailability;
import com.luxera.companion.digitalhuman.conversation.ConversationOutputValidator;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.luxera.companion.wakeup.AgentWakeupService;
import com.luxera.companion.world.AgentEventType;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 Phase 4 验收 —— <b>"回复不再是默认行为"</b>。
 *
 * <p>这个文件回答的是实施计划 §4 那句验收:
 * <pre>
 *   busy 时收到消息 → DEFER/IGNORE 且<b>不回复</b>
 *   到点唤醒        → READ → REPLY
 * </pre>
 *
 * <h2>为什么非要用一整套 mock 把 AgentRuntime 拼出来</h2>
 * 要证明的那句话是"<b>她不会发消息出去</b>", 而这是一个关于<b>整条链</b>的断言。
 * 决策算得对不对由 {@code MindDecisionPlannerTest} 覆盖, 但"算出来之后有没有真的拦住"
 * 只有在这里才看得见 —— 用一个假的 AgentRuntime 是证明不了这件事的。
 *
 * <p>断言的方式统一是 {@code verify(chatWorld, never()).append(any())}:
 * 不是"她回到了某一行"(那种断言会被重构破坏), 而是"没有任何一条消息被写进会话" ——
 * 那正是验收条件本身。
 *
 * <p>这里用<b>真实的</b> {@link MindDecisionPlanner}, 而唤醒服务用 mock。
 * 分界线是: planner 与拦截之间的<b>接缝</b>是本文件要测的东西, 不能 mock 掉;
 * 而唤醒等级是<b>上游给的事实</b>, 由用例直接指定才测得出"忙"与"醒"的差别。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V11AdvanceMindTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";

    @Mock private ChatWorldPort chatWorld;
    @Mock private PerceptionEngine perceptionEngine;
    @Mock private WorkingMemory workingMemory;
    @Mock private UserChatStyleService userChatStyleService;
    @Mock private BehaviorLearningService behaviorLearningService;
    @Mock private MessagePipeline messagePipeline;
    @Mock private ExpressionAgent expressionAgent;
    @Mock private InteractionPolicyEngine interactionPolicy;
    @Mock private ResponseLatencyEngine latencyEngine;
    @Mock private AgentStateService agentStateService;
    @Mock private AvailabilityService availabilityService;
    @Mock private RelationshipService relationshipService;
    @Mock private PhoneNotificationService phoneNotificationService;
    /**
     * 真的(spy), 只把 {@code evaluate} 换掉。
     *
     * <p>必须是 spy 而不是 mock: {@code requiresCognition(level)} 是一个<b>纯判断</b>
     * (ATTENTION 及以上才算唤醒), 也是老链"低价值消息不打扰"那道闸门的依据。
     * 用 mock 的话它默认返回 false —— 于是<b>每一条消息</b>都会走"不打扰"分支,
     * 而那些断言"她没发消息"的用例会全部通过, 却是因为一个与它们无关的原因通过的。
     */
    @Spy private CognitiveWakeupService wakeup = new CognitiveWakeupService();
    @Mock private IntentionService intentionService;
    @Mock private CompanionSchedule schedule;
    @Mock private CompanionRuntime companionRuntime;
    @Mock private com.luxera.companion.cognitive.CognitiveSessionService cognitiveSessionService;
    @Mock private com.luxera.companion.reality.RealityConsistencyChecker realityChecker;
    @Mock private com.luxera.companion.digitalhuman.state.StateVersionGate stateVersionGate;
    @Mock private RealityLedger realityLedger;
    @Mock private ConversationOutputValidator outputValidator;
    @Mock private AgentSwitchService agentSwitch;
    @Mock private MindStateService mindStates;
    @Mock private CognitionDecisionRecorder recorder;
    /**
     * Phase 5 加的。它是认知链<b>唯一</b>的外部副作用出口 —— 断言它, 就是断言
     * "她决定押后"这件事没有停在内存里, 而是变成了一行会有执行者的闹钟。
     */
    @Mock private AgentWakeupService wakeups;

    /** 真的: 本文件要测的正是决策与拦截之间那条接缝 */
    private final PersonActorRegistry personActorRegistry = new PersonActorRegistry();
    private final MindDecisionPlanner planner = new MindDecisionPlanner(new CognitiveWakeupService());
    private final ResponseContinuityGuard guard = new ResponseContinuityGuard();

    private V11CognitionSwitch cognitionSwitch;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        cognitionSwitch = new V11CognitionSwitch();
        ReflectionTestUtils.setField(cognitionSwitch, "enabled", true);
        ReflectionTestUtils.setField(cognitionSwitch, "shadow", false);

        runtime = new AgentRuntime(chatWorld, perceptionEngine, workingMemory, userChatStyleService,
                behaviorLearningService, messagePipeline, expressionAgent, interactionPolicy, latencyEngine,
                agentStateService, availabilityService, relationshipService, phoneNotificationService,
                wakeup, intentionService, schedule, companionRuntime,
                cognitiveSessionService, realityChecker, stateVersionGate, personActorRegistry,
                realityLedger, null, null, outputValidator, null, agentSwitch,
                null, null, null, mindStates, planner, cognitionSwitch, recorder, guard, wakeups);

        when(agentSwitch.isRunnable(AGENT)).thenReturn(true);
        when(perceptionEngine.perceive(anyString()))
                .thenReturn(new PerceptionEngine.Perception("greeting", "calm", "今天的面试"));
        when(schedule.activityFor(eq(AGENT), any())).thenReturn(CompanionSchedule.Activity.EVENING);
        when(agentStateService.get(AGENT)).thenReturn(mock(AgentState.class));
        when(phoneNotificationService.create(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(mock(PhoneNotification.class));
        when(chatWorld.messages(CONV)).thenReturn(List.of(userMessage()));
        when(chatWorld.recentMessages(eq(CONV), anyInt())).thenReturn(List.of(userMessage()));
        when(chatWorld.append(any())).thenReturn(assistantMessage());
    }

    // ─────────────────────────── 构造输入 ───────────────────────────

    private MessageView userMessage() {
        return MessageView.builder().id("m1").conversationId(CONV).senderType("user")
                .content("我今天面试挂了").deliveryStatus("DELIVERED").build();
    }

    private MessageView assistantMessage() {
        return MessageView.builder().id("a1").conversationId(CONV).senderType("companion")
                .content("别难过, 抱抱你").build();
    }

    /** 老链流水线的产出。{@code outcome} 决定老链"打算怎么做"。 */
    private void pipelineSays(MessagePipeline.PipelineResult.Outcome outcome, double notice, double inspect) {
        when(messagePipeline.process(any(), any(), any(), anyList(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(new MessagePipeline.PipelineResult(outcome,
                        new BrainDecision("REPLY", 0.5, List.of(), "安慰他", 0.8, false,
                                null, 40, 1, null, null),
                        null, null, userMessage(), "test",
                        new AttentionService.Attention(0.5, 0.5, notice, inspect, 0)));
    }

    private void wakes(CognitiveWakeupService.WakeLevel level) {
        // 用 doReturn 而不是 when: 在 spy 上, when(...) 会先把<b>真实方法</b>跑一遍,
        // 而真实 evaluate 依赖注入进去的配置, 在测试里没有
        doReturn(level).when(wakeup).evaluate(any(), anyDouble());
    }

    private void available(CompanionAvailability a) {
        when(availabilityService.current(eq(AGENT), any(), any())).thenReturn(a);
    }

    /** 让回复路径真的能走通(不 stub 的话它会在"没写出来"就返回, 于是测不出"发出去了") */
    private void replyPathWorks() {
        when(companionRuntime.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CompanionRuntime.ChatOutcome("别难过, 抱抱你", "别难过, 抱抱你", null, null));
        when(expressionAgent.execute(any())).thenReturn(new ExpressionResult(
                ExpressionResult.ExpressionStrategy.NEUTRAL, List.of(), false, false));
        when(interactionPolicy.decide(any())).thenReturn(new InteractionDecision(
                InteractionAction.REPLY_NOW, ResponseCommitment.CASUAL, 0L,
                true, false, false, "test", 0.8,
                ResponseBudget.forCommitment(ResponseCommitment.CASUAL, false)));
        when(latencyEngine.computeDelayMs(any(), any(), anyDouble(), anyDouble(), any(), any())).thenReturn(0L);
        when(stateVersionGate.snapshot(AGENT)).thenReturn(1L);
        when(stateVersionGate.tryCommit(eq(AGENT), anyLong())).thenReturn(true);
        when(outputValidator.validate(any())).thenReturn(null);
        when(realityChecker.check(any(), any(), any(), any())).thenReturn(null);
    }

    private void run() {
        runtime.advanceMind(USER, AGENT, CONV, List.of(userMessage()));
    }

    // ─────────────────────────── 验收: 不回复 ───────────────────────────

    @Nested
    @DisplayName("验收: 在忙 → DEFER, 且没有任何消息被写进会话")
    class Silence {

        @Test
        void busyMeansDeferAndNoMessageIsSent() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);

            run();

            // 整个 Phase 4 的核心断言: 老链说 REPLY, 而新决策说 DEFER —— 消息不出去
            verify(chatWorld, never()).append(any());
            verify(companionRuntime, never()).generate(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
            // 但"不回"必须留下痕迹, 否则它与一次崩溃在现象上一模一样
            verify(chatWorld).publishEvent(eq(AGENT), eq(ChatEventTypes.USER_MESSAGE_STATUS),
                    argThat(m -> "READ".equals(m.get("status")) && "V11_DEFER".equals(m.get("action"))));
            verify(intentionService).create(eq(AGENT), eq(USER), anyString(), anyDouble(),
                    anyString(), anyString(), any(), anyInt());
        }

        @Test
        void aLowValueMessageIsDoNothingAndNotEvenMarkedRead() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.MICRO_WAKE);
            available(CompanionAvailability.AVAILABLE);

            run();

            verify(chatWorld, never()).append(any());
            verify(chatWorld, never()).updateDeliveryStatus(any(), any(), anyString());
            verify(chatWorld).publishEvent(eq(AGENT), eq(ChatEventTypes.USER_MESSAGE_STATUS),
                    argThat(m -> "V11_DO_NOTHING".equals(m.get("action"))
                            && "below_cognition_threshold".equals(m.get("reason"))));
        }

        @Test
        void observeKeepsTheMessageUnread() {
            // "看到了但没点开" 在老链里也是不标记已读的 —— 真人不点开就不算已读
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.2);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.AVAILABLE);

            run();

            verify(chatWorld, never()).append(any());
            verify(chatWorld, never()).updateDeliveryStatus(any(), any(), anyString());
        }

        @Test
        void aPausedAgentNeverReachesTheDecisionAtAll() {
            when(agentSwitch.isRunnable(AGENT)).thenReturn(false);
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.DEEP_THINKING);
            available(CompanionAvailability.AVAILABLE);

            run();

            verifyNoInteractions(recorder);
            verify(chatWorld, never()).append(any());
        }
    }

    // ─────────────────────────── 验收: 唤醒 → 读 → 回 ───────────────────────────

    @Nested
    @DisplayName("验收: 到点唤醒 → READ → REPLY")
    class Engagement {

        @Test
        void awakeAndAvailableMeansReadThenReply() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.DELIBERATION);
            available(CompanionAvailability.AVAILABLE);
            replyPathWorks();

            run();

            verify(chatWorld).append(any());
            // 顺序不是风格问题: 先回后读意味着"她回了但没看"
            var ordered = inOrder(chatWorld);
            ordered.verify(chatWorld).updateDeliveryStatus(eq(AGENT), any(), eq("READ"));
            ordered.verify(chatWorld).append(any());
        }

        @Test
        void beingUrgedOverridesBusyAndTheReplyGoesOut() {
            // V10 既有语义: Busy ≠ 不回复。这条用例把它钉在接缝上 ——
            // planner 的单元测试证明了规则, 这里证明规则真的接到了链上
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);
            replyPathWorks();

            runtime.advanceMind(USER, AGENT, CONV, List.of(
                    MessageView.builder().id("m1").conversationId(CONV).senderType("user")
                            .content("在吗? 怎么不回我").deliveryStatus("DELIVERED").build()));

            verify(chatWorld).append(any());
        }
    }

    // ─────────────────────────── shadow ───────────────────────────

    @Nested
    @DisplayName("shadow: 记账, 但一个字都不改")
    class Shadow {

        @BeforeEach
        void shadowOn() {
            ReflectionTestUtils.setField(cognitionSwitch, "enabled", false);
            ReflectionTestUtils.setField(cognitionSwitch, "shadow", true);
        }

        @Test
        void shadowRecordsTheDivergenceWithoutBlockingTheReply() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);   // 新决策会说 DEFER
            replyPathWorks();

            run();

            verify(recorder).record(argThat(d -> d.type() == DecisionType.DEFER), eq(true), eq("REPLY"));
            // 行为一点没变: 这条回复照样出去了。一旦 shadow 能拦下一次回复,
            // 它就不再是"观察", 而是一次开关看起来还关着的静默上线
            verify(chatWorld).append(any());
        }

        @Test
        void shadowDoesNotEvenRunWhenBothSwitchesAreOff() {
            ReflectionTestUtils.setField(cognitionSwitch, "shadow", false);
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);
            replyPathWorks();

            run();

            verifyNoInteractions(recorder);
            verify(chatWorld).append(any());
        }
    }

    // ─────────────────────────── 老链仍然在位 ───────────────────────────

    @Nested
    @DisplayName("开关关掉时, 老链的三个判断一字未动")
    class OldChain {

        @BeforeEach
        void v11Off() {
            ReflectionTestUtils.setField(cognitionSwitch, "enabled", false);
            ReflectionTestUtils.setField(cognitionSwitch, "shadow", false);
        }

        @Test
        void ignoredStaysIgnored() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.IGNORE, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.AVAILABLE);

            run();

            verify(chatWorld, never()).append(any());
            verify(chatWorld).publishEvent(eq(AGENT), eq(ChatEventTypes.USER_MESSAGE_STATUS),
                    argThat(m -> "IGNORE".equals(m.get("action"))));
        }

        @Test
        void lowValueStaysMicroWake() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.MICRO_WAKE);
            available(CompanionAvailability.AVAILABLE);

            run();

            verify(chatWorld, never()).append(any());
            verify(chatWorld).publishEvent(eq(AGENT), eq(ChatEventTypes.USER_MESSAGE_STATUS),
                    argThat(m -> "MICRO_WAKE".equals(m.get("action"))));
        }

        @Test
        void deferredStillCreatesAnIntention() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.DEFERRED, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.AVAILABLE);

            run();

            verify(chatWorld, never()).append(any());
            verify(intentionService).create(eq(AGENT), eq(USER), anyString(), anyDouble(),
                    anyString(), anyString(), any(), anyInt());
        }
    }

    // ─────────────────────────── Phase 5: 押后必须有人捡回来 ───────────────────────────

    /**
     * 这一组的核心是一句话: <b>一个没有执行者的"待会儿"等于永远不回</b>。
     *
     * <p>Phase 4 让 DEFER 成了一个真实的决策; 但直到这里之前, 那个决策除了在账本上
     * 记一笔之外什么也没发生 —— 老链的 DEFER 就是这个下场。Phase 5 给它的执行者
     * 是一行 {@code agent_schedule}: 到点之后会有一封信投进她的信箱,
     * 而信拆开之后她重新面对这条会话。
     */
    @Nested
    @DisplayName("Phase 5: 押后 → 排一个闹钟 (否则'待会儿'没有执行者)")
    class WakeupScheduling {

        @Test
        void deferSchedulesAnAlarmAtTheDecisionsOwnReviewTime() {
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);   // planner 会说 DEFER(60 分钟后)

            run();

            verify(wakeups).schedule(eq(AGENT), any(LocalDateTime.class),
                    eq(AgentEventType.SCHEDULED_WAKEUP),
                    // 来源键锚在<b>会话</b>上: 同一段对话里押后两次是"改主意",
                    // 用消息 id 会攒出一串到点一起响的闹钟
                    eq(AgentWakeupService.key(AgentWakeupService.SRC_DECISION, CONV)),
                    contains("busy"));
        }

        @Test
        void replySchedulesNothing() {
            // 回了就了结了 —— 一个 REPLY 还要排闹钟的话, 她会过一小时又想起来回一次
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.DELIBERATION);
            available(CompanionAvailability.AVAILABLE);
            replyPathWorks();

            run();

            verify(chatWorld).append(any());
            verifyNoInteractions(wakeups);
        }

        @Test
        void bothSwitchesOffTouchesNothing() {
            ReflectionTestUtils.setField(cognitionSwitch, "enabled", false);
            ReflectionTestUtils.setField(cognitionSwitch, "shadow", false);
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);
            replyPathWorks();   // 开关全关时老链是唯一在回话的那条路, 它会一路走到发消息

            run();

            // 连算都不算(decideInMind 在第一行就返回 null), 于是也不会有闹钟
            verifyNoInteractions(wakeups);
        }

        @Test
        void shadowAlsoSchedulesBecauseTheAlarmIsHerClockNotTheWorld() {
            // 这一条是<b>有意</b>的, 写下来免得后来者以为它是漏网之鱼:
            // shadow 不许写世界(消息/关系/活动/通知), 但允许写她自己的时钟与账本 ——
            // 一行闹钟只会产出一封由 shadow 消费者拆开、只记一笔的信, 对方看不出差别。
            // 不排的话, 切流那天这条接线就是第一次被执行, 而"第一次执行"永远是最危险的时刻。
            ReflectionTestUtils.setField(cognitionSwitch, "enabled", false);
            ReflectionTestUtils.setField(cognitionSwitch, "shadow", true);
            pipelineSays(MessagePipeline.PipelineResult.Outcome.REPLY, 0.9, 0.9);
            wakes(CognitiveWakeupService.WakeLevel.ATTENTION);
            available(CompanionAvailability.BUSY);
            replyPathWorks();   // shadow 期老链继续跑, 回话的仍然是它

            run();

            verify(wakeups).schedule(eq(AGENT), any(LocalDateTime.class),
                    eq(AgentEventType.SCHEDULED_WAKEUP), anyString(), anyString());
            // 世界的分界线画在"V11 的决策有没有落到世界里": DEFER 那一笔(已读 + 状态事件 +
            // 现实账本)只由 applyNonReplyDecision 写, 而它只在 enabled 时被调用。
            // 闹钟是唯一被允许的例外 —— 因为它不是世界, 是她自己的时钟。
            verify(chatWorld, never()).publishEvent(eq(AGENT), eq(ChatEventTypes.USER_MESSAGE_STATUS),
                    argThat(m -> "V11_DEFER".equals(m.get("action"))));
        }
    }
}
