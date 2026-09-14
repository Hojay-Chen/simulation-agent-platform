package com.luxera.companion.relationship;

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

/** 关系线索(设计文档 §10.1): 如"找工作"→准备简历→投递→面试→等结果 */
@Entity
@Table(name = "relationship_threads", indexes = @Index(name = "idx_thread_rel", columnList = "relationship_id"))
@Getter
@Setter
public class RelationshipThread {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "relationship_id", nullable = false, length = 36)
    private String relationshipId;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(length = 1000)
    private String summary;

    /** ACTIVE/CLOSED/PAUSED */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false)
    private double importance = 0.5;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt = LocalDateTime.now();

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
