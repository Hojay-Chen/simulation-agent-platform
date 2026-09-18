package com.luxera.companion.human.life.activity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §8.4 —— <b>一次"正在做的事"的标识</b>。
 *
 * <h2>它与 {@code PlanItemId} 是两回事</h2>
 * 这个区别容易搞混, 而搞混的后果很具体:
 * <table border="1">
 *   <tr><th></th><th>{@code PlanItemId}</th><th>{@code ActivityId}</th></tr>
 *   <tr><td>它标识什么</td><td><b>计划表上的一格</b>（"12:25-13:10 写作业"）</td>
 *       <td><b>一次实际的执行</b>（"她真的开始写了"）</td></tr>
 *   <tr><td>什么时候有</td><td>一排进计划表就有</td><td>她真的开始做的那一刻才有</td></tr>
 *   <tr><td>一项能有几个</td><td>一个</td>
 *       <td><b>可以有好几个</b> —— 她 12:25 开始写、12:40 被叫走、
 *           12:50 回来重新开始, 这是<b>两次执行</b>（两条 Activity）</td></tr>
 *   <tr><td>重排时</td><td>被 {@code SUPERSEDED}, 换成一个新 id</td>
 *       <td>不参与重排 —— 已经发生过的执行是历史, 改不了</td></tr>
 * </table>
 *
 * <p>"一项计划被做了两次"这件事必须能被表示出来。它是真实的行为:
 * 她被打断后回来重新开始的那一段, 与第一段是分开的两件事 ——
 * 注意力水平、进度、被打断的代价都不同。用同一个 id 表示两段,
 * 会让"她这一天被打断了几次"这个统计永远算不准。
 *
 * <h2>为什么在内存里生成而不是交给数据库</h2>
 * 与 {@code PlanItemId} 同理: 她开始做某件事的那一刻,
 * 这个 id 就必须存在了 —— 那是行为, 不是一次插入操作。
 * 等数据库发号意味着"她开始做事"这个动作要先做一次 IO,
 * 而仿真里的时刻不是由数据库决定的。
 *
 * <p>{@link #parse(String)} 让数据库里读回来的 id 与原始的是同一个 ——
 * 前缀 {@code "act-"} 是为了让人一眼看出这是活动而不是计划项,
 * 日志里两者经常一起出现。
 */
public record ActivityId(String value) {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public ActivityId {
        Objects.requireNonNull(value, "活动 id 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("活动 id 不能是空字符串");
        }
    }

    /** 生成一个新的。 */
    public static ActivityId generate() {
        return new ActivityId("act-" + SEQUENCE.incrementAndGet());
    }

    /** 从字符串还原（数据库读回、日志解析）。 */
    public static ActivityId parse(String raw) {
        return new ActivityId(raw);
    }

    public boolean isGenerated() {
        return value.startsWith("act-");
    }

    @Override
    public String toString() {
        return value;
    }
}
