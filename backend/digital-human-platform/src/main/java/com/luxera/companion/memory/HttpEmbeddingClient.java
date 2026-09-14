package com.luxera.companion.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容的向量客户端(如 SiliconFlow BAAI/bge 系列) */
@Slf4j
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private final AppProperties props;
    private WebClient webClient;

    public HttpEmbeddingClient(AppProperties props) {
        this.props = props;
        String base = props.getEmbedding().getBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = WebClient.builder()
                .baseUrl(base)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public boolean available() {
        return props.getEmbedding().getApiKey() != null && !props.getEmbedding().getApiKey().isBlank();
    }

    @Override
    public List<Float> embed(String text) {
        if (!available()) return List.of();
        try {
            JsonNode node = webClient.post().uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getEmbedding().getApiKey())
                    .bodyValue(Map.of("model", props.getEmbedding().getModel(), "input", text))
                    .retrieve()
                    .onStatus(s -> !s.is2xxSuccessful(), res -> res.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(err -> new RuntimeException("Embedding 请求失败: " + err)))
                    .bodyToMono(byte[].class)
                    .map(bytes -> {
                        try { return mapper().readTree(bytes); }
                        catch (Exception e) { throw new RuntimeException("Embedding 响应解析失败", e); }
                    })
                    .block();
            List<Float> result = new ArrayList<>();
            for (JsonNode v : node.path("data").path(0).path("embedding")) {
                result.add((float) v.asDouble());
            }
            if (result.size() != props.getEmbedding().getDimension()) {
                log.warn("Embedding 维度 {} ≠ 配置 {} (vector 列维度不匹配会导致写入失败)",
                        result.size(), props.getEmbedding().getDimension());
            }
            return result;
        } catch (Exception e) {
            log.debug("Embedding 生成失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public int dimension() {
        return props.getEmbedding().getDimension();
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
