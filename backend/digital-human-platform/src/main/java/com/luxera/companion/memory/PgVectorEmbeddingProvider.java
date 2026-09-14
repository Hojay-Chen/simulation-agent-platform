package com.luxera.companion.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * pgvector 向量检索提供器(设计文档 §12.3): 用 PostgreSQL + pgvector 做真实语义检索。
 * 未配置 embedding key 时 available()=false → 自动回退现有结构化排序。
 */
@Component
public class PgVectorEmbeddingProvider implements EmbeddingProvider {

    private final JdbcTemplate jdbc;
    private final HttpEmbeddingClient embeddingClient;

    public PgVectorEmbeddingProvider(JdbcTemplate jdbc, HttpEmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    public boolean available() {
        return embeddingClient.available();
    }

    /** 生成向量(委托 embedding 客户端) */
    public List<Float> embed(String text) {
        return embeddingClient.embed(text);
    }

    @Override
    public List<String> searchSimilar(String userId, String companionId, String query, int topK) {
        if (!available() || query == null || query.isBlank()) return List.of();
        List<Float> vec = embeddingClient.embed(query);
        if (vec == null || vec.isEmpty()) return List.of();
        String vectorStr = toVector(vec);
        try {
            return jdbc.query(
                    "SELECT id FROM memories WHERE companion_id = ? AND user_id = ? AND embedding IS NOT NULL "
                            + "ORDER BY embedding <=> ?::vector LIMIT ?",
                    (rs, i) -> rs.getString("id"), companionId, userId, vectorStr, topK);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 写向量到指定记忆 */
    @Transactional
    public void updateEmbedding(String memoryId, List<Float> vec) {
        if (vec == null || vec.isEmpty()) return;
        String vectorStr = toVector(vec);
        try {
            jdbc.update("UPDATE memories SET embedding = ?::vector WHERE id = ?", vectorStr, memoryId);
        } catch (Exception e) {
            // 维度不匹配等 → 忽略, 保留无向量回退
        }
    }

    private static String toVector(List<Float> vec) {
        return "[" + vec.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }
}
