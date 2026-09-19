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
     *
     * <h2>意图返回 null 时怎么走</h2>
     * 不能直接 {@code .toPlan()} —— 那会在本行抛一个
     * {@code NullPointerException}, 而栈顶指向的是适配器, 不是那个没实现
     * {@code evaluate} 的意图。于是"某个意图忘了给判定"看起来像"适配器坏了",
     * 排查要从这里往回翻三层。
     *
     * <p>按 {@code Feasibility#fromPlan(null)} 已经定下的语义处理:
     * <b>null 视为"从来没判过"</b>, 换成一条指名道姓的不可行。
     * 两个方向对 null 的看法必须一致 —— 否则"没判过"这件事
     * 从 mind 侧过来会崩、从 plan 侧过来会被判成不可行, 而它是同一件事。
     */
    @Override
    public PlanIntent.Feasibility evaluate(PlanningContext context) {
        // 用全限定名: 本类实现了 PlanIntent, 于是简单名 Feasibility 在这里
        // 先解析到继承来的 PlanIntent.Feasibility, 而不是同包的这个。
        com.luxera.companion.human.mind.intention.Feasibility verdict =
                intention.evaluate(IntentionContext.from(context));
        if (verdict == null) {
            return PlanIntent.Feasibility.no("这件事没有给出可行性判定 —— 意图 "
                    + intention.id().value() + " 的 evaluate 返回了 null。"
                    + "这与'判过说不行'不是同一件事: 前者是没实现, 后者是业务结论");
        }
        return verdict.toPlan();
    }

    /**
     * 拆成哪几步 —— 转发给意图。
     *
     * <p>{@code actions} 返回 null 与 {@code evaluate} 返回 null 的处理<b>刻意不同</b>:
     * 这里抛而不是翻译。因为"拆不出步骤"有一个合法的表达 —— 空列表
     * (见 {@code Intention#actions}), 所以 null <b>不携带任何业务含义</b>,
     * 它只可能是编程错误。而放它过去的后果是它一路走到
     * {@code DecisionEngine} 的 {@code actions().isEmpty()} 上才炸,
     * 那时现场已经离肇事处很远了。
     */
    @Override
    public List<PlanIntent.ActionIntent> decompose(PlanningContext context) {
        List<PlanIntent.ActionIntent> steps = intention.actions(IntentionContext.from(context));
        return Objects.requireNonNull(steps, "意图 " + intention.id().value()
                + " 的 actions() 返回了 null —— 拆不出步骤请返回空列表, "
                + "null 会被 DecisionEngine 读成一次崩溃而不是'这一个是原子意图'");
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
