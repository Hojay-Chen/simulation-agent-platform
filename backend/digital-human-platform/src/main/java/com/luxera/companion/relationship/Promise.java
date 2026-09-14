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

/** 承诺(设计文档 §10.2): "我答应过你的事情" */
@Entity
@Table(name = "promises", indexes = @Index(name = "idx_promise_rel", columnList = "relationship_id"))
@Getter
@Setter
public class Promise {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "relationship_id", nullable = false, length = 36)
    private String relationshipId;

    /** USER / COMPANION */
    @Column(nullable = false, length = 16)
    private String promisor;

    @Column(name = "promise_text", nullable = false, length = 500)
    private String promiseText;

    /** OPEN/KEPT/BROKEN/DUE */
    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
