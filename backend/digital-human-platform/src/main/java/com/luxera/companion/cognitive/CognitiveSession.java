package com.luxera.companion.cognitive;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V9 §4.3 Cognitive Session: Agent 当前持续的认知过程。
 *
 * 保存 current_focus(当前关注) / current_thought(当前想法) / current_intention(当前意图)
 * / active_plans(进行中的计划) / emotion_summary。
 * 用户不说话时也可以持续变化(主动事件/后台循环)。
 *
 * state_version: 乐观锁 —— 并发写入(用户消息 + 主动事件 + 后台任务)时,
 * 提交冲突则重新读取最新状态(State Manager 语义, 防止覆盖)。
 */
@Entity
@Table(name = "cognitive_sessions")
@Getter
@Setter
public class CognitiveSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    /** 当前关注(最近一次感知的话题/事件) */
    @Column(name = "current_focus", length = 200)
    private String currentFocus;

    /** 当前想法(内心正在想的事, 高价值才可能沉淀) */
    @Column(name = "current_thought", length = 500)
    private String currentThought;

    /** 当前意图(此刻想做的事) */
    @Column(name = "current_intention", length = 200)
    private String currentIntention;

    /** 进行中的计划摘要(JSON 数组: [{title, status, expectedTime}]) */
    @Column(name = "active_plans", columnDefinition = "text")
    private String activePlans;

    /** 情绪摘要(如 "有点担心他") */
    @Column(name = "emotion_summary", length = 200)
    private String emotionSummary;

    /** 认知状态版本(乐观锁) */
    @Column(name = "state_version", nullable = false)
    private long stateVersion = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 乐观锁递增 */
    public long nextVersion() {
        return ++stateVersion;
    }
}
