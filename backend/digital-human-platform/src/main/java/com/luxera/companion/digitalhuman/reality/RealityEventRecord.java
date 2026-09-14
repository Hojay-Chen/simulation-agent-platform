package com.luxera.companion.digitalhuman.reality;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * V10 §22.2 timeline_event: Reality Ledger 持久化实体。
 *
 * - @Immutable: 禁止 Hibernate 更新/删除(append-only, 与方案禁止 UPDATE 一致);
 * - 事件一旦写入, 只允许追加, 不允许修改;
 * - 若事实变化(PlanChanged/ActivityEnded), 写入新事件。
 */
@Entity
@Immutable
@Table(name = "timeline_event", indexes = {
        @Index(name = "idx_timeline_person", columnList = "person_id"),
        @Index(name = "idx_timeline_person_time", columnList = "person_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RealityEventRecord {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload", columnDefinition = "text")
    private Map<String, Object> payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "causation_id", length = 64)
    private String causationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }

    public static RealityEventRecord from(RealityEvent event) {
        RealityEventRecord record = new RealityEventRecord();
        record.setEventId(event.eventId());
        record.setPersonId(event.personId());
        record.setEventType(event.type().name());
        record.setOccurredAt(LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        record.setPayload(event.payload());
        record.setCorrelationId(event.correlationId());
        record.setCausationId(event.causationId());
        return record;
    }

    public RealityEvent toEvent() {
        Instant at = occurredAt == null ? Instant.now() : occurredAt.toInstant(ZoneOffset.UTC);
        return new RealityEvent(eventId, personId,
                RealityEventType.valueOf(eventType), at, payload, correlationId, causationId);
    }
}
