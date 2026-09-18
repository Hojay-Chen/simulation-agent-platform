package com.luxera.agentserver.web;

import com.luxera.companion.common.BusinessException;
import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.persona.AgentSwitchService;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import com.luxera.companion.persona.CompanionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开关的**控制台面** —— 权限的划界, 以及"打得错的输入不许被猜成默认值"。
 *
 * <h2>它钉住的三件事</h2>
 *
 * <ol>
 *   <li><b>认不出的 lifecycle 回 400, 不猜。</b>一个打错的枚举值若被当成 active,
 *       症状是"我明明按了停, 它还在烧 token" —— 而没有人会去怀疑那个请求体。</li>
 *   <li><b>归属先于开关。</b>不属于调用者的 agent 在这里就 404 了, 开关根本不会被调用 ——
 *       否则任何登录用户都能用别人的 agentId 停掉别人的 agent, 而且请求是"成功"的:
 *       开关认 id 不认人。</li>
 *   <li><b>批量只作用于自己的。</b>"全部停止"在这一面没有平台级主语 —— 它只能是我名下那一批。
 *       平台级的全部关停在 8092 的管理密钥面。</li>
 * </ol>
 *
 * <p>为什么用真 {@code AgentSwitchService} + 替身仓储, 而不是把开关也替掉: 这一面的
 * 核心断言之一是 <b>changed 这个数从哪来</b> —— 它必须是真的"这一次改动了几行", 而不是
 * 一个由控制器猜出来的布尔。用真开关才测得到这条。
 *
 * <p>为什么不是 {@code @SpringBootTest} + MockMvc: 那一套要拉整个 8091 上下文(认知链、LLM、
 * 数据源)。装配(三条路由真的被 8091 扫到、{@code {id}} 真的吃不掉 {@code lifecycle})
 * 由部署后的实测与 {@code GET /api/agents/lifecycle} 的一次真实调用覆盖。
 */
class AgentLifecycleControllerTest {

    private static final String ME = "user-1";

    private CompanionRepository companions;
    private CompanionService companionService;
    private CurrentUser currentUser;
    private AgentLifecycleController controller;

    @BeforeEach
    void setUp() {
        companions = mock(CompanionRepository.class);
        companionService = mock(CompanionService.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.requireUserId()).thenReturn(ME);
        controller = new AgentLifecycleController(new AgentSwitchService(companions, true),
                companionService, currentUser);
    }

    // ─────────────── PUT 一个状态 ───────────────

    @Test
    void aPausedRequestStopsTheAgentAndReportsTheChange() {
        Companion c = companion("a", "active");
        when(companionService.requireOwned(ME, "a")).thenReturn(c);
        when(companions.findById("a")).thenReturn(Optional.of(c));

        ResponseEntity<Map<String, Object>> resp = controller.setLifecycle("a", body("paused"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("paused", resp.getBody().get("lifecycle"));
        assertEquals(Boolean.TRUE, resp.getBody().get("changed"));
        assertEquals("paused", c.getStatus(), "改动要落在实体上 —— 这一列才是开关的真身");
    }

    @Test
    void theSecondIdenticalRequestReportsNoChange() {
        Companion c = companion("a", "active");
        when(companionService.requireOwned(ME, "a")).thenReturn(c);
        when(companions.findById("a")).thenReturn(Optional.of(c));

        controller.setLifecycle("a", body("paused"));
        ResponseEntity<Map<String, Object>> second = controller.setLifecycle("a", body("paused"));

        assertEquals(Boolean.FALSE, second.getBody().get("changed"),
                "changed 回答的是「我按了两下, 第二下做事了吗」; 它不该在第二次撒谎说自己又停了一次");
    }

    @Test
    void caseAndWhitespaceInTheBodyAreForgiven() {
        Companion c = companion("a", "active");
        when(companionService.requireOwned(ME, "a")).thenReturn(c);
        when(companions.findById("a")).thenReturn(Optional.of(c));

        assertEquals("paused",
                controller.setLifecycle("a", body("  PAUSED ")).getBody().get("lifecycle"));
    }

    @Test
    void anUnrecognisedLifecycleIsRejectedRatherThanGuessed() {
        for (String bad : new String[]{"stop", "sleeping", "", "  ", null, "active2"}) {
            ResponseEntity<Map<String, Object>> resp = controller.setLifecycle("a", body(bad));

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), "「" + bad + "」必须被拒");
        }
        // 一个被拒的请求不该产生任何副作用: 没查归属、没碰开关
        verify(companionService, never()).requireOwned(anyString(), anyString());
        verify(companions, never()).save(any());
    }

    @Test
    void aMissingBodyIsRejectedNotTreatedAsActive() {
        // body 为 null 时绝不能兜底成 active —— 一个被截断的请求体不该让 agent 重新跑起来
        assertEquals(HttpStatus.BAD_REQUEST, controller.setLifecycle("a", null).getStatusCode());
    }

    @Test
    void ownershipIsCheckedBeforeTheSwitchSoNobodyCanStopSomeoneElsesAgent() {
        when(companionService.requireOwned(ME, "theirs"))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "伴侣不存在", null));

        assertThrows(BusinessException.class, () -> controller.setLifecycle("theirs", body("paused")));

        // 关键: 开关一次都没被调用。控制器若先动开关再查归属, 这里就会留下一次真实的停机
        verify(companions, never()).findById(anyString());
    }

    // ─────────────── 批量: 只作用于自己名下 ───────────────

    @Test
    void pauseAllOnlyTouchesWhatTheCallerOwns() {
        // 仓储里躺着三个 agent, 但 list(ME) 只给出其中一个 —— 批量必须走 list(userId),
        // 不能走 findByDeletedAtIsNull(全平台)。后者会让任何一个登录用户一键停掉所有人。
        when(companionService.list(ME)).thenReturn(List.of(companion("mine", "active")));
        when(companions.findById("mine")).thenReturn(Optional.of(companion("mine", "active")));

        Map<String, Object> out = controller.pauseAllMine();

        assertEquals(1, out.get("paused"));
        assertEquals(1, out.get("total"));
        verify(companionService, times(1)).list(ME);
        verify(companions, never()).findByDeletedAtIsNullOrderByCreatedAtAsc();
    }

    @Test
    void pauseAllReportsOnlyRowsThatActuallyFlipped() {
        when(companionService.list(ME)).thenReturn(List.of(
                companion("a", "active"), companion("b", "paused")));
        when(companions.findById("a")).thenReturn(Optional.of(companion("a", "active")));
        when(companions.findById("b")).thenReturn(Optional.of(companion("b", "paused")));

        Map<String, Object> out = controller.pauseAllMine();

        assertEquals(1, out.get("paused"), "already-paused 的那一个不该被算进 changed");
        assertEquals(2, out.get("total"), "但 total 是名单的大小 —— 两个数回答的是两个问题");
    }

    @Test
    void resumeAllBringsBackOnlyThePausedOnes() {
        Companion paused = companion("b", "paused");
        when(companionService.list(ME)).thenReturn(List.of(companion("a", "active"), paused));
        when(companions.findById("a")).thenReturn(Optional.of(companion("a", "active")));
        when(companions.findById("b")).thenReturn(Optional.of(paused));

        Map<String, Object> out = controller.resumeAllMine();

        assertEquals(1, out.get("resumed"));
        assertEquals("active", paused.getStatus());
    }

    // ─────────────── 全景 ───────────────

    @Test
    void theOverviewCarriesMyAgentsAndThePlatformWidePicture() {
        when(companionService.list(ME)).thenReturn(List.of(
                companion("a", "active"), companion("b", "paused")));
        when(companions.findByDeletedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                companion("a", "active"), companion("b", "paused"), companion("c", "active")));

        Map<String, Object> out = controller.overview();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) out.get("agents");
        assertEquals(2, agents.size());
        assertEquals("a", agents.get(0).get("agentId"));
        assertEquals("active", agents.get(0).get("lifecycle"));
        assertEquals(Boolean.TRUE, agents.get(1).get("paused"));

        assertEquals(1L, out.get("mineActive"));
        assertEquals(1L, out.get("minePaused"));

        // 平台那一份回答的是"我关了的是不是全部" —— 一个只看自己列表的界面无法回答它
        AgentSwitchService.Stats platform = (AgentSwitchService.Stats) out.get("platform");
        assertEquals(2, platform.active());
        assertEquals(1, platform.paused());
        assertTrue(platform.runtimeEnabled());
    }

    @Test
    void theOverviewNeedsALoggedInUser() {
        when(currentUser.requireUserId())
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("未登录"));

        assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> controller.overview());
        verify(companionService, never()).list(anyString());
    }

    @Test
    void anAgentWithNoStatusColumnValueReadsAsRunning() {
        // 存量行没写过 status。它在界面上必须显示成"运行中", 而不是"已停止" ——
        // 后者会让运维以为一切都关好了, 而实际上 110 个 agent 全在跑。
        when(companionService.list(ME)).thenReturn(List.of(companion("old", null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents =
                (List<Map<String, Object>>) controller.overview().get("agents");

        assertEquals("active", agents.get(0).get("lifecycle"));
        assertFalse((Boolean) agents.get(0).get("paused"));
        assertEquals(1L, controller.overview().get("mineActive"));
    }

    // ─────────────── 夹具 ───────────────

    private static AgentLifecycleController.SetLifecycleBody body(String lifecycle) {
        AgentLifecycleController.SetLifecycleBody b = new AgentLifecycleController.SetLifecycleBody();
        b.setLifecycle(lifecycle);
        return b;
    }

    private static Companion companion(String id, String status) {
        Companion c = new Companion();
        c.setId(id);
        c.setUserId(ME);
        c.setName(id);
        c.setStatus(status);
        return c;
    }
}
