package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanPriority;

import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>她有多想要这件事</b>。
 *
 * <h2>为什么它不是 enum —— 这一条要用 §1.3 P4 认真回答</h2>
 * 判据是: 取值集合由<b>本设计的内部逻辑</b>决定, 还是由<b>外部世界的多样性</b>决定?
 * 严格说, "紧急程度"这个词本身像是一个封闭的五档。但判据问的不是"名字听起来封闭吗",
 * 而是<b>取值的产生方式</b>:
 *
 * <ul>
 *   <li>优先级不是一个<b>分类</b>, 而是一个<b>排序用的量</b>。它要回答的问题是
 *       "这两件事哪件更急", 而不是"这件事属于哪一档"。它的唯一用途是
 *       {@link #compareTo} 与 {@link #outranks} —— 而这两件事只需要一个全序,
 *       不需要一个枚举;</li>
 *   <li>它<b>要被算出来</b>。一个意图的紧迫程度来自它的截止时间、来源(是她自己的
 *       主意还是别人的请求)、以及她此刻的状态。中间结果会落在档与档之间,
 *       而枚举只能二选一 —— 于是"稍微急一点"这个真实存在的状态在数据上消失了,
 *       表现是<b>排序出现随机的平局</b>;</li>
 *   <li>它是<b>要被插值的</b>。同一个意图在她饿的时候和刚吃完饭的时候紧迫程度不同,
 *       这个变化是连续的。枚举之间没有"之间"。</li>
 * </ul>
 *
 * <p>所以这里用与 {@code PlanPriority} 同形的 record: <b>一个数 + 一句人话</b>。
 * 具名常量({@link #TRIVIAL} 等)只是常用点的拼写便利, 不是取值集合的边界 ——
 * 第三方可以写 {@code IntentionPriority.of(63, "想在雨停之前到")}, 平台源码一行都不用改。
 *
 * <h2>它为什么与 {@code PlanPriority} 是两个类型</h2>
 * 因为两者量的不是同一件事, 而且它们<b>可以不一致</b>:
 * <ul>
 *   <li>{@code PlanPriority} 是<b>一个计划项</b>的性质 —— 它回答"在日程表上,
 *       这一项该不该给别人让路"。它是排定之后的事实, 是重排器的输入;</li>
 *   <li>本类型是<b>一个念头</b>的性质 —— 它回答"她此刻多想做这件事"。
 *       它存在于计划表之前, 也可能永远不进计划表。</li>
 * </ul>
 * 一个反例说明它们必须能分开: "我想把实验做完"这个念头优先级很高, 但它拆成的
 * 计划项("查资料 30 分钟")优先级很低 —— 因为它只是漫长过程中的一小步。
 * 合成一个类型之后, 这一步会被迫继承那个高优先级, 于是它会开始挤掉别人的日程。
 *
 * <p>两者的转换只发生在 {@link Intention#toPlan()} 那一处(经由
 * {@link IntentionPlanIntent}), 而<b>那一次转换是要丢信息的</b>(label 会被换成
 * 计划侧的说法) —— 把转换点收在一处, 才能让"丢了什么"是可数的。
 */
public record IntentionPriority(int level, String label) implements Comparable<IntentionPriority> {

    /** 可做可不做 —— 想起来才做。 */
    public static final IntentionPriority TRIVIAL = new IntentionPriority(10, "可做可不做");

    /** 日常想要 —— 她大部分念头在这一档。 */
    public static final IntentionPriority ROUTINE = new IntentionPriority(40, "想做的事");

    /** 有承诺或有截止时间 —— "答应了人家"、今晚必须交。 */
    public static final IntentionPriority IMPORTANT = new IntentionPriority(70, "有承诺或截止时间");

    /** 不能让路 —— 疼、急、危险。 */
    public static final IntentionPriority CRITICAL = new IntentionPriority(95, "不能让路的事");

    /** 缺省档 —— 与 {@code PlanPriority.DEFAULT} 取同一档, 于是"没特别说"的意图不会凭空插队。 */
    public static final IntentionPriority DEFAULT = ROUTINE;

    public IntentionPriority {
        Objects.requireNonNull(label, "优先级必须带一句人话 —— 它会进 LLM context 与日志");
        if (level < 0 || level > 100) {
            throw new IllegalArgumentException("优先级的级别必须在 [0, 100], 收到 " + level
                    + " —— 越界不会报错, 只会让排序结果变得无法解释");
        }
    }

    public static IntentionPriority of(int level) {
        return new IntentionPriority(level, "级别 " + level);
    }

    public static IntentionPriority of(int level, String label) {
        return new IntentionPriority(level, label);
    }

    /** 这一档比另一档高吗。 */
    public boolean outranks(IntentionPriority other) {
        return other != null && level > other.level;
    }

    /**
     * 这一档<b>明显</b>比另一档高吗 —— 用于"值得打断她"的判断。
     *
     * <p>阈值与 {@code PlanPriority.SIGNIFICANT_GAP} 取同一个数(15)。这不是巧合:
     * "明显更高"这个判断在计划侧和意图侧必须一样, 否则一个念头在进入计划表的那一刻
     * 会换一个答案。
     */
    public static final int SIGNIFICANT_GAP = 15;

    public boolean significantlyOutranks(IntentionPriority other) {
        return other != null && level - other.level >= SIGNIFICANT_GAP;
    }

    /** 到计划侧的说法 —— 这是两个类型之间<b>唯一</b>的转换点。 */
    public PlanPriority toPlanPriority() {
        return PlanPriority.of(level, label);
    }

    @Override
    public int compareTo(IntentionPriority other) {
        return Integer.compare(level, other.level);
    }

    public String describe() {
        return label + "(" + level + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
