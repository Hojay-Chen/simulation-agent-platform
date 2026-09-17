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
 *
 * <p><b>{@link #canActForUsers} 是一道默认关闭的闸门。</b>开着它, 这个客户端才可以在
 * 创建 agent 时指定 {@code ownerUserId = 某个真人} —— 也就是"代建"。聊天平台的一键创建
 * 需要这个能力(它替登录用户建 agent, 而那个 agent 必须归**用户**所有, 否则用户在自己的
 * 通讯录里看不到它)。默认 false 意味着: 新登记的一把 {@code sap_} key 永远只能给自己
 * 建 agent, 想代建得由平台显式开。
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

    /**
     * 这个客户端能否在创建 agent 时指定 {@code ownerUserId}(代建)。默认 false。
     *
     * <h2>为什么是可空列 + 包装类型 + 一个自己的读取方法</h2>
     *
     * 三件事都是为了同一个坑: 这一列是**加在有数据的表上的新列**。
     *
     * <ol>
     *   <li>{@code nullable = false} 会直接失败 —— {@code ddl-auto: update} 生成的是
     *       {@code add column ... boolean not null}(不带默认值), 而 PostgreSQL 在表里
     *       已经有行时拒绝这条语句。所以列必须可空, 既有的行落成 NULL。</li>
     *   <li>NULL 落到 {@code boolean} 基本类型上会在读取时抛, 所以字段用 {@code Boolean}。</li>
     *   <li>读取一律走 {@link #canActForUsers()} 而不是 Lombok 的
     *       {@code getCanActForUsers()} —— 后者把 null 原样交出去, 调用点那句
     *       {@code if (!client.canActForUsers())} 就还安全(基本类型方法签名), 但任何直接
     *       比较 {@code Boolean} 的写法都会掉进"null 不等于 true 也不等于 false"的缝里。
     *       <b>闸门读错方向比闸门不存在更糟: 它看起来是开着的。</b></li>
     * </ol>
     */
    @Column(name = "can_act_for_users")
    private Boolean canActForUsers = false;

    /**
     * 代建权限的**唯一**读法 —— 把 null(加列之前建的行)收成 false。
     *
     * <p>与 Lombok 生成的 {@code getCanActForUsers()} 并存不冲突, 但调用方只该用这一个:
     * 名字短、返回基本类型、且"没有值 = 没有权限"这条默认写死在方法体里而不是散在调用点。
     */
    public boolean canActForUsers() {
        return Boolean.TRUE.equals(canActForUsers);
    }

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
