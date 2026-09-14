package com.luxera.companion.plan;

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
 * V9 §5 PlanRevision: 计划变更的因果记录。
 * 计划变化不是覆盖 —— 每次变更(CREATED/MODIFIED/ACTIVATED/COMPLETED/CANCELLED/INTERRUPTED/SUPERSEDED)
 * 都记一条, 带原因。用户追问"你不是说要去跑步吗"时, 沿 revision 链恢复自然解释。
 */
@Entity
@Table(name = "plan_revisions", indexes = @Index(name = "idx_plan_rev_plan", columnList = "plan_id,occurred_at"))
@Getter
@Setter
public class PlanRevision {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "plan_id", nullable = false, length = 36)
    private String planId;

    /** CREATED / MODIFIED / ACTIVATED / COMPLETED / CANCELLED / INTERRUPTED / SUPERSEDED */
    @Column(nullable = false, length = 16)
    private String action;

    @Column(name = "reason", length = 300)
    private String reason;

    /** 变更前后状态快照(用于审计/重放) */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
