package com.luxera.companion.runtime;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 排程动作(§64/§65): 主动行为必须持久化 —— 服务重启不丢。
 * 例如: 晚上想告诉用户一件事、延迟回复、未回消息复查、活动结束、事件模拟。
 */
@Entity
@Table(name = "scheduled_actions", indexes = {
        @Index(name = "idx_sched_status_execute", columnList = "status,execute_at")
})
@Getter
@Setter
public class ScheduledAction {

    /** 动作类型(常量见 ScheduledActionService) */
    public static final String SEND_MESSAGE = "SEND_MESSAGE";
    public static final String CHECK_MESSAGE = "CHECK_MESSAGE";
    public static final String RE_EVALUATE_MESSAGE = "RE_EVALUATE_MESSAGE";
    public static final String ACTIVITY_END = "ACTIVITY_END";
    public static final String EVENT_SIMULATION = "EVENT_SIMULATION";
    public static final String EMOTION_RECHECK = "EMOTION_RECHECK";
    public static final String PROACTIVE_THOUGHT = "PROACTIVE_THOUGHT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 动作类型(SEND_MESSAGE / RE_EVALUATE_MESSAGE / ...) */
    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "execute_at", nullable = false)
    private LocalDateTime executeAt;

    /** JSON payload: 动作所需数据(如 SEND_MESSAGE 的会话/内容/延迟) */
    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
