package com.luxera.companion.human.mind.decision;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanPriority;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.cognition.ReasoningResult;
import com.luxera.companion.human.mind.intention.Feasibility;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.4.5 —— <b>"决定做什么"的那台确定性引擎</b>。
 *
 * <h2>它在 §3.4.5 分工表里的位置</h2>
 * <table border="1">
 *   <tr><th>这件事</th><th>谁做</th><th>在哪</th></tr>
 *   <tr><td>决定做什么</td><td><b>确定性引擎</b></td><td><b>本类</b></td></tr>
 *   <tr><td>怎么说</td><td>LLM</td><td>{@link LanguageEngine} —— 在<b>本类之后</b>被调用</td></tr>
 *   <tr><td>提出候选计划</td><td>LLM</td><td>外面 —— 产出 {@link Intention} 塞进候选</td></tr>
 *   <tr><td>校验计划</td><td>PlanValidator</td><td>{@code human.life.plan} 侧 —— 它拿到的正是本类产出的改动</td></tr>
 * </table>
 *
 * <p>这张表是一条硬约束, 不是描述。它的意思是: <b>本类里不许出现模型调用</b>。
 * 一旦这里可以问模型"她要不要回", 分工表就塌了 —— 而塌掉之后不会立刻出问题,
 * 只会在某次模型抽风时表现为"她今天做了三件互相矛盾的事", 且无法复现。
 *
 * <h2>为什么 {@link #decide} 是纯函数</h2>
 * 因为它<b>不读时钟、不写状态、不碰总线</b>: 同一个 {@code (ReasoningResult, IntentionContext, PlanItem)}
 * 进来, 出去的决定逐字段相同。这条性质有三个具体后果:
 *
 * <ul>
 *   <li><b>可回溯</b>。事后问"她当时为什么这么做", 答案是重跑一次 {@code decide}
 *       —— 而不是一句"当时模型那么说的";</li>
 *   <li><b>可测试</b>。测试里不需要打桩任何外部东西, 断言的是决定本身;</li>
 *   <li><b>可回放</b>。仿真重放到那一刻时她会做同样的选择 —— 这是"同一个人"
 *       这件事在工程上唯一可验证的含义。</li>
 * </ul>
 *
 * <p>唯一的例外是 {@link #announce}: 它把"她做过决定"这件事投回事件流, 于是
 * 行为分析能看到她做了什么, 而不是只看到世界对她做了什么。它与
 * {@code AttentionShifted} 是同一种做法 —— <b>先决定, 再记账</b>, 记账不是决定的一部分。
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不调用语言引擎</b>。本类只产出 {@link Decision}; "说成什么话"是
 *       {@link LanguageEngine} 的事, 且必须在<b>本类返回之后</b>才发生。
 *       本类连 {@code LanguageEngine} 这个类型都没有 import —— 顺序纪律在这里
 *       不是注释里的约定, 是<b>依赖图上不存在的边</b>;</li>
 *   <li><b>不执行动作</b>。产出的 {@link ActionIntent} 只是清单。装配
 *       {@code ActionCommand} 并发出的是别人 —— 那一步需要 actorId、幂等键与发出时刻;</li>
 *   <li><b>不应用计划改动</b>。{@link Decision#planMutations()} 由 Mind 转交
 *       {@code PlanBoard.apply(...)}。决定是值, 应用是动作 —— 混在一起之后
 *       "这个决定能不能被应用两次"就没有答案了;</li>
 *   <li><b>不判断答案好不好</b>。它选的是"当前优先级最高的那一件", 不是"最合适的那一件"。
 *       后者需要品味, 而品味不是能写进确定性引擎的东西 —— 它属于 persona 与规则表,
 *       两者都在外面。</li>
 * </ul>
 */
public final class DecisionEngine {

    /**
     * 打断一件已经排好的事, 需要高出多少优先级。
     *
     * <p>用 {@link PlanPriority#SIGNIFICANT_GAP} 这<b>同一个数字</b>, 而不是另写一个常量:
     * "显著高出"这个概念在计划侧与决定侧必须是同一把尺子, 否则会出现
     * "计划器认为该让路、决定引擎认为不该打断"的对峙, 而那种对峙没有任何一方是错的。
     */
    public static final int INTERRUPTION_GAP = PlanPriority.SIGNIFICANT_GAP;

    private final String humanId;
    private final EventFabric fabric;
    private final AtomicLong sequence = new AtomicLong();

    /** 一个只产决定、不记账的引擎 —— 用于测试与"这次不上报"的场景。 */
    public DecisionEngine() {
        this("unknown", null);
    }

    public DecisionEngine(String humanId) {
        this(humanId, null);
    }

    /**
     * @param fabric 用来投 {@code mind.decision-made.v1}。可以传 {@code null} ——
     *               那时决定照做, 只是不记账(见 {@link #announce})
     */
    public DecisionEngine(String humanId, EventFabric fabric) {
        this.humanId = humanId == null || humanId.isBlank() ? "unknown" : humanId;
        this.fabric = fabric;
    }

    public String humanId() {
        return humanId;
    }

    /** 只产决定, 不记账 —— {@link #decide} 的简写。 */
    public Decision decide(ReasoningResult result, IntentionContext situation) {
        return decide(result, situation, null);
    }

    /**
     * 做出这一次的决定。
     *
     * <p>四步, 顺序是固定的:
     *
     * <ol>
     *   <li><b>没有候选</b> → {@link DecisionReason#CODE_NOTHING_TO_DO}。
     *       这是一个<b>真实的决定</b>, 不是"没算出结果";</li>
     *   <li><b>有候选但都做不了</b> → {@link DecisionReason#CODE_NOTHING_FEASIBLE},
     *       并把每个人的拒绝理由写进叙事。这一条比上一条重要得多: 它区分了
     *       "她没想到"与"她想了但做不成";</li>
     *   <li><b>她手头正有事, 而新的这件事没有显著更重要</b> →
     *       {@link DecisionReason#CODE_KEPT_CURRENT} + 一条 {@link PlanMutation.KeepActive}。
     *       这是 §3.5.6 那条 {@code KeepActive} 存在的理由:
     *       <b>"她处理了, 结论是继续"必须与"什么都没发生"看起来不一样</b>;</li>
     *   <li><b>否则</b> → {@link DecisionReason#CODE_CHOSE}, 把那个意图的拆解步骤
     *       转成动作意图。若它<b>拆不出此刻能执行的一步</b>, 那就不是"执行",
     *       而是"排进日程" —— 产出一条 {@link PlanMutation.Insert}。</li>
     * </ol>
     *
     * @param result    认知的产出。它只带候选, 不带结论 —— 结论是本类的责任
     * @param situation 她的处境投影。可行性判断与参数取值都从这里读
     * @param current   她此刻正在做的事, 可以为 {@code null}(她闲着)。
     *                  它<b>不是</b>从计划表里"顺手查一下"来的, 而是由调用方查好传进来 ——
     *                  本类拿不到计划表, 这正是它可被纯函数地测试的原因
     */
    public Decision decide(ReasoningResult result, IntentionContext situation, PlanItem current) {
        Objects.requireNonNull(result, "没有认知结果就没有可决定的事 —— 空转请用 decide 之前判断");
        Objects.requireNonNull(situation, "决定必须看着她的处境做 —— 否则与掷骰子无异");
        DecisionId id = nextId();

        List<Intention> candidates = result.intentions();
        if (candidates.isEmpty()) {
            return RuleDecision.nothing(id, DecisionReason.nothingToDo(explainNothing(result)));
        }

        List<Intention> feasible = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        for (Intention intention : candidates) {
            Feasibility feasibility = intention.evaluate(situation);
            if (feasibility.feasible()) {
                feasible.add(intention);
            } else {
                rejections.add("「" + intention.description() + "」此刻做不了: "
                        + feasibility.reason());
            }
        }

        if (feasible.isEmpty()) {
            return RuleDecision.nothing(id, DecisionReason.nothingFeasible(
                    "摆在面前的 " + candidates.size() + " 件事, 一件也做不了: "
                            + String.join("; ", rejections)));
        }

        feasible.sort(MindDecisionPlanner.priorityOrder());
        Intention chosen = feasible.get(0);

        if (current != null && !outranksCurrent(chosen, current)) {
            return RuleDecision.keeping(id, DecisionReason.keptCurrent(
                            "她正做着「" + current.intent().description() + "」(优先级 "
                                    + current.priority().level() + "); 「" + chosen.description()
                                    + "」(" + chosen.priority().describe() + ") 没有显著更重要, "
                                    + "所以她记下了这件事, 继续手上的事。"),
                    new PlanMutation.KeepActive(current.id(),
                            "新来的「" + chosen.description() + "」不足以打断, 继续当前安排"));
        }

        String why = "因为「" + chosen.description() + "」是此刻最该做的那一件("
                + chosen.priority().describe() + "), 且此刻做得了";

        List<ActionIntent> actions = new ArrayList<>();
        for (com.luxera.companion.human.life.plan.PlanIntent.ActionIntent step
                : chosen.actions(situation)) {
            actions.add(ActionIntent.fromPlanAction(step, why));
        }

        List<PlanMutation> mutations = new ArrayList<>();
        if (actions.isEmpty()) {
            mutations.add(new PlanMutation.Insert(
                    PlanItem.schedule(chosen.toPlan(),
                            TimeWindow.startingAt(situation.now(), chosen.expectedDuration())),
                    "「" + chosen.description() + "」拆不出此刻就能执行的一步, "
                            + "所以先把它排进日程, 而不是假装做了"));
        }

        return RuleDecision.of(id, DecisionReason.chose(
                "她决定做「" + chosen.description() + "」——" + why + "。"), actions, mutations);
    }

    /**
     * 把"她做过这次决定"记进事件流。
     *
     * <p>返回空 {@link Optional} 的两种情况: 没有总线, 或这次决定不值得上报。
     * 后者由 {@link DecisionMade} 自己判断(它永远值得), 所以实际上只有前者 ——
     * 这个签名之所以还是 {@code Optional}, 是为了让"没记成"在所有情况下都是一个
     * 可以被检查的返回值, 而不是一个静默的分支。
     */
    public Optional<DecisionMade> announce(Decision decision, Instant at) {
        Objects.requireNonNull(decision, "要上报的决定不能为空");
        Objects.requireNonNull(at, "上报必须带仿真时刻 —— 不许读系统时钟");
        if (fabric == null) {
            return Optional.empty();
        }
        DecisionMade event = DecisionMade.from(decision, at);
        fabric.publish(event);
        return Optional.of(event);
    }

    /**
     * 新来的这件事值不值得打断她手上那件。
     *
     * <p>判据是<b>"显著高出"</b>而不是"高出": 差一点点就让路, 否则她的日程会被
     * 一件件稍高一点的事切成碎片 —— 从外面看, 那叫"她什么都做不完"。
     * 而"不能让路"的安排(优先级顶格)在这里自动得到保护: 没有任何优先级能显著
     * 高出它, 所以她一定会把手上的事做完。
     */
    private static boolean outranksCurrent(Intention chosen, PlanItem current) {
        return chosen.priority().toPlanPriority().significantlyOutranks(current.priority());
    }

    private static String explainNothing(ReasoningResult result) {
        String understanding = result.understanding();
        return understanding == null || understanding.isBlank()
                ? "认知这轮没有给出任何候选项 —— 她没什么要做的。"
                : understanding;
    }

    /**
     * 决定 id 的生成。
     *
     * <p>用进程内计数器, <b>不是时钟</b>。{@code human/} 里一次都不许读系统时钟 ——
     * 而这个类又是"决定"这件事的负责人, 它顺手取个时间戳会是最难查的一类违规
     * (因为它只在重放时表现为不一致)。
     */
    private DecisionId nextId() {
        return DecisionId.of("decision-" + humanId + "-" + sequence.incrementAndGet());
    }

    public String describe() {
        return "DecisionEngine[" + humanId + ", 确定性: 无模型、无时钟、无 I/O; 记账 "
                + (fabric != null ? "开" : "关") + "]";
    }

    @Override
    public String toString() {
        return describe();
    }
}
