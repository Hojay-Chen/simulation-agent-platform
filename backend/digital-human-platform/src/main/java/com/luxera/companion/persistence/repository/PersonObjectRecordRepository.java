package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.PersonObjectRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2.2 §3.4.6 —— {@code person_object} 的读口: <b>她的通讯录</b>。
 *
 * <h2>{@link #findByHumanIdOrderByDisplayNameAsc} 是启动时的一次性加载</h2>
 * {@code RelationshipGraph} 里有 {@code Map<PersonId, PersonObject> persons} ——
 * 一个 agent 认识的人通常是个位到几十位, 不是一个需要分页的量。
 * 于是正确的做法是<b>启动时一次读进来</b>, 之后 {@code resolve(accountId)}
 * 全在内存里做。
 *
 * <p>这也是为什么这个接口里没有"按 id 查单个"的强调: 一次一个地查人物对象
 * 会让"她见到一个人"变成一次数据库往返, 而那个动作在她每一次感知里都会发生。
 *
 * <h2>每个方法都带 {@code humanId} —— 这是本表的硬约束, 不是可选过滤</h2>
 * 见 {@link PersonObjectRecord} 的类注释: 同一个真人在两个 agent 的心里有两条行。
 * 一个<b>不带</b> {@code humanId} 的查询（例如 {@code findById}）在单一 agent 的
 * 测试里看起来完全正常, 而在两个 agent 共库时会返回<b>另一个 agent 心里的那个人</b>——
 * 表现是"她忽然用别人的方式称呼用户"。所以本接口刻意不提供
 * "只按 {@code personId} 查"的方法。
 *
 * <p>{@code findById} 是继承来的, 无法去掉 —— 但它的正确性由
 * {@code person_id} 在全库唯一保证（每个 agent 生成自己的 id）。
 * 这一点必须写下来, 否则下一个人会以为 {@code personId} 只是"在这个 agent 内唯一"。
 */
public interface PersonObjectRecordRepository extends JpaRepository<PersonObjectRecord, String> {

    /**
     * 启动时加载她的通讯录 —— 走 {@code idx_person_object_human_name}。
     *
     * <p>按名字排序在 SQL 里做, 理由与 {@code WorldObjectRecordRepository} 的装配查询相同。
     */
    List<PersonObjectRecord> findByHumanIdOrderByDisplayNameAsc(String humanId);

    /**
     * 按类型筛: "她认识几个同学 / 几个家人" —— 走 {@code idx_person_object_type}。
     *
     * <p>关系种类是类型三元组而不是一列（见 {@code RelationshipRecord} 的类注释
     * 关于 "为什么 relationship_kind 不是一列"）—— 于是这个问题是一次索引查询,
     * 而不是把她的全部人物对象读进内存再分类。
     */
    List<PersonObjectRecord> findByHumanIdAndPersonTypeNamespaceAndPersonTypeName(
            String humanId, String personTypeNamespace, String personTypeName);

    /**
     * 按名字找 —— 给控制台的模糊查找与"她说的这个人是不是她认识的"这类判断。
     *
     * <p>用 {@code Containing}（{@code LIKE '%…%'}）是刻意的: 名字是自然语言,
     * 而"她记得的那个人叫小什么"是一个真实的问题。代价必须写清楚:
     * <b>{@code Containing} 用不上 {@code idx_person_object_human_name} 的排序列</b>
     * （前导通配符使 B-tree 索引失效）, 所以它只给控制台用, 不在 tick 路径上。
     */
    List<PersonObjectRecord> findByHumanIdAndDisplayNameContaining(String humanId, String namePart);

    /** 她认识多少人。 */
    long countByHumanId(String humanId);

    /** 这个 agent 心里有没有这个人（用于绑定落库前的完整性检查）。 */
    boolean existsByHumanIdAndPersonId(String humanId, String personId);
}
