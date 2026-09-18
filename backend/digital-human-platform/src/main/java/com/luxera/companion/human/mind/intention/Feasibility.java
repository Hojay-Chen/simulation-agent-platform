package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanIntent;

import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>"这件事现在做得了吗"的答案</b>。
 *
 * <h2>它为什么不直接复用 {@link PlanIntent.Feasibility}</h2>
 * 两者形状几乎一样, 所以"为什么不合成一个"必须先回答 —— 它有一个具体的答案:
 * <b>它们问的不是同一个问题, 而且会给出不同的答案</b>。
 *
 * <table border="1">
 *   <tr><th></th><th>本类型</th><th>{@code PlanIntent.Feasibility}</th></tr>
 *   <tr>
 *     <td>问的是</td>
 *     <td>她<b>此刻</b>做得了这件事吗</td>
 *     <td>这件事<b>排得进日程表</b>吗</td>
 *   </tr>
 *   <tr>
 *     <td>谁来算</td>
 *     <td>意图自己(它知道需要什么)</td>
 *     <td>计划侧(它知道约束、时间窗口、已有安排)</td>
 *   </tr>
 *   <tr>
 *     <td>典型反例</td>
 *     <td>"去实验室" —— 现在去得了</td>
 *     <td>"去实验室" —— 今明两天排不进任何 90 分钟的空档</td>
 *   </tr>
 * </table>
 *
 * <p>两个答案都是真的, 而它们同时成立的情况非常常见。合成一个类型之后, 这种
 * 不一致只能靠"谁最后写的算"来解决 —— 而症状会是<b>她说"我现在就去", 然后一整天
 * 都没有去</b>, 且日志里看不出是哪一步出的问题。
 *
 * <p>两者的转换收在 {@link #toPlan()} 一处。它不是无损的: 计划侧多一个
 * {@code missing} 的语义约束(那些字符串会被重排器拿去推导前置项),
 * 而本类型的 {@code missing} 是给她自己看的。转换时两者被合在一起,
 * 这正是"转换点必须唯一"的理由 —— 丢信息的地方只有一个, 可数。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不含优先级</b>。做得了的事未必急着做 —— 那是 {@link IntentionPriority} 的事。
 *       把两者合成一个"值不值得做"的分数, 会让"现在做不了但很急"这种状态
 *       无法表达, 而它恰恰是最需要被解释的一种状态;</li>
 *   <li><b>不含时间估计</b>。多久做完是 {@link Intention#expectedDuration()},
 *       因为"做得了吗"与"要多久"是两个独立的问题: 一场考试做得了但需要两小时,
 *       而她现在只有二十分钟。</li>
 * </ul>
 *
 * @param feasible 现在做得了吗
 * @param reason   <b>无论可行与否都要给的一句人话</b> —— 它会进 LLM context。
 *                 空白是允许的但不推荐, 理由见 {@code PlanIntent.Feasibility} 的同一段:
 *                 日志里会出现一排没有理由的判定
 * @param missing  缺什么才能做。可行时为空列表
 */
public record Feasibility(boolean feasible, String reason, List<String> missing) {

    public Feasibility {
        Objects.requireNonNull(reason, "可行性的理由不能为 null —— 没有理由时请用空字符串");
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    public static Feasibility yes(String reason) {
        return new Feasibility(true, reason, List.of());
    }

    /**
     * "做得了", 不附理由 —— <b>绝大多数情况下这是你想要的</b>。
     *
     * <p>它存在的理由是"理由"的两个不对称: 做不成时的理由必须写(那是排查的入口),
     * 而做得了时的理由通常没什么可说的。若只提供 {@link #yes(String)}, 那么每个
     * 实现类都会被迫编一句话("可以做")—— 而一排"可以做"的日志会把真正有信息量的
     * 那几条挤掉。
     *
     * <p>真正有话说的时候(比如"现在去正好, 再晚就锁门了")才用 {@link #yes(String)}。
     */
    public static Feasibility yes() {
        return new Feasibility(true, "", List.of());
    }

    public static Feasibility no(String reason, String... missing) {
        return new Feasibility(false, reason, List.of(missing));
    }

    public static Feasibility no(String reason, List<String> missing) {
        return new Feasibility(false, reason, missing);
    }

    /** 到计划侧的说法 —— 两个类型之间<b>唯一</b>的转换点。 */
    public PlanIntent.Feasibility toPlan() {
        return new PlanIntent.Feasibility(feasible, reason, missing);
    }

    public String describe() {
        if (feasible) {
            return "现在做得了" + (reason.isEmpty() ? "" : " —— " + reason);
        }
        return "现在做不了: " + reason + (missing.isEmpty() ? "" : " (缺: " + missing + ")");
    }

    @Override
    public String toString() {
        return describe();
    }
}
