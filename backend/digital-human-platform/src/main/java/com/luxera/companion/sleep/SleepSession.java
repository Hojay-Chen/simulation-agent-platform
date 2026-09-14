package com.luxera.companion.sleep;

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
 * §8 Sleep Session: 一次完整的睡眠记录。
 * Agent 真正拥有"昨天睡了多久", 而不是每次重新计算。
 *
 * sleep_type: NORMAL / NAP / OVERSLEEP / INTERRUPTED
 * cause:      NATURAL / EXHAUSTION / ALARM / SOCIAL / USER_INTERACTION / ENVIRONMENT
 */
@Entity
@Table(name = "sleep_sessions", indexes = @Index(name = "idx_sleep_companion", columnList = "companion_id"))
@Getter
@Setter
public class SleepSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 时长(分钟) */
    @Column(nullable = false)
    private int durationMinutes = 0;

    /** 睡眠质量 0-1 */
    @Column(name = "sleep_quality", nullable = false)
    private double sleepQuality = 0.6;

    /** NORMAL / NAP / OVERSLEEP / INTERRUPTED */
    @Column(name = "sleep_type", nullable = false, length = 16)
    private String sleepType = "NORMAL";

    /** NATURAL / EXHAUSTION / ALARM / SOCIAL / USER_INTERACTION / ENVIRONMENT */
    @Column(nullable = false, length = 24)
    private String cause = "NATURAL";

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
