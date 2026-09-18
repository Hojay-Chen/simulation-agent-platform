package com.luxera.companion.cognition;

import com.luxera.companion.attention.AttentionService;
import com.luxera.companion.state.CompanionAvailability;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * V11 §11.3 的 <b>Deterministic Rules</b> 那个方框 —— 她在世界上遇到一件事之后,
 * 决定要不要动、动什么。
 *
 * <h2>它不发明任何策略, 它把已经存在的策略变成可读的东西</h2>
 * 这一点必须先说清楚, 否则这个类看起来像是"又一次重写认知"。它<b>不是</b>。
 * 下面每一条规则, 今天都已经散落在 {@code AgentRuntime.process} 的三百行里:
 * <pre>
 *   "没看到就别回"          ← pipelineResult.isIgnored()
 *   "睡着且不重要就别吵她"    ← sleeping &amp;&amp; wake &lt; DELIBERATION
 *   "忙就先记着, 待会儿再说"  ← pipelineResult.isDeferred()
 *   "被连发催问要坐不住"      ← urged 覆盖 DEFER / 提升 wake
 *   "低价值消息不打断她"      ← !requiresCognition(wake)
 * </pre>
 * 本类做的事情是: 把这些判断从"流程的走向"提出来, 变成<b>一个能返回值的函数</b>。
 * 提出来之后才可能有 shadow 期的 diff(拿它的结论与老链的实际走向比),
 * 而 diff 是切流的唯一判据。
 *
 * <h2>"忙"不等于"不回"(这条容易搞反)</h2>
 * {@link CompanionAvailability} 的类注释写着: <b>Busy ≠ 不回复 —— 影响的是延迟/长度/追问/注意力,
 * 而不是 canReply=false</b>。V11 §12"回复不再是默认行为"与这句<b>不矛盾</b>, 但很容易被
 * 读成矛盾, 于是这里明确写下来:
 * <pre>
 *   忙 + 消息不值得打断她   → DEFER (不回, 且留痕)
 *   忙 + 被催问 / 情绪信号强 → REPLY (回, 只是更慢更短)
 * </pre>
 * 也就是说"忙"是 DEFER 的<b>必要条件之一</b>, 不是充分条件。这个区别正是 V10 那句注释要守的东西 ——
 * 一个只因为"在忙"就再也不回消息的数字人不是更像人, 是更像一个坏掉的客服。
 *
 * <h2>它是纯函数</h2>
 * 没有 I/O、没有 Spring 注入的服务、不读时钟(时刻由 {@code now} 传入)。
 * 这不是为了好看: 决策是 Phase 4 里唯一一处会<b>直接改变 53 个 agent 行为</b>的新逻辑,
 * 而它必须能被穷举测试 —— 一个会查库的 {@code decide()} 只能靠在集成测试里碰运气。
 * 所有输入都来自调用方<b>已经算好的</b>值(注意力、可用性、唤醒等级),
 * 所以这里也不会出现"两个地方各自算'她忙不忙'而答案不同"。
 */
@Component
public class MindDecisionPlanner {

    private final CognitiveWakeupService wakeup;

    public MindDecisionPlanner(CognitiveWakeupService wakeup) {
        this.wakeup = wakeup;
    }

    /** 押后的默认复查间隔: 与 AgentRuntime 里 DEFER 分支给 intention 的复查时间一致。 */
    static final int DEFAULT_DEFER_MINUTES = 60;

    /** 情绪信号低于这个强度, 就不算"她非回不可"的理由。 */
    private static final double STRONG_EMOTION = 0.4;

    /**
     * 决策的输入 —— 全是调用方<b>已经算好</b>的值。
     *
     * <p>刻意做成一个宽参数记录而不是十几个方法参数: 这是一次"她现在的处境"的快照,
     * 而 {@code decide} 的每个分支都要看其中好几项 —— 拆成参数会让每个新规则都改一次签名。
     *
     * @param wake           唤醒等级({@link CognitiveWakeupService#evaluate} 的产出)
     * @param attention      注意力({@link AttentionService} 的产出); 为 null 表示还没算(按"看不到"处理)
     * @param availability   可用性({@link com.luxera.companion.state.AvailabilityService} 的产出)
     * @param sleeping       此刻是不是睡着的
     * @param urged          是不是被连发催问
     * @param emotionalSignal 对方的情绪信号强度(0~1)
     * @param agentPaused    她是不是被用户按了暂停
     */
    public record DecisionInput(CognitiveWakeupService.WakeLevel wake,
                                AttentionService.Attention attention,
                                CompanionAvailability availability,
                                boolean sleeping,
                                boolean urged,
                                double emotionalSignal,
                                boolean agentPaused) {

        public static DecisionInput of(CognitiveWakeupService.WakeLevel wake,
                                       AttentionService.Attention attention,
                                       CompanionAvailability availability,
                                       boolean sleeping, boolean urged, double emotionalSignal) {
            return new DecisionInput(wake, attention, availability, sleeping, urged, emotionalSignal, false);
        }
    }

    /**
     * 算出一个决策。<b>顺序即优先级, 每个分支都必须能独立读懂。</b>
     *
     * <p>规则顺序不是随便排的, 它回答的是"哪件事更根本":
     * 先问"她能不能动"(暂停), 再问"这件事够不够格叫醒她"(唤醒等级),
     * 再问"她有没有真的看到"(注意力), 最后才问"她现在方便吗"(睡着/忙)。
     * 反过来的话, 一个被暂停的 agent 会先被判定为"忙, 押后" ——
     * 于是日志里全是 DEFER, 而真相是它根本不在。
     */
    public CognitiveDecision decide(DecisionInput in, LocalDateTime now) {
        if (in == null) {
            return CognitiveDecision.of(DecisionType.DO_NOTHING, "no_input");
        }
        // 1. 她不在。这不是"押后", 押后会有人复查; 这是"没人可复查"
        if (in.agentPaused()) {
            return CognitiveDecision.of(DecisionType.DO_NOTHING, "agent_paused");
        }
        // 2. 这件事根本不够格进入她的意识(如"哈哈") —— 消息已入库, 但不打断她
        if (in.wake() == null || !wakeup.requiresCognition(in.wake())) {
            return CognitiveDecision.of(DecisionType.DO_NOTHING, "below_cognition_threshold");
        }
        // 3. 阈值过了, 但她没看到(静音/勿扰/注意力被占满)。与第 2 条的区别是
        //    这一条是"看到了就会回", 第 2 条是"看到了也不回" —— 两者的后续完全不同
        if (in.attention() == null || in.attention().noticeProbability() < 0.3) {
            return CognitiveDecision.of(DecisionType.DO_NOTHING, "not_noticed");
        }
        // 4. 她拿起了手机(会打开会话读一次)。V11 里"读"是一个动作, 不再与感知混在一起
        boolean willInspect = in.attention().inspectProbability() >= 0.45;

        // 5. 睡着了: 只有够分量的消息才吵醒她。被催问 / 情绪强烈 → 吵醒
        if (asleep(in)) {
            boolean important = in.wake() == CognitiveWakeupService.WakeLevel.DELIBERATION
                    || in.wake() == CognitiveWakeupService.WakeLevel.DEEP_THINKING
                    || in.urged() || in.emotionalSignal() >= STRONG_EMOTION;
            if (!important) {
                // 等她醒过来再说 —— 这不是"押后", 是"她现在不在场", 所以复查时刻推到早上
                return CognitiveDecision.until(DecisionType.WAIT, "sleeping",
                        morningAfter(now));
            }
            return willInspect
                    ? CognitiveDecision.of(DecisionType.REPLY, "woken_up_by_important_message")
                    : CognitiveDecision.of(DecisionType.READ_MESSAGES, "woken_up_but_not_yet_looking");
        }
        // 6. 在忙 / 走神: 消息不够急 → 看到了, 押后。**但被催问就坐不住**(V10 既有语义)
        if (isBusy(in.availability()) && !in.urged() && in.emotionalSignal() < STRONG_EMOTION) {
            return CognitiveDecision.until(DecisionType.DEFER, "busy_" + in.availability().name().toLowerCase(),
                    now == null ? null : now.plusMinutes(DEFAULT_DEFER_MINUTES));
        }
        // 7. 看到了但没拿起手机 → 只读不回(她瞥见了通知, 没点开)
        if (!willInspect) {
            return CognitiveDecision.of(DecisionType.OBSERVE, "noticed_but_not_opening");
        }
        // 8. 其余: 回。
        //    "其余"这两个字是关键 —— 在 V11 里 REPLY 是**走到最后剩下的那一个**,
        //    而不是"默认行为"。上面七条里任何一条命中, 都不会走到这里。
        return CognitiveDecision.of(DecisionType.REPLY, "engaged");
    }

    /**
     * 忙到"想回也得挑时候"的状态。
     *
     * <p>注意 {@code AVAILABLE} 与 {@code RESTING} 都不算: 休息中的她<b>恰恰</b>有空回消息,
     * 把 RESTING 归进"忙"是一个很容易犯、又很难发现的错 —— 表面上是"她在休息别打扰",
     * 实际效果是"她闲下来的时候谁也不理"。
     */
    private static boolean isBusy(CompanionAvailability a) {
        return a == CompanionAvailability.BUSY
                || a == CompanionAvailability.SOCIALIZING
                || a == CompanionAvailability.TRAVELING;
    }

    /**
     * "她睡着了"这件事有<b>两个</b>来源: 作息(睡眠模型)与可用性, 而它们可能不同步
     * (前者来自时刻表与精力, 后者来自 AgentState)。
     *
     * <p>两个事实说的是同一件事, 就不该允许它们互相矛盾 —— 若只看其中一个, 当它们不一致时
     * 会出现"可用性说她睡着、作息说她醒着" → 走到"忙"那条分支 → <b>押后一小时</b>。
     * 对一个睡着了的人来说这是最差的答案: 既没等她醒, 也没解释清楚。
     * 所以这里取<b>并集</b>: 任一个说她睡着, 就按睡着处理(重要消息照样能吵醒)。
     */
    private static boolean asleep(DecisionInput in) {
        return in.sleeping() || in.availability() == CompanionAvailability.SLEEPING;
    }

    /** 早上八点 —— 不是"八小时后": 深夜两点收到消息时, 八小时后是上午十点, 而她十点可能还在睡。 */
    private static LocalDateTime morningAfter(LocalDateTime now) {
        if (now == null) {
            return null;
        }
        LocalDateTime morning = now.toLocalDate().plusDays(1).atTime(8, 0);
        return now.getHour() < 8 ? now.toLocalDate().atTime(8, 0) : morning;
    }
}
