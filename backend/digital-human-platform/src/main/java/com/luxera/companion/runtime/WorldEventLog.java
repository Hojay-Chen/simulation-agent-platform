package com.luxera.companion.runtime;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 世界事件日志(§62 Event Sourcing): 系统里发生过的所有 WorldEvent。
 * 出现"为什么她突然不理我"时, 可以按时间回放事件链。
 */
@Entity
@Table(name = "world_events", indexes = {
        @Index(name = "idx_world_events_companion_time", columnList = "companion_id,occurred_at")
})
@Getter
@Setter
public class WorldEventLog {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** JSON payload */
    @Column(columnDefinition = "text")
    private String payload;

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
