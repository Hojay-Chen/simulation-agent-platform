package com.luxera.companion.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LAP v1 R2/R7: 数字人与应用平台之间的唯一一条认知链。
 *
 * <p>最要紧的一条是 <b>"LLM 不可用 ⇒ 零次 execute"</b> —— 用户明确要求数字人下棋必须由 LLM
 * 决策, 不许有启发式兜底。把这条断言写死在这里, 就是这次解耦过程中该性质不会被悄悄替换成
 * "随便挑一个空位"的保险丝。
 *
 * <p>R7 加的第二组断言针对主动路径: <b>路由必须能把幻觉挡在门外</b> —— 编造的能力、编造的应用、
 * 编造的动作、以及"候选不止一个却没说选哪个", 四种情况一律不行动。它们同属一个原则: 平台的
 * 校验说了算, 模型的回答只是输入。
 *
 * <p>直接构造 {@code AgentApplicationFlow}(不启 Spring): 用 Mockito 的
 * {@code PersonActorRegistry} 把 mailbox 变成同线程直调, 于是断言是确定性的, 不靠 sleep。
 */
class AgentApplicationFlowTest {

    private static final String COMPANION = "companion-1";
    private static final String USER = "user-1";
    private static final String URI = "game://session/room-1";
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

        // mailbox 同线程直调, 让"订阅 → 反应"这条链在测试里是同步的
        PersonActorRegistry registry = mock(PersonActorRegistry.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(registry).tell(anyString(), any(Runnable.class));

        flow = new AgentApplicationFlow(port, llmRouter, realityLedger, new EventRouter(),
                registry, mock(PersonaService.class), THRESHOLD);
    }

    // ─────────────────────────── LLM 优先, 不降级 ───────────────────────────

    @Test
    void llmUnavailableMeansTheAgentDoesNothing() {
        when(llmRouter.available()).thenReturn(false);
        stubResourceAndPending();

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    @Test
    void mockProviderMeansTheAgentDoesNothing() {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(true);
        stubResourceAndPending();

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 反应路径: 正常 ───────────────────────────

    @Test
    void llmChoiceBecomesExactlyOneExecuteWithTheLlmInput() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":4,"player":"companion"},"reason":"占据中心"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.react(COMPANION, USER, URI, "evt-1");

        ArgumentCaptor<ActionRequest> request = ArgumentCaptor.forClass(ActionRequest.class);
        verify(port, times(1)).execute(request.capture(), any(InvocationContext.class));

        assertEquals("game.make_move", request.getValue().action());
        assertEquals(URI, request.getValue().target());
        assertEquals(4, request.getValue().input().path("position").asInt());
        assertEquals("companion", request.getValue().input().path("player").asText());
        // 乐观并发令牌来自读到的资源版本 —— CAS 在 R4 生效, 但现在就得传对
        assertEquals(1L, request.getValue().expectedResourceVersion());

        verify(realityLedger, times(1))
                .append(eq(COMPANION), eq(RealityEventType.APPLICATION_ACTION_EXECUTED), any(), any(), any());
    }

    @Test
    void llmSayingDoNothingProducesNoExecute() {
        stubResourceAndPending();
        stubLlm("""
                {"input":null,"reason":"局面还不该我动"}
                """);

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    @Test
    void llmMayNameWhichOfSeveralPendingActionsItWants() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(makeMoveSpec(), resignSpec()));
        stubLlm("""
                {"actionId":"game.resign","input":{"reason":"没得下了"},"reason":"认输"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.react(COMPANION, USER, URI, "evt-1");

        ArgumentCaptor<ActionRequest> request = ArgumentCaptor.forClass(ActionRequest.class);
        verify(port, times(1)).execute(request.capture(), any(InvocationContext.class));
        assertEquals("game.resign", request.getValue().action(),
                "候选里有两个动作时, 该执行的是 LLM 选的那个, 不是列表里的第一个");
    }

    // ─────────────────────────── 反应路径: 幻觉挡在门外 ───────────────────────────

    @Test
    void anActionIdThatIsNotPendingMeansNoAction() {
        stubResourceAndPending();
        stubLlm("""
                {"actionId":"game.flip_the_board","input":{"position":4},"reason":"编的"}
                """);

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    @Test
    void severalCandidatesWithNoNamedChoiceMeansNoAction() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(makeMoveSpec(), resignSpec()));
        stubLlm("""
                {"input":{"position":4},"reason":"没说选哪个"}
                """);

        flow.react(COMPANION, USER, URI, "evt-1");

        // 候选不止一个而 LLM 没说是哪个 —— 替它补一个就是启发式
        verify(port, never()).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 反应路径: 边界 ───────────────────────────

    @Test
    void nothingPendingMeansNoLlmCallAtAll() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of());

        flow.react(COMPANION, USER, URI, "evt-1");

        verifyNoInteractions(llmRouter);
        verify(port, never()).execute(any(), any());
    }

    @Test
    void missingResourceMeansNothingHappens() {
        when(port.read(URI)).thenReturn(Optional.empty());

        flow.react(COMPANION, USER, URI, "evt-1");

        verifyNoInteractions(llmRouter);
        verify(port, never()).execute(any(), any());
    }

    @Test
    void rejectedActionIsNotWrittenToTheLedger() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":4,"player":"companion"},"reason":"占据中心"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.failure(
                com.luxera.companion.contracts.application.ActionStatus.STATE_CONFLICT,
                "STATE_CONFLICT", "版本已变"));

        flow.react(COMPANION, USER, URI, "evt-1");

        verify(port, times(1)).execute(any(), any());
        verifyNoInteractions(realityLedger);
    }

    // ─────────────────────────── 事件入口过滤 ───────────────────────────

    @Test
    void agentTriggerFalseIsIgnoredBeforeAnyRead() {
        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("resourceUri", URI, "agentTrigger", false)));

        verifyNoInteractions(port);
    }

    @Test
    void agentTriggerTrueReachesThePort() {
        stubResourceAndPending();
        stubLlm("""
                {"input":{"position":0,"player":"companion"},"reason":"占角"}
                """);
        when(port.execute(any(), any())).thenReturn(ActionResponse.success(mapper.createObjectNode(), null));

        flow.onApplicationEvent(ExternalEvent.of(COMPANION, ExternalEventType.APPLICATION_EVENT,
                Map.of("resourceUri", URI, "userId", USER, "agentTrigger", true)));

        verify(port, times(1)).execute(any(), any());
    }

    // ═══════════════════ 主动路径: 意图 → 能力 → 应用 (R7) ═══════════════════

    @Test
    void aConfidentCapabilityBecomesAnIntent() {
        stubCatalogue();
        when(port.applicationsFor("game.play")).thenReturn(List.of(application("com.luxera.tictactoe")));
        stubLlm("""
                {"capability":"game.play","confidence":0.9,"reason":"用户想下棋"}
                """);

        Optional<AgentApplicationFlow.Intent> intent = flow.route(COMPANION, "陪我下盘棋");

        assertTrue(intent.isPresent());
        assertEquals("game.play", intent.get().capabilityId());
        assertEquals("com.luxera.tictactoe", intent.get().applicationId());
        assertEquals(0.9, intent.get().confidence(), 1e-9);
    }

    @Test
    void confidenceBelowTheThresholdMeansNoApplicationIsChosen() {
        stubCatalogue();
        stubLlm("""
                {"capability":"game.play","confidence":0.4,"reason":"也许吧"}
                """);

        assertTrue(flow.route(COMPANION, "今天天气不错").isEmpty());
        verify(port, never()).applicationsFor(anyString());
    }

    @Test
    void aCapabilityNotInTheCatalogueIsTreatedAsNoChoice() {
        stubCatalogue();
        stubLlm("""
                {"capability":"weather.control","confidence":0.99,"reason":"编的"}
                """);

        assertTrue(flow.route(COMPANION, "把天气改一改").isEmpty());
        verify(port, never()).applicationsFor(anyString());
    }

    @Test
    void noCapabilityMeansNoApplication() {
        stubCatalogue();
        stubLlm("""
                {"capability":null,"confidence":0,"reason":"闲聊而已"}
                """);

        assertTrue(flow.route(COMPANION, "我今天有点累").isEmpty());
    }

    @Test
    void anEmptyCatalogueMeansTheLlmIsNeverAsked() {
        when(port.capabilities()).thenReturn(List.of());

        assertTrue(flow.route(COMPANION, "提醒我三点开会").isEmpty());
        verifyNoInteractions(llmRouter);
    }

    @Test
    void aSingleCandidateIsChosenWithoutAskingTheLlmAgain() {
        stubCatalogue();
        when(port.applicationsFor("game.play")).thenReturn(List.of(application("com.luxera.tictactoe")));
        stubLlm("""
                {"capability":"game.play","confidence":0.9,"reason":"用户想下棋"}
                """);

        assertTrue(flow.route(COMPANION, "陪我下棋").isPresent());
        // 只有一次 LLM 调用: 能力选择。一个候选的"选择"没有信息量, 不该再花一次。
        verify(llmRouter, times(1)).structured(any(StructuredRequest.class));
    }

    @Test
    void severalCandidatesAreHandedToTheLlmAndItsAnswerIsHonoured() {
        stubCatalogue();
        when(port.applicationsFor("game.play")).thenReturn(
                List.of(application("com.luxera.tictactoe"), application("com.luxera.gomoku")));
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class))).thenReturn(
                new StructuredResult("{\"capability\":\"game.play\",\"confidence\":0.9,\"reason\":\"想下棋\"}", mapper),
                new StructuredResult("{\"applicationId\":\"com.luxera.gomoku\",\"reason\":\"他说五子棋\"}", mapper));

        Optional<AgentApplicationFlow.Intent> intent = flow.route(COMPANION, "来盘五子棋");

        assertTrue(intent.isPresent());
        assertEquals("com.luxera.gomoku", intent.get().applicationId());
    }

    @Test
    void anApplicationTheLlmInventedIsRefused() {
        stubCatalogue();
        when(port.applicationsFor("game.play")).thenReturn(
                List.of(application("com.luxera.tictactoe"), application("com.luxera.gomoku")));
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class))).thenReturn(
                new StructuredResult("{\"capability\":\"game.play\",\"confidence\":0.9,\"reason\":\"想下棋\"}", mapper),
                new StructuredResult("{\"applicationId\":\"com.luxera.chess\",\"reason\":\"编的\"}", mapper));

        assertTrue(flow.route(COMPANION, "下一盘国际象棋").isEmpty());
    }

    @Test
    void theCapabilityCatalogueFingerprintChangesWithTheCatalogue() {
        String one = AgentApplicationFlow.hash(List.of(capability("game.play")));
        String two = AgentApplicationFlow.hash(List.of(capability("game.play"), capability("reminder.manage")));
        String reordered = AgentApplicationFlow.hash(List.of(capability("reminder.manage"), capability("game.play")));

        assertNotEquals(one, two, "目录变了指纹必须变");
        assertEquals(two, reordered, "顺序变了内容没变 —— 指纹不该变, 否则它就没有意义");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private void stubResourceAndPending() {
        when(port.read(URI)).thenReturn(Optional.of(resource()));
        when(port.pendingActions(eq(URI), any())).thenReturn(List.of(makeMoveSpec()));
    }

    private void stubCatalogue() {
        when(port.capabilities()).thenReturn(List.of(capability("game.play"), capability("reminder.manage")));
    }

    private void stubLlm(String json) {
        when(llmRouter.available()).thenReturn(true);
        when(llmRouter.isMockActive()).thenReturn(false);
        when(llmRouter.structured(any(StructuredRequest.class)))
                .thenReturn(new StructuredResult(json, mapper));
    }

    private static CapabilityView capability(String id) {
        return new CapabilityView(id, id, "描述 " + id, id.split("\\.")[0]);
    }

    private static ApplicationView application(String id) {
        return new ApplicationView(id, "1.0.0", id, "描述", "game", List.of("game.play"));
    }

    private ResourceView resource() {
        JsonNode state = mapper.valueToTree(Map.of(
                "board", List.of("X", "", "", "", "O", "", "", "", ""),
                "turn", "O", "winner", ""));
        return new ResourceView(URI, "game.session", "tictactoe", "room-1", state, 1L, Instant.now(), null);
    }

    private ActionSpec makeMoveSpec() {
        JsonNode schema = mapper.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of("position", Map.of("type", "integer"))));
        return new ActionSpec("game.make_move", "tictactoe", "game.play", "落子",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.FOCUSED, schema,
                "能三连就三连, 否则阻断对手");
    }

    private ActionSpec resignSpec() {
        return new ActionSpec("game.resign", "tictactoe", "game.play", "认输",
                PermissionLevel.WRITE, RiskLevel.LOW, AttentionPolicy.FOCUSED, null, null);
    }
}
