package com.luxera.companion.persistence.store;

import com.luxera.companion.human.life.plan.PlanConstraint;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.persistence.entity.PlanConstraintRecord;
import com.luxera.companion.persistence.entity.PlanItemRecord;
import com.luxera.companion.persistence.entity.PlanRevisionRecord;
import com.luxera.companion.persistence.repository.PlanConstraintRecordRepository;
import com.luxera.companion.persistence.repository.PlanItemRecordRepository;
import com.luxera.companion.persistence.repository.PlanRevisionRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.2 + §3.5.3 —— <b>计划版本链</b>的读写路径。
 *
 * <h2>一次保存写的不是一行, 是"一版"</h2>
 * {@code PlanRevision} 是一个聚合: 它自带版本号、上一步的引用、这一版的全部项、
 * 全部约束、以及这一版产生的全部改动。落到库里是<b>三张表上的若干行</b>:
 * <pre>
 *   plan_revision   1 行   （链的一节）
 *   plan_item       N 行   （这一版的项）
 *   plan_constraint M 行   （这一版的全局约束 + 各项自己的约束）
 * </pre>
 * 这三张表必须<b>一起写</b>。分区写的后果是明确且严重的: 版本行写进去了、项没写进去,
 * 恢复出来就是一个"她有一版计划, 但那一版里什么都没有"的空壳 ——
 * 表现是"她重启后不记得自己要做什么", 而且库里每一张表看起来都是好的。
 * 所以本类只提供 {@link #appendRevision} 这一个写口, 没有"单独保存一项"的方法。
 *
 * <h2>为什么每一条 {@code plan_item} 行都要重写一遍（而不是只存变化的那几项）</h2>
 * 因为 {@code PlanBoard.apply} 的产物就是这样: 它把上一版的项放进一个 {@code working}
 * 表里, 逐条应用改动, 然后把整张表交给新的 {@code PlanRevision}
 * —— <b>没被碰过的项原样出现在新版本里</b>（同一个 {@code PlanItemId}）。
 * 于是"某一版的完整项集合"在数据上就是"那一版的全部行", 而不需要一次递归的
 * "沿链回溯 + 逐版合并"。代价必须写清楚:
 * <pre>
 *   存储量 = 项数 × 版本数
 *   一个跑了半年的 agent: 20 项 × 每天 5 次重排 × 180 天 = 18000 行
 * </pre>
 * 这个数量对一个 agent 的库来说完全可控, 而它换来的是:
 * <ul>
 *   <li>"她 12:15 时以为自己几点写作业"是<b>一次 WHERE 查询</b>, 不是一次重放
 *       （重放要从第一版一路应用几千条 mutation, 而其中任何一条读不懂都会让答案错）;</li>
 *   <li>历史版本<b>不可变</b> —— 新版本写新行, 老版本的行一个字节都不动。
 *       这直接满足"旧版本永不删除"那条承诺, 而差值存储做不到: 差值方案里,
 *       一次"改正一个旧的差值"会同时改变它之后所有版本的读法</li>
 * </ul>
 *
 * <h2>{@code mutations_json} 为什么包一层 {@code {"mutations": [...]}}</h2>
 * 因为那一列的读法是 {@code StringMapConverter}（{@code Map<String,Object> ⇄ text},
 * 见 {@code DomainPayloadCodec} 关于"本仓已经有一个读法"的论证）。
 * 一个裸的 JSON 数组<b>不是</b>一个 {@code Map} —— 想存数组只有两条路:
 * 换一个 converter（于是这一列成为全仓唯一一种 JSON 读法）, 或者包一层。
 * 包一层是便宜的那条, 而且它顺带留出了扩展位: 将来若要往这一列里加
 * "这一版的校验结果", 它就是一个新键, 而不是一次数据迁移。
 *
 * <h2>写入是<b>幂等</b>的</h2>
 * {@link #appendRevision} 在一个版本行已经存在时会检查它的项是否也写齐了,
 * 没写齐就补写。这不是多余的谨慎: "先写版本行、再写项"是两次数据库往返,
 * 中间崩一次就会留下一个空壳版本 —— 而那个空壳在恢复时看起来完全正常
 * （她只是碰巧没有安排任何事）。幂等让"重放一次保存"成为一个安全的修复动作。
 *
 * <p>这句话里的"检查"与"补写"都要真的发生才算数: 判据是行数与领域对象数
 * <b>相等</b>, 而判定为缺失之后<b>必须真的写</b>。这两条都曾经不成立
 * （见 {@link #repairOrSkip} 的说明）—— 一个"只记日志不写数据"的修复分支
 * 会把一次缺失伪装成已经自愈。
 */
@Slf4j
public class PlanStore {

    /** {@code mutations_json} 里那个包装键 —— 见类注释。 */
    static final String F_MUTATIONS = "mutations";

    private final PlanRevisionRecordRepository revisions;
    private final PlanItemRecordRepository items;
    private final PlanConstraintRecordRepository constraints;
    private final PlanItemCodec itemCodec;
    private final PlanConstraintCodec constraintCodec;
    private final PlanMutationCodec mutationCodec;

    public PlanStore(PlanRevisionRecordRepository revisions,
                     PlanItemRecordRepository items,
                     PlanConstraintRecordRepository constraints,
                     PlanItemCodec itemCodec,
                     PlanConstraintCodec constraintCodec,
                     PlanMutationCodec mutationCodec) {
        this.revisions = Objects.requireNonNull(revisions, "版本仓库不能为空");
        this.items = Objects.requireNonNull(items, "计划项仓库不能为空");
        this.constraints = Objects.requireNonNull(constraints, "约束仓库不能为空");
        this.itemCodec = Objects.requireNonNull(itemCodec, "计划项编解码器不能为空");
        this.constraintCodec = Objects.requireNonNull(constraintCodec, "约束编解码器不能为空");
        this.mutationCodec = Objects.requireNonNull(mutationCodec, "改动编解码器不能为空");
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 保存一版计划 —— <b>本类唯一的写口</b>。
     *
     * <p>写出的版本行<b>不引用它的项</b>（没有外键, 也是本仓惯例）:
     * 项通过 {@code revision_id} 反向找到。这不是偷懒 —— 它让"这一版有多少项"
     * 这个问题不需要读版本行。
     *
     * @return 版本行。{@code null} 只会在"这一版已经完整写过"时返回 ——
     *         调用方据此知道这次保存是一次无害的重放
     */
    public PlanRevisionRecord appendRevision(String humanId, PlanRevision revision) {
        Objects.requireNonNull(humanId, "版本必须属于某个人 —— 没有人的版本查不出来");
        Objects.requireNonNull(revision, "要保存的版本不能为空");

        String revisionId = revision.revisionId();
        if (revisions.existsByRevisionId(revisionId)) {
            return repairOrSkip(humanId, revision);
        }

        PlanRevisionRecord record = new PlanRevisionRecord();
        record.setRevisionId(revisionId);
        record.setHumanId(humanId);
        record.setRevisionNumber(revision.revisionNumber());
        record.setPreviousRevisionId(revision.previousRevisionId().orElse(null));
        record.setReason(revision.reason());
        record.setCreatedSimAt(revision.createdAt());
        record.setMutationsJson(encodeMutations(revision.mutations()));
        PlanRevisionRecord saved = revisions.save(record);

        writeItems(humanId, revision);
        writeConstraints(revision);
        return saved;
    }

    /**
     * 版本行在、三张表不全 —— <b>补写</b>; 三张表都齐了 —— 什么都不做。
     *
     * <h2>判据说清楚, 以及这里曾经有一个<b>谎报修复</b>的 bug</h2>
     * 判据是"这一版在 {@code plan_item} 与 {@code plan_constraint} 里的行数,
     * 是否分别等于领域里的项数与约束数"。刻意不看内容是否一致:
     * 一次"内容不同但数量相同"的补写在正常流程里不可能发生
     * （同一个版本号只会被产生一次）, 而为此写一段逐字段比对的代码,
     * 需要它的时候恰恰是最不需要它的时候 —— 那时人想的是"让它先跑起来"。
     *
     * <p>但<b>计数要比对的是"等于", 不是"大于零"</b>, 而且<b>判定为缺失之后必须真的写</b>
     * —— 这两条都是被一次真实的缺陷逼出来的:
     * <pre>
     *   旧实现: if (written &gt; 0) { return null; }   // "已经写过"
     *           log.warn("本次调用会把缺失的项补上");
     *           return existing;                     // ← 然后什么也没写
     * </pre>
     * 两个后果叠加起来是一个<b>静默的空壳</b>:
     * <ol>
     *   <li>"大于零"把"写到一半崩了"（{@code saveAll} 中途失败, 3 项里只落了 1 项）
     *       判成"写完了"。判据与它自己上面的承诺不符, 而判据是更宽的那一个;</li>
     *   <li>补写分支<b>只记日志不写数据</b>, 于是那个"写到一半"的版本<b>永远</b>停在
     *       一半 —— 每一次重启走这条路径都会再打印一遍同一句"本次调用会把缺失的项补上",
     *       而库里一个字节都不变。</li>
     * </ol>
     * 表现是: 她的一版计划里少了两项, 而她<b>看起来完全正常</b> ——
     * 她只是偶尔忘了自己下午要去实验室。日志里那句话甚至会把排查方向引偏:
     * "它会补上的"会让人以为问题已经自愈。
     *
     * <p>补写是安全的: 两张表的行键都带 {@code revisionId}
     * （{@code revisionId#itemId} / {@code revisionId#constraintId}）,
     * 所以重写同一版的同一项是<b>按主键覆盖</b>, 不会产生重复行。
     */
    private PlanRevisionRecord repairOrSkip(String humanId, PlanRevision revision) {
        String revisionId = revision.revisionId();

        PlanRevisionRecord existing = revisions.findById(revisionId).orElse(null);
        if (existing == null) {
            // existsByRevisionId 说在, findById 说不在 —— 只可能是一次并发删除。
            // 本表没有删除路径, 所以走到这里说明有人绕过了本类
            log.warn("[PlanStore] 版本 {} 的存在性检查与读取结果不一致 —— "
                    + "跳过这次保存。本表没有删除路径, 所以这不是正常状态", revisionId);
            return null;
        }

        long itemsWritten = items.countByRevisionId(revisionId);
        long itemsExpected = revision.items().size();
        long constraintsWritten = constraints.findByRevisionId(revisionId).size();
        long constraintsExpected = revision.constraints().size();
        for (PlanItem item : revision.items()) {
            constraintsExpected += item.constraints().size();
        }

        if (itemsWritten == itemsExpected && constraintsWritten == constraintsExpected) {
            log.info("[PlanStore] 版本 {} 已经写过（{} 项 / {} 条约束）—— 跳过这次保存。"
                    + "重复保存是安全的, 但如果你是在期望一次新的写入, 请检查版本号是不是撞了",
                    revisionId, itemsWritten, constraintsWritten);
            return null;
        }

        log.warn("[PlanStore] 版本 {} 的行在, 但它的三张表不齐（项 {}/{}、约束 {}/{}）—— "
                        + "这是一次'写到一半崩了'的痕迹。本次调用会把缺失的那些补上。"
                        + "补写是安全的: 两张表的行键都带着版本号, 重写是按主键覆盖",
                revisionId, itemsWritten, itemsExpected, constraintsWritten, constraintsExpected);
        writeItems(humanId, revision);
        writeConstraints(revision);
        return existing;
    }

    /** 只写项 —— 供 {@link #appendRevision} 的补写路径复用。 */
    private void writeItems(String humanId, PlanRevision revision) {
        List<PlanItemRecord> rows = new ArrayList<>(revision.items().size());
        for (PlanItem item : revision.items()) {
            rows.add(itemCodec.toRecord(item, humanId, revision.revisionId()));
        }
        if (!rows.isEmpty()) {
            items.saveAll(rows);
        }
    }

    /**
     * 写约束 —— <b>两种归属放在同一次写里</b>。
     *
     * <p>这一版的全局约束（{@code revision.constraints()}）与每一项自己的约束
     * （{@code item.constraints()}）落在同一张表, 靠 {@code plan_item_id} 是否为
     * {@code NULL} 区分（见 {@code PlanConstraintRecord} 的类注释）。
     *
     * <p>这里有一个容易漏掉的点: {@code PlanItemCodec.toRecord} <b>不写</b>项自己的约束
     * —— 它写的是项的那几列。约束一律由本方法写。所以"项在库里长什么样"这件事
     * 在两处定义（{@code PlanItemCodec} 与 {@code PlanConstraintCodec}）,
     * 而它们拼起来必须等于 {@code PlanItemCodec.toEmbedded} 写进
     * {@code mutations_json} 的那一份。这就是 {@code PlanItemCodec} 类注释里
     * "两种形态承载同样的信息"这句话的<b>真正含义</b>: 三种写法（两处列 + 一处嵌入）
     * 描述同一件事, 而它们的一致性由 {@link PlanStoreTest} 的往返用例保证。
     */
    private void writeConstraints(PlanRevision revision) {
        List<PlanConstraintRecord> rows = new ArrayList<>();
        for (PlanConstraint global : revision.constraints()) {
            rows.add(constraintCodec.toRecord(global, revision.revisionId(), null));
        }
        for (PlanItem item : revision.items()) {
            for (PlanConstraint own : item.constraints()) {
                rows.add(constraintCodec.toRecord(own, revision.revisionId(), item.id().value()));
            }
        }
        if (!rows.isEmpty()) {
            constraints.saveAll(rows);
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 恢复流程的第一问: <b>她现在的那一版计划是什么</b>。
     *
     * <p>取版本号最大的那一版（不是"最近写入的"、也不是"仿真时刻最晚的"）——
     * 版本号是全序, 而仿真时刻可能因批量重排而相同, 用后者会让恢复结果在两次
     * 重启之间不同（见 {@code PlanRevisionRecordRepository} 的同名方法）。
     */
    public Optional<PlanRevision> latest(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        return revisions.findFirstByHumanIdOrderByRevisionNumberDesc(humanId).map(this::assemble);
    }

    /** 指定的一版 —— 审计与"她当时是怎么安排的"。 */
    public Optional<PlanRevision> revision(String revisionId) {
        Objects.requireNonNull(revisionId, "版本 id 不能为空");
        return revisions.findById(revisionId).map(this::assemble);
    }

    /**
     * 全链 —— 她的计划是怎么一路变成现在这样的, 从早到晚。
     *
     * <p>返回<b>版本行</b>而不是 {@code PlanRevision}: 全链的每一版都去读它的项与约束,
     * 会把这个方法变成 N×3 次查询。要读某一版的完整内容, 拿它的
     * {@code revisionId} 走 {@link #revision}。
     */
    public List<PlanRevisionRecord> chain(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        return revisions.findByHumanIdOrderByRevisionNumberAsc(humanId);
    }

    /**
     * 一版的三张表 → 一个 {@code PlanRevision}。
     *
     * <p>这里用<b>会抛</b>的读法（{@code PlanItemCodec.fromRecord} 与
     * {@code PlanConstraintCodec.fromRecord} 都是）: 恢复"她此刻的计划"时少了一项,
     * 表现是"她忘了自己下午要去实验室"—— 一个看起来完全正常、只是偶尔丢事的人。
     * 而那比一次起不来更难查。
     */
    public PlanRevision assemble(PlanRevisionRecord record) {
        Objects.requireNonNull(record, "要装配的版本行不能为空");
        String revisionId = record.getRevisionId();

        List<PlanConstraint> revisionWide = new ArrayList<>();
        for (PlanConstraintRecord row : constraints.findByRevisionIdAndPlanItemIdIsNull(revisionId)) {
            revisionWide.add(constraintCodec.fromRecord(row));
        }

        List<PlanItem> planItems = new ArrayList<>();
        for (PlanItemRecord row : items.findByRevisionIdOrderByStartAtAsc(revisionId)) {
            PlanItem item = itemCodec.fromRecord(row);
            // 按 (版本, 项) 两个列查 —— 同一项会在多版里各有一行,
            // 只按项查会把历史上所有版本的约束都捞回来, 而表现是
            // "她重启后凭空多了一堆做不了的事"。过滤在 SQL 里做, 不在内存里
            List<PlanConstraint> own = new ArrayList<>();
            for (PlanConstraintRecord c
                    : constraints.findByRevisionIdAndPlanItemId(revisionId, row.getItemId())) {
                own.add(constraintCodec.fromRecord(c));
            }
            if (!own.isEmpty()) {
                item = item.withConstraints(own);
            }
            planItems.add(item);
        }

        return new PlanRevision(record.getRevisionNumber(),
                record.getPreviousRevisionId(),
                record.getReason() == null ? "" : record.getReason(),
                record.getCreatedSimAt(),
                planItems,
                revisionWide,
                decodeMutations(record.getMutationsJson()));
    }

    // ─────────────────────────── 编解码: 改动列表 ───────────────────────────

    /** 改动列表 → {@code mutations_json} 那一列的值（包一层, 见类注释）。 */
    private Map<String, Object> encodeMutations(List<PlanMutation> mutations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(F_MUTATIONS, mutationCodec.toList(mutations));
        return body;
    }

    /**
     * {@code mutations_json} → 改动列表。
     *
     * <p>那一列是 {@code null} 时返回空列表而不是抛: {@code null} 在这里的意思是
     * "这一列还没有值"（历史行、或由外部工具写入的行）, 而"一版没有记录任何改动"
     * 与"一版记录了零条改动"是同一件事 —— 第一版就是这样
     * （{@code PlanRevision.first} 传的就是 {@code List.of()}）。
     *
     * <p>但<b>包一层键存在、值读不懂</b>时必须抛: 那不是"没有改动",
     * 而是"有改动但我读不懂"。两者混淆的后果是 {@code keepActiveCount()} 与
     * {@code abandonedItems()} 静默归零 —— 而"她这一版放弃了几项"正是
     * 行为分析要读的东西。
     */
    @SuppressWarnings("unchecked")
    private List<PlanMutation> decodeMutations(Map<String, Object> column) {
        if (column == null || !column.containsKey(F_MUTATIONS)) {
            return List.of();
        }
        Object raw = column.get(F_MUTATIONS);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException(
                    "plan_revision.mutations_json 的 " + F_MUTATIONS + " 字段应当是数组, 实际是 "
                            + raw.getClass().getName() + " —— 它不是'没有改动', 是'改动读不出来'");
        }
        List<Map<String, Object>> bodies = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map)) {
                throw new IllegalStateException(
                        "mutations_json 的第 " + bodies.size() + " 个元素不是对象: "
                                + (element == null ? "null" : element.getClass().getName()));
            }
            bodies.add((Map<String, Object>) element);
        }
        return mutationCodec.fromList(bodies);
    }

    /** 诊断: 一行版本说的是什么。 */
    public static String describe(PlanRevisionRecord record) {
        Objects.requireNonNull(record, "要描述的行不能为空");
        return record.getRevisionId() + " (第 " + record.getRevisionNumber() + " 版) "
                + "自 " + (record.getPreviousRevisionId() == null ? "开头" : record.getPreviousRevisionId())
                + " 「" + record.getReason() + "」 @ " + record.getCreatedSimAt();
    }
}
