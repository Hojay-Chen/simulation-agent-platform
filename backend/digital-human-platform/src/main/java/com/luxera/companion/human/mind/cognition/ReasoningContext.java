package com.luxera.companion.human.mind.cognition;

import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.memory.MemoryRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * V2.2 §3.4.5 —— <b>认知这一步能看到的全部东西</b>。
 *
 * <h2>为什么它是显式的参数, 而不是"让引擎自己去拿"</h2>
 * 与 {@code PlanningContext} 的同一段理由, 而在这个环节上更尖锐:
 * 认知这一步<b>可能由一个 LLM 完成</b>, 而 LLM 的回答必须能被复现。
 * 如果引擎可以自己去查记忆、自己去问世界"现在下雨吗", 那么同一个 agent
 * 在同一时刻问两次会得到两个答案, 而<b>"她当时为什么这么想"就没有答案了</b> ——
 * 那正是行为分析最不能丢的东西。
 *
 * <p>把输入收进一个不可变对象, "给她这些, 她会怎么想"就变成了一个可以反复问、
 * 可以离线问、可以在测试里问的<b>问句</b>。测试里那个"她一定想回这句话"的断言,
 * 依赖的正是这个性质。
 *
 * <h2>它刻意不包含什么 —— 这一段是本类的重点</h2>
 * <ul>
 *   <li><b>不含 {@code EventFabric}</b>。认知不订阅事件、不发事件。她能做的动作
 *       只有一种出口: {@code Decision} → {@code ActionCommand}(§3.4.2)。
 *       把总线放进来, 就等于给了认知一条绕过决策直接动手的路, 而那条路一旦存在,
 *       迟早会被走;</li>
 *   <li><b>不含任何世界对象</b>。没有手机、没有环境、没有地点对象 ——
 *       只有 {@link #situation} 里那句已经翻译好的投影。这不是洁癖, 见 §3.4.2 的三条禁止写法;</li>
 *   <li><b>不含"她上一次想了什么"</b>。上一步的结论如果还有效, 它应该已经在
 *       {@link #goals} 或 {@link #recalled} 里了。放一个"上一轮的结果"进来会让
 *       认知变成递推的, 而递推的链子没法从中间开始回放。</li>
 * </ul>
 *
 * @param now           仿真时刻。所有"刚发生"的判断都拿它比 —— 不许读系统时钟
 * @param attended      被注意力放行的感知(§3.4.4 的产物)。<b>没进这里的就是她没注意到的</b> ——
 *                      认知看不到这一批之外的东西, 这正是"她没看见"与"她不记得"能分开的原因
 * @param candidates    待考虑的意图。它可能来自规则、来自计划表、也可能来自 LLM 的上一轮提议
 * @param recalled      检索出来的记忆。已经按重要度排好序, 认知不必再排一次
 * @param goals         她此刻在追的目标(一句话一条)
 * @param situation     她的处境投影 —— 见 {@link IntentionContext}。
 *                      它与"世界现在什么样"是两回事: 后者由别人查好、翻译好才进来
 * @param attributes    零散事实的开放袋子。加字段会逼每个引擎跟着改, 而这个袋子
 *                      让"某个引擎需要一个新事实"不必动平台源码
 */
public record ReasoningContext(
        Instant now,
        List<AttendedPercept> attended,
        List<Intention> candidates,
        List<MemoryRecord> recalled,
        Set<String> goals,
        IntentionContext situation,
        Map<String, Object> attributes) {

    public ReasoningContext {
        Objects.requireNonNull(now, "认知必须带仿真时刻 —— 不许读系统时钟");
        Objects.requireNonNull(situation, "认知必须看得到她的处境 —— 否则可行性判断没有依据");
        attended = attended == null ? List.of() : List.copyOf(attended);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        recalled = recalled == null ? List.of() : List.copyOf(recalled);
        goals = goals == null ? Set.of() : Set.copyOf(goals);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 一个"什么都没发生"的处境 —— 用于"她醒着但没什么要注意的"这类空转。 */
    public static ReasoningContext quiet(Instant now, IntentionContext situation) {
        return new ReasoningContext(now, List.of(), List.of(), List.of(), Set.of(), situation, Map.of());
    }

    public ReasoningContext withCandidates(List<Intention> newCandidates) {
        return new ReasoningContext(now, attended, newCandidates, recalled, goals, situation, attributes);
    }

    public ReasoningContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new ReasoningContext(now, attended, candidates, recalled, goals, situation, merged);
    }

    public Optional<Object> attribute(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(attributes.get(key));
    }

    /**
     * 她被"叫醒"了吗 —— 有没有任何东西值得想一下。
     *
     * <p>做成方法而不是让每个引擎自己判空: 这个判断如果写错, 症状是
     * <b>她在没人找她时也每轮都回一句话</b>, 而那看起来像"她很粘人",
     * 不像一个 bug。收成一处, 它就只可能错一次。
     */
    public boolean hasAnythingToThinkAbout() {
        return !attended.isEmpty() || !candidates.isEmpty();
    }

    /** 一句话描述她此刻的处境 —— 进日志, 也进 LLM 的 system 段。 */
    public String summarize() {
        return "此刻 " + now + "; 注意到 " + attended.size() + " 件事; 想着 "
                + candidates.size() + " 个念头; 处境: " + situation.describe();
    }

    @Override
    public String toString() {
        return summarize();
    }
}
