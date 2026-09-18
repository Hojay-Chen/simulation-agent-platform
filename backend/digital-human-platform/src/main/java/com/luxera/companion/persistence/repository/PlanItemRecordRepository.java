package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.PlanItemRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * V2.2 §7.2 —— {@code plan_item} 的读口。
 *
 * <h2>这个接口里<b>没有</b>"扣减剩余时长"的方法, 而且这不是遗漏</h2>
 * §9 验收标准 G 的原文是: "计划表在任何时刻都是一个完整的
 * {@code [start_at, end_at]} 窗口; <b>领域模型与数据库 schema 中都不存在
 * {@code remainingDuration} 这个概念</b>"。
 *
 * <p>所以任何形如下面的方法都<b>不允许</b>出现在这里:
 * <pre>
 *   // 禁止 —— 它凭空发明了一个领域里不存在的量
 *   @Modifying @Query("update PlanItemRecord i set i.remainingMs = i.remainingMs - :d ...")
 *   void burnDown(long durationMs);
 * </pre>
 * 想"更新进度"的人要写的是 {@code save} 上一个<b>新的</b> {@link PlanItemRecord}
 * （新的 {@code item-N}、新的窗口）—— 见 {@link PlanItemRecord} 类注释关于
 * "重排产生新 id"的论证。
 *
 * <h2>"当前活着的项"为什么有两个方法</h2>
 * 它们服务于两个不同的查询, 索引也因此不同:
 * <ul>
 *   <li>{@link #findByRevisionIdAndLifecycleIn} —— <b>一版之内</b>。恢复某一份
 *       计划快照时用（{@code PlanStore.restore} 沿链取一版, 再取它的项）;</li>
 *   <li>{@link #findByHumanIdAndLifecycleIn} —— <b>跨版本</b>的"她现在归哪一项"。
 *       它走 {@code idx_plan_item_human_lifecycle}, 而不是靠 join
 *       （本仓无外键, 见 {@link PlanItemRecord#getHumanId()} 的说明）。</li>
 * </ul>
 */
public interface PlanItemRecordRepository extends JpaRepository<PlanItemRecord, String> {

    /**
     * 某一版里的全部项 —— 走 {@code idx_plan_item_revision_start} 的<b>前缀</b>
     * （{@code revision_id} 是那条索引的第一列, 所以这个查询用得上它, 即使不带范围条件）。
     *
     * <p>正序按 {@code startAt}: 一版计划的自然读法是"从早到晚"。
     */
    List<PlanItemRecord> findByRevisionIdOrderByStartAtAsc(String revisionId);

    /**
     * §7.2 明确点名的那条查询: <b>支撑 {@code dueAt(t)} 索引扫描</b>。
     *
     * <p>原文: "索引: {@code CREATE INDEX ON plan_item (revision_id, start_at)}
     * —— 支撑 {@code dueAt(t)} 索引扫描"。
     *
     * <p>形状是"等值列 + 范围列": 先钉住哪一版（{@code revision_id}）, 再按
     * {@code start_at} 扫一段。这正是那条复合索引的列序存在的理由 ——
     * 反过来（{@code start_at} 在前）会让数据库先按时间扫<b>全部版本</b>的项,
     * 再丢掉不属于这一版的。在一张只增不减的表上, 那个代价随时间增长。
     */
    List<PlanItemRecord> findByRevisionIdAndStartAtLessThanOrderByStartAtAsc(
            String revisionId, Instant before);

    /**
     * 一版里指定去向的项 —— 例如"这一版里被放弃的那几项"。
     *
     * <p>用 {@code In} 而不是多次单值查询: 调用方（{@code abandonedItems()} 那一侧）
     * 手里本来就有一组状态, 而逐次查询会让一次恢复变成 N 次往返。
     */
    List<PlanItemRecord> findByRevisionIdAndLifecycleInOrderByStartAtAsc(
            String revisionId, Collection<String> lifecycles);

    /**
     * 跨版本的"她现在归哪一项" —— 走 {@code idx_plan_item_human_lifecycle}。
     *
     * <p>为什么需要它（而不是只靠上一条）: {@code PlanBoard} 有一个
     * {@code IN_MEMORY_HISTORY_LIMIT}, 于是"内存里已被淘汰、数据库里还在"
     * 是一个正常状态。重启时若只知道"最新一版里活着的项", 那些从更早版本里
     * 延续下来、仍未被取代的项会被漏掉 —— 表现是"她重启后忘了自己正在等衣服洗完"。
     */
    List<PlanItemRecord> findByHumanIdAndLifecycleInOrderByStartAtAsc(
            String humanId, Collection<String> lifecycles);

    /** 界面与诊断: 她最近安排的事, 倒序。 */
    List<PlanItemRecord> findTop100ByHumanIdOrderByStartAtDesc(String humanId);

    /** 这一版有几项。 */
    long countByRevisionId(String revisionId);

    /**
     * 这一项在这一版里存在吗 —— 写入时的完整性检查（"这条 mutation 指向的项还在不在"）。
     *
     * <p>按 {@code item_id}（领域 id）而不是按主键查: 主键是
     * {@code revision_id + "#" + item_id}（见 {@link PlanItemRecord} 的类注释"主键是复合的"）,
     * 而调用方手上只有领域那一半。见 {@code PlanItemRecord#rowKeyOf}。
     *
     * <p>{@code dependencies} 那一列的引用完整性<b>不在这里检查</b>: 它是 JSON 列,
     * 要判"有没有别的项依赖我"必须把那一版的项全读进内存。而按
     * {@code PlanItemRecord#getDependencies()} 的说明, 悬空的依赖是<b>合法的</b>
     * （依赖可以指向更早版本里已被 SUPERSEDED 的项）, 所以检查它本来就不该做。
     */
    boolean existsByRevisionIdAndItemId(String revisionId, String itemId);

    /**
     * 一项计划跨版本的全部行 —— 走 {@code idx_plan_item_item}。
     *
     * <p>"item-3 的一生": 它在第几版被插进来、在哪一版变成 {@code SUPERSEDED}、
     * 有没有哪一版把它挪了时间。返回顺序按<b>行键</b>（即 {@code rev-…} 字符串）——
     * 而这一点必须写明: <b>{@code rev-10} 会排在 {@code rev-9} 前面</b>
     * （字符串序, 不是数字序）。所以调用方若要"从早到晚"读它的一生, 必须自己按
     * {@code createdInRevision} 或 {@code plan_revision.revision_number} 重排。
     * 刻意不提供一个"看起来能排序"的方法名去掩盖这件事 ——
     * 一个按字符串序的 {@code ...OrderByRevisionIdAsc} 会在她的第 10 次重排之后
     * 才开始给出错误的顺序, 而那时候没有人会怀疑排序。
     */
    List<PlanItemRecord> findByItemIdOrderByRevisionIdAsc(String itemId);
}
