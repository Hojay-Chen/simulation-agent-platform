package com.luxera.companion.proactive;

import com.luxera.companion.behavior.BehaviorAction;
import com.luxera.companion.behavior.BehaviorCandidate;
import com.luxera.companion.behavior.BehaviorEngine;
import com.luxera.companion.behavior.BehaviorOutcome;
import com.luxera.companion.runtime.v11.ProactiveActionRecorder;
import com.luxera.companion.runtime.v11.V11ProactiveSwitch;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import com.luxera.companion.world.EventSource;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §18.2 —— <b>她醒来之后做了什么</b>。
 *
 * <p>本文件的重点不是"能不能选出行为", 而是三条边界:
 * <ol>
 *   <li><b>shadow 只算不做</b> —— 用的是 {@code select} 而不是 {@code evaluate},
 *       因为后者边选边做, 调一次就等于真的让她发了消息;</li>
 *   <li><b>关掉时是"拆掉然后丢掉"</b> —— 信要变成 CONSUMED, 不能攒着, 否则切流那天
 *       积压的事件会一起被拆开, 表现为她一口气发了几十条;</li>
 *   <li><b>不碰阶梯事件</b> —— 那条链今天由 V11DeliveryPath 直连认知, 再注册一个消费者
 *       会让同一条消息被处理两次, 而"她一次回了你两条"这种故障不报错。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProactiveActionConsumerTest {

    private static final String AGENT = "agent-1";

    @Mock private BehaviorEngine behaviorEngine;
    @Mock private V11ProactiveSwitch v11;
    @Mock private ProactiveActionRecorder recorder;

    private ProactiveActionConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProactiveActionConsumer(behaviorEngine, v11, recorder);
        when(v11.isActive()).thenReturn(true);
    }

    private EventEnvelope letter(AgentEventType type) {
        return new EventEnvelope("env-1", AGENT, type, EventSource.SCHEDULE,
                null, LocalDateTime.now(), Map.of("wakeupId", "w-1"));
    }

    // ─────────────────────────── 它认哪几种信 ───────────────────────────

    @Nested
    @DisplayName("支持的事件类型")
    class Supported {

        @Test
        void itTakesTheFiveThatWakeHerFromInside() {
            assertTrue(consumer.supports(AgentEventType.SCHEDULED_WAKEUP));
            assertTrue(consumer.supports(AgentEventType.LIFE_EVENT));
            assertTrue(consumer.supports(AgentEventType.OPEN_LOOP_DUE));
            assertTrue(consumer.supports(AgentEventType.INTENTION_ACTIVATED));
            assertTrue(consumer.supports(AgentEventType.RELATIONSHIP_CHANGED));
        }

        @Test
        void itDeliberatelyRefusesThePerceptionLadder() {
            // 这条断言是 Phase 2/4 那条"阶梯事件零消费者"记录的守卫。哪天有人顺手把
            // USER_MESSAGE_* 加进支持列表, 同一条消息就会被处理两次 —— 而它的症状
            // 只是"她偶尔回你两条", 谁都不会把它当成故障报上来。
            for (AgentEventType t : AgentEventType.values()) {
                if (t.isPerceptionLadder()) {
                    assertFalse(consumer.supports(t), t + " 不属于本消费者");
                }
            }
        }

        @Test
        void aNullTypeIsRefusedInsteadOfBlowingUp() {
            assertFalse(consumer.supports(null));
        }
    }

    // ─────────────────────────── 三个档位 ───────────────────────────

    @Nested
    @DisplayName("开关关掉: 拆掉然后丢掉")
    class Off {

        @Test
        void theLetterIsOpenedAndDiscardedNotLeftUnread() {
            when(v11.isActive()).thenReturn(false);

            consumer.consume(letter(AgentEventType.SCHEDULED_WAKEUP));

            // 返回而不是抛异常 —— 信箱据此把信标记成 CONSUMED。留着不拆的话,
            // 切流那天所有积压的事件会一起被拆开。
            verify(recorder).record(eq("SCHEDULED_WAKEUP"), isNull(), eq("proactive_off"), eq(0.0));
            verifyNoInteractions(behaviorEngine);
        }

        @Test
        void aBrokenEnvelopeIsIgnoredRatherThanFailing() {
            assertDoesNotThrow(() -> consumer.consume(null));
            verifyNoInteractions(behaviorEngine);
        }
    }

    @Nested
    @DisplayName("shadow: 算出她会做什么, 但不动手")
    class Shadow {

        @BeforeEach
        void shadowOnly() {
            when(v11.isEffective()).thenReturn(false);
        }

        @Test
        void itAsksForTheCandidateAndNeverExecutesIt() {
            BehaviorCandidate planned = BehaviorCandidate.of(
                    BehaviorAction.SEND_PROACTIVE_MESSAGE, "想问问面试的事", 0.72);
            when(behaviorEngine.select(eq(AGENT), any(LocalDateTime.class))).thenReturn(planned);

            consumer.consume(letter(AgentEventType.OPEN_LOOP_DUE));

            verify(behaviorEngine).select(eq(AGENT), any(LocalDateTime.class));
            // evaluate 边选边做: 调一次就等于真的让她发了消息。shadow 调它就是上线。
            verify(behaviorEngine, never()).evaluateById(anyString(), any(), anyString());
            verify(recorder).record(eq("OPEN_LOOP_DUE"),
                    eq(BehaviorAction.SEND_PROACTIVE_MESSAGE), eq("想问问面试的事"), eq(0.72));
        }

        @Test
        void noCandidateIsCountedAsAnErrorNotAsSilence() {
            // "算出来不主动"与"根本没算出来"在账面上必须能分开, 否则一个坏掉的 shadow
            // 会表现为"她本来一次都不会主动说话", 而那读起来像是可以切流了
            when(behaviorEngine.select(eq(AGENT), any(LocalDateTime.class))).thenReturn(null);

            consumer.consume(letter(AgentEventType.LIFE_EVENT));

            verify(recorder).recordError();
            verify(recorder, never()).record(anyString(), any(), anyString(), anyDouble());
        }

        @Test
        void aThrowingEngineIsRecordedAndPropagated() {
            // 抛出去是必须的: 信箱会把它标成 FAILED 并重试。吞掉的话, 一次真实故障
            // 会伪装成"她这次不想说话"。
            when(behaviorEngine.select(eq(AGENT), any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("库挂了"));

            assertThrows(RuntimeException.class,
                    () -> consumer.consume(letter(AgentEventType.LIFE_EVENT)));
            verify(recorder).recordError();
        }
    }

    @Nested
    @DisplayName("enabled: 让它真的发生")
    class Enabled {

        @BeforeEach
        void effective() {
            when(v11.isEffective()).thenReturn(true);
        }

        @Test
        void theEngineRunsAndTheOutcomeIsRecorded() {
            when(behaviorEngine.evaluateById(eq(AGENT), any(LocalDateTime.class), anyString()))
                    .thenReturn(new BehaviorOutcome(BehaviorAction.CONTACT_OTHER_PERSON,
                            "和朋友们聚聚", 0.4, "V11_LIFE_EVENT", LocalDateTime.now()));

            consumer.consume(letter(AgentEventType.LIFE_EVENT));

            verify(behaviorEngine).evaluateById(eq(AGENT), any(LocalDateTime.class), anyString());
            // 只选不做的那一半没有被调用 —— 两个档位走的必须是同一条选择逻辑,
            // 否则 shadow 记下的东西与切流后真的会发生的事情不是同一件事
            verify(behaviorEngine, never()).select(anyString(), any(LocalDateTime.class));
            verify(recorder).record(eq("LIFE_EVENT"),
                    eq(BehaviorAction.CONTACT_OTHER_PERSON), eq("和朋友们聚聚"), eq(0.4));
        }

        @Test
        void theReasonStringCarriesTheEventThatWokeHer() {
            when(behaviorEngine.evaluateById(anyString(), any(), anyString()))
                    .thenReturn(new BehaviorOutcome(BehaviorAction.DO_NOTHING, "发会呆", 0.3,
                            "V11_INTENTION_ACTIVATED", LocalDateTime.now()));

            consumer.consume(letter(AgentEventType.INTENTION_ACTIVATED));

            // 理由串是"这次行为是被谁唤醒的"在日志里唯一的线索
            verify(behaviorEngine).evaluateById(eq(AGENT), any(LocalDateTime.class),
                    eq("V11_INTENTION_ACTIVATED"));
        }
    }
}
