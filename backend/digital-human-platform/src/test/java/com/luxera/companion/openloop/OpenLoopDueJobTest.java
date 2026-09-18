package com.luxera.companion.openloop;

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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 Phase 5 —— <b>悬着的事到点了</b>。
 *
 * <p>最要紧的一条是 {@link Delivery#reschedulingTheSameLoopProducesANewLetter}:
 * 幂等键里如果只有悬案 id, 一个"面试结果"一辈子只会响一次 —— 第一封信拆掉之后,
 * 改期到点会被信箱当成重复而静默丢掉。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenLoopDueJobTest {

    private static final String AGENT = "agent-1";

    @Mock private OpenLoopRepository repo;
    @Mock private AgentMailbox mailbox;
    @Mock private V11ProactiveSwitch v11;
    @Mock private ProactiveActionRecorder recorder;

    private OpenLoopDueJob job;

    @BeforeEach
    void setUp() {
        job = new OpenLoopDueJob(repo, mailbox, v11, recorder);
        ReflectionTestUtils.setField(job, "lookbackDays", 7);
        when(v11.isActive()).thenReturn(true);
        when(mailbox.accept(any())).thenReturn(true);
    }

    private OpenLoop loop(String id, LocalDateTime dueAt) {
        OpenLoop l = new OpenLoop();
        l.setId(id);
        l.setCompanionId(AGENT);
        l.setTitle("面试结果");
        l.setOwnerType("USER_EVENT");
        l.setImportance(0.8);
        l.setEmotionalWeight(0.7);
        l.setExpectedResolutionAt(dueAt);
        l.setStatus("WAITING");
        return l;
    }

    private void dueReturns(OpenLoop... loops) {
        when(repo.findByStatusInAndExpectedResolutionAtBetweenOrderByExpectedResolutionAtAsc(
                anyList(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(loops));
    }

    private EventEnvelope captured() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(mailbox).accept(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("投递")
    class Delivery {

        @Test
        void aLoopThatJustCameDueBecomesALetter() {
            dueReturns(loop("l-1", LocalDateTime.now().minusMinutes(2)));

            job.fireDue();

            EventEnvelope e = captured();
            assertEquals(AGENT, e.agentId());
            assertEquals(AgentEventType.OPEN_LOOP_DUE, e.type());
            // 来源是 INTENTION(悬而未决的事与念头), 不是 SCHEDULE —— 它不是时钟到的,
            // 是"那件事该有结果了"
            assertEquals(EventSource.INTENTION, e.source());
            assertEquals("l-1", e.get("openLoopId"));
            assertEquals("面试结果", e.get("title"));
        }

        @Test
        void reschedulingTheSameLoopProducesANewLetter() {
            // 键是 "openloop:<id>@<到点时刻>": 只带 id 的话, 一个悬案一辈子只会响一次
            dueReturns(loop("l-1", LocalDateTime.now().minusMinutes(10)));
            job.fireDue();
            dueReturns(loop("l-1", LocalDateTime.now().minusMinutes(1)));
            job.fireDue();

            ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
            verify(mailbox, times(2)).accept(captor.capture());

            assertNotEquals(captor.getAllValues().get(0).eventId(),
                    captor.getAllValues().get(1).eventId(),
                    "同一个悬案改期到点必须是新的一封信, 否则第二封信会被当成重复丢掉");
        }

        @Test
        void theLookbackWindowIsBounded() {
            dueReturns();

            job.fireDue();

            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repo).findByStatusInAndExpectedResolutionAtBetweenOrderByExpectedResolutionAtAsc(
                    anyList(), from.capture(), any(), any(Pageable.class));
            // 没有下界的话, 一个三个月前就该有结果的悬案会在每次扫描时都被判定为"到点了",
            // 于是她每隔几分钟就想起来问一次同一件事
            assertTrue(from.getValue().isBefore(LocalDateTime.now().minusDays(6)));
            assertTrue(from.getValue().isAfter(LocalDateTime.now().minusDays(8)));
        }

        @Test
        void aLetterAlreadyInTheMailboxIsNotAnError() {
            dueReturns(loop("l-1", LocalDateTime.now().minusMinutes(1)));
            when(mailbox.accept(any())).thenReturn(false);

            job.fireDue();

            verify(recorder, never()).recordError();
        }

        @Test
        void aBrokenLetterIsCountedAndDoesNotStopTheRest() {
            dueReturns(loop("l-1", LocalDateTime.now().minusMinutes(2)),
                    loop("l-2", LocalDateTime.now().minusMinutes(1)));
            when(mailbox.accept(any())).thenThrow(new RuntimeException("库挂了")).thenReturn(true);

            assertDoesNotThrow(job::fireDue);

            verify(recorder).recordError();
            verify(mailbox, times(2)).accept(any());
        }
    }

    @Nested
    @DisplayName("不该动的时候")
    class Off {

        @Test
        void nothingIsQueriedWhileTheSwitchIsOff() {
            when(v11.isActive()).thenReturn(false);

            job.fireDue();

            verifyNoInteractions(repo);
            verifyNoInteractions(mailbox);
        }

        @Test
        void aLoopWithoutAnOwnerOrATimeIsSkipped() {
            OpenLoop orphan = loop("l-1", LocalDateTime.now().minusMinutes(1));
            orphan.setCompanionId(null);
            dueReturns(orphan);

            job.fireDue();

            // 一条悬案如果不知道属于谁, 它就不是她的事
            verifyNoInteractions(mailbox);
        }
    }
}
