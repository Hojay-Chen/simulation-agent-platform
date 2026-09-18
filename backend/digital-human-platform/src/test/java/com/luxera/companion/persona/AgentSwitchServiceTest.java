package com.luxera.companion.persona;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开关本身 —— 四个读判据、两个写操作的幂等、以及批量操作划在哪条界上。
 *
 * <p>用替身仓储而不是真库: 这里要断言的是"开关怎么理解状态", 不是"数据库存不存得住"。
 * 真库那一段由部署后的实测覆盖。
 */
class AgentSwitchServiceTest {

    private CompanionRepository companions;

    @BeforeEach
    void setUp() {
        companions = mock(CompanionRepository.class);
    }

    private AgentSwitchService service(boolean runtimeEnabled) {
        return new AgentSwitchService(companions, runtimeEnabled);
    }

    // ─────────────── 读: 四种输入 ───────────────

    @Test
    void aPausedAgentIsNotRunnableAndAnActiveOneIs() {
        when(companions.findById("a")).thenReturn(Optional.of(companion("a", "paused")));
        when(companions.findById("b")).thenReturn(Optional.of(companion("b", "active")));

        AgentSwitchService s = service(true);
        assertFalse(s.isRunnable("a"));
        assertTrue(s.isRunnable("b"));
    }

    @Test
    void anUnknownCompanionIdRunsBecauseABugIsNotAPause() {
        // 这是本类唯一一处"往不省钱的方向兜底"。它的理由是一个取舍: 一个查不到的 id
        // 意味着调用方有 bug, 而"用停机来应对 bug"会把一个显式的错误变成一片静默 ——
        // 症状是"她不回我了", 而没有任何东西指向这里。开关的职责是执行**明确的**暂停。
        when(companions.findById(anyString())).thenReturn(Optional.empty());
        assertTrue(service(true).isRunnable("nobody"));
    }

    @Test
    void aCallWithNoCompanionIdIsNotAnAgentsExpense() {
        // 平台里有一批调用是用户当场发起的(编译人格、应用链路的三个 resolver),
        // 它们的 metadata 里本来就没有 companionId。拦住它们等于"人按了按钮却没反应",
        // 而这与省钱无关。
        assertTrue(service(true).isRunnable(null));
        assertTrue(service(true).isRunnable("  "));
        verify(companions, never()).findById(anyString());
    }

    @Test
    void theDeploymentWideSwitchStopsEveryoneWithoutTouchingTheDatabase() {
        // 部署级总闸关掉时, 连查库都不该发生 —— 它回答的是"这台机器要不要跑",
        // 与"这个 agent 是什么状态"无关。
        AgentSwitchService s = service(false);
        assertFalse(s.isRunnable("a"));
        assertFalse(s.isRunnable(null));
        verify(companions, never()).findById(anyString());
    }

    @Test
    void isRunningOnAnEntityNeedsNoQueryAndAlsoExcludesDeletedAgents() {
        AgentSwitchService s = service(true);
        assertTrue(s.isRunning(companion("a", "active")));
        assertFalse(s.isRunning(companion("a", "paused")));

        Companion deleted = companion("a", "active");
        deleted.setDeletedAt(LocalDateTime.now());
        assertFalse(s.isRunning(deleted), "已销毁的 agent 不该被定时任务再推进");
        assertFalse(s.isRunning(null));

        // 全部判据都在实体上 —— 一次库都没查
        verify(companions, never()).findById(anyString());
    }

    @Test
    void lifecycleOfFallsBackToActiveForAMissingRow() {
        when(companions.findById("x")).thenReturn(Optional.empty());
        assertEquals(AgentLifecycle.ACTIVE, service(true).lifecycleOf("x"));
    }

    // ─────────────── 写: 幂等 ───────────────

    @Test
    void pauseIsIdempotentAndDoesNotRewriteAnAlreadyPausedRow() {
        Companion c = companion("a", "paused");
        when(companions.findById("a")).thenReturn(Optional.of(c));

        // 第二次暂停返回 false, 且**不 save** —— "我按了两下"不该在数据上留下两道痕迹
        assertFalse(service(true).pause("a"));
        verify(companions, never()).save(any());
    }

    @Test
    void pauseThenResumeFlipsTheColumnAndReportsTheChange() {
        Companion c = companion("a", "active");
        when(companions.findById("a")).thenReturn(Optional.of(c));

        assertTrue(service(true).pause("a"));
        assertEquals("paused", c.getStatus());
        assertTrue(c.isPaused());

        assertTrue(service(true).resume("a"));
        assertEquals("active", c.getStatus());
    }

    @Test
    void pausingAMissingAgentIsNotAnErrorJustNoChange() {
        when(companions.findById("ghost")).thenReturn(Optional.empty());
        assertFalse(service(true).pause("ghost"));
        assertFalse(service(true).resume("ghost"));
    }

    // ─────────────── 批量 ───────────────

    @Test
    void pauseAllCountsOnlyWhatActuallyChangedAndSkipsDeletedRows() {
        List<Companion> all = new ArrayList<>(List.of(
                companion("a", "active"),
                companion("b", "active"),
                companion("c", "paused")));          // 已经停着的不算改动
        when(companions.findByDeletedAtIsNullOrderByCreatedAtAsc()).thenReturn(all);

        assertEquals(2, service(true).pauseAll(), "只报真正变化的行数 —— 它回答'我按了两下, 第二下做事了吗'");
        assertEquals(0, service(true).pauseAll(), "再按一次是 0, 不是 2");

        // 已软删的行根本不在输入里(findByDeletedAtIsNull...), 所以 pauseAll 天然不碰它们 ——
        // 那让 RETIRED 与 PAUSED 两个概念在数据上不会互相污染
        ArgumentCaptor<Iterable<Companion>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(companions).saveAll(saved.capture());
        for (Companion c : saved.getValue()) {
            assertEquals("paused", c.getStatus());
        }
    }

    @Test
    void resumeAllOnlyTouchesThePausedOnes() {
        List<Companion> all = new ArrayList<>(List.of(
                companion("a", "active"),
                companion("b", "paused")));
        when(companions.findByDeletedAtIsNullOrderByCreatedAtAsc()).thenReturn(all);

        assertEquals(1, service(true).resumeAll());
        assertEquals("active", all.get(1).getStatus());
        assertEquals("active", all.get(0).getStatus());
    }

    // ─────────────── 统计 ───────────────

    @Test
    void statsCountsBothSidesAndCarriesTheDeploymentSwitch() {
        when(companions.findByDeletedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                companion("a", "active"), companion("b", "paused"), companion("c", null)));

        AgentSwitchService s = service(true);
        AgentSwitchService.Stats st = s.stats();
        assertEquals(2, st.active(), "status 为 null 的存量行算运行中, 与 AgentLifecycle.of 一致");
        assertEquals(1, st.paused());
        assertTrue(st.runtimeEnabled());

        s.noteBlocked("event-simulation");
        s.noteBlocked("event-simulation");
        s.noteBlocked("chat_stream");
        assertEquals(3, s.stats().blockedCalls());
        assertEquals(2L, s.stats().blockedByTask().get("event-simulation"));
        assertEquals(1L, s.stats().blockedByTask().get("chat_stream"));
        assertFalse(service(false).stats().runtimeEnabled());
    }

    // ─────────────── 替身 ───────────────

    private static Companion companion(String id, String status) {
        Companion c = new Companion();
        c.setId(id);
        c.setUserId("user-1");
        c.setName(id);
        c.setStatus(status);
        return c;
    }
}
