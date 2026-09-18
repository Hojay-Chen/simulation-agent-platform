package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.PlanRevisionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code plan_revision} 的读口。
 *
 * <h2>这个接口里没有任何 {@code delete} —— 这是本表最重要的一条纪律</h2>
 * {@code PlanRevision} 的类注释把承诺写死了: <b>"永不删除"</b>, 因为它是审计、
 * 回放、调试、行为分析、Agent 学习五件事的共同地基。
 * 而这条承诺在本接口上的实现方式只有一个: <b>这里不出现任何删除方法</b>。
 *
 * <p>必须承认这<b>不是</b>一条类型系统能保证的纪律: {@code JpaRepository} 自带
 * {@code deleteAll()} / {@code deleteById()}。真正的保证是没有任何业务代码调它们。
 * 写这段 javadoc 的实际作用, 是让下一个想让"清理旧计划"的人<b>先看到它被明确禁止过</b>
 * —— 因为 {@code deleteAllByHumanId} 这种方法的诱惑是真实的（"她这一版之前的
 * 计划都过时了, 删掉省地方"）, 而它的后果是行为分析失去唯一的时间线。
 *
 * <h2>为什么没有 {@code update} 相关的方法</h2>
 * 一个 Revision 一旦写入就不可变（见 {@link PlanRevisionRecord} 的类注释）。
 * {@code save()} 对一个已存在的 id 是 merge（= UPDATE）, 所以纪律是
 * <b>revision id 由领域生成且全局唯一, 每一次重排都是新 id</b> ——
 * {@code revisionNumber} 的单调递增保证了这一点。
 * 本接口不提供"按 id 覆盖"的便捷方法, 正是为了让那次 merge 只能由
 * {@code PlanStore} 显式发起。
 */
public interface PlanRevisionRecordRepository extends JpaRepository<PlanRevisionRecord, String> {

    /**
     * 恢复流程的第一问: "她最新的计划是哪一版" —— 走
     * {@code idx_plan_revision_human_number} 并直接取首行。
     *
     * <p>用 {@code findFirstBy...OrderByRevisionNumberDesc} 而不是把全部版本捞回内存排序:
     * 这张表只增不减（她每重排一次就多一行）, 而"取最新一版"是每次重启都要问的问题。
     * 全量拉取的代价随她的运行时长线性增长 —— 这是"跑了半年的实例启动变慢"的典型来源。
     *
     * <p>注意排序键是 {@code revisionNumber} 而不是 {@code createdSimAt}:
     * 版本号是<b>全序</b>（单调递增、无重复）, 而仿真时刻可能因为时钟精度或
     * 一次批量重排而相同 —— 相同时刻的两行谁"最新"在排序里是不确定的,
     * 那会让恢复结果在两次重启之间不同。
     */
    Optional<PlanRevisionRecord> findFirstByHumanIdOrderByRevisionNumberDesc(String humanId);

    /**
     * 全链回溯 —— "她的计划是怎么一路变成现在这样的"。
     *
     * <p>正序是必须的: 行为分析与审计要读的是<b>演化</b>, 而倒序会把
     * "她先想学数学, 后来改成写作业"讲成反的。
     */
    List<PlanRevisionRecord> findByHumanIdOrderByRevisionNumberAsc(String humanId);

    /** 分页形态 —— 界面只展示最近若干版, 而全链可能很长。 */
    List<PlanRevisionRecord> findTop50ByHumanIdOrderByRevisionNumberDesc(String humanId);

    /**
     * 沿链回溯一步 —— 走 {@code idx_plan_revision_previous}。
     *
     * <p>返回 {@code List} 而不是 {@code Optional}: {@code previous_revision_id}
     * 上<b>刻意没有唯一约束</b>（分叉是合法的, 见 {@link PlanRevisionRecord} 的类注释）。
     * 用 {@code Optional} 会让一次合法的分叉表现成
     * {@code IncorrectResultSizeDataAccessException} —— 一个数据库异常,
     * 而它其实只是"她重排了两次、都接着 rev-17"这个正常状态。
     */
    List<PlanRevisionRecord> findByPreviousRevisionIdOrderByRevisionNumberAsc(String previousRevisionId);

    /**
     * 第一版 —— {@code previousRevisionId} 为空的那一行。
     *
     * <p>链的显式起点。恢复流程<b>不</b>需要它（从最新的那一版沿链回溯就够了）,
     * 但审计与"她最初的计划是什么"这个问题需要。
     */
    Optional<PlanRevisionRecord> findFirstByHumanIdAndPreviousRevisionIdIsNullOrderByRevisionNumberAsc(
            String humanId);

    /** 她一共重排过多少次。 */
    long countByHumanId(String humanId);

    /** 链的存在性检查 —— {@code previousRevisionId} 指向的那一版真的在吗。 */
    boolean existsByRevisionId(String revisionId);
}
