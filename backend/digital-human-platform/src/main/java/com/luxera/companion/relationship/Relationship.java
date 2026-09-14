package com.luxera.companion.relationship;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户与伴侣的关系状态(数值主要供系统使用,不直接展示给用户)。
 * 一个 user-companion 对一条。
 */
@Entity
@Table(name = "relationships", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "companion_id"}))
@Getter
@Setter
public class Relationship {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 关系的 Person 身份层引用(agent 侧 = companion.id, user 侧 = user.id) */
    @Column(name = "agent_person_id", length = 36)
    private String agentPersonId;

    @Column(name = "user_person_id", length = 36)
    private String userPersonId;

    @Column(name = "relationship_type", length = 32)
    private String relationshipType = "friend";

    @Column(name = "relationship_stage", nullable = false, length = 32)
    private String relationshipStage = "new";

    @Column(nullable = false)
    private double familiarity = 0.05;

    @Column(nullable = false)
    private double trust = 0.1;

    @Column(nullable = false)
    private double intimacy = 0.05;

    @Column(nullable = false)
    private double affection = 0.2;

    /** 关系张力(冲突/摩擦), 0-1 */
    @Column(nullable = false, columnDefinition = "double precision not null default 0")
    private double tension = 0.0;

    /** 双向性(对方在意这段关系的程度感知), 0-1 */
    @Column(nullable = false, columnDefinition = "double precision not null default 0.5")
    private double reciprocity = 0.5;

    /** 尊重 */
    @Column(nullable = false, columnDefinition = "double precision not null default 0.4")
    private double respect = 0.4;

    /** 依赖(需要对方的程度) */
    @Column(nullable = false, columnDefinition = "double precision not null default 0.15")
    private double dependence = 0.15;

    /** 联系压力 —— 沉默越久越高, 驱动主动联系(关系维护需求) */
    @Column(name = "connection_pressure", nullable = false, columnDefinition = "double precision not null default 0")
    private double connectionPressure = 0.0;

    @Column(name = "shared_experience_count", nullable = false)
    private int sharedExperienceCount = 0;

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "last_interaction_at")
    private LocalDateTime lastInteractionAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

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
