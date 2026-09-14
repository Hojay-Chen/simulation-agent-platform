package com.luxera.companion.intention;

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
 * §35 Intention Memory: 意图记忆。
 * 不是普通记忆, 而是"未来行为的潜在触发器"。
 * 例如"等下我要告诉他一件事" / "我本来想回他那句的, 被打断了"。
 *
 * 意图可以衰减/遗忘/重新激活:
 * - activation_probability: 未来被想起的概率
 * - expected_time: 期望执行时间(到点附近激活概率高)
 * - expiry_time: 过期后遗忘(真人会忘)
 */
@Entity
@Table(name = "intentions", indexes = @Index(name = "idx_intention_companion", columnList = "companion_id"))
@Getter
@Setter
public class Intention {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "companion_id", nullable = false, length = 36)
    private String companionId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** 意图内容("想告诉他一件事" / "该回复他了") */
    @Column(nullable = false, length = 500)
    private String content;

    /** 重要性 0-1 */
    @Column(nullable = false)
    private double importance = 0.5;

    /** 关联情绪(想分享/内疚/担心) */
    @Column(length = 32)
    private String emotion;

    /** 目标对象(user / friend / self) */
    @Column(nullable = false, length = 16)
    private String target = "user";

    /** 期望执行时间(到点附近激活概率高) */
    @Column(name = "expected_time")
    private LocalDateTime expectedTime;

    /** 过期时间(超过则遗忘) */
    @Column(name = "expiry_time")
    private LocalDateTime expiryTime;

    /** 未来被想起的概率 0-1(随时间衰减, 到 expected_time 附近回升) */
    @Column(name = "activation_probability", nullable = false)
    private double activationProbability = 0.5;

    /** ACTIVE / ACTED / EXPIRED / FORGOTTEN */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

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
