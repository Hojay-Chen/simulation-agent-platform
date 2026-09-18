package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §3.4.6 —— {@code relationship}: <b>她与某个 {@code PersonObject} 的关系</b>
 * （同学 / 好友 / 导师）。
 *
 * <h2>它与 {@link PersonObjectRecord} 的分工, 一句话说清</h2>
 * <pre>
 *   person_object  ——  "这个人是谁"（名字、印象、情感）—— 她的<b>认识</b>
 *   relationship   ——  "我和他是什么关系、现在有多近"  —— 她的<b>立场</b>
 * </pre>
 * 之所以必须是两张表而不是一张: 关系有<b>方向与不对称</b>。
 * <pre>
 *   她认为他是"普通同学"      而 他认为她是"最好的朋友"
 *   她对他的亲近度是 0.3      而 他对她是 0.9
 * </pre>
 * 合并成一张表的后果是二者只能共享一个字段, 于是"她单方面亲近一个不亲近她的人"
 * 这个真实且重要的状态无法表达 —— 而它恰恰是社交仿真里最有价值的输入之一。
 *
 * <h2>为什么 {@code relationship_kind} 不是一列 —— 而这是刻意的</h2>
 * 本仓旧模型里关系类型是一个字段（{@code relationship/RelationshipTypes}),
 * 而 §3.4.6 的 {@code bootstrap} 也写了"relation 由 persona 指定
 * （'朋友' / '恋人' / '同学'…）"—— 看起来它显然该是一列。这里不这么做, 理由是 P4:
 * <blockquote>
 * 关系类型是<b>三方的扩展点</b>。一个"她与导师的联合培养关系"这种类型,
 * 平台不该预先知道; 而每加一种关系类型做一次 DDL, 正是 §7.1 要防的
 * "每加一种行为就要一次 DDL"。
 * </blockquote>
 * 所以关系类型由<b>类型三元组</b>承载: {@code ClassmateRelationship} /
 * {@code MentorRelationship} 各是一个 {@code @DomainType} 标注的实现类,
 * 加一种关系 = 加一个类 = 零 DDL。而"她的通讯录里有几个同学"这类查询
 * <b>仍然走索引</b>（{@code idx_relationship_type}）, 因为三元组是真正的列。
 *
 * <h2>{@code strength} 为什么拆成列, 而它显然能"从载荷里算出来"</h2>
 * 亲密值（或任何等价的名字）是这张表上唯一被频繁<b>排序与比较</b>的量:
 * <pre>
 *   "她最亲近的三个人是谁"        →  ORDER BY strength DESC LIMIT 3
 *   "哪些关系需要维护了"          →  WHERE strength &lt; ?
 * </pre>
 * 而它在载荷里是一个 {@code double} —— 试图从 JSON 里排序会让每一条这样的查询
 * 都变成全表读 + 内存排序。拆出来是"写一次、读很多次"的冗余, 判断依据与
 * {@code plan_item.human_id} 那一处相同。
 *
 * <p>代价: 它与载荷里的同名字段可能不一致。这里沿用
 * {@code ContinuousEffectRecord} 定下的纪律 —— <b>读的时候以列</b>为准 ——
 * 而不是让两处各自解释。
 */
@Entity
@Table(name = "relationship", indexes = {
        // ① 主查询: "她的关系网" —— 按亲近度排。
        //    等值列 human_id + 排序列 strength。见类注释"为什么 strength 拆成列"。
        @Index(name = "idx_relationship_human_strength", columnList = "human_id,strength"),
        // ② 反向查: "谁和她有关系" —— 用于对方那一侧的分析（她被人怎么看待）。
        //    它不会走 ①, 因为 ① 的第一列是 human_id。
        @Index(name = "idx_relationship_person", columnList = "person_id"),
        // ③ 按关系类型筛与统计 —— 见类注释"为什么 relationship_kind 不是一列"。
        @Index(name = "idx_relationship_type",
                columnList = "relationship_type_namespace,relationship_type_name")
})
@Getter
@Setter
public class RelationshipRecord {

    /** 关系 id —— 由领域生成。 */
    @Id
    @Column(name = "relationship_id", length = 64)
    private String relationshipId;

    /** <b>谁的</b>立场 —— agent 的 id。见类注释"关系是不对称的": 这一列决定这是她的看法。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /** 关系指向谁 —— {@link PersonObjectRecord#getPersonId()}。 */
    @Column(name = "person_id", nullable = false, length = 64)
    private String personId;

    /** 关系类型三元组 —— 见类注释"为什么 relationship_kind 不是一列"。 */
    @Column(name = "relationship_type_namespace", nullable = false, length = 96)
    private String relationshipTypeNamespace;

    @Column(name = "relationship_type_name", nullable = false, length = 96)
    private String relationshipTypeName;

    @Column(name = "relationship_type_version", nullable = false)
    private int relationshipTypeVersion;

    /**
     * 亲近度 —— 见类注释"为什么 strength 拆成列"。
     *
     * <p>量纲由关系类型决定（{@code 0..1} 是平台约定的范围,
     * {@code RelationshipEngine} 的衰减与增长规则作用在它上面）。
     * 本层<b>不校验范围</b>: 一个越界的值说明写入方算错了, 而把它静默夹到
     * {@code [0,1]} 会让那个 bug 永远查不出来 —— 这正是 {@code ActionResult}
     * 选择"返回 rejected 而不是抛异常"时相反的一侧判断: 能自愈的错就自愈,
     * 不能自愈的错就让它可见。
     */
    @Column(name = "strength", nullable = false)
    private double strength;

    /** 关系建立时刻（仿真时刻）。 */
    @Column(name = "established_sim_at")
    private Instant establishedSimAt;

    /**
     * 关系自己的状态（她对他的期望、她记得的承诺、她对他的称呼…）。
     *
     * <p>去掉它, 恢复出来的关系就只剩一个数字。而"她答应过他周末帮她补课"
     * 这类内容没有别的归属 —— {@code Promise} 在旧模型里是独立的表,
     * 而 V2.2 的统一策略是"稳定的关系型元数据 + 多态载荷":
     * 承诺有<b>到期时刻</b>那种需要被查询的量才值得拆出来,
     * 而在 V2.2 的关系模型里那个量还没有被定义 —— 定义它之前先不拆列,
     * 是"不为还不存在的查询建索引"的同一条纪律。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload_json", columnDefinition = "text")
    private Map<String, Object> payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (relationshipId == null) {
            relationshipId = "rel-unknown-" + UUID.randomUUID();
        }
    }

    public String describe() {
        return relationshipTypeNamespace + "." + relationshipTypeName
                + " " + humanId + " → " + personId + " (" + strength + ")";
    }
}
