package com.luxera.companion.behavior;

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
 * §45/§46 Behavior Pattern: 人物逐渐形成的习惯行为模式。
 * 这些不是 Prompt —— 而是随着互动更新、可被 Brain 参考的行为倾向。
 *
 * 例如: "用户晚上发消息 → 经常第二天回复"(work_hours_low_response),
 * "工作时 → 不喜欢看手机"(focus_on_work), "开心时 → 更容易主动分享"。
 */
@Entity
@Table(name = "behavior_patterns", indexes = @Index(name = "idx_bp_companion", columnList = "companion_id"))
@Getter
@Setter
public class BehaviorPattern {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    /** 模式名(如 work_hours_low_response / night_late_reply) */
    @Column(nullable = false, length = 64)
    private String pattern;

    /** 模式的语义描述(供 Brain 参考) */
    @Column(nullable = false, length = 300)
    private String description;

    /** 置信度 0-1(观察越多越可信) */
    @Column(nullable = false)
    private double confidence = 0.5;

    /** 观察次数(每次观测更新) */
    @Column(nullable = false)
    private int observations = 0;

    /** 行为影响方向: boost_response / reduce_response / boost_proactive / reduce_proactive */
    @Column(name = "influence", nullable = false, length = 32)
    private String influence = "boost_response";

    /** 强度 0-1(对 Brain 决策的影响权重) */
    @Column(nullable = false)
    private double strength = 0.5;

    @Column(name = "last_observed_at")
    private LocalDateTime lastObservedAt;

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
