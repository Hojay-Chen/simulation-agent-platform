package com.luxera.companion.human.life.plan;

import com.luxera.companion.boundary.event.EventTypeId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §3.5.6 —— <b>重排所需的全部事实</b>。
 *
 * <h2>它<b>不</b>包含什么, 比它包含什么更重要</h2>
 * 设计文档 §3.5.6 特意写了一行:
 * <blockquote>
 * {@code ReplanningContext} 包含重排所需的全部事实（<b>不包含"当前计划该怎么继续"
 * 这种暗示</b>）
 * </blockquote>
 *
 * <p>这一行是针对用户明确否定的那条思路:
 * <blockquote>
 * 打断当前正在做的计划 event, <b>不是</b>简单把当前在做的计划 event
 * 更新剩余时间然后立马执行一个计划 event, 再把被中断的计划 event 继续执行
 * </blockquote>
 *
 * <p>"暂停/恢复"的思路之所以错, 根子在它的<b>输入</b>里就已经预设了答案:
 * 一旦 context 里有 {@code remainingDuration} 这种东西, 重排器能做的推理就只剩
 * "把它排到什么时候继续"。<b>而一个"她可能根本不想继续了"的世界,
 * 不能从一个已经假定她会继续的输入出发。</b>
 *
 * <p>所以本类里没有剩余时长、没有"进度百分比"、没有"继续/放弃"的倾向字段。
 * 它只给事实: 现在几点、她什么状态、正在做什么、有哪些事还没被消化、有什么约束。
 *
 * <h2>{@link #active} 给的是什么</h2>
 * 它描述"她此刻在做什么", 而<b>不</b>暗示"这件事应该继续"。重排器可以据此
 * 产出 {@link PlanMutation.Replace}（换掉）、{@link PlanMutation.Move}（推后）、
 * {@link PlanMutation.KeepActive}（继续）, 三者的代价在 context 里是同等的。
 *
 * <h2>为什么 {@link #trigger} 单独一个字段</h2>
 * 因为"为什么现在要重排"这件事必须显式。一次重排可能是被刺激触发的
 * （觉得冷）、也可能是被时间触发的（到点了要做下一项）、还可能是被外部指令触发的
 * （用户说"别去了"）。三者的正确反应完全不同, 而它们的区别只体现在这个字段上。
 */
public record ReplanningContext(
        Instant now,
        PlanRevision currentRevision,
        Optional<ActiveExecution> active,
        PlanningContext.HumanSnapshot human,
        List<UnresolvedEvent> events,
        List<PlanConstraint> constraints,
        PlanningGoals goals,
        Trigger trigger) {

    public ReplanningContext {
        Objects.requireNonNull(now, "重排必须带仿真时刻");
        Objects.requireNonNull(currentRevision, "重排必须能看到当前的计划 —— 即使它是空的");
        active = active == null ? Optional.empty() : active;
        Objects.requireNonNull(human, "重排必须能看到她的状态");
        events = events == null ? List.of() : List.copyOf(events);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        Objects.requireNonNull(goals, "重排必须知道她的长期目标 —— 否则重排可能偏离它们");
        Objects.requireNonNull(trigger, "重排必须说明为什么现在重排");
    }

    /** 她此刻正在做的事。 */
    public record ActiveExecution(
            PlanItemId itemId,
            String description,
            Instant startedAt,
            TimeWindow window) {

        public ActiveExecution {
            Objects.requireNonNull(itemId, "正在做的事必须有 id");
            Objects.requireNonNull(description, "正在做的事必须有描述 —— 它进 LLM context");
            Objects.requireNonNull(startedAt, "开始时刻不能为空");
            Objects.requireNonNull(window, "时间窗口不能为空");
        }

        /** 已经做了多久。 */
        public java.time.Duration elapsed(Instant now) {
            return java.time.Duration.between(startedAt, now);
        }

        public static ActiveExecution of(PlanItem item, Instant startedAt) {
            return new ActiveExecution(item.id(), item.intent().description(), startedAt, item.window());
        }
    }

    /**
     * 一条"还没被消化"的事件。
     *
     * <p>为什么是<b>摘要</b>而不是原始的 {@code WorldEvent}: 重排器需要的不是
     * 事件的完整载荷, 而是"发生了什么、多急、什么时候"。而原始事件带着它对
     * 具体类型的依赖 —— 重排器一旦拿到具体类型, 就会开始写
     * {@code if (event instanceof ColdStimulus)}。
     *
     * <p>设计文档 §3.5.6 把这条禁令写成了代码对照:
     * <pre>{@code
     * // ❌ 禁止
     * if (event.typeId().equals("environment.temperature-changed.v1")) { insertWearClothesPlan(); }
     * // ✅ 正确
     * PlanRevision replan(ReplanningContext context);
     * }</pre>
     *
     * <p>注意这里<b>保留</b>了 {@code typeId}。它是给 LLM 读的（"有一条关于温度的刺激"）,
     * 而不是给重排器的 {@code if} 用的。这个区别在评审时要盯住。
     */
    public record UnresolvedEvent(
            EventTypeId typeId,
            Instant occurredAt,
            String summary,
            double urgency) {

        public UnresolvedEvent {
            Objects.requireNonNull(typeId, "事件必须有类型 id —— 它要进 LLM context");
            Objects.requireNonNull(occurredAt, "事件必须有发生时刻");
            Objects.requireNonNull(summary, "事件必须有一句人能读懂的摘要");
            if (urgency < 0.0 || urgency > 1.0) {
                throw new IllegalArgumentException(
                        "urgency 应在 0..1 之间, 收到 " + urgency
                                + " —— 越界的值会让'谁更急'的比较失去意义");
            }
        }

        /** 最急的那条 —— 重排器常常只需要这一个。 */
        public static Optional<UnresolvedEvent> mostUrgent(List<UnresolvedEvent> events) {
            return events.stream().max(java.util.Comparator.comparingDouble(UnresolvedEvent::urgency));
        }
    }

    /**
     * 她的长期目标。
     *
     * <p>为什么重排必须看到它: 一次重排可以自由地在未来一小时里重新安排,
     * 但<b>不能偏离她长期在做的事</b>。一个看不到目标的局部重排器会把
     * "推进毕业论文"慢慢挤掉 —— 每一次挤压都局部合理, 而累积结果是
     * "她这个月一个字都没写"。
     *
     * <p>而那种失败模式最危险的地方是: <b>它在每一步都看起来是对的。</b>
     */
    public record PlanningGoals(List<String> goals, Map<String, Double> weights, String summary) {

        public static final PlanningGoals NONE = new PlanningGoals(List.of(), Map.of(), "暂无长期目标");

        public PlanningGoals {
            goals = goals == null ? List.of() : List.copyOf(goals);
            weights = weights == null ? Map.of() : Map.copyOf(weights);
            Objects.requireNonNull(summary, "目标的摘要不能为 null —— 它进 LLM context");
        }

        public boolean isEmpty() {
            return goals.isEmpty();
        }
    }

    /**
     * 为什么现在要重排。
     *
     * <h3>为什么是 record + 常量, 而不是枚举</h3>
     * 三种触发看起来是封闭的, 但<b>第三方会带来第四种</b>: 接入了教务系统的三方
     * 可能因为"课表变了"而要求重排; 接入了气象服务的三方可能因为"暴雨预警"而要求重排。
     * 这些都是"外部世界通知", 但它们的语义各不相同, 而重排器对它们的反应也不同。
     *
     * <p>枚举在这里会逼着三方把自己塞进 {@link #STIMULUS} 里 —— 于是
     * "她觉得冷了"和"暴雨预警"变成同一件事, 而前者需要她自己去穿衣服,
     * 后者需要她取消外出。那是两种完全不同的重排。
     *
     * @param kind   触发的类别（用于分组统计）
     * @param detail 一句话说明（进 LLM context）
     */
    public record Trigger(String kind, String detail) {

        /** 到点了 —— 时间推动的重排（"该做下一项了"）。 */
        public static final String KIND_TIME = "time";

        /** 感官刺激 —— 觉得冷、听到铃声、闻到味道。 */
        public static final String KIND_STIMULUS = "stimulus";

        /** 外部指令 —— 用户说"别去了"。 */
        public static final String KIND_INSTRUCTION = "instruction";

        /** 先前不可行的事现在可行了（"手机有电了"）。 */
        public static final String KIND_FEASIBILITY = "feasibility";

        /** 身体状态变化跨过了阈值（"太累了, 撑不住了"）。 */
        public static final String KIND_BODY = "body";

        public static final String KIND_OTHER = "other";

        public Trigger {
            Objects.requireNonNull(kind, "触发类别不能为空");
            Objects.requireNonNull(detail, "触发必须有说明 —— 它是要写进历史的理由");
        }

        public static Trigger time(String detail) {
            return new Trigger(KIND_TIME, detail);
        }

        public static Trigger stimulus(String detail) {
            return new Trigger(KIND_STIMULUS, detail);
        }

        public static Trigger instruction(String detail) {
            return new Trigger(KIND_INSTRUCTION, detail);
        }

        public static Trigger body(String detail) {
            return new Trigger(KIND_BODY, detail);
        }

        public boolean isStimulusDriven() {
            return KIND_STIMULUS.equals(kind) || KIND_BODY.equals(kind);
        }

        public String describe() {
            return kind + ": " + detail;
        }
    }

    // ─────────────────────────── 派生视图 ───────────────────────────

    /** 她此刻正在做的那一项（如果有的话, 从当前版本里查出来）。 */
    public Optional<PlanItem> activeItem() {
        return active.flatMap(a -> currentRevision.find(a.itemId()));
    }

    /**
     * 当前版本里仍然占着未来的项, 按开始时间排序。
     *
     * <p>这是重排器"看现状"的入口。注意它<b>不含</b>被替代/取消的项 ——
     * 那些是历史, 而重排关心的是未来。
     */
    public List<PlanItem> liveItems() {
        return currentRevision.liveItems();
    }

    /** 她此刻归哪一项（按时间窗口, 而不是按 active 字段）。 */
    public List<PlanItem> itemsCoveringNow() {
        return currentRevision.activeAt(now);
    }

    /** 最急的那条未消化事件。 */
    public Optional<UnresolvedEvent> mostUrgentEvent() {
        return UnresolvedEvent.mostUrgent(events);
    }

    /** 她能不能用某个能力。 */
    public boolean can(String capabilityKey) {
        return planningContext().can(capabilityKey);
    }

    /** 构造一个 {@link PlanningContext} —— 供 {@link PlanIntent#evaluate} 使用。 */
    public PlanningContext planningContext() {
        return new PlanningContext(now, human, constraints, java.util.Set.of(), Map.of(), Map.of());
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("重排上下文[").append(now).append(" 触发=").append(trigger.describe()).append(']');
        sb.append("\n  当前: ").append(currentRevision.revisionId())
                .append(" (").append(liveItems().size()).append(" 项生效)");
        active.ifPresent(a -> sb.append("\n  正在做: ").append(a.description())
                .append(" (已 ").append(a.elapsed(now).toMinutes()).append(" 分钟)"));
        sb.append("\n  她的状态: ").append(human.summary());
        if (!events.isEmpty()) {
            sb.append("\n  未消化的事件: ");
            events.forEach(e -> sb.append("\n    - [").append(e.typeId().name())
                    .append("] ").append(e.summary()));
        }
        if (!goals.isEmpty()) {
            sb.append("\n  长期目标: ").append(goals.summary());
        }
        return sb.toString();
    }
}
