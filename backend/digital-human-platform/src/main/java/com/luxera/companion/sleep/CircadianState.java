package com.luxera.companion.sleep;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * §3 Circadian State: 生物钟状态。
 * 记录每个伴侣的长期节律 + 实时睡眠压力。
 *
 * sleep_pressure(睡眠稳态压力, Process S): 醒着越久越高, 睡觉时下降。
 * circadian_phase_shift(节律偏移): 现实时间 vs 生物钟时间的偏移(小时)。
 *   例如现实 23:00 但 phase_shift=-1.5 → 生物钟 21:30。
 * chronotype: EARLY/NORMAL/LATE —— 天然倾向, 不是固定作息。
 */
@Entity
@Table(name = "circadian_states")
@Getter
@Setter
public class CircadianState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    /** EARLY / NORMAL / LATE */
    @Column(nullable = false, length = 8)
    private String chronotype = "NORMAL";

    /** 睡眠压力 0-1: 醒着越久越高 */
    @Column(name = "sleep_pressure", nullable = false)
    private double sleepPressure = 0.2;

    /** 睡眠债 0-1: 长期睡眠不足累积 */
    @Column(name = "sleep_debt", nullable = false)
    private double sleepDebt = 0.1;

    /** 节律偏移(小时): 现实时间 - 生物钟时间 = offset; 正=生物钟晚 */
    @Column(name = "circadian_phase_shift", nullable = false)
    private double circadianPhaseShift = 0;

    /** 上次醒来时间 */
    @Column(name = "last_wake_at")
    private LocalDateTime lastWakeAt;

    /** 当前是否在睡眠中 */
    @Column(name = "is_sleeping", nullable = false)
    private boolean sleeping = false;

    /** 本次睡眠开始时间 */
    @Column(name = "sleep_started_at")
    private LocalDateTime sleepStartedAt;

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
