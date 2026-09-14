package com.luxera.companion.llm;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StructuredRequest {
    private final String system;
    private final String user;
    /** 任务类型标识: persona-compile | perception | memory-extraction | user-model-extraction |
     *  daily-reflection | weekly-reflection | persona-evolution | reminder-extraction |
     *  self-model-extraction | relationship-narrative */
    private final String task;
    private final String schemaHint;
    private final Double temperature;
    /** 模型覆盖(由 LlmRouter 按用途路由填充, 缺省用 chat-model) */
    private final String model;

    /** V9: 观测元数据(companionId/path/hashes) */
    private final java.util.Map<String, String> metadata;
}
