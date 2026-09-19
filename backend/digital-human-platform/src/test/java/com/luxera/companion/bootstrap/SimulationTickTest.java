package com.luxera.companion.bootstrap;

import com.luxera.companion.runtime.EnvironmentRefreshJob;
import com.luxera.companion.runtime.SimulationClock;
import com.luxera.companion.runtime.WorldRuntime;
import com.luxera.companion.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimulationTick} 的测试。
 *
 * <h2>为什么这个类值得有自己的测试, 而它看起来只是两行接线</h2>
 * 因为它身上有一条<b>没有任何别的东西在守</b>的失效: {@code ScheduledExecutorService}
 * 的规矩是"任务抛出去的异常会<b>永久取消</b>这个任务"。于是任何一次瞬时故障
 * (一次恢复、一次并发写、一次 GC 之后的状态错乱)都会把"她的世界"从
 * <b>一次失败</b>变成<b>永久静止</b>。而这两种情况在日志里长得很像:
 * 都只有一条异常。区别只在"之后还有没有下一拍" —— 而那正是本类要钉住的东西。
 *
 * <p>第二个要钉的是 §8.6.5 那两个循环<b>必须各有一条线程</b>。它的反面
 * (共用一条)不会报错, 只会表现为"她的一天停了半分钟, 而日志里只有一条关于天气的 WARN"。
 * 那是一条极难归因的症状, 所以这里用一次真的会阻塞的取数去试它。
 *
 * <h2>哪一条测试证明了哪一个 catch —— 以及有一个没有被证明</h2>
 * 本类有两个 {@code try/catch}(心跳一个、环境刷新一个), 它们的形状一模一样。
 * 直接证明的只有<b>心跳那一个</b>({@link #心跳一直抛异常循环也一直在重试()}:
 * 关掉 runtime 之后每一次 pulse 都必抛)。环境刷新那一个<b>没有被直接证明</b> ——
 * 因为它的身体里能抛的两件事都够不着: {@code EnvironmentRefreshJob.pulse} 自己接住了
 * sink 的异常(那是它该做的, 它知道一次失败的取数意味着什么), 而
 * {@code applyRefresh} 在 runtime 已关时是静默跳过、不抛。
 *
 * <p>把这件事写在这里, 而不是想办法造一个假的抛出去: 一个为了证明 catch 而存在的
 * 假异常, 证明的是那个假异常, 不是接线。所以
 * {@link #取数源一直抛异常循环也一直在重试()} 证明的是<b>另一样同样要紧的东西</b> ——
 * "上游一直失败时这个循环一直在重试" —— 而刷新那个 catch 目前的地位是
 * "与已被证明的那一个同形"。这句话说得比"它也对"准确。
 *
 * <h2>关于时间</h2>
 * 本测试用真的线程与真的睡眠, 所以它<b>是</b>有时序的。所有断言都留了很大余量
 * (期望值与门槛差 2 倍以上), 因为一个偶尔红的守卫会被人加进忽略名单 ——
 * 而进了忽略名单的守卫等于不存在。宁可慢一点, 也不要抖。
 */
class SimulationTickTest {

    private static final Instant T = Instant.parse("2026-03-01T12:00:00Z");

    /** 一个会睡一会儿的取数源 —— 它模拟"天气 API 卡了 200 毫秒"。 */
    private static final class SlowSink implements EnvironmentRefreshJob.EnvironmentSink {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> threadName = new AtomicReference<>();
        volatile long sleepMillis = 200;
        volatile boolean shouldThrow = false;

        @Override
        public List<World.EnvironmentReading> fetchEnvironments(Instant now) {
            calls.incrementAndGet();
            // 记下第一条真的执行了这个方法的线程 —— 它就是"环境刷新循环"所在的那条。
            threadName.compareAndSet(null, Thread.currentThread().getName());
            if (shouldThrow) {
                throw new IllegalStateException("模拟: 天气源挂了");
            }
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /**
     * 一套装好的循环。
     *
     * <p>时钟用 {@code realtime} 而不是 {@code at(T)}: 环境刷新是按<b>边界</b>对齐的,
     * 而一个不前进的时钟永远落在同一个边界上, 于是第一轮之后每一轮都会被跳过 ——
     * 那样这个夹具就测不到"外呼"这件事了。
     */
    private static final class Rig implements AutoCloseable {
        final World world = new World();
        final SimulationClock clock = SimulationClock.realtime(T);
        final SlowSink sink = new SlowSink();
        final SimulationProperties properties = new SimulationProperties();
        final EnvironmentRefreshJob job;
        final WorldRuntime runtime;
        final SimulationTick tick;

        Rig(long tickMs, Duration environmentInterval) {
            this.properties.setTickMs(tickMs);
            this.properties.setEnvironmentInterval(environmentInterval);
            this.job = new EnvironmentRefreshJob(clock, sink, environmentInterval);
            this.runtime = new WorldRuntime(world, clock, job);
            this.tick = new SimulationTick(runtime, properties);
        }

        @Override
        public void close() {
            tick.stop();
            runtime.close();
        }
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────── 两个循环互不拖累 ───────────────────────

    @Test
    @DisplayName("一次慢的取数不耽误心跳 —— 这正是两条线程的理由")
    void 一次慢的取数不耽误心跳() {
        try (Rig rig = new Rig(20, Duration.ofMillis(20))) {
            rig.tick.start();
            await(1000);

            // 心跳 25 拍左右是期望值。门槛取 10: 若两个循环共用一条线程,
            // 每一轮 200ms 的外呼会把心跳压到 1 秒里最多 5 拍 —— 于是这个数
            // 在"共用"与"分开"两种接线下的差别是 2.5 倍, 而不是噪声级别的差别。
            assertTrue(rig.runtime.pulses() >= 10,
                    "心跳在 1 秒里只跳了 " + rig.runtime.pulses() + " 拍 —— "
                            + "若环境刷新与它共用一条线程, 一次 200ms 的外呼就会把她的时间压住, "
                            + "而那正是 §8.6.5 要防的那件事");

            assertTrue(rig.sink.calls.get() >= 1, "环境刷新一次都没跑起来");

            // 取数确实在另一条线程上 —— 名字前缀是它唯一能被看见的地方。
            assertTrue(rig.sink.threadName.get() != null && rig.sink.threadName.get().startsWith("sim-env"),
                    "环境刷新跑在 '" + rig.sink.threadName.get() + "' 上, 而不是它自己那条 sim-env 线程上");

            assertTrue(rig.runtime.refreshSkips() >= 1,
                    "空读数的刷新必须被计成跳过 —— 否则'还没接天气源'与'接了但什么都没取到'分不开");
        }
    }

    // ─────────────────────── 异常不许把循环掐死 ───────────────────────

    @Test
    @DisplayName("取数源一直抛异常, 循环也一直在重试")
    void 取数源一直抛异常循环也一直在重试() {
        try (Rig rig = new Rig(20, Duration.ofMillis(20))) {
            rig.sink.shouldThrow = true;
            rig.tick.start();
            await(600);

            // 关键不是"失败了一次", 而是"失败之后还有下一次"。
            // 一个只会失败的循环若在第一次之后停住, 这个数会停在 1 —— 而 1 看起来
            // 完全正常: 它就像"刚失败了一次, 还没到下一次"。区分这两者的只有次数。
            assertTrue(rig.sink.calls.get() >= 3,
                    "取数源只被调了 " + rig.sink.calls.get() + " 次 —— 少于 3 说明这个循环在第一次异常之后"
                            + "就再也没跑过(ScheduledExecutorService 抛异常即永久取消), "
                            + "而那意味着她的天气永远停在最初那一刻");

            // 这一条钉的是"接住它的那一层还活着": sink 抛的异常由 EnvironmentRefreshJob
            // 自己接住(它知道一次失败的取数意味着什么 —— 沿用旧快照), 而不是逃到这里。
            // 本类的 refreshFailures 记的是另一个更外面的类: 连 job 都抛了的那种(见下面那段说明)。
            assertTrue(rig.job.failures() >= 3,
                    "取数源连续失败, 而 EnvironmentRefreshJob 只记下 " + rig.job.failures()
                            + " 次失败 —— 这个数与调用次数对不上, 说明失败在某一层被吞掉了却没计数");

            // 心跳不受影响: 它与取数之间没有任何共享。
            assertTrue(rig.runtime.pulses() >= 10,
                    "取数挂掉不该影响心跳, 实际只跳了 " + rig.runtime.pulses() + " 拍");
        }
    }

    @Test
    @DisplayName("心跳一直抛异常, 循环也一直在重试")
    void 心跳一直抛异常循环也一直在重试() {
        try (Rig rig = new Rig(20, Duration.ofMillis(20))) {
            rig.runtime.close();   // 关掉之后 pulse 必然抛 IllegalStateException
            rig.tick.start();
            await(400);

            // 同一个道理, 只是这次抛的是心跳那条线程。它比上面那条更危险:
            // 心跳停了就是"她的世界静止了", 而那件事在业务层面没有任何别的信号。
            assertTrue(rig.tick.tickFailures() >= 3,
                    "心跳连续抛异常时只记下 " + rig.tick.tickFailures() + " 次 —— "
                            + "少于 3 说明心跳在第一次异常之后就停了, 而她的一天从此不再前进, "
                            + "日志里却只有一条异常");
        }
    }

    // ─────────────────────── 生命周期 ───────────────────────

    @Test
    @DisplayName("停止之后一拍都不再发生")
    void 停止之后一拍都不再发生() {
        try (Rig rig = new Rig(20, Duration.ofMillis(20))) {
            rig.tick.start();
            await(200);
            rig.tick.stop();

            long pulsesAtStop = rig.runtime.pulses();
            int sinkAtStop = rig.sink.calls.get();
            assertTrue(pulsesAtStop >= 3, "停止前应该已经跳了几拍, 实际 " + pulsesAtStop);

            await(200);

            assertEquals(pulsesAtStop, rig.runtime.pulses(),
                    "stop 之后心跳还在跳 —— 停机时这会打在一个已经开始销毁的 runtime 上");
            assertEquals(sinkAtStop, rig.sink.calls.get(), "stop 之后取数还在跑");

            assertFalse(rig.tick.isRunning());
            assertTrue(rig.tick.describe().contains("已停止"), rig.tick.describe());
        }
    }

    @Test
    @DisplayName("start 可以重复调, stop 也可以")
    void start与stop都是幂等的() {
        try (Rig rig = new Rig(20, Duration.ofMillis(20))) {
            rig.tick.start();
            rig.tick.start();       // 不该起第二条线程, 也不该抛
            await(150);
            assertTrue(rig.tick.isRunning());

            rig.tick.stop();
            rig.tick.stop();        // 同样不该抛
            assertFalse(rig.tick.isRunning());

            // 停完还能再起来 —— 每个循环用的都是线程池, 而不是一次性的线程。
            rig.tick.start();
            await(150);
            assertTrue(rig.tick.isRunning());
            assertTrue(rig.runtime.pulses() > 0);
        }
    }

    @Test
    @DisplayName("相位取最大值: 最后开始跑, 最先停下来")
    void 相位取最大值() {
        try (Rig rig = new Rig(1000, Duration.ofMinutes(10))) {
            assertEquals(Integer.MAX_VALUE, rig.tick.getPhase(),
                    "相位不是最大值 —— 它就会在 WorldRuntime 还没建好之前开始跑, "
                            + "或者在停机时继续往一条正在拆除的线程上塞活");
            assertTrue(rig.tick.isAutoStartup(), "它必须自己起来: 一个需要手工启动的心跳等于没有心跳");
            assertEquals(0, rig.tick.tickFailures());
            assertEquals(0, rig.tick.refreshFailures());
        }
    }
}
