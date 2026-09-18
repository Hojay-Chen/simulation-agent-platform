package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.RelationshipRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §3.4.6 —— {@code relationship} 的读口。
 *
 * <h2>对称性: 同一条"关系"会被查两次, 而这是设计的必然</h2>
 * 见 {@link RelationshipRecord} 的类注释: 关系是<b>不对称</b>的
 * （她认为他是普通同学, 他认为她是最好的朋友）。于是"影响力最大的是哪几段关系"
 * 与"她对谁的影响力最大"是两个不同的查询, 走两条不同的索引:
 * <pre>
 *   findByHumanIdOrderByStrengthDesc   →  idx_relationship_human_strength
 *   findByPersonIdOrderByStrengthDesc  →  idx_relationship_person
 * </pre>
 * 只留其中一个的后果是另一半问题只能靠全表扫 —— 而那是"她的关系网越大越慢"的来源。
 *
 * <h2>为什么"改主意"用 {@code save} 而不是删除重建</h2>
 * 关系强度是会变的（{@code RelationshipEngine} 的衰减与增长）。
 * 一个已经被创建的关系, 它的 {@code relationship_id} 是稳定的 ——
 * 删掉重建会得到一个新的 id, 而这个 id 会被别的表引用
 * （{@code Promise} 在旧模型里就是按 {@code relationship_id} 关联的）。
 * 所以这里<b>不提供</b>按 (humanId, personId) 的删除方法:
 * 需要"断开一段关系"时, 正确的做法是让它的 {@code strength} 降到 0 或者
 * 换一个表示"已经不再来往"的关系类型（一个新的 {@code @DomainType}）——
 * 而不是把那一行抹掉。历史留下了, 就能回答"她和他什么时候开始疏远的"。
 */
public interface RelationshipRecordRepository extends JpaRepository<RelationshipRecord, String> {

    /**
     * 她最亲近的几段关系 —— 走 {@code idx_relationship_human_strength}, 倒序。
     *
     * <p>倒序是本查询的语义本身（"最亲近"）, 不是排版习惯:
     * 正序会得到"她最疏远的人", 而这两个结果的用途完全不同。
     */
    List<RelationshipRecord> findByHumanIdOrderByStrengthDesc(String humanId);

    /** 她的全部关系 —— 启动时加载 {@code RelationshipGraph} 用, 顺序稳定即可。 */
    List<RelationshipRecord> findByHumanIdOrderByRelationshipIdAsc(String humanId);

    /**
     * "她与这个人是什么关系" —— 单向查询, 返回 {@code Optional}。
     *
     * <p>为什么是 {@code Optional}: 关系在<b>值</b>上是一对一的
     * （一个 agent 对同一个人物对象只有一种当前立场）, 而"她改主意了"的存储形态是
     * 换一个关系类型 — 而本表没有唯一约束（本仓惯例, 见
     * {@code DeviceApplicationRecord#getId()} 的说明）, 所以重复行在数据库层面
     * 是可能的。用 {@code Optional} 让重复变成一个会抛的异常,
     * 而不是一个被静默忽略的第二行 —— 那个第二行的后果是"她的亲密值有两个值,
     * 而代码读到哪一个取决于数据库返回顺序"。
     */
    Optional<RelationshipRecord> findFirstByHumanIdAndPersonIdOrderByUpdatedAtDesc(
            String humanId, String personId);

    /**
     * 她与这个人的关系历史（换过几次关系类型 / 改过几次).
     *
     * <p>按 {@code establishedSimAt} 正序 —— 读的是故事, 不是最新状态。
     */
    List<RelationshipRecord> findByHumanIdAndPersonIdOrderByEstablishedSimAtAsc(
            String humanId, String personId);

    /**
     * 按关系类型筛 —— 走 {@code idx_relationship_type}。
     *
     * <p>见 {@link RelationshipRecord} 的类注释: 关系种类是类型三元组
     * （{@code ClassmateRelationship} / {@code MentorRelationship} 各是一个
     * {@code @DomainType}）, 于是"她的通讯录里有几个同学"是一次索引查询, 零 DDL。
     */
    List<RelationshipRecord> findByHumanIdAndRelationshipTypeNamespaceAndRelationshipTypeName(
            String humanId, String relationshipTypeNamespace, String relationshipTypeName);

    /** 她有几段关系。 */
    long countByHumanId(String humanId);

    /** 亲密度高于某个值的有几个 —— "她有几个亲近的人"。 */
    long countByHumanIdAndStrengthGreaterThanEqual(String humanId, double threshold);
}
