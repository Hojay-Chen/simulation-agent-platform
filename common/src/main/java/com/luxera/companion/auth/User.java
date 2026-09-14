package com.luxera.companion.auth;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(unique = true, length = 128)
    private String email;

    @Column(length = 64)
    private String nickname;

    /** 用户所在时区,如 Asia/Shanghai */
    @Column(length = 64)
    private String timezone = "Asia/Shanghai";

    /** 用户生日(可选),伴侣会知道 */
    private LocalDate birthDate;

    @Column(length = 16)
    private String gender;

    /**
     * V10 §29-§30: 账号类型。SIMULATOR 账号是数字人的"手机"登录的普通用户行 ——
     * 业务代码不得据此做任何 Agent 特殊逻辑(无 BotUser 语义),仅用于:
     * 1) 前端用户列表过滤(不展示 sim- 账号);2) 审计。
     * 对 Chat Platform 的消息/会话/已读链路, SIMULATOR 与 HUMAN 完全同权同路径。
     */
    @Column(name = "user_kind", nullable = false, length = 16)
    private String userKind = "HUMAN";

    /** V10: 展示名(simulator 账号 = 伴侣名; 普通用户 = 昵称冗余, 可空) */
    @Column(name = "display_name", length = 64)
    private String displayName;

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
