package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LAP v1 R7 —— 用途路由({@code app.llm.purpose.*})的三条"不覆盖"规矩。
 *
 * <p>这三条在改之前都是<b>靠运气</b>成立的: {@code application.yml} 里当时只配了
 * {@code summary} 一个块, 所以未登记的 task 走到那个"默认 extraction"分支时查不到配置块、
 * 原样返回 —— 行为对, 但理由是"恰好没配"。一旦有人给 {@code extraction} 配上模型, 所有
 * 没登记的 task 都会悄悄换模型, 而且没人会知道。这个类把运气换成断言:
 *
 * <ol>
 *   <li>不认识的 task 原样通过(同一个对象, 不是"复制一份再改回去")</li>
 *   <li>调用方自己设了 model, 就不该被用途配置覆盖</li>
 *   <li>metadata 必须跟着走到网关、也走到 {@code llm_calls} —— 第三条最隐蔽:
 *       {@code LlmCallService.record} 在 {@code companionId == null} 时<b>静默 return</b>,
 *       于是"这次调用没落库"的表现是"表里什么都没有", 而不是一条报错。</li>
 * </ol>
 *
 * <p>用 Mockito 的 {@code LlmCallService} 而不是真库: 这里要断言的是"路由把什么交给了它",
 * 不是"它写库成不成功"。真实落库由端到端链路覆盖。
 */
class LlmRouterPurposeTest {

    private static final String COMPANION = "companion-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private MockLlmGateway gateway;
    private LlmCallService llmCallService;
    private LlmRouter router;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        AppProperties.Llm llm = new AppProperties.Llm();
        llm.setProvider("mock");
        llm.setChatModel("chat-model");
        Map<String, AppProperties.Purpose> purposes = new HashMap<>();
        // 只配「应用链路」这一个块。missing 的那些用途 key 故意不配 —— 这正是第 1 条要测的。
        purposes.put("application", purpose("application-model", 0.1));
        llm.setPurpose(purposes);
        props.setLlm(llm);

        gateway = mock(MockLlmGateway.class);
        llmCallService = mock(LlmCallService.class);
        router = new LlmRouter(props, mock(OpenAiCompatibleGateway.class),
                mock(AnthropicGateway.class), gateway, llmCallService);
        router.init();

        when(gateway.structured(any())).thenReturn(new StructuredResult("{}", mapper));
    }

    // ─────────────────── 1. 用途配置生效 ───────────────────

    @Test
    void theApplicationPurposeSuppliesTheModelButNotTheTemperatureTheCallerSet() {
        StructuredRequest sent = capture(request("application-action-selection", null, 0.3, Map.of()));

        assertEquals("application-model", sent.getModel(), "应用链路该用 purpose.application 的模型");
        assertEquals(0.3, sent.getTemperature(), 1e-9,
                "调用方明确给了温度就以它为准 —— 用途块只补缺, 不覆盖");
    }

    @Test
    void theApplicationPurposeSuppliesTheTemperatureWhenTheCallerDidNot() {
        StructuredRequest sent = capture(request("application-capability-selection", null, null, Map.of()));

        assertEquals(0.1, sent.getTemperature(), 1e-9);
    }

    // ─────────────────── 2. 不认识的任务原样通过 ───────────────────

    @Test
    void anUnknownTaskIsPassedThroughUntouched() {
        StructuredRequest original = request("something-nobody-registered", "my-model", 0.77, Map.of());

        assertSame(original, capture(original),
                "未登记的 task 必须原样返回 —— 尤其不能悄悄套上别的用途的模型/温度");
    }

    @Test
    void aKnownTaskWhosePurposeBlockIsMissingIsAlsoPassedThroughUntouched() {
        // session-summary 是登记过的 task(映射到 summary 用途), 但 summary 块没配。
        StructuredRequest original = request("session-summary", "my-model", 0.5, Map.of());

        assertSame(original, capture(original));
    }

    // ─────────────────── 3. metadata 必须活着到达 ───────────────────

    @Test
    void metadataSurvivesRoutingAndReachesTheCallLog() {
        Map<String, String> meta = Map.of("companionId", COMPANION, "purpose", "application");

        StructuredRequest sent = capture(request("application-action-selection", null, 0.3, meta));

        assertEquals(COMPANION, sent.getMetadata().get("companionId"),
                "路由重建了请求, metadata 掉了的话日志里就只剩一条空调用");
        assertMetadataRecorded(COMPANION);
    }

    @Test
    void metadataSurvivesEvenWhenTheRequestIsPassedThrough() {
        Map<String, String> meta = Map.of("companionId", COMPANION, "purpose", "other");

        capture(request("something-nobody-registered", null, 0.3, meta));

        assertMetadataRecorded(COMPANION);
    }

    @Test
    void aCallWithoutACompanionIdIsStillPassedToTheLogService() {
        // 跳过记库是 LlmCallService 的职责, 不是路由的 —— 路由不能替它做这个决定,
        // 否则"谁在什么时候打了什么模型"这件事就被一个中间层吞掉了。
        capture(request("application-action-selection", null, 0.3, Map.of()));

        verify(llmCallService, times(1)).record(eq("structured"), any(), any(), any(), any(),
                anyLong(), eq("success"), any());
    }

    // ─────────────────── 夹具 ───────────────────

    private static AppProperties.Purpose purpose(String model, double temperature) {
        AppProperties.Purpose p = new AppProperties.Purpose();
        p.setModel(model);
        p.setTemperature(temperature);
        return p;
    }

    private static StructuredRequest request(String task, String model, Double temperature,
                                             Map<String, String> metadata) {
        return StructuredRequest.builder()
                .system("sys").user("usr").task(task)
                .model(model).temperature(temperature).metadata(metadata)
                .build();
    }

    private StructuredRequest capture(StructuredRequest request) {
        router.structured(request);
        ArgumentCaptor<StructuredRequest> captor = ArgumentCaptor.forClass(StructuredRequest.class);
        verify(gateway, atLeastOnce()).structured(captor.capture());
        return captor.getValue();
    }

    private void assertMetadataRecorded(String companionId) {
        ArgumentCaptor<Map<String, String>> meta = ArgumentCaptor.forClass(Map.class);
        verify(llmCallService, times(1)).record(any(), any(), any(), any(), any(),
                anyLong(), any(), meta.capture());
        assertEquals(companionId, meta.getValue().get("companionId"),
                "companionId 到不了 LlmCallService 的话, 这次调用在 llm_calls 里根本不存在");
    }
}
