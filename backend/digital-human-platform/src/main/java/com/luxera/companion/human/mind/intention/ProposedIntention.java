package com.luxera.companion.human.mind.intention;

import com.luxera.companion.human.life.plan.PlanIntent;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * V2.2 §3.4.7 —— <b>一个"通用意图": 由外部(通常是 LLM)提出来的候选</b>。
 *
 * <h2>它为什么必须存在</h2>
 * §3.4.5 的分工表里, "提出候选计划"是 <b>LLM</b> 的工作。而 LLM 的输出是一个字符串 ——
 * 它说"我想去实验室把实验做完", 但它在编译期不可能实现某一个 Java 接口。
 * 于是需要这么一个<b>数据形状</b>把那段文字接住:
 *
 * <pre>{@code
 * // LLM 说的话 → 一个意图对象 → 一个计划项
 * "我想去实验室把实验做完" → new ProposedIntention(描述, 优先级, 需要的能力) → PlanIntent
 * }</pre>
 *
 * <p>没有它, 实现者只有两条路: 要么给每个可能的意图写一个类(那等于把枚举搬回来,
 * 只是换成了类名), 要么让 LLM 的输出直接变成计划项(那等于<b>跳过了可行性检查</b> ——
 * 而 §3.4.5 明说"校验计划排不排得下"必须是确定性引擎做的事)。
 *
 * <h2>它与第三方自定义意图的关系</h2>
 * 两者不是替代关系, 而是<b>两条并存的路</b>:
 * <ul>
 *   <li>一个平台或应用有<b>明确的、要反复用</b>的意图(比如"完成实验"), 就写一个类,
 *       标 {@code @DomainType}, 注册进 {@link IntentionRegistry} —— 它有自己的
 *       可行性规则与动作拆分;</li>
 *   <li>一个只出现一次的、临时说出来的意图("今天想早点睡"), 用本类接住就够了,
 *       它的可行性判断是通用的(需要的能力都在吗)。</li>
 * </ul>
 *
 * <p>判断标准是<b>它会不会第二次出现</b>, 而不是"它重要不重要"。给每个一次性念头写一个类,
 * 会让 {@code mind} 包变成一堆只有几行的类; 而把反复出现的事都用本类接住,
 * 会让它的可行性判断永远停在"能力够不够"这一层, 丢失那些只有专门实现才知道的约束。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不解析自然语言</b>。{@link #description()} 就是一句人话, 本类不做任何
 *       从话里猜字段的事 —— 那些字段由提出它的那一方(LLM 的调用者)填好。
 *       在这里加一个"从描述里提取时间"的正则, 会让一个可测试的接口变成一段
 *       谁都说不清行为的启发式;</li>
 *   <li><b>不验证能力是否存在</b>。它只说"我需要什么", "有没有"由
 *       {@link IntentionContext} 回答 —— 这正是 {@link #evaluate} 做的事。</li>
 * </ul>
 */
public record ProposedIntention(
        IntentionId id,
        String description,
        IntentionPriority priority,
        Set<String> requiredCapabilities,
        String activityType,
        Duration expectedDuration,
        boolean movableInTime,
        List<PlanIntent.ActionIntent> actions) implements Intention {

    public ProposedIntention {
        Objects.requireNonNull(id, "意图必须有身份 —— 否则" + "她为什么没做成这件事"
                + "在日志里找不到主语");
        Objects.requireNonNull(description, "意图必须有一句人话 —— 它会进 LLM context");
        priority = priority == null ? IntentionPriority.DEFAULT : priority;
        requiredCapabilities = requiredCapabilities == null
                ? Set.of() : Set.copyOf(requiredCapabilities);
        activityType = activityType == null ? "life.activity.other" : activityType;
        expectedDuration = expectedDuration == null ? Duration.ofMinutes(30) : expectedDuration;
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (description.isBlank()) {
            throw new IllegalArgumentException(
                    "意图的描述不能是空白 —— 一个说不出自己在想什么的意图无法被解释, "
                            + "也无法被排进计划表");
        }
    }

    /** 最常用的一种: 只要一句话和一个优先级。 */
    public static ProposedIntention of(String id, String description, IntentionPriority priority) {
        return new ProposedIntention(IntentionId.of(id), description, priority,
                Set.of(), null, null, true, List.of());
    }

    /** 需要能力的一种 —— {@link #evaluate} 会据此判断此刻做不做得了。 */
    public static ProposedIntention needing(String id, String description, IntentionPriority priority,
                                            Set<String> capabilities) {
        return new ProposedIntention(IntentionId.of(id), description, priority,
                capabilities, null, null, true, List.of());
    }

    @Override
    public Feasibility evaluate(IntentionContext context) {
        if (context == null) {
            return Feasibility.no("不知道该在什么处境下判断");
        }
        if (requiredCapabilities.isEmpty()) {
            return Feasibility.yes("不需要任何外部能力, 想做就能做");
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String capability : requiredCapabilities) {
            if (!context.can(capability)) {
                missing.add(capability);
            }
        }
        if (missing.isEmpty()) {
            return Feasibility.yes("需要的 " + requiredCapabilities.size() + " 项能力现在都能用");
        }
        return Feasibility.no("她现在用不了这些能力", List.copyOf(missing));
    }

    /**
     * 把这个意图拆成此刻能执行的动作。
     *
     * <p><b>这个覆盖是必需的, 而且它很容易被漏掉。</b>record 自动生成的访问器是
     * {@code actions()}(无参), 而 {@link Intention} 上的方法叫
     * {@code actions(IntentionContext)}(有参)—— 两者<b>签名不同</b>, 所以前者
     * 并不覆盖后者。漏掉这个方法的后果极其安静: 编译通过、运行不报错,
     * 只是<b>每一个由本类表达的意图都永远拆不出动作</b>, 于是她所有的决定都变成
     * "排进日程"而永远不会真的做任何事。这个 bug 不会出现在日志里, 只会出现在
     * "她怎么什么都不做"这个现象里。
     *
     * <p>之所以不把 record 的组件名改掉(比如叫 {@code steps}), 是因为
     * {@code actions} 正是它的语义。名字不该为了绕开一个必须记住的覆盖而变丑 ——
     * 该做的是把这件事写在这里。
     */
    @Override
    public List<PlanIntent.ActionIntent> actions(IntentionContext context) {
        return actions;
    }

    public ProposedIntention withActions(List<PlanIntent.ActionIntent> newActions) {
        return new ProposedIntention(id, description, priority, requiredCapabilities, activityType,
                expectedDuration, movableInTime, newActions);
    }

    public ProposedIntention withPriority(IntentionPriority newPriority) {
        return new ProposedIntention(id, description, newPriority, requiredCapabilities, activityType,
                expectedDuration, movableInTime, actions);
    }
}
