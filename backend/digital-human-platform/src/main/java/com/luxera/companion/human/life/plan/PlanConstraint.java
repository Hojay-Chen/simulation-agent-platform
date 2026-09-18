package com.luxera.companion.human.life.plan;

import com.luxera.companion.registry.DomainType;

import java.util.Objects;

/**
 * V2.2 §3.5.4 —— <b>对计划的一项约束</b>。
 *
 * <h2>为什么约束也必须是开放的</h2>
 * 用户要求这个架构能接三方平台软件。而"什么条件下这件事不能做"这件事,
 * <b>恰恰是三方最清楚而平台最不清楚的</b>:
 * <table border="1">
 *   <tr><th>约束</th><th>谁知道</th></tr>
 *   <tr><td>时间不重叠、时长不超限</td><td>平台（通用算术）</td></tr>
 *   <tr><td>依赖顺序、资源占用</td><td>平台（通用图与集合）</td></tr>
 *   <tr><td>{@code LaboratorySafetyConstraint} 实验安全</td><td><b>接入实验室系统的三方</b></td></tr>
 *   <tr><td>{@code ExamConstraint} 考试周禁止娱乐</td><td><b>接入教务系统的三方</b></td></tr>
 *   <tr><td>{@code WeatherConstraint} 下雨不外出</td><td><b>接入气象服务的三方</b></td></tr>
 *   <tr><td>{@code BudgetConstraint} 预算不够</td><td><b>接入记账应用的三方</b></td></tr>
 * </table>
 *
 * <p>如果约束是枚举（{@code enum ConstraintType {TIME, DEPENDENCY, …}）,
 * 上面后四行就永远接不进来 —— 因为平台作者不知道"实验室安全"这件事存在。
 *
 * <h2>{@link ConstraintResult} 里的 {@code reason} 为什么是必填的</h2>
 * 因为约束的失败会被<b>她读到</b>。用户描述的场景里, 她因为觉得冷而重排计划 ——
 * 那个"觉得冷"就是一条触觉刺激, 而"为什么不能按原计划"这件事必须能表达成
 * 一句人话, 才能进入 LLM 的 context。
 *
 * <p>一个只返回 {@code false} 的约束, 会让她看到"计划不可行"却不知道为什么 ——
 * 于是她的反应只能是重新随便排一个, 而不是针对性地解决（"那我先穿件衣服"）。
 * <b>返回布尔值而不返回理由, 就是剥夺了她做出正确反应所需的信息。</b>
 *
 * <h2>{@link Severity} 为什么可以是枚举</h2>
 * 因为它是<b>固定基础设施</b>: "硬约束 / 软约束 / 建议"这三种强度由
 * {@link PlanValidator} 的处理逻辑决定（硬约束违反 → 拒绝整个 Revision;
 * 软约束违反 → 接受但记警告; 建议 → 只记录）。三方不会带来第四种强度 ——
 * 如果它觉得需要, 那说明它想表达的是"违反的代价有多大", 那是一个数值,
 * 而不是一个新的类别。
 */
public interface PlanConstraint {

    ConstraintId id();

    /**
     * 这个约束在当前上下文下, 对某个计划项是否成立？
     *
     * <p><b>实现必须是纯函数</b> —— 同样的 {@code (item, context)} 必须得到同样的结果。
     * 为什么这条要求这么硬: 见 {@link PlanningContext} 的说明, 重排必须可复现。
     * 一个会读系统时间、读随机数、读全局缓存的约束实现, 会让"她 12:15 为什么那样排"
     * 永远无法回放验证。
     */
    ConstraintResult evaluate(PlanItem item, PlanningContext context);

    /**
     * 一句话说明这个约束是干什么的。
     *
     * <p>会出现在诊断面板上（"她今天的计划被哪些约束管着"）。
     * 一个写不出这句话的约束, 通常也说不清它到底在防什么。
     */
    default String describe() {
        return id().value();
    }

    /** 约束的身份。 */
    record ConstraintId(String value) {

        public ConstraintId {
            Objects.requireNonNull(value, "约束 id 不能为空");
            if (value.isBlank()) {
                throw new IllegalArgumentException("约束 id 不能是空白");
            }
        }

        public static ConstraintId of(String value) {
            return new ConstraintId(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * 一次约束判定的结果。
     *
     * @param satisfied 成立吗
     * @param reason    <b>一句人能读懂的话</b>。成立时也应给出（"时间不冲突"）,
     *                  因为它会出现在诊断输出里; 一个只有失败才有话的约束,
     *                  会让"为什么这次通过了"无从查证
     * @param severity  违反时的强度。成立时这个字段无意义, 但必须给一个值 ——
     *                  用 {@link Severity#HARD} 即可
     */
    record ConstraintResult(boolean satisfied, String reason, Severity severity) {

        public ConstraintResult {
            Objects.requireNonNull(reason, "约束判定必须给出理由 —— 它要进 LLM context。"
                    + "没有理由时请用空字符串, 但请先想一想为什么写不出理由");
            Objects.requireNonNull(severity, "违反强度不能为空");
        }

        public static ConstraintResult ok(String reason) {
            return new ConstraintResult(true, reason, Severity.HARD);
        }

        public static ConstraintResult violated(String reason, Severity severity) {
            return new ConstraintResult(false, reason, severity);
        }

        public static ConstraintResult hardViolation(String reason) {
            return new ConstraintResult(false, reason, Severity.HARD);
        }

        public static ConstraintResult softViolation(String reason) {
            return new ConstraintResult(false, reason, Severity.SOFT);
        }

        public static ConstraintResult advisory(String reason) {
            return new ConstraintResult(false, reason, Severity.ADVISORY);
        }

        /** 违反的是不是"会让整个 Revision 被拒绝"的那一类。 */
        public boolean blocking() {
            return !satisfied && severity == Severity.HARD;
        }

        public String describe() {
            return (satisfied ? "✔ " : "✘ ") + reason;
        }
    }

    /**
     * 违反后的强度。
     *
     * <p>三档的语义差别会直接改变 {@link PlanValidator} 的行为, 所以它们的边界要清楚:
     */
    enum Severity {

        /**
         * 硬约束: 违反 → <b>整个 Revision 被拒绝</b>。
         *
         * <p>用在哪: 物理上不可能的事（时间重叠、时长超过可用时间、依赖倒置）。
         * 这些东西如果允许进计划表, 调度器<b>一定</b>会在运行期出错 ——
         * 也就是把一个确定会发生的故障推迟到更难查的时刻。
         */
        HARD("硬约束 —— 违反则整个计划不被采纳"),

        /**
         * 软约束: 违反 → 采纳但记一条警告。
         *
         * <p>用在哪: "应该避免但可以破例"的事（熬夜写完作业、连续两小时不休息）。
         * 真人会破例, 而她破例本身<b>是有研究价值的行为数据</b> ——
         * 用硬约束挡住它, 等于把"她为了考试熬夜了"这个事实从数据里抹掉。
         */
        SOFT("软约束 —— 违反会被记下但计划仍然生效"),

        /**
         * 建议: 违反 → 只记录, 不警告。
         *
         * <p>用在哪: "更好的做法"（上午做数学比晚上效率高）。它不进告警,
         * 但会进行为分析的时间轴 —— 因为"她总是选效率低的时段做数学"
         * 是一个值得被发现的模式。
         */
        ADVISORY("建议 —— 只记录, 不产生告警");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
