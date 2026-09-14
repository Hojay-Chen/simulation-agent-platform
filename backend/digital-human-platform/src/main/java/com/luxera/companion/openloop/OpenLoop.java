package com.luxera.companion.openloop;

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
 * 未完成事项(设计文档 §8)。
 * Open Loop: 尚未完成、等待结果、未来还会继续的事 —— 主动行为最重要的驱动。
 */
@Entity
@Table(name = "open_loops", indexes = @Index(name = "idx_loop_companion", columnList = "companion_id"))
@Getter
@Setter
public class OpenLoop {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** USER_EVENT / SELF_EVENT / PROMISE / RELATIONSHIP / WORK */
    @Column(name = "owner_type", length = 32)
    private String ownerType = "USER_EVENT";

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String description;

    /** OPEN/WAITING/RESOLVED/ABANDONED/FORGOTTEN */
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(nullable = false)
    private double importance = 0.5;

    @Column(name = "emotional_weight", nullable = false)
    private double emotionalWeight = 0.5;

    @Column(name = "expected_resolution_at")
    private LocalDateTime expectedResolutionAt;

    @Column(name = "last_referenced_at")
    private LocalDateTime lastReferencedAt;

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
