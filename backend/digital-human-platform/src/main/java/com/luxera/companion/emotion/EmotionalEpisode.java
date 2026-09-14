package com.luxera.companion.emotion;

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

/** 事件型情绪(设计文档 §7.2) */
@Entity
@Table(name = "emotional_episodes", indexes = @Index(name = "idx_episode_companion", columnList = "companion_id"))
@Getter
@Setter
public class EmotionalEpisode {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(length = 128)
    private String trigger;

    /** tired/sad/anxious/angry/happy/lonely/grateful/frustrated/curious/calm */
    @Column(nullable = false, length = 32)
    private String emotion;

    @Column(nullable = false)
    private double intensity = 0.5;

    @Column(length = 500)
    private String cause;

    /** 伴随的内部想法 */
    @Column(length = 500)
    private String thought;

    /** 行为倾向(verbosity_down/initiative_down/humor_down/social_energy_down/... ) */
    @Column(name = "behavior_tendency", length = 255)
    private String behaviorTendency;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(nullable = false)
    private boolean resolved = false;

    @Column(name = "source_type", length = 32)
    private String sourceType;

    @Column(name = "source_id", length = 64)
    private String sourceId;

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
