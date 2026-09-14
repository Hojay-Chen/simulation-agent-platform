package com.luxera.companion.relationship;

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

/** 关系里程碑事件(设计文档 35 节) */
@Entity
@Table(name = "relationship_events")
@Getter
@Setter
public class RelationshipEvent {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "relationship_id", nullable = false, length = 36)
    private String relationshipId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private double significance = 0.5;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

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
