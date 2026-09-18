package com.luxera.companion.runtime;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §8.4 —— <b>她的一天里那个唯一的时间来源</b>。
 *
 * <h2>为什么必须有一个这样的类, 而不是到处 {@code Instant.now()}</h2>
 * 这一条是整个 V2.2 设计的隐含前提, 而它在这份代码里被反复引用
 * （{@code PlanBoard} "重排必须带时刻"、{@code PlanScheduler} "调度器不读时钟"、
 * {@code Activity} "开始时刻由外部给定"）。理由只有一句话:
 *
 * <blockquote>
 * 行为分析要能回放"她 12:15 为什么这样排"。一个会读系统时间的实现,
 * 会让这个回放永远无法验证。
 * </blockquote>
 *
 * <p>具体到后果上, 三个问题都源自同一个根:
 * <ul>
 *   <li><b>测试无法确定。</b>一个内部读 {@code now()} 的规划器, 其行为随运行时刻变化。
 *       测试于是只能写成"结果应当落在某个范围里", 而那种断言放过了大部分 bug;</li>
 *   <li><b>回放无法复现。</b>把上周三的数据喂回去重跑, 得到的是另一段历史 ——
 *       于是"她为什么变消沉了"这个问题只能靠猜;</li>
 *   <li><b>加速仿真无法进行。</b>要观察她一个月的演变, 不可能真的等一个月。
 *       而只要时间是从系统时钟读的, 加速就没有着力点。</li>
 * </ul>
 *
 * <h2>两种模式</h2>
 * <table border="1">
 *   <tr><th>模式</th><th>{@link #now()} 怎么算</th><th>用在哪</th></tr>
 *   <tr>
 *     <td><b>实时</b>（{@link #realtime}）</td>
 *     <td>{@code startedAt + 真实已过时间 × 速度}</td>
 *     <td>生产运行。速度 1.0 = 与真实时间同速; 速度 60 = 一分钟仿真一小时</td>
 *   </tr>
 *   <tr>
 *     <td><b>步进</b>（{@link #at}）</td>
 *     <td>存在内部的那个值, 由 {@link #advanceBy}/{@link #advanceTo} 推进</td>
 *     <td>测试与回放。<b>时间是被人推着走的, 不是自己流的</b></td>
 *   </tr>
 * </table>
 *
 * <p>两种模式下 {@code now()} 都是<b>全函数</b>（永远有答案, 不需要先"启动"）,
 * 调用方也不必知道自己在哪种模式里 —— 这是刻意的: 如果调用方需要判断模式,
 * 那么测试里跑的那条路径与生产里跑的就<b>不是同一条</b>, 而测试的价值正来自它们是同一条。
 *
 * <h2>仿真时间<b>只能向前</b></h2>
 * {@link #advanceTo} 收到一个早于当前时刻的值会抛异常, 而不是悄悄接受。
 * 理由: 时间倒流会同时破坏三件事 ——
 * "旧版本永不删除"（{@code PlanRevision} 链会成环）、
 * 因果顺序（{@code EventFabric} 的因果深度追踪会失效）、
 * 以及"她的一天"这个最基本的概念。
 * <b>一个允许时间倒流的仿真系统, 其全部历史数据都不可信。</b>
 *
 * <p>需要回到过去重跑时, 正确的做法是<b>新建一个 clock</b>（{@link #at}），
 * 而不是把当前这个往回拨。
 *
 * <h2>时间边界的对齐 —— 环境刷新为什么要用它</h2>
 * 用户的要求是"环境每隔 1 分钟或 10 分钟刷新一次"。实现这一点有两种写法:
 * <pre>{@code
 * // ❌ 从"上次刷新"起算: 会漂移
 * if (Duration.between(lastRefresh, now).compareTo(TEN_MINUTES) >= 0) { refresh(); }
 *
 * // ✅ 对齐到时间边界: 永远落在 :00 / :10 / :20
 * if (clock.floorTo(TEN_MINUTES).isAfter(lastRefresh)) { refresh(); }
 * }</pre>
 *
 * <p>第一种看起来更简单, 但它的实际效果是刷新时刻会一路漂移下去 ——
 * 因为每次执行都有几毫秒的误差, 而那些误差会累积。跑了三天之后,
 * "每 10 分钟"变成了"每 10 分钟零 4 秒", 而她日记里的天气变化时刻
 * 与真实世界的天气数据再也对不上。{@link #floorTo} 把这件事变成确定的。
 */
@Slf4j
public final class SimulationClock {

    /** 默认速度: 一秒钟仿真一秒。 */
    public static final double REALTIME_SPEED = 1.0;

    private final Instant startedAt;
    private final Double speed;          // 非空 = 实时模式; 空 = 步进模式
    private final Clock wallClock;       // 只用于实时模式; 从不让领域代码看到它

    /** 步进模式下的当前时刻。实时模式下不使用。 */
    private volatile Instant stepped;

    /** 推进过多少次 —— "她这三天里被推了多少下"这个问题的原始数据。 */
    private long tickSequence;

    private SimulationClock(Instant startedAt, Double speed, Clock wallClock) {
        if (startedAt == null) {
            throw new IllegalArgumentException(
                    "仿真必须有一个起点时刻 —— 一个不知从何时开始的仿真无法回放");
        }
        this.startedAt = startedAt;
        this.speed = speed;
        this.wallClock = wallClock;
        this.stepped = startedAt;
    }

    /**
     * 实时时钟 —— 生产用。
     *
     * @param startedAt 仿真的起点。通常取服务启动那一刻, 或数据里上一次的时刻
     *                  （<b>续跑时用后者</b>, 否则她的一天会从"现在"重新开始）
     * @param speed     仿真秒 / 真实秒。1.0 = 同速, 60 = 一分钟当一小时
     */
    public static SimulationClock realtime(Instant startedAt, double speed) {
        if (speed <= 0) {
            throw new IllegalArgumentException(
                    "速度必须为正, 收到 " + speed + " —— 零或负的速度会让时间停住或倒流");
        }
        return new SimulationClock(startedAt, speed, Clock.systemUTC());
    }

    /** 实时时钟, 同速。 */
    public static SimulationClock realtime(Instant startedAt) {
        return realtime(startedAt, REALTIME_SPEED);
    }

    /**
     * 步进时钟 —— 测试与回放用。
     *
     * <p>它是<b>确定性的</b>: 从同一个起点出发、按同样的顺序推进,
     * 得到的时刻序列每次都完全一样。
     */
    public static SimulationClock at(Instant start) {
        return new SimulationClock(start, null, null);
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 现在的仿真时刻。
     *
     * <p>实时模式下它是从墙钟算出来的, 步进模式下它是被推进到的那个值。
     * 两种模式都不需要"启动"。
     */
    public Instant now() {
        if (speed == null) {
            return stepped;
        }
        long realMillis = wallClock.millis() - instantToWallBase();
        long simMillis = (long) (realMillis * speed);
        return startedAt.plusMillis(simMillis);
    }

    /** 实时模式下墙钟的基准 —— 第一次问时间时记下来, 此后不再变。 */
    private volatile Long wallBase;

    private long instantToWallBase() {
        Long base = wallBase;
        if (base == null) {
            synchronized (this) {
                if (wallBase == null) {
                    wallBase = wallClock.millis();
                }
                base = wallBase;
            }
        }
        return base;
    }

    /** 仿真的起点。 */
    public Instant startedAt() {
        return startedAt;
    }

    /** 从起点到现在过了多久（仿真时间）。 */
    public Duration elapsed() {
        return Duration.between(startedAt, now());
    }

    /** 是不是实时模式。 */
    public boolean isRealtime() {
        return speed != null;
    }

    /** 速度（步进模式返回 0）。 */
    public double speed() {
        return speed == null ? 0.0 : speed;
    }

    /** 推进过多少次。 */
    public long tickSequence() {
        return tickSequence;
    }

    // ─────────────────────────── 推进 ───────────────────────────

    /**
     * 把时间推到 {@code target}。
     *
     * <p>实时模式下这个方法<b>只是对齐检查</b> —— 时间在墙钟那边自己流,
     * 你不能推它。传一个未来的时刻会抛异常, 因为那说明调用方以为自己在步进模式里。
     *
     * @throws IllegalArgumentException 目标时刻早于当前时刻, 或实时模式下传了未来时刻
     */
    public void advanceTo(Instant target) {
        Objects.requireNonNull(target, "推进目标不能为空");
        Instant current = now();
        if (target.isBefore(current)) {
            throw new IllegalArgumentException(
                    "仿真时间只能向前: 当前 " + current + ", 却被要求回到 " + target
                            + " —— 时间倒流会同时破坏版本链、因果顺序和'她的一天'这个概念。"
                            + "需要重跑请新建一个 clock");
        }
        if (speed != null) {
            if (target.isAfter(current)) {
                throw new IllegalArgumentException(
                        "实时模式下时间由墙钟推动, 不能被推到 " + target
                                + "（当前 " + current + "）—— 需要控制时间请用 SimulationClock.at(...)");
            }
            return;   // target == current, 无操作
        }
        this.stepped = target;
        this.tickSequence++;
    }

    /** 把时间往前推一段。 */
    public void advanceBy(Duration amount) {
        Objects.requireNonNull(amount, "推进量不能为空");
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                    "推进量不能为负, 收到 " + amount + " —— 要回退请新建一个 clock");
        }
        advanceTo(now().plus(amount));
    }

    // ─────────────────────────── 时间边界 ───────────────────────────

    /**
     * 把 {@code moment} 向下取整到 {@code interval} 的整数倍（相对 {@link #startedAt}）。
     *
     * <p>用它来实现周期性任务: "每 10 分钟"就是
     * {@code floorTo(now, TEN_MINUTES)} 变了一个值。见类注释里那段关于漂移的说明。
     *
     * @throws IllegalArgumentException interval 不为正
     */
    public Instant floorTo(Instant moment, Duration interval) {
        Objects.requireNonNull(moment, "要对齐的时刻不能为空");
        requirePositive(interval);
        long step = interval.toMillis();
        long delta = moment.toEpochMilli() - startedAt.toEpochMilli();
        long floored = Math.floorDiv(delta, step) * step;
        return startedAt.plusMillis(floored);
    }

    /** 当前时刻向下取整到 {@code interval} 的整数倍。 */
    public Instant floorTo(Duration interval) {
        return floorTo(now(), interval);
    }

    /**
     * 下一个时间边界（严格大于 {@code after}）。
     *
     * <p>周期性任务用它算"下次该在什么时候跑", 从而不必自己攒一个
     * {@code lastRun + interval} 的游标 —— 那种游标正是漂移的来源。
     */
    public Instant nextBoundary(Instant after, Duration interval) {
        Instant floored = floorTo(after, interval);
        if (floored.equals(after)) {
            return after.plus(interval);
        }
        return floored.plus(interval);
    }

    /** 当前时刻是不是正好落在 {@code interval} 的边界上。 */
    public boolean onBoundary(Duration interval) {
        Instant current = now();
        return floorTo(current, interval).equals(current);
    }

    private static void requirePositive(Duration interval) {
        Objects.requireNonNull(interval, "时间间隔不能为空");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException(
                    "时间间隔必须为正, 收到 " + interval
                            + " —— 零间隔会让对齐运算除零, 负间隔会让时间反向");
        }
    }

    // ─────────────────────────── 诊断 ───────────────────────────

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("SimulationClock[").append(speed == null ? "步进" : "实时 ×" + speed).append(']');
        sb.append(" 起点 ").append(startedAt);
        sb.append(", 现在 ").append(now());
        sb.append(", 已过 ").append(elapsed().toMinutes()).append(" 分钟");
        if (tickSequence > 0) {
            sb.append(", 推进 ").append(tickSequence).append(" 次");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SimulationClock[" + now() + "]";
    }
}
