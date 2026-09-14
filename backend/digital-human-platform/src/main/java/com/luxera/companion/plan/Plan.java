package com.luxera.companion.plan;

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
 * V9 §5 Plan: Agent 生活时间轴上的计划/事件(Reality Ledger 的一部分)。
 *
 * 计划是概率性的: confidence 表示"大概会做"; flexibility 表示可被突发打断的程度。
 * 计划变化不覆盖, 而是产生 {@link PlanRevision}(因果链, 用户追问旧计划时可自然解释)。
 *
 * 状态: PLANNED → ACTIVE → COMPLETED | CANCELLED | SUPERSEDED
 */
@Entity
@Table(name = "plans", indexes = {
        @Index(name = "idx_plans_companion", columnList = "companion_id,plan_status"),
        @Index(name = "idx_plans_time", columnList = "expected_time")
})
@Getter
@Setter
public class Plan {

    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** ACTIVITY / EVENT / INTENTION / SOCIAL */
    @Column(nullable = false, length = 24)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "plan_status", nullable = false, length = 16)
    private String status = STATUS_PLANNED;

    /** 计划发生概率 0-1(如"今晚大概想跑步, 60%") */
    @Column(nullable = false)
    private double confidence = 0.6;

    /** 可被打断程度 0-1(0=雷打不动, 1=随时可改) */
    @Column(nullable = false)
    private double flexibility = 0.5;

    @Column(name = "expected_time")
    private LocalDateTime expectedTime;

    /** 触发条件(自然语言, 可空 = 无计划也是合法状态) */
    @Column(name = "trigger_condition", length = 200)
    private String triggerCondition;

    @Column(name = "parent_plan_id", length = 36)
    private String parentPlanId;

    /** 被谁/什么事件打断(REALITY: event id) */
    @Column(name = "interrupted_by", length = 64)
    private String interruptedBy;

    @Column(name = "revision_reason", length = 300)
    private String revisionReason;

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
