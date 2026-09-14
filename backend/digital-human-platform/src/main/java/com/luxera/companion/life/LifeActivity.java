package com.luxera.companion.life;

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

/** 伴侣的生活活动(设计文档 §5.4) */
@Entity
@Table(name = "life_activities", indexes = @Index(name = "idx_life_act_companion", columnList = "companion_id"))
@Getter
@Setter
public class LifeActivity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** SLEEP/WORK/STUDY/MEAL/COMMUTE/EXERCISE/LEISURE/SOCIAL/HOUSEWORK/HOBBY/REST/OTHER */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(name = "planned_start")
    private LocalDateTime plannedStart;

    @Column(name = "planned_end")
    private LocalDateTime plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    @Column(nullable = false)
    private double importance = 0.4;

    @Column(name = "emotional_significance", nullable = false)
    private double emotionalSignificance = 0.3;

    /** PLANNED/ACTIVE/DONE/CANCELLED */
    @Column(nullable = false, length = 16)
    private String status = "PLANNED";

    // ── Activity Model(§6/§32): 具体活动 + 可打断性 ──────────
    /** 注意力占用度 0-1: 会议 0.92, 散步 0.2 */
    @Column(name = "attention_demand", nullable = false)
    private double attentionDemand = 0.5;

    /** 可打断性 0-1: 越接近 1 越容易被消息打断(吃饭 0.6, 开会 0.12) */
    @Column(nullable = false)
    private double interruptibility = 0.5;

    /** 手机可用性 0-1: 手机在不在身边(开会放包里 → 低) */
    @Column(name = "phone_availability", nullable = false)
    private double phoneAvailability = 0.6;

    /** 情绪影响 -1~1: 活动对心情的影响(见朋友 +0.2, 加班 -0.15) */
    @Column(name = "mood_effect", nullable = false)
    private double moodEffect = 0;

    /** 活动进度 0-1 */
    @Column(nullable = false)
    private double progress = 0;

    /** 是否被消息/事件中断(INTERRUPTED 状态恢复后回到 ACTIVE) */
    @Column(name = "interrupted", nullable = false)
    private boolean interrupted = false;

    @Column(name = "interrupted_at")
    private LocalDateTime interruptedAt;

    /** 中断原因(用户消息 / 环境事件 / 想起未完成的事) */
    @Column(name = "interrupt_reason", length = 200)
    private String interruptReason;

    /** SIMULATED_LIFE_EVENT / USER_SHARED_EVENT / REAL_TOOL_EVENT / SYSTEM_EVENT */
    @Column(nullable = false, length = 32)
    private String source = "SIMULATED_LIFE_EVENT";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
