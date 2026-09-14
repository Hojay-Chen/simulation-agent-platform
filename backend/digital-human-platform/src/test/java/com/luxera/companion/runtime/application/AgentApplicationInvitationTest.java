package com.luxera.companion.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.persona.PersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LAP v2 R13: <b>收到邀请的数字人怎么决定去不去</b>。
 *
 * <p>三件事, 每一件都有一条断言盯着:
 * <ol>
 *   <li><b>"没决定"与"决定不去"是两回事。</b> LLM 不可用或答得不能采信时是前者 ——
 *       什么都不记。把一次服务抖动写成"这个数字人拒绝过谁", 是账本里最难查的一类假话。</li>
 *   <li><b>进场只有一扇门</b>: 兑票。票不灵了才轮到"门还开着"。两扇都没开就什么都没发生,
 *       绝不退回去改口说"谢绝"。</li>
 *   <li><b>明文 token 只有一个去处</b> —— {@code joinByInvitation} 的第一个参数。
 *       不写日志、不写账本、不进提示词。最后一条由
 *       {@link #thePlaintextTicketNeverReachesTheLedger()} 逐字断言。</li>
 * </ol>
 */
class AgentApplicationInvitationTest {

    private static final String COMPANION = "companion-1";
    private static final String APP = "com.luxera.example";
    private static final String SESSION = "s-invited";
    private static final String INVITATION = "inv-1";
    private static final String TOKEN = "tok-abcdefghijklmnopqrstuv";

    private final ObjectMapper mapper = new ObjectMapper();

    private ApplicationRuntimePort port;
    private LlmRouter llmRouter;
    private RealityLedger realityLedger;
    private AgentApplicationInvitationHandler handler;

    @BeforeEach
    void setUp() {
        port = mock(ApplicationRuntimePort.class);
        llmRouter = mock(LlmRouter.class);
        realityLedger = mock(RealityLedger.class);

        PersonActorRegistry registry = mock(PersonActorRegistry.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(registry).tell(anyString(), any(Runnable.class));

        handler = new AgentApplicationInvitationHandler(port, llmRouter, realityLedger,
                new EventRouter(), registry, mock(PersonaService.class));
    }

    // ─────────────────────────── 接受 ───────────────────────────

    /** 想去 → 兑票 → 进场 → 记一笔。 */
    @Test
    void acceptingRedeemsTheTicketAndRecordsIt() {
        stubLlm("{\"accept\":true,\"reason\":\"正好想下盘棋\"}");
        when(port.joinByInvitation(eq(TOKEN), any())).thenReturn(SESSION);

        assertEquals(AgentApplicationInvitationHandler.Decision.ACCEPT, handle());

        verify(port, times(1)).joinByInvitation(eq(TOKEN), any());
        verify(port, never()).joinSession(anyString(), any());
        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(realityLedger).append(eq(COMPANION),
                eq(RealityEventType.APPLICATION_INVITATION_ACCEPTED), payload.capture(), any(), any());
        assertEquals(SESSION, payload.getValue().get("sessionId"));
    }

    /** 票被撤了/过期了, 但那一场还开着门 —— 门开着本身就是准入, 不需要票。 */
    @Test
    void aDeadTicketStillLetsTheAgentThroughAnOpenDoor() {
        stubLlm("{\"accept\":true,\"reason\":\"想去\"}");
        when(port.joinByInvitation(eq(TOKEN), any()))
                .thenThrow(new IllegalStateException("INVITATION_REVOKED: 票被撤回了"));

        assertEquals(AgentApplicationInvitationHandler.Decision.ACCEPT, handle());

        verify(port, times(1)).joinSession(eq(SESSION), any());
        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(realityLedger).append(eq(COMPANION),
                eq(RealityEventType.APPLICATION_INVITATION_ACCEPTED), payload.capture(), any(), any());
        assertEquals(SESSION, payload.getValue().get("sessionId"), "记的是真的进去了的那一场");
    }

    /**
     * 两扇门都没开 → 什么都没发生。
     *
     * <p>尤其: <b>不退回去改口说"谢绝"</b>。一封没能兑现的邀请信是一个事实, 而它不是这个数字人
     * 的决定 —— 账本里记错这一笔, 事后就分不清"他没去"与"他没进得去"。
     */
    @Test
    void whenNeitherDoorOpensNothingIsRecorded() {
        stubLlm("{\"accept\":true,\"reason\":\"想去\"}");
        when(port.joinByInvitation(eq(TOKEN), any())).thenThrow(new IllegalStateException("INVITATION_EXPIRED"));
        doAnswer(inv -> {
            throw new IllegalStateException("SESSION_FULL");
        }).when(port).joinSession(anyString(), any());

        assertEquals(AgentApplicationInvitationHandler.Decision.ACCEPT, handle());

        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 谢绝 / 没决定 ───────────────────────────

    /** 想过之后说不 —— 记一笔, 但一扇门都不碰。 */
    @Test
    void decliningIsRecordedAndTouchesNoDoor() {
        stubLlm("{\"accept\":false,\"reason\":\"今天不想\"}");

        assertEquals(AgentApplicationInvitationHandler.Decision.REJECT, handle());

        verify(realityLedger).append(eq(COMPANION),
                eq(RealityEventType.APPLICATION_INVITATION_DECLINED), any(), any(), any());
        verify(port, never()).joinByInvitation(anyString(), any());
        verify(port, never()).joinSession(anyString(), any());
    }

    /**
     * <b>LLM 不可用 ⇒ 不决定, 也不记账。</b>
     *
     * <p>这一条与反应路径上那条"零次 execute"是同一句话: 数字人的行为必须由 LLM 决定,
     * 不许有默认值兜底。默认"去"会让它在服务抖动时到处乱窜, 默认"不去"会让它替自己撒一次谎。
     */
    @Test
    void llmUnavailableMeansNoDecisionAndNoLedgerEntry() {
        when(llmRouter.available()).thenReturn(false);

        assertEquals(AgentApplicationInvitationHandler.Decision.IGNORE, handle());

        verifyNoInteractions(realityLedger);
        verify(port, never()).joinByInvitation(anyString(), any());
        verify(port, never()).joinSession(anyString(), any());
        // 连问都不问: 不可用就是不问, 不是"问了再说"
        verify(llmRouter, never()).structured(any(StructuredRequest.class));
    }

    /** mock provider 同理 —— 它给出的任何答复都不是这个数字人的意思。 */
    @Test
    void aMockProviderMeansNoDecision() {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(true);

        assertEquals(AgentApplicationInvitationHandler.Decision.IGNORE, handle());

        verifyNoInteractions(realityLedger);
        verify(port, never()).joinByInvitation(anyString(), any());
    }

    /** 答得不能采信(没有 accept 字段)也算"没决定" —— 从一句读不懂的话里挑一个布尔就是启发式。 */
    @Test
    void anAnswerWithoutAnAcceptFieldIsNotADecision() {
        stubLlm("{\"reason\":\"这个应用看起来挺有意思的\"}");

        assertEquals(AgentApplicationInvitationHandler.Decision.IGNORE, handle());

        verifyNoInteractions(realityLedger);
        verify(port, never()).joinByInvitation(anyString(), any());
    }

    /** LLM 调用本身炸了也一样: 没决定。 */
    @Test
    void aFailingLlmCallIsNotADecision() {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class))).thenThrow(new RuntimeException("超时"));

        assertEquals(AgentApplicationInvitationHandler.Decision.IGNORE, handle());

        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── token 的去处 ───────────────────────────

    /**
     * <b>明文票不许出现在账本里</b> —— 一个字符都不许。
     *
     * <p>这不是洁癖: 账本是 append-only 的, 一条凭据被记进"记忆"里就不再是凭据了。而且账本会
     * 被回放、被投影进记忆、被读进提示词 —— 每一条都是 token 不该去的地方。
     */
    @Test
    void thePlaintextTicketNeverReachesTheLedger() {
        stubLlm("{\"accept\":true,\"reason\":\"想去\"}");
        when(port.joinByInvitation(eq(TOKEN), any())).thenReturn(SESSION);

        handle();

        ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
        verify(realityLedger).append(eq(COMPANION),
                eq(RealityEventType.APPLICATION_INVITATION_ACCEPTED), payload.capture(), any(), any());
        assertFalse(payload.getValue().toString().contains(TOKEN),
                "账本里出现了明文票: " + payload.getValue());
        // 相关性用的是邀请 id —— 它是这张票的名字, 不是票本身
        assertTrue(payload.getValue().containsValue(INVITATION));
    }

    // ─────────────────────────── 事件入口 ───────────────────────────

    /** 走完整条入口(订阅 → 邮箱 → 处置)也要能兑票。 */
    @Test
    void aDirectedInvitationEventGoesAllTheWayThroughTheMailbox() {
        stubLlm("{\"accept\":true,\"reason\":\"去\"}");
        when(port.joinByInvitation(eq(TOKEN), any())).thenReturn(SESSION);

        handler.onApplicationEvent(invitationEvent(true));

        verify(port, times(1)).joinByInvitation(eq(TOKEN), any());
        verify(realityLedger).append(eq(COMPANION),
                eq(RealityEventType.APPLICATION_INVITATION_ACCEPTED), any(), any(), any());
    }

    /** 形状不全的信直接丢掉 —— 缺哪一样都不猜。 */
    @Test
    void anIncompleteInvitationEventIsDroppedBeforeItReachesThePort() {
        handler.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("eventType", AgentApplicationInvitationHandler.EVENT_TYPE, "sessionId", SESSION)));

        verifyNoInteractions(port);
        verifyNoInteractions(llmRouter);
    }

    /** 普通应用事件不归它管 —— 哪怕它带着 agentTrigger。 */
    @Test
    void anOrdinaryApplicationEventIsNotItsBusiness() {
        handler.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("eventType", "MOVE", "resourceUri", "app://thing/1", "agentTrigger", true)));

        verifyNoInteractions(port);
        verifyNoInteractions(llmRouter);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private AgentApplicationInvitationHandler.Decision handle() {
        return handler.handle(COMPANION, APP, SESSION, INVITATION, "MEMBER", TOKEN);
    }

    private static ExternalEvent invitationEvent(boolean withToken) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("eventType", AgentApplicationInvitationHandler.EVENT_TYPE);
        payload.put("applicationId", APP);
        payload.put("sessionId", SESSION);
        payload.put("invitationId", INVITATION);
        payload.put("role", "MEMBER");
        payload.put("agentTrigger", true);
        if (withToken) payload.put("token", TOKEN);
        return ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT, payload);
    }

    private void stubLlm(String json) {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class)))
                .thenReturn(new StructuredResult(json, mapper));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
