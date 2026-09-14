package com.luxera.companion.relationship;

import com.luxera.companion.common.convert.ObjectListConverter;
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

/** 关系叙事(设计文档 §38/§42): "我们之间发生过一个故事", 版本化 */
@Entity
@Table(name = "relationship_narratives")
@Getter
@Setter
public class RelationshipNarrative {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "relationship_id", nullable = false, unique = true, length = 36)
    private String relationshipId;

    @Column(name = "current_summary", length = 2000)
    private String currentSummary;

    @Convert(converter = ObjectListConverter.class)
    @Column(name = "important_chapters_json", columnDefinition = "text")
    private List<Object> importantChapters = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "emotional_arc_json", columnDefinition = "text")
    private List<String> emotionalArc = new ArrayList<>();

    @Column(name = "shared_identity", length = 500)
    private String sharedIdentity;

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
