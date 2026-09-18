package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 开关的**硬闸** —— {@code LlmRouter} 的三个出口。
 *
 * <h2>为什么这一层是"唯一的保证"</h2>
 *
 * 入口闸(邮箱任务体 / SSE 路径 / 事件链)与批处理闸({@code findRunnable()})都可能被将来的
 * 一条新路径绕开 —— 加一个定时任务、加一个 controller, 忘了接闸门不会编不过。而这一层
 * 绕不开: 全平台 28 处 LLM 调用点分属十几个类、由十几个触发器驱动, 但**没有任何一个类
 * 注入 {@code LlmGateway}**, 全部注入的都是本类。要在不再穿过这里的前提下烧掉 token,
 * 只能新写一条完全独立的 LLM 通道 —— 那件事会有它自己的 review。
 *
 * <p>所以这组测试守的是**钱**: 每一条失败都对应"一个我以为停掉的 agent 正在持续调用模型"。
 *
 * <h2>为什么拦下时必须返回空结果而不是抛异常</h2>
 *
 * 各调用方对 LLM 失败的处理是 {@code catch} → 回退到启发式默认值。抛异常的话, 一个被暂停
 * 的 agent 会**继续做完整条判断**, 只是没有模型参与 —— 那不是"停下来", 而是"用更笨的方式
 * 继续跑"。空结果让解析全部落到"取不到就返回空"的分支上, 链路安静地结束。
 */
class LlmRouterGateTest {

    private static final String AGENT = "companion-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private MockLlmGateway gateway;
    private LlmCallService llmCallService;
    private com.luxera.companion.persona.AgentSwitchService agentSwitch;
    private LlmRouter router;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        AppProperties.Llm llm = new AppProperties.Llm();
        llm.setProvider("mock");
        llm.setChatModel("chat-model");
        props.setLlm(llm);

        gateway = mock(MockLlmGateway.class);
        llmCallService = mock(LlmCallService.class);
        agentSwitch = mock(com.luxera.companion.persona.AgentSwitchService.class);
        router = new LlmRouter(props, mock(OpenAiCompatibleGateway.class),
                mock(AnthropicGateway.class), gateway, llmCallService, agentSwitch, mapper);
        router.init();

        when(gateway.chat(any())).thenReturn(new ChatResult("你好", "chat-model", 3, 4, "mock"));
        when(gateway.structured(any())).thenReturn(new StructuredResult("{\"ok\":true}", mapper));
    }

    // ─────────────── 暂停: 一个字节都不出去 ───────────────

    @Test
    void aPausedAgentNeverReachesTheGatewayOnChat() {
        paused();

        ChatResult r = router.chat(chatRequest(AGENT));

        verify(gateway, never()).chat(any());
        assertNotNull(r, "被拦下要返回空结果而不是 null —— 调用方会直接拼它");
        assertEquals("", r.getContent());
        assertEquals(0, r.getPromptTokens() + r.getCompletionTokens(),
                "拦下的调用在账上必须是 0 token, 否则统计会显示一个停着的 agent 还在花钱");
    }

    @Test
    void aPausedAgentGetsNoDeltasOnTheStreamingPath() {
        // SSE 路径不经过邮箱, 是"入口闸"覆盖不到的一条 —— 这里必须拦住。
        // 断言是 "一次回调都没有", 不是 "回调了一个空串": 将来若有调用方按 delta 个数
        // 计事, 前者不会骗它。
        paused();

        List<String> deltas = new ArrayList<>();
        router.chatStream(chatRequest(AGENT), deltas::add);

        verify(gateway, never()).chatStream(any(), any());
        assertTrue(deltas.isEmpty(), "被拦下的流不该产生任何 delta");
    }

    @Test
    void aPausedAgentGetsAnEmptyObjectNotAnExceptionOnStructured() {
        paused();

        StructuredResult r = router.structured(structuredRequest(AGENT, "perception"));

        verify(gateway, never()).structured(any());
        assertEquals("{}", r.getRaw(), "空对象让各 resolver 走「取不到就返回空」的分支, 链路安静结束");
    }

    @Test
    void aBlockedCallIsNotWrittenToTheLedgerButIsCountedInstead() {
        // llm_calls 是"花了多少"的账本。一笔没花出去的钱记进去只会把账搅浑
        // (110 个 agent × 48 次/天 = 每天五千多行噪音)。改成内存计数, 在运维统计里露出来。
        paused();

        router.chat(chatRequest(AGENT));
        router.structured(structuredRequest(AGENT, "perception"));

        verify(llmCallService, never()).record(anyString(), any(), any(), any(), any(), anyLong(),
                anyString(), any());
        verify(agentSwitch).noteBlocked("chat");
        verify(agentSwitch).noteBlocked("perception");
    }

    // ─────────────── 运行中: 一个字节都不该少 ───────────────

    @Test
    void anActiveAgentPassesThroughOnAllThreeExits() {
        when(agentSwitch.isRunnable(any())).thenReturn(true);

        assertEquals("你好", router.chat(chatRequest(AGENT)).getContent());

        List<String> deltas = new ArrayList<>();
        router.chatStream(chatRequest(AGENT), deltas::add);
        verify(gateway).chatStream(any(), any());

        assertEquals("{\"ok\":true}", router.structured(structuredRequest(AGENT, "perception")).getRaw());
        // 放行的调用照常记账 —— 闸门的存在不该让账本少掉正常的那部分。
        // 三条出口各记一笔(chat / chat_stream / structured)。
        verify(llmCallService, org.mockito.Mockito.times(3))
                .record(anyString(), any(), any(), any(), any(), anyLong(), eq("success"), any());
    }

    // ─────────────── 放行的两种情形 ───────────────

    @Test
    void aCallWithoutACompanionIdIsNotGated() {
        // 用户当场发起的调用(编译人格、应用链路)不带 companionId。它们的 isRunnable 由
        // AgentSwitchService 判为 true —— 这里要钉的是**路由把 null 原样交出去了**,
        // 而不是自己造一个 id 或直接拦掉。
        when(agentSwitch.isRunnable(null)).thenReturn(true);

        assertEquals("你好", router.chat(chatRequest(null)).getContent());
        verify(agentSwitch).isRunnable(null);
    }

    @Test
    void aRequestWithNoMetadataAtAllDoesNotBlowUp() {
        // ChatRequest.metadata 可以是 null(直接 new 出来的对象没有 metadata)。这条路径上的
        // NPE 会让每一次无 metadata 的调用炸掉 —— 那是一个与被暂停的 agent 毫无关系的故障。
        when(agentSwitch.isRunnable(null)).thenReturn(true);

        ChatRequest bare = ChatRequest.builder()
                .messages(List.of(LlmMessage.system("s"), LlmMessage.user("u")))
                .build();

        assertEquals("你好", router.chat(bare).getContent());
    }

    // ─────────────── 夹具 ───────────────

    /** 把替身设成"这个 agent 已暂停"。 */
    private void paused() {
        when(agentSwitch.isRunnable(AGENT)).thenReturn(false);
    }

    private static ChatRequest chatRequest(String companionId) {
        ChatRequest.ChatRequestBuilder b = ChatRequest.builder()
                .messages(List.of(LlmMessage.system("sys"), LlmMessage.user("usr")));
        if (companionId != null) b.metadata(Map.of("companionId", companionId));
        return b.build();
    }

    private static StructuredRequest structuredRequest(String companionId, String task) {
        StructuredRequest.StructuredRequestBuilder b = StructuredRequest.builder()
                .system("sys").user("usr").task(task);
        if (companionId != null) b.metadata(Map.of("companionId", companionId));
        return b.build();
    }
}
