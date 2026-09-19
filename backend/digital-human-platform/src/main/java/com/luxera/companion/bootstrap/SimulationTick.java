package com.luxera.companion.bootstrap;

import com.luxera.companion.runtime.EnvironmentRefreshJob;
import com.luxera.companion.runtime.WorldRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §8.6.5 —— <b>让时间真的开始走的那两个循环</b>。
 *
 * <h2>这里有两个循环, 而不是一个</h2>
 * <table border="1">
 *   <tr><th></th><th>心跳</th><th>环境刷新</th></tr>
 *   <tr><td>周期</td><td>{@code companion.sim.tick-ms}(缺省 1 秒)</td>
 *       <td>{@code companion.sim.environment-interval}(缺省 10 分钟)</td></tr>
 *   <tr><td>单次耗时</td><td>纳秒级(纯内存)</td><td><b>可以是 30 秒</b> —— 它做外呼</td></tr>
 *   <tr><td>线程</td><td>自己的一条</td><td>自己的一条</td></tr>
 * </table>
 *
 * <p>它们必须各有一条线程, 理由不是"更快", 而是<b>一条超时的外呼会把她的那一天停住</b>:
 * 默认的 {@code TaskScheduler} 池大小是 1, 而本仓的旧平台里已经有 ~19 个
 * {@code @Scheduled} 任务共用着那一根线程(见 {@code V11TurnSealJob} 的类注释) ——
 * 那个形状的后果是"她的一天停了半分钟, 而日志里只有一条关于天气的 WARN"。
 * 这一层不重复那个形状。
 *
 * <h2>为什么是 {@code scheduleWithFixedDelay} 而不是 {@code scheduleAtFixedRate}</h2>
 * {@code fixedRate} 以"上一次<b>开始</b>的时刻"为基准排下一次, 于是当一次 tick 超过了周期
 * (一次恢复、一次慢查询、一次 GC), 它会在之后<b>连着补跑几次</b>来追平 —— 而仿真时间
 * 不是这么走的。用户要的是"时间前进", 不是"补算"。补跑还会让
 * {@code WorldRuntime.failedPulses()} 与"她被跳过了多久"这类读面失真:
 * 它们本来是"时间真的欠下了"的证据, 补跑会把欠下的时间抹掉。
 * {@code fixedDelay} 以"上一次<b>结束</b>"为基准, 慢就是慢, <b>欠下的时间如实体现在
 * 仿真时钟上</b> —— 而那正是我们要她能"发现"的东西(迟到的计划项)。
 *
 * <h2>为什么不用 {@code @Scheduled}, 尽管 §8.6.5 写的是它</h2>
 * 那一版的写法是 {@code @Scheduled(fixedDelayString = "${companion.sim.tick-ms:1000}")},
 * 它有两个当时的写作者看不见的问题:
 * <ol>
 *   <li><b>{@code @Scheduled} 没法指定调度器。</b>本章程 2.7(Spring Framework 5.3)的
 *       {@code @Scheduled} 没有 {@code scheduler} 属性(那是 6.1 才有的), 于是"它有自己的
 *       调度器"这件事只能靠 {@code SchedulingConfigurer} 去改<b>全局默认</b>的那个 ——
 *       而那个默认调度器正被仓里 ~19 个旧任务共用。为了给两个新任务分线程而去动
 *       19 个旧任务脚下的东西, 是拿一个已知的收益去换一个未知的破坏。</li>
 *   <li><b>SpEL 会绕过 {@link SimulationProperties} 的校验。</b>那个类在
 *       {@code afterPropertiesSet} 里拒绝了 {@code tickMs <= 0} 与零/负的刷新间隔,
 *       而 {@code ${companion.sim.tick-ms:1000}} 是<b>第二次读同一个配置</b> ——
 *       一次读的是没校验过的值。§8.6.4 说过 {@code interval = 0} 是最危险的那个,
 *       因为它不抛异常: 它把"十分钟一次外呼"变成"每一拍一次外呼"。
 *       <b>一个值只能有一个读者</b>, 而那个读者必须是校验过的那个。</li>
 * </ol>
 * 语义上两者完全相同: {@code scheduleWithFixedDelay} 就是 {@code fixedDelay}。
 *
 * <h2>为什么每个循环都把异常吞掉 —— 这是本类唯一一处"吞"</h2>
 * 因为 {@code ScheduledExecutorService} 的规矩是: <b>一个抛出去的异常会永久取消
 * 这个任务</b>, 之后再也不会有下一次。于是"一次瞬时故障"会变成"她的世界从此静止",
 * 而日志里只有一条异常 —— 那条异常看起来像一次失败, 而不像一次终止。
 *
 * <p>所以每个循环各自 catch, 把计数加一, 然后<b>继续排下一拍</b>。
 * 关键是它不能安静: 每一条都会打 ERROR, 并且计数可以从这两条线程之外读到。
 */
@Slf4j
public final class SimulationTick implements SmartLifecycle {

    private final WorldRuntime runtime;
    private final SimulationProperties properties;

    /**
     * 两条线程的名字前缀 —— 它们是排查时唯一的线索。
     *
     * <p>一个 jstack 里如果只有 {@code pool-3-thread-1}, 读的人无从知道停住的是
     * 她的心跳还是天气; 而 {@code sim-tick} 与 {@code sim-env} 把这件事
     * 写在崩溃现场本身。
     */
    private static final String TICK_THREAD = "sim-tick";
    private static final String REFRESH_THREAD = "sim-env";

    private final AtomicLong tickFailures = new AtomicLong();
    private final AtomicLong refreshFailures = new AtomicLong();

    private volatile ThreadPoolTaskScheduler tickScheduler;
    private volatile ThreadPoolTaskScheduler refreshScheduler;
    private volatile boolean running;

    public SimulationTick(WorldRuntime runtime, SimulationProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    /**
     * 相位取 {@link Integer#MAX_VALUE}: 低相位先启动、<b>逆序停止</b>, 于是本类
     * 最后开始跑、最先停下来。
     *
     * <p>这个顺序是想要的: 开始得最晚 —— 因为到那时 {@code WorldRuntime} 才建好、
     * 座位表才填过; 停得最早 —— 因为一旦开始停机, 就不该再有新的心跳往一条
     * 正在拆除的线程上塞活。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        tickScheduler = newScheduler(TICK_THREAD);
        refreshScheduler = newScheduler(REFRESH_THREAD);

        Duration tickInterval = Duration.ofMillis(properties.getTickMs());
        tickScheduler.scheduleWithFixedDelay(this::pulseOnce, tickInterval);
        refreshScheduler.scheduleWithFixedDelay(this::refreshOnce, properties.getEnvironmentInterval());

        running = true;
        log.info("[Sim] 两个循环都起来了: 心跳每 {}ms 一拍(线程 {}), 环境刷新每 {}(线程 {})",
                properties.getTickMs(), TICK_THREAD, properties.getEnvironmentInterval(), REFRESH_THREAD);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // 先停心跳: 它一秒一拍, 是这两条里更容易撞上停机窗口的那条。
        shutdown(tickScheduler, TICK_THREAD);
        shutdown(refreshScheduler, REFRESH_THREAD);
        tickScheduler = null;
        refreshScheduler = null;
        log.info("[Sim] 两个循环都停了。累计失败: 心跳 {} 拍, 环境刷新 {} 轮", tickFailures.get(), refreshFailures.get());
    }

    // ─────────────────────────── 两拍 ───────────────────────────

    /**
     * 一拍心跳。
     *
     * <p>{@code clock.now()} 是唯一的时刻来源 —— 本类自己不读墙上时钟(§8.5.1)。
     * 这一行同时是"仿真时间可以倒流吗"这个问题的答案: 它不会, 因为时钟是单调的,
     * 而 {@code WorldRuntime.pulse} 会拒绝任何比上一拍早的时刻(那是编程错误, 不是数据)。
     */
    void pulseOnce() {
        try {
            runtime.pulse(runtime.clock().now());
        } catch (RuntimeException e) {
            long n = tickFailures.incrementAndGet();
            log.error("[Sim] 第 {} 拍失败(已经跳过这一拍, 下一拍照常): {}", n, e.toString(), e);
        }
    }

    /**
     * 一轮环境刷新 —— <b>取数与用数是分开的两步, 跨两条线程</b>(§8.5.9)。
     *
     * <p>{@code job.pulse(at)} 在<b>这条</b>线程上做外呼(可能 30 秒), 然后把读数
     * 交给 {@code runtime.applyRefresh(...)}, 由它转到仿真线程上去改世界。
     * 本类不做这个转交 —— 那是 {@code WorldRuntime} 的事, 因为"世界只有一个写者"
     * 这条不变式必须由一个知道全部写路径的地方来守。
     *
     * <p>这里传进去的 {@code at} 与 {@code pulse} 用的是<b>同一个</b> {@code clock.now()}。
     * 这一点是有讲究的: 刷新任务自己也要一个时刻来算对齐边界, 而如果让它自己去读时钟,
     * 就会存在两次读取(§8.6.3 的"一个值只能有一个读者"的另一种形态) ——
     * 于是"这一批读数属于哪个时刻"会有两个可能答案。
     */
    void refreshOnce() {
        try {
            Instant at = runtime.clock().now();
            EnvironmentRefreshJob.PulseResult result = runtime.environmentRefreshJob().pulse(at);
            runtime.applyRefresh(result, at);
        } catch (RuntimeException e) {
            long n = refreshFailures.incrementAndGet();
            log.error("[Sim] 第 {} 轮环境刷新失败(这一轮作废, 下一轮照常): {}", n, e.toString(), e);
        }
    }

    // ─────────────────────────── 读面 ───────────────────────────

    /** 累计失败的心跳拍数 —— 与 {@code WorldRuntime.failedPulses()} 不是同一个数, 见类注释。 */
    public long tickFailures() {
        return tickFailures.get();
    }

    /** 累计失败的环境刷新轮数。 */
    public long refreshFailures() {
        return refreshFailures.get();
    }

    /** 两个循环此刻在不在跑。 */
    public String describe() {
        return "[Sim] 循环: " + (running ? "运行中" : "已停止")
                + "(心跳失败 " + tickFailures.get() + " 拍 / 环境刷新失败 " + refreshFailures.get() + " 轮)";
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 一条专属线程。
     *
     * <p>{@code setDaemon(true)}: 这两个循环<b>不该</b>拖住 JVM 的退出。它们的作用是
     * 在她活着的时候推进时间, 而不是阻止进程结束 —— 一个非守护线程会让
     * "按了 Ctrl-C 却停不下来"变成一次真的需要 {@code kill -9} 的事故。
     *
     * <p>{@code setRemoveOnCancelPolicy(true)}: 取消掉的任务要从队列里真的消失。
     * 少了它, 反复启停会看到队列里堆积着一串再也不会执行的壳 ——
     * 它们不影响正确性, 但会让"到底有几个任务在等"这个问题的答案永远是错的。
     */
    private static ThreadPoolTaskScheduler newScheduler(String namePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(namePrefix + "-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.error("[Sim] {} 上有一个预料之外的异常逃到了调度器", namePrefix, t));
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 停掉一条线程, <b>并等它真的停</b>。
     *
     * <p>等这一步是有代价的(它可能等满一秒), 而不等的代价更大: 不等的话,
     * 停机之后的某个瞬间还会有一拍心跳打在已经开始销毁的 bean 上 ——
     * 那时它抛的异常会出现在一个与它毫无关系的堆栈里。
     */
    private static void shutdown(ThreadPoolTaskScheduler scheduler, String namePrefix) {
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.shutdown();
        } catch (RuntimeException e) {
            log.warn("[Sim] 停 {} 时出错(继续停另一条): {}", namePrefix, e.toString());
        }
    }
}
