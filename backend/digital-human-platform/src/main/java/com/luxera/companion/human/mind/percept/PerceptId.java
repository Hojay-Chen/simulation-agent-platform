package com.luxera.companion.human.mind.percept;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.3 —— <b>一次感知的身份</b>。
 *
 * <h2>它为什么存在</h2>
 * 感知是<b>流</b>, 不是集合。同一条刺激在 12:00:01 和 12:00:37 各响一次是两件事,
 * 而它们长得一模一样 —— 同样的通道、同样的显著度、同样的来源。没有身份, 就没有办法回答
 * "她 12:00 之后注意到的那几次响声里, 有几次进了工作认知", 因为这两次在日志里无法区分。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不是刺激类型的名字</b>。那是 {@link com.luxera.companion.boundary.event.EventTypeId}
 *       的事 —— 一个是"哪一类感知", 一个是"哪一次感知"。混成一个是 V2.1 之前的老毛病:
 *       于是"她一共听到过几次提示音"这个问题, 答案变成了"有/没有", 因为同类型的都被去重了。</li>
 *   <li><b>不是排序依据</b>。时刻由 {@link Percept#occurredAt()} 给。
 *       {@link #generate()} 用的是进程内的自增序号而<b>不是系统时钟</b> ——
 *       这一条不是风格问题: {@code human/} 里读时钟会让回放不可复现,
 *       而 V22BoundaryArchitectureTest 会直接打红。</li>
 * </ul>
 *
 * <h2>为什么序号够用, 而不需要 UUID</h2>
 * 感知 id 只在<b>一个 agent 的两次 tick 之间</b>被引用(进工作记忆、进日志、被决策引用),
 * 从不跨进程合并。所以"进程内自增"与"全局唯一"在这里是同一个东西,
 * 而自增还额外给了一个属性: 它<b>可以比较先后</b>, 于是"最近三次感知"不需要排序就能取到。
 */
public record PerceptId(String value) implements Comparable<PerceptId> {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public PerceptId {
        Objects.requireNonNull(value, "感知 id 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "感知 id 不能是空白 —— 一个空白 id 会让'她注意到的那一次响声'"
                            + "与'另一条无 id 的记录'在日志里无法区分");
        }
    }

    public static PerceptId of(String value) {
        return new PerceptId(value);
    }

    /** 造一个新的感知 id。见类注释: 自增而不是时钟。 */
    public static PerceptId generate() {
        return new PerceptId("percept-" + SEQUENCE.incrementAndGet());
    }

    @Override
    public int compareTo(PerceptId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
