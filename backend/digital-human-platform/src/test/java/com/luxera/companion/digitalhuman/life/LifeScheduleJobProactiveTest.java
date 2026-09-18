package com.luxera.companion.digitalhuman.life;

import com.luxera.companion.mailbox.AgentMailbox;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 Phase 5 —— <b>世界那一半同步, 她那一半变信</b>。
 *
 * <p>与 {@link LifeScheduleJobTest}(V10 的集成测试, 管"活动真的收尾了没有")是两件事:
 * 那一份问的是世界, 这一份问的是那条<b>刻意的分裂</b>。活动结束这件事必须当场发生
 * (它是事实, 会被作息、可用性、注意力读到), 而"她注意到并可能做点什么"必须变成一封信
 * (她是活的, 不该被调度线程推着说话)。把两者一起改成投信, 症状是"她一睡觉活动就永远不结束";
 * 把两者一起留成同步调用, 症状是"调度线程开始跑认知"。两种都不报错, 只能靠用例钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LifeScheduleJobProactiveTest {

    private static final String AGENT = "agent-1";

    @Mock private LifeScheduleStore store;
    @Mock private LifeEventDispatcher dispatcher;
    @Mock private AgentMailbox mailbox;
    @Mock private V11ProactiveSwitch v11;
    @Mock private ProactiveActionRecorder recorder;

    private LifeScheduleJob job;

    @BeforeEach
    void setUp() {
        job = new LifeScheduleJob(store, dispatcher, mailbox, v11, recorder);
        when(v11.isActive()).thenReturn(true);
        when(mailbox.accept(any())).thenReturn(true);
    }

    private LifeScheduleRecord record(String scheduleId, Map<String, Object> payload) {
        LifeScheduleRecord r = new LifeScheduleRecord();
        r.setId("row-" + scheduleId);
        r.setScheduleId(scheduleId);
        r.setPersonId(AGENT);
        r.setEventType("ACTIVITY_END");
        r.setFireAt(LocalDateTime.now().minusMinutes(1));
        r.setPayload(payload);
        return r;
    }

    private void dueReturns(LifeScheduleRecord... records) {
        when(store.dueEvents(any(LocalDateTime.class))).thenReturn(List.of(records));
        when(dispatcher.dispatch(any())).thenReturn(true);
    }

    private EventEnvelope captured() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(mailbox).accept(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────── 投信 ───────────────────────────

    @Nested
    @DisplayName("她的那一半: 一封只带坐标的信")
    class TheLetter {

        @Test
        void aFinishedActivityTellsHerSoThroughTheMailbox() {
            dueReturns(record("activity-end-a1", Map.of("activityId", "a1", "title", "在图书馆")));

            job.fireDueEvents();

            EventEnvelope e = captured();
            assertEquals(AGENT, e.agentId());
            assertEquals(AgentEventType.LIFE_EVENT, e.type());
            assertEquals(EventSource.LIFE_SIMULATION, e.source());
            assertEquals("ACTIVITY_END", e.get("lifeEventType"));
            assertEquals("在图书馆", e.get("title"));
        }

        @Test
        void theLetterCarriesCoordinatesNotProse() {
            // payload 是活动/计划的 id 与标题, 过信封白名单时会当场拦住任何内容类键名。
            // 这一条是"生活的变化"与"她要说什么"被分开的证据: 前者是事实, 后者由她自己决定。
            dueReturns(record("activity-end-a1", Map.of("activityId", "a1", "title", "在图书馆")));

            job.fireDueEvents();

            assertTrue(captured().references().keySet().stream()
                            .noneMatch(k -> k.toLowerCase().contains("content")
                                    || k.toLowerCase().contains("text")
                                    || k.toLowerCase().contains("body")),
                    "生活事件里不该有正文: 该说什么是她的事");
        }

        @Test
        void theSameScheduleFiringTwiceIsOneLetter() {
            // 幂等键是 scheduleId —— 与闹钟那一路<b>正好相反</b>(见 AgentWakeupJobTest)。
            // 差别不是随手写的: 一个排程项对应一次生活变化, markDone 写库失败时下一轮会
            // 重走这里, 那时第二封必须被认出来, 否则她会经历两次"活动结束了"。
            dueReturns(record("activity-end-a1", null));
            job.fireDueEvents();
            dueReturns(record("activity-end-a1", null));
            job.fireDueEvents();

            ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
            verify(mailbox, times(2)).accept(captor.capture());

            // 第二封会被信箱按同一个 eventId 认成重复并丢掉 —— 这正是这里要的
            assertEquals(captor.getAllValues().get(0).eventId(),
                    captor.getAllValues().get(1).eventId(),
                    "同一个排程项重放必须还是同一封信");
        }
    }

    // ─────────────────────────── 世界那一半 ───────────────────────────

    @Nested
    @DisplayName("世界的那一半: 当场发生, 求人不如求己")
    class TheWorldHalf {

        @Test
        void theActivityEndsSynchronouslyAndNotByLetter() {
            dueReturns(record("activity-end-a1", null));

            job.fireDueEvents();

            // 这一条与上面那一条是同一个决策的两面: 如果派发也改成投信, 她睡觉时活动就
            // 永远不会结束 —— 而"活动进行中"会被作息、可用性、注意力读到
            verify(dispatcher).dispatch(any());
            verify(store).markDone("row-activity-end-a1");
        }

        @Test
        void theActivityStillEndsWhileTheSwitchIsOff() {
            when(v11.isActive()).thenReturn(false);
            dueReturns(record("activity-end-a1", null));

            job.fireDueEvents();

            verify(store).markDone("row-activity-end-a1");
            verifyNoInteractions(mailbox);   // 投出去也没人拆, 只会让信箱长草
            verify(recorder, never()).recordError();
        }

        @Test
        void aRejectedDispatchIsMarkedFailedAndTellsHerNothing() {
            when(store.dueEvents(any(LocalDateTime.class)))
                    .thenReturn(List.of(record("activity-end-a1", null)));
            when(dispatcher.dispatch(any())).thenReturn(false);

            job.fireDueEvents();

            verify(store).markFailed(eq("row-activity-end-a1"), anyString());
            verify(store, never()).markDone(anyString());
            // 活动没有真的结束, 就不该告诉她"结束了" —— 那会让她记住一件没发生过的事
            verifyNoInteractions(mailbox);
        }

        @Test
        void aThrowingDispatchDoesNotStopTheRest() {
            when(store.dueEvents(any(LocalDateTime.class))).thenReturn(
                    List.of(record("s-1", null), record("s-2", null)));
            when(dispatcher.dispatch(any()))
                    .thenThrow(new RuntimeException("库抖了一下"))
                    .thenReturn(true);

            assertDoesNotThrow(job::fireDueEvents);

            verify(store).markFailed(eq("row-s-1"), anyString());
            verify(store).markDone("row-s-2");
            verify(mailbox).accept(any());
        }
    }

    // ─────────────────────────── 边界 ───────────────────────────

    @Nested
    @DisplayName("投不出去的时候")
    class Failures {

        @Test
        void aBrokenLetterDoesNotUndoTheWorldChange() {
            // 生活的变化已经发生了, 只是她这次没被告知。把排程项退回重做会让活动结束两次;
            // 让它抛出去会让这一批后面的事件全部留在原地。
            dueReturns(record("activity-end-a1", null));
            when(mailbox.accept(any())).thenThrow(new RuntimeException("信箱挂了"));

            assertDoesNotThrow(job::fireDueEvents);

            verify(store).markDone("row-activity-end-a1");
            verify(recorder).recordError();
            verify(store, never()).markFailed(anyString(), anyString());
        }

        @Test
        void aRecordWithoutAnOwnerIsNotHerBusiness() {
            LifeScheduleRecord orphan = record("activity-end-a1", null);
            orphan.setPersonId(null);
            dueReturns(orphan);

            job.fireDueEvents();

            verifyNoInteractions(mailbox);
            verify(recorder, never()).recordError();   // 别人的事不是故障
        }

        @Test
        void anEmptyBatchDoesNothing() {
            when(store.dueEvents(any(LocalDateTime.class))).thenReturn(List.of());

            job.fireDueEvents();

            verifyNoInteractions(dispatcher);
            verifyNoInteractions(mailbox);
        }
    }

    // ─────────────────────────── 结构守卫 ───────────────────────────

    @Nested
    @DisplayName("它没有能力直接让她说话")
    class NoDirectLine {

        @Test
        void theOnlyWayOutOfThisClassIsTheMailbox() {
            // 这条断言读起来像洁癖, 但它是 §17.2 唯一能被机器检查的形状:
            // "Scheduler 不直接让 Agent 聊天" —— 只要这个类手上没有任何一个能发消息的端口,
            // 那句话就不可能被将来某次"顺手加个兜底"改掉。AgentMailbox 是唯一的出口。
            boolean sawMailbox = false;
            for (Constructor<?> c : LifeScheduleJob.class.getDeclaredConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    String name = p.getSimpleName();
                    assertFalse(name.contains("Chat") || name.contains("Delivery")
                                    || name.contains("Notifier") || name.contains("WebSocket"),
                            "生活调度不该持有任何能直接说话的端口, 却拿到了 " + name);
                    if (p.equals(AgentMailbox.class)) {
                        sawMailbox = true;
                    }
                }
            }
            assertTrue(sawMailbox, "出口只能是信箱");
        }
    }
}
