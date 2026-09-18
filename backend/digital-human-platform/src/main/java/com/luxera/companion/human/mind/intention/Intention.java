package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanIntent;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * V2.2 §3.4.7 —— <b>她想要什么</b>。一个<b>开放对象</b>, 不是一个枚举。
 *
 * <h2>为什么必须开放 —— §1.3 P4 的判据在这里给出的是最清楚的一次</h2>
 * "她有哪几种意图?" 这个问题<b>没有答案</b>, 而且不是"暂时没有": 想要什么是
 * 外部世界的多样性, 不是本设计的内部逻辑。她会想要"把实验做完"、"下周去一趟图书馆"、
 * "把那个人拉黑"、"再睡十分钟" —— 这份清单不可能被写在一份设计文档里,
 * 因为它由她的生活决定, 而生活会一直产生新的条目。
 *
 * <p>用枚举的失败方式很具体, 而且这个代码库已经付出过一次代价:
 * 旧实现里 {@code LifeActivity.type} 那十二个字符串常量, 让"她想做一件设计者
 * 没想到的事"变成了<b>平台发版才能解决的事</b>。所以这里沿着
 * {@code registry.DomainType} + {@code DomainTypeRegistry} 的既有做法:
 *
 * <pre>{@code
 * // 第三方(或未来某个应用)自己写的意图 —— 平台源码里没有这个词
 * @DomainType("lab.finish-experiment")
 * public record FinishExperimentIntention(...) implements Intention { ... }
 *
 * registry.register(FinishExperimentIntention.class);
 * }</pre>
 *
 * <p>注册之后它就能被反序列化、被检索、被 LLM 当作候选之一 —— 全程不需要改
 * {@code human.mind} 里的任何一行。见 {@link IntentionRegistry}。
 *
 * <h2>它与 {@link PlanIntent} 的分工 —— 一张表说清</h2>
 * <table border="1">
 *   <tr><th></th><th>{@code Intention}</th><th>{@code PlanIntent}</th></tr>
 *   <tr><td>是什么</td><td>一个念头</td><td>一件排得进日程的事</td></tr>
 *   <tr><td>做不做得成</td><td>{@link #evaluate}(此刻)</td><td>{@code evaluate}(排得下吗)</td></tr>
 *   <tr><td>它的身份</td><td>{@link IntentionId}(想法本身)</td><td>{@code IntentId}(这件事)</td></tr>
 *   <tr><td>谁能产生它</td><td>LLM、规则、她自己的记忆</td><td>只有通过可行性检查之后</td></tr>
 * </table>
 *
 * <p>两者之间只有一个方向的转换: {@link #toPlan()}。它<b>默认实现已经给了</b> ——
 * 见 {@link IntentionPlanIntent}。实现一个新意图的人只需要关心"我想要什么"和
 * "这做得了吗", 不需要知道计划表长什么样。
 *
 * <h2>它不负责什么</h2>
 * <ul>
 *   <li><b>不决定做不做</b>。它只回答"做得了吗"与"多想做"。要不要现在做, 是
 *       认知环节里确定性引擎的判断 —— 见 §3.4.5 的分工表: <b>决定做什么是确定性引擎,
 *       不是意图自己, 也不是 LLM</b>;</li>
 *   <li><b>不含时间窗口</b>。"什么时候做"是排定之后的事。一个自带时间窗口的意图,
 *       会在重排时无法被挪动 —— 而它本来只是"想做", 不是"约好了"。</li>
 * </ul>
 */
public interface Intention {

    IntentionId id();

    /**
     * 自然语言描述 —— 给她自己看(进 LLM context), 也给界面与行为分析看。
     *
     * <p>例子: {@code "把今天的实验做完"}、{@code "给妈妈回个电话"}。
     * <b>不要写 {@code "RESEARCH_EXPERIMENT"} 这种</b> —— 那等于把枚举搬回来了,
     * 而且会直接进 LLM context, 让模型看见一个它无法理解的词。
     */
    String description();

    /** 她有多想要这件事 —— 见 {@link IntentionPriority} 关于"为什么不是枚举"。 */
    IntentionPriority priority();

    /**
     * 这件事<b>此刻</b>做得了吗。
     *
     * <p>它必须诚实。返回做得了而实际做不成, 会让她的计划表排进一堆注定失败的事,
     * 而症状会被误读成"她意志力差" —— 与 {@code PlanIntent#evaluate} 的同一段警告。
     */
    Feasibility evaluate(IntentionContext context);

    /**
     * 这个念头要靠哪些能力才做得成。
     *
     * <p>它是 {@link #evaluate} 的主要依据, 也是计划重排时"她现在有没有这些能力"的
     * 检查依据。默认空集表示"不需要任何能力" —— 比如"再躺五分钟"。
     */
    default Set<String> requiredCapabilities() {
        return Set.of();
    }

    /**
     * 真要去做的时候, 分成哪几个动作。
     *
     * <p>返回空列表表示这是一个原子意图 —— <b>合法, 不是未实现</b>。
     */
    default List<PlanIntent.ActionIntent> actions(IntentionContext context) {
        return List.of();
    }

    /**
     * 做起来时是哪一类活动 —— 一个<b>活动类型名</b>({@code life.activity.*})。
     *
     * <p>默认值与 {@code PlanIntent#activityType()} 的默认值相同, 且<b>两处都写死</b>。
     * 这是刻意的重复: 把那个常量提升成一个跨包的公共常量, 会让
     * {@code human.mind.intention} 与 {@code human.life.plan} 多一条编译期依赖,
     * 而这条依赖只为省一个字符串。重复的代价是"两处可能写岔", 所以这里明写出来。
     */
    default String activityType() {
        return "life.activity.other";
    }

    /** 这件事能不能被重排器挪时间 —— 见 {@code PlanIntent#movableInTime()}。 */
    default boolean movableInTime() {
        return true;
    }

    /** 预计需要多久。 */
    default Duration expectedDuration() {
        return Duration.ofMinutes(30);
    }

    /**
     * 把它翻译成计划侧认识的形式 —— <b>两个层之间唯一的转换点</b>。
     *
     * <p>给了默认实现, 于是实现一个新意图的人不必知道计划表长什么样。
     * 要覆盖它是一件很罕见的事, 而且覆盖的人应当先问自己"为什么我的意图
     * 需要知道计划侧的细节"。
     */
    default PlanIntent toPlan() {
        return new IntentionPlanIntent(this);
    }

    /** 她会不会用这句话说起这件事 —— 日志与诊断用。 */
    default String describe() {
        return "[" + priority().describe() + "] " + description();
    }
}
