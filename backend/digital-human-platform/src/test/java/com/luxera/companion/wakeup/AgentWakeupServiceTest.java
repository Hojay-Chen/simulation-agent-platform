package com.luxera.companion.wakeup;

import com.luxera.companion.world.AgentEventType;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V11 §18.1 —— <b>她下一次什么时候醒</b>。
 *
 * <p>这个类没有判断, 只有一个 upsert 与几条查询 —— 所以它值得被穷举测完:
 * 一旦"排期"这一层开始出错, 症状不是异常, 而是<b>她再也没提过某件事</b>,
 * 那是一个从日志上看不出来的故障形态。
 *
 * <p>所有用例都用 {@link LocalDateTime#now()} 作基准而不是写死时刻:
 * {@code schedule} 内部会拿真实时钟做"过去就夹到现在"的判断, 写死的时间戳
 * 会让用例的结果取决于跑测试的钟点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentWakeupServiceTest {

    private static final String AGENT = "agent-1";
    private static final String KEY = "decision:conv-1";

    @Mock private AgentWakeupRepository repo;

    private AgentWakeupService service;

    @BeforeEach
    void setUp() {
        service = new AgentWakeupService(repo);
        when(repo.findByAgentIdAndEventTypeAndSourceKey(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
    }

    private AgentWakeup existing(LocalDateTime wakeAt, String status) {
        AgentWakeup w = new AgentWakeup();
        w.setId("w-1");
        w.setAgentId(AGENT);
        w.setEventType(AgentEventType.SCHEDULED_WAKEUP);
        w.setSourceKey(KEY);
        w.setWakeAt(wakeAt);
        w.setStatus(status);
        return w;
    }

    // ─────────────────────────── 排期 ───────────────────────────

    @Nested
    @DisplayName("排一个闹钟")
    class Schedule {

        @Test
        void aNewAlarmIsPersistedAsPending() {
            LocalDateTime at = LocalDateTime.now().plusMinutes(30);

            assertTrue(service.schedule(AGENT, at, AgentEventType.SCHEDULED_WAKEUP, KEY, "忙完再说"));

            ArgumentCaptor<AgentWakeup> saved = ArgumentCaptor.forClass(AgentWakeup.class);
            verify(repo).save(saved.capture());
            assertEquals(AGENT, saved.getValue().getAgentId());
            assertEquals(at, saved.getValue().getWakeAt());
            assertEquals(AgentWakeup.S_PENDING, saved.getValue().getStatus());
            assertEquals("忙完再说", saved.getValue().getReason());
        }

        @Test
        void aTimeInThePastIsClampedNotRejected() {
            // 这是本类最重要的一条: 拒绝一个过去的时刻 = 那个闹钟永远不响, 而
            // "她早该醒了"恰恰是最常见的情况(进程停了、她当时被暂停)。夹到现在
            // 等于"下一轮就醒", 是唯一不会静默丢事的处理。
            LocalDateTime past = LocalDateTime.now().minusHours(3);

            assertTrue(service.schedule(AGENT, past, AgentEventType.SCHEDULED_WAKEUP, KEY, "该回了"));

            ArgumentCaptor<AgentWakeup> saved = ArgumentCaptor.forClass(AgentWakeup.class);
            verify(repo).save(saved.capture());
            assertTrue(saved.getValue().getWakeAt().isAfter(past),
                    "过去的时刻必须被夹到现在, 而不是原样存进去");
            assertFalse(saved.getValue().getWakeAt().isBefore(LocalDateTime.now().minusSeconds(5)));
        }

        @Test
        void anAlarmWithoutATimeIsNotStoredAtAll() {
            // 一行永远不响的闹钟不是"以后再想", 是一个会一直出现在"等待中"计数里的幽灵
            assertFalse(service.schedule(AGENT, null, AgentEventType.SCHEDULED_WAKEUP, KEY, "以后再说"));
            verify(repo, never()).save(any());
        }

        @Test
        void theSameAlarmAtTheSameTimeIsATrueNoOp() {
            LocalDateTime at = LocalDateTime.now().plusMinutes(30);
            when(repo.findByAgentIdAndEventTypeAndSourceKey(AGENT, AgentEventType.SCHEDULED_WAKEUP, KEY))
                    .thenReturn(Optional.of(existing(at, AgentWakeup.S_PENDING)));

            // 返回 false 的意义: 调用方能区分"我改了主意"与"我又说了一遍同样的话"
            assertFalse(service.schedule(AGENT, at, AgentEventType.SCHEDULED_WAKEUP, KEY, "再说一次"));
            verify(repo, never()).save(any());
        }

        @Test
        void movingTheSameAlarmUpdatesThatRowInsteadOfAddingOne() {
            // 两个闹钟各占一行的话, 到点时会一起响 —— "她醒来"在日志里变成一串同时到达的事件
            LocalDateTime old = LocalDateTime.now().plusMinutes(30);
            LocalDateTime moved = LocalDateTime.now().plusHours(3);
            AgentWakeup row = existing(old, AgentWakeup.S_PENDING);
            when(repo.findByAgentIdAndEventTypeAndSourceKey(AGENT, AgentEventType.SCHEDULED_WAKEUP, KEY))
                    .thenReturn(Optional.of(row));

            assertTrue(service.schedule(AGENT, moved, AgentEventType.SCHEDULED_WAKEUP, KEY, "算了, 三小时后"));

            ArgumentCaptor<AgentWakeup> saved = ArgumentCaptor.forClass(AgentWakeup.class);
            verify(repo).save(saved.capture());
            assertEquals("w-1", saved.getValue().getId(), "必须是同一行被改时刻, 不是新增一行");
            assertEquals(moved, saved.getValue().getWakeAt());
        }

        @Test
        void anAlreadyFiredAlarmIsRevivedRatherThanDuplicated() {
            // 她等的事又有了新的时刻(面试结果没等到, 又约了下一次)。这里必须复活旧行:
            // 新建一行会撞上同一行的幂等键, 而那一撞的后果是一行永远不会被读到的数据。
            LocalDateTime next = LocalDateTime.now().plusDays(2);
            AgentWakeup fired = existing(LocalDateTime.now().minusDays(1), AgentWakeup.S_FIRED);
            fired.setFiredAt(LocalDateTime.now().minusDays(1));
            when(repo.findByAgentIdAndEventTypeAndSourceKey(AGENT, AgentEventType.SCHEDULED_WAKEUP, KEY))
                    .thenReturn(Optional.of(fired));

            assertTrue(service.schedule(AGENT, next, AgentEventType.SCHEDULED_WAKEUP, KEY, "又约了一次"));

            assertEquals(AgentWakeup.S_PENDING, fired.getStatus());
            assertEquals(next, fired.getWakeAt());
            assertNull(fired.getFiredAt(), "复活之后它还没响过");
        }

        @Test
        void aMissingSourceKeyIsRefused() {
            assertFalse(service.schedule(AGENT, LocalDateTime.now().plusMinutes(1),
                    AgentEventType.SCHEDULED_WAKEUP, "  ", "无来源"));
            verify(repo, never()).save(any());
        }

        @Test
        void anOverlongReasonIsTruncatedToTheColumnWidth() {
            // 列宽 160 —— 超了不是"截断得难看", 是 INSERT 直接失败
            String huge = "忙".repeat(400);

            service.schedule(AGENT, LocalDateTime.now().plusMinutes(1),
                    AgentEventType.SCHEDULED_WAKEUP, KEY, huge);

            ArgumentCaptor<AgentWakeup> saved = ArgumentCaptor.forClass(AgentWakeup.class);
            verify(repo).save(saved.capture());
            assertEquals(AgentWakeupService.REASON_MAX, saved.getValue().getReason().length());
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Nested
    @DisplayName("查询")
    class Queries {

        @Test
        void dueAsksOnlyForPendingAndOnlyUpToNow() {
            service.due(LocalDateTime.now(), 50);

            ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                    ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            verify(repo).findByStatusAndWakeAtBeforeOrderByWakeAtAsc(
                    eq(AgentWakeup.S_PENDING), any(LocalDateTime.class), page.capture());
            assertEquals(50, page.getValue().getPageSize(),
                    "必须带上限: 这个查询跑在共用调度线程上");
        }

        @Test
        void dueWithANonPositiveLimitAsksForNothing() {
            assertTrue(service.due(LocalDateTime.now(), 0).isEmpty());
            verify(repo, never()).findByStatusAndWakeAtBeforeOrderByWakeAtAsc(anyString(), any(), any());
        }

        @Test
        void cancellingTouchesOnlyPendingRowsOfThatSource() {
            AgentWakeup a = existing(LocalDateTime.now().plusMinutes(5), AgentWakeup.S_PENDING);
            AgentWakeup b = existing(LocalDateTime.now().plusMinutes(9), AgentWakeup.S_PENDING);
            when(repo.findByAgentIdAndSourceKeyAndStatus(AGENT, KEY, AgentWakeup.S_PENDING))
                    .thenReturn(List.of(a, b));

            assertEquals(2, service.cancelFor(AGENT, KEY));

            assertEquals(AgentWakeup.S_CANCELLED, a.getStatus());
            assertEquals(AgentWakeup.S_CANCELLED, b.getStatus());
            assertNotNull(a.getFiredAt(), "撤掉的也要记时刻, 否则永远清不掉");
        }

        @Test
        void cancellingNothingDoesNotWrite() {
            when(repo.findByAgentIdAndSourceKeyAndStatus(anyString(), anyString(), anyString()))
                    .thenReturn(List.of());
            assertEquals(0, service.cancelFor(AGENT, KEY));
            verify(repo, never()).saveAll(any());
        }

        @Test
        void nextWakeupIsTheEarliestOne() {
            LocalDateTime soon = LocalDateTime.now().plusMinutes(5);
            when(repo.findFirstByAgentIdAndStatusOrderByWakeAtAsc(AGENT, AgentWakeup.S_PENDING))
                    .thenReturn(Optional.of(existing(soon, AgentWakeup.S_PENDING)));

            // 取最早而不是最后排的那个 —— 调用方自己去比大小的人迟早会比错方向
            assertEquals(soon, service.nextWakeupOf(AGENT));
        }

        @Test
        void purgeReturnsAnIntBecauseTheCallerCountsRows() {
            when(repo.deleteByStatusInAndFiredAtBefore(anyList(), any())).thenReturn(7L);
            assertEquals(7, service.purgeFinishedBefore(LocalDateTime.now().minusDays(7)));
        }
    }

    // ─────────────────────────── 来源键 ───────────────────────────

    @Nested
    @DisplayName("来源键的拼法只有一处")
    class SourceKeys {

        @Test
        void keysArePrefixedWithTheirSource() {
            assertEquals("intention:i-9",
                    AgentWakeupService.key(AgentWakeupService.SRC_INTENTION, "i-9"));
        }

        @Test
        void aMissingIdProducesNoKeyAtAll() {
            // 返回 null 而不是 "decision:null": 后者会变成一个真实的来源键, 于是
            // 所有"没有 id"的闹钟会共享同一行 —— 它们会互相覆盖, 而且看起来一切正常
            assertNull(AgentWakeupService.key(AgentWakeupService.SRC_DECISION, null));
            assertNull(AgentWakeupService.key(AgentWakeupService.SRC_DECISION, "  "));
        }
    }
}
