package com.luxera.companion.digitalhuman.summary;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V9 §20 Session Rolling Summary: 会话达到阈值后, 早期消息压缩为结构化摘要。
 *
 * 摘要区分 facts(她知道的关于用户的事) / unresolved(没说完的事) /
 * relationship(关系变化) / plans(约定过的事)。近期原文保留, 远期摘要化。
 */
@Entity
@Table(name = "session_summaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_summary_conv", columnNames = {"conversation_id"}))
@Getter
@Setter
public class SessionSummary {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** 完整摘要文本(供上下文注入) */
    @Column(name = "summary_text", columnDefinition = "text")
    private String summaryText;

    @Column(name = "facts_text", columnDefinition = "text")
    private String factsText;

    @Column(name = "unresolved_text", columnDefinition = "text")
    private String unresolvedText;

    @Column(name = "relationship_text", columnDefinition = "text")
    private String relationshipText;

    @Column(name = "plans_text", columnDefinition = "text")
    private String plansText;

    /** 摘要生成时的消息总数(每新增 20 条重新摘要一次) */
    @Column(name = "message_count_at_summary", nullable = false)
    private int messageCountAtSummary = 0;

    /** 摘要版本 */
    @Column(name = "version", nullable = false)
    private int version = 1;

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
