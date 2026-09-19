package com.luxera.companion.runtime;

import com.luxera.companion.runtime.EnvironmentRefreshJob.PulseResult;
import com.luxera.companion.runtime.EnvironmentRefreshJob.PulseResult.Outcome;
import com.luxera.companion.world.World;
import com.luxera.companion.world.environment.Environment.EnvironmentRefresh;
import com.luxera.companion.world.environment.EnvironmentSnapshot;
import com.luxera.companion.world.object.ObjectId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentRefreshJob} —— <b>边界对齐、失败处置、重入、以及"只取不用"</b>
 * 这几件事的测试。
 *
 * <h2>为什么用 {@code SimulationClock.at(...)} 而不是 {@code realtime(...)}</h2>
 * 因为本类的全部行为都是"时刻的函数": 同一个边界内取几次、跨过边界之后取不取、
 * 失败之后下一个边界是什么时候。用一个会自己流动的时钟测它, 只能写成
 * "结果应当落在某个范围里" —— 而那种断言恰好放过了本类最需要被钉住的那几条
 * (见 §8.5.0: 这个 job 存在的理由就是时刻要对齐)。步进模式下这些断言全是等号。
 *
 * <p>本测试里 <b>一次真实时钟读取都没有</b>: 每个 {@code now} 都是显式传进去的,
 * 这正是那个 job 的入口只收 {@code Instant} 的原因。
 *
 * <h2>§8.5.9 之后这一层能测什么、不能测什么</h2>
 * 这里测的是<b>"取"这一侧</b>: 取了几轮、取回几份读数、失败几份、边界记在哪。
 * "读数被应用之后世界变成了什么样"<b>不在这里</b> —— 那需要一条仿真线程和一个真的
 * {@code World}(而且 {@code apply} 是另一条线程上的另一个调用), 它由
 * {@code world.WorldAdvanceEquivalenceTest} 负责(§8.5.10)。
 * 这个分工是刻意的: 本测试的 sink 是一个 lambda(不装配世界), 于是失败、重入、
 * 边界这三条最容易写错的路径都能被单独钉住。
 */
class EnvironmentRefreshJobTest {

    /** 仿真起点 —— {@code SimulationClock.floorTo} 的对齐基准就是它。 */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    // ─────────────────────────── 测试替身 ───────────────────────────

    /**
     * 一份"取回来了"的读数。
     *
     * <p>这里用的是 {@link EnvironmentRefresh} 的<b>规范构造器</b>, 而不是那个
     * {@code EnvironmentRefresh.of(...)} 工厂: 那个工厂是
     * {@code world.environment} 的包级私有, 而本测试住在 {@code runtime} 包 ——
     * 它有意住在这里(这是 job 的测试, 不是环境自己的测试)。
     * 规范构造器是公开的, 而它多出来的两个参数(提供方 id 与时刻)正好是这份读数
     * 该有的东西。
     */
    private static World.EnvironmentReading reading(int index, Instant now) {
        EnvironmentSnapshot snapshot = EnvironmentSnapshot.mild(20.0 + index, now);
        return new World.EnvironmentReading(
                ObjectId.of("world.environment", "test-env-" + index),
                new EnvironmentRefresh(true, snapshot, List.of(), null, "test-provider", now));
    }

    /** 一份"取回来但是失败的"读数 —— 提供方没给数据, 快照仍是旧的那一份。 */
    private static World.EnvironmentReading failedReading(int index, Instant now) {
        EnvironmentSnapshot unchanged = EnvironmentSnapshot.mild(20.0 + index, now);
        return new World.EnvironmentReading(
                ObjectId.of("world.environment", "test-env-" + index),
                new EnvironmentRefresh(false, unchanged, List.of(),
                        "提供方超时", "test-provider", now));
    }

    /** 一批"成功的读数"。 */
    private static List<World.EnvironmentReading> ok(Instant now) {
        return List.of(reading(0, now), reading(1, now));
    }

    // ─────────────────── ① 同一个边界内多次 pulse 只取一次 ───────────────────

    @Test
    void manyPulsesInsideOneBoundaryFetchOnlyOnce() {
        SimulationClock clock = SimulationClock.at(T0);
        List<Instant> calls = new ArrayList<>();
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            calls.add(now);
            return ok(now);
        });

        // 从边界正上方到边界结束, 每 60 秒问一次 —— 十次问, 一次真的取
        PulseResult first = job.pulse(T0);
        assertTrue(first.fetched());
        assertEquals(T0, first.boundary());
        assertEquals(2, first.readings().size(), "取回来的读数要原样交给调用方");

        for (int second = 60; second < 600; second += 60) {
            PulseResult again = job.pulse(T0.plusSeconds(second));
            assertEquals(Outcome.IDLE, again.outcome(),
                    "同一边界内的第 " + (second / 60 + 1) + " 次 pulse 不该再取");
            assertTrue(again.readings().isEmpty(),
                    "没取就没有读数 —— 而且绝不能是 null(调用方会因为一个空指针炸掉)");
        }

        assertEquals(1, calls.size(), "sink 只该被叫一次");
        assertEquals(1, job.fetches());
        assertEquals(2, job.fetchedReadings(), "累计取回的量 —— 它是交接量, 不是结果量");
        assertEquals(0, job.failedReadings());
        assertEquals(0, job.skipped(), "同一边界内的重复 pulse 不是重入, 不该计入跳过");
        assertEquals(T0, job.lastFetchedBoundary());
        // "上一次尝试"停在真的取过的那一刻 —— 边界内的空转不是一次尝试
        assertEquals(T0, job.lastAttemptAt());
    }

    // ─────────────────── ② 跨过边界后取数 ───────────────────

    @Test
    void crossingTheBoundaryFetchesAgain() {
        SimulationClock clock = SimulationClock.at(T0);
        List<Instant> calls = new ArrayList<>();
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            calls.add(now);
            return ok(now);
        });

        job.pulse(T0.plusSeconds(1));                        // 边界 T0
        job.pulse(T0.plus(TEN_MINUTES).minusSeconds(1));     // 仍是边界 T0
        assertEquals(1, calls.size(), "边界前一秒还是同一个边界");

        PulseResult crossed = job.pulse(T0.plus(TEN_MINUTES).plusSeconds(1));   // 边界 T0+10min
        assertTrue(crossed.fetched());
        assertEquals(T0.plus(TEN_MINUTES), crossed.boundary());
        assertEquals(2, calls.size());

        // "落在哪个边界"是按 now 算的, 而不是按"距上次过了多久" ——
        // 这正是 floorTo 与 Duration.between 的区别(见 SimulationClock 类注释)
        assertEquals(T0.plus(TEN_MINUTES), job.lastFetchedBoundary());
        // 刚过完的这个边界已经服务过了, 所以"下一次"是再往后一个 —— 而不是它自己
        assertEquals(T0.plus(TEN_MINUTES.multipliedBy(2)),
                job.nextBoundaryAt(T0.plus(TEN_MINUTES).plusSeconds(1)));

        job.pulse(T0.plus(TEN_MINUTES.multipliedBy(2)).plusSeconds(1));
        assertEquals(3, calls.size());
        assertEquals(T0.plus(TEN_MINUTES.multipliedBy(2)), job.lastFetchedBoundary());
    }

    // ─────────────────── ③ 失败: 吞掉、计数、下一个边界照常 ───────────────────

    @Test
    void aFailingFetchIsSwallowedCountedAndDoesNotStopTheNextBoundary() {
        SimulationClock clock = SimulationClock.at(T0);
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            attempts.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("气象 API 挂了");
            }
            return ok(now);
        });

        PulseResult failed = job.pulse(T0.plusSeconds(5));
        assertTrue(failed.failed(), "异常必须变成一次 FAILED, 而不是从 pulse 里飞出去");
        assertTrue(failed.readings().isEmpty(), "失败的那一拍没有可交付的东西");
        assertEquals(1, job.failures());
        assertEquals(0, job.fetches());
        assertNotNull(job.lastError());
        assertTrue(job.lastError().contains("气象 API 挂了"));

        // 同一个边界内不再重试 —— 失败也占用边界。
        // 不这么做的后果很具体: 驱动 pulse 的那一侧是每秒问一次, 于是一次外部故障
        // 在十分钟里被放大成 600 次外呼(World 的 lastEnvironmentAttemptAt 那段话)
        job.pulse(T0.plusSeconds(30));
        job.pulse(T0.plusSeconds(300));
        assertEquals(1, attempts.get(), "失败的边界内不该反复重试");

        // 失败的边界不能被记成"成功取过"
        assertNull(job.lastFetchedBoundary());

        // 下一个边界照常 —— 一次外部故障不该让她的一天停摆
        failing.set(false);
        PulseResult next = job.pulse(T0.plus(TEN_MINUTES).plusSeconds(1));
        assertTrue(next.fetched());
        assertEquals(2, attempts.get());
        assertEquals(1, job.fetches());
        assertEquals(T0.plus(TEN_MINUTES), job.lastFetchedBoundary());
        assertNull(job.lastError(), "成功之后 lastError 应当被清掉");
    }

    // ─────────────────── ③b 取回来是空批次也是正常结果 ───────────────────

    @Test
    void anEmptyBatchIsANormalOutcomeNotAFailure() {
        SimulationClock clock = SimulationClock.at(T0);
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> List.of());

        PulseResult result = job.pulse(T0.plusSeconds(1));

        // "每个地点都没人"是完全正常的十分钟(World 的配额过滤器), 它不是失败
        assertTrue(result.fetched());
        assertFalse(result.hasReadings(), "空批次不必往仿真线程上提交一次");
        assertEquals(0, job.failures(), "没人不是故障");
        assertEquals(1, job.fetches(), "但这一轮确实问过上游");
        assertEquals(0, job.fetchedReadings());
        assertEquals(T0, job.lastFetchedBoundary(), "取过就是取过 —— 空批次也占用边界");
    }

    // ─────────────────── ④ nextBoundaryAt ───────────────────

    @Test
    void nextBoundaryAtSaysWhenTheNextFetchReallyHappens() {
        SimulationClock clock = SimulationClock.at(T0);
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, EnvironmentRefreshJobTest::ok);

        // 还没处理过这个边界 → "下一次"就是它自己(此刻 pulse 就会真的去取)
        assertEquals(T0, job.nextBoundaryAt(T0.plusSeconds(1)));

        job.pulse(T0.plusSeconds(1));

        // 处理过之后是下一个边界
        assertEquals(T0.plus(TEN_MINUTES), job.nextBoundaryAt(T0.plusSeconds(1)));
        assertEquals(T0.plus(TEN_MINUTES), job.nextBoundaryAt(T0.plusSeconds(599)));

        // 正落在边界上问: 也已经算"处理过了", 所以是再下一个 —— 而不是说"就在此刻"
        assertEquals(T0.plus(TEN_MINUTES), job.nextBoundaryAt(T0.plus(TEN_MINUTES)));

        job.pulse(T0.plus(TEN_MINUTES));
        assertEquals(T0.plus(TEN_MINUTES.multipliedBy(2)), job.nextBoundaryAt(T0.plus(TEN_MINUTES)));
    }

    // ─────────────────── ⑤ 重入: 跳过, 并把边界一并记掉 ───────────────────

    @Test
    void aReentrantPulseIsSkippedAndConsumesTheBoundary() {
        SimulationClock clock = SimulationClock.at(T0);
        AtomicReference<EnvironmentRefreshJob> self = new AtomicReference<>();
        AtomicInteger sinkCalls = new AtomicInteger();

        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            sinkCalls.incrementAndGet();
            // 模拟"被两个驱动同时问"的接线: 另一个线程在这一次取数还没返回时驱动同一个 job
            self.get().pulse(now.plus(TEN_MINUTES));
            return ok(now);
        });
        self.set(job);

        PulseResult outer = job.pulse(T0.plusSeconds(1));
        assertTrue(outer.fetched());
        assertEquals(1, sinkCalls.get());
        assertEquals(1, job.skipped(), "在跑的那一轮结束时进来的 pulse 必须被跳过");
        assertEquals(1, job.fetches(), "跳过的那一次不算一次取数");

        // 被跳过的那一个边界被记成"试过了" —— 于是接下来的每次 pulse 都只是内存里
        // 的一次比较, 不会变成每秒一条 WARN
        assertEquals(T0.plus(TEN_MINUTES.multipliedBy(2)),
                job.nextBoundaryAt(T0.plus(TEN_MINUTES).plusSeconds(1)),
                "被跳过的边界不该被反复重试");

        // 而成功的那一轮仍然是成功的
        assertEquals(T0, job.lastFetchedBoundary());
    }

    // ─────────────────── 读数里的失败不是 job 的失败 ───────────────────

    @Test
    void failedReadingsAreCountedByTheJobButAreNotJobFailures() {
        SimulationClock clock = SimulationClock.at(T0);
        AtomicInteger attempt = new AtomicInteger();
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            // 两个环境里有一个取不到 —— 这是"上游那条数据链"的问题, 不是本 job 的问题
            attempt.incrementAndGet();
            return List.of(reading(0, now), failedReading(1, now));
        });

        PulseResult result = job.pulse(T0.plusSeconds(1));

        assertTrue(result.fetched(), "World 把取数失败变成读数里的失败快照, 而不是抛异常");
        assertEquals(1, result.failedReadings(), "这一批里有一份是失败的");
        assertEquals(0, job.failures(), "job 这一层没有异常");
        assertEquals(1, job.fetches(), "它确实调了一次 sink");
        assertEquals(2, job.fetchedReadings());
        assertEquals(1, job.failedReadings(),
                "而那一份确实失败了 —— 运维要看的数在这里(它数的是'上游坏了几次')");
        assertTrue(job.describe().contains("其中失败 1 份"), job.describe());
    }

    // ─────────────────── 装配阶段的错误 ───────────────────

    @Test
    void aNonPositiveIntervalIsRejectedAtConstruction() {
        SimulationClock clock = SimulationClock.at(T0);

        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentRefreshJob(clock, EnvironmentRefreshJobTest::ok, Duration.ZERO),
                "零间隔 = 每次 pulse 都外呼一次, 而那不会让天气更新得更快");
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentRefreshJob(clock, EnvironmentRefreshJobTest::ok, Duration.ofMinutes(-1)));
        assertThrows(NullPointerException.class,
                () -> new EnvironmentRefreshJob(null, EnvironmentRefreshJobTest::ok));
        assertThrows(NullPointerException.class,
                () -> new EnvironmentRefreshJob(clock, null));
    }

    /** 一个返回 null 的 sink 是我们自己的接线错误 —— 它要被看见, 但不该让这一拍炸掉。 */
    @Test
    void aSinkReturningNullIsTreatedAsAnEmptyBatchNotAsAFailure() {
        SimulationClock clock = SimulationClock.at(T0);
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> null);

        PulseResult result = job.pulse(T0.plusSeconds(1));

        assertTrue(result.fetched(), "接线错误不该被伪装成'气象 API 挂了'");
        assertTrue(result.readings().isEmpty());
        assertEquals(0, job.failures());
    }

    // ─────────────────── 节奏可以独立配 ───────────────────

    @Test
    void theRhythmIsIndependentlyConfigurable() {
        SimulationClock clock = SimulationClock.at(T0);
        List<Instant> calls = new ArrayList<>();
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, now -> {
            calls.add(now);
            return ok(now);
        }, Duration.ofMinutes(1));

        assertEquals(Duration.ofMinutes(1), job.interval());

        // 1 分钟节奏下, 原本属于同一个 10 分钟边界的两个时刻落在两个边界上
        job.pulse(T0.plusSeconds(1));
        job.pulse(T0.plusSeconds(61));
        assertEquals(2, calls.size());
        assertEquals(T0.plusSeconds(60), job.lastFetchedBoundary());
        // 默认节奏仍是 10 分钟 —— 它恰好等于 World 的门, 但所有者不同(见类注释最后一节)
        assertEquals(Duration.ofMinutes(10), EnvironmentRefreshJob.DEFAULT_REFRESH_INTERVAL);
        assertEquals(EnvironmentRefreshJob.DEFAULT_REFRESH_INTERVAL,
                new EnvironmentRefreshJob(clock, EnvironmentRefreshJobTest::ok).interval());
    }

    @Test
    void describeCarriesTheOpsFacingNumbers() {
        SimulationClock clock = SimulationClock.at(T0);
        EnvironmentRefreshJob job = new EnvironmentRefreshJob(clock, EnvironmentRefreshJobTest::ok);

        assertTrue(job.describe().contains("尚未成功过"), "一次都没跑过时要说得出来");
        job.pulse(T0.plusSeconds(1));

        String text = job.describe();
        assertTrue(text.contains("取数 1 轮"), text);
        assertTrue(text.contains("读数 2 份"), "取回多少份要能看见: " + text);
        assertTrue(text.contains(T0.toString()), "上次成功取数的边界要能看见: " + text);
        assertFalse(text.contains("需要处理"), "没失败就不要报警: " + text);
        // 它刻意<b>不</b>报"改了几个环境、投了几条事件" —— 那些数在 World 那份报告里,
        // 抄一份到这里会让两个数在出现分歧时都能自称是真的(见 describe 的注释)
        assertFalse(text.contains("事件"), "apply 那一侧的数不该出现在这一行里: " + text);
    }
}
