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
 * V2.2 §3.4.6 —— {@code person_object}: <b>她心里的"一个人"</b>。
 *
 * <h2>这张表不存"人", 存"她认为的那个人"</h2>
 * §3.4 的对象总表里 {@code PersonObject} 的归属写得明确: <b>Human (Mind)</b>。
 * 这一条归属决定了整张表的形状, 而且是它最容易做错的地方:
 * <pre>
 *   错:   person_object 是"用户表"的又一版  →  于是它会开始出现头像、邮箱、注册时间
 *   对:   person_object 是"她对某人的印象"  →  于是它只有名字、关系、印象、情感
 * </pre>
 * 判据很简单: <b>同一个真实的人, 在两个 agent 的心里有两条 {@code person_object} 行</b>,
 * 而它们的名字可以完全不同（在她这里叫"妈妈", 在另一个 agent 那里叫"张女士"）。
 * §3.4.6 末尾那句"你手机里的备注名是你自己起的"说的就是这件事。
 * 因此这张表<b>永远不能</b>被当作账号目录使用 —— 账号目录是
 * {@link ConversationAccountBindingRecord}, 而它的 {@code person_id} 指向这里。
 *
 * <h2>为什么要落库: 不存会怎样</h2>
 * 不存的话, 重启之后她<b>不认识任何人了</b>:
 * <ul>
 *   <li>{@code RelationshipGraph.resolve(accountId)} 对每一个账号都返回
 *       {@code Optional.empty()} —— 连用户（她的主人）都变成"不认识的人"。
 *       而 {@code persona} 里写着她与用户的关系是"朋友", 于是系统进入一个
 *       自相矛盾的状态: 人设说她认识, 通讯录说她不认识;</li>
 *   <li>{@code RelationshipMemory} 的历史还在（那是另一张表）, 但它记的是
 *       "在某个 {@code person_id} 上发生过什么" —— 那些 id 指向的人不存在了,
 *       历史变成一堆无法解释的编号。</li>
 * </ul>
 * 这两条合起来说明: 人物对象是<b>记忆与关系的所指</b>。所指没了, 记忆与关系就都读不懂了。
 *
 * <h2>为什么 {@code payload_json} 里存的是完整对象, 而不是拆成一堆列</h2>
 * {@code PersonObject} 的字段会生长（印象、情感值、她给他起的昵称、她对他的
 * 期望、她记得的他的习惯…）。每加一个字段加一列的路线, 在这张表上尤其危险:
 * <pre>
 *   她认识的人越多, 这张表越大, 而 ALTER TABLE 的代价与表大小成正比。
 *   于是"给人物对象加一个字段"这件小事, 变成了一次随她人生变长的停机。
 * </pre>
 * 统一策略（§7.1: 稳定的关系型元数据 + JSON 多态载荷）在这里的落法是:
 * <b>只把"要用来找行"的字段拆成列</b>（{@code person_id} / {@code human_id} /
 * {@code display_name}）, 其余全部留在载荷里。
 *
 * <p>{@code display_name} 之所以被<b>拆出来冗余一份</b>, 理由是本表唯一需要的
 * 查询形状是"她的通讯录, 按名字排"（她打开通讯录、控制台展示她的社会关系）。
 * 若名字只活在 JSON 里, 那个查询要写成 {@code payload_json ->> 'name'},
 * 既不能走普通索引, 又把一个领域字段名（{@code name}）编码进了 SQL ——
 * 改名那天这条查询静默地按 {@code NULL} 排序。
 *
 * <h2>{@code first_seen_sim_at} 与 {@code created_at} 是两种时间</h2>
 * 前者是"她第一次见到这个人"（仿真时刻, 由领域给）, 后者是"这一行被写进来的
 * 物理时刻"。仿真加速 60 倍时前者被压缩、后者不变 —— 两者之差是"她的人生
 * 压缩了多少"的一个直接读数。合成一列会让这个读数永远消失。
 */
@Entity
@Table(name = "person_object", indexes = {
        // ① "她的通讯录, 按名字排" —— 见类注释: 本表唯一的热查询形状。
        //    等值列 human_id + 排序列 display_name, 一次索引扫描直接出结果。
        @Index(name = "idx_person_object_human_name", columnList = "human_id,display_name"),
        // ② 按类型筛: "她的通讯录里有几个同学 / 几个家人"。
        //    §3.4.6 的 "relation" 在本设计里就是类型三元组（见 RelationshipRecord 的说明）,
        //    于是这个问题是一次索引查询, 而不是把她的全部人物对象读进内存再分类。
        @Index(name = "idx_person_object_type",
                columnList = "person_type_namespace,person_type_name")
})
@Getter
@Setter
public class PersonObjectRecord {

    /**
     * 人物对象 id —— 领域里的 {@code PersonId}。
     *
     * <p>长度 64: 这个 id 要出现在 {@code Percept} 的 {@code SourceRef}、
     * {@code RelationshipMemory} 的历史、以及日志里, 而 §3.4.6 的
     * {@code Map<PersonId, PersonObject>} 意味着它由领域生成。
     * 给 64 而不是 36, 是因为本设计<b>不规定</b>它是 UUID ——
     * 一个"人"的 id 完全可以是可读的（{@code "person-mom"}）,
     * 而把宽度卡在 36 会悄悄禁止那个选择。
     */
    @Id
    @Column(name = "person_id", length = 64)
    private String personId;

    /**
     * 这是<b>谁的</b>心里的人 —— agent 的 id。
     *
     * <p>见类注释: 同一个真人在两个 agent 的心里有两条行。少了这一列,
     * 这条约束就无法表达, 而 {@code person_id} 会退化成"用户 id 的别名"——
     * 那正是本表最该避免的形态。
     */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /** 她给这个人起的名字（或她记住的那个名字）。见类注释"为什么拆出来冗余一份"。 */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 人物对象的类型三元组 —— 由实现类上的 {@code @DomainType} 决定, 平台不预设取值。 */
    @Column(name = "person_type_namespace", nullable = false, length = 96)
    private String personTypeNamespace;

    @Column(name = "person_type_name", nullable = false, length = 96)
    private String personTypeName;

    @Column(name = "person_type_version", nullable = false)
    private int personTypeVersion;

    /**
     * 完整的 {@code PersonObject} —— 印象、情感、昵称、她记得的他的习惯。
     *
     * <p><b>这里可能出现"看起来像正文、但其实是记忆"的东西</b>, 而它与 §9
     * 验收标准 E 不冲突, 需要说清楚以免读者误判:
     * <pre>
     *   被禁止的:  聊天消息正文经"消息到达"这条路进入领域模型（绕开 Mind 的注意与读取）
     *   允许的:    她<b>读过之后</b>形成的印象 —— 而那正是 ReadMessagesAction 之后的正常结果
     * </pre>
     * 判据是<b>来源</b>, 不是内容: 印象只能由她的认知流程写进来, 不能由聊天平台
     * 推送的载荷直接填进来。所以这一列的 <b>写入方有且只有认知侧</b>,
     * 本层不做任何"从消息里提取印象"的操作 —— 那会把上面那条判据彻底模糊掉。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload_json", columnDefinition = "text")
    private Map<String, Object> payloadJson;

    /** 她第一次见到这个人的时刻（仿真时刻）。 */
    @Column(name = "first_seen_sim_at")
    private Instant firstSeenSimAt;

    /** 最后一次对这个人的认识被改动（墙上时钟）。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (personId == null) {
            // 一个没有 id 的人物对象会让所有指向它的记忆与绑定全部悬空。
            // 兜底值刻意可辨认, 见 WorldObjectRecord 的同一条说明
            personId = "person-unknown-" + UUID.randomUUID();
        }
    }

    public String describe() {
        return personTypeNamespace + "." + personTypeName + "[" + personId + "] \"" + displayName + "\"";
    }
}
