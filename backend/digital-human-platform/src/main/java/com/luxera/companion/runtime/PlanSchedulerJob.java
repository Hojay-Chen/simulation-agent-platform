package com.luxera.companion.runtime;

import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanTrigger;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §8.4 —— <b>推着她的一天往前走的那个 tick</b>。
 *
 * <h2>它做的唯一一件事</h2>
 * <pre>
 *   tick()  →  读 {@link SimulationClock#now()}  →  {@link Life#advanceTo(Instant)}  →  记录
 * </pre>
 *
 * <p>就这些。它<b>不</b>决定她该做什么, <b>不</b>开始任何活动, <b>不</b>改计划表。
 * 它只回答"现在几点了, 有没有到点的项", 然后把答案交出去。
 *
 * <h2>为什么是"轮询"而不是"给每一项挂一个定时器"</h2>
 * 定时器方案看起来更精确: 12:00 那一项就注册一个 12:00 的定时器,
 * 到点回调。它的问题是<b>重排</b>:
 *
 * <pre>
 *   12:00 写作业（定时器 A）          12:15 她决定先穿衣
 *        ↓                                ↓
 *   计划表产生新版本: 写作业被移到 12:25   定时器 A 现在错了
 *        ↓
 *   必须取消 A、注册 12:25 的新定时器、再给穿衣注册一个 12:15 的
 *        ↓
 *   而"取消旧的 + 注册新的"不是原子的 —— 中间那一瞬间,
 *   两个定时器可能都在, 也可能都不在
 * </pre>
 *
 * <p>后一种情况的症状是<b>漏触发</b>: 她 12:15 该穿衣服, 而那一刻恰好
 * 落在"旧的已取消、新的还没注册"的缝里。这种 bug 在重排频繁时出现,
 * 在不重排时不出现 —— 也就是只在真实使用中出现。
 *
 * <p>轮询方案里没有这个问题, 因为<b>没有任何状态需要同步</b>: 每个 tick 都
 * 重新问一次当前的计划表。重排只是让下一次询问的答案不同而已。
 * 代价是 tick 期间的空转 —— 而那个代价小到不值得讨论（一次 tick 是
 * 一次内存里的 {@code NavigableMap} 区间查询）。
 *
 * <h2>"到点了" ≠ "她开始做了" —— 这是本类最需要说清的一点</h2>
 * {@link Life#advanceTo} 为每一个到点的项发一条 {@code plan.item-due.v1},
 * 那是一条 <b>刺激</b>, 不是一条指令。她可能正忙着别的事, 也可能在睡觉 ——
 * 于是"到点了"变成一条要 Mind 处理的信号, 而<b>她可以选择不理会</b>。
 *
 * <p>本类<b>刻意不</b>在触发后调用 {@code Life.begin(...)}。如果那样做,
 * 每一次到点都会无条件打断她手上的事 —— 那正是用户否定的那个实现
 * （"简单把当前计划 event 更新剩余时间然后立马执行一个计划 event"）。
 *
 * <h2>三种失败, 三种处置</h2>
 * <table border="1">
 *   <tr><th>失败</th><th>处置</th><th>为什么</th></tr>
 *   <tr>
 *     <td>上一次 tick 还没跑完, 这一次又来了</td>
 *     <td><b>跳过</b>这一次, 记 WARN 并计数</td>
 *     <td>排队是错的: 积压的 tick 会集中释放, 于是她会在同一秒里
 *         收到五六条"到点了"。而不跳过又会并发地改计划表。
 *         跳过的后果（这一 tick 的项晚一点被处理）可以由
 *         {@link PlanTrigger#lateness()} 如实记录下来 —— 数据不会丢</td>
 *   </tr>
 *   <tr>
 *     <td>推进过程中抛异常（比如某个事件处理器坏了）</td>
 *     <td>吞掉, 记 ERROR 并计数, <b>下一个 tick 照常</b></td>
 *     <td>她的一天不能因为一个坏处理器停下来。但失败必须计数:
 *         计数不为零意味着有一段时间的计划没有被处理, 而那段历史是空的</td>
 *   </tr>
 *   <tr>
 *     <td>时钟被要求倒流</td>
 *     <td><b>不吞, 直接抛出去</b></td>
 *     <td>这是编程错误, 不是运行期故障。吞掉它会让整个仿真在
 *         一个不可能的时间线上继续跑, 而产出的全部数据都不可信 ——
 *         那比崩溃坏得多（见 {@link SimulationClock#advanceTo}）</td>
 *   </tr>
 * </table>
 *
 * <h2>"她的 Mind 卡住了"要怎么被发现</h2>
 * 因为到点的项只触发一次（{@link PlanItem#dueAt} 要求 {@code PENDING}），
 * 一个停摆的 Mind 不会表现为"反复触发", 而表现为<b>沉默</b> ——
 * 她该做的事一直没做, 而没有任何东西在报警。
 *
 * <p>{@link #lateItems(Duration)} 就是为了让这件事有声:
 * 它是"时间窗已经过了却还没被结束的项"。这个数持续增长时,
 * 问题不在计划表, 而在处理它的那一侧。没有这个方法, 那种故障
 * 只会在事后翻数据时被偶然发现。
 *
 * <h2>和 {@code PlanScheduler} 的分工</h2>
 * {@code PlanScheduler} 刻意<b>没有</b> {@code nextDueAt()} 之类的预测接口
 * （理由见它的类注释: 会造出第二个时间驱动）。本类就是那个唯一的时间驱动,
 * 而它不预测, 只询问。
 *
 * <h2>装配</h2>
 * 本类<b>不含任何 Spring 注解</b>, 于是它能在测试里被 {@link #tickAt(Instant)}
 * 一步步推着走。生产环境的接线在配置层完成:
 * <pre>{@code
 * @Scheduled(fixedDelayString = "${companion.sim.tick-ms:1000}")
 * public void onTick() { planSchedulerJob.tick(); }
 * }</pre>
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}: 前者是"上一次跑完
 * 之后等这么久", 后者是"每这么久跑一次（可能并发）"。配 {@link #tick()}
 * 的跳过策略, 前者永远不会产生需要被跳过的 tick。
 */
@Slf4j
public class PlanSchedulerJob {

    /** 默认 tick 间隔。一秒一次 —— 与真实秒同速时, 这个精度对行为分析足够。 */
    public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);

    /**
     * 超过这个迟到量就要在日志里出声。
     *
     * <p>取 30 秒: 一个 tick 的迟到在这个量级上, 说明不是调度抖动,
     * 而是真的有什么东西卡住了。
     */
    public static final Duration NOTABLE_LATENESS = Duration.ofSeconds(30);

    private final SimulationClock clock;
    private final Life life;
    private final Duration tickInterval;

    /** 重入保护 —— 见类注释里"跳过而不是排队"那段。 */
    private final AtomicBoolean ticking = new AtomicBoolean();

    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong skippedTicks = new AtomicLong();
    private final AtomicLong triggersFired = new AtomicLong();
    private final AtomicLong lateTriggers = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile Instant lastTickAt;
    private volatile Duration worstLateness = Duration.ZERO;

    public PlanSchedulerJob(SimulationClock clock, Life life) {
        this(clock, life, DEFAULT_TICK_INTERVAL);
    }

    public PlanSchedulerJob(SimulationClock clock, Life life, Duration tickInterval) {
        this.clock = Objects.requireNonNull(clock, "调度任务必须有一个时间来源");
        this.life = Objects.requireNonNull(life, "调度任务必须知道在推动谁的一天");
        Objects.requireNonNull(tickInterval, "tick 间隔不能为空");
        if (tickInterval.isNegative() || tickInterval.isZero()) {
            throw new IllegalArgumentException(
                    "tick 间隔必须为正, 收到 " + tickInterval
                            + " —— 零间隔会让调度器空转烧 CPU, 而它不会让她的一天走得更快");
        }
        this.tickInterval = tickInterval;
    }

    // ─────────────────────────── 推动 ───────────────────────────

    /**
     * 走一个 tick: 读时钟, 把生活推进到现在。
     *
     * <p>这是生产环境唯一的入口（由 {@code @Scheduled} 调用）。
     */
    public TickResult tick() {
        return tick(clock.now());
    }

    /**
     * 把时间推到 {@code at} 再走一个 tick —— 测试与回放用。
     *
     * <p>实时模式下这会抛异常（时间由墙钟推, 不能被推）, 这是刻意的:
     * 一个"测试里能控制时间、生产里控制不了"的接口会让两条路径分叉。
     *
     * @throws IllegalArgumentException 时钟被要求倒流, 或实时模式下被要求跳到未来
     */
    public TickResult tickAt(Instant at) {
        Objects.requireNonNull(at, "推进目标不能为空");
        clock.advanceTo(at);
        return tick(at);
    }

    private TickResult tick(Instant at) {
        if (!ticking.compareAndSet(false, true)) {
            long n = skippedTicks.incrementAndGet();
            log.warn("[PlanSchedulerJob] 上一个 tick 还没跑完, 跳过 {} 这一次"
                            + "（累计跳过 {} 次）—— 排队是错的, 积压的 tick 会集中释放; "
                            + "被跳过的项会由 PlanTrigger.lateness 如实记下, 数据不会丢",
                    at, n);
            return TickResult.skipped(at, n);
        }
        try {
            List<PlanTrigger> fired = life.advanceTo(at);

            ticks.incrementAndGet();
            lastTickAt = at;
            if (!fired.isEmpty()) {
                triggersFired.addAndGet(fired.size());
                recordLateness(fired, at);
            }
            return TickResult.of(at, fired, worstLateness);

        } catch (RuntimeException e) {
            // 注意: 这里捕获的是"推进过程中的意外", 不包括时钟倒流 ——
            // 那个在 tickAt 里、进这个方法之前就已经抛出去了。
            failures.incrementAndGet();
            log.error("[PlanSchedulerJob] {} 推进失败（累计 {} 次）—— 她的一天照常继续, "
                            + "但这一段时间里的计划没有被处理, 那段历史是空的",
                    at, failures.get(), e);
            return TickResult.failed(at, e);

        } finally {
            ticking.set(false);
        }
    }

    private void recordLateness(List<PlanTrigger> fired, Instant at) {
        Duration worst = Duration.ZERO;
        int late = 0;
        for (PlanTrigger trigger : fired) {
            Duration lateness = trigger.lateness();
            if (!trigger.late()) {
                continue;
            }
            late++;
            if (lateness.compareTo(worst) > 0) {
                worst = lateness;
            }
        }
        if (late > 0) {
            lateTriggers.addAndGet(late);
            if (worst.compareTo(worstLateness) > 0) {
                worstLateness = worst;
            }
            // 迟到是行为数据（拖延的度量）, 但超过阈值的那种是故障信号 —— 两种都记,
            // 级别不同。见 PlanTrigger 关于"lateness 不是误差, 是拖延的度量"的说明
            if (worst.compareTo(NOTABLE_LATENESS) > 0) {
                log.warn("[PlanSchedulerJob] {} 有 {} 项迟到, 最大 {}ms —— "
                                + "这个量级不像 tick 抖动, 更像有什么卡住了",
                        at, late, worst.toMillis());
            } else {
                log.debug("[PlanSchedulerJob] {} 有 {} 项迟到, 最大 {}ms",
                        at, late, worst.toMillis());
            }
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public Duration tickInterval() {
        return tickInterval;
    }

    /** 成功推进过多少次。 */
    public long ticks() {
        return ticks.get();
    }

    public long skippedTicks() {
        return skippedTicks.get();
    }

    public long triggersFired() {
        return triggersFired.get();
    }

    /** 迟到过的触发项数（迟到本身不是故障, 见 {@link PlanTrigger#lateness()}）。 */
    public long lateTriggers() {
        return lateTriggers.get();
    }

    public long failures() {
        return failures.get();
    }

    public Instant lastTickAt() {
        return lastTickAt;
    }

    /** 历史上最大的一次迟到量。 */
    public Duration worstLateness() {
        return worstLateness;
    }

    /**
     * 已经过了时间窗却还没结束的项 —— <b>"她的 Mind 是不是卡住了"的那盏灯</b>。
     *
     * <p>见类注释最后一段。返回空列表是常态; 持续非空且增长时,
     * 需要查的不是计划表, 而是处理它的那一侧。
     */
    public List<PlanItem> lateItems(Instant now, Duration threshold) {
        Objects.requireNonNull(now, "要问'哪些项迟了'必须带时刻");
        Objects.requireNonNull(threshold, "迟到阈值不能为空");
        List<PlanItem> late = new ArrayList<>();
        for (PlanItem item : life.plan().current().items()) {
            if (item.overdueAt(now)) {
                Duration overdue = item.runningLateBy(now).orElse(Duration.ZERO);
                if (overdue.compareTo(threshold) >= 0) {
                    late.add(item);
                }
            }
        }
        return List.copyOf(late);
    }

    /** 用默认阈值（默认 tick 间隔的 10 倍）问一次。 */
    public List<PlanItem> lateItems(Instant now) {
        return lateItems(now, tickInterval.multipliedBy(10));
    }

    public String describe() {
        StringBuilder sb = new StringBuilder("[PlanSchedulerJob] ");
        sb.append("tick ").append(ticks.get()).append(" 次");
        if (skippedTicks.get() > 0) {
            sb.append(", 跳过 ").append(skippedTicks.get()).append(" 次");
        }
        sb.append(", 触发 ").append(triggersFired.get()).append(" 项");
        if (lateTriggers.get() > 0) {
            sb.append("（其中迟到 ").append(lateTriggers.get())
                    .append(", 最大 ").append(worstLateness.toMillis()).append("ms）");
        }
        if (failures.get() > 0) {
            sb.append(", 失败 ").append(failures.get()).append(" ← 需要处理");
        }
        sb.append(", 最后 ").append(lastTickAt == null ? "尚未跑过" : lastTickAt);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 一次 tick 的摘要 ───────────────────────────

    /**
     * 一次 tick 处置了什么。
     *
     * <p>返回值存在的理由与 {@link Life#advanceTo} 返回 {@code List} 而不是
     * {@code boolean} 是同一条: 调用方（监控、测试、回放分析）需要知道
     * "刚才到底触发了什么、迟了多久", 而不只是"成没成"。
     *
     * @param at            这一 tick 的仿真时刻
     * @param fired         触发了哪几项（{@link #skipped()} 为真时为空）
     * @param worstLateness 这一 tick 里最大的一次迟到量
     * @param skipped       是不是因为上一次还没跑完而被跳过的
     * @param skippedTotal  累计跳过了多少次（诊断抖动用）
     * @param error         推进失败时的异常消息, 正常为 {@code null}
     */
    public record TickResult(
            Instant at,
            List<PlanTrigger> fired,
            Duration worstLateness,
            boolean skipped,
            long skippedTotal,
            String error) {

        public TickResult {
            fired = fired == null ? List.of() : List.copyOf(fired);
        }

        static TickResult of(Instant at, List<PlanTrigger> fired, Duration worst) {
            return new TickResult(at, fired, worst, false, 0L, null);
        }

        static TickResult skipped(Instant at, long skippedTotal) {
            return new TickResult(at, List.of(), Duration.ZERO, true, skippedTotal, null);
        }

        static TickResult failed(Instant at, RuntimeException e) {
            return new TickResult(at, List.of(), Duration.ZERO, false, 0L,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        /** 这一 tick 有问题吗（被跳过, 或者推进失败）。 */
        public boolean troubled() {
            return skipped || error != null;
        }

        /** 这一 tick 有值得记录的迟到吗。 */
        public boolean late() {
            return worstLateness.compareTo(PlanTrigger.NEGLIGIBLE) > 0;
        }

        public String describe() {
            if (skipped) {
                return "tick@" + at + " 跳过（累计 " + skippedTotal + " 次）";
            }
            if (error != null) {
                return "tick@" + at + " 失败: " + error;
            }
            if (fired.isEmpty()) {
                return "tick@" + at + " 无事";
            }
            return "tick@" + at + " 触发 " + fired.size() + " 项"
                    + (late() ? "（最大迟到 " + worstLateness.toMillis() + "ms）" : "");
        }
    }
}
