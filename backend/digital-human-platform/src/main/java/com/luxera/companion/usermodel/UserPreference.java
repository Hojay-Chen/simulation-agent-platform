package com.luxera.companion.usermodel;

import com.luxera.companion.common.convert.StringMapConverter;
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
import java.util.Map;
import java.util.UUID;

/** 用户偏好(设计文档 18 节) */
@Entity
@Table(name = "user_preferences")
@Getter
@Setter
public class UserPreference {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 128)
    private String preference;

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> value;

    @Column(nullable = false)
    private double confidence = 0.8;

    /** explicit | inferred */
    @Column(name = "source_type", length = 16)
    private String sourceType = "explicit";

    @Column(name = "source_id", length = 64)
    private String sourceId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt = LocalDateTime.now();

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
