package com.luxera.companion.human.mind.cognition;

import com.luxera.companion.human.mind.attention.AttendedPercept;
import com.luxera.companion.human.mind.intention.Feasibility;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionPriority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.4.5 —— <b>确定性引擎: 决定做什么</b>。分工表上的第一行就是它。
 *
 * <h2>它为什么必须存在, 而且必须确定性</h2>
 * 分工表把"决定做什么"交给确定性引擎、把"提出候选"交给 LLM, 这两件事的分界不是
 * 审美, 而是这个平台能不能被研究的前提:
 *
 * <ul>
 *   <li><b>"她为什么没回这条消息"必须是一个可复现的答案。</b>如果这个答案来自一个模型,
 *       那它每次都不同 —— 于是任何一次"她这次怎么没回"的复盘都只能重新问一遍模型,
 *       而那问出来的是<b>一个新的答案</b>, 不是当初那个;</li>
 *   <li><b>门槛必须可调参。</b>"深夜勿扰的消息她不回"这条规则, 如果藏在模型的权重里,
 *       就没法回答"把阈值从 0.35 调到 0.5 她会怎样" —— 而这正是这个平台要做的实验;</li>
 *   <li><b>它要能在没有网络的机器上跑。</b>一个连不上模型就完全瘫痪的 agent,
 *       不是一个可以长期运行的 agent。</li>
 * </ul>
 *
 * <p>所以本类里没有任何 I/O、没有随机、没有时钟。全部输入来自
 * {@link ReasoningContext}, 全部输出是一个 {@link ReasoningResult} ——
 * <b>同一个 context 问一万次, 答案一模一样</b>, 这条性质由
 * {@code mind/MindDecisionPlannerTest} 直接断言。
 *
 * <h2>它是 {@link ReasoningEngine} 的一个实现 —— 这一点值得说清楚</h2>
 * 它<b>不是</b>一个"绕过 LLM 的特别通道", 而是与 LLM 并列的一个实现。
 * 于是 {@code human.mind.decision.DecisionEngine} 那一侧根本不知道
 * 这次的想法是规则来的还是模型来的 —— 它收到的都是 {@link ReasoningResult}。
 *
 * <p>这个对称性有一个直接好处: <b>换掉 LLM 不会改变她做决定的方式, 只会改变
 * 她想得到什么</b>。要验证这句话, 只需要把两个引擎各跑一遍同一批 context,
 * 然后断言"下游的决定路径完全相同"。
 *
 * <h2>它怎么选</h2>
 * <ol>
 *   <li>把候选逐个对着处境算可行性({@link Intention#evaluate})。
 *       <b>不可行的先出局</b> —— 不是降权, 是出局: 一个此刻做不了的念头不该
 *       在排序里占位置, 否则它会挤掉一个做得成的;</li>
 *   <li>在剩下的里面按 {@link IntentionPriority} 降序排。
 *       <b>平局时按描述字典序</b> —— 排序必须是一个全序, 否则"她有时选 A 有时选 B"
 *       会被当成性格, 而那其实是一个没定义的行为;</li>
 *   <li>取最前面的一个, 但把前三个都写进观察里 —— "她考虑过另外两件事"这句话
 *       在复盘时非常有用, 而它在数据上不加成本。</li>
 * </ol>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不生成候选</b>。候选从 {@link ReasoningContext#candidates()} 来。
 *       一个自己凭空想出念头的"确定性引擎"是自相矛盾的 —— 想出新东西不是规则能做的
 *       (那正是 LLM 的位置)。本类的职责是<b>选</b>, 不是<b>想</b>;</li>
 *   <li><b>不读感知正文去做判断</b>。它看的是 {@link AttendedPercept#attentionScore()}
 *       与处境, 不是消息内容 —— 与注意力那一段同一条纪律。看了正文,
 *       就等于把"她认不认识这个人"这件事重新交给了文本匹配;</li>
 *   <li><b>不改计划表</b>。计划怎么改是 {@code Decision} 的事。在这里顺手调一下时间窗,
 *       会让"计划为什么变了"有两个可能的成因。</li>
 * </ul>
 */
public final class MindDecisionPlanner implements ReasoningEngine {

    /** 引擎名 —— 见 {@link ReasoningEngine#engineId()} 关于"名字要说性质而不是品牌"。 */
    public static final String ENGINE_ID = "mind.rule-planner";

    /**
     * 观察里最多写几个"她考虑过但没选"的念头。
     *
     * <p>写成常量而不是 {@code 3} 散在代码里, 是因为它会被调: 一个很长的候选清单
     * 全写进观察会让日志变成噪声, 而噪声会让真正有用的那几条被淹没。
     */
    public static final int OBSERVED_REJECTIONS = 3;

    public MindDecisionPlanner() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public boolean deterministic() {
        return true;
    }

    @Override
    public ReasoningResult reason(ReasoningContext context) {
        Objects.requireNonNull(context, "认知上下文不能为空 —— 见 ReasoningContext");

        List<Intention> feasible = new ArrayList<>();
        List<String> observations = new ArrayList<>();

        for (Intention intention : context.candidates()) {
            Feasibility feasibility = intention.evaluate(context.situation());
            if (feasibility.feasible()) {
                feasible.add(intention);
            } else {
                observations.add("「" + intention.description() + "」此刻做不了: "
                        + feasibility.reason());
            }
        }

        if (feasible.isEmpty()) {
            return ReasoningResult.deterministic(ENGINE_ID, explainNothing(context), List.of(),
                    observations);
        }

        feasible.sort(priorityOrder());

        Intention chosen = feasible.get(0);
        observations.addAll(describeRejections(feasible));

        return ReasoningResult.deterministic(ENGINE_ID,
                explainChoice(context, chosen, feasible.size()),
                List.of(chosen),
                observations);
    }

    /**
     * 从前几个候选里选一个 —— 给"她想同时做两件事"的场景用(比如计划重排要用到
     * "第二想要的是什么")。
     *
     * <p>它返回的是<b>同一个全序的前 N 个</b>, 而不是"N 个独立的决定" ——
     * 这一点必须写清楚, 否则调用方会以为它们互不相干。
     */
    public List<Intention> rank(ReasoningContext context, int limit) {
        List<Intention> feasible = new ArrayList<>();
        for (Intention intention : context.candidates()) {
            if (intention.evaluate(context.situation()).feasible()) {
                feasible.add(intention);
            }
        }
        feasible.sort(priorityOrder());
        return limit <= 0 || feasible.size() <= limit
                ? List.copyOf(feasible)
                : List.copyOf(feasible.subList(0, limit));
    }

    // ─────────────────────────── 内部的确定性 ───────────────────────────

    /**
     * 全序比较器 —— <b>平局必须被打断</b>。
     *
     * <p>只按优先级排会留下平局, 而平局的胜者取决于 {@code List} 的迭代顺序,
     * 那又取决于候选是怎么被塞进来的。结果是同一个 agent 在同一个处境下
     * 有时选 A 有时选 B —— 而<b>一个随机的行为会被误读成性格</b>,
     * 这是行为分析里最难查的一类问题。
     *
     * <p>用描述做最后的裁决键是一个刻意的选择: 它稳定、可读, 而且它<b>不是随机数</b>。
     *
     * <p><b>它是 {@code public} 的, 这是一条设计决定</b>: {@code DecisionEngine} 也用它。
     * 若两边各写一份排序规则, "认知选中的那个"与"决定执行的那个"迟早会在某个平局上
     * 分道扬镳 —— 而那种 bug 的现象是"她想了 A 却做了 B", 从日志上看完全无迹可寻。
     * 一份规则、一个公开入口, 是让这两步永远一致的唯一办法。
     */
    public static Comparator<Intention> priorityOrder() {
        return Comparator
                .comparing(Intention::priority, Comparator.<IntentionPriority>reverseOrder())
                .thenComparing(Intention::description)
                .thenComparing((Intention i) -> i.id().value());
    }

    private static String explainChoice(ReasoningContext context, Intention chosen, int feasibleCount) {
        return "此刻有 " + context.attended().size() + " 件事被她注意到, "
                + context.candidates().size() + " 个念头在候选里, 其中 " + feasibleCount
                + " 个现在做得了。她决定先做「" + chosen.description() + "」—— "
                + "它是当下优先级最高的那一件(" + chosen.priority().describe() + ")。";
    }

    private static String explainNothing(ReasoningContext context) {
        if (context.candidates().isEmpty()) {
            return "没有什么要做的 —— " + (context.attended().isEmpty()
                    ? "她此刻没注意到任何值得反应的事。"
                    : "注意到了 " + context.attended().size() + " 件事, 但都不需要动作。");
        }
        return "有 " + context.candidates().size() + " 个念头, 但此刻一个也做不了。";
    }

    private static List<String> describeRejections(List<Intention> ranked) {
        List<String> out = new ArrayList<>();
        int limit = Math.min(OBSERVED_REJECTIONS, ranked.size() - 1);
        for (int i = 1; i <= limit; i++) {
            Intention other = ranked.get(i);
            out.add("她也想过「" + other.description() + "」(" + other.priority().describe()
                    + "), 但排在后面");
        }
        return out;
    }

    /** 供日志与诊断用 —— 一句话说清这个引擎是什么。 */
    public String describe() {
        return "MindDecisionPlanner[确定性: 无 I/O、无时钟、无随机; 全序排序]";
    }

    @Override
    public String toString() {
        return describe();
    }
}
