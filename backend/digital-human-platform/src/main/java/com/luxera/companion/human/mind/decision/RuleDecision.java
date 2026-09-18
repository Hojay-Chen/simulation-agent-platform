package com.luxera.companion.human.mind.decision;

import com.luxera.companion.human.life.plan.PlanMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.4.7 —— <b>{@link Decision} 的实现</b>。这个名字里的 "Rule" 是给读代码的人看的:
 * 装在这里的决定是<b>确定性引擎</b>产出的, 不是模型产出的。
 *
 * <h2>为什么它是 record 而不是"每类决定一个子类"</h2>
 * 因为决定的形状只有一种 —— 一个原因加上两串产物。为"回复"、"继续"、"改计划"
 * 各写一个子类看似更"面向对象", 实际后果是:
 *
 * <ul>
 *   <li>每个子类都要重复那四个访问器, 而它们的语义完全一样;</li>
 *   <li>{@code instanceof} 链会重新出现在消费方 —— 而 {@code instanceof} 链正是
 *       枚举被消灭之后最容易被误当成"多态"的东西。真正的多态在<b>意图</b>那一层
 *       (那里确实是开放对象), 决定这一层<b>不该</b>有分支;</li>
 *   <li>新增一种"决定"意味着新增一个类, 而它其实只是字段取值的不同组合。</li>
 * </ul>
 *
 * <p>反过来, 如果哪天出现了一种<b>结构化不同</b>的决定(比如"同时做两套互斥的动作"),
 * 那时它应当是本接口的另一个实现 —— 而这条路的开放性是保留的:
 * {@link Decision} 是接口。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不生成 id</b>。{@link #of} 里的 id 由调用方给 —— 一次决定的身份必须由
 *       产生它的那一方决定, 因为那条 id 要同时写进事件、动作命令与计划版本。
 *       在构造函数里自动生成, 会让这三处对不上;</li>
 *   <li><b>不校验动作与能力键是否对得上</b>。那件事在意图的可行性判断里做过了 ——
 *       在这里再做一次只会多一个可能彼此矛盾的答案。</li>
 * </ul>
 */
public record RuleDecision(
        DecisionId id,
        DecisionReason reason,
        List<ActionIntent> actions,
        List<PlanMutation> planMutations) implements Decision {

    public RuleDecision {
        Objects.requireNonNull(id, "决定必须有身份 —— 见 DecisionId 关于溯源的说明");
        Objects.requireNonNull(reason, "决定必须说明理由 —— 没有理由的决定无法被复盘");
        actions = actions == null ? List.of() : List.copyOf(actions);
        planMutations = planMutations == null ? List.of() : List.copyOf(planMutations);
    }

    public static RuleDecision of(DecisionId id, DecisionReason reason,
                                  List<ActionIntent> actions, List<PlanMutation> planMutations) {
        return new RuleDecision(id, reason, actions, planMutations);
    }

    /**
     * 一次"她决定不做"。
     *
     * <p>它<b>不是</b>一个空对象: 没有动作不等于没有决定。这个工厂存在的意义
     * 就是让"她看过了但不动"有一个明确的表达 —— 用例见 {@link DecisionReason#CODE_KEPT_CURRENT}。
     */
    public static RuleDecision nothing(DecisionId id, DecisionReason reason) {
        return new RuleDecision(id, reason, List.of(), List.of());
    }

    /** 一次"她决定继续当前的事" —— 只有计划改动, 没有动作。 */
    public static RuleDecision keeping(DecisionId id, DecisionReason reason,
                                       PlanMutation mutation) {
        return new RuleDecision(id, reason, List.of(),
                mutation == null ? List.of() : List.of(mutation));
    }

    public RuleDecision withAction(ActionIntent action) {
        List<ActionIntent> merged = new ArrayList<>(actions);
        merged.add(Objects.requireNonNull(action, "动作意图不能为空"));
        return new RuleDecision(id, reason, merged, planMutations);
    }

    public RuleDecision withPlanMutation(PlanMutation mutation) {
        List<PlanMutation> merged = new ArrayList<>(planMutations);
        merged.add(Objects.requireNonNull(mutation, "计划改动不能为空"));
        return new RuleDecision(id, reason, actions, merged);
    }

    @Override
    public String toString() {
        return describe();
    }
}
