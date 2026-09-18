package com.luxera.companion.human.mind.decision;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.7 —— <b>一次决定的身份</b>。
 *
 * <h2>为什么"决定"需要一个 id</h2>
 * 因为一次决定会<b>留下好几样东西</b>: 一个 {@link Decision} 对象、若干条
 * {@code ActionCommand}、可能还有一次 {@code PlanRevision}, 以及一条
 * {@link DecisionMade} 事件。这四样东西分散在四个地方, 而事后复盘要问的第一个问题是:
 *
 * <blockquote>
 * "她 12:15 发的这条消息, 对应的是哪一次决定?"
 * </blockquote>
 *
 * <p>没有 id 时, 这个问题只能靠时间戳去猜 —— 而同一个瞬间可能有两件事
 * (她一边回消息一边改了计划), 于是"猜"会给出一个看起来合理但错误的答案。
 * 一个错的溯源结论比没有结论更糟: 它会被写进行为分析报告。
 *
 * <h2>它不是从 {@code PlanRevision} 或 {@code ActionCommand} 派生的</h2>
 * 因为一次决定<b>可以什么都不产生</b>(她想了想, 决定继续写作业 ——
 * 那是 §3.5.6 里 {@code KeepActive} 存在的理由), 也可以只产生其中之一。
 * 让它派生自某一类产物, 会让"什么都没产生的决定"没有身份 —— 而那恰恰是
 * 最需要被记录的一类: <b>"她当时为什么没回"的答案就是一次没有动作的决定。</b>
 *
 * <h2>它为什么不读时钟</h2>
 * 序号来自一个进程内的计数器, 时间来自参数。用 {@code Instant.now()} 拼 id
 * 是很常见的写法, 而它是 {@code human/} 的硬规则所禁止的 —— 见
 * {@code V22BoundaryArchitectureTest}。
 */
public record DecisionId(String value) implements Comparable<DecisionId> {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public DecisionId {
        Objects.requireNonNull(value, "决定 id 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("决定 id 不能是空白");
        }
    }

    public static DecisionId of(String value) {
        return new DecisionId(value);
    }

    /** 生成一个 —— 只用进程内序号, <b>不读系统时钟</b>。 */
    public static DecisionId generate() {
        return new DecisionId("decision-" + SEQUENCE.incrementAndGet());
    }

    @Override
    public int compareTo(DecisionId other) {
        return value.compareTo(other.value);
    }

    public String describe() {
        return "decision:" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
