package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.ActivityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code activity_record} 的读口。
 *
 * <h2>恢复流程只调一个方法</h2>
 * {@link #findFirstByHumanIdAndStateOrderByStartedAtDesc} 是 {@code ActivityStore.restore}
 * 的全部内容 —— "她现在有没有在做的事"。它是一条索引查询（走
 * {@code idx_activity_human_state}）, 不是"把她这一辈子的活动捞出来找找看"。
 *
 * <p>返回 {@code Optional} 而不是 {@code List}: 业务上她<b>最多只能同时做一件事</b>
 * （{@code Life.currentActivity()} 是 {@code Optional<Activity>}）。
 * 若这里返回多行, 那本身就是一个数据异常, 而 {@code Optional} 会让它变成
 * {@code IncorrectResultSizeDataAccessException} —— <b>一个会炸的 bug 比一个被静默
 * 忽略的第二行好</b>。用 {@code List} 取第一条会让"她同时在睡觉和跑步"这件事
 * 永远不被发现。
 *
 * <h2>为什么没有"按 state 全表查"的方法</h2>
 * 那正是上面那个查询的<b>错误写法</b>: 它会把全部 agent 的运行中活动一起捞出来,
 * 而恢复是针对一个 agent 的。列进这个接口等于给错误写法发了一张通行证。
 *
 * <h2>为什么也<b>没有</b>"找出 activity_type 与 intent_type 不一致的行"那个方法</h2>
 * 那个查询（见 {@link ActivityRecord} 类注释: 它是"某个三方忘了注册"的唯一可查信号）
 * 要比较两列, 而 Spring Data 的派生查询表达不了它 —— 只能写 {@code @Query} 或原生 SQL。
 * 这里刻意<b>不写</b>: 它无法走索引（{@code activity_type_name <> intent_type_name}
 * 是全表扫）, 而作为一条诊断查询, 它的自然形态是控制台里的一个手写 SQL,
 * 不是一个会被误接进 tick 循环的仓库方法。
 */
public interface ActivityRecordRepository extends JpaRepository<ActivityRecord, String> {

    /**
     * 重启后的第一问: "她现在是不是正在做某件事" —— 走 {@code idx_activity_human_state}。
     *
     * <p>{@code state} 的取值由调用方给 {@code "RUNNING"}（见
     * {@code ActivityRecord.running()}）。刻意不在接口上写死常量方法: 这个接口只做映射,
     * 不做决策 —— 什么状态算"在做", 是 {@code AbstractActivity} 那一侧的定义。
     */
    Optional<ActivityRecord> findFirstByHumanIdAndStateOrderByStartedAtDesc(
            String humanId, String state);

    /**
     * 她这一天做了什么 —— 时间轴视图与行为分析, 正序。走 {@code idx_activity_human_time}。
     *
     * <p>正序而不是倒序: 行为分析要读的是"她的一天怎么流动的",
     * 而"从早上到晚上"与"从晚上到早上"讲的是两个不同的故事（后者是复盘, 前者是经历）。
     */
    List<ActivityRecord> findByHumanIdAndStartedAtBetweenOrderByStartedAtAsc(
            String humanId, Instant from, Instant to);

    /** 界面与诊断: 最近做的几件事, 倒序。 */
    List<ActivityRecord> findTop100ByHumanIdOrderByStartedAtDesc(String humanId);

    /**
     * 计划 vs 实际: "这一项计划实际被执行过几次、每次多久" —— 走 {@code idx_activity_plan_item}。
     *
     * <p>正序是必要的: 要算"第一次做和第二次做之间隔了多久"。
     */
    List<ActivityRecord> findByPlanItemIdOrderByStartedAtAsc(String planItemId);

    /** 计数 —— "她今天做了几件事"。 */
    long countByHumanIdAndStartedAtBetween(String humanId, Instant from, Instant to);
}
