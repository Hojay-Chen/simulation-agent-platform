package com.luxera.companion.persona;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** 人生时间线事件(教育/工作/搬家/经历) */
@Entity
@Table(name = "companion_life_events")
@Getter
@Setter
public class LifeEvent {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 32)
    private String subtype;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "start_time")
    private LocalDate startTime;

    @Column(name = "end_time")
    private LocalDate endTime;

    @Column(nullable = false)
    private double importance = 0.5;

    @Column(name = "emotional_significance", nullable = false)
    private double emotionalSignificance = 0.5;

    @Column(length = 32)
    private String source = "persona";

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
