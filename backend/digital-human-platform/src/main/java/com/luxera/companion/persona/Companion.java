package com.luxera.companion.persona;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.UUID;

/**
 * 数字人格实例: 用户真正长期相处的 Companion。
 * 年龄永远通过 birth_date 动态计算。
 *
 * <h2>三个 id, 永不互换</h2>
 *
 * <ul>
 *   <li>{@link #id} —— agent 平台标识**这个 agent 个体**的值。只在对接 agent 平台的
 *       程序之间流通; 人念不出来, 也不该出现在聊天界面里。</li>
 *   <li>{@link #chatAccountId} —— 它在**聊天平台**的账号 id, 也就是聊天平台的
 *       {@code users.id}。它回答"这个 agent 用哪个账号说话", 是 agent 侧认识聊天平台的
 *       唯一落点。</li>
 *   <li>{@link #userId} —— 这个 agent **归谁所有**(真人)。它决定"谁能在通讯录里看到它"。
 *       第三方程序自助创建的 agent 会把它设成自己的客户端 id, 那是"客户端即所有者"
 *       那种形状; 聊天平台一键创建时它是**真人**的 id。</li>
 * </ul>
 *
 * <p>这三者此前是搅在一起的(agent 的 Person 行直接复用 companion.id, 而聊天账号
 * 根本没有列), 于是"哪个是哪个"只能靠上下文猜。现在它们各占一列, 各自回答一个问题。
 */
@Entity
@Table(name = "companions")
@Getter
@Setter
public class Companion {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /**
     * 该 agent 在**聊天平台**的账号 id = 聊天平台的 {@code users.id}。**不是本表的 id**。
     *
     * <p>可空 + 唯一, 两个性质都是设计的一部分:
     *
     * <ul>
     *   <li><b>可空</b> —— 第三方程序经 openAPI 自助创建的 agent 没有聊天账号(它们不
     *       通过聊天平台说话), 加列时库里已有的行也没有。{@code ddl-auto: update} 本来就
     *       加不了 NOT NULL 列, 而这一次"可空"是语义上正确的, 不只是权宜。</li>
     *   <li><b>唯一</b> —— 它同时是**幂等键**: 一个聊天账号最多登记一个 agent。重试
     *       ("建了但响应丢了")因此安全: 再调一次拿到的是同一个 agent, 而不是第二个。</li>
     * </ul>
     *
     * <p>放**列**而不是 {@code persons.metadata} 那个 JSON: JSON 不可索引, 而对账要
     * "拿着聊天账号反查 agent", 且唯一约束正是幂等性的实现。
     */
    @Column(name = "chat_account_id", length = 36, unique = true)
    private String chatAccountId;

    /**
     * 这个 agent 是**哪个 API 客户端**建的。恒写调用方的 clientId(真人经聊天平台创建时
     * 为空 —— 那条路上没有客户端这回事)。
     *
     * <p>与 {@link #userId} 分开是因为它们回答两个不同的问题: {@code user_id} 回答
     * "这是谁的好友", 而本列回答"这是谁建的"。绝大多数时候两者一致(第三方自建自用),
     * 但**代建**时不一致: 聊天平台以真人 U 的名义建了一个 agent, 那一行是
     * {@code user_id = U}, {@code created_by_client_id = 聊天平台的客户端}。
     *
     * <p>不合并的代价是 8092 的可见性判据要写三个分支(见
     * {@code CompanionRepository#findVisibleToClient}), 收益是
     * {@code CompanionService#requireOwned} —— 鉴权主路径, 有四个调用点 —— 一个字都不用动。
     * 代建的 agent 因此**看得见、但改不动也删不掉**: 它属于那个真人, 不属于客户端。
     */
    @Column(name = "created_by_client_id", length = 36)
    private String createdByClientId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Convert(converter = PlaceJsonConverter.class)
    @Column(name = "birth_place", columnDefinition = "text")
    private Place birthPlace;

    @Column(length = 64)
    private String nationality = "Chinese";

    @Column(length = 64)
    private String timezone = "Asia/Shanghai";

    @Column(length = 500)
    private String greeting;

    @Column(length = 32)
    private String status = "active";

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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

    /** 动态年龄 */
    public int age() {
        if (birthDate == null) return 0;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }
}
