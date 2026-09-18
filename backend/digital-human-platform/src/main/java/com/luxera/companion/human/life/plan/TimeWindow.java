package com.luxera.companion.human.life.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §3.5.2 —— <b>一个时间段</b>，不是"一个时刻"。
 *
 * <h2>为什么计划必须落成时间段</h2>
 * 用户对计划事件的描述里有一句特别关键的话：
 * <blockquote>
 * 有一类 event 其实是安排 event……可以理解为这类 event 其实是<b>计划表</b>，
 * 然后可以随时修改的，而且是<b>时间段类型</b>的，比如有一个写作业的 event，
 * 安排在 12:00 持续 1 小时
 * </blockquote>
 *
 * <p>如果计划只记"触发时刻"，那么"写作业持续 1 小时"这件事就没有地方存放，
 * 于是它会变成一个隐式的约定（"到点了就启动 activity，activity 自己知道跑多久"）。
 * 而一旦它隐式化了，下面这些问题就都答不出来：
 * <ul>
 *   <li>"12:30 的时候她本来应该正在干什么？" —— 需要问活动才知道，而活动可能已经被回收了；</li>
 *   <li>"穿衣服 12:15-12:25 会不会和写作业撞上？" —— 无解，因为写作业没有结束时间；</li>
 *   <li>"把写作业推到穿衣服之后" —— 无法表达，因为没有"之后"这个概念。</li>
 * </ul>
 *
 * <p><b>区间是半开的</b>：{@code [start, end)}。这样 {@code 12:00-13:00} 与
 * {@code 13:00-14:00} 可以首尾相接而不算重叠 —— 而那恰恰是"写作业接运动"的常见形状。
 * 用闭区间会让所有相邻计划都变成"冲突"，于是冲突检测变成一堆特例判断。
 *
 * <h2>不可变</h2>
 * 它是 {@link PlanRevision} 的一部分，而 Revision 是快照。任何一个字段可变，
 * "Revision 18 里写作业是 12:25-13:10"这件事就会随着后来的修改而改变 ——
 * 于是历史版本不再能回答"她当时计划的是什么"。<b>版本化的前提是不可变。</b>
 *
 * <p>要改时间就 {@link #startingAt} / {@link #lasting} 产生一个新的，
 * 而不是就地改。改名 {@code shift} 而非 {@code moveTo} 也是刻意的：
 * "把整个窗口平移 10 分钟"和"把起点挪到 12:25"是两件事，前者保持时长。
 */
public record TimeWindow(Instant start, Instant end) {

    /**
     * 一个窗口的最小长度。
     *
     * <p>为什么不允许零长度：一个零长度的窗口在时间轴上是一个点，
     * 它既能"不与任何东西重叠"又"占据一个触发时刻" —— 于是冲突检测会漏掉它，
     * 而调度器会触发它。<b>允许一个语义自相矛盾的形状存在，
     * 就是给未来埋一个只在特定数据下出现的 bug。</b>
     */
    public static final Duration MIN_LENGTH = Duration.ofMinutes(1);

    public TimeWindow {
        Objects.requireNonNull(start, "窗口起点不能为空");
        Objects.requireNonNull(end, "窗口终点不能为空");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "窗口终点 " + end + " 早于起点 " + start + " —— 一个负长度的窗口无法被调度，"
                            + "也无法参与冲突检测。如果你想要的是'从某时刻开始且时长未知'，"
                            + "请显式给一个预计时长（expectedDuration），因为计划表要能回答"
                            + "'这段时间她在干什么'");
        }
        if (Duration.between(start, end).compareTo(MIN_LENGTH) < 0) {
            throw new IllegalArgumentException(
                    "窗口 " + start + " → " + end + " 短于最小长度 " + MIN_LENGTH
                            + " —— 零长度或近零长度的计划项会同时满足'不重叠'和'占一个触发点'，"
                            + "让冲突检测与调度产生分歧");
        }
    }

    public static TimeWindow of(Instant start, Instant end) {
        return new TimeWindow(start, end);
    }

    /** 从某时刻起持续一段。用户描述的形状（"安排在 12:00 持续 1 小时"）直接对应这个工厂。 */
    public static TimeWindow startingAt(Instant start, Duration length) {
        Objects.requireNonNull(length, "时长不能为空");
        return new TimeWindow(start, start.plus(length));
    }

    // ─────────────────────────── 派生 ───────────────────────────

    public Duration duration() {
        return Duration.between(start, end);
    }

    public boolean contains(Instant moment) {
        // 半开区间: 终点不算在内。见类注释
        return !moment.isBefore(start) && moment.isBefore(end);
    }

    /**
     * 整个窗口是否落在另一窗口内。
     *
     * <p>给 {@code MOVE} 的校验用：把写作业推到 12:25-13:10 之后，它必须仍然
     * 落在她可用的时间范围里（比如不能跨过睡眠时间）。
     */
    public boolean within(TimeWindow outer) {
        return !start.isBefore(outer.start()) && !end.isAfter(outer.end());
    }

    /**
     * 与另一窗口是否重叠。
     *
     * <p>半开区间下的重叠判定：{@code a.start < b.end && b.start < a.end}。
     * 首尾相接（{@code a.end == b.start}）返回 {@code false} —— 见类注释。
     */
    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end()) && other.start().isBefore(end);
    }

    /** 平移整个窗口，保持时长。 */
    public TimeWindow shift(Duration delta) {
        Objects.requireNonNull(delta, "平移量不能为空");
        return new TimeWindow(start.plus(delta), end.plus(delta));
    }

    /**
     * 换一个起点，<b>保持时长</b>。
     *
     * <p>这正是用户描述的"把写作业的启动时间设定为穿衣服执行结束的时间"要用的操作 ——
     * 注意用户说的是"启动时间"，而写作业的时长（1 小时）不变。所以这个操作
     * 天然是"改起点、保时长"，而不是"改起点、保终点"。
     */
    public TimeWindow startingAt(Instant newStart) {
        Objects.requireNonNull(newStart, "新的起点不能为空");
        return new TimeWindow(newStart, newStart.plus(duration()));
    }

    /** 换一个时长，<b>保持起点</b>。对应 {@code RESIZE}。 */
    public TimeWindow lasting(Duration newLength) {
        Objects.requireNonNull(newLength, "新的时长不能为空");
        return new TimeWindow(start, start.plus(newLength));
    }

    /** 与另一窗口的交集。不相交时返回 {@link java.util.Optional#empty()}。 */
    public java.util.Optional<TimeWindow> intersect(TimeWindow other) {
        Instant s = start.isAfter(other.start()) ? start : other.start();
        Instant e = end.isBefore(other.end()) ? end : other.end();
        return s.isBefore(e) ? java.util.Optional.of(new TimeWindow(s, e)) : java.util.Optional.empty();
    }

    /**
     * 已经过去了多少（0 到 1）。
     *
     * <p>给重排器的一个输入：写作业进行到 25% 时被打断，和进行到 90% 时被打断，
     * 正确的反应是不同的（前者值得重排，后者可能干脆做完）。
     * <b>注意这不是 {@code remainingDuration}</b> —— 它只是一个用来做判断的读数，
     * 不会被存进计划表。见 §3.5.6 对"暂停/恢复"的否定。
     */
    public double progressAt(Instant now) {
        long total = duration().toMillis();
        if (total <= 0) {
            return 1.0;
        }
        long done = Duration.between(start, now).toMillis();
        return Math.max(0.0, Math.min(1.0, (double) done / total));
    }

    @Override
    public String toString() {
        return start + "→" + end;
    }
}
