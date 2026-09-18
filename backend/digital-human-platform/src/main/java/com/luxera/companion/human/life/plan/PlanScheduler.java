package com.luxera.companion.human.life.plan;

import java.time.Instant;
import java.util.List;

/**
 * V2.2 §3.5.5 —— <b>把"到点了"这件事从计划表里问出来</b>。
 *
 * <h2>它<b>不</b>理解"写作业"、"上课"</h2>
 * 这是本接口最重要的性质。Scheduler 只理解三样东西:
 * <ol>
 *   <li>{@link PlanItem} —— 一项安排;</li>
 *   <li>{@link TimeWindow} —— 它占的时间段;</li>
 *   <li>{@link PlanTrigger} —— 它到点了这件事。</li>
 * </ol>
 *
 * <p>"写作业"要做哪些步骤、需要什么能力、做完算不算完成 —— 那些是
 * {@link PlanIntent} 和 {@link PlanReplanner} 的事。<b>调度器一旦开始理解活动语义,
 * 它就会长出针对具体活动的 {@code if}, 而那些 {@code if} 就是枚举的雏形。</b>
 *
 * <h2>与现有 {@code LifeTickJob} 的差别是本质的</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code LifeTickJob}（旧）</th><th>本接口</th></tr>
 *   <tr><td>每个 tick 做什么</td><td>扫描<b>全部</b> {@code life_activities},
 *       <br/>逐条判断"该结束了吗"</td>
 *       <td>只查索引里 {@code start_at <= t} 的那一小撮</td></tr>
 *   <tr><td>复杂度</td><td>O(n) / tick, 而 tick 是秒级</td><td>O(log n + k), k = 该触发的项数</td></tr>
 *   <tr><td>触发去重</td><td>靠活动自己的状态判断</td><td>由 {@link PlanBoard} 统一去重</td></tr>
 * </table>
 *
 * <p>这个差别在"每个 tick 都要结算持续影响账本"（见 {@code TickAware}）叠加上之后
 * 变得关键: 一个 agent 一秒钟要做的工作量决定了它能不能跑在实时约束下。
 *
 * <h2>为什么 {@link #register} 与 {@link #reschedule} 是两个方法</h2>
 * 它们在实现上确实可能只差一行, 但语义不同, 而语义决定了日志与诊断:
 * <ul>
 *   <li>{@link #register} = "启用这一版计划"（启动、恢复）;</li>
 *   <li>{@link #reschedule} = "换一版"（重排）。</li>
 * </ul>
 *
 * <p>合成一个方法会让"她今天重排了几次"这个统计无法从调度器侧得到 ——
 * 而那个数字是行为分析关心的（计划稳定性）。分开它, 日志里就能一眼分开这两件事。
 *
 * <h2>为什么没有 {@code nextDueAt()}</h2>
 * 一个"下一个触发时刻是什么"的查询看起来很有用（可以据此跳过空闲的 tick）。
 * 它被刻意省略, 因为它的引入会让"时间推进"这件事出现<b>两个驱动源</b>:
 * tick 循环推一次, 调度器的预测推一次。而两者在重排之后必然短暂不一致 ——
 * 于是会出现"跳过的 tick 里恰好有该处理的事"这种极难复现的漏触发。
 * <b>让 tick 循环做唯一的驱动源, 是省掉一整类 bug 的代价最小的办法。</b>
 */
public interface PlanScheduler {

    /** 启用一版计划（替换掉当前调度）。 */
    void register(PlanRevision revision);

    /**
     * 取消某一项。
     *
     * <p>它与"重排时 {@code Remove}"的区别: 这里是<b>单个</b>操作的接口,
     * 给外部驱动用（比如用户直接说"别去实验室了"）。重排走
     * {@link PlanBoard#apply} 的整套改动 —— 因为重排是一个决定, 而这个是一个指令。
     */
    void cancel(PlanItemId id);

    /** 用新版本重排（替换掉当前调度）。 */
    void reschedule(PlanRevision revision);

    /**
     * 到点了该触发哪些？
     *
     * <p><b>走索引, 不是全表扫描。</b>返回的项在这一刻之后不会再被返回 ——
     * 去重由实现负责（见 {@link PlanTrigger} 关于"一次触发就是一个事实"的说明）。
     *
     * @param time 仿真时刻
     */
    List<PlanTrigger> dueAt(Instant time);

    /** 调度器现在管着几项 —— 诊断用。 */
    int scheduledCount();

    /** 当前生效的版本 —— 诊断用。 */
    PlanRevision currentRevision();
}
