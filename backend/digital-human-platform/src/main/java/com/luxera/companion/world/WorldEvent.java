package com.luxera.companion.world;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * §四十三~§四十六 WorldEvent: 数字人世界中的事件(持久化)。
 *
 * 事件分类: Communication / Life / Body / Social / Memory / Intention / Environment。
 * 用户消息只是其中一种事件; 时间、生活活动结束、睡眠压力变化、情绪变化、
 * 关系变化、记忆激活、意图激活都会产生事件 —— 世界每时每刻都在运行,
 * 而不是"用户发消息世界才开始"。
 *
 * causationId/correlationId: 因果链与相关性(事件溯源基础)。
 */
@Entity
@Table(name = "digital_world_events", indexes = {
        @Index(name = "idx_dwe_agent", columnList = "agent_id,id"),
        @Index(name = "idx_dwe_time", columnList = "occurred_at"),
        @Index(name = "idx_dwe_type", columnList = "agent_id,type")
})
@Getter
@Setter
public class WorldEvent {

    /** 事件分类常量 */
    public static final String SRC_COMMUNICATION = "COMMUNICATION";
    public static final String SRC_LIFE = "LIFE";
    public static final String SRC_BODY = "BODY";
    public static final String SRC_SOCIAL = "SOCIAL";
    public static final String SRC_MEMORY = "MEMORY";
    public static final String SRC_INTENTION = "INTENTION";
    public static final String SRC_ENV = "ENV";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "agent_id", nullable = false, length = 36)
    private String agentId;

    /** 事件类型: TIME_TICK / MESSAGE_CREATED / ACTIVITY_STARTED / ACTIVITY_FINISHED / SLEEP_PRESSURE_CHANGED / SOCIAL_SILENCE / ... */
    @Column(nullable = false, length = 48)
    private String type;

    /** 来源分类(COMMUNICATION/LIFE/BODY/SOCIAL/MEMORY/INTENTION/ENV) */
    @Column(nullable = false, length = 24)
    private String source;

    /** 事件主体引用(如 messageId / activityId) */
    @Column(length = 64)
    private String subject;

    /** 事件目标(person id / conversation id) */
    @Column(length = 64)
    private String target;

    @Convert(converter = StringMapConverter.class)
    @Column(columnDefinition = "text")
    private Map<String, Object> payload;

    /** 0-1 重要性(供唤醒/行为选择参考) */
    @Column(nullable = false)
    private double importance = 0.3;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "causation_id", length = 64)
    private String causationId;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    /** BehaviorEngine 处理时间(为空 = 尚未处理) */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
