package com.luxera.companion.reflection;

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

/** 反思记录(每日/每周) */
@Entity
@Table(name = "reflection_records")
@Getter
@Setter
public class ReflectionRecord {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(nullable = false, length = 16)
    private String type = "daily";

    /** 周期标识,如 2026-08-15 */
    @Column(nullable = false, length = 32)
    private String period;

    @Column(length = 1000)
    private String summary;

    @Convert(converter = ObjectListConverter.class)
    @Column(columnDefinition = "text")
    private List<Object> insights = new ArrayList<>();

    @Convert(converter = ObjectListConverter.class)
    @Column(name = "memory_candidates", columnDefinition = "text")
    private List<Object> memoryCandidates = new ArrayList<>();

    @Convert(converter = ObjectListConverter.class)
    @Column(name = "user_model_candidates", columnDefinition = "text")
    private List<Object> userModelCandidates = new ArrayList<>();

    @Convert(converter = ObjectListConverter.class)
    @Column(name = "relationship_candidates", columnDefinition = "text")
    private List<Object> relationshipCandidates = new ArrayList<>();

    /** 设计文档 §39: 伴侣自我洞察 / 用户生活模式 */
    @Convert(converter = ObjectListConverter.class)
    @Column(name = "self_insights", columnDefinition = "text")
    private List<Object> selfInsights = new ArrayList<>();

    @Convert(converter = ObjectListConverter.class)
    @Column(name = "life_patterns", columnDefinition = "text")
    private List<Object> lifePatterns = new ArrayList<>();

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
