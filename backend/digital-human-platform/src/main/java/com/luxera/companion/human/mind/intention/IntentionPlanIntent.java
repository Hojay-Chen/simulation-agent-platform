package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanningContext;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.4.7 —— <b>把一个念头包装成计划侧认识的形式</b>。
 *
 * <h2>它为什么存在, 且为什么只有一个</h2>
 * "她想把实验做完"与"这件事排进周四 19:00-20:00"是两种东西, 但它们之间必须有一条
 * 可走的路, 否则念头永远只是念头。这条路在代码里就是本类, 而它<b>只有一个</b>:
 * {@link Intention#toPlan()} 的默认实现返回它; 除了这里, {@code mind} 下没有任何地方
 * 构造 {@code PlanIntent}。
 *
 * <p>"只有一个"这件事有具体的价值: 两个概念之间的转换总要丢信息 ——
 * {@link IntentionPriority} 的 label 会被换成计划侧的说法,
 * {@link Feasibility} 的 {@code missing} 会被合进计划侧的同一个字段。
 * 转换点收在一处, 丢的东西就是可数的; 散在五处, 就没人能回答
 * "为什么计划表上这一项看起来不一样了"。
 *
 * <h2>它是只读的</h2>
 * 它不持有任何可变状态, 每次 {@code evaluate} / {@code decompose} 都转发给被包装的
 * {@link Intention}。于是"这个意图此刻做得了吗"这件事只有一个答案来源。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不做可行性判断</b>。它只把 {@code PlanningContext} 翻译成
 *       {@link IntentionContext} 然后转发 —— 判断在意图自己手里。
 *       在这里补一层"看起来做不了就别排了"的聪明逻辑, 会让同一条规则存在两处,
 *       而它们在某人改了其中一处之后会开始给出不同的答案;</li>
 *   <li><b>不修改被包装的意图</b>。它是适配器, 不是包装器意义上的"增强"。</li>
 * </ul>
 */
public final class IntentionPlanIntent implements PlanIntent {

    private final Intention intention;

    public IntentionPlanIntent(Intention intention) {
        this.intention = Objects.requireNonNull(intention,
                "要包装的意图不能为空 —— 见 Intention.toPlan() 的默认实现");
    }

    public Intention unwrap() {
        return intention;
    }

    @Override
    public IntentId id() {
        return IntentId.of(intention.id().value());
    }

    @Override
    public String description() {
        return intention.description();
    }

    /**
     * 计划侧问"这件事现在做得了吗" —— 转发给意图, 用一份她的词汇的投影。
     *
     * <p>返回的是 {@link PlanIntent.Feasibility}, 不是
     * {@link com.luxera.companion.human.mind.intention.Feasibility} ——
     * 转换就在这一行发生, 见 {@code Feasibility#toPlan()}。
     */
    @Override
    public PlanIntent.Feasibility evaluate(PlanningContext context) {
        return intention.evaluate(IntentionContext.from(context)).toPlan();
    }

    @Override
    public List<PlanIntent.ActionIntent> decompose(PlanningContext context) {
        return intention.actions(IntentionContext.from(context));
    }

    @Override
    public Set<String> requiredCapabilities() {
        return intention.requiredCapabilities();
    }

    @Override
    public boolean movableInTime() {
        return intention.movableInTime();
    }

    @Override
    public Duration expectedDuration() {
        return intention.expectedDuration();
    }

    @Override
    public String activityType() {
        return intention.activityType();
    }

    public String describe() {
        return "IntentionPlanIntent(" + intention.describe() + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
