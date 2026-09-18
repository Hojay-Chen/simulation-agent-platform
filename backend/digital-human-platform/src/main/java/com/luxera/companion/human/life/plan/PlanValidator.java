package com.luxera.companion.human.life.plan;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.6 —— <b>LLM 提议的"做什么"要过这里, 引擎决定"排得下排不下"</b>。
 *
 * <h2>为什么必需: LLM 会算错</h2>
 * 设计文档 §3.5.6 把分工写成了两条明确的清单:
 * <table border="1">
 *   <tr><th>职责</th><th>由谁做</th><th>为什么</th></tr>
 *   <tr><td>提出候选意图（"要不先穿件衣服？"）</td><td><b>LLM</b></td>
 *       <td>开放性判断, 没有固定答案</td></tr>
 *   <tr><td>解释原因</td><td><b>LLM</b></td><td>自然语言生成</td></tr>
 *   <tr><td>时间合法性（有没有重叠）</td><td><b>确定性引擎</b></td>
 *       <td>算术问题, LLM 会算错</td></tr>
 *   <tr><td>依赖合法性（前置做了吗）</td><td><b>确定性引擎</b></td><td>图问题</td></tr>
 *   <tr><td>冲突检测（资源/地点）</td><td><b>确定性引擎</b></td><td>集合运算</td></tr>
 *   <tr><td>状态迁移（Revision 链）</td><td><b>确定性引擎</b></td><td>事务问题</td></tr>
 * </table>
 *
 * <p>"LLM 会算错"这句话不是猜测。一个被要求"把写作业排到 12:25, 持续 1 小时,
 * 排在穿衣服 12:15-12:25 之后"的模型, 完全可能给出 {@code 12:25-13:20} 或
 * {@code 12:35-13:25} —— 而这两种错误在计划表里都表现为"她的一天排得不对",
 * 一个看起来像"她自己的决定很奇怪"的症状。<b>把算术交给模型,
 * 就是把她变成一个会犯低级算术错误的人。</b>
 *
 * <h2>校验不过怎么办: 丢弃 + <b>记录</b></h2>
 * 用户的要求是"校验不过的重排被丢弃, 并记录一条 {@code PlanValidationFailed}
 * （<b>这是可观测的, 不是静默丢弃</b>）"。
 *
 * <p>为什么"可观测"是硬要求: 一个悄悄失败的规划, 在行为分析里表现为
 * <b>"她今天什么都没安排"</b> —— 而真相是"她的规划器一直在报错"。
 * 这两者在数据上完全不同, 但没有这条事件时它们长得一模一样,
 * 研究者会据此得出"她今天很消极"的<b>假结论</b>。
 *
 * <h2>三种违规强度的处理</h2>
 * <table border="1">
 *   <tr><th>{@link PlanConstraint.Severity}</th><th>处理</th><th>理由</th></tr>
 *   <tr><td>{@code HARD}</td><td><b>拒绝整个 Revision</b></td>
 *       <td>物理上不可能的事（时间重叠）如果进了计划表, 调度器<b>一定</b>会在运行期出错 ——
 *           那只是把一个确定会发生的故障推迟到更难查的时刻</td></tr>
 *   <tr><td>{@code SOFT}</td><td>接受, 记警告</td>
 *       <td>真人会破例（为了考试熬夜）, 而<b>破例本身是有研究价值的行为数据</b>。
 *           用硬约束挡住它, 等于把"她为了考试熬夜了"这个事实从数据里抹掉</td></tr>
 *   <tr><td>{@code ADVISORY}</td><td>接受, 只记录</td>
 *       <td>"更好的做法"（上午做数学效率高）。不进告警, 但进时间轴 ——
 *           因为"她总是选低效时段做数学"是一个值得被发现的模式</td></tr>
 * </table>
 */
@Slf4j
public class PlanValidator {

    /**
     * 一次校验的结果。
     *
     * @param valid      可以采纳吗（等价于"没有 HARD 违规"）
     * @param violations HARD 违规 —— <b>每一条都必须是一句人能读懂的话</b>,
     *                   因为它们会被拼进 {@code ValidationFailed} 的载荷,
     *                   并且最终出现在运维告警里。写"constraint failed"等于没写
     * @param warnings   SOFT 违规
     * @param advisories ADVISORY 违规
     */
    public record ValidationResult(boolean valid,
                                   List<String> violations,
                                   List<String> warnings,
                                   List<String> advisories) {

        public ValidationResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            advisories = advisories == null ? List.of() : List.copyOf(advisories);
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of(), List.of(), List.of());
        }

        public static ValidationResult okWith(List<String> warnings, List<String> advisories) {
            return new ValidationResult(true, List.of(), warnings, advisories);
        }

        public static ValidationResult rejected(List<String> violations) {
            return new ValidationResult(false, violations, List.of(), List.of());
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public String describe() {
            if (valid) {
                return "校验通过" + (warnings.isEmpty() ? "" : " (软约束违反 " + warnings.size() + " 处)")
                        + (advisories.isEmpty() ? "" : " (建议 " + advisories.size() + " 处)");
            }
            return "校验未通过: " + String.join("; ", violations);
        }
    }

    private final EventFabric fabric;

    /**
     * @param fabric 用来发 {@code system.plan-validation-failed.v1}。
     *               允许为 {@code null}（测试里不需要事件总线）—— 但那样失败就
     *               只进日志, 而<b>日志不是行为分析的数据源</b>。
     *               生产装配时必须给
     */
    public PlanValidator(EventFabric fabric) {
        this.fabric = fabric;
    }

    // ─────────────────────────── 预演 ───────────────────────────

    /**
     * 把一整套改动<b>试算一遍</b>, 得到"如果采纳会变成什么样"。
     *
     * <p>实现上它开一个临时的 {@link PlanBoard}, 把改动应用上去, 再把结果版本取出来 ——
     * <b>而不是重新写一遍 {@code applyOne} 的逻辑</b>。
     *
     * <p>为什么不重写: 试算与实际应用必须<b>逐字一致</b>, 否则会出现"试算说没问题,
     * 采纳之后却不一样"这种最难查的一类 bug —— 因为它需要同时怀疑两个实现。
     * 复用同一个 {@code apply} 让这种分叉在结构上不可能发生。
     *
     * <p>代价是临时对象。相对于"计划表算错一次"的代价, 那点分配不值得犹豫。
     */
    public PlanRevision preview(PlanRevision from, Instant at, String reason,
                               List<PlanMutation> mutations) {
        return materialize(from, at, reason, mutations);
    }

    /**
     * 试算的<b>唯一实现</b> —— {@link #preview} 与
     * {@link ReplanProposal#materialize} 都走这里。
     *
     * <p>为什么刻意做成静态: 它是一个纯函数（给定同样的起点、时刻、理由、改动,
     * 必然得到同样的结果 —— 除了新生成的 {@link PlanItemId}）。
     * 做成静态能让 {@link ReplanProposal} 在没有 {@code PlanValidator} 实例的地方
     * 也能试算, 而不必为了拿一个纯函数去 new 一个持有事件总线的对象。
     *
     * <p>两个入口共用一份实现是有意的: 一旦它们各自实现一遍,
     * "试算说排得下、真应用却发现排不下"就会成为一个可能出现、
     * 且需要同时怀疑两个实现的 bug。
     */
    public static PlanRevision materialize(PlanRevision from, Instant at, String reason,
                                          List<PlanMutation> mutations) {
        Objects.requireNonNull(from, "试算必须有一个起点版本");
        Objects.requireNonNull(at, "试算必须带时刻");
        Objects.requireNonNull(reason, "试算必须带理由");
        Objects.requireNonNull(mutations, "试算必须带改动清单 —— 想表达'什么都不改'请传空列表");
        PlanBoard scratch = new PlanBoard(from);
        return scratch.apply(at, reason, mutations);
    }

    // ─────────────────────────── 校验 ───────────────────────────

    /**
     * 校验一个候选版本。
     *
     * <p>三类检查, 全部是确定性的:
     * <ol>
     *   <li><b>时间重叠</b> —— 两个还占着未来的项窗口相交。这是 HARD, 因为她的身体只有一个;</li>
     *   <li><b>依赖顺序</b> —— 一项依赖的前置在它开始之后才结束。HARD;</li>
     *   <li><b>约束</b> —— 逐条 {@link PlanConstraint#evaluate}, 按各自的强度分流。</li>
     * </ol>
     */
    public ValidationResult validate(PlanRevision candidate, PlanningContext context) {
        Objects.requireNonNull(candidate, "要校验的版本不能为空");
        Objects.requireNonNull(context, "校验必须带上下文 —— 约束是上下文相关的");

        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> advisories = new ArrayList<>();

        // ① 时间重叠
        violations.addAll(candidate.overlaps());

        // ② 依赖顺序
        violations.addAll(checkDependencies(candidate));

        // ③ 逐条约束
        List<PlanConstraint> all = new ArrayList<>(candidate.constraints());
        candidate.liveItems().forEach(i -> all.addAll(i.constraints()));
        Map<String, PlanConstraint.Severity> worstByConstraint = new LinkedHashMap<>();
        for (PlanConstraint constraint : all) {
            for (PlanItem item : candidate.liveItems()) {
                PlanConstraint.ConstraintResult result = constraint.evaluate(item, context);
                if (result.satisfied()) {
                    continue;
                }
                String line = constraint.describe() + " → 「" + item.intent().description()
                        + "」: " + result.reason();
                switch (result.severity()) {
                    case HARD -> violations.add(line);
                    case SOFT -> warnings.add(line);
                    case ADVISORY -> advisories.add(line);
                }
                // 同一个约束可以在多个项上违规 —— 只保留最严重的那一档用于汇总日志
                worstByConstraint.merge(constraint.id().value(), result.severity(),
                        (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
            }
        }

        if (!violations.isEmpty()) {
            return ValidationResult.rejected(violations);
        }
        if (!warnings.isEmpty()) {
            log.info("[PlanValidator] {} 通过但有 {} 处软约束违反: {}",
                    candidate.revisionId(), warnings.size(), warnings);
        }
        return ValidationResult.okWith(warnings, advisories);
    }

    /**
     * 依赖顺序检查。
     *
     * <p>规则: 一项依赖的前置必须<b>在它开始之前或正好同时结束</b>。
     * 判据用 {@code 前置.end <= 本项.start} —— 与 {@link TimeWindow} 的半开区间一致,
     * 所以"穿衣服 12:15-12:25 → 写作业 12:25-13:10"是合法的
     * （这正是用户描述的那个形状）。
     *
     * <p>缺失的前置也报错: 一个指向不存在的项的依赖, 会让"她永远在等一件不存在的事"。
     */
    private List<String> checkDependencies(PlanRevision candidate) {
        List<String> issues = new ArrayList<>();
        for (PlanItem item : candidate.liveItems()) {
            for (PlanItemId depId : item.dependencies()) {
                Optional<PlanItem> dep = candidate.find(depId);
                if (dep.isEmpty()) {
                    issues.add("「" + item.intent().description() + "」依赖的 "
                            + depId.value() + " 不在计划表里 —— 她会永远等不到它");
                    continue;
                }
                PlanItem prerequisite = dep.get();
                if (prerequisite.window().end().isAfter(item.window().start())) {
                    issues.add("「" + item.intent().description() + "」依赖 "
                            + depId.value() + ", 但它要到 " + prerequisite.window().end()
                            + " 才结束, 而本项 " + item.window().start() + " 就开始 —— 顺序倒置");
                }
            }
        }
        return issues;
    }

    // ─────────────────────────── 校验 + 记录 ───────────────────────────

    /**
     * 校验一个候选版本; 不通过时<b>记一条事件再拒绝</b>。
     *
     * @param proposedBy 谁提的（{@code "llm"}、{@code "rule-engine"}…）。
     *                   它决定了这条失败记录该去找谁的麻烦
     * @return 通过则返回该版本; 不通过返回 {@link Optional#empty()}
     */
    public Optional<PlanRevision> accept(PlanRevision candidate, PlanningContext context,
                                        String proposedBy,
                                        Map<String, Object> rejectedPlanSnapshot) {
        ValidationResult result = validate(candidate, context);
        if (result.valid()) {
            return Optional.of(candidate);
        }
        publishFailure(proposedBy, context.now(), result, rejectedPlanSnapshot);
        return Optional.empty();
    }

    /**
     * 校验失败时发一条 {@code system.plan-validation-failed.v1}。
     *
     * <p><b>无论有没有 {@code EventFabric} 都会记 WARN 日志</b> —— 因为"规划器一直在报错"
     * 这件事必须有人能发现, 而如果唯一的记录途径是事件总线, 那么总线没接好的环境里
     * 它就会彻底消失。日志是兜底, 事件是数据源, 两者都要有。
     */
    public void publishFailure(String proposedBy, Instant at, ValidationResult result,
                              Map<String, Object> rejectedPlanSnapshot) {
        log.warn("[PlanValidator] 拒绝了一份由 {} 提出的计划: {}", proposedBy, result.describe());
        if (fabric == null) {
            log.warn("[PlanValidator] 没有事件总线, 这条校验失败只会留在日志里 —— "
                    + "行为分析将看不到它, 而'她今天什么都没安排'会被误读成消极");
            return;
        }
        try {
            fabric.publish(PlanEvents.ValidationFailed.of(proposedBy, at,
                    result.describe(), rejectedPlanSnapshot, result.violations()));
        } catch (RuntimeException e) {
            // 发事件失败不该让校验本身失败 —— 校验的结论已经做出了
            log.error("[PlanValidator] 发布校验失败事件时出错", e);
        }
    }

    /** 现在有没有接事件总线。诊断用。 */
    public boolean recordsToFabric() {
        return fabric != null;
    }

    public String describe() {
        return "PlanValidator[" + (fabric == null ? "仅日志" : "事件可观测") + "]";
    }
}
