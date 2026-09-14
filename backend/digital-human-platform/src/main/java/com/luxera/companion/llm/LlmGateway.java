package com.luxera.companion.llm;

import java.util.function.Consumer;

/** LLM 网关统一接口: 聊天 / 流式聊天 / 结构化 JSON 输出 */
public interface LlmGateway {

    /** 网关标识(用于日志与响应) */
    String name();

    /** 是否可用(如 openai-compatible 未配 key 时不可用) */
    boolean available();

    /** 非流式聊天 */
    ChatResult chat(ChatRequest request);

    /** 流式聊天,逐段回调增量文本 */
    void chatStream(ChatRequest request, Consumer<String> onDelta);

    /** 结构化 JSON 输出(抽取/编译等任务) */
    StructuredResult structured(StructuredRequest request);
}
