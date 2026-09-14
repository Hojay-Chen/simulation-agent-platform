package com.luxera.companion.runtime;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent 运行痕迹(§60): 每次 Agent 调用产生一条记录。
 * 不记录完整内部思维链, 只记录 输入摘要 + 结构化结果 + 状态变化 + 耗时 + token。
 * 用于回答"为什么她突然不理我"这类问题(回放事件链)。
 */
@Entity
@Table(name = "agent_traces")
@Getter
@Setter
public class AgentTrace {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", length = 36)
    private String companionId;

    /** 追踪链 id(一次事件处理的所有 Agent 共享) */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "parent_trace_id", length = 64)
    private String parentTraceId;

    @Column(name = "agent_name", nullable = false, length = 64)
    private String agentName;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(name = "wake_reason", length = 32)
    private String wakeReason;

    @Column(length = 64)
    private String model;

    @Column(name = "input_summary", columnDefinition = "text")
    private String inputSummary;

    @Column(columnDefinition = "text")
    private String output;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "token_usage")
    private Integer tokenUsage;

    @Column(length = 32)
    private String status = "success";   // success | fallback | failed

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
