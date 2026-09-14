package com.luxera.companion.appraisal;

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
 * 消息评估(§十): 一条用户消息对 Agent 内部状态的意义。
 * 消息不是直接触发回复, 而是先改变内部状态(Appraisal), 再由 Drives 竞争产生行为。
 */
@Entity
@Table(name = "message_appraisals", indexes = @Index(name = "idx_appraisal_message", columnList = "message_id"))
@Getter
@Setter
public class MessageAppraisal {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 0-1 情绪冲击 */
    @Column(name = "emotional_impact", nullable = false)
    private double emotionalImpact = 0.3;

    /** -1~1 关系影响(负=张力/正=增进) */
    @Column(name = "relationship_impact", nullable = false)
    private double relationshipImpact = 0;

    /** 0-1 紧迫程度(求助/难过→高) */
    @Column(nullable = false)
    private double urgency = 0.1;

    /** 0-1 温暖(想他/道歉→高) */
    @Column(nullable = false)
    private double warmth = 0;

    /** 0-1 受伤(被指责/冷落→高) */
    @Column(nullable = false)
    private double hurt = 0;

    /** 0-1 生气(被冒犯→高) */
    @Column(nullable = false)
    private double anger = 0;

    /** 0-1 个人相关度 */
    @Column(name = "personal_relevance", nullable = false)
    private double personalRelevance = 0.3;

    @Column(length = 500)
    private String context;

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
