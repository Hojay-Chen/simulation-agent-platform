package com.luxera.companion.life;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 伴侣的连续生活状态(设计文档 §5.3)。
 * "即使不聊天,她也仍然在生活" —— 本表记录她此刻在做什么。
 */
@Entity
@Table(name = "companion_life")
@Getter
@Setter
public class CompanionLife {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    @Column(name = "current_activity", length = 64)
    private String currentActivity;

    @Column(name = "current_location", length = 64)
    private String currentLocation;

    @Column(name = "day_phase", length = 32)
    private String dayPhase;          // SLEEP/MORNING/WORK_BUSY/LUNCH/WORK_AFTERNOON/EVENING/LEISURE/LATE_NIGHT

    @Column(name = "work_status", length = 32)
    private String workStatus;

    @Column(name = "social_status", length = 32)
    private String socialStatus;

    @Column(name = "sleep_status", length = 32)
    private String sleepStatus;

    @Column(name = "life_energy", nullable = false)
    private double lifeEnergy = 0.7;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "life_context_json", columnDefinition = "text")
    private Map<String, Object> lifeContext = new HashMap<>();

    /** 当前"生活日期"(跨天时推进) */
    @Column(name = "life_date")
    private LocalDate lifeDate;

    @Column(name = "last_simulated_at")
    private LocalDateTime lastSimulatedAt;

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
