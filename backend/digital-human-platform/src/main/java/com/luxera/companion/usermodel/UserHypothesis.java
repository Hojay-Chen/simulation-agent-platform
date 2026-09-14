package com.luxera.companion.usermodel;

import com.luxera.companion.common.convert.ObjectListConverter;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 用户推测(≠事实,置信度不足,必须关联证据;设计文档 20-21 节) */
@Entity
@Table(name = "user_hypotheses")
@Getter
@Setter
public class UserHypothesis {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, length = 128)
    private String hypothesis;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private double confidence = 0.5;

    @Convert(converter = ObjectListConverter.class)
    @Column(columnDefinition = "text")
    private List<Object> evidence = new ArrayList<>();

    @Column(length = 32)
    private String status = "active";

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
