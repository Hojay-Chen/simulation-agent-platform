package com.luxera.companion.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luxera.companion.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** OpenAI 兼容网关(默认 DeepSeek): /chat/completions,支持流式 */
@Slf4j
@Component
public class OpenAiCompatibleGateway implements LlmGateway {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private WebClient webClient;

    public OpenAiCompatibleGateway(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        init();
    }

    private void init() {
        String base = props.getLlm().getBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder()
                .baseUrl(base)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String name() { return "openai-compatible"; }

    @Override
    public boolean available() {
        return props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        Map<String, Object> body = baseBody(request);
        body.put("stream", false);
        JsonNode node = post("/chat/completions", body);
        JsonNode choice = node.path("choices").path(0);
        String content = choice.path("message").path("content").asText("");
        JsonNode usage = node.path("usage");
        return new ChatResult(content, node.path("model").asText(props.getLlm().getChatModel()),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), name());
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onDelta) {
        Map<String, Object> body = baseBody(request);
        body.put("stream", true);
        StringBuilder buffer = new StringBuilder();
        postRaw("/chat/completions", body)
                .doOnNext(chunk -> {
                    buffer.append(chunk);
                    int idx;
                    while ((idx = buffer.indexOf("\n")) != -1) {
                        String line = buffer.substring(0, idx).trim();
                        buffer.delete(0, idx + 1);
                        processLine(line, onDelta);
                    }
                })
                .doOnComplete(() -> {
                    if (buffer.length() > 0) processLine(buffer.toString().trim(), onDelta);
                })
                .doOnError(err -> log.error("LLM 流式请求失败: {}", err.getMessage()))
                .blockLast();
    }

    private void processLine(String line, Consumer<String> onDelta) {
        if (!line.startsWith("data:")) return;
        String data = line.substring(5).trim();
        if (data.equals("[DONE]")) return;
        try {
            JsonNode n = mapper.readTree(data);
            String delta = n.path("choices").path(0).path("delta").path("content").asText(null);
            if (delta != null && !delta.isEmpty()) {
                onDelta.accept(delta);
            }
        } catch (Exception e) {
            log.debug("忽略无法解析的流式块: {}", data);
        }
    }

    @Override
    public StructuredResult structured(StructuredRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModel() != null ? request.getModel() : props.getLlm().getChatModel());
        body.put("temperature", request.getTemperature() != null ? request.getTemperature() : props.getLlm().getStructuredTemperature());
        body.put("max_tokens", props.getLlm().getMaxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.getSystem()));
        messages.add(Map.of("role", "user", "content", request.getUser()));
        body.put("messages", messages);
        JsonNode node = post("/chat/completions", body);
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        return new StructuredResult(content, mapper);
    }

    private Map<String, Object> baseBody(ChatRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getLlm().getChatModel());
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : props.getLlm().getMaxTokens());
        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage m : request.getMessages()) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        body.put("messages", messages);
        return body;
    }

    private JsonNode post(String uri, Map<String, Object> body) {
        return webClient.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getLlm().getApiKey())
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> !s.is2xxSuccessful(), res -> res.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(err -> new RuntimeException("LLM 请求失败(" + res.rawStatusCode() + "): " + truncate(err))))
                .bodyToMono(byte[].class)
                .map(bytes -> {
                    try { return mapper.readTree(bytes); }
                    catch (Exception e) { throw new RuntimeException("LLM 响应解析失败", e); }
                })
                .block();
    }

    private Flux<String> postRaw(String uri, Map<String, Object> body) {
        return webClient.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getLlm().getApiKey())
                .bodyValue(body)
                .retrieve()
                .onStatus(s -> !s.is2xxSuccessful(), res -> res.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(err -> new RuntimeException("LLM 流式请求失败(" + res.rawStatusCode() + "): " + truncate(err))))
                .bodyToFlux(DataBuffer.class)
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return new String(bytes, StandardCharsets.UTF_8);
                });
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }
}
