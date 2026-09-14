package com.luxera.companion.memory;

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
 * 长期记忆: episodic(发生过什么) / semantic(长期认知) / shared(共同经历)。
 * 强度 = importance × confidence × recency_decay × emotional_weight × relationship_weight × retrieval_frequency
 */
@Entity
@Table(name = "memories")
@Getter
@Setter
public class Memory {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(length = 32)
    private String type;   // episodic | semantic | shared | relational | self | narrative

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 500)
    private String summary;

    /** 记忆在叙事中的角色(如 INSIDE_JOKE/里程碑/共同经历) */
    @Column(name = "narrative_role", length = 64)
    private String narrativeRole;

    /** 固话来源(experience id) */
    @Column(name = "consolidation_source", length = 64)
    private String consolidationSource;

    @Column(name = "last_confirmed_at")
    private LocalDateTime lastConfirmedAt;

    /** 遗忘强度(0-1, 越高越易被遗忘) */
    @Column(name = "forgetting_strength", nullable = false)
    private double forgettingStrength = 0;

    @Column(nullable = false)
    private double importance = 0.5;

    @Column(nullable = false)
    private double confidence = 0.7;

    @Column(name = "emotional_weight", nullable = false)
    private double emotionalWeight = 0.5;

    @Column(name = "relationship_weight", nullable = false)
    private double relationshipWeight = 0.5;

    @Column(name = "retrieval_count", nullable = false)
    private int retrievalCount = 0;

    @Column(name = "last_retrieved_at")
    private LocalDateTime lastRetrievedAt;

    /** 记忆发生时间(episodic 用) */
    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(length = 32)
    private String status = "active";   // active | forgotten | archived

    @Column(name = "source_type", length = 32)
    private String sourceType;          // conversation | reflection | event | system

    @Column(name = "source_id", length = 64)
    private String sourceId;

    /** 向量(经 JdbcTemplate 写读, JPA 不直接绑定, 见 PgVectorEmbeddingProvider) */
    @Column(name = "embedding", columnDefinition = "vector(1024)", insertable = false, updatable = false)
    private String embedding;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 记忆检索强度(设计文档 30 节) */
    public double retrievalStrength(int daysSinceOccurred) {
        double recencyDecay = Math.pow(0.92, Math.max(0, daysSinceOccurred));
        double frequencyBoost = 1 + 0.5 * Math.log(1 + retrievalCount);
        return importance * confidence * recencyDecay
                * emotionalWeight * relationshipWeight * frequencyBoost;
    }
}
