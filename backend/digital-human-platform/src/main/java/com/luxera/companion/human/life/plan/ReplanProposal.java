package com.luxera.companion.human.life.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §3.5.6 —— <b>一次重排的产物: "我建议这样改", 而不是"我已经改了"</b>。
 *
 * <h2>为什么交付的是改动清单, 而不是一个现成的 {@link PlanRevision}</h2>
 * 设计文档把 {@link PlanReplanner} 的签名草拟成了
 * {@code PlanRevision replan(ReplanningContext)}。实现时改成了本类, 原因很具体:
 *
 * <p>{@link PlanItem} 的 id 是在<b>应用改动的时候</b>才生成的 ——
 * {@link PlanMutation.Move} 会让 {@code PlanBoard} 用
 * {@link PlanItem#copyAsNew} 造一个<b>新 id</b> 的新项, 旧项转成
 * {@link PlanLifecycle#SUPERSEDED} 保留下来。这意味着:
 *
 * <pre>
 *   重排器造一个 Revision  ──→ 里面的 id 是 "item-7"（试算时生成）
 *          ↓ 交给校验
 *          ↓ 校验通过, 交给 PlanBoard 应用
 *   PlanBoard 再造一个 Revision ──→ 里面的 id 是 "item-12"（应用时又生成一次）
 * </pre>
 *
 * <p>于是"被校验的那个版本"和"被采纳的那个版本"是<b>两个不同的对象</b>,
 * 它们的 id 对不上。这个错位造成的后果不是崩溃, 而是更坏的东西:
 * {@code plan.revision-created.v1} 事件里报的 id、前端展示的 id、
 * 以及数据库里存下来的 id 会分属两次生成, <b>而它们指向的是同一件事</b>。
 * 那种数据很难查, 因为它看起来总是"差不多对"。
 *
 * <p>所以重排器只产出<b>意图</b>（六种 {@link PlanMutation} 的清单）,
 * 而 {@link PlanBoard#apply} 是唯一把意图变成事实的地方:
 *
 * <pre>
 *   PlanReplanner  → ReplanProposal（改动清单 + 理由 + 谁提的）
 *          ↓
 *   PlanValidator  → 校验（试算 = 应用, 见 {@link PlanValidator#preview}）
 *          ↓ 通过                    ↓ 不通过
 *   PlanBoard.apply(同一份清单)   记 system.plan-validation-failed.v1 并丢弃
 * </pre>
 *
 * <p>这样"她的一天"只有<b>一个</b>真相源, 而校验过的和采纳的是同一条路径。
 *
 * <h2>为什么 {@code reason} 和 {@code proposedBy} 是必须的字段</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>它回答的问题</th><th>没有它会怎样</th></tr>
 *   <tr>
 *     <td>{@link #reason()}</td>
 *     <td>她为什么这样改</td>
 *     <td>{@code plan.revision-created.v1} 只剩下一串 id 变动,
 *         行为分析看到"她 12:15 换了计划"却<b>永远不知道为什么</b>。
 *         而"为什么"才是这个平台存在的理由</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #proposedBy()}</td>
 *     <td>是谁提的（{@code "rule:cold-gets-dressed"} / {@code "llm"}）</td>
 *     <td>当观测到"她一天重排了 40 次"时, 无法回答"是规则在抖, 还是 LLM 在反复改主意"</td>
 *   </tr>
 * </table>
 *
 * <h2>空改动清单是合法的, 而且有意义</h2>
 * {@link #mutations()} 为空表示"她想过这件事, 决定什么都不改"。
 * 它与"根本没想过"是两回事 —— 后者不该产生新版本, 前者必须产生,
 * 因为"她被打扰过并且选择了忽略"是一个真实的、有研究价值的行为。
 * 与之并列的是只有 {@link PlanMutation.KeepActive} 的情形（见 {@link #isNoop()}）:
 * 那也是"想过了但不改", 只是她此刻手上有活, 所以改动里带上了"继续做它"这个显式声明。
 */
public record ReplanProposal(
        List<PlanMutation> mutations,
        String reason,
        String proposedBy) {

    public ReplanProposal {
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        Objects.requireNonNull(reason, "提案必须说明理由 —— 它要成为新版本的 reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "提案的理由不能是空字符串 —— 一个说不出理由的改动, 在历史里就是一次无从解释的意外");
        }
        Objects.requireNonNull(proposedBy, "提案必须记录是谁提的");
        if (proposedBy.isBlank()) {
            throw new IllegalArgumentException("proposedBy 不能是空字符串");
        }
    }

    /** 空提案 —— "想过了, 什么都不改"。 */
    public static ReplanProposal unchanged(String reason, String proposedBy) {
        return new ReplanProposal(List.of(), reason, proposedBy);
    }

    /** 只有一项改动的常见情形 —— 大多数规则只改一件事。 */
    public static ReplanProposal of(PlanMutation single, String reason, String proposedBy) {
        return new ReplanProposal(List.of(single), reason, proposedBy);
    }

    /** 这份提案什么都不做（没有改动, 或者只有 {@link PlanMutation.KeepActive}）。 */
    public boolean isNoop() {
        return PlanReplanner.isNoop(mutations);
    }

    /** 有没有真的改动计划表的形状（"她决定继续做"不算）。 */
    public boolean isStructural() {
        return !isNoop();
    }

    /** 里面那条"继续做当前这件事"的声明（如果有）。 */
    public java.util.Optional<PlanMutation.KeepActive> keepActive() {
        return PlanReplanner.keepActiveIn(mutations);
    }

    /**
     * 改动项数有没有超过某个上限。
     *
     * <p>调用方（{@link DefaultPlanReplanner}）用它在<b>应用之前</b>拦下失控的提案。
     * 注意它只是"问一句", 不做截断 —— 为什么不能截断见
     * {@link DefaultPlanReplanner#replan}。
     */
    public boolean exceeds(int limit) {
        return mutations.size() > limit;
    }

    /**
     * 把这份提案<b>试算</b>成一个版本 —— 不触碰任何真实的计划表。
     *
     * <p>试算与真实应用走的是同一个 {@link PlanBoard#apply}, 所以
     * "试算说没问题、采纳后却不一样"这种分叉在结构上不可能发生。
     * 这也是 {@link PlanValidator#accept} 之前必须调的一步。
     *
     * <p><b>试算的结果只用于校验, 用完就丢</b> —— 它不发布事件、不落库、
     * 不成为当前版本。真正落地的是 {@link #mutations()} 这份意图,
     * 由 {@code PlanBoard.apply} 重新算一遍。所以试算里那些新生成的
     * {@link PlanItemId} 是<b>一次性的草稿</b>, 外界永远看不到它们。
     *
     * <p>这一条是刻意写下来的: 如果哪天有人图省事, 把试算出来的版本
     * 直接当成结果用, 那么"被校验的"和"被采纳的"就变成了两个对象,
     * 而它们的 id 会分属两次生成 —— 那种数据看起来总是"差不多对",
     * 因此极难被发现。
     *
     * @param from 从哪个版本出发（通常是 {@link PlanBoard#current()}）
     * @param at   试算所用的仿真时刻
     */
    public PlanRevision materialize(PlanRevision from, Instant at) {
        return PlanValidator.materialize(from, at, reason, mutations);
    }

    /** 一行摘要 —— 日志与诊断面板用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(proposedBy).append("] 「").append(reason).append("」: ");
        if (mutations.isEmpty()) {
            return sb.append("无改动").toString();
        }
        sb.append(mutations.size()).append(" 处改动");
        for (PlanMutation mutation : mutations) {
            sb.append("\n    · ").append(mutation.describe());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ReplanProposal[" + proposedBy + ", " + mutations.size() + " 项: " + reason + "]";
    }
}
