package com.luxera.companion.outbox;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V10 §21.3 outbox_event: 可靠发布的事件待发表。
 *
 * 业务状态与事件发布在同一事务提交(业务提交 = 事件入队),
 * 由 OutboxRelayJob 异步发布 —— 禁止 "先更新数据库再发布 MQ" 的双写。
 *
 * 状态机: PENDING → PUBLISHED; 失败重试(退避), 超过上限 → FAILED。
 * event_key 唯一: 同 key 幂等入队(不重复入队)。
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_person", columnList = "person_id"),
        @Index(name = "idx_outbox_status", columnList = "status, next_attempt_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_outbox_key", columnNames = "event_key"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    /** 最大重试次数(超过标记 FAILED, 由人工/巡检处理) */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    /** 幂等键(同 key 不重复入队) */
    @Column(name = "event_key", nullable = false, length = 128)
    private String eventKey;

    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    /** ExternalEventType 名称 */
    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload", columnDefinition = "text")
    private Map<String, Object> payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public void assignIdIfMissing() {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
    }
}
