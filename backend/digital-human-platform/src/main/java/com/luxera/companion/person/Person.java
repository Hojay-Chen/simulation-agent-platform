package com.luxera.companion.person;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

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
 * §五 Person: 数字人世界中的"人"。
 *
 * 关键设计: **User / Agent / OtherPerson 都是 Person**, 不再各自特殊处理。
 * - USER  → id 沿用 users.id(零迁移)
 * - AGENT → id 沿用 companions.id(零迁移)
 * - OTHER → 数字人的其他社会关系(朋友/家人/同事), UUID 独立生成
 *
 * 全部现有表仍以 user_id/companion_id 关联; persons 是"身份层", 不改变 ID 体系。
 */
@Entity
@Table(name = "persons", indexes = {
        @javax.persistence.Index(name = "idx_persons_user", columnList = "user_id"),
        @javax.persistence.Index(name = "idx_persons_companion", columnList = "companion_id")
})
@Getter
@Setter
public class Person {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_OTHER = "OTHER";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** USER / AGENT / OTHER */
    @Column(name = "person_type", nullable = false, length = 16)
    private String personType;

    /** USER 时: 对应 users.id */
    @Column(name = "user_id", length = 36)
    private String userId;

    /** AGENT 时: 对应 companions.id */
    @Column(name = "companion_id", length = 36)
    private String companionId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String gender;

    /** 关系称呼/画像等(OTHER 人物用) */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "metadata", columnDefinition = "text")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
