package com.luxera.companion.thought;

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
 * 内部思想(设计文档 §6)。
 * Thought ≠ 输出: 可保留/压制/遗忘/转化为主动行为或长期理解, 但不自动展示给用户。
 */
@Entity
@Table(name = "thoughts", indexes = @Index(name = "idx_thought_companion", columnList = "companion_id"))
@Getter
@Setter
public class Thought {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** REFLECTION/CURIOSITY/WORRY/DESIRE/ASSOCIATION/EXPECTATION/REMINDER/UNFINISHED/MEMORY_TRIGGER/SELF_REFLECTION */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(name = "trigger_type", length = 32)
    private String triggerType;    // CONVERSATION/LIFE/MEMORY/OPEN_LOOP/EMOTION/REFLECTION

    @Column(name = "trigger_ref", length = 64)
    private String triggerRef;

    @Column(nullable = false)
    private double importance = 0.4;

    @Column(name = "emotional_weight", nullable = false)
    private double emotionalWeight = 0.4;

    @Column(name = "relationship_weight", nullable = false)
    private double relationshipWeight = 0.4;

    @Column(nullable = false)
    private double confidence = 0.5;

    @Column(name = "strength", nullable = false)
    private double strength = 0.5;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** ACTIVE/SUPPRESSED/ACTED/RESOLVED/EXPIRED/FORGOTTEN */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

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
