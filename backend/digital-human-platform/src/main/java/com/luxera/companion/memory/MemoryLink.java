package com.luxera.companion.memory;

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

/** 记忆关联(设计文档 28/98 节): 记忆图谱的边 */
@Entity
@Table(name = "memory_links", indexes = {
        @Index(name = "idx_link_from", columnList = "from_memory_id"),
        @Index(name = "idx_link_to", columnList = "to_memory_id")
})
@Getter
@Setter
public class MemoryLink {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "from_memory_id", nullable = false, length = 36)
    private String fromMemoryId;

    @Column(name = "to_memory_id", nullable = false, length = 36)
    private String toMemoryId;

    /** same_topic | mentioned_together | causal | sequence */
    @Column(nullable = false, length = 32)
    private String relation = "same_topic";

    @Column(nullable = false)
    private double strength = 0.5;

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
