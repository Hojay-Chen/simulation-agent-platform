package com.luxera.companion.human.mind.cognition;

import com.luxera.companion.human.mind.intention.Intention;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.4.5 —— <b>认知这一步的产出: 她理解成了什么, 以及她想要什么</b>。
 *
 * <h2>它为什么把"理解"与"意图"放在同一个对象里</h2>
 * 因为两者是<b>一次思考的两个面</b>, 而分开会让"她为什么会想要这个"变成两个对象的
 * 关联问题。{@link #understanding()} 是她对自己处境的解释("那个人连着发了三条"),
 * {@link #intentions()} 是这个解释的结论("她想回一句")。
 * 日志与 LLM context 要的正是这两句连在一起 —— 只留结论的话,
 * "她为什么这么做"就答不上来。
 *
 * <h2>它是<b>候选</b>, 不是决定 —— 这一点是 §3.4.5 分工表的落点</h2>
 * 分工表说: <b>决定做什么是确定性引擎, 提出候选计划才是 LLM</b>。所以本对象里
 * 装的<b>永远是候选</b>, 无论是谁产出的:
 *
 * <ul>
 *   <li>{@code MindDecisionPlanner}(确定性)产出的也是候选 —— 它选出"最该做的那一个"
 *       之后仍然走同一条路;</li>
 *   <li>LLM 产出的更是候选, 而且它<b>可能产出多个互相冲突的</b>("回她"和"先写完作业")。</li>
 * </ul>
 *
 * <p>于是"决定"这件事只发生在一个地方({@code human.mind.decision.DecisionEngine}),
 * 而"谁提的候选"从一个架构问题退化成一个实现细节 —— 这正是那张分工表想要的效果:
 * <b>换掉 LLM 不会改变她做决定的方式, 只会改变她想得到什么。</b>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不含可行性</b>。可行性是<b>对着处境</b>算出来的, 而处境会变。
 *       一个"出生时就带着可行性"的结果对象, 会让"十分钟前她说做不了的事,
 *       现在做得了了"无法表达 —— 因为那个值已经写死了。
 *       需要时调 {@link Intention#evaluate}, 那是一个问句, 不是一个字段;</li>
 *   <li><b>不含动作</b>。要执行什么由 {@code Decision} 决定 ——
 *       从"想要什么"到"做什么"之间那一步(可行性、优先级、计划表约束)
 *       正是确定性引擎的工作, 不能在这里被跳过。</li>
 * </ul>
 *
 * @param understanding 她对自己处境的一句人话解释。会进日志与 LLM context
 * @param intentions    候选意图。空列表表示"她想清楚之后觉得没什么要做的" ——
 *                      <b>这是合法的, 不是失败</b>: 一个人大部分时候什么也不做
 * @param observations  推理过程中值得留下的观察("那个人今天已经第三次找她了")。
 *                      它们不进决定, 但进日志 —— 事后复盘靠的就是这些
 * @param engineId      是谁想出来的。同一个结果对象可能由规则引擎或 LLM 产出,
 *                      而在排查"她今天怎么这么怪"时, 第一件要问的事就是"这话谁说的"
 * @param deterministic 这个结果是不是纯规则的(没有抽签、没有模型)
 */
public record ReasoningResult(
        String understanding,
        List<Intention> intentions,
        List<String> observations,
        String engineId,
        boolean deterministic) {

    public ReasoningResult {
        understanding = understanding == null ? "" : understanding;
        intentions = intentions == null ? List.of() : List.copyOf(intentions);
        observations = observations == null ? List.of() : List.copyOf(observations);
        engineId = engineId == null || engineId.isBlank() ? "unknown" : engineId;
    }

    /** 最常见的一种: 一句理解 + 若干候选。 */
    public static ReasoningResult of(String engineId, String understanding,
                                     List<Intention> intentions) {
        return new ReasoningResult(understanding, intentions, List.of(), engineId, false);
    }

    /** 确定性引擎的产出 —— 见 {@code MindDecisionPlanner}。 */
    public static ReasoningResult deterministic(String engineId, String understanding,
                                                List<Intention> intentions,
                                                List<String> observations) {
        return new ReasoningResult(understanding, intentions, observations, engineId, true);
    }

    /** 她想清楚了, 但什么都不打算做。 */
    public static ReasoningResult nothing(String engineId, String understanding) {
        return new ReasoningResult(understanding, List.of(), List.of(), engineId, false);
    }

    public ReasoningResult withObservation(String observation) {
        List<String> merged = new ArrayList<>(observations);
        merged.add(Objects.requireNonNull(observation, "观察不能为空"));
        return new ReasoningResult(understanding, intentions, merged, engineId, deterministic);
    }

    public ReasoningResult withIntention(Intention intention) {
        List<Intention> merged = new ArrayList<>(intentions);
        merged.add(Objects.requireNonNull(intention, "候选意图不能为空"));
        return new ReasoningResult(understanding, merged, observations, engineId, deterministic);
    }

    public ReasoningResult withUnderstanding(String newUnderstanding) {
        return new ReasoningResult(newUnderstanding, intentions, observations, engineId, deterministic);
    }

    /** 有没有任何值得走下一步的候选。 */
    public boolean hasCandidates() {
        return !intentions.isEmpty();
    }

    public String describe() {
        return "[" + engineId + (deterministic ? "/确定性" : "/非确定性") + "] " + understanding
                + " → 候选 " + intentions.size() + " 个";
    }

    @Override
    public String toString() {
        return describe();
    }
}
