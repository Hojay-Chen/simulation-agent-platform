package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.contracts.application.ActionResponse;
import com.luxera.companion.contracts.application.ActionSpec;
import com.luxera.companion.contracts.application.ActionStatus;
import com.luxera.companion.contracts.application.AttentionPolicy;
import com.luxera.companion.contracts.application.InvocationContext;
import com.luxera.companion.contracts.application.PermissionLevel;
import com.luxera.companion.contracts.application.ResourceView;
import com.luxera.companion.contracts.application.RiskLevel;
import com.luxera.companion.contracts.application.SessionRef;
import com.luxera.companion.contracts.spi.ApplicationRuntimePort;
import com.luxera.companion.digitalhuman.actor.PersonActorRegistry;
import com.luxera.companion.digitalhuman.event.EventRouter;
import com.luxera.companion.digitalhuman.event.ExternalEvent;
import com.luxera.companion.digitalhuman.event.ExternalEventType;
import com.luxera.companion.digitalhuman.reality.RealityLedger;
import com.luxera.companion.llm.LlmRouter;
import com.luxera.companion.llm.StructuredRequest;
import com.luxera.companion.llm.StructuredResult;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.runtime.application.AgentApplicationInvitationHandler;
import com.luxera.companion.runtime.application.SessionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * LAP v2 R13: <b>八段流水线本身</b> —— 停在哪一段, 以及"定位不到会话"为什么不是失败。
 *
 * <p>{@code AgentApplicationFlowTest} 的 21 条钉的是<em>行为</em>(不降级、幻觉挡在门外、
 * 候选不止一个就不许含糊), 是 R2/R7 留下的保险丝, 一条都不许动。这一组钉的是 R13 新加的
 * <em>结构</em>: 每一段各自负责什么, 以及 {@link AgentApplicationFlow.PipelineReport} 说出来的
 * 那句话是不是真的。
 *
 * <p>最要紧的一条是 {@link #withoutASessionThePipelineStillRuns()}: 定位会话是一个
 * <b>尽力而为的富化</b>, 不是闸门。把它做成闸门, 那些资源上不挂会话的应用(提醒收件箱就是)
 * 的事件会从此一个人也唤不醒 —— 而所有既有断言仍然全绿。
 */
class AgentApplicationPipelineTest {

    private static final String COMPANION = "companion-1";
    private static final String USER = "user-1";
    private static final String URI = "example://thing/1";
    private static final String APP = "com.luxera.example";
    private static final double THRESHOLD = 0.6;

    private final ObjectMapper mapper = new ObjectMapper();

    private ApplicationRuntimePort port;
    private LlmRouter llmRouter;
    private RealityLedger realityLedger;
    private AgentApplicationFlow flow;

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

        flow = new AgentApplicationFlow(port, llmRouter, realityLedger, new EventRouter(),
                registry, mock(PersonaService.class), THRESHOLD);
    }

    // ─────────────────────────── 停在哪一段 ───────────────────────────

    @Test
    void aMissingResourceStopsAtRead() {
        when(port.read(URI)).thenReturn(Optional.empty());

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.READ, report.stoppedAt());
        assertEquals("RESOURCE_NOT_FOUND", report.reason());
        verifyNoInteractions(llmRouter);
    }

    @Test
    void nothingPendingStopsAtPending() {
        when(port.read(URI)).thenReturn(Optional.of(resource("s-1")));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of());

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.PENDING, report.stoppedAt());
        assertEquals("NOTHING_PENDING", report.reason());
        verifyNoInteractions(llmRouter);
    }

    @Test
    void anUnavailableLlmStopsAtEligible() {
        stubResourceAndPending("s-1");
        when(llmRouter.available()).thenReturn(false);

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.ELIGIBLE, report.stoppedAt());
        assertEquals("LLM_UNAVAILABLE", report.reason());
        verify(port, never()).execute(any(), any());
    }

    @Test
    void aRefusedActionStopsAtExecuteAndCarriesTheStatus() {
        stubResourceAndPending("s-1");
        stubLlm("{\"input\":{\"value\":1},\"reason\":\"做\"}");
        when(port.execute(any(), any())).thenReturn(ActionResponse.failure(
                ActionStatus.STATE_CONFLICT, "STATE_CONFLICT", "版本已变"));

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.EXECUTE, report.stoppedAt());
        assertEquals("REFUSED:STATE_CONFLICT", report.reason());
        assertNull(report.actionId(), "被拒的动作不算做过");
        verifyNoInteractions(realityLedger);
    }

    @Test
    void aCompletedRunStopsAtRecordWithTheActionId() {
        stubResourceAndPending("s-1");
        stubLlm("{\"input\":{\"value\":1},\"reason\":\"做\"}");
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.RECORD, report.stoppedAt());
        assertEquals("example.act", report.actionId());
        assertEquals("s-1", report.sessionId());
        assertEquals(SessionResolver.Strategy.RESOURCE, report.strategy());
    }

    // ─────────────────────────── 会话是怎么定下来的 ───────────────────────────

    /**
     * 事件 payload 里点名的那一场, 要真的被钉进交给平台的上下文里。
     *
     * <p>钉与不钉的分别是"平台按我指的办"与"平台自己猜一个" —— 后者在同一个应用里开了好几场时
     * 会猜错, 而猜错的表现是数字人对着另一场说了一句莫名其妙的话。
     */
    @Test
    void theSessionNamedByTheEventIsPinnedIntoTheContext() {
        when(port.read(URI)).thenReturn(Optional.of(resource("s-from-resource")));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(spec()));
        stubLlm("{\"input\":{\"value\":1},\"reason\":\"做\"}");
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("resourceUri", URI, "userId", USER, "sessionId", "s-from-event", "agentTrigger", true)));

        ArgumentCaptor<InvocationContext> ctx = ArgumentCaptor.forClass(InvocationContext.class);
        verify(port).pendingActions(eq(URI), ctx.capture());
        assertEquals("s-from-event", ctx.getValue().sessionId(),
                "payload 点名的会话赢过资源行上的那个");
    }

    /**
     * <b>定位不到会话不是失败</b> —— 该做的还是照做。
     *
     * <p>提醒收件箱那种资源上根本不挂会话(它的 {@code sessionId} 一直是 null), 而网关自己还有
     * 三档兜底。在这里拦一道, 那些应用的事件就一个人也唤不醒了。
     */
    @Test
    void withoutASessionThePipelineStillRuns() {
        when(port.read(URI)).thenReturn(Optional.of(resource(null)));
        when(port.sessionsOf(eq(APP), any())).thenReturn(List.of());
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(spec()));
        stubLlm("{\"input\":{\"value\":1},\"reason\":\"做\"}");
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        AgentApplicationFlow.PipelineReport report = react();

        assertEquals(AgentApplicationFlow.Stage.RECORD, report.stoppedAt());
        assertNull(report.sessionId());
        assertEquals(SessionResolver.Strategy.NONE, report.strategy());
        verify(port, times(1)).execute(any(), any());
    }

    /** 平台说"你此刻就在这一场里" —— 第三条策略真的会被走到。 */
    @Test
    void anOngoingSessionIsFoundWhenNothingElsePointsAtOne() {
        when(port.read(URI)).thenReturn(Optional.of(resource(null)));
        when(port.sessionsOf(eq(APP), any())).thenReturn(List.of(
                new SessionRef("s-ongoing", APP, "ACTIVE", "UNLISTED", "INVITE_ONLY",
                        2, 8, true, "HUMAN", "someone", null)));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of());

        assertEquals(SessionResolver.Strategy.PARTICIPATING, react().strategy());
    }

    // ─────────────────────────── 邀请不走这条路 ───────────────────────────

    /**
     * 邀请事件也是 {@code APPLICATION_EVENT}, 也带着 {@code agentTrigger} ——
     * 但它不是"应用里发生了什么", 是"有人点名找我"。反应路径必须在入口处让开,
     * 否则它会把 {@code session://invitation/...} 当成一个资源去读, 而那个东西根本不存在。
     */
    @Test
    void anInvitationEventNeverReachesTheReactionPath() {
        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("eventType", AgentApplicationInvitationHandler.EVENT_TYPE,
                        "resourceUri", "session://invitation/s-1/inv-1",
                        "sessionId", "s-1", "token", "tok-x", "agentTrigger", true)));

        verifyNoInteractions(port);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private AgentApplicationFlow.PipelineReport react() {
        return flow.react(COMPANION, USER, URI, "evt-1");
    }

    private void stubResourceAndPending(String sessionId) {
        when(port.read(URI)).thenReturn(Optional.of(resource(sessionId)));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(spec()));
    }

    private void stubLlm(String json) {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class)))
                .thenReturn(new StructuredResult(json, mapper));
    }

    private ResourceView resource(String sessionId) {
        JsonNode state = mapper.valueToTree(Map.of("value", 0));
        return new ResourceView(URI, "example.thing", APP, sessionId, state, 1L, Instant.now(), null);
    }

    private ActionSpec spec() {
        JsonNode schema = mapper.valueToTree(new LinkedHashMap<>(Map.of("type", "object")));
        return new ActionSpec("example.act", APP, "example.do", "做一件事",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.FOCUSED, schema, null);
    }
}
