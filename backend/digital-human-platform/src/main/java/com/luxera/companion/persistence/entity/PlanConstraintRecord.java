package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.util.Map;

/**
 * V2.2 §7.2 / §3.5.4 —— {@code plan_constraint}: <b>"什么条件下这件事做不了"</b>。
 *
 * <h2>为什么约束也要落库, 而不是每次从代码里重新算</h2>
 * {@code PlanConstraint} 的类注释把"约束必须是开放的"论证得很透（三方最清楚
 * 实验室安全、考试周禁娱、下雨不外出这些事）。落库这一侧要回答的是另一个问题:
 * <b>约束带参数。</b>
 * <pre>
 *   BudgetConstraint(budget=200, category="交通")
 *   LaboratorySafetyConstraint(lab="B-207", requiresTraining=true)
 * </pre>
 * 参数是这条约束的<b>内容</b>, 不是它的类型。不存参数, 恢复出来的
 * "她当时的计划受哪些约束管着"就只剩下一串类型名 —— 而行为分析要问的恰恰是
 * "她为什么没去实验室", 那需要 {@code lab="B-207"} 这个具体值。
 *
 * <h2>{@code plan_item_id} 为什么可以为空 —— 这是与 §7.2 的一处补充</h2>
 * §7.2 的 {@code plan_constraint} 只有 {@code plan_item_id}, 也就是"这一项自己的约束"。
 * 但 {@code PlanRevision} 上还有一层 <b>全局约束</b>:
 * <pre>{@code
 *   PlanRevision.constraints()   // "这一版生效的全局约束"
 *   PlanItem.constraints()       // "这一项自己附加的约束"
 * }</pre>
 * 那一层在 §7.2 里没有归宿。三个选择:
 * <table border="1">
 *   <tr><th>做法</th><th>代价</th></tr>
 *   <tr><td>再建一张 {@code plan_revision_constraint} 表</td>
 *       <td>两张形状完全一样的表（除了一列是 {@code revision_id} 还是 {@code plan_item_id}）,
 *           于是每一个读约束的地方都要写两遍, 而漏掉一遍的表现是
 *           "全局约束在恢复后消失了" —— 她的计划会忽然排进一堆本该被挡住的事</td></tr>
 *   <tr><td>把全局约束塞进 {@code plan_revision} 的一列 JSON</td>
 *       <td>约束的两种归属有了两套存储与两套读法, 而它们必须保持一致。
 *           这是"两个真相源"的一个新实例</td></tr>
 *   <tr><td><b>一列可空的 {@code plan_item_id}（本实现）</b></td>
 *       <td>{@code NULL} = 属于这一版（全局）, 非空 = 属于某一项。
 *           代价是读的时候要多一个判断, 收益是只有一套存储、一套读法</td></tr>
 * </table>
 *
 * <p>这条补充不引入新概念（"约束可以挂在版本上"领域里已经有了）, 只是给它一个位置。
 */
@Entity
@Table(name = "plan_constraint", indexes = {
        // ① 读某一版的全部全局约束。见本类注释: plan_item_id IS NULL 的那些。
        @Index(name = "idx_plan_constraint_revision", columnList = "revision_id"),
        // ② 读某一项自己的约束 —— 恢复一条计划项时的必经之路。
        @Index(name = "idx_plan_constraint_item", columnList = "plan_item_id"),
        // ③ "这条约束是从第几版开始管着她的、到第几版消失" —— 行为分析里
        //    "那条禁令什么时候解除的"这个问题。按领域的 constraint_id 查,
        //    而不是按行键（行键里混着 revision, 无法跨版本区配）。
        @Index(name = "idx_plan_constraint_cid", columnList = "constraint_id")
})
@Getter
@Setter
public class PlanConstraintRecord {

    /**
     * 行键 —— {@code rev-18#c-7}。<b>不是</b>领域的约束 id。
     *
     * <h2>为什么约束的主键也不能是 {@code ConstraintId}</h2>
     * 与 {@code PlanItemRecord} 同一条理由, 但这里的重复是<b>必然</b>而不是偶然:
     * {@code PlanRevision.next(...)} 把 {@code constraints} <b>原样传给下一版</b>
     * —— 一版计划上的全局约束会出现在它之后<b>每一版</b>的行里, 且它的
     * {@code ConstraintId} 一个字都不会变。用 {@code ConstraintId} 当主键,
     * 第二次重排就会主键冲突。
     *
     * <p>领域的 id 因此单独存一列（见 {@link #constraintId}）:
     * "这条约束贯穿了哪几版"要按它查, 而"日志里那个 id 在库里查不到"这个
     * 纯人为的故障也因此不会出现 —— 两列都在, 谁都不会丢。
     */
    @Id
    @Column(name = "id", length = 192)
    private String id;

    /** 领域里的约束 id —— {@code PlanConstraint.ConstraintId.value()}。跨版本引用靠它。 */
    @Column(name = "constraint_id", nullable = false, length = 128)
    private String constraintId;

    /** 属于哪一版 —— 全局约束与项约束都有它。 */
    @Column(name = "revision_id", nullable = false, length = 64)
    private String revisionId;

    /** 属于哪一项。<b>NULL 表示这是这一版的全局约束</b>（见类注释）。 */
    @Column(name = "plan_item_id", length = 64)
    private String planItemId;

    /** 约束类型三元组 —— 三方自己的命名空间（{@code laboratory.safety} 之类）。 */
    @Column(name = "constraint_namespace", nullable = false, length = 96)
    private String constraintNamespace;

    @Column(name = "constraint_name", nullable = false, length = 96)
    private String constraintName;

    @Column(name = "constraint_version", nullable = false)
    private int constraintVersion;

    /**
     * 约束的参数 —— 多态载荷。
     *
     * <p>见类注释里 {@code BudgetConstraint(budget=200)} 那个例子:
     * 参数是这条约束的内容。存成 JSON 而不是拆成列, 是因为参数的形状
     * <b>由三方决定</b> —— 平台不知道"实验室安全"需要哪些参数, 正如它不知道
     * 一个 {@code ActionCommand} 需要哪些参数（那是 {@code CapabilityDescriptor} 的事）。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "constraint_json", columnDefinition = "text")
    private Map<String, Object> constraintJson;

    /** 行键的拼法 —— 写与读必须用同一个, 所以只有这一处。 */
    public static String rowKeyOf(String revisionId, String constraintId) {
        return revisionId + "#" + constraintId;
    }

    /** 这是这一版的全局约束吗。 */
    public boolean revisionWide() {
        return planItemId == null || planItemId.isBlank();
    }

    public String describe() {
        return (revisionWide() ? "全局" : "项 " + planItemId + " 上")
                + " 的 " + constraintNamespace + "." + constraintName;
    }
}
