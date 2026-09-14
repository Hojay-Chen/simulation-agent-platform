package com.luxera.companion.selfmodel;

import com.luxera.companion.common.convert.StringListConverter;
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

/**
 * 自我模型(设计文档 §9): "我是谁"的当前理解。
 * Persona = 相对稳定的我是怎样的人; SelfModel = 当前阶段我觉得自己怎样。
 * 单行/伴侣, 版本化(每次更新 version+1, 记录 change_reason)。
 */
@Entity
@Table(name = "self_models")
@Getter
@Setter
public class SelfModel {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    @Convert(converter = StringListConverter.class)
    @Column(name = "facts_json", columnDefinition = "text")
    private List<String> facts = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "preferences_json", columnDefinition = "text")
    private List<String> preferences = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "patterns_json", columnDefinition = "text")
    private List<String> patterns = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "beliefs_json", columnDefinition = "text")
    private List<String> beliefs = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "goals_json", columnDefinition = "text")
    private List<String> goals = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "concerns_json", columnDefinition = "text")
    private List<String> concerns = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "plans_json", columnDefinition = "text")
    private List<String> plans = new ArrayList<>();

    /** 当前阶段的自我叙事, 如"最近工作有点忙, 发现自己越来越喜欢晚上安静地待着" */
    @Column(length = 2000)
    private String narrative;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

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
