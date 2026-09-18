package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import com.luxera.companion.persistence.convert.StringListConverter;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * V2.2 §7.2 / §3.5.2 —— {@code plan_item}: <b>"她打算在某个时间段做某件事"</b>。
 *
 * <h2>它是"时间段"而不是"时刻" —— 而这一点决定了索引的形状</h2>
 * §7.2 在 {@code start_at} / {@code end_at} 那里加了一句粗体: <b>时间段（不是时刻）</b>。
 * 那不是排版强调, 它是一条硬约束的落点 —— §9 验收标准 G:
 * <blockquote>
 * 计划表在任何时刻都是一个完整的 {@code [start_at, end_at]} 窗口;
 * <b>领域模型与数据库 schema 中都不存在 {@code remainingDuration} 这个概念</b>
 * </blockquote>
 * 本表因此<b>没有</b> {@code remaining_ms} 这样的列, 而
 * {@code PlanItemRecordRepository} 里也刻意不出现任何"扣减剩余时长"的更新方法。
 *
 * <h2>一行 = "某一版计划里的一项" —— 所以主键是<b>复合</b>的</h2>
 * 一个计划项在相邻两版里的 {@code PlanItemId} <b>往往是同一个</b>:
 * {@code PlanBoard.apply} 把上一版的项放进一个 {@code working} 表里逐条改动,
 * 只有被 {@code Move} / {@code Resize} / {@code Replace} 碰过的项才走
 * {@code PlanItem.copyAsNew}（它才生成新 id）。于是:
 * <pre>
 *   rev-17:  item-3(写作业, ACTIVE)      rev-18:  item-3(写作业, ACTIVE)
 *                                            └─ 同一行数据在库里出现两次
 * </pre>
 * <b>把 {@code item-N} 当主键会直接主键冲突</b> —— 而冲突发生在她第一次重排的时候,
 * 也就是这个系统刚跑起来不久。
 *
 * <p>于是主键是 {@code revision_id + "#" + item_id}: 行的身份由"哪一版"和"哪一项"
 * 共同决定, 这正是这一行在数据里的真实身份。为什么不用一个
 * {@code @PrePersist} 生成的随机 UUID（本仓新表的惯例）: 因为那样行键就与数据无关了,
 * 而"这一行是哪一版哪一项"会退化成一个只能靠两列联合查询回答的问题 ——
 * 而它本来是可以直接读出来的。这与 {@code ActivityRecord}（主键就是 {@code act-N}）、
 * {@code ContinuousEffectRecord}（主键就是 {@code eff-N}）是同一条纪律:
 * <b>当数据里已经有一个稳定标识时, 不要另造一个</b>。
 *
 * <p>{@code item_id} 因此必须<b>单独存一列</b>（它不能再兼任主键）——
 * "item-3 在哪些版本里出现过"与"这一项今天被执行过几次"都要按它查。
 *
 * <h2>重排时新旧行的关系</h2>
 * 被 {@code Move} / {@code Replace} 碰过的项会换成新 id（{@code copyAsNew} 的论证:
 * 复用 id 只改窗口, 会让"Revision 17 里的 item-3"与"Revision 18 里的 item-3"
 * 变成"同一个 id 指两个不同的时间段"）, 旧的那一项在<b>新版本</b>里被标成
 * {@code SUPERSEDED} —— 也就是说同一个 {@code item_id} 在两版里的 {@code lifecycle}
 * 是不同的值。这是"一行 = 某一版的一项"这条设计必须成立的第二个理由:
 * 若按 {@code item_id} 覆盖写一行, 那"她 12:15 时以为这项还在做"就永久丢了。
 *
 * <h2>一行里为什么有四个"看起来能互推"的字段</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>它回答</th><th>为什么不能从别的推出来</th></tr>
 *   <tr>
 *     <td>{@code start_at} / {@code end_at}</td>
 *     <td>"这段时间她归这一项"</td>
 *     <td>—— 调度与冲突检测的依据</td>
 *   </tr>
 *   <tr>
 *     <td>{@code duration_ms}</td>
 *     <td>"她觉得要做多久"</td>
 *     <td><b>不能</b>用 {@code end_at - start_at} 代替: 计划窗口可以比预计时长长
 *         （她给自己留了缓冲）。两个数都要在, 行为分析才能回答"她是留了余量,
 *         还是每次都低估" —— 而这正是 {@code PlanItem} 类注释里那一节的内容</td>
 *   </tr>
 *   <tr>
 *     <td>{@code priority_level} / {@code priority_label}</td>
 *     <td>"这一项有多要紧" / "这句话怎么念给她听"</td>
 *     <td>{@code PlanPriority} 是一个 record（等级 + 标签）, 而 {@code PlanPriority.of(level)}
 *         只会给出<b>默认标签</b>。只存等级的话, 一个自定义标签
 *         （"有承诺或截止时间"这种)会在恢复时被静默换成"日常安排" —— 而标签会进 LLM context</td>
 *   </tr>
 *   <tr>
 *     <td>{@code origin_kind} / {@code origin_detail}</td>
 *     <td>"谁安排的" / 尤其是"<b>谁让她做的</b>"</td>
 *     <td>{@code PlanOrigin} 是 record(kind, detail), 而 {@code PlanOrigin.user("妈妈")}
 *         里的 detail 是"这条计划为什么存在"的全部信息。丢了它, 恢复出来的计划里
 *         "用户要求她做的事"会退化成"她自己安排的" —— 而这两种在她的行为分析里
 *         是完全不同的两类</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么 {@code lifecycle} 不是 §7.2 写的 {@code lifecycle_json}</h2>
 * {@code PlanLifecycle} 是枚举, 而且是一个<b>正确</b>的枚举: 取值集合
 * （PENDING/ACTIVE/DONE/CANCELLED/SUPERSEDED 以及它们之间的合法迁移）由本设计的内部逻辑
 * 决定, 不是由外部世界的多样性决定。三方不会带来第六种"计划项去处"。
 * 包进 JSON 的唯一效果是让 {@link #INDEX_LIFECYCLE} 那条索引失效。
 */
@Entity
@Table(name = "plan_item", indexes = {
        // ① §7.2 明确要求的那一条, 原文: "支撑 dueAt(t) 索引扫描"。
        //    为什么是这个列序: "这一版里, 起点在 t 之前的项" 是等值列(revision_id)+范围列(start_at),
        //    而把这个顺序反过来会让数据库先按 start_at 扫全部版本, 再丢掉不属于这一版的 ——
        //    在一张只增不减的表上, 那意味着越跑越慢。
        @Index(name = "idx_plan_item_revision_start", columnList = "revision_id,start_at"),
        // ② "她现在归哪一项" —— 与上一条不同: 它跨版本查, 找的是<b>当前活着</b>的那些项。
        //    没有它, 每一次 tick 都要把她的全部历史计划项捞出来过滤。
        @Index(name = "idx_plan_item_human_lifecycle", columnList = "human_id,lifecycle"),
        // ③ 跨版本追一项的历史: "item-3 是在第几版被插进来的、第几版被替代掉的"。
        //    plan_item 与 activity_record 的接合点也走它 ——
        //    activity_record.plan_item_id 与 plan_item.item_id 是同一个值域（见类注释
        //    "主键是复合的"那段: 主键不能再兼任这个角色, 所以另开一条索引）。
        //    没有它, "这一项被执行过几次"要先按 revision 扫全部版本。
        @Index(name = "idx_plan_item_item", columnList = "item_id"),
        // ④ 按意图类型查: "她这一类事（写作业 / 吃饭）总共安排过几次"。
        //    两个等值列而不是三列: 行为分析问的是"哪一类", 而跨 major 版本统计
        //    "她一共安排过多少次写作业"正是它想要的 —— 版本差异在 intent_json 里。
        @Index(name = "idx_plan_item_intent", columnList = "intent_type_namespace,intent_type_name")
})
@Getter
@Setter
public class PlanItemRecord {

    /** 行键 —— {@code rev-18#item-3}。见类注释"主键是复合的"。长度 192 是 {@code 64+1+64} 留的余量。 */
    @Id
    @Column(name = "id", length = 192)
    private String id;

    /** 领域里的计划项 id —— {@code item-N}。<b>不是行键</b>（同一项会在多版里各有一行）。 */
    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    /**
     * 属于哪一版。
     *
     * <p>它<b>不是</b> §7.2 里有外键约束的那一列 —— 本仓没有一处用
     * {@code @ManyToOne} / {@code @JoinColumn}（{@code grep -rn "@ManyToOne\|@JoinColumn"}
     * 零命中）, 全部实体都用裸 id 列关联。沿用这条约定, 于是"删一个 revision 该不该
     * 连带删掉它的项"这个问题不存在 —— 因为<b>没有删除操作</b>（见 {@code PlanRevisionRecord}）。
     */
    @Column(name = "revision_id", nullable = false, length = 64)
    private String revisionId;

    /**
     * 谁的计划 —— 冗余存一份 agent id。
     *
     * <h2>为什么这一列在 §7.2 里没有, 而这里必须有</h2>
     * §7.2 的 {@code plan_item} 只有 {@code revision_id}, 顺着外键总能找到 {@code human_id}。
     * 但本仓的实体之间<b>没有外键, 也没有 JoinColumn</b> —— 于是"她的当前计划项"
     * （上面 {@code idx_plan_item_human_lifecycle} 服务的那个查询）就变成:
     * <pre>
     *   -- 没有这一列:
     *   SELECT i.* FROM plan_item i JOIN plan_revision r ON i.revision_id = r.revision_id
     *            WHERE r.human_id = ? AND i.lifecycle IN ('PENDING','ACTIVE')
     *   -- 多一次 join, 而这条查询跑在<b>每个 tick</b> 上
     * </pre>
     * 行为分析里"她一天的计划项"要被读几十次, 而这一列是写一次读很多次的那种冗余 ——
     * 代价是一次写入多几个字节, 收益是热路径上少一次 join。
     *
     * <p>代价要说清楚: 它与 {@code plan_revision.human_id} 可能不一致。本层<b>不做校验</b>
     * （不读时钟、不做领域判断之外, 这也属于"不做决策"）—— 写入方（{@code PlanStore}）
     * 保证它们来自同一个 {@code PlanRevision}。一个不一致的行说明有人绕过了 store 写数据。
     */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /** 意图类型三元组 —— {@code intent_json} 的读法。 */
    @Column(name = "intent_type_namespace", nullable = false, length = 96)
    private String intentTypeNamespace;

    @Column(name = "intent_type_name", nullable = false, length = 96)
    private String intentTypeName;

    @Column(name = "intent_type_version", nullable = false)
    private int intentTypeVersion;

    /**
     * 意图本身 —— 多态载荷。
     *
     * <p>这是"不为每种计划建一张表"这条设计目标在数据库里的<b>全部落点</b>:
     * {@code StudyPlan} / {@code WorkPlan} / {@code ExperimentPlan} / {@code TravelPlan}
     * 之间没有任何 DDL 差别, 它们是同一列 JSON 里的不同 {@code _type}。
     * 加一种计划不需要一次迁移 —— 这正是 §7.1 那句"每加一种行为就要一次 DDL。
     * 这是 V2.1 §5.4.1 已经论证过的"要防的事。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "intent_json", columnDefinition = "text")
    private Map<String, Object> intentJson;

    /** 窗口起点。与 {@code end_at} 一起构成一个完整的 {@code [start, end]}, 没有"剩余"的概念。 */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /**
     * 预计时长, 毫秒。
     *
     * <p>为什么是毫秒而不是把 {@code Duration} 交给 Jackson: {@code Duration} 的默认
     * 序列化形式随 Jackson 版本变过（秒+纳秒的浮点 / ISO-8601 字符串 / 数字）。
     * 一个明确的 {@code BIGINT} 毫秒在数据库里是没有歧义的, 而"这一列存的是哪种形式"
     * 不应该是读数据的人需要问的问题。也与 {@code TimeWindow} 的领域单位一致 ——
     * 那里算的是 {@code toMillis()}。
     */
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** 优先级等级（0-100）。 */
    @Column(name = "priority_level", nullable = false)
    private int priorityLevel;

    /** 优先级的可读标签。见类注释"为什么不能从等级推出来"。 */
    @Column(name = "priority_label", length = 64)
    private String priorityLabel;

    /** 生命周期位置 —— {@code PENDING/ACTIVE/DONE/CANCELLED/SUPERSEDED}。 */
    @Column(name = "lifecycle", nullable = false, length = 16)
    private String lifecycle;

    /** 来源的种类 —— {@code self} / {@code user} / {@code system} / {@code other}。 */
    @Column(name = "origin_kind", nullable = false, length = 16)
    private String originKind;

    /** 来源的细节 —— 尤其是"谁让她做的"。 */
    @Column(name = "origin_detail", length = 256)
    private String originDetail;

    /**
     * 依赖哪些别的项先完成。
     *
     * <p>存成 JSON 字符串数组而不是逗号串, 理由见 {@code StringListConverter}。
     * 刻意<b>不在数据库层做外键</b>: 一个依赖指向的项可能属于<b>更早的</b> revision
     * （"写作业要先等衣服洗完"—— 衣服那项在重排时可能已被 SUPERSEDED 换成了新 id）。
     * 加外键的后果是重排时保存失败, 而她正在重新安排自己的生活。
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "dependencies_json", columnDefinition = "text")
    private List<String> dependencies;

    /** 这一次安排是否不可移动（"14:00 的考试"）。与 {@code PlanIntent.movableInTime()} 是两回事。 */
    @Column(name = "fixed_slot", nullable = false)
    private boolean fixedSlot;

    /** 这一项是在第几版里被创建的 —— 回答"它是原计划的, 还是重排时新插进来的"。 */
    @Column(name = "created_in_revision", nullable = false)
    private long createdInRevision;

    /** 一句话备注（完成情况、放弃原因、拷贝来由…）。 */
    @Column(name = "note", length = 1000)
    private String note;

    /** 行键的拼法 —— 写与读必须用同一个, 所以只有这一处。 */
    public static String rowKeyOf(String revisionId, String itemId) {
        return revisionId + "#" + itemId;
    }

    public String describe() {
        return "[" + itemId + " @ " + revisionId + "] " + intentTypeNamespace + "." + intentTypeName
                + " " + startAt + "→" + endAt + " (" + lifecycle + ")";
    }
}
