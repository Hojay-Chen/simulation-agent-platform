package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

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
 * V2.2 §7.2 / §3.5.3 —— {@code plan_revision}: <b>某一刻她对未来的完整安排, 链式, 永不删除</b>。
 *
 * <h2>这张表是"她为什么改主意了"的唯一答案来源</h2>
 * {@code PlanRevision} 的类注释把承诺写死了:
 * <blockquote>
 * 每一次重排都产出一个<b>新的</b> Revision, 旧的进 {@code revisionHistory} —— <b>永不删除</b>。
 * 这条"永不删除"是审计、回放、调试、行为分析、Agent 学习五件事的共同地基。
 * </blockquote>
 * 落到这张表上就是两条纪律, 而它们都不是默认行为, 必须显式写出来:
 * <ol>
 *   <li><b>没有删除方法。</b> {@code PlanRevisionRecordRepository} 里不出现 {@code delete*}
 *       —— 一个继承自 {@code JpaRepository} 的接口<b>自带</b> {@code deleteAll()},
 *       所以"这张表不会被删空"这件事靠的是没有任何业务代码调它, 而不是接口上没这个方法。
 *       这一点在实体这一侧能做的只有把它写进 javadoc, 让它成为一个必须被遵守的约定;</li>
 *   <li><b>没有 UPDATE。</b> 一个 Revision 一旦写入就不可变 —— 这正是
 *       {@code PlanItem} 被写成不可变 record 的那条推理链的终点
 *       （"如果 PlanItem 可变, Revision 17 和 Revision 18 会指向同一个对象"）。
 *       store 那一侧只调 {@code save}, 而 {@code save} 对一个已存在的 id 是 merge
 *       （= UPDATE）—— 所以纪律是: <b>revision 的 id 由领域生成且全局唯一,
 *       每一次重排都是新 id</b>。{@code revisionNumber} 的单调递增保证了这一点。</li>
 * </ol>
 *
 * <h2>表名与旧的 {@code plan_revisions} 的关系（必须知道）</h2>
 * 本仓已有一张 {@code plan_revisions}（{@code plan/PlanRevision} 实体, V9 的
 * "计划变更的因果记录"）。两者的形状差别是本质的:
 * <table border="1">
 *   <tr><th></th><th>旧 {@code plan_revisions}</th><th>本表 {@code plan_revision}</th></tr>
 *   <tr><td>一行是什么</td><td>一次<b>状态变更</b>（CREATED/MODIFIED/…）</td>
 *       <td>一份<b>完整的未来安排</b>（"她此刻打算怎么样"）</td></tr>
 *   <tr><td>主键</td><td>随机 UUID</td><td>{@code rev-N}（版本号就是身份）</td></tr>
 *   <tr><td>内容</td><td>一个 action 字符串 + 前后状态</td><td>理由 + 全部计划项（在 {@code plan_item} 表里）</td></tr>
 * </table>
 *
 * <p>它们是两套模型, 不是新旧两版。但名字太近, 所以这里明确: 本表由 §8.3 的"直接替换"
 * 落地后, 旧的 {@code plan_revisions} 会随 {@code plan/} 包一起退场。
 *
 * <h2>为什么 {@code revision_id} 是 {@code rev-N} 而不是 §7.2 写的 UUID</h2>
 * 因为版本链的<b>两端都在领域里被钉死成了这个形状</b>:
 * <pre>
 *   PlanRevision.revisionId()          →  "rev-" + revisionNumber
 *   PlanRevision.previousRevisionId()  →  上一版的 revisionId
 *   PlanEvents.RevisionCreated.revisionId / previousRevisionId  →  同上
 * </pre>
 * 换成 UUID 会让 {@code plan.revision-created.v1} 的载荷、控制台里显示的
 * "承接 rev-17"、以及行为分析报告里的编号全部对不上。而好处是零 ——
 * 版本号本身就是自然的、单调的、可读的身份。
 *
 * <h2>为什么 {@code reason} 是普通文本列, 不是 §7.2 的 {@code reason_json}</h2>
 * 因为领域里它是 {@code String reason()} —— 一句<b>第一人称、面向人</b>的话
 * （"天冷了，我想先穿件衣服"）。§7.2 期望它装一个 {@code ReplanningReason} 对象,
 * 但在本仓里<b>没有任何类叫这个名字</b>（{@code grep -rn ReplanningReason} 零命中）。
 * 为一个不存在的类型建一列 JSON, 会得到一个永远只有一个键的字典:
 * {@code {"reason":"天冷了"}} —— 比直接存这句话多一层拆包, 而换不来任何可查询性。
 */
@Entity
@Table(name = "plan_revision", indexes = {
        // ① "她最新的计划是哪一版" —— 恢复流程与界面打开时的第一问。
        //    (human_id, revision_number DESC) 让这句话变成一次索引取首行, 而不是
        //    把她的全部历史版本捞回来按号排序。这张表只增不减, 所以这一点会越来越重要。
        @Index(name = "idx_plan_revision_human_number", columnList = "human_id,revision_number"),
        // ② 沿链回溯: "rev-18 的上一版是谁"。previous_revision_id 在链上是唯一的,
        //    但刻意<b>不加唯一约束</b> —— 见本类注释末尾关于"分叉"的说明。
        @Index(name = "idx_plan_revision_previous", columnList = "previous_revision_id")
})
@Getter
@Setter
public class PlanRevisionRecord {

    /** 版本 id —— {@code rev-N}。 */
    @Id
    @Column(name = "revision_id", length = 64)
    private String revisionId;

    /** 谁的计划 —— agent 的 id。§7.2 写 UUID, 本仓的 agent id 是 36 位字符串。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /**
     * 版本序号 —— 从 1 开始。
     *
     * <h2>为什么要存它（{@code rev-18} 里已经有 18 了）</h2>
     * 这是本表里唯一一处<b>刻意的冗余</b>, 而它换来的是可查询性:
     * <pre>
     *   -- 有这一列:
     *   SELECT * FROM plan_revision WHERE human_id = ? ORDER BY revision_number DESC LIMIT 1;
     *                                                          ^^^^^^^^^^^^^^^ 走索引
     *
     *   -- 没有这一列（只能从 rev-N 里解析）:
     *   ORDER BY CAST(SUBSTRING(revision_id FROM 5) AS INTEGER) DESC   -- 全表扫 + 无法索引
     * </pre>
     * 第二个写法还有第二个毛病: 它把一个<b>领域约定</b>（id 前缀是 {@code rev-}）
     * 编码进了 SQL。哪天这个前缀改了, 这条查询会静默地返回错的东西 ——
     * 因为 {@code SUBSTRING} 不会报错, 它只会切出一个空串然后转数字失败或者变成 0。
     */
    @Column(name = "revision_number", nullable = false)
    private long revisionNumber;

    /**
     * 前一版的 id。第一版为空 —— <b>链是显式的</b>, 不是靠"按号排序"隐含推出来的。
     *
     * <p>为什么显式的链是必须的（{@code PlanEvents.RevisionCreated} 的 javadoc 已论证）:
     * {@code PlanBoard} 有一个 {@code IN_MEMORY_HISTORY_LIMIT}, 于是"内存里已被淘汰、
     * 数据库里还在"是一个正常状态。那一刻若靠"按 revisionNumber 排序"去推前驱,
     * 推出来的会是一个<b>不存在的版本号</b>。
     *
     * <p>为什么<b>不加唯一约束</b>: 唯一约束会禁止两个版本指向同一个前驱 ——
     * 也就是禁止<b>分叉</b>。而分叉在语义上是合法的: "她重排了一次, 又回退到刚才那一版
     * 重新考虑"会产生两条都以 rev-17 为前驱的记录。加约束的后果是第二次保存
     * 抛一个数据库约束异常, 而异常发生在<b>她正在重新安排自己的生活那一刻</b>。
     */
    @Column(name = "previous_revision_id", length = 64)
    private String previousRevisionId;

    /** 为什么产生这个版本。第一人称、面向人 —— 它会进 LLM context 与行为分析报告。 */
    @Column(name = "reason", length = 1000)
    private String reason;

    /** 版本产生时刻（仿真时刻）。 */
    @Column(name = "created_at_sim", nullable = false)
    private Instant createdSimAt;

    /**
     * 这一版相对上一版做了哪些改动。
     *
     * <h2>为什么它是一个 {@code text} 列而不是 §7.2 之外的另一张表</h2>
     * §7.2 没有给 mutations 一个位置。三个可能:
     * <table border="1">
     *   <tr><th>做法</th><th>代价</th></tr>
     *   <tr><td>不存</td><td>恢复出来的 Revision 丢掉 {@code abandonedItems()} 与
     *       {@code keepActiveCount()} —— 而"她这一版放弃了几项"是行为分析里
     *       最有价值的一个数。恢复与写入不对称, 是这一层唯一不可接受的故障</td></tr>
     *   <tr><td>单独一张 {@code plan_mutation} 表</td>
     *       <td>六种 mutation 各有不同的字段（Move 有窗口、Resize 有时长、Replace 有两端）。
     *           要么六张表, 要么一张宽表里一半列永远为空。而 mutations 的访问模式是
     *           <b>"跟着某一版一起读出来"</b>, 从来不单独查 —— 这正是一列 JSON 的形状</td></tr>
     *   <tr><td><b>一列 JSON（本实现）</b></td>
     *       <td>—— 代价是它是手写编解码（见 {@code PlanMutationCodec}）, 因为 Jackson
     *           绑不动它: {@code PlanMutation.Insert} 里嵌着一个完整的 {@code PlanItem},
     *           而 {@code PlanItem} 里又有一个开放接口 {@code PlanIntent}</td></tr>
     * </table>
     *
     * <p><b>为什么不用多态序列化器直接写这列</b>: {@code PlanMutation} 是 sealed interface,
     * 六个实现都是 record 且<b>没有标 {@code @DomainType}</b>, 于是写进去会带一个
     * {@code _untyped} 标记、读回来只能是个 Map —— 那就不是"存下去再读回来是同一个对象"了。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "mutations_json", columnDefinition = "text")
    private Map<String, Object> mutationsJson;

    /** 这一行什么时候被写进来的（墙上时钟）。与 {@code created_at_sim} 是两种时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (revisionId == null) {
            // 走到这里说明调用方忘了设版本号 —— 一个没有身份的版本会让链断掉。
            // 给一个可辨认的兜底值而不是 UUID: UUID 看起来像正常数据, 而
            // "rev-unknown-<uuid>" 在库里一眼就是个 bug
            revisionId = "rev-unknown-" + UUID.randomUUID();
        }
    }

    public boolean isFirstVersion() {
        return previousRevisionId == null || previousRevisionId.isBlank();
    }

    public String describe() {
        return revisionId + (isFirstVersion() ? "（首版）" : " 承接 " + previousRevisionId)
                + " 「" + reason + "」 @ " + createdSimAt;
    }
}
