package com.luxera.agentserver.web;

import com.luxera.companion.contracts.application.ActionRequest;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ApplicationView;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.CapabilityView;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAP 目录的拼装 —— 三层变一棵树, 以及两件容易写错的事。
 *
 * <h2>它钉住的两个症状</h2>
 *
 * 1. **逐层短路**。聊天平台不可达时, 三个读方法各会超时 5 秒。不短路的话, 一个
 *    没有能力的部署上这一页要转 1+N 次超时 —— 用户看到的是"控制台卡死了", 而真相
 *    是"应用平台是空的"。
 * 2. **一层失败不拖垮整页**。某个应用的 actions 读挂了, 其余能力与应用照常显示。
 *    让一个动作列表的失败把"平台有哪些能力"整页吃掉, 是把小故障放大成大故障。
 *
 * <p>为什么是纯单测: 装配(这个控制器真的被 8091 扫到、真的拿到了那个 bean)由部署后
 * 的实测覆盖 —— 那件事这组单测替代不了, 也不该假装能。
 */
class LapCatalogControllerTest {

    @Test
    void capabilityApplicationAndActionBecomeOneTree() {
        Fake port = new Fake();
        port.capabilities = List.of(cap("game.play", "对局"));
        port.applications = List.of(app("com.luxera.gomoku", "五子棋"));
        port.actions = List.of(action("game.make_move"));

        List<LapCatalogController.CapabilityEntry> tree = new LapCatalogController(port).catalog();

        assertEquals(1, tree.size());
        LapCatalogController.ApplicationEntry appEntry = tree.get(0).applications().get(0);
        assertEquals("com.luxera.gomoku", appEntry.applicationId());
        assertEquals(1, appEntry.actions().size());
        assertEquals("game.make_move", appEntry.actions().get(0).actionId());
        assertEquals(List.of("game.play"), appEntry.capabilities(),
                "能力的归属要跟着应用一起带出来 —— 前端不该为了这条信息再问一次");
    }

    @Test
    void noCapabilitiesMeansNoFurtherCallsAtAll() {
        Fake port = new Fake();
        port.capabilities = List.of();

        assertTrue(new LapCatalogController(port).catalog().isEmpty());
        assertEquals(0, port.applicationCalls,
                "没有能力就不该问应用 —— 每次都是一次 5s 超时的赌注");
        assertEquals(0, port.actionCalls);
    }

    @Test
    void anEmptyCapabilityStillSkipsItsActionLookups() {
        Fake port = new Fake();
        port.capabilities = List.of(cap("game.play", "对局"));
        port.applications = List.of();

        List<LapCatalogController.CapabilityEntry> tree = new LapCatalogController(port).catalog();

        assertEquals(1, tree.size(), "没有应用的能力仍然要出现 —— 「这个能力还没人实现」是一句话, 不是空白");
        assertTrue(tree.get(0).applications().isEmpty());
        assertEquals(0, port.actionCalls);
    }

    @Test
    void oneBadLayerDegradesToEmptyInsteadOfFailingTheWholePage() {
        Fake port = new Fake();
        port.capabilities = List.of(cap("game.play", "对局"), cap("life.remind", "提醒"));
        port.applications = List.of(app("com.luxera.gomoku", "五子棋"));
        port.actionsThrow = true;

        List<LapCatalogController.CapabilityEntry> tree = new LapCatalogController(port).catalog();

        assertEquals(2, tree.size(), "一个应用的动作读挂了, 另一个能力不该跟着消失");
        assertTrue(tree.get(0).applications().get(0).actions().isEmpty(),
                "读挂的那一层降级成空 —— 而不是把异常抛到页面上");
    }

    @Test
    void capabilitiesThrowingStillYieldsAnEmptyCatalogRatherThanA500() {
        Fake port = new Fake();
        port.capabilitiesThrow = true;

        assertTrue(new LapCatalogController(port).catalog().isEmpty());
    }

    @Test
    void aNullListFromThePortIsTreatedAsEmpty() {
        // 端口的契约是"读缺席 → 空", 但 `null` 与 `List.of()` 在 Java 里是两回事;
        // 一个返回 null 的实现不该让目录页 NPE。
        Fake port = new Fake();
        port.capabilities = null;

        assertTrue(new LapCatalogController(port).catalog().isEmpty());
    }

    // ── 桩 ────────────────────────────────────────────────────────────────────

    private static CapabilityView cap(String id, String title) {
        return new CapabilityView(id, title, title + " 的说明", "game");
    }

    private static ApplicationView app(String id, String name) {
        return new ApplicationView(id, "1.0.0", name, name + " 的说明", "game", List.of("game.play"));
    }

    private static ActionSpec action(String id) {
        return new ActionSpec(id, "com.luxera.gomoku", "game.play", "下一步",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.AWARE, null, null);
    }

    /** 只实现被目录用到的三个读方法; 其余抛 —— 目录若碰了它们, 测试会立刻炸。 */
    private static final class Fake implements ApplicationRuntimePort {
        List<CapabilityView> capabilities = List.of();
        List<ApplicationView> applications = List.of();
        List<ActionSpec> actions = List.of();
        boolean capabilitiesThrow = false;
        boolean actionsThrow = false;
        int applicationCalls = 0;
        int actionCalls = 0;

        @Override public List<CapabilityView> capabilities() {
            if (capabilitiesThrow) throw new IllegalStateException("聊天平台不可达");
            return capabilities;
        }

        @Override public List<ApplicationView> applicationsFor(String capabilityId) {
            applicationCalls++;
            return applications;
        }

        @Override public List<ActionSpec> actionsOf(String applicationId) {
            actionCalls++;
            if (actionsThrow) throw new IllegalStateException("这一层挂了");
            return actions;
        }

        // ── 以下不在目录的读取路径上 ──
        @Override public Optional<ResourceView> read(String resourceUri) { throw new UnsupportedOperationException(); }
        @Override public List<ActionSpec> pendingActions(String resourceUri, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public ActionResponse execute(ActionRequest request, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public String ensureSession(String applicationId, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public List<SessionRef> sessionsOf(String applicationId, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public String joinByInvitation(String token, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public void joinSession(String sessionId, InvocationContext ctx) { throw new UnsupportedOperationException(); }
        @Override public void leaveSession(String sessionId, InvocationContext ctx) { throw new UnsupportedOperationException(); }
    }
}
