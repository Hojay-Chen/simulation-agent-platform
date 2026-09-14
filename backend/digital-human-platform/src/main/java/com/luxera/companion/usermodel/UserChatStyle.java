package com.luxera.companion.usermodel;

import com.luxera.companion.common.convert.StringMapConverter;
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
import java.util.Map;
import java.util.UUID;

/**
 * 用户聊天习惯(P1 §四十七): 学习用户的消息长度/发送速度/连发/emoji/哈哈/提问/活跃时段。
 * 目的不是模仿用户, 而是让她的节奏与用户兼容(她保持自己的性格)。
 * 每(companion)一条, 由 ChatController 在消息入库时增量更新。
 */
@Entity
@Table(name = "user_chat_styles")
@Getter
@Setter
public class UserChatStyle {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, unique = true, length = 36)
    private String companionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** 已统计的消息数 */
    @Column(name = "sample_count", nullable = false)
    private int sampleCount = 0;

    /** 平均消息字数 */
    @Column(name = "avg_message_length", nullable = false)
    private double avgMessageLength = 0;

    /** 平均发送间隔(ms) */
    @Column(name = "avg_gap_ms", nullable = false)
    private double avgGapMs = 0;

    /** 连发率 0-1: 间隔<2s 的消息占比 */
    @Column(name = "burst_rate", nullable = false)
    private double burstRate = 0;

    /** emoji 使用率 0-1 */
    @Column(name = "emoji_rate", nullable = false)
    private double emojiRate = 0;

    /** "哈哈/呵呵" 等笑声频率 0-1 */
    @Column(name = "laugh_rate", nullable = false)
    private double laughRate = 0;

    /** 提问频率 0-1(含？) */
    @Column(name = "question_rate", nullable = false)
    private double questionRate = 0;

    /** 活跃起始小时(0-23) */
    @Column(name = "active_hour_start")
    private Integer activeHourStart;

    /** 活跃结束小时(0-23) */
    @Column(name = "active_hour_end")
    private Integer activeHourEnd;

    /** 小时分布计数(键: 0-23, 值: 出现次数), 用于活跃时段 */
    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> hourDistribution;

    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt = LocalDateTime.now();

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
