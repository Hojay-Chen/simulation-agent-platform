package com.luxera.companion.llm;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 一条对话消息(与 OpenAI/Anthropic 通用) */
@Getter
@RequiredArgsConstructor
public class LlmMessage {
    private final String role;    // system | user | assistant
    private final String content;

    public static LlmMessage system(String c) { return new LlmMessage("system", c); }
    public static LlmMessage user(String c) { return new LlmMessage("user", c); }
    public static LlmMessage assistant(String c) { return new LlmMessage("assistant", c); }
}
