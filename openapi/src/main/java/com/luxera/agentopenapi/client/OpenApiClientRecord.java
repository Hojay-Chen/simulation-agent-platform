package com.luxera.agentopenapi.client;

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
 * G4 — 对外 OpenAPI 的<b>机器客户端</b>身份。
 *
 * <p>三方不是真人: 不复用 {@code users} 表(那是 chat 平台的域, 注册也已关闭),
 * 也不走 JWT —— 机器客户端只有一把 API Key。Key 只在创建时明文返回一次,
 * 库里存 sha256(hex); 明文永不回查、不进日志(前 8 字符的 prefix 列仅供
 * 人眼辨认是哪把 key)。
 *
 * <p>归属映射: 三方建的 agent 落 {@code companions} 表时, {@code user_id} 放
 * 本表的 id —— 仓 1 的 {@code CompanionDirectoryPort.requireOwned} 因此天然工作,
 * 认知链(server:8091)照常把它当自己的 agent 推进, 跨服务零改动。
 */
@Entity
@Table(name = "openapi_clients", indexes = {
        @Index(name = "idx_openapi_clients_key", columnList = "api_key_hash", unique = true),
})
@Getter
@Setter
public class OpenApiClientRecord {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    /** sha256 hex(明文 key) — 验证即再哈希比对。API key 是 64 字符随机熵, 不需要 BCrypt 的慢哈希。 */
    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    /** 明文 key 前 8 字符, 仅供人眼辨认(日志/排错说"是 sap_ab12... 这把")。 */
    @Column(name = "api_key_prefix", nullable = false, length = 16)
    private String apiKeyPrefix;

    /** 可空 — 将来挂真人所有者(管理面看到"这是谁的客户端")。 */
    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

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
