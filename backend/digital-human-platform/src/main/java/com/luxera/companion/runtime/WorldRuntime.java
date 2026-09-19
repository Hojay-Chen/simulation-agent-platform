package com.luxera.companion.runtime;

import com.luxera.companion.world.World;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * V2.2 §8.5.1 / §8.5.2 —— <b>唯一的心跳</b>, 以及那张座位表。
 *
 * <h2>它做的唯一一件事</h2>
 * 把 §8.5.1 那四步按那个顺序跑一遍, 一秒一次。除此之外它什么也不做。
 *
 * <pre>
 * WorldRuntime.pulse(now)
 *   Instant now = clock.now();            ← 一次心跳只读一次时刻
 *   ① 每个座位: planSchedulerJob.tickAt(now)      "她打算做什么"
 *   ② world.advanceDevices(elapsed, now)          "设备动了"
 *   ③ world.applyEnvironments(readings, now)      "外面变了"  ← 不在本方法里, 见下
 *   ④ 每个座位: humanActor.pulse(now)              "她知道了"
 * </pre>
 *
 * <h2>为什么 ③ 不在 {@link #pulse(Instant)} 里面</h2>
 * 因为用户/天气源那一次外呼<b>不许在心跳线程上</b>(§8.5.0: 一次 30 秒的天气超时
 * 会把她的一天停半分钟), 而"改 World 的只有一条线程"(§8.5.9)。
 * 两条约束只有一个交集: <b>取在刷新线程上, 用在仿真线程上。</b>
 * 所以 ③ 的入口是 {@link #applyRefresh} —— 它由环境刷新的那条线程调用,
 * 内部把"应用"提交到仿真线程。
 *
 * <p>代价要说清楚: §8.5.1 那张图里 ③ 在 ② 与 ④ 之间, 而在真实的线程上它落在
 * <b>哪里都有可能</b> —— 它是一次异步提交。逻辑顺序("先设备、后环境")在
 * {@link World#advance} 那条离线路径上仍然是严格的; 而在实时路径上,
 * 一次环境应用与一次心跳的相对次序不做保证。这件事不会让她看到因果倒置的事件
 * (环境事件的 {@code occurredAt} 是取数时刻, 由 {@code World} 记), 但"这个 tick
 * 里天气变了"这句话只在多数 tick 上为真。
 *
 * <h2>一条线程, 而且没有第二条路能进去</h2>
 * 本类持有一条<b>单线程</b>执行器, World 与 Human 的一切改动都从它上面过
 * ({@link #onSimulationThread} 入队并等待, {@link #submit} 入队不等)。
 * 这不是性能取舍, 是 {@code World} 的类注释里那条"刻意不加锁"的前提
 * —— 不加锁的前提是没人跟它抢。
 *
 * <p><b>为什么 {@code pulse} 自己入队, 而不是让调用方写 {@code submit(runtime::pulse)}。</b>
 * 后者是一条只写在别人脑子里的约定: 忘掉它不会报错, 只会让心跳在调度器线程上跑,
 * 于是它与刷新线程之外的一切并发。这里把它做成唯一的入口 ——
 * {@code pulse} 从调用方看是同步的(它等这一次跑完), 于是测试仍然可以一步一步
 * 推着它走, 不需要 sleep。
 *
 * <h2>座位表: 一个 Human 的 actor 与它的调度器必须一起登记</h2>
 * {@link #bind} 一次登记三样东西 —— actor、它那个 {@code PlanSchedulerJob}、
 * 以及由此推出的 humanId。<b>刻意不提供"分别登记"的入口。</b>
 *
 * <p>理由与 §8.6.3 里那张"两张 {@code PlanBoard}"是同一个:
 * {@code HumanActor} 与 {@code PlanSchedulerJob} 都必须与同一个 Human 一一对应,
 * 而它们是两个对象。若允许分两次登记, 就会出现"actor 登记了、调度器忘了"的样子 ——
 * 症状是她的计划表永远不走到点, 而心跳日志一切正常。一个 {@link Seat} 让这种状态
 * <b>无法被表达</b>。
 *
 * <p>登记顺序被刻意保留({@link LinkedHashMap}): 心跳 ① 与 ④ 遍历同一个顺序,
 * 于是"先问她打算做什么, 再让世界动"这条时序在<b>人与人之间</b>也是稳定的 ——
 * 否则同一次心跳里, 两个 agent 的 ①④ 交错次序每次都不同, 而那种不确定性
 * 在排查"她为什么先看到这个"时是纯噪音。
 *
 * <h2>它不许做的</h2>
 * <ul>
 *   <li><b>不许碰 Human 的内部。</b>它只知道 {@code HumanActor.pulse(now)} 这一个方法。
 *       它不知道她在想什么、在做什么 —— 那是 §3.4.2 边界在运行时侧的落点;</li>
 *   <li><b>不许读墙钟。</b>时刻只有一个来源({@code SimulationClock}), 而它连
 *       {@code clock.now()} 都只在一处调用(见 {@link #pulse(Instant)} 的说明);</li>
 *   <li><b>不许在心跳里做外呼。</b>见上面 ③ 那一段;</li>
 *   <li><b>不许 drain 刺激队列。</b>那不是"多看一次", 而是把刺激从她手里抢走
 *       ({@code RealtimeEventQueue.poll} 是取出即消费) —— 症状是"手机响了, 但她
 *       什么反应都没有", 且两个出队点各自都认为自己正常。本类连
 *       {@code EventFabric} 都不碰。</li>
 * </ul>
 *
 * <h2>PAUSED 的处理点在哪</h2>
 * <b>不在本类。</b>{@link HumanActor#pulse(Instant)} 自己看运行档并直接返回
 * (§8.5.4), 所以本类在 ④ 里不做任何筛选 —— 它照样调她, 由她决定跑不跑。
 *
 * <p>这一条是刻意的, 而且是为了不出现"两处都在判断运行档"。本类若也筛一遍,
 * 那么运行档就有了<b>两个读者</b>, 而它们会在"她被恢复的那一刻"给出不同答案
 * (一个刚从 {@code AgentRegistry} 拿到新值, 另一个还是上一拍的)。一个判断,
 * 一个位置。
 *
 * <p>同理, 本类<b>没有</b> §8.5.2 那张草图里的 {@code Map<String, AgentLifecycle>}:
 * 运行档住在 {@code HumanActor} 里, 本类只<b>写</b>它({@link #applyLifecycle}),
 * 从不另存一份。两份"谁在跑"与那两张 {@code PlanBoard} 是同一类错误。
 */
@Slf4j
public final class WorldRuntime implements AutoCloseable {

    /** 仿真线程的名字 —— 出问题时 {@code jstack} 里能一眼认出是谁。 */
    private static final String THREAD_NAME = "sim-tick";

    /**
     * 一次 {@link #close()} 最多等多久。
     *
     * <p>给得这么短是因为它要等的那件事是"当前这一拍跑完", 而一拍是纳秒到毫秒级。
     * 等不到就说明有东西卡住了(比如某个 handler 在做外呼) —— 那时<b>不该继续等</b>:
     * 关闭流程被一个卡住的 tick 挡住, 会连带把 Spring 上下文的停机一起挡住,
     * 而运维看到的是"服务停不下来", 与真凶(一次外呼)隔了三层。
     */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final World world;
    private final SimulationClock clock;
    private final EnvironmentRefreshJob environmentRefreshJob;

    /** humanId → 座位。登记顺序即心跳遍历顺序, 见类注释。 */
    private final Map<String, Seat> seats = new LinkedHashMap<>();

    private final ExecutorService simulationThread;

    /** 最后一次心跳的时刻 —— 环境应用的时刻、以及 {@code elapsed} 的起点。 */
    private volatile Instant lastInstant;

    private final AtomicLong pulses = new AtomicLong();
    private final AtomicLong failedPulses = new AtomicLong();
    private final AtomicLong refreshApplications = new AtomicLong();
    private final AtomicLong refreshSkips = new AtomicLong();

    /** {@code close()} 之后再来一次心跳就是编程错误, 所以它要能被判出来。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorldRuntime(World world, SimulationClock clock,
                        EnvironmentRefreshJob environmentRefreshJob) {
        this.world = Objects.requireNonNull(world, "运行时必须有一个 World");
        this.clock = Objects.requireNonNull(clock, "运行时必须有一个时钟 —— 它是唯一的时刻来源");
        this.environmentRefreshJob = Objects.requireNonNull(environmentRefreshJob,
                "运行时必须有一个环境刷新任务 —— 少了它, 世界从启动起就停在初始天气上, "
                        + "而她与日志都不会说这件事");
        this.simulationThread = Executors.newSingleThreadExecutor(namedDaemonFactory());
    }

    private static ThreadFactory namedDaemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            // 守护线程: 一个忘记 close 的上下文不该把 JVM 吊住。停机路径本来就走 close(),
            // 这一条只是"最后一道"。
            thread.setDaemon(true);
            return thread;
        };
    }

    // ─────────────────────────── 座位表 ───────────────────────────

    /**
     * 一个 seat —— 一个 Human 在心跳里的全部足迹。
     *
     * <p>两个引用<b>必须</b>指向同一个 Human: actor 是它的执行体, scheduler 是它
     * 那张计划表的驱动器。见类注释"座位表"那一段。
     */
    private record Seat(HumanActor actor, PlanSchedulerJob scheduler) {
    }

    /**
     * 把一个 Human 接进心跳, 同时把它的 {@code EventFabric} 接到世界上。
     *
     * <p>两件事在一个方法里做完是刻意的: 只做前者她会收不到世界的事件(她还是能跑,
     * 只是听不见世界); 只做后者世界会往一个没人处理的门里投递。两者都不是异常,
     * 都是沉默。
     *
     * @param actor     她的执行体
     * @param scheduler 她那张计划表的调度器 —— 必须是同一个 Human 的
     * @throws IllegalArgumentException 已经登记过这个 humanId
     */
    public void bind(HumanActor actor, PlanSchedulerJob scheduler) {
        Objects.requireNonNull(actor, "座位必须有一个 actor");
        Objects.requireNonNull(scheduler, "座位必须有一个计划表调度器");
        requireOpen();

        String humanId = actor.humanId().value();
        Seat previous = seats.putIfAbsent(humanId, new Seat(actor, scheduler));
        if (previous != null) {
            throw new IllegalArgumentException("humanId " + humanId + " 已经在座位表里了 —— "
                    + "重复登记会让第二次的 actor 覆盖第一次的, 而第一次那个的生命周期、"
                    + "计数、以及它已经收到的刺激全部静默丢失。装配层每个 Human 只该 bind 一次");
        }
        // 世界的座位表与这里的必须一致: 哪一边少了, 投递就断在哪一边。
        onSimulationThread(() -> {
            world.bind(humanId, actor.fabric());
            return null;
        });
        log.info("座位已登记: {} (计划表调度器 {}, 心跳共 {} 个座位)",
                humanId, scheduler.describe(), seats.size());
    }

    /** 把她的运行档写进去 —— 唯一的写入口, 见类注释。 */
    public void applyLifecycle(String humanId, AgentLifecycle lifecycle) {
        Seat seat = requireSeat(humanId);
        AgentLifecycle previous = seat.actor().lifecycle();
        seat.actor().setLifecycle(lifecycle);
        if (previous != lifecycle) {
            log.info("运行档变化: {} {} → {}{}", humanId, previous.wire(), lifecycle.wire(),
                    lifecycle.isPaused()
                            ? " —— 她不再被 pulse: 刺激会积压, 而这正是'她不看'(§3.6.6)"
                            : "");
        }
    }

    /**
     * 把她挪到一个地点, <b>并立即刷新那个地点的环境</b>。
     *
     * <p>"立即刷新"这一件事在 {@code World.placeHumanAt} 里 —— 她进门那一刻的天气
     * 永远是对的, 而不是十分钟后才有。本方法只负责把它排到仿真线程上。
     */
    public void placeHumanAt(String humanId, String placeId, Instant now, String reason) {
        requireSeat(humanId);
        onSimulationThread(() -> {
            world.placeHumanAt(humanId, placeId, now, reason);
            return null;
        });
    }

    private Seat requireSeat(String humanId) {
        Seat seat = seats.get(humanId);
        if (seat == null) {
            throw new IllegalArgumentException("座位表里没有 " + humanId + " —— 已登记的是 "
                    + seats.keySet() + "。装配层必须先 bind 再操作她");
        }
        return seat;
    }

    // ─────────────────────────── 心跳 ───────────────────────────

    /**
     * §8.5.1 的那四步。
     *
     * <h2>为什么 {@code now} 是参数, 而本方法<b>不</b>自己去读时钟</h2>
     * 因为"一次心跳只读一次时刻"这条约束的落点是<b>调用方</b>:
     * {@code SimulationTick} 读一次 {@code clock.now()}, 然后把它交给这里,
     * 于是 ①②④ 以及 {@code HumanActor} 内部用的都是同一个时刻。
     * 若本方法自己读, 那么"调用方也读一次"就成了必然 —— 两次读会跨越秒边界,
     * 而 {@code ContinuousEffectLedger} 的结算依赖时刻, 两个不同的时刻会让账本
     * 算出一个她从未有过的值, 且它印在一行看起来很正常的日志里。
     *
     * <p>本方法从调用方看是<b>同步</b>的(入队并等它跑完), 见类注释。
     *
     * @throws IllegalArgumentException {@code now} 早于上一次心跳 —— 时钟倒流是
     *         编程错误, 不吞({@code PlanSchedulerJob} 与 {@code HumanActor} 同一条)
     */
    public void pulse(Instant now) {
        Objects.requireNonNull(now, "心跳必须带一个时刻 —— 墙钟不是来源");
        requireOpen();
        onSimulationThread(() -> {
            pulseNow(now);
            return null;
        });
    }

    private void pulseNow(Instant now) {
        Instant previous = lastInstant;
        if (previous != null && now.isBefore(previous)) {
            throw new IllegalArgumentException("时钟倒流: 上一次心跳在 " + previous
                    + ", 这一次在 " + now + " —— 仿真时刻只能向前。"
                    + "这个错误不吞: 倒流之后账本会按一个更早的时刻结算, 而她身上的影响"
                    + "会凭空变强, 且没有任何事件解释这个变化");
        }
        Duration elapsed = previous == null ? Duration.ZERO : Duration.between(previous, now);

        try {
            // ① 她打算做什么 —— 到点的项发出一条 plan.item-due.v1, 那是一条<刺激>,
            //    不是一条命令(§8.5.5)。放在 ②③④ 之前, 于是同一个 tick 的 ④ 里
            //    她先意识到"该写作业了", 再听到手机响。
            for (Seat seat : seats.values()) {
                seat.scheduler().tickAt(now);
            }

            // ② 设备演化 —— 纯内存, 不含环境。事件同步投进各自主人的 EventFabric。
            World.AdvanceReport devices = world.advanceDevices(elapsed, now);

            // ③ 环境: 不在这一拍里 —— 由刷新线程提交, 见类注释。

            // ④ 她知道了。遍历顺序与 ① 相同(见类注释的座位表那一段)。
            //    运行档由 HumanActor 自己看, 本类不筛(见类注释的 PAUSED 那一段)。
            for (Seat seat : seats.values()) {
                seat.actor().pulse(now);
            }

            lastInstant = now;
            pulses.incrementAndGet();

            if (devices.hasFailures()) {
                // 设备故障不该静默: 一个坏掉的闹钟在她那边表现为"世界少了一件事",
                // 而那是无法从她的行为里反推出来的。
                log.warn("设备演化有失败: {}", devices.describe());
            }
        } catch (RuntimeException e) {
            // 一次心跳抛异常不该让整条仿真线程死掉 —— 那会让"她永远停在那一刻",
            // 而日志里最后一行是那个异常, 看起来像一次孤立的错误。
            failedPulses.incrementAndGet();
            log.error("心跳失败(已跳过这一拍, 仿真继续): {}", now, e);
        }
    }

    // ─────────────────────── 环境: 取在别处, 用在这里 ───────────────────────

    /**
     * 处理一轮环境刷新的结果 —— <b>由刷新线程调用</b>(§8.5.9)。
     *
     * <p>这个方法存在的理由是"让它不可能被写错"。装配层的刷新壳只需要写:
     * <pre>{@code
     * Instant at = runtime.clock().now();
     * runtime.applyRefresh(environmentRefreshJob.pulse(at), at);
     * }</pre>
     * 而"取回来的读数要真的被应用"这件事在<b>这里</b>被保证, 不在那个壳里。
     * 若把它留给壳(即让壳自己判断 {@code hasReadings()} 再 {@code submit}),
     * 那么忘掉那一步的症状是<b>天气永远不变</b>而日志一切正常 ——
     * 那正是 §8.5.11 那一族缺口的形状, 而这一族已经有两条了。
     *
     * @param result {@code EnvironmentRefreshJob.pulse(...)} 的结果
     * @param at     这一轮取数所用的时刻 —— 应用时用的是<b>同一个</b>时刻,
     *               于是"读数"与"世界被告知这件事发生的时刻"不会差出一个边界
     */
    public void applyRefresh(EnvironmentRefreshJob.PulseResult result, Instant at) {
        Objects.requireNonNull(result, "刷新结果不能为空");
        Objects.requireNonNull(at, "刷新必须带时刻");
        if (closed.get()) {
            // 停机中: 这一轮读数丢掉是对的(世界正在被关闭), 但要说一声 ——
            // 否则"最后一轮天气没进去"会变成一个只有对着日志数才会发现的空洞。
            refreshSkips.incrementAndGet();
            log.debug("运行时已关闭, 跳过这一轮环境应用: {}", result.describe());
            return;
        }
        if (!result.hasReadings()) {
            // 每个地点都没人是<b>正常结果</b>(见 EnvironmentSink 的注释), 不是故障。
            refreshSkips.incrementAndGet();
            return;
        }
        List<World.EnvironmentReading> readings = result.readings();
        submit(() -> {
            World.EnvironmentReport report = world.applyEnvironments(readings, at);
            refreshApplications.incrementAndGet();
            if (report.hasFailures()) {
                log.warn("环境应用有失败: {}", report.describe());
            }
        });
    }

    // ─────────────────────── 线程纪律 ───────────────────────

    /**
     * 在仿真线程上跑一件事, <b>并等它跑完</b>, 把结果带回来。
     *
     * <p>本类自己的一切改动都走这一条。外部若要在她的世界里做点什么(装配层的
     * 初始摆放、运维面的手动干预), 也应该走它 —— 而不是直接调 {@code World}。
     */
    public <T> T onSimulationThread(Supplier<T> work) {
        Objects.requireNonNull(work, "要跑的事不能为空");
        try {
            return simulationThread.submit(work::get).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待仿真线程时被打断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("仿真线程上的任务失败", cause);
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("运行时已经关闭, 不能再提交任务", e);
        }
    }

    /**
     * 在仿真线程上跑一件事, <b>不等</b>。
     *
     * <p>只有环境刷新那条线程用它 —— 它提交完就可以回去等下一个边界了,
     * 而"等"会让一次心跳的耗时把刷新线程也拖住。心跳自己用的是
     * {@link #onSimulationThread}(它必须等, 否则测试没法一步步推)。
     */
    public void submit(Runnable work) {
        Objects.requireNonNull(work, "要跑的事不能为空");
        try {
            simulationThread.execute(work);
        } catch (RejectedExecutionException e) {
            refreshSkips.incrementAndGet();
            log.debug("运行时已经关闭, 这一件事没有提交", e);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("运行时已经关闭 —— 再 pulse 一次会往一条不再"
                    + "执行的队列里塞任务, 而它看起来是成功的");
        }
    }

    /**
     * 停掉仿真线程。
     *
     * <p><b>顺序是刻意的: 先拒绝新工作, 再等当前那一拍跑完。</b>反过来(先等再拒)
     * 会让"等待"永远等不到 —— 队列里还有人在塞。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        simulationThread.shutdown();
        try {
            if (!simulationThread.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("仿真线程在 {} 内没有停下, 强制中断 —— 这通常意味着某处 handler "
                                + "在做外呼(§8.5.0 禁止的那件事), 或者一个 tick 卡住了",
                        SHUTDOWN_GRACE);
                simulationThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            simulationThread.shutdownNow();
        }
        log.info("运行时已停止: {}", describe());
    }

    // ─────────────────────────── 读面 ───────────────────────────

    public World world() {
        return world;
    }

    public SimulationClock clock() {
        return clock;
    }

    public EnvironmentRefreshJob environmentRefreshJob() {
        return environmentRefreshJob;
    }

    /**
     * 最后一次心跳的时刻 —— 环境应用用的就是它。
     *
     * <p>启动之后、第一次心跳之前是空的: "她还没被推进过"与"她停在某个时刻"
     * 是两件事, 用一个 {@code null} 而不是某个哨兵时刻来表示, 是因为哨兵都会被
     * 当成真的时刻用一次。
     */
    public Optional<Instant> lastInstant() {
        return Optional.ofNullable(lastInstant);
    }

    public List<String> humanIds() {
        return List.copyOf(seats.keySet());
    }

    public Optional<HumanActor> actor(String humanId) {
        Seat seat = seats.get(humanId);
        return seat == null ? Optional.empty() : Optional.of(seat.actor());
    }

    public int seatCount() {
        return seats.size();
    }

    public long pulses() {
        return pulses.get();
    }

    public long failedPulses() {
        return failedPulses.get();
    }

    public long refreshApplications() {
        return refreshApplications.get();
    }

    /**
     * 被跳过的环境应用次数 —— "取回来了但没用上"的次数。
     *
     * <p>它与 {@code World.failedRefreshCount} 是两件事: 那是"取不到",
     * 这是"取到了却没进世界"。后者非零时, 第一条该看的就是它是不是在停机中累加的
     * (那是正常的), 否则说明刷新壳压根没调 {@link #applyRefresh}。
     */
    public long refreshSkips() {
        return refreshSkips.get();
    }

    /** 一行摘要 —— 运维面与 §8.6.7 的启动日志都用它。 */
    public String describe() {
        List<String> paused = new ArrayList<>();
        for (Map.Entry<String, Seat> entry : seats.entrySet()) {
            if (entry.getValue().actor().lifecycle().isPaused()) {
                paused.add(entry.getKey());
            }
        }
        return "运行时: " + seats.size() + " 个座位"
                + (paused.isEmpty() ? "" : ", 暂停中 " + paused)
                + ", 心跳 " + pulses.get() + " 次"
                + (failedPulses.get() > 0 ? ", 失败 " + failedPulses.get() + " 次" : "")
                + ", 环境应用 " + refreshApplications.get() + " 次"
                + (refreshSkips.get() > 0 ? ", 跳过 " + refreshSkips.get() + " 次" : "")
                + ", 世界 " + world.describe();
    }

    @Override
    public String toString() {
        return "WorldRuntime{" + seats.size() + " 个座位, 心跳 " + pulses.get()
                + ", lastInstant=" + lastInstant + '}';
    }
}
