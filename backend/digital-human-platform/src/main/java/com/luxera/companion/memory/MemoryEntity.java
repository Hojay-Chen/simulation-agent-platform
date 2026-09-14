package com.luxera.companion.memory;

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
 * 实体层(P2 §五十四): 长期聊天的"那家公司/上次那个地方/他"这类指代需要实体记忆。
 * 记录用户提到的实体(人/公司/地点/餐厅/项目/电影/事件/话题), 用于上下文注入与指代理解。
 */
@Entity
@Table(name = "entities", indexes = @Index(name = "idx_entity_companion", columnList = "companion_id"))
@Getter
@Setter
public class MemoryEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** PERSON/COMPANY/PLACE/RESTAURANT/PROJECT/MOVIE/EVENT/TOPIC */
    @Column(nullable = false, length = 32)
    private String type = "TOPIC";

    @Column(nullable = false, length = 128)
    private String name;

    /** 一句话描述(上下文用) */
    @Column(length = 300)
    private String description;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "mention_count", nullable = false)
    private int mentionCount = 0;

    /** 最近一次提到的上下文(帮助理解指代) */
    @Column(length = 300)
    private String lastContext;

    @Column(nullable = false)
    private double salience = 0.3;

    @Column(length = 32)
    private String status = "active";

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
