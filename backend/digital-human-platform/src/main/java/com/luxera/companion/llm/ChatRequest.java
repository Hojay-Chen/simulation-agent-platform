package com.luxera.companion.llm;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ChatRequest {
    private final List<LlmMessage> messages;
    @Builder.Default private final double temperature = 0.9;
    private final Integer maxTokens;
    @Builder.Default private final boolean stream = false;
    /** 附加元数据,供 Mock 网关等个性化(不入 prompt) */
    private final Map<String, String> metadata;
}
