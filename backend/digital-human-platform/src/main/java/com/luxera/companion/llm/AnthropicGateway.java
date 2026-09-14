package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Anthropic Messages 网关(非流式;流式退化为一次性输出) */
@Slf4j
@Component
public class AnthropicGateway implements LlmGateway {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AppProperties props;
    private final ObjectMapper mapper;
    private WebClient webClient;

    public AnthropicGateway(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        init();
    }

    private void init() {
        String base = props.getLlm().getBaseUrl();
        if (base == null || base.isBlank()) base = "https://api.anthropic.com";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder()
                .baseUrl(base)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "anthropic"; }

    @Override
    public boolean available() {
        return props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        JsonNode node = post(request.getMessages(), request.getTemperature(), null);
        return new ChatResult(extractText(node), props.getLlm().getChatModel(), 0, 0, name());
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
        // 简单实现: 一次返回完整文本
        onDelta.accept(chat(request).getContent());
    }

    @Override
    public StructuredResult structured(StructuredRequest request) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.user(request.getUser()));
        JsonNode node = post(messages, request.getTemperature() != null ? request.getTemperature() : props.getLlm().getStructuredTemperature(),
                request.getSystem());
        return new StructuredResult(extractText(node), mapper);
    }

    private JsonNode post(List<LlmMessage> messages, double temperature, String system) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getLlm().getChatModel());
        body.put("max_tokens", props.getLlm().getMaxTokens());
        body.put("temperature", temperature);
        if (system != null && !system.isBlank()) body.put("system", system);
        List<Map<String, String>> msgs = new ArrayList<>();
        for (LlmMessage m : messages) {
            msgs.add(Map.of("role", "user".equals(m.getRole()) || "assistant".equals(m.getRole()) ? m.getRole() : "user",
                    "content", m.getContent()));
        }
        body.put("messages", msgs);

        String base = props.getLlm().getBaseUrl();
        String uri = (base != null && base.endsWith("/v1")) ? "/messages" : "/v1/messages";
        return webClient.post().uri(uri)
                .header("x-api-key", props.getLlm().getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> !s.is2xxSuccessful(), res -> res.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(err -> new RuntimeException("Anthropic 请求失败(" + res.rawStatusCode() + "): " + err)))
                .bodyToMono(byte[].class)
                .map(bytes -> {
                    try { return mapper.readTree(bytes); }
                    catch (Exception e) { throw new RuntimeException("Anthropic 响应解析失败", e); }
                })
                .block();
    }

    private static String extractText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : node.path("content")) {
            sb.append(c.path("text").asText(""));
        }
        return sb.toString();
    }
}
