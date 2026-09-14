package com.luxera.companion.memory;

import java.util.List;

/** 记忆语义检索的插件式接口(pgvector 等后续接入;MVP 用 Noop 实现) */
public interface EmbeddingProvider {

    /** 返回与 query 语义最接近的 topK 条记忆 id */
    List<String> searchSimilar(String userId, String companionId, String query, int topK);
}
