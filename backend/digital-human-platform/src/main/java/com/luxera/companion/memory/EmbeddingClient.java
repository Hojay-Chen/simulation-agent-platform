package com.luxera.companion.memory;

import java.util.List;

/** 向量生成客户端(设计文档 §12.3: PostgreSQL + pgvector) */
public interface EmbeddingClient {

    boolean available();

    /** 生成文本向量 */
    List<Float> embed(String text);

    int dimension();
}
