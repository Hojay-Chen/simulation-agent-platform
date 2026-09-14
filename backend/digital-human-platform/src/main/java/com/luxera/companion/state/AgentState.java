package com.luxera.companion.state;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 伴侣短期动态状态(不等于人格) */
@Entity
@Table(name = "agent_states")
@Getter
@Setter
public class AgentState {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    @Column(length = 32)
    private String mood = "calm";

    @Column(nullable = false)
    private double energy = 0.72;

    @Column(nullable = false)
    private double stress = 0.18;

    @Column(name = "social_energy", nullable = false)
    private double socialEnergy = 0.6;

    @Column(nullable = false)
    private double curiosity = 0.68;

    @Column(name = "emotional_closeness", nullable = false)
    private double emotionalCloseness = 0.3;

    /** Appraisal: 受伤程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double hurt = 0;

    /** Appraisal: 生气程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double anger = 0;

    /** Emotion: 难过程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double sadness = 0;

    /** Emotion: 焦虑程度(0-1, 随状态衰减) */
    @Column(nullable = false)
    private double anxiety = 0;

    /** Emotion: 温暖/亲密感受(0-1, 正面情绪) */
    @Column(nullable = false)
    private double warmth = 0;

    // ── Body State(§50): 身体状态影响情绪/注意力/表达 ──────────
    /** 困倦程度 0-1(凌晨 + 困 → 即使想聊也可能说"明天再聊") */
    @Column(nullable = false, columnDefinition = "double precision default 0.1")
    private double sleepiness = 0.1;

    /** 饥饿程度 0-1 */
    @Column(nullable = false, columnDefinition = "double precision default 0.1")
    private double hunger = 0.1;

    /** 身体不适 0-1(头疼/感冒等) */
    @Column(name = "physical_discomfort", nullable = false, columnDefinition = "double precision default 0")
    private double physicalDiscomfort = 0;

    /** 专注度 0-1(工作中高, 深夜低) */
    @Column(nullable = false, columnDefinition = "double precision default 0.6")
    private double focus = 0.6;

    /** 情绪叠加: 孤独感 0-1(与 warmth 并存: 可以既喜欢又孤独) */
    @Column(nullable = false, columnDefinition = "double precision default 0")
    private double loneliness = 0;

    /** 情绪叠加: 愉悦/开心 0-1(与 hurt 并存) */
    @Column(nullable = false, columnDefinition = "double precision default 0")
    private double joy = 0;

    /** 情绪叠加: 亲昵/喜爱 0-1 */
    @Column(nullable = false, columnDefinition = "double precision default 0")
    private double affection = 0;

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
