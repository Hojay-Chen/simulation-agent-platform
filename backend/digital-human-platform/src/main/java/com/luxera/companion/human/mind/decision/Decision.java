package com.luxera.companion.human.mind.decision;

import com.luxera.companion.human.life.plan.PlanMutation;

import java.util.List;

/**
 * V2.2 §3.4.7 —— <b>她决定做什么</b>。一个<b>结果对象</b>, 不是枚举。
 *
 * <h2>"不是枚举"这条要求到底在防什么</h2>
 * 旧实现把"她决定做什么"表达成一档枚举({@code DO_NOTHING} / {@code REPLY} /
 * {@code DEFER} / ...)。它的失败方式不是"不够优雅", 而是三件具体的事:
 *
 * <ol>
 *   <li><b>它答不了"做什么"之外的问题。</b>"回复谁、回什么、要不要顺便改计划"
 *       全都塞不进一个常量, 于是实现只能在外面再挂一堆字段 —— 那时"决定"这件事
 *       就散在五个地方了;</li>
 *   <li><b>它把"继续做当前的事"和"什么都没发生"混成一档。</b>
 *       §3.5.6 为此专门写了 {@code KeepActive} 的理由: 两者必须能分开,
 *       否则"她没处理这个事件"与"她处理了, 结论是继续"看起来一样;</li>
 *   <li><b>第三方加不了。</b>接入一个新应用, 她想做的事就有了新种类,
 *       而枚举只能由平台作者改。</li>
 * </ol>
 *
 * <p>所以本接口的形状是"一个原因 + 一串动作 + 一串计划改动", 而它的实现
 * (见 {@link RuleDecision}) 是一个普通记录。第三方要实现自己的决定类型是允许的,
 * 只是没有必要 —— 决定是<b>结果</b>, 结果只有一种形状。
 *
 * <h2>它是 Mind 唯一的出口 —— 这一条是 §3.4.2 的落点</h2>
 * <pre>
 *   Mind → Decision → ActionCommand → ActionFabric → Phone → Application
 * </pre>
 *
 * <p>Mind 里没有任何一条不经过 {@code Decision} 就碰到世界的路:
 * 它拿不到 Phone、拿不到 Application、拿不到环境。而本接口<b>不提供</b>
 * "顺手执行一下"的方法, 也不持有总线 —— 于是那条捷径在类型上不存在。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不执行</b>。它是一份"要做什么"的清单, 不是执行器。装配成
 *       {@code ActionCommand} 并发送是别人的事 —— 那个人需要 actorId、幂等键、
 *       发出时刻, 而这三样都不是"决定"的属性;</li>
 *   <li><b>不含措辞</b>。"说什么"由 {@link LanguageEngine} 在<b>本对象已经存在之后</b>
 *       才生成 —— 见 §3.4.5 的分工表: <b>先有"她决定要说"这个值, 模型才被调用。</b>
 *       把措辞放进决定里, 会让"她已经决定了"这件事的成立时刻取决于一次网络往返。</li>
 * </ul>
 */
public interface Decision {

    DecisionId id();

    DecisionReason reason();

    /**
     * 要执行的动作。可以为空 —— <b>空不是失败, 是"她决定不做"</b>。
     *
     * <p>最常见的空动作决定就是 {@code KeepActive}: 她收到了消息, 看过了,
     * 决定继续写作业。这条决定必须被记录, 否则"她没回"与"她没看见"
     * 在日志里长得一模一样。
     */
    List<ActionIntent> actions();

    /**
     * 对计划表的修改。可以为空(这次决定不改计划)。
     *
     * <p>它们由 Mind 交给 {@code PlanBoard.apply(...)}, <b>不是在这里被应用的</b> ——
     * 决定是一个纯值, 应用它是别人的事。这样"同一个决定对象能不能被应用两次"
     * 才有确定答案(不能, 见 {@code PlanMutation} 的版本语义)。
     */
    List<PlanMutation> planMutations();

    default boolean hasActions() {
        return !actions().isEmpty();
    }

    default boolean changesPlan() {
        return !planMutations().isEmpty();
    }

    /**
     * 这次决定要不要说点什么 —— 决定"要不要调用语言引擎"的那一个判断。
     *
     * <p>做成方法而不是让调用方自己看 {@code actions()} 里有没有某种能力键:
     * 后者需要知道具体的能力键叫什么, 而那正是 {@code human/} 里不许出现的东西。
     */
    default boolean needsWording() {
        return hasActions();
    }

    default String describe() {
        return id().describe() + " " + reason().describe()
                + " → 动作 " + actions().size() + " 个, 计划改动 " + planMutations().size() + " 处";
    }
}
