package com.luxera.companion.usermodel;

import com.luxera.companion.common.convert.ObjectListConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 长期观察形成的用户行为模式(设计文档 19 节) */
@Entity
@Table(name = "user_patterns")
@Getter
@Setter
public class UserPattern {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, length = 128)
    private String pattern;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private double confidence = 0.7;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount = 0;

    @Convert(converter = ObjectListConverter.class)
    @Column(columnDefinition = "text")
    private List<Object> evidence = new ArrayList<>();

    @Column(name = "first_observed_at", nullable = false, updatable = false)
    private LocalDateTime firstObservedAt = LocalDateTime.now();

    @Column(name = "last_observed_at", nullable = false)
    private LocalDateTime lastObservedAt = LocalDateTime.now();

    @Column(length = 32)
    private String status = "active";

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
