package com.luxera.companion.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * V2.2 §7.2 —— {@code human}: <b>一个 agent 实例的锚点行</b>。
 *
 * <h2>它为什么这么薄, 以及它为什么仍然必须存在</h2>
 * §7.2 给这张表列了三个字段（{@code id} / {@code version} / {@code created_at} /
 * {@code updated_at}）—— 也就是说, 文档自己也认为它<b>不装内容</b>。
 * 那么内容在哪?
 * <table border="1">
 *   <tr><th>关于她的什么</th><th>在哪张表</th></tr>
 *   <tr><td>人设（persona）、与用户的关系</td><td>{@code companion}（本仓既有的 {@code persona/Companion}）</td></tr>
 *   <tr><td>她打算做什么</td><td>{@link PlanRevisionRecord} + {@link PlanItemRecord}</td></tr>
 *   <tr><td>她真的做了什么</td><td>{@link ActivityRecord}</td></tr>
 *   <tr><td>她此刻身上挂着什么影响</td><td>{@link ContinuousEffectRecord}</td></tr>
 *   <tr><td>她经历过什么</td><td>{@link WorldEventRecord}</td></tr>
 * </table>
 *
 * <p>那这一行的作用是——<b>它是上面所有 {@code human_id} 列的所指</b>。
 * 没有它, 那些列就只是三十六位十六进制字符串, 而"这个 id 到底代表一个存在过的
 * agent, 还是某个 bug 拼出来的字符串"这个问题无法回答。
 *
 * <p>具体买到两件事:
 * <ol>
 *   <li><b>"她存在过"是可查的。</b> 运维想删一个 agent 时, 第一个动作是
 *       {@code SELECT ... FROM human WHERE id = ?} —— 查得到才敢继续。
 *       查不到却还有几十万行事件挂在这个 id 上, 是一类真实的数据事故;</li>
 *   <li><b>审计时间只有一个来源。</b> "这个 agent 什么时候被创建的"有且只有一个答案。
 *       从别处推（找她最早的一条事件）会得到一个近似的、依赖数据保留策略的答案 ——
 *       而一个会被清理策略改变的"创建时间"不是创建时间。</li>
 * </ol>
 *
 * <h2>关于 §7.2 的 {@code version} 列: 本实现没有它</h2>
 * 理由与 {@link WorldObjectRecord} 完全相同（本仓零 {@code @Version} /
 * 零 {@code @Lock}, 并发由调度层的单线程 tick 保证, 而不是实体层的乐观锁）,
 * 这里不重复。但这一张表还多一条<b>更硬</b>的理由:
 * <pre>
 *   human 这一行在 agent 的一生中<b>从来不被更新</b>。
 * </pre>
 * 它没有可变的字段（人设改动改的是 {@code companion}, 不是这一行）。
 * 一个永远不被 UPDATE 的行上的乐观锁, 在最乐观的情况下也只是一个
 * "每次读都多带一列、每次写都多一次比较"的纯开销。
 *
 * <h2>表名为什么是单数 {@code human}</h2>
 * 与 {@code world_event} / {@code plan_revision} 同一条理由: §7.2 用单数, 而
 * 本仓复数的 {@code persons}（{@code person/Person}, 那是"数字人世界里的社会身份层"）
 * 已经被占了。两者的差别不是命名偏好:
 * <pre>
 *   persons  ——  User / Agent / OtherPerson 三种身份的统一表示 (§五)
 *   human    ——  一个仿真 agent 实例（她）, 是 persons 里 {@code AGENT} 那一类的一个实例
 * </pre>
 * 即 {@code human.id} 是 {@code persons.id} 的一个子集, 而不是另一套身份体系。
 * 这个关系必须写在这里, 否则下一个读代码的人会以为平台有两套互相独立的 id。
 */
@Entity
@Table(name = "human")
@Getter
@Setter
public class HumanRecord {

    /**
     * agent 的 id。
     *
     * <p>它与 {@code companion.id} / {@code persons.id}（{@code AGENT} 那一行）
     * <b>是同一个值</b>, 不是新发的一个。零迁移: §8.3 的迁移路径要求"直接改,
     * 不留旧路径", 而换一套 id 会让全部既有数据的外部引用失效。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** 人设里的显示名。冗余一份, 只为让这一行在 {@code psql} 里能被人一眼认出。 */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** 这一行什么时候被写进来的（墙上时钟）。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后一次改动（墙上时钟）。见类注释: 实际上永远不会变, 但 §7.2 要求它存在。 */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String describe() {
        return "agent[" + id + "] " + displayName;
    }
}
