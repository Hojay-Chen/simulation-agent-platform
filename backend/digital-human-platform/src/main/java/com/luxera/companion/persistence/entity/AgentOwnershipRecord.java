package com.luxera.companion.persistence.entity;

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
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * V2.2 §3.6 / §7.2 —— {@code agent_ownership}: <b>她归谁、她现在跑不跑</b>。
 *
 * <h2>它为什么<b>不</b>是 {@code human} 表上的几列</h2>
 * 这是本类存在的全部理由, 而它值得一个具体的场景, 而不是一句"职责单一":
 *
 * <pre>
 *   用户 A 把她的 agent 转让给用户 B:
 *
 *   合在一行上（旧 companions 表）:
 *     UPDATE human SET owner_user_id = 'user_B' WHERE id = 'hum_x'
 *     → 这一行同时是仿真世界的身份锚点。改它 = 动一个被
 *       {@code HumanRecord} 描述为"内容都在别处"的聚合根所在的行 ——
 *       于是"她换了个主人"和"重新加载她的身体"落在同一个事务里,
 *       一次配额/计费侧的写操作开始需要仿真侧的一致性与锁。
 *
 *   分两张表（本类）:
 *     UPDATE agent_ownership SET owner_user_id = 'user_B' WHERE human_id = 'hum_x'
 *     → 仿真侧一行都没动, 不需要重新加载任何东西: 她的记忆、身体、计划
 *       都还是她的。归属变更是一次纯粹的、单列的平台侧写操作。
 * </pre>
 *
 * 第二条理由同样是具体的: <b>{@code status} 这个词在旧表上背了两种含义</b> ——
 * "认知循环暂停了"（平台侧）与"这一步她身体内部的某个量变了"（仿真侧）。
 * 于是"她为什么不动了"有两个答案, 而<b>数据上只有一个</b> ——
 * 排查的人只能猜。分表之后, "她为什么不动了"的答案只在这张表的
 * {@code lifecycle} 一列上, 而仿真侧的问题在仿真侧的表里。
 *
 * <h2>表名为什么是单数 {@code agent_ownership}</h2>
 * 与 {@code human} / {@code world_event} / {@code plan_revision} 同一条理由:
 * §7.2 用单数。这里没有历史包袱要绕（没有既存的 {@code agent_ownerships}）,
 * 保持与同一批新表一致, 而不是在同一个包里混用两种命名风格。
 *
 * <h2>三个 ID 永不混用 —— 这一列上有一个真实的陷阱</h2>
 * <pre>
 *   human_id            hum_xxx   仿真世界里的那个人（→ {@link HumanRecord#getId()}）
 *   owner_user_id       user_xxx  平台用户 —— <b>不是</b>聊天账号
 *   created_by_client_id （三方客户端 id, 不是账号 id）
 * </pre>
 * {@code owner_user_id} 与 {@code conversation_account_binding.chat_account_id}
 * （形如 {@code acc_xxx} / {@code agent_xxx}）是<b>两种东西</b>, 而它们都是
 * {@code String} —— 也就是说用错一个在类型上完全合法。§3.6.3 的纪律是
 * "分表 + 分前缀之后这件事在写代码时就能看出来": 这一列的前缀是 {@code user_},
 * 而这一列的名字里写着 {@code user}。把聊天账号写进这里, 症状是
 * "控制台显示她属于一个不存在的人", 而库里的 id 看起来完全正常。
 *
 * <h2>{@code lifecycle} 为什么是 {@code String} 而不是 {@code @Enumerated}</h2>
 * §3.6.4 明确说了生命周期是<b>允许</b>用枚举的那个地方 —— 判据是 §1.3 P4 那一句
 * "取值集合由本设计的内部逻辑决定, 还是由外部世界的多样性决定":
 * <table border="1">
 *   <tr><th></th><th>判据</th><th>结论</th></tr>
 *   <tr><td>{@code AgentLifecycle}</td>
 *       <td>平台自己定义的两个档位, 外部世界不会明天冒出一个第三种,
 *           也不需要第三方来扩展它</td>
 *       <td><b>可以是枚举</b>（{@code runtime/AgentLifecycle}）</td></tr>
 *   <tr><td>{@code Activity.type}</td>
 *       <td>第三方要能加"做实验"这一种活动</td>
 *       <td><b>必须</b>是多态实现类（{@code @DomainType}）</td></tr>
 * </table>
 * 而"允许用枚举"说的是<b>领域侧的取值</b>可以用枚举, 不是"这一列要用
 * {@code @Enumerated} 映射"。这里仍然存 {@code AgentLifecycle.wire()} 返回的字符串,
 * 与 {@code conversation_account_binding.bind_reason}、{@code action_command.status}
 * 同一处理。多出来的第四条理由是本表特有的:
 * <pre>
 *   一张被 {@code psql} 读的表, 加上 {@code @Enumerated(STRING)} 之后,
 *   枚举常量的名字变成了数据的一部分 —— 而 {@code AgentLifecycle} 刚刚
 *   被搬进 {@code runtime} 包（§3.6.4: 它是平台/运行时概念, 放进 human 包
 *   就等于让 Human 聚合"知道自己是归谁的"）。一个会因为 <b>包结构调整</b>
 *   而改变写库内容的字段, 不该是字段的类型选择。
 * </pre>
 * 而领域侧的类型安全并没有丢: 读回来的那一列交给
 * {@code AgentLifecycle.of(String)} 转一次, 那个方法有自己的兜底规则
 * （见下一段）。
 *
 * <h2>两个问题, 两个列 —— 这是本表上最重要的一个决定</h2>
 * "她还活着吗"与"活着的话跑不跑"被拆成两列（{@code deleted_at} 与
 * {@code lifecycle}）, <b>而不是一个四值枚举</b>。这条推理来自旧实现
 * （{@code persona.AgentLifecycle} 只落两档, 把 {@code RETIRED} 留给
 * {@code deleted_at}）, §3.6.5 照收了它, 而它值得在这里原文再写一遍:
 * <blockquote>
 * {@code RETIRED} 就是 {@code deleted_at is not null}。它有自己的写入路径
 * 和清理逻辑, 再包一层枚举只会让两处状态有互相矛盾的机会。
 * 所以枚举只回答"活着、但要不要跑", 而"还活着吗"由 {@code deletedAt} 回答。
 * <b>两个问题, 两个列, 不合并。</b>
 * </blockquote>
 * 合并会坏在哪, 两条:
 * <ol>
 *   <li><b>四值枚举允许出现自相矛盾但类型合法的行。</b>
 *       {@code lifecycle = active} 而 {@code deleted_at != null} —— 谁都能写出来
 *       （一次软删忘了改枚举, 一次恢复忘清时间戳）, 而<b>读到它的人只能猜
 *       哪一半是真的</b>: 她是被删了（那就不该跑）还是活的（那删除时间是哪来的）?
 *       两个列则<b>没有</b>这种组合 —— 软删就是软删, 运行档就是运行档,
 *       它们各自回答各自的问题, 不会互相说谎;</li>
 *   <li><b>四值枚举里的 {@code ARCHIVED} 没有任何写入路径。</b>
 *       §3.6.5 的原话: "它没有任何一条写入路径、没有任何一个需求指向它 ——
 *       加一个'看起来合理'的枚举值, 与 V2.1 那种'定义了字段却没定义取值'
 *       是同一类毛病, 只是方向相反"。一个永远不会被写入的取值不是冗余,
 *       是一个<b>会被别人实现的空壳</b>: 下一个读代码的人会以为
 *       "归档"这条路径存在, 然后去写它。</li>
 * </ol>
 *
 * <h2>读侧的兜底: <b>认不出来的值一律当 {@code active}</b></h2>
 * 这是本设计里唯一一处"往危险的方向兜底"的地方（§3.6.5）, 而它是刻意的 ——
 * 这个判断在<b>每次认知 tick 之前</b>都会跑一次, 两种错法的代价悬殊:
 * <table border="1">
 *   <tr><th>错法</th><th>后果</th></tr>
 *   <tr><td>把"暂停"误判成"运行"</td>
 *       <td>多烧一点 token。<b>可察觉（账单上看得见）、可纠正、有上限</b></td></tr>
 *   <tr><td>把"运行"误判成"暂停"</td>
 *       <td><b>这个 agent 变成哑巴</b> —— 症状是"她不回我了",
 *           没有报错、没有日志、<b>没有任何指向这个枚举的线索</b></td></tr>
 * </table>
 * 所以规则是: <b>只有字面写着 {@code paused} 才停</b>。将来若有人往这一列写
 * 别的值（{@code "sleeping"} / {@code "idle"} / 拼写错的 {@code "pausd"}）,
 * 默认行为是"照常运行", 而不是静默停机。
 * <p>这条规则的规范实现是 {@code AgentLifecycle.of(String)}（在 {@code runtime} 包）。
 * 本类<b>另有一份</b> {@link #paused()} 用于只有一行、拿不到枚举的读路径 ——
 * 两处的比较必须一致, 见 {@link #paused()} 的说明（那里写了"不一致时哪边算错"）。
 *
 * <h2>列宽 32 —— 它<b>不是</b>按"今天有几个取值"定的</h2>
 * §7.2 给的是 {@code VARCHAR(32)}。按当前取值（最长的 {@code "paused"} 六个字符）
 * 定成 {@code VARCHAR(8)} 看起来更"精确", 但那是一条会咬人的精确:
 * {@code ddl-auto: update} <b>不会修改已存在列的类型与宽度</b> ——
 * 它只建缺失的表与列。于是下面这条路径是真实存在的:
 * <pre>
 *   今天写 VARCHAR(8)  →  半年后有人往这一列写一个更长的值
 *                         （例如运营侧的 "maintenance-window", 18 个字符）
 *                      →  列上没有任何报错的那一天, 是插入被截断或失败的那一天,
 *                         而那时库里已经有几十万行按旧宽度写下的数据
 * </pre>
 * 32 覆盖的是"一个平台侧短标识"这个<b>类别</b>, 而不是"当前这个枚举的基数"。
 * 这与 {@code category} 列从 §7.2 的 24 放大到 64 是同一条推理
 * （见 {@link WorldEventRecord}）: <b>列宽要按含义定, 不按今天的取值定</b>。
 *
 * <h2>为什么没有 {@code version} 乐观锁列</h2>
 * 与本层其它实体一致（本仓零 {@code @Version} / 零 {@code @Lock},
 * 并发由调度层的单线程 tick 保证）—— 见 {@link WorldObjectRecord} 的完整论证。
 * 但这一张表上值得补一句, 因为"转让"是这里面唯一一个真的可能被两个人同时发起的操作:
 * <pre>
 *   用户 A 与用户 B 同时把同一个 agent 转给自己:
 *     无版本列 → 后写的赢, 库里是一条确定的行（owner = 其中一个）
 *     有版本列 → 其中一个拿到 OptimisticLockException
 * </pre>
 * 两个结果在<b>语义上都可接受</b>（一次转让是一个决定, 不是一个增量 ——
 * 它没有"把两次转让合并起来"这种正确做法）。所以这里付出的代价只是
 * "输的那个人的意图被静默丢弃", 而调用方若需要"让第二个人知道",
 * 它要的是"读一次再判断"（`findByHumanId` → 比较 → 写), 而不是一列版本号。
 *
 * <h2>{@code human_id} 上没有外键 —— 而这一条比别处更需要解释</h2>
 * 本仓全部实体都没有外键（零 {@code @ManyToOne} / 零 {@code @JoinColumn}）,
 * 但"约定如此"不是一个理由。这一列上的具体理由是:
 * <pre>
 *   有了外键: DELETE FROM human WHERE id = ? 会先去检查/级联 agent_ownership,
 *             于是"平台侧的归属行"的存亡被"仿真侧身份行的存亡"绑定了 ——
 *             而这恰好是本类开头那段要拆开的东西, 只是换了一个方向。
 *   没有外键: 悬空行是可能的（human 行没了, 归属行还在）。
 * </pre>
 * 悬空行在这里的正确处理是读的时候当作"这个 agent 不存在"（
 * {@code findByHumanId} 返回 {@code Optional.empty()} 也行, 返回一行但 join 不到
 * human 也行 —— 两者都要能表示), 而不是让删除动作失败。
 * 而唯一约束 {@code uk_agent_ownership_human} 仍然保证<b>方向正确的</b>那一半:
 * 一个 human 不可能有两行归属。
 *
 * <h2>{@code created_at} / {@code updated_at} 为什么是墙上时钟, 而这张表上没有仿真时刻</h2>
 * 本层其它表的"仿真时间"列（{@code occurred_at} / {@code started_at} /
 * {@code booked_at}）都是 {@code Instant}, 因为那些值参与回放: 把仿真加速 60 倍
 * 会改变它们。这张表<b>一个仿真时刻列都没有</b>, 而这是刻意的:
 * 归属与生命周期是<b>平台</b>概念, 它们不参与仿真回放 ——
 * "这个 agent 是 2026-09-19 被暂停的"是运维在看的墙上时间,
 * 而它不该随着仿真时钟的倍率变化。给这一行加一个 {@code Instant} 列
 * 会暗示"这件事在仿真世界里发生过", 而它没有。
 */
@Entity
@Table(name = "agent_ownership",
        uniqueConstraints = {
                // 一个 Human 只被一个用户拥有（§3.6.8）。这条约束是"唯一归属"的
                // 唯一保证 —— 见类注释与 findByHumanId 的说明
                @UniqueConstraint(name = "uk_agent_ownership_human", columnNames = "human_id")
        },
        indexes = {
                // "这个用户名下有几个 agent" —— 控制台列表, 也是配额/计费侧的计数入口。
                // 走 idx_agent_ownership_owner。注意它不能替代 human_id 上的唯一约束索引:
                // 那两个查询的第一个条件不同（一个是 owner, 一个是 human id）
                @Index(name = "idx_agent_ownership_owner", columnList = "owner_user_id")
        })
@Getter
@Setter
public class AgentOwnershipRecord {

    /**
     * 行的 id。
     *
     * <p>与 {@code human_id} 是<b>两个不同的值</b>, 刻意不合并:
     * 用 {@code human_id} 当主键看起来省一列, 但它会让"归属行的身份"
     * 与"仿真身份"成为同一个值 —— 于是又一次, 想改归属就要引用仿真侧的标识。
     * 独立主键买到的是: 归属行可以被独立引用（审计、导出、三方客户端回执）,
     * 而引用它不会泄露一个仿真世界的 id。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 这个 agent 的仿真身份 —— 指向 {@link HumanRecord#getId()}。
     *
     * <p><b>唯一</b>（{@code uk_agent_ownership_human}）。没有这条约束会怎样,
     * 值得写清楚, 因为它的症状不像约束缺失:
     * <pre>
     *   没有唯一约束:
     *     一次 provisioning 重试（或两个客户端同时创建）→ 两行归属
     *     → findByHumanId 变成"取第一行"（而这个"第一"由查询计划决定）
     *     → 控制台上她的归属<b>偶尔</b>显示成另一个人
     *   症状随 VACUUM 与计划变化, 无法复现 —— 这正是把约束建在库里
     *   而不是靠"调用方不会写重"这条约定的理由
     * </pre>
     * 而"转让"这个操作本身也依赖它: 转让必须是一次
     * {@code UPDATE owner_user_id}（见类注释）, 而如果没有唯一约束,
     * 一个把转让实现成 INSERT 的人不会得到任何报错 ——
     * 他会得到两个主人, 而两个主人看起来都是对的。
     */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /**
     * 平台用户 ID（{@code user_xxx}）—— <b>不是聊天账号</b>。
     *
     * <p>见类注释"三个 ID 永不混用"。
     *
     * <p>长度 36 而不是聊天账号的 64: 平台用户 id 是本平台自己发的 UUID 形状标识,
     * 而聊天账号 id 由仓 1 决定（它可以是 {@code agent_xxx} 这类更长的形式）。
     * 用 64 会掩盖"有人把聊天账号写进来了"这件事 —— 一个短到装不下聊天账号的列,
     * 会让那个错误在写入点就失败, 而不是在控制台上显示一个查不到的人。
     */
    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    /**
     * 这个 agent 是哪个三方客户端创建的 —— {@code null} = 平台自建。
     *
     * <p><b>可空</b>且<b>语义明确</b>: {@code NULL} 不是"不知道", 而是
     * "创建它的是平台自己的 provisioning 流程"。§3.6.3 把这两个来源分开,
     * 是因为它们牵动不同的后果（三方创建的要算在它头上, 平台自建的不用）。
     * 空值在这里用一个真实取值（{@code NULL}）表达, 而不是一个
     * {@code "PLATFORM"} 之类的哨兵字符串: 哨兵字符串会与一个真的叫这个名字的
     * 客户端 id 撞车, 而 {@code NULL} 不会。
     *
     * <p>长度 36: 三方客户端 id 由平台发放（{@code client_xxx}），与
     * {@code owner_user_id} 同一宽度。
     */
    @Column(name = "created_by_client_id", length = 36)
    private String createdByClientId;

    /**
     * 平台侧的运行档位 —— {@code AgentLifecycle.wire()} 的返回值,
     * <b>只有 {@code "active"} / {@code "paused"} 两个值</b>（§3.6.4）。
     *
     * <p>它回答的<b>只是</b>"活着的话, 跑不跑"。"还活着吗"是
     * {@link #deletedAt} 那一列的事 —— 见类注释"两个问题, 两个列"。
     *
     * <p>用它自己的话再说一遍这一列的语义（§3.6.6）:
     * <b>{@code PAUSED} 不是"停止仿真", 是"她不再产生新的认知 tick"</b> ——
     * 世界照旧在走（时钟、气温、聊天平台上的消息、计划表上的时刻）。
     * 所以这一列<b>不能</b>由 Human 内部的字段表达: 那会让"暂停"变成
     * "她拒绝处理事件", 事件堆在队列里等她醒。两者的区别在恢复的那一刻显形 ——
     * 前者要读的是一段世界历史, 后者要读的是一个积压的队列;
     * 前者能分辨"这三天里下了两场雨", 后者只能看到"有两千条事件"。
     *
     * <p>"暂停"这个词必须按<b>字面</b>理解 —— 它不删任何东西:
     * 记忆、关系、未闭环的念头、待处理的消息队列、手机通知全部原样保留。
     * 恢复之后是从暂停的那一刻接着走, 而不是从零开始。这正是它与
     * {@link #deletedAt} 的分界, 也是它存在的理由。
     */
    @Column(name = "lifecycle", nullable = false, length = 32)
    private String lifecycle;

    /**
     * 软删时刻（墙上时钟）—— <b>{@code NULL} = 她还活着</b>。
     *
     * <p>它与 {@link #lifecycle} 是两个问题（§3.6.5）:
     * <pre>
     *   还活着吗？          deleted_at IS NULL
     *   活着的话跑不跑？    lifecycle = "active" / "paused"
     * </pre>
     * 这一列之所以必须单独存在, 而不是把 {@code DELETED} /
     * {@code RETIRED} 塞进枚举里: 那个取值<b>是这列的同义改写</b> ——
     * 于是"她已经被删了"这件事在库里有两个副本, 而两份副本迟早不一致
     * （删的时候忘了改枚举、恢复的时候忘清时间戳）。类注释里有完整论证。
     *
     * <p>类型选 {@code LocalDateTime}（墙上时钟）而不是 {@code Instant}:
     * 与 {@link #createdAt} 同一条理由 —— 删除是一个<b>平台侧</b>动作,
     * 它不参与仿真回放, 所以它不该随着仿真时钟的倍率变化。
     *
     * <p><b>写入这一列意味着什么, 本层不做假设。</b> §3.6.5 只说它有自己的
     * 写入路径与清理逻辑（"软删一个 agent 意味着她的身体、计划、关系网怎么办"
     * 是那个清理逻辑要回答的问题）, 而那段逻辑不在本层 —— 本层只保证
     * "她删了"这个事实有一列接得住, 并且不会与运行档混同。
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 这一行什么时候被写进来的（墙上时钟）。见类注释末段: 这张表上没有仿真时刻。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最后一次改动（墙上时钟）—— 转让与暂停/恢复都是这一列上的变化。
     *
     * <p>它在本表上是<b>真的会被用到</b>的（不像 {@link HumanRecord#getUpdatedAt()}
     * 那样"实际上永远不会变"）: "这个 agent 上一次换主人是什么时候"
     * 是审计里真实的一问。
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /**
     * 这是平台自建的吗 —— {@code created_by_client_id} 为空。
     *
     * <p>给 {@code null} 一个名字, 好让调用处读起来是
     * {@code if (row.platformCreated())} 而不是又一次
     * {@code row.getCreatedByClientId() == null}（后者在别处几乎总是
     * 一个漏掉的空值处理）。
     */
    public boolean platformCreated() {
        return createdByClientId == null || createdByClientId.isBlank();
    }

    /**
     * 她还活着吗 —— 与 {@link #paused()} 是<b>两个问题</b>（§3.6.5）。
     *
     * <p>这里刻意<b>不</b>提供 {@code active()} / {@code deleted()} 之外的
     * 第三态判断: 两个列回答两个问题, 每一个都是布尔, 而"她还跑不跑"
     * 的正确问法是 {@code !deleted() && !paused()} —— 一个调用方必须
     * <b>自己写出来</b>的合取。把它包成一个 {@code running()} 方法看起来贴心,
     * 但那会把两个问题又合并成一个答案, 而合并正是 §3.6.5 驳掉的东西:
     * 合起来之后, "她被删了"与"她被暂停了"在调用处再也分不开,
     * 于是"暂停不影响世界, 删除才影响"这条语义就会在某个分支里丢掉。
     */
    public boolean alive() {
        return deletedAt == null;
    }

    /**
     * 她还在跑吗 —— <b>读侧的兜底规则的落点</b>, 见类注释那一节。
     *
     * <p>规则只有一条: <b>只有字面写着 {@code paused} 才算停</b>
     * （忽略大小写与首尾空白）。{@code null}、空串、拼错的值、
     * 将来别的值一律返回 {@code false} —— 即"照常运行"。
     *
     * <h2>为什么这里重复了一次字符串比较, 而不 import 那个枚举</h2>
     * 规范实现是 {@code runtime} 包里的 {@code AgentLifecycle.of(String)}
     * （它同样返回 {@code PAUSED} 之外的一切 → {@code ACTIVE}）。这里重复一个
     * {@code equalsIgnoreCase}, 换来的是两件事:
     * <ol>
     *   <li>{@code persistence/entity} 不必为了一个字符串比较依赖
     *       {@code runtime} 包 —— 依赖的方向应当反过来（运行时读实体,
     *       而不是实体知道运行时）;</li>
     *   <li>读路径常常<b>只有一行</b>（{@code findByHumanId} 的返回值）,
     *       而拿到枚举要走一次 {@code of()}; 一行上有这个方法,
     *       调用处的意图就写成了 {@code if (row.paused())}, 而不是又一次
     *       {@code lifecycle == null || !"paused".equalsIgnoreCase(...)} ——
     *       后者几乎总是被写成漏掉 {@code null} 的那一版,
     *       而漏掉 null 的默认方向是"<b>停</b>", 也就是那个会把 agent
     *       变成哑巴的方向。</li>
     * </ol>
     * <p><b>两处不一致时哪边算错:</b> {@code AgentLifecycle.of} 那边。
     * 这一列的唯一权威读法是运行时的那个枚举 —— 本方法只是让"只有一行"的
     * 读路径也拿到<b>同一个方向</b>的兜底。如果有一天两者给出了不同答案,
     * 要改的是本方法（让它去调 {@code of()}）, 而判断"她跑不跑"的结果
     * 以运行时为准。把这句话写下来, 是因为这类重复最容易演变成
     * "两个地方都觉得自己对"。
     */
    public boolean paused() {
        return lifecycle != null && LIFECYCLE_PAUSED.equalsIgnoreCase(lifecycle.trim());
    }

    /**
     * {@code lifecycle} 里那个"停"的值。
     *
     * <p>它<b>不是</b>这一列的规范定义（{@code AgentLifecycle.wire()} 才是）,
     * 而只是 {@link #paused()} 用来比较的一个字面量。刻意不在这里列全
     * {@code "active"}: 一个本层不负责定义、只负责识别的取值,
     * 只需要认识它要判的那一个。
     */
    private static final String LIFECYCLE_PAUSED = "paused";

    public String describe() {
        return "agent[" + humanId + "] 属于 " + ownerUserId
                + " (" + lifecycle + ")"
                + (alive() ? "" : ", 已于 " + deletedAt + " 软删")
                + (platformCreated() ? " [平台自建]" : " [由 " + createdByClientId + " 创建]");
    }
}
