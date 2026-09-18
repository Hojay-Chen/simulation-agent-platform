package com.luxera.companion.wakeup;

import com.luxera.companion.mailbox.AgentMailbox;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §17.2 —— <b>Scheduler 只投信, 不跑认知</b>。
 *
 * <p>本文件里唯一有点讲究的一条是 {@link Delivery#theSameAlarmFiringTwiceProducesTwoDifferentLetters}:
 * 幂等键用错地方的后果是"她第一次没回, 之后那个闹钟就再也没响过" —— 没有异常、
 * 没有日志, 收信人一辈子不知道有这件事。这类 bug 只能在用例里钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentWakeupJobTest {

    private static final String AGENT = "agent-1";

    @Mock private AgentWakeupService wakeups;
    @Mock private AgentMailbox mailbox;
    @Mock private V11ProactiveSwitch v11;

    private AgentWakeupJob job;

    @BeforeEach
    void setUp() {
        job = new AgentWakeupJob(wakeups, mailbox, v11);
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        when(v11.isActive()).thenReturn(true);
        when(mailbox.accept(any())).thenReturn(true);
    }

    private AgentWakeup alarm(String id, LocalDateTime at) {
        return alarm(AGENT, id, at);
    }

    private AgentWakeup alarm(String agentId, String id, LocalDateTime at) {
        AgentWakeup w = new AgentWakeup();
        w.setId(id);
        w.setAgentId(agentId);
        w.setEventType(AgentEventType.SCHEDULED_WAKEUP);
        w.setSourceKey("life");
        w.setWakeAt(at);
        w.setReason("生命节律");
        w.setStatus(AgentWakeup.S_PENDING);
        return w;
    }

    private EventEnvelope captured() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(mailbox, atLeastOnce()).accept(captor.capture());
        return captor.getValue();
    }

    // ─────────────────────────── 投递 ───────────────────────────

    @Nested
    @DisplayName("投一封信")
    class Delivery {

        @Test
        void aDueAlarmBecomesALetterInHerMailbox() {
            LocalDateTime at = LocalDateTime.now().minusSeconds(1);
            when(wakeups.due(any(), anyInt())).thenReturn(List.of(alarm("w-1", at)));

            job.fireDue();

            EventEnvelope e = captured();
            assertEquals(AGENT, e.agentId());
            assertEquals(AgentEventType.SCHEDULED_WAKEUP, e.type());
            // 来源是 SCHEDULE(时钟), 不是 SELF —— 它是"时间到了", 不是"她想起来了"
            assertEquals(EventSource.SCHEDULE, e.source());
            assertEquals("w-1", e.get("wakeupId"));
            assertEquals("life", e.get("source"));
        }

        @Test
        void theEnvelopeCarriesNoMessageBody() {
            // 信封的构造函数自己会拦内容类键名, 这里是"没有人在 refs 里塞正文"的正面证据
            when(wakeups.due(any(), anyInt()))
                    .thenReturn(List.of(alarm("w-1", LocalDateTime.now().minusSeconds(1))));

            job.fireDue();

            assertTrue(captured().references().keySet().stream()
                            .noneMatch(k -> k.toLowerCase().contains("content")),
                    "闹钟的信封里只有坐标: 它连正文都没有, 也不该有");
        }

        @Test
        void firingMarksTheRowAsHistory() {
            when(wakeups.due(any(), anyInt()))
                    .thenReturn(List.of(alarm("w-1", LocalDateTime.now().minusSeconds(1))));

            job.fireDue();

            verify(wakeups).markFired(any(AgentWakeup.class), any(LocalDateTime.class));
        }

        @Test
        void aLetterAlreadyInTheMailboxStillFinishesTheAlarm() {
            // accept 返回 false 只意味着"这封信已经在信箱里"。两种情况下这个闹钟都
            // 已经完成使命 —— 不标记的话, 它会让这个闹钟每 10 秒重试一次, 直到永远
            when(wakeups.due(any(), anyInt()))
                    .thenReturn(List.of(alarm("w-1", LocalDateTime.now().minusSeconds(1))));
            when(mailbox.accept(any())).thenReturn(false);

            job.fireDue();

            verify(wakeups).markFired(any(AgentWakeup.class), any(LocalDateTime.class));
        }

        @Test
        void nothingIsSentWhileTheSwitchIsOff() {
            when(v11.isActive()).thenReturn(false);

            job.fireDue();

            // 刻意不是"发了但没人收": 那样信会攒在信箱里, 切流那天一起被拆开,
            // 表现为她一口气发了几十条主动消息
            verifyNoInteractions(mailbox);
            verify(wakeups, never()).due(any(), anyInt());
        }

        @Test
        void oneBadAlarmDoesNotStopTheRest() {
            // 一个 try 包住整个循环的话, 投不出去的那一行会把<b>它后面那一批</b>一起留在原地 ——
            // 而下一轮又从同一个毒药行开始, 队列头部永远推不动。
            when(wakeups.due(any(), anyInt())).thenReturn(List.of(
                    alarm("w-1", LocalDateTime.now().minusSeconds(2)),
                    alarm("w-2", LocalDateTime.now().minusSeconds(1))));
            when(mailbox.accept(any()))
                    .thenThrow(new RuntimeException("库抖了一下"))
                    .thenReturn(true);

            assertDoesNotThrow(job::fireDue);

            verify(mailbox, times(2)).accept(any());
            verify(wakeups, times(1)).markFired(any(AgentWakeup.class), any(LocalDateTime.class));
        }

        @Test
        void theSameAlarmFiringTwiceProducesTwoDifferentLetters() {
            // 这一条是本文件的重点。幂等键是 "行的 id + 这一次响的时刻":
            //   · 只用 sourceKey → 她改了主意把闹钟推后时键不变, 第二封被当成重复丢掉
            //   · 只用行的 id   → 复活同一行之后, 第二轮以后的响全部静默丢失
            // 两种写法都会表现为"她第一次没回, 之后那个闹钟就再也没响过"。
            LocalDateTime first = LocalDateTime.now().minusMinutes(5);
            LocalDateTime second = LocalDateTime.now().minusMinutes(1);
            when(wakeups.due(any(), anyInt()))
                    .thenReturn(List.of(alarm("w-1", first)))
                    .thenReturn(List.of(alarm("w-1", second)));

            job.fireDue();
            String firstId = captured().eventId();
            job.fireDue();

            ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
            verify(mailbox, times(2)).accept(captor.capture());
            String secondId = captor.getAllValues().get(1).eventId();

            assertEquals(firstId, captor.getAllValues().get(0).eventId());
            assertNotEquals(firstId, secondId,
                    "同一个闹钟的两次响必须是两封信 —— 否则第二次响会被信箱当成重复而静默丢掉");
        }

        @Test
        void twoAgentsNeverShareALetterId() {
            // 幂等是<b>全局</b>的(event_id 上有唯一约束, 信箱也用全局判断做短路)。
            // 两个 agent 拿到同一个来源键是必然会发生的 —— 一次广播式的定时唤醒、
            // 或者"今天九点的闹钟" —— 少了 agentId 那个前缀, 后一个 agent 的信会被
            // 当成"已经收过了"直接丢掉: 没有异常, 没有日志, 收信人一辈子不知道有这件事。
            LocalDateTime at = LocalDateTime.now().minusSeconds(1);
            when(wakeups.due(any(), anyInt()))
                    .thenReturn(List.of(alarm("agent-a", "w-1", at), alarm("agent-b", "w-1", at)));
            ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);

            job.fireDue();

            verify(mailbox, times(2)).accept(captor.capture());
            assertNotEquals(captor.getAllValues().get(0).eventId(),
                    captor.getAllValues().get(1).eventId(),
                    "同一个来源键在不同 agent 上必须是两封信");
        }
    }

    // ─────────────────────────── 清理 ───────────────────────────

    @Nested
    @DisplayName("清历史")
    class Purge {

        @Test
        void purgingUsesTheConfiguredRetention() {
            when(wakeups.purgeFinishedBefore(any())).thenReturn(3);

            job.purgeFinished();

            ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(wakeups).purgeFinishedBefore(cutoff.capture());
            assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(6)),
                    "7 天保留期, 切点必须真的在一周之前");
        }

        @Test
        void aBrokenPurgeNeverEscapes() {
            when(wakeups.purgeFinishedBefore(any())).thenThrow(new RuntimeException("库挂了"));
            assertDoesNotThrow(job::purgeFinished);
        }
    }
}
