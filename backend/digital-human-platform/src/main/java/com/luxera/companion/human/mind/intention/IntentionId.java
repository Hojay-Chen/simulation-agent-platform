package com.luxera.companion.human.mind.intention;

import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>一个意图的身份</b>。
 *
 * <h2>它与 {@code PlanItemId}、{@code PlanIntent.IntentId} 的区别</h2>
 * 三者看起来都是"一个 id", 但它们数的东西不一样, 混用会让统计直接错掉:
 *
 * <table border="1">
 *   <tr><th>类型</th><th>数的是</th><th>例子</th></tr>
 *   <tr>
 *     <td>{@code PlanItemId}</td>
 *     <td><b>一次安排</b></td>
 *     <td>"周三 19:00-20:00 写作业"</td>
 *   </tr>
 *   <tr>
 *     <td>{@code PlanIntent.IntentId}</td>
 *     <td><b>被安排的那件事</b></td>
 *     <td>"把数学作业第 3 章做完"</td>
 *   </tr>
 *   <tr>
 *     <td>本类型</td>
 *     <td><b>她想要什么</b></td>
 *     <td>"我想把今天的实验做完"(还不知道什么时候做、做不做得成)</td>
 *   </tr>
 * </table>
 *
 * <p>后两者的差别最容易被忽略, 而它是有内容的: 一个 {@code PlanIntent} 已经<b>通过了
 * 可行性检查</b>并被翻译成了计划表能理解的形式; 而一个 {@code Intention} 可能
 * 根本做不成、可能永远排不进计划表、可能只是她的一句"我想"。把两者合成一个类型,
 * 就意味着"想做但做不了的事"在她心里无处存在 —— 而人心里大部分念头都属于这一类。
 *
 * <h2>它不是从 {@code PlanIntent.IntentId} 派生的</h2>
 * 因为那样一来, {@link #value()} 就会背上"必须能当计划意图 id 用"的隐含契约。
 * 转换发生在 {@link Intention#toPlan()} 那一处, 且<b>只有</b>那一处 ——
 * 见 {@link Feasibility} 里关于"一个转换点"的说明。
 */
public record IntentionId(String value) implements Comparable<IntentionId> {

    public IntentionId {
        Objects.requireNonNull(value, "意图 id 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException("意图 id 不能是空白");
        }
    }

    public static IntentionId of(String value) {
        return new IntentionId(value);
    }

    @Override
    public int compareTo(IntentionId other) {
        return value.compareTo(other.value);
    }

    public String describe() {
        return "intention:" + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
