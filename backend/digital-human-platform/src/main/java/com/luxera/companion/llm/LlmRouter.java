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

    private LlmGateway active;

    public LlmRouter(AppProperties props, OpenAiCompatibleGateway openAi,
                     AnthropicGateway anthropic, MockLlmGateway mock,
                     LlmCallService llmCallService) {
        this.props = props;
        this.openAi = openAi;
        this.anthropic = anthropic;
        this.mock = mock;
        this.llmCallService = llmCallService;
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
        long t0 = System.currentTimeMillis();
        ChatResult r = active.chat(request);
        llmCallService.record("chat", r.getProvider(), r.getModel(),
                r.getPromptTokens(), r.getCompletionTokens(),
                System.currentTimeMillis() - t0, "success", request.getMetadata());
        return r;
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
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
