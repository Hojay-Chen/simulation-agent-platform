package com.luxera.companion.phone;

import com.luxera.companion.runtime.PersistentAgentRuntime;
import com.luxera.companion.world.AgentEventType;
import com.luxera.companion.world.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V11 §2.2.2 / §7.2 —— 意识阶梯。
 *
 * <p>重点在<b>幂等键</b>。同一级重复上报必须落成一封信, 不同级必须是不同的信 ——
 * 前者的反例是"她读了同一条消息两遍"(认知上不存在), 后者的反例是
 * "NOTIFIED 把 NOTICED 挤掉了"(一条消息永远走不到被读那一级)。
 * 这两种错误在数据库里都只是少几行, 不会报错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwarenessLadderTest {

    private static final String AGENT = "agent-1";
    private static final String OTHER_AGENT = "agent-2";
    private static final String CONV = "conv-1";
    private static final List<String> IDS = List.of("m1", "m2");

    @Mock private PersistentAgentRuntime runtime;

    private AwarenessLadder ladder;

    @BeforeEach
    void setUp() {
        ladder = new AwarenessLadder(runtime);
        when(runtime.accept(any())).thenReturn(true);
    }

    private List<EventEnvelope> captured() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(runtime, atLeastOnce()).accept(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("每一级都是一封独立的信")
    class Steps {

        @Test
        void everyStepWritesItsOwnEventType() {
            ladder.received(AGENT, CONV, IDS);
            ladder.notified(AGENT, CONV, IDS);
            ladder.noticed(AGENT, CONV, IDS);
            ladder.read(AGENT, CONV, IDS, MessageBatch.Transport.SIMULATOR);

            Set<AgentEventType> types = captured().stream()
                    .map(EventEnvelope::type).collect(Collectors.toSet());
            assertEquals(Set.of(
                    AgentEventType.USER_MESSAGE_RECEIVED,
                    AgentEventType.USER_MESSAGE_NOTIFIED,
                    AgentEventType.USER_MESSAGE_NOTICED,
                    AgentEventType.USER_MESSAGE_READ), types);
        }

        @Test
        void theSameStepForTwoMessagesProducesTwoEvents() {
            ladder.noticed(AGENT, CONV, IDS);
            assertEquals(2, captured().size(), "两条消息是两个事实, 不是一条");
        }

        @Test
        void everyStepOfTheLadderIsMarkedAsPerception() {
            // AgentEventType 把"阶梯"与"世界里的事实"分成两类。混在一起排序
            // 正是旧链最根子的错误, 所以这条性质值得钉住。
            ladder.received(AGENT, CONV, IDS);
            ladder.deferred(AGENT, CONV, IDS, LocalDateTime.now().plusHours(2), "在忙");
            for (EventEnvelope e : captured()) {
                assertTrue(e.onPerceptionLadder(), e.type() + " 应当属于意识阶梯");
            }
        }
    }

    @Nested
    @DisplayName("幂等: 同一级只进信箱一次")
    class Idempotence {

        @Test
        void reportingTheSameStepTwiceProducesTheSameEventId() {
            ladder.noticed(AGENT, CONV, List.of("m1"));
            String first = captured().get(0).eventId();

            reset(runtime);
            when(runtime.accept(any())).thenReturn(true);
            ladder.noticed(AGENT, CONV, List.of("m1"));
            String second = captured().get(0).eventId();

            assertEquals(first, second,
                    "消息重投/重放会让同一级被上报两次, 而'她读了同一条消息两遍'在认知上不存在");
        }

        @Test
        void differentStepsOfTheSameMessageGetDifferentEventIds() {
            // 若各级共用一个 id, NOTIFIED 会把 NOTICED 挤掉 —— 一条消息永远走不到"被读"
            ladder.received(AGENT, CONV, List.of("m1"));
            ladder.notified(AGENT, CONV, List.of("m1"));
            ladder.noticed(AGENT, CONV, List.of("m1"));

            List<String> ids = captured().stream().map(EventEnvelope::eventId).toList();
            assertEquals(3, Set.copyOf(ids).size(), "三级台阶必须有三封不同的信: " + ids);
        }

        @Test
        void theEventIdIsScopedToTheAgent() {
            // Phase 1 修掉的那个静默丢信的坑: eventId 幂等是<b>全局</b>的
            // (agent_inbox.event_id 唯一约束), 所以两个 agent 撞上同一个来源键时,
            // 后一个的信会被当成"已收过"直接丢掉 —— 没有异常, 没有日志。
            ladder.noticed(AGENT, CONV, List.of("m1"));
            String a = captured().get(0).eventId();

            reset(runtime);
            when(runtime.accept(any())).thenReturn(true);
            ladder.noticed(OTHER_AGENT, CONV, List.of("m1"));
            String b = captured().get(0).eventId();

            assertNotEquals(a, b, "两个 agent 的同一条消息不能共用一个幂等键");
        }
    }

    @Nested
    @DisplayName("信封装不下正文")
    class NoTextInTheEnvelope {

        @Test
        void theReadEventCarriesTheTransportButNoContent() {
            ladder.read(AGENT, CONV, List.of("m1"), MessageBatch.Transport.CHAT_WORLD_PORT);
            EventEnvelope e = captured().get(0);

            assertEquals("chat-world-port", e.references().get("transport"),
                    "设备通道到底有没有在工作, 必须是一个可查询的事实, 而不是只能翻日志");
            assertEquals("m1", e.references().get("messageId"));
            assertEquals(CONV, e.references().get("conversationId"));
        }

        @Test
        void aNullTransportDoesNotBlowUpTheReadEvent() {
            ladder.read(AGENT, CONV, List.of("m1"), null);
            assertEquals("none", captured().get(0).references().get("transport"));
        }

        @Test
        void theDeferredEventRecordsWhenAndWhy() {
            LocalDateTime until = LocalDateTime.of(2026, 9, 18, 23, 0);
            ladder.deferred(AGENT, CONV, List.of("m1"), until, "在忙");

            EventEnvelope e = captured().get(0);
            assertEquals(AgentEventType.USER_MESSAGE_DEFERRED, e.type());
            assertEquals(until.toString(), e.references().get("until"));
            assertEquals("在忙", e.references().get("reason"));
        }

        @Test
        void deferringWithoutAReasonOrUntilIsStillValid() {
            // "没说什么时候回"和"没说为什么"都是真实情形, 不该变成两个 null 字段
            ladder.deferred(AGENT, CONV, List.of("m1"), null, null);
            EventEnvelope e = captured().get(0);
            assertFalse(e.references().containsKey("until"));
            assertFalse(e.references().containsKey("reason"));
        }
    }

    @Nested
    @DisplayName("上报失败不能让主链崩掉")
    class FailuresAreSwallowed {

        @Test
        void anExceptionFromTheRuntimeDoesNotPropagate() {
            // 她已经读到消息了, 那是既成事实。记不上只是"这一刻没留下痕迹",
            // 不是"这件事没发生" —— 更不能因此把一次送达整个炸掉。
            when(runtime.accept(any())).thenThrow(new RuntimeException("信箱满了"));
            assertDoesNotThrow(() -> ladder.noticed(AGENT, CONV, IDS));
        }

        @Test
        void theReturnValueCountsOnlyWhatWasActuallyAccepted() {
            when(runtime.accept(any())).thenReturn(true, false);
            assertEquals(1, ladder.noticed(AGENT, CONV, List.of("m1", "m2")));
        }

        @Test
        void nothingToReportMeansNoInteractionAtAll() {
            assertEquals(0, ladder.noticed(AGENT, CONV, List.of()));
            assertEquals(0, ladder.noticed(AGENT, CONV, null));
            assertEquals(0, ladder.noticed(null, CONV, IDS));
            assertEquals(0, ladder.noticed("  ", CONV, IDS));
            verifyNoInteractions(runtime);
        }

        @Test
        void blankMessageIdsInsideTheBatchAreSkipped() {
            assertEquals(1, ladder.noticed(AGENT, CONV, java.util.Arrays.asList("m1", null, "  ")));
        }
    }

    @Nested
    @DisplayName("批次工具")
    class BatchHelpers {

        @Test
        void idsOfExtractsTheCoordinatesInOrder() {
            MessageBatch batch = new MessageBatch(List.of(
                    com.luxera.companion.contracts.api.MessageView.builder().id("a").build(),
                    com.luxera.companion.contracts.api.MessageView.builder().id("b").build()),
                    MessageBatch.Transport.SIMULATOR, "2 条");
            assertEquals(List.of("a", "b"), AwarenessLadder.idsOf(batch));
        }

        @Test
        void idsOfANullOrEmptyBatchIsEmptyNotAnException() {
            assertEquals(List.of(), AwarenessLadder.idsOf(null));
            assertEquals(List.of(), AwarenessLadder.idsOf(MessageBatch.unreachable("没信号")));
        }
    }

    @Test
    @DisplayName("Transports 的 wire 名是落库的字符串, 不能随便改")
    void transportWireNamesArePersistedValues() {
        // 这些字符串会进 agent_inbox.references, 也就进了"设备通道到底有没有在工作"
        // 这个查询的 WHERE 子句。改名等于让历史数据读不出来。
        assertEquals("simulator", MessageBatch.Transport.SIMULATOR.wire());
        assertEquals("chat-world-port", MessageBatch.Transport.CHAT_WORLD_PORT.wire());
        assertEquals("none", MessageBatch.Transport.NONE.wire());
    }

    @Test
    @DisplayName("unreachable 批次一定是空的, 且带着原因")
    void anUnreachableBatchIsAlwaysEmptyWithAReason() {
        MessageBatch b = MessageBatch.unreachable("设备没配对");
        assertTrue(b.messages().isEmpty());
        assertTrue(b.isEmpty());
        assertNotNull(b.note());
    }
}
