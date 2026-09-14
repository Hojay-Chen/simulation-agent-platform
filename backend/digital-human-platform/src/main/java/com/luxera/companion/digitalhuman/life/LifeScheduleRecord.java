package com.luxera.companion.digitalhuman.life;

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
 * life_schedule: 生活事件排程表(时间触发的持久化载体)。
 * 状态: PENDING → DONE; 执行失败 → FAILED(重试由下一次轮询兜底)。
 */
@Entity
@Table(name = "life_schedule", indexes = {
        @Index(name = "idx_life_schedule_person", columnList = "person_id"),
        @Index(name = "idx_life_schedule_due", columnList = "status, fire_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_life_schedule_id", columnNames = "schedule_id"))
@Getter
@Setter
@NoArgsConstructor
public class LifeScheduleRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "schedule_id", nullable = false, length = 64)
    private String scheduleId;

    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "fire_at", nullable = false)
    private LocalDateTime fireAt;

    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload", columnDefinition = "text")
    private Map<String, Object> payload;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fired_at")
    private LocalDateTime firedAt;

    public void assignIdIfMissing() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
