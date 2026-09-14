package com.luxera.companion.llm;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * V9 §22/§23 LLM Call 观测: 每次模型调用记录 模型/token/latency/cache 估计/上下文 hash。
 * context_hash = stableHash/sessionHash/dynamicHash —— 相同 agent 相同 stableHash 的
 * 连续调用 → provider prefix cache 命中估计(DeepSeek 等自动前缀缓存)。
 */
@Entity
@Table(name = "llm_calls", indexes = {
        @Index(name = "idx_llm_calls_companion", columnList = "companion_id,created_at"),
        @Index(name = "idx_llm_calls_hash", columnList = "context_hash")
})
@Getter
@Setter
public class LlmCallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "companion_id", length = 36)
    private String companionId;

    /** chat / structured / chat_stream */
    @Column(nullable = false, length = 24)
    private String task;

    /** FAST / DEEP */
    @Column(length = 8)
    private String path;

    @Column(length = 64)
    private String model;

    @Column(length = 24)
    private String provider;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** prefix cache 命中估计(与上一条同 agent 调用 stableHash 相同) */
    @Column(name = "cache_estimated", nullable = false)
    private boolean cacheEstimated = false;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "context_hash", length = 96)
    private String contextHash;

    @Column(name = "status", length = 16)
    private String status = "success";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
