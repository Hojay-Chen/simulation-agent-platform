package com.luxera.companion.digitalhuman.event;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * V10 §21.2 processed_event: 事件幂等表。
 *
 * 每个事件处理成功后落一条记录; 重复事件(eventId 相同)被 DeduplicationHandler
 * 短路, 保证"重试不产生重复副作用"(V10 MVP 验收 13)。
 */
@Entity
@Table(name = "processed_event", indexes = {
        @javax.persistence.Index(name = "idx_processed_person", columnList = "person_id")
})
@Getter
@Setter
public class ProcessedEventRecord {

    @Id
    @Column(name = "event_id", length = 96)
    private String eventId;

    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "note", length = 255)
    private String note;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;

    public static ProcessedEventRecord of(ExternalEvent event, String note) {
        ProcessedEventRecord record = new ProcessedEventRecord();
        record.setEventId(event.eventId());
        record.setPersonId(event.personId());
        record.setEventType(event.type().name());
        record.setNote(note);
        return record;
    }
}
