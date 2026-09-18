package com.luxera.companion.llm;

import com.luxera.companion.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.function.Consumer;

/**
 * LLM 路由: 根据配置选择实际网关。
 * 未配置 key 且 mock-fallback=true 时自动降级 Mock,保证离线可跑通全流程。
 */
@Slf4j
@Component
public class LlmRouter implements LlmGateway {

    private final AppProperties props;
    private final OpenAiCompatibleGateway openAi;
    private final AnthropicGateway anthropic;
    private final MockLlmGateway mock;
    private final LlmCallService llmCallService;
    private final com.luxera.companion.persona.AgentSwitchService agentSwitch;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    private LlmGateway active;

    public LlmRouter(AppProperties props, OpenAiCompatibleGateway openAi,
                     AnthropicGateway anthropic, MockLlmGateway mock,
                     LlmCallService llmCallService,
                     com.luxera.companion.persona.AgentSwitchService agentSwitch,
                     com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.props = props;
        this.openAi = openAi;
        this.anthropic = anthropic;
        this.mock = mock;
        this.llmCallService = llmCallService;
        this.agentSwitch = agentSwitch;
        this.mapper = mapper;
    }

    // ── Agent 开关的硬闸 ─────────────────────────────────────
    //
    // 这是"暂停一个 agent"这件事的**唯一保证**。理由很实在: 全平台 28 处 LLM 调用点
    // (感知/情绪/记忆/决策/表达/回复/反省/事件模拟/应用链路…) 分属十几个类、由十几个
    // 不同的触发器驱动, 但**无一例外**都要穿过本类的这三个方法 —— 因为没有任何一个类
    // 注入 LlmGateway, 全部注入的都是本类。
    //
    // 另外两层闸门(AgentRuntime 的入口闸、定时任务的批处理闸)省的是无用功: 少了它们
    // 也会拦住 token, 但每 30 分钟仍会有一批任务白跑一遍数据库和计算。这一层则是在
    // 最靠近钱包的地方说"不"。它同时兜住将来新加的、忘了接闸门的路径。
    //
    // 拦下的调用**不写 llm_calls**: 那张表是"花了多少"的账本, 而一笔没花出去的钱
    // 记进去只会把账搅浑(110 个 agent × 48 次/天 = 每天五千多行噪音)。记账改成
    // 内存计数器, 在 AgentSwitchService 的统计里露出来。

    /**
     * 这次调用属于哪个 agent —— 从 metadata 里取。
     *
     * <p>取不到就是 {@code null}, 而 {@code null} 的语义是"这不是某个 agent 的开销"
     * (用户当场发起的人格编译、应用链路的三个 resolver), 见
     * {@link com.luxera.companion.persona.AgentSwitchService#isRunnable(String)}。
     */
    private static String companionIdOf(java.util.Map<String, String> meta) {
        return meta == null ? null : meta.get("companionId");
    }

    /** 这次调用该不该被拦下。被拦时顺手记一笔内存计数, 供运维确认开关真的在生效。 */
    private boolean blocked(String companionId, String task) {
        if (agentSwitch == null || agentSwitch.isRunnable(companionId)) return false;
        agentSwitch.noteBlocked(task);
        log.debug("[LLM] agent {} 已暂停, 拦下一次 {} 调用(未发出网络请求)", companionId, task);
        return true;
    }

    /** 被拦下时的空回复。内容是空的, 但**不能是 null** —— 调用方会直接拼它。 */
    private static ChatResult blockedChat() {
        return new ChatResult("", "paused", 0, 0, "paused");
    }

    /**
     * 被拦下时的结构化结果: 一个空对象 {@code {}}。
     *
     * <p>刻意**不是**抛异常, 也刻意不是 {@code null}: 抛异常会被各调用方的
     * {@code catch} 吃成"LLM 失败"然后回退到各自的启发式默认值, 于是一个被暂停的
     * agent 反而**继续做完了整条判断**, 只是没有模型参与 —— 那不是"停下来"。
     * {@code {}} 让 {@code path(...)} 全部取到 missing node, 各 resolver 的
     * "取不到就返回空"分支自然生效, 链路安静地结束。
     */
    private StructuredResult blockedStructured() {
        return new StructuredResult("{}", mapper);
    }

    @PostConstruct
    void init() {
        String provider = props.getLlm().getProvider();
        switch (provider == null ? "" : provider) {
            case "mock" -> active = mock;
            case "anthropic" -> {
                if (anthropic.available()) {
                    active = anthropic;
                } else if (props.getLlm().isMockFallback()) {
                    log.warn("[LLM] anthropic 未配置 api-key,降级为 mock 网关");
                    active = mock;
                } else {
                    throw new IllegalStateException("LLM provider=anthropic 但未配置 app.llm.api-key");
                }
            }
            case "openai-compatible" -> {
                if (openAi.available()) {
                    active = openAi;
                } else if (props.getLlm().isMockFallback()) {
                    log.warn("[LLM] openai-compatible(DeepSeek) 未配置 DEEPSEEK_API_KEY,降级为 mock 网关。配置后自动切换真实模型。");
                    active = mock;
                } else {
                    throw new IllegalStateException("LLM provider=openai-compatible 但未配置 app.llm.api-key");
                }
            }
            default -> throw new IllegalStateException("未知 LLM provider: " + provider);
        }
        log.info("[LLM] 网关已启用: {} (provider={})", active.name(), provider);
    }

    @Override public String name() { return active.name(); }
    @Override public boolean available() { return active.available(); }

    public boolean isMockActive() { return active == mock; }
    public String activeProvider() { return active.name(); }

    @Override
    public ChatResult chat(ChatRequest request) {
        if (blocked(companionIdOf(request.getMetadata()), "chat")) return blockedChat();
        long t0 = System.currentTimeMillis();
        ChatResult r = active.chat(request);
        llmCallService.record("chat", r.getProvider(), r.getModel(),
                r.getPromptTokens(), r.getCompletionTokens(),
                System.currentTimeMillis() - t0, "success", request.getMetadata());
        return r;
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
        // 被拦下时**一个 delta 都不发** —— 不是发一个空串。收流的那一侧是
        // `raw.append(delta)`, 两者对 StringBuilder 的结果相同, 但"什么都没发生"比
        // "发生了一次空的流"更贴近事实, 而将来若有调用方按 delta 个数计事, 前者不会骗它。
        if (blocked(companionIdOf(request.getMetadata()), "chat_stream")) return;
        // 流式: 结束后记录(流式接口不返回 token 用量, 记录延迟/路径/hash 用于观测)
        long t0 = System.currentTimeMillis();
        try {
            active.chatStream(request, onDelta);
            llmCallService.record("chat_stream", active.name(), active.name(),
                    null, null, System.currentTimeMillis() - t0, "success", request.getMetadata());
        } catch (Exception e) {
            llmCallService.record("chat_stream", active.name(), active.name(),
                    null, null, System.currentTimeMillis() - t0, "error", request.getMetadata());
            throw e;
        }
    }

    @Override
    public StructuredResult structured(StructuredRequest request) {
        // 闸门在用途路由**之前**: 被拦下的调用不该再走一遍配置解析
        if (blocked(companionIdOf(request.getMetadata()), request.getTask())) return blockedStructured();
        // 模型用途路由(设计文档 §25): 按 task 指定模型/温度, 缺省用 chat-model
        StructuredRequest routed = applyPurpose(request);
        long t0 = System.currentTimeMillis();
        StructuredResult r = active.structured(routed);
        llmCallService.record("structured", active.name(), routed.getModel() != null ? routed.getModel() : active.name(),
                null, null, System.currentTimeMillis() - t0, "success", request.getMetadata());
        return r;
    }

    /**
     * 按任务类型应用用途路由(感知/抽取用轻模型, 反思/演化用强模型等)。
     *
     * <p>三条"不覆盖"的规矩, 每一条都对应一个曾经踩过的坑:
     * <ol>
     *   <li><b>用途 key 或配置块缺失 → 请求原样通过。</b>在此之前, 不认识的 task 会被默默
     *       当成 {@code extraction}: 配置里没有这个块时倒也无害, 但一旦有人给
     *       {@code extraction} 配了模型或温度, 所有没登记的 task 都会悄悄用上它 ——
     *       改一个用途的配置去影响另一个用途的调用, 是最难查的一类。现在不认识的 task
     *       会留下一条 WARN。</li>
     *   <li><b>{@code request.setModel(...)} 优先于用途配置。</b>调用方明说了要用哪个模型,
     *       就不该被"这个任务通常用哪个模型"覆盖掉。</li>
     *   <li><b>metadata 必须带上。</b>用途路由会新建一个请求, 漏掉 metadata 会让
     *       {@code LlmCallService.record} 认不出 companionId 而静默跳过记库 ——
     *       "这次调用发生了什么"就查不到了。</li>
     * </ol>
     */
    private StructuredRequest applyPurpose(StructuredRequest request) {
        String purposeKey = purposeFor(request.getTask());
        if (purposeKey == null) return request;
        AppProperties.Purpose purpose = props.getLlm().getPurpose() == null
                ? null : props.getLlm().getPurpose().get(purposeKey);
        if (purpose == null) return request;
        return StructuredRequest.builder()
                .system(request.getSystem())
                .user(request.getUser())
                .task(request.getTask())
                .schemaHint(request.getSchemaHint())
                .temperature(request.getTemperature() != null ? request.getTemperature() : purpose.getTemperature())
                .model(request.getModel() != null ? request.getModel() : purpose.getModel())
                .metadata(request.getMetadata())
                .build();
    }

    /** 任务 → 用途 key; 不认识的 task 返回 null(原样通过), 而不是猜一个。 */
    private static String purposeFor(String task) {
        if (task == null) return null;
        return switch (task) {
            case "perception" -> "perception";
            case "daily-reflection", "weekly-reflection" -> "reflection";
            case "persona-evolution" -> "persona_evolution";
            case "persona-compile" -> "extraction";
            case "memory-extraction", "user-model-extraction", "self-model-extraction",
                 "relationship-narrative", "reminder-extraction" -> "extraction";
            case "session-summary" -> "summary";
            // LAP v1: 应用链路的两个任务共用一个用途块(application), 由 app.llm.purpose.application 配
            case "application-capability-selection", "application-selection",
                 "application-action-selection" -> "application";
            default -> {
                log.warn("[LLM] 未知 task={}, 不应用用途路由(用调用方给的模型/温度)", task);
                yield null;
            }
        };
    }
}
