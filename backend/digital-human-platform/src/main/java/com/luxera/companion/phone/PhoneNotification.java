package com.luxera.companion.phone;

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
 * §15-§17 Phone Notification: 手机通知生命周期。
 * 用户消息 → 手机通知产生 → Agent 听到(heard)/看到(seen)/打开(opened)/阅读(read)。
 * 每一步都不是必然发生 —— 这是"她到底经历了什么"的持久记录。
 */
@Entity
@Table(name = "phone_notifications", indexes = {
        @Index(name = "idx_phone_notif_companion", columnList = "companion_id"),
        @Index(name = "idx_phone_notif_message", columnList = "message_id")
})
@Getter
@Setter
public class PhoneNotification {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** 通知来源: CHAT / MENTION / CALL / SYSTEM */
    @Column(name = "source_app", nullable = false, length = 16)
    private String sourceApp = "CHAT";

    /** 通知类型: MESSAGE / MENTION / CALL / SYSTEM */
    @Column(name = "notification_type", nullable = false, length = 16)
    private String notificationType = "MESSAGE";

    /** 消息摘要(通知里显示的预览) */
    @Column(length = 200)
    private String preview;

    /** 手机是否响铃/震动(静音/勿扰 → false) */
    @Column(nullable = false)
    private boolean sound = false;

    @Column(nullable = false)
    private boolean vibration = false;

    /** 通知是否送达手机 */
    @Column(nullable = false)
    private boolean delivered = false;

    /** Agent 是否听到(在手机附近 + 有声/震动) */
    @Column(nullable = false)
    private boolean heard = false;

    /** Agent 是否瞥见通知(屏幕亮/看到横幅) */
    @Column(nullable = false)
    private boolean seen = false;

    /** Agent 是否打开聊天 */
    @Column(nullable = false)
    private boolean opened = false;

    /** Agent 是否已读 */
    @Column(nullable = false)
    private boolean read = false;

    /** 各阶段时间戳 */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "heard_at")
    private LocalDateTime heardAt;

    @Column(name = "seen_at")
    private LocalDateTime seenAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

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
