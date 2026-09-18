package com.luxera.companion.runtime.v11;

import com.luxera.companion.action.ReadMessagesAction;
import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.attention.DeliverySalience;
import com.luxera.companion.attention.DeliverySignals;
import com.luxera.companion.agent.CompanionSchedule;
import com.luxera.companion.contracts.api.MessageView;
import com.luxera.companion.phone.AwarenessLadder;
import com.luxera.companion.phone.MessageBatch;
import com.luxera.companion.phone.PhoneStateService;
import com.luxera.companion.state.AgentStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §2.2.2 —— 送达主链的两件事: <b>判定不许有副作用, 执行必须按阶梯来</b>。
 *
 * <p>用例里最重要的是 {@link ShadowSafety#assessmentTouchesNothingAtAll}。它不是
 * 一条普通的行为断言, 它是 shadow 模式之所以成立的<b>前提</b>: 判定一旦会写库,
 * 开关看起来是关的, 而 agent 的行为已经在变 —— 那是一次没人知道的静默上线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class V11DeliveryPathTest {

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String CONV = "conv-1";
    private static final List<String> IDS = List.of("m1", "m2");

    @Mock private DeliverySignals signals;
    @Mock private AttentionService attentionService;
    @Mock private PhoneStateService phoneStates;
    @Mock private AgentStateService agentStates;
    @Mock private CompanionSchedule schedule;
    @Mock private AwarenessLadder ladder;
    @Mock private ReadMessagesAction readMessages;
    /** 默认 {@code isAggregating()=false} —— 于是这一整类测的是"没有回合合并"的老行为。 */
    @Mock private V11TurnPath turnPath;

    private V11DeliveryPath path;

    @BeforeEach
    void setUp() {
        path = new V11DeliveryPath(signals, attentionService, phoneStates, agentStates,
                schedule, ladder, readMessages, turnPath);
        when(signals.collect(any(), any(), any(), anyInt())).thenReturn(
                new DeliverySalience.Signals(1, DeliverySalience.NEVER_REPLIED_MINUTES, 100, 0.4, 0.5, 0.0));
        when(schedule.activityFor(any(), any())).thenReturn(CompanionSchedule.Activity.EVENING);
    }

    /** 让注意力算出一个指定的"注意到概率"。 */
    private void noticeProbability(double p) {
        when(attentionService.compute(any(), any(), any(), anyDouble()))
                .thenReturn(new AttentionService.Attention(0.25, 0.2, p, 0.7, 900L));
    }

    @Nested
    @DisplayName("shadow 安全: 判定不许产生任何效果")
    class ShadowSafety {

        @Test
        void assessmentTouchesNothingAtAll() {
            noticeProbability(0.9);
            path.assess(USER, AGENT, CONV, IDS.size(), LocalDateTime.now());

            // 不写阶梯、不读正文、不发通知 —— 这四条是 shadow 的全部承诺。
            // 任何一条被破坏, "并跑观察"就变成了"静默上线"。
            verifyNoInteractions(ladder);
            verifyNoInteractions(readMessages);
        }

        @Test
        void assessmentIsRepeatableBecauseItHasNoSideEffects() {
            noticeProbability(0.42);
            var a = path.assess(USER, AGENT, CONV, IDS.size(), LocalDateTime.now());
            var b = path.assess(USER, AGENT, CONV, IDS.size(), LocalDateTime.now());
            assertEquals(a.salience(), b.salience(), 1e-9);
            assertEquals(a.noticeProbability(), b.noticeProbability(), 1e-9);
            assertEquals(a.noticed(), b.noticed());
        }

        /**
         * Mock 看不见"悄悄写库", 所以这条专门钉住那个具体的坑:
         * {@code AgentStateService.getOrCreate()} 在行不存在时会 {@code repo.save()} 一条。
         * 用它, shadow 就会在真实流量里往 {@code agent_states} 插行 —— 而
         * {@code verifyNoInteractions(agentStates)} 是发现不了的, 因为交互<b>确实</b>发生了,
         * 只是发生在一个看起来像只读的方法里。
         */
        @Test
        void assessmentMustNotCreateStateRowsAsASideEffect() {
            noticeProbability(0.9);
            path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());

            verify(agentStates).get(AGENT);
            verify(agentStates, never()).getOrCreate(any());
            verify(agentStates, never()).save(any());
        }
    }

    @Nested
    @DisplayName("判定")
    class Assessment {

        @Test
        void aHighNoticeProbabilityMeansSheNoticed() {
            noticeProbability(0.9);
            assertTrue(path.assess(USER, AGENT, CONV, 1, LocalDateTime.now()).noticed());
        }

        @Test
        void aLowNoticeProbabilityMeansSheDidNot() {
            noticeProbability(0.05);
            assertFalse(path.assess(USER, AGENT, CONV, 1, LocalDateTime.now()).noticed());
        }

        @Test
        void aBrandNewRelationshipGetsTheAttentionFloor() {
            // 信号说关系很浅 → 保底 0.55, 高于阈值 0.3。与老链的 max(noticeProb, 0.55) 同源。
            when(signals.collect(any(), any(), any(), anyInt())).thenReturn(
                    new DeliverySalience.Signals(1, DeliverySalience.NEVER_REPLIED_MINUTES, 2, 0.0, 0.0, 0.0));
            noticeProbability(0.01);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            assertTrue(a.noticed(), "刚认识时她应当会认真看消息");
            assertTrue(a.noticeProbability() >= 0.55, "保底没有生效: " + a.noticeProbability());
        }

        @Test
        void whenAttentionCannotBeComputedSheIsAssumedToNotice() {
            // 失败方向必须是"她注意到了"。反过来的失败不报错, 只表现为"她突然不理人了"。
            when(phoneStates.current(any(), any())).thenThrow(new RuntimeException("库挂了"));
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            assertTrue(a.noticed());
            assertEquals(1.0, a.noticeProbability(), 1e-9);
        }

        @Test
        void theReasonExplainsWhichWayItWent() {
            noticeProbability(0.9);
            assertTrue(path.assess(USER, AGENT, CONV, 1, LocalDateTime.now()).reason().contains("注意到"));
            noticeProbability(0.05);
            assertFalse(path.assess(USER, AGENT, CONV, 1, LocalDateTime.now()).reason().contains("注意到了"));
        }
    }

    @Nested
    @DisplayName("执行: 阶梯的每一级都必须真的被踩过")
    class Delivery {

        private final List<List<MessageView>> handed = new ArrayList<>();

        @Test
        void notNoticingEndsTheDeliveryWithoutEverReadingText() {
            noticeProbability(0.02);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            assertFalse(a.noticed());

            boolean tookOver = path.deliver(USER, AGENT, CONV, IDS, a, LocalDateTime.now(), handed::add);

            assertTrue(tookOver, "没注意到也是一个结论, 不是'交给老链重来一遍'");
            // 消息确实到了、手机确实响了 —— 这两级与世界的事实一致
            verify(ladder).received(eq(AGENT), eq(CONV), eq(IDS));
            verify(ladder).notified(eq(AGENT), eq(CONV), eq(IDS));
            // 但她没有意识到, 所以 NOTICED 不该被记, 正文更不该被读出来
            verify(ladder, never()).noticed(any(), any(), any());
            verifyNoInteractions(readMessages);
            assertTrue(handed.isEmpty(), "没注意到就不该有任何正文进入认知链");
        }

        @Test
        void noticingLeadsToReadingAndToTheTextReachingCognition() {
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            MessageView m = MessageView.builder().id("m1").conversationId(CONV).content("在吗").build();
            when(readMessages.readDelivered(eq(AGENT), eq(CONV), eq(IDS)))
                    .thenReturn(new MessageBatch(List.of(m), MessageBatch.Transport.CHAT_WORLD_PORT, "平台直读 1 条"));

            boolean tookOver = path.deliver(USER, AGENT, CONV, IDS, a, LocalDateTime.now(), handed::add);

            assertTrue(tookOver);
            verify(ladder).received(eq(AGENT), eq(CONV), eq(IDS));
            verify(ladder).notified(eq(AGENT), eq(CONV), eq(IDS));
            verify(ladder).noticed(eq(AGENT), eq(CONV), eq(IDS));
            assertEquals(1, handed.size());
            assertEquals("在吗", handed.get(0).get(0).getContent());
        }

        @Test
        void noticingButReadingNothingKeepsTheTextOutOfCognition() {
            // 手机没配对/没信号/消息比扫描窗口更老 —— 她看了一眼, 什么都没看到。
            // 这时绝不能把"空"当成"没消息"塞进认知链, 那会让她回一句莫名其妙的话。
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            when(readMessages.readDelivered(any(), any(), any()))
                    .thenReturn(MessageBatch.unreachable("设备没配对"));

            boolean tookOver = path.deliver(USER, AGENT, CONV, IDS, a, LocalDateTime.now(), handed::add);

            assertTrue(tookOver);
            verify(ladder).noticed(eq(AGENT), eq(CONV), eq(IDS));
            assertTrue(handed.isEmpty(), "读不到正文时不该有任何东西进入认知链");
        }

        @Test
        void theLadderIsClimbedInOrder() {
            // 顺序是这份设计的实质: RECEIVED → NOTIFIED → NOTICED → READ。
            // 顺序错了, "她为什么没回"就又变成一个查不出来的问题。
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            when(readMessages.readDelivered(any(), any(), any()))
                    .thenReturn(new MessageBatch(List.of(), MessageBatch.Transport.CHAT_WORLD_PORT, ""));

            path.deliver(USER, AGENT, CONV, IDS, a, LocalDateTime.now(), handed::add);

            var order = inOrder(ladder);
            order.verify(ladder).received(eq(AGENT), eq(CONV), eq(IDS));
            order.verify(ladder).notified(eq(AGENT), eq(CONV), eq(IDS));
            order.verify(ladder).noticed(eq(AGENT), eq(CONV), eq(IDS));
        }

        @Test
        void theReadPolicyOnlyAsksForTheDeliveredMessages() {
            // 不是"把整个会话拉下来筛一遍" —— 那正是本轮要消灭的形状
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            when(readMessages.readDelivered(any(), any(), any()))
                    .thenReturn(new MessageBatch(List.of(), MessageBatch.Transport.CHAT_WORLD_PORT, ""));

            path.deliver(USER, AGENT, CONV, IDS, a, LocalDateTime.now(), handed::add);

            verify(readMessages).readDelivered(AGENT, CONV, IDS);
            verifyNoMoreInteractions(readMessages);
        }
    }

    @Nested
    @DisplayName("shadow 对比记录")
    class ShadowRecording {

        @Test
        void aDeliverySheWouldMissIsCountedAsADisagreement() {
            V11DeliveryShadow shadow = new V11DeliveryShadow();
            noticeProbability(0.02);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            path.recordShadow(shadow, AGENT, 1, a, -1, LocalDateTime.now());

            assertEquals(1L, shadow.stats().get("total"));
            assertEquals(1L, shadow.stats().get("v11WouldSkip"),
                    "老链处理了而新门认为她不会注意到 —— 这正是切流后会变的那部分");
        }

        @Test
        void agreementsAreCountedSeparatelyFromDisagreements() {
            V11DeliveryShadow shadow = new V11DeliveryShadow();
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            path.recordShadow(shadow, AGENT, 1, a, -1, LocalDateTime.now());

            assertEquals(1L, shadow.stats().get("agreeNoticed"));
            assertEquals(0L, shadow.stats().get("v11WouldSkip"));
        }

        @Test
        void shadowNeverClaimsToKnowWhetherTextWasReadable() {
            // readCount = -1(没尝试读)。这一项在 shadow 期间必须是 0 ——
            // 一个"猜出来"的读取成功率比没有这个数字更糟, 它会让人以为切流已经安全了。
            V11DeliveryShadow shadow = new V11DeliveryShadow();
            noticeProbability(0.9);
            var a = path.assess(USER, AGENT, CONV, 1, LocalDateTime.now());
            path.recordShadow(shadow, AGENT, 1, a, -1, LocalDateTime.now());

            assertEquals(0L, shadow.stats().get("v11NoText"));
        }
    }

    @Nested
    @DisplayName("开关")
    class Switch {

        /**
         * 断言的是 {@code @Value} 里的<b>出厂默认</b>, 而不是裸 Java 字段的默认值。
         * 差别是实质性的: 裸 {@code new} 出来两个都是 false, 而真正会部署上去的是
         * {@code enabled=false, shadow=true}。测前者等于什么都没测。
         */
        @Test
        void theShippedDefaultsAreOffAndShadowingOn() throws Exception {
            assertEquals("false", defaultValueOf("enabled"),
                    "出厂必须默认关: 这是本轮唯一会让 53 个 agent 行为真变的地方");
            assertEquals("true", defaultValueOf("shadow"),
                    "出厂必须默认 shadow: 关掉它就等于这次改动没有被观察过就上了线");
        }

        @Test
        void shadowAloneIsActiveButDoesNotTakeOverBehavior() throws Exception {
            V11RuntimeSwitch s = new V11RuntimeSwitch();
            set(s, "shadow", true);
            assertTrue(s.isActive(), "shadow 要参与判定, 否则没有对比数据");
            assertFalse(s.isEnabled(), "但 shadow 绝不允许改行为");
        }

        @Test
        void bothOffMeansTheOldChainRunsAlone() throws Exception {
            V11RuntimeSwitch s = new V11RuntimeSwitch();
            set(s, "enabled", false);
            set(s, "shadow", false);
            assertFalse(s.isActive());
        }

        private static String defaultValueOf(String field) throws Exception {
            var f = V11RuntimeSwitch.class.getDeclaredField(field);
            return f.getAnnotation(org.springframework.beans.factory.annotation.Value.class)
                    .value().split(":")[1].replace("}", "");
        }

        private static void set(Object target, String field, Object value) throws Exception {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        }
    }
}
