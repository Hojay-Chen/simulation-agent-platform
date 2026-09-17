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

    /**
     * 账号ID —— 这个"人"在平台上的唯一地址。**与 {@link #name} 正交**: 名字可以重名
     * (而且在这个平台里必然重名, 因为名字是 LLM 生成的、相似描述会收敛到同一个, 见
     * {@link Handles} 的类注释), 账号ID 必须唯一。等价的现成概念是微信号。
     *
     * <h3>为什么可空</h3>
     *
     * {@code ddl-auto: update} 只能给已有表**加可空列** —— 给一张已经有数据的表加 NOT NULL
     * 列需要默认值, 而"所有人的默认账号ID 都一样"恰恰违反了唯一性。所以这一列在数据库层
     * 是 nullable, 由 {@code PersonHandleBackfill} 在启动时给老行补号, 新行在
     * {@code PersonService} 里建的时候就带上。
     *
     * <p>因此**读的人要能接受 null**: 补号还没跑完、或者补号失败(唯一性撞车会重试, 但
     * 极端情况下会给不出), 这一列就是 null。界面上表现为"这行没有账号ID", 而不是崩溃。
     *
     * <h3>为什么是数据库唯一约束, 不是只靠代码查重</h3>
     *
     * 代码里的"查一下有没有被占用, 没有就写"两步之间存在窗口: 两个人同时把账号ID 改成
     * 同一个, 两边都查不到、两边都写成功。唯一约束把并发交给数据库裁决 —— 第二个事务
     * 拿到约束冲突, 报"已被占用"而不是静默产生两个同号的人。代码里那次查重仍然保留,
     * 但它的职责只是**给出友好的错误信息**, 不是保证正确性。
     *
     * <p>删除 Agent **不会**释放它的账号ID(软删的行还占着这个值)。这是有意的: 一个
     * 曾经属于某个 Agent 的账号ID 若能被别人捡走, 就成了一条冒充路径 —— 别人手里那个
     * 旧 ID 会指向一个陌生人。空间足够大(31^10), 烧掉几个不可惜。
     */
    @Column(name = "handle", length = 32, unique = true)
    private String handle;

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
