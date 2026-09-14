package com.luxera.companion.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResult {
    private String content;
    private String model;
    private int promptTokens;
    private int completionTokens;
    /** 实际使用的网关名(mock / openai-compatible / ...) */
    private String provider;
}
