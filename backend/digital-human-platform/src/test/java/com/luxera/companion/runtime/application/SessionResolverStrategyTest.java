package com.luxera.companion.runtime.application;

import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * LAP v2 R13: {@link SessionResolver} 的四条定位策略, 以及 {@code locate} 与 {@code enter} 的分别。
 *
 * <p>这一组用例要钉住的性质只有一条, 但它有四个面: <b>"打听"与"承诺"是两件事</b>。
 * {@code locate} 跑在每条事件上, 它一次都不许写; {@code enter} 才谈得上进去。把这条性质写死
 * 在这里, 是因为删掉 installation 之后最容易发生的退化就是"反正要找一个会话, 顺手就进了"——
 * 那样每读一次资源都会往会话表里塞一场。
 *
 * <p>第二个重点是 {@link SessionResolver#enter} 第 3 步那个<em>例外</em>: 定位到一场进不去的
 * 会话时, 它必须抛, 而不是退回"那我另开一场"。一次失败被换成一次静默的复制, 是这一整轮里
 * 最难在事后查出来的那种 bug。
 */
class SessionResolverStrategyTest {

    private static final String COMPANION = "companion-1";
    private static final String APP = "com.luxera.example";

    private ApplicationRuntimePort port;
    private SessionResolver resolver;

    @BeforeEach
    void setUp() {
        port = mock(ApplicationRuntimePort.class);
        resolver = new SessionResolver(port);
    }

    // ─────────────────────────── 只读定位 ───────────────────────────

    /** 点名的会话赢过一切 —— 连平台都不必问: 调用方已经知道答案了。 */
    @Test
    void anExplicitSessionWinsWithoutAskingThePlatform() {
        Optional<SessionResolver.Resolution> found = resolver.locate(
                SessionResolver.Query.of(COMPANION, APP).withExplicit("s-explicit").withResource("s-resource"),
                ctx());

        assertEquals("s-explicit", found.orElseThrow().sessionId());
        assertEquals(SessionResolver.Strategy.EXPLICIT, found.get().by());
        verify(port, never()).sessionsOf(anyString(), any());
    }

    /** 没人点名时, 资源行上记的那一场说了算 —— 那是既成事实, 不是推断。 */
    @Test
    void theResourcesOwnSessionIsUsedWhenNothingIsExplicit() {
        Optional<SessionResolver.Resolution> found = resolver.locate(
                SessionResolver.Query.of(COMPANION, APP).withResource("s-resource"), ctx());

        assertEquals("s-resource", found.orElseThrow().sessionId());
        assertEquals(SessionResolver.Strategy.RESOURCE, found.get().by());
        // 策略 1、2 命中时一次都不问平台 —— 拿一个已知的答案去换一个可能被截断的列表,
        // 只会把一场对的会话换成一场碰巧更靠前的。
        verify(port, never()).sessionsOf(anyString(), any());
    }

    /** 两条"我知道"都落空, 才轮到"平台说我此刻在里面"。 */
    @Test
    void participatingIsTheThirdChoice() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(
                ref("s-open-stranger", "OPEN", false),
                ref("s-mine", "INVITE_ONLY", true)));

        Optional<SessionResolver.Resolution> found = resolver.locate(
                SessionResolver.Query.of(COMPANION, APP), ctx());

        assertEquals("s-mine", found.orElseThrow().sessionId());
        assertEquals(SessionResolver.Strategy.PARTICIPATING, found.get().by());
    }

    /** 退场过的那一场不算"我在里面" —— 会话结束了就更不算。 */
    @Test
    void anEndedSessionIsNotWhereIAmParticipating() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-dead", "OPEN", true, 1, "ENDED")));

        assertTrue(resolver.locate(SessionResolver.Query.of(COMPANION, APP), ctx()).isEmpty());
    }

    /**
     * <b>只读定位绝不走进任何一扇门</b> —— 哪怕门开着。
     *
     * <p>这是本类最要紧的一条: {@code locate} 跑在每一条事件上, 而"顺手进去"会让数字人在
     * 谁都没请它的时候出现在别人的局里。
     */
    @Test
    void locateNeverJoinsEvenWhenTheDoorIsOpen() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-open", "OPEN", false)));

        assertTrue(resolver.locate(SessionResolver.Query.of(COMPANION, APP), ctx()).isEmpty());
        verify(port, never()).joinSession(anyString(), any());
        verify(port, never()).ensureSession(anyString(), any());
    }

    /** 平台读不出来时, 定位退化成"什么都没找到", 而不是把整条链炸掉。 */
    @Test
    void aFailingSessionLookupDegradesToNotFound() {
        when(port.sessionsOf(anyString(), any())).thenThrow(new IllegalStateException("会话库暂时不可用"));

        assertTrue(resolver.locate(SessionResolver.Query.of(COMPANION, APP), ctx()).isEmpty());
    }

    // ─────────────────────────── 确保在场 ───────────────────────────

    /** 开着的门可以走进去 —— 这是 enter 与 locate 的全部分别。 */
    @Test
    void enterWalksThroughAnOpenDoor() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-open", "OPEN", false)));

        SessionResolver.Resolution entered = resolver.enter(SessionResolver.Query.of(COMPANION, APP), ctx());

        assertEquals("s-open", entered.sessionId());
        assertEquals(SessionResolver.Strategy.DISCOVERABLE, entered.by());
        verify(port, times(1)).joinSession("s-open", ctx());
        verify(port, never()).ensureSession(anyString(), any());
    }

    /** 一场都没有 → 开一场, 并且记成 CREATED(而不是含糊的 DISCOVERABLE)。 */
    @Test
    void enterCreatesOneWhenThereIsNothingToJoin() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of());
        when(port.ensureSession(APP, ctx())).thenReturn("s-new");

        SessionResolver.Resolution entered = resolver.enter(SessionResolver.Query.of(COMPANION, APP), ctx());

        assertEquals("s-new", entered.sessionId());
        assertEquals(SessionResolver.Strategy.CREATED, entered.by());
        verify(port, never()).joinSession(anyString(), any());
    }

    /** 已经在一场里, 而那一场是私密的: 我本来就进得去, 于是什么都不用做。 */
    @Test
    void enterIsIdempotentWhenIAmAlreadyInside() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-mine", "INVITE_ONLY", true)));

        SessionResolver.Resolution entered = resolver.enter(SessionResolver.Query.of(COMPANION, APP), ctx());

        assertEquals("s-mine", entered.sessionId());
        assertEquals(SessionResolver.Strategy.PARTICIPATING, entered.by());
        verify(port, never()).joinSession(anyString(), any());
        verify(port, never()).ensureSession(anyString(), any());
    }

    /**
     * <b>点名了一场进不去的会话 → 抛, 而不是另开一场。</b>
     *
     * <p>这是本类的第二条要害。{@code ensureSession} 那一步在"我进不去我想进的那一场"时
     * 必须够不着 —— 否则一次失败会变成一次静默的复制: 邀请你的人在那场里等着, 而你在新的
     * 一场里对着空房间, 两边都不知道发生了什么。
     */
    @Test
    void enterRefusesToQuietlyOpenASecondRoomWhenTheNamedOneNeedsATicket() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-private", "INVITE_ONLY", false)));

        SessionResolver.UnavailableException e = assertThrows(SessionResolver.UnavailableException.class,
                () -> resolver.enter(SessionResolver.Query.of(COMPANION, APP).withExplicit("s-private"), ctx()));

        assertEquals("NEEDS_INVITATION", e.reason());
        verify(port, never()).ensureSession(anyString(), any());
        verify(port, never()).joinSession(anyString(), any());
    }

    /** 点名了一场平台压根看不见的会话 —— 也不替调用方猜, 抛。 */
    @Test
    void enterRefusesASessionThePlatformCannotSee() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of());

        SessionResolver.UnavailableException e = assertThrows(SessionResolver.UnavailableException.class,
                () -> resolver.enter(SessionResolver.Query.of(COMPANION, APP).withExplicit("s-ghost"), ctx()));

        assertEquals("SESSION_NOT_VISIBLE", e.reason());
        verify(port, never()).ensureSession(anyString(), any());
    }

    /** 满员的场子进不去 —— 也不退回去开一场。 */
    @Test
    void enterRefusesAFullRoom() {
        when(port.sessionsOf(APP, ctx())).thenReturn(List.of(ref("s-full", "OPEN", false, 8, "ACTIVE")));

        SessionResolver.UnavailableException e = assertThrows(SessionResolver.UnavailableException.class,
                () -> resolver.enter(SessionResolver.Query.of(COMPANION, APP).withExplicit("s-full"), ctx()));

        assertEquals("NEEDS_INVITATION", e.reason());
        verify(port, never()).ensureSession(anyString(), any());
    }

    /** 没有应用就问不出"我在哪儿" —— 但点名的那一场仍然认。 */
    @Test
    void withoutAnApplicationThereIsNothingToDiscover() {
        assertTrue(resolver.locate(SessionResolver.Query.of(COMPANION, null), ctx()).isEmpty());
        assertEquals("s-explicit", resolver.locate(
                        SessionResolver.Query.of(COMPANION, null).withExplicit("s-explicit"), ctx())
                .orElseThrow().sessionId());
        verify(port, never()).sessionsOf(any(), any());
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static InvocationContext ctx() {
        return InvocationContext.agent(COMPANION, "user-1", "corr-1");
    }

    private static SessionRef ref(String sessionId, String joinPolicy, boolean joined) {
        return ref(sessionId, joinPolicy, joined, joined ? 1 : 0, "ACTIVE");
    }

    private static SessionRef ref(String sessionId, String joinPolicy, boolean joined,
                                  int participantCount, String status) {
        return new SessionRef(sessionId, APP, status, "UNLISTED", joinPolicy,
                participantCount, 8, joined, "HUMAN", "someone", null);
    }
}
