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

/** 共同经历(设计文档 27 节,Shared Memory 的来源之一) */
@Entity
@Table(name = "shared_experiences")
@Getter
@Setter
public class SharedExperience {

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
    private double importance = 0.5;

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
