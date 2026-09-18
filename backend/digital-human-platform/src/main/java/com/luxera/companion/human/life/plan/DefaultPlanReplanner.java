package com.luxera.companion.human.life.plan;

import com.luxera.companion.human.life.plan.ReplanningContext.Trigger;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.5.6 —— <b>确定性重排器: 按优先级问一圈规则, 第一条认领的说了算</b>。
 *
 * <h2>它是两条路径中的"保守"那条</h2>
 * <table border="1">
 *   <tr><th></th><th>本类（{@code DefaultPlanReplanner}）</th>
 *       <th>{@code LlmPlanReplanner}</th></tr>
 *   <tr><td>怎么决定</td><td>代码逻辑</td><td>模型提出候选</td></tr>
 *   <tr><td>可复现</td><td><b>是</b>（同样的 context → 同样的结果）</td>
 *       <td>否（同样输入可能给出不同输出）</td></tr>
 *   <tr><td>覆盖面</td><td>已知情形</td><td>开放情形</td></tr>
 *   <tr><td>什么时候用</td><td>常见情形; <b>以及 LLM 不可用时的降级路径</b></td>
 *       <td>需要"她该不该改主意"这类判断时</td></tr>
 * </table>
 *
 * <p>"降级路径"这五个字是本类最重要的职责之一。设计文档 §3.5.6 要求任何时刻
 * 都必须有一个能用的重排器: 一个只有 LLM 重排器的系统, 在模型超时的那一分钟里
 * <b>会变成一个不会重排的人</b> —— 而"她今天没反应"在行为分析里看不出是故障。
 * 用户对这类降级的要求是明确的: 降级可以, 但必须<b>让人知道降级了</b>
 * （所以每次降级都会记日志并带着 {@link #FALLBACK_PROPOSER} 这个名字进版本历史）。
 *
 * <h2>它<b>不</b>知道任何领域知识</h2>
 * "冷了要穿衣服"这件事不在本类里, 也不该在。它只做三件事:
 * <ol>
 *   <li>按 {@link PlanAdjustmentRule#priority()} 从小到大把规则排好;</li>
 *   <li>跳过 {@link PlanAdjustmentRule#vetoedBy} 说"这会儿不算数"的规则;</li>
 *   <li>取<b>第一条提出非空改动</b>的规则的结果。</li>
 * </ol>
 *
 * <p>"第一条认领的说了算"这个策略是刻意的<b>保守</b>选择。另一种做法是
 * 把所有规则的建议合并起来 —— 那看起来更"全面", 但它会产生一类很难查的行为:
 * 两条各自合理的规则合起来会排出一个没人会做的日程
 * （"她先穿衣服再脱衣服, 因为两条规则都在说温度的事"）。
 * 一次只采纳一条, 让"哪条规则赢了"永远是个可回答的问题。
 *
 * <h2>同优先级时按什么排</h2>
 * 按 {@link PlanAdjustmentRule#name() 规则名}的字典序, 而<b>不是</b>按注册顺序。
 * 理由: 注册顺序取决于 Spring 的 bean 扫描顺序, 那是个在设计上不可控、
 * 在升级依赖时可能悄悄变化的东西。而"她为什么突然改了行为"必须是可复现的 ——
 * 用一个稳定的排序键, 这件事就不依赖于 bean 的加载顺序。
 */
@Slf4j
public class DefaultPlanReplanner implements PlanReplanner {

    /** 降级时记进版本历史的提议者名。带着它, 就能在数据里把降级和正常重排分开。 */
    public static final String FALLBACK_PROPOSER = "rule-fallback";

    /** 本重排器的名字 —— 进 {@code plan.revision-created.v1} 的载荷。 */
    public static final String NAME = "default-rule-replanner";

    private final List<PlanAdjustmentRule> rules;
    private final int maxMutationsPerReplan;

    /** 没有任何规则时的构造 —— 一切走降级路径。空规则集是合法配置, 不是错误。 */
    public DefaultPlanReplanner() {
        this(List.of(), PlanReplanner.DEFAULT_MAX_MUTATIONS);
    }

    public DefaultPlanReplanner(List<PlanAdjustmentRule> rules) {
        this(rules, PlanReplanner.DEFAULT_MAX_MUTATIONS);
    }

    /**
     * @param rules                   按优先级会被重新排序, 传入顺序不影响结果
     * @param maxMutationsPerReplan   一次重排最多改动几项。见
     *                                {@link PlanReplanner#maxMutationsPerReplan()}
     */
    public DefaultPlanReplanner(List<PlanAdjustmentRule> rules, int maxMutationsPerReplan) {
        Objects.requireNonNull(rules, "规则列表不能为 null —— 没有规则请传空列表");
        if (maxMutationsPerReplan < 1) {
            throw new IllegalArgumentException(
                    "一次重排至少要允许 1 项改动, 收到 " + maxMutationsPerReplan
                            + " —— 允许 0 就等于“永远不会重排”");
        }
        List<PlanAdjustmentRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(PlanAdjustmentRule::priority)
                .thenComparing(PlanAdjustmentRule::name));
        this.rules = List.copyOf(sorted);
        this.maxMutationsPerReplan = maxMutationsPerReplan;
    }

    // ─────────────────────────── 核心 ───────────────────────────

    /**
     * {@inheritDoc}
     *
     * <h3>四步</h3>
     * <ol>
     *   <li>规则已按优先级 + 名字排好（构造时做的）;</li>
     *   <li>逐条问: 被否决吗? 提出什么? —— 返回空列表 = "我不认领这个处境";</li>
     *   <li>提了但超出上限的, <b>整份丢弃</b>并继续问下一条（为什么不截断见下）;</li>
     *   <li>一条都没认领 → 走降级（见 {@link #fallback}）。</li>
     * </ol>
     *
     * <h3>为什么超出上限时丢弃而不是截断</h3>
     * 改动之间是<b>有依赖</b>的: 用户描述的那个场景里, "插入穿衣"和
     * "把写作业的启动时间设为穿衣结束的时间"是一对 —— 单独留下前者,
     * 计划表上就会出现一个和写作业重叠的穿衣时段。
     *
     * <p>也就是说<b>截断产生的是一个错的计划, 而不是一个不完整的计划</b>。
     * 而一个错的计划会真的被执行（她的身体只有一个, 12:15 只能做一件事）。
     * 相比之下, "这条规则今天没生效"只是一个可观测的缺失。
     * 两害相权, 取丢弃。
     */
    @Override
    public ReplanProposal replan(ReplanningContext context) {
        Objects.requireNonNull(context, "重排上下文不能为空");

        for (PlanAdjustmentRule rule : rules) {
            if (rule.vetoedBy(context)) {
                log.debug("[PlanReplanner] 规则 {} 被否决, 跳过", rule.name());
                continue;
            }

            List<PlanMutation> proposed;
            try {
                proposed = rule.propose(context);
            } catch (RuntimeException e) {
                // 一条写坏的规则不该让她失去重排能力 —— 与 PlanBoard 对一个坏观察者的态度一致
                log.error("[PlanReplanner] 规则 {} 提出改动时抛异常, 跳过它继续问下一条",
                        rule.name(), e);
                continue;
            }

            if (proposed == null || proposed.isEmpty()) {
                // 空列表 = "我不认领这个处境", 而不是"建议什么都不改"。见 PlanAdjustmentRule
                log.debug("[PlanReplanner] 规则 {} 不认领当前处境", rule.name());
                continue;
            }

            int limit = Math.min(rule.maxMutations(), maxMutationsPerReplan);
            if (proposed.size() > limit) {
                log.warn("[PlanReplanner] 规则 {} 一次提出了 {} 项改动, 超过上限 {} —— "
                                + "整份丢弃而不是截断（截断会留下互相依赖的碎片, 排出一个错的计划）; "
                                + "继续问下一条规则",
                        rule.name(), proposed.size(), limit);
                continue;
            }

            String reason = reasonFor(rule, proposed);
            log.info("[PlanReplanner] 规则 {} 认领了这次重排（触发={}）: {} 项改动 —— {}",
                    rule.name(), context.trigger().describe(), proposed.size(), reason);
            return new ReplanProposal(proposed, reason, rule.name());
        }

        return fallback(context);
    }

    /**
     * 版本理由 —— 用<b>规则自己的话</b>, 而不是模板。
     *
     * <p>为什么不做成 {@code "rule:" + rule.name()} 这种模板: 这条字符串最终会
     * 变成 {@code plan.revision-created.v1} 的 {@code reason},
     * 再进入她的 LLM context 与行为分析报告。一句"有点冷, 先加件衣服"
     * 能让读报告的人理解她; 一句 {@code "cold-rule triggered"} 只能让人确认
     * 代码跑过了。用户对这个平台的要求是前者。
     *
     * <p>多项改动时取第一条的理由再补一句计数 —— 因为改动之间通常是因果链
     * （"先穿衣服, 于是写作业顺延"）, 而第一条正是那条链的起因。
     */
    private String reasonFor(PlanAdjustmentRule rule, List<PlanMutation> proposed) {
        String head = proposed.get(0).reason();
        if (proposed.size() == 1) {
            return head;
        }
        return head + "（连带 " + (proposed.size() - 1) + " 处调整）";
    }

    /**
     * 一条规则都没认领时的兜底。<b>永远返回一份合法的提案</b>, 绝不返回 null。
     *
     * <h3>两种情况, 两种兜底</h3>
     * <table border="1">
     *   <tr><th>处境</th><th>兜底</th><th>为什么</th></tr>
     *   <tr>
     *     <td>她手上正有事</td>
     *     <td>一条 {@link PlanMutation.KeepActive}</td>
     *     <td>"被打扰了, 想了想, 决定先把这点做完" —— 这是<b>真实的人会做的事</b>,
     *         而不是一个缺失的答案。它必须产生新版本, 因为"她被打扰过"是个事实</td>
     *   </tr>
     *   <tr>
     *     <td>她手上没事</td>
     *     <td>空改动清单</td>
     *     <td>她确实是空着的, 没有"当前这件事"可以继续。空的改动清单配上
     *         {@code reason} 表示"看过当前安排, 没什么要调整的"</td>
     *   </tr>
     * </table>
     *
     * <h3>为什么这个兜底要记 WARN</h3>
     * 因为"没有规则认领"这句话有两种截然不同的含义:
     * <ul>
     *   <li><b>正常</b> —— 处境确实没什么可做的（她正躺着休息, 房间里冷了一点,
     *       但盖着被子）;</li>
     *   <li><b>配置缺口</b> —— 这个处境本该有规则管, 但没人写。
     *       当 {@link Trigger#KIND_TIME}（到点了该推进下一项）都无人认领时,
     *       几乎可以确定是后者: 时间推进是最机械的一种触发, 不该需要"想"。</li>
     * </ul>
     * <p>日志里区分这两种, 是为了让"她今天几乎没执行任何计划"这个现象
     * 在排查时能立刻指向"规则集没配全", 而不是被误读成"她很懒"。
     */
    private ReplanProposal fallback(ReplanningContext context) {
        if (Trigger.KIND_TIME.equals(context.trigger().kind())) {
            log.warn("[PlanReplanner] 一次由时间推动的重排没有任何规则认领 —— "
                            + "这几乎总是规则集没配全, 而不是她的正常行为。时刻={}, 现存 {} 项",
                    context.now(), context.liveItems().size());
        }

        if (context.active().isEmpty()) {
            log.debug("[PlanReplanner] 降级: 她没有正在做的事, 也没有规则认领 —— 不产生改动");
            return ReplanProposal.unchanged("看过当前的安排, 没有需要调整的地方",
                    FALLBACK_PROPOSER);
        }

        ReplanningContext.ActiveExecution active = context.active().get();
        log.debug("[PlanReplanner] 降级: 没有规则认领, 但她正在做「{}」—— 保留它",
                active.description());
        return ReplanProposal.of(
                new PlanMutation.KeepActive(active.itemId(),
                        "虽然 " + context.trigger().detail() + ", 但先把手上的「"
                                + active.description() + "」做完"),
                "没什么要改的, 先继续手头的事",
                FALLBACK_PROPOSER);
    }

    // ─────────────────────────── 诊断 ───────────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int maxMutationsPerReplan() {
        return maxMutationsPerReplan;
    }

    /**
     * 规则已经排好序 —— 直接返回, 因为顺序就是"谁先被问"这个语义本身。
     * 返回一个不可变副本, 免得调用方以为自己能改它。
     */
    public List<PlanAdjustmentRule> registeredRules() {
        return rules;
    }

    public int ruleCount() {
        return rules.size();
    }

    /** 一条规则都没配吗 —— 此时一切都走降级路径。 */
    public boolean isRuleless() {
        return rules.isEmpty();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("DefaultPlanReplanner[上限 ").append(maxMutationsPerReplan)
                .append(" 项改动, ").append(rules.size()).append(" 条规则]");
        for (PlanAdjustmentRule rule : rules) {
            sb.append("\n  ").append(rule.priority()).append("  ").append(rule.name())
                    .append(" —— ").append(rule.describe())
                    .append("（最多 ").append(rule.maxMutations()).append(" 项）");
        }
        if (rules.isEmpty()) {
            sb.append("\n  （没有规则 —— 所有重排都会走降级路径）");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "DefaultPlanReplanner[" + rules.size() + " 条规则]";
    }
}
