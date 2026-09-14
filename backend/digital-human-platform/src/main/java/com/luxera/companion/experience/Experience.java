package com.luxera.companion.experience;

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
 * 原始经历(设计文档 §11)。
 * Experience ≠ Memory: 聊天/事件/情绪/想法先产生 Experience,
 * 只有经过 consolidation 才进入长期 Memory。
 */
@Entity
@Table(name = "experiences", indexes = @Index(name = "idx_exp_companion", columnList = "companion_id"))
@Getter
@Setter
public class Experience {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** CONVERSATION/LIFE_EVENT/EMOTIONAL_EVENT/RELATIONSHIP_EVENT/THOUGHT/SHARED_EXPERIENCE */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_id", length = 64)
    private String sourceId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 500)
    private String summary;

    @Column(nullable = false)
    private double importance = 0.4;

    @Column(name = "emotional_weight", nullable = false)
    private double emotionalWeight = 0.4;

    @Column(name = "relationship_weight", nullable = false)
    private double relationshipWeight = 0.4;

    @Column(name = "memory_score")
    private Double memoryScore;

    /** PENDING/CONSOLIDATED/ARCHIVED/DISCARDED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

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
