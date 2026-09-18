package com.luxera.companion.human.life.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.5 —— <b>"到点了, 该做这一项了"</b>。
 *
 * <h2>为什么触发要有自己的类型, 而不是直接返回 {@link PlanItem}</h2>
 * 因为 {@code PlanItem} 描述的是<b>安排</b>（"12:00-13:00 写作业"）,
 * 而触发描述的是<b>一次发生</b>（"13:07 才轮到她处理这一项"）。两者携带的信息不同:
 *
 * <table border="1">
 *   <tr><th></th><th>{@code PlanItem}</th><th>本类</th></tr>
 *   <tr><td>知道</td><td>应该什么时候做</td><td>实际什么时候被处理、迟了多久</td></tr>
 *   <tr><td>会变吗</td><td>不会（不可变快照的一部分）</td><td>每次触发都不同</td></tr>
 *   <tr><td>能重复吗</td><td>能（同一项被触发一次）</td><td>不能 —— 一次触发就是一个事实</td></tr>
 * </table>
 *
 * <h2>{@link #lateness()} 为什么必须存在</h2>
 * 它看起来像是调试信息, 实际上是一个<b>核心行为数据</b>。
 *
 * <p>仿真是离散 tick 驱动的, 而计划是连续的。两者必然对不齐: 计划 12:00:00 开始,
 * 而 tick 可能落在 12:00:03。这个偏差本身无害 —— 但如果它<b>变大</b>, 那是信号:
 * <ul>
 *   <li>tick 太稀疏（仿真精度不够, 结论不可信）;</li>
 *   <li>或者她当时正忙于别的事（<b>这才是真正有价值的那一半</b>）。</li>
 * </ul>
 *
 * <p>第二半的意义: "计划 12:00 开始写作业, 实际 12:09 才开始" 与
 * "计划 12:00 开始, 12:00 就开始" 描述的是两种不同的她。前者是拖延,
 * 后者是自律 —— 而区分它们所需要的, 恰恰就是这个字段。
 *
 * <p><b>所以 {@link #lateness()} 不是"误差", 是"拖延的度量"。</b>
 * 把它当成误差来消除（比如把计划时间对齐到 tick 边界）会毁掉这个信号。
 */
public record PlanTrigger(
        PlanItem item,
        Instant scheduledStart,
        Instant firedAt,
        long tickSequence) {

    public PlanTrigger {
        Objects.requireNonNull(item, "触发的计划项不能为空");
        Objects.requireNonNull(scheduledStart, "计划开始时刻不能为空");
        Objects.requireNonNull(firedAt, "实际触发时刻不能为空");
        if (scheduledStart.isAfter(firedAt)) {
            throw new IllegalArgumentException(
                    "触发时刻 " + firedAt + " 早于计划开始时刻 " + scheduledStart
                            + " —— 提前触发说明调度器的判断条件写错了, "
                            + "而如果放它过去, 症状是'她提前开始做计划里的事'");
        }
    }

    /** 迟了多久。零表示准时（在 tick 精度内）。 */
    public Duration lateness() {
        return Duration.between(scheduledStart, firedAt);
    }

    /** 迟到的毫秒数 —— 日志与报表里更好用。 */
    public long latenessMillis() {
        return lateness().toMillis();
    }

    /**
     * 迟到的程度是否可以忽略。
     *
     * <p>阈值取 1 秒: 一个 1 秒以内的偏差在行为上不可观测, 把它算成"拖延"
     * 只会给行为分析引入噪声。
     */
    public static final Duration NEGLIGIBLE = Duration.ofSeconds(1);

    public boolean late() {
        return lateness().compareTo(NEGLIGIBLE) > 0;
    }

    /**
     * 这一项因为在做别的事而错过它的起点了吗。
     *
     * <p>与 {@link #late()} 的差别: 那个是"tick 什么时候来的", 这个是
     * "她当时有没有空"。后者需要外部告知（计划表自己不知道她在忙什么）,
     * 所以它不在这里判断。
     */
    public Optional<Duration> overdueBy(Instant now) {
        return item.runningLateBy(now);
    }

    /** 计划项的计划时长 —— 报表里常用, 免得每次写 {@code trigger.item().window().duration()}。 */
    public Duration plannedDuration() {
        return item.window().duration();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("触发 ").append(item.id().value()).append(" 「")
                .append(item.intent().description()).append("」 计划 ")
                .append(scheduledStart);
        if (late()) {
            sb.append(", 实际 ").append(firedAt).append(" (迟 ").append(latenessMillis()).append("ms)");
        } else {
            sb.append(", 准时");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
