package com.luxera.companion.runtime.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 未回复消息状态(§79): 消息被读到但本次没有回复 → 记录待复查状态。
 * 形成"看到了但不回, 稍后可能回"的行为连续性。
 * 服务重启也不丢 —— 真人不会因为服务器重启而忘记没回的消息。
 */
@Entity
@Table(name = "pending_message_states", indexes = {
        @Index(name = "idx_pending_review", columnList = "status,next_review_at"),
        @Index(name = "idx_pending_message", columnList = "message_id")
})
@Getter
@Setter
public class PendingMessageState {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REPLIED = "REPLIED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "sender_text", columnDefinition = "text")
    private String senderText;

    /** 是否已读 */
    @Column(nullable = false)
    private boolean read = true;

    /** 是否已回复 */
    @Column(nullable = false)
    private boolean replied = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 下次复查时间(到点唤醒 Brain 重新评估) */
    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    /** 当时不回的原因 */
    @Column(length = 500)
    private String reason;

    // ── §54 Communication Friction ──────────────
    /** 摩擦类型: SEEN_NO_REPLY(看到了没回) / WANTED_TO_REPLY_FORGOT(想回忘了) / REPLIED_HALFWAY(回一半被打断) */
    @Column(name = "friction_type", length = 32)
    private String frictionType;

    /** 复查次数(每次复查 +1; 多次仍不回 → 人偶尔会忘记) */
    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

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
