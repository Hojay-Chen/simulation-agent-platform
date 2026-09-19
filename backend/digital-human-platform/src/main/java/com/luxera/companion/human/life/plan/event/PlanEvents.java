package com.luxera.companion.human.life.plan.event;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.ScheduledEvent;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.PlanTrigger;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §5.4 —— <b>{@code plan.*} 与 {@code system.plan-validation-failed} 事件的实现</b>。
 *
 * <h2>为什么这些实现类必须存在</h2>
 * {@link com.luxera.companion.registry.CoreEventCatalog} 里列了这五条事件的
 * 类型名、载荷字段、生产者、消费者。但<b>目录是索引, 不是实现</b> ——
 * 它只有名字和描述, 没有类。而真正让一条事件能在系统里流动的东西是:
 * <ol>
 *   <li>一个实现 {@link WorldEvent} 的类（这样 {@code EventFabric} 认得它）;</li>
 *   <li>标上 {@link DomainType}（这样它落库之后能读回来）;</li>
 *   <li>实现合适的能力接口 —— {@link ScheduledEvent} / {@link SensoryEvent} ——
 *       决定它进哪个存储结构。</li>
 * </ol>
 *
 * <p>没有第 3 步, 一条"到点了"的事件就只会被记进历史, 而不会进
 * {@code RealtimeEventQueue} —— 于是她<b>永远不会注意到该开始写作业了</b>。
 * 那正是本设计里"事件目录必须配实现"这句话的实际含义。
 *
 * <h2>五种事件分别进哪个结构</h2>
 * <table border="1">
 *   <tr><th>事件</th><th>能力接口</th><th>归宿</th><th>为什么</th></tr>
 *   <tr>
 *     <td>{@code plan.item-due.v1}</td><td>{@link SensoryEvent}</td>
 *     <td>RealtimeEventQueue</td>
 *     <td>"到点了"是一条<b>提醒</b> —— 她需要注意到它, 然后决定做什么。
 *         注意它不是 {@link ScheduledEvent}: 它不是在安排未来, 而是在提示现在</td>
 *   </tr>
 *   <tr>
 *     <td>{@code plan.item-interrupted.v1}</td><td>仅 {@link WorldEvent}</td>
 *     <td>EventStore; 触发 {@code PlanReplanner}</td>
 *     <td>它是<b>结果, 不是输入</b> —— 打断已经发生了, 这条事件是它的记录。
 *         让它实现 {@link ScheduledEvent} 会把它当成"一条新的安排"喂回计划表
 *         （窗口还在过去）, 而且绕开 {@code PlanValidator}。
 *         完整论证见 {@link ItemInterrupted} 的 javadoc</td>
 *   </tr>
 *   <tr>
 *     <td>{@code plan.revision-created.v1}</td><td>仅 {@link WorldEvent}</td>
 *     <td>EventStore</td>
 *     <td>它是<b>结果</b>, 不是输入。让它再进计划表会形成自环
 *         （新版本触发新版本）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code plan.item-scheduled.v1}</td><td>仅 {@link WorldEvent}</td>
 *     <td>EventStore; 前端日程视图</td>
 *     <td>通知性质 —— "计划表上多了一项"。计划表本身就是它的真相源</td>
 *   </tr>
 *   <tr>
 *     <td>{@code plan.item-finished.v1}</td><td>仅 {@link WorldEvent}</td>
 *     <td>EventStore</td>
 *     <td>通知性质 —— 用于对比计划与实际时长</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么全部放在一个文件里</h2>
 * 它们共享同一批字段（{@code itemId} / {@code intentType} / 时间窗）和同一段设计理由。
 * 拆成五个文件会让每条事件的注释重复一遍上面那张表 —— 而重复的注释迟早会分叉,
 * 于是五个文件里的说明有三个是过期的。
 */
public final class PlanEvents {

    private PlanEvents() {
        // 纯容器类
    }

    /** 这五条的类型 id —— 注册与订阅时用, 免得各处重复拼字符串。 */
    public static final EventTypeId ITEM_SCHEDULED = EventTypeId.parse("plan.item-scheduled.v1");
    public static final EventTypeId ITEM_DUE = EventTypeId.parse("plan.item-due.v1");
    public static final EventTypeId ITEM_FINISHED = EventTypeId.parse("plan.item-finished.v1");
    public static final EventTypeId ITEM_INTERRUPTED = EventTypeId.parse("plan.item-interrupted.v1");
    public static final EventTypeId REVISION_CREATED = EventTypeId.parse("plan.revision-created.v1");
    public static final EventTypeId VALIDATION_FAILED =
            EventTypeId.parse("system.plan-validation-failed.v1");

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>本文件里的六条事件, 由本类自己登记</b> —— 装配层调用。
     *
     * <h2>为什么登记这件事归这里</h2>
     * 因为"这六条一起构成计划侧的全部事件"这件事, 本来就只有本文件知道 ——
     * 上面那六个 {@link EventTypeId} 常量与下面那六个 record 是同一个事实的两半。
     * 让 {@link ItemDue} 自己登记自己也能跑, 但那样 {@code plan.*} 就没有任何一处
     * 能回答"计划域一共有哪些事件"; 而那张清单是诊断面板、启动日志与
     * §8.6.6 那条守卫共同要的东西。把入口留在这里, 清单与实现永远在同一个文件里。
     *
     * <h2>为什么这条清单不能靠"扫 plan.* 前缀"推出来</h2>
     * 因为六条里有一条<b>不在这个命名空间下</b>: {@link ValidationFailed} 是
     * {@code system.plan-validation-failed} —— 计划校验失败是"系统级故障",
     * 与"计划表上发生了什么"不是同一类事实(见它的 javadoc)。
     * 一条按前缀猜的装配会安静地漏掉它, 而漏掉的后果是"她今天为什么什么都没安排"
     * 这个问题在重启之后再没有答案 —— 那正是这条事件存在的全部理由。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条 —— 装配层把它汇总进启动日志。可重复调用: 同一个类登记两次
     *         在注册表那边是一次空操作, 所以装配层重复装配(或测试各自装配)不会炸
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(ItemScheduled.class);
        registry.register(ItemDue.class);
        registry.register(ItemFinished.class);
        registry.register(ItemInterrupted.class);
        registry.register(RevisionCreated.class);
        registry.register(ValidationFailed.class);
        return 6;
    }

    // ─────────────────────────── plan.item-scheduled.v1 ───────────────────────────

    /**
     * 计划表上多了一项。
     *
     * <p><b>为什么需要这条事件, 而不是让关心的人直接去问 {@code PlanBoard}</b>:
     * 问 {@code PlanBoard} 只能得到"现在有什么", 而答不出"什么时候加的、为什么加的"。
     * 行为分析要看的是后者 —— "她今天给自己加了几件事"是关于她的数据,
     * 而"她今天有几件事"只是现状的快照。
     */
    @DomainType(value = "plan.item-scheduled",
            description = "计划表上新增了一项安排")
    public record ItemScheduled(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String itemId,
            String intentType,
            String description,
            Instant windowStart,
            Instant windowEnd,
            String origin,
            long revisionNumber) implements WorldEvent {

        public ItemScheduled {
            Objects.requireNonNull(itemId, "计划项 id 不能为空");
            Objects.requireNonNull(intentType, "意图类型不能为空 —— 它让行为分析按类别统计");
            Objects.requireNonNull(description, "描述不能为空 —— 它进 LLM context");
        }

        /** 从一项计划安排构造。 */
        public static ItemScheduled of(PlanItem item, long revisionNumber, Instant at) {
            return new ItemScheduled(ITEM_SCHEDULED, at, "human.life.plan.PlanBoard",
                    item.id().value(), item.intent().getClass().getSimpleName(),
                    item.intent().description(), item.window().start(), item.window().end(),
                    item.origin().describe(), revisionNumber);
        }

        @Override
        public String describe() {
            return "安排 " + windowStart + "→" + windowEnd + " " + description
                    + " (来自 " + origin + ", " + revisionNumber() + " 版)";
        }
    }

    // ─────────────────────────── plan.item-due.v1 ───────────────────────────

    /**
     * 到点了 —— <b>这一条是她"注意到该开始做某事"的入口</b>。
     *
     * <h3>为什么它是 {@link SensoryEvent} 而不是 {@link ScheduledEvent}</h3>
     * 这是一个容易搞反的地方, 而搞反的后果很具体:
     * <ul>
     *   <li>如果它是 {@code ScheduledEvent}, 它会进计划表 —— 于是"到点了"这件事
     *       又变成了一项新安排, 而它到点之后又会产生一条"到点了"……
     *       <b>一个无限自我繁殖的计划表。</b></li>
     *   <li>作为 {@code SensoryEvent}, 它进 {@code RealtimeEventQueue},
     *       被 {@code AttentionService} 看到, 由 Mind 决定"现在开始做"还是
     *       "先把手头这点做完"。<b>这才是真的她的行为</b> —— 闹钟响了不等于她立刻起身。</li>
     * </ul>
     *
     * <h3>为什么用 {@code TACTILE}（触觉）通道</h3>
     * 因为时间是<b>无处不在且没有专门的感官</b>的。视觉刺激是"看到了什么",
     * 听觉是"听到了什么" —— 而"感觉时间到了"更像一种内在的触感/压迫感。
     * 把它归到任何一个外部感官都会误导下游的注意力模型
     * （"她听到了什么" → 去查听觉通道 → 什么也没有）。
     *
     * <h3>urgency 用计划项的优先级推出来</h3>
     * 一项 {@code CRITICAL} 的事到点了, 应当比一项 {@code TRIVIAL} 的事更能抓住她。
     * <b>但 {@code plan.item-due} 的 urgency 不等于优先级本身</b> ——
     * 它被压到 0.3~0.9 的区间里。理由: 一条独立刺激的"急"不该超过"火警"
     * （1.0）。优先级再高的作业, 也不该在队列里压过一条真实的危险信号。
     */
    @DomainType(value = "plan.item-due",
            description = "计划项到点了, 提示她该开始了")
    public record ItemDue(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String itemId,
            String intentType,
            String description,
            Instant windowStart,
            Instant windowEnd,
            int priorityLevel,
            long lateMillis) implements SensoryEvent {

        /** urgency 的下界 —— 一件微不足道的事到点了, 也该有基本的提示力。 */
        public static final double MIN_URGENCY = 0.3;

        /** urgency 的上界 —— 见类注释"urgency 用计划项的优先级推出来"。 */
        public static final double MAX_URGENCY = 0.9;

        public ItemDue {
            Objects.requireNonNull(itemId, "计划项 id 不能为空");
            Objects.requireNonNull(description, "描述不能为空");
        }

        public static ItemDue of(PlanTrigger trigger) {
            Objects.requireNonNull(trigger, "触发不能为空");
            PlanItem item = trigger.item();
            return new ItemDue(ITEM_DUE, trigger.firedAt(), "human.life.plan.PlanScheduler",
                    item.id().value(), item.intent().getClass().getSimpleName(),
                    item.intent().description(), item.window().start(), item.window().end(),
                    item.priority().level(), trigger.latenessMillis());
        }

        @Override
        public String modality() {
            return "TACTILE";
        }

        /**
         * {@code 0.3 + 优先级/100 * 0.6}, 落在 {@code [0.3, 0.9]}。
         *
         * <p>见类注释: 上限刻意低于 1.0, 好让"火警"这类真实的急事仍然能压过它。
         */
        @Override
        public double urgency() {
            return MIN_URGENCY + (priorityLevel / 100.0) * (MAX_URGENCY - MIN_URGENCY);
        }

        /**
         * 折叠键 —— <b>同一项在短时间内重复的"到点了"应该折叠</b>。
         *
         * <p>为什么需要: 一个被推迟的计划项会在每个 tick 都满足"到点了"
         * （它的 {@code start_at} 已经过去了）。虽然 {@code PlanBoard} 做了去重,
         * 但重排之后产生的新项会重新满足条件 —— 于是队列里可能出现
         * "同一件事的第三次提醒"。折叠键让它们合成一条。
         *
         * <p>注意折叠后保留的是<b>最早那条的定序</b>（见 {@code RealtimeEventQueue}）——
         * 也就是说第三次提醒不会排到第一次前面去。
         */
        @Override
        public String foldingKey() {
            return "plan-due:" + itemId;
        }

        /** 迟到了多久 —— 见 {@code PlanTrigger} 关于"这是拖延的度量"的说明。 */
        public Duration lateness() {
            return Duration.ofMillis(lateMillis);
        }

        @Override
        public String stimulusDescribe() {
            return "「" + description + "」到点了" + (lateMillis > 1000 ? " (迟 " + lateMillis + "ms)" : "");
        }
    }

    // ─────────────────────────── plan.item-finished.v1 ───────────────────────────

    /**
     * 一项做完了。
     *
     * <p><b>实际时长与计划时长的对比是本事件最有价值的部分</b>: 它是"她有多了解自己"
     * 这个问题的原始数据。一个总是低估自己所需时间的 agent, 其计划表会持续地
     * 排得过满 —— 而那不是"意志力问题", 是<b>自我认知偏差</b>, 是行为分析该发现的模式。
     */
    @DomainType(value = "plan.item-finished",
            description = "计划项完成, 带实际耗时")
    public record ItemFinished(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String itemId,
            String intentType,
            String description,
            Instant actualStart,
            Instant actualEnd,
            long plannedMillis,
            String note) implements WorldEvent {

        public ItemFinished {
            Objects.requireNonNull(itemId, "计划项 id 不能为空");
            Objects.requireNonNull(description, "描述不能为空");
            note = note == null ? "" : note;
        }

        public static ItemFinished of(PlanItem item, Instant startedAt, Instant endedAt, String note) {
            return new ItemFinished(ITEM_FINISHED, endedAt, "human.life.plan.PlanBoard",
                    item.id().value(), item.intent().getClass().getSimpleName(),
                    item.intent().description(), startedAt, endedAt,
                    item.window().duration().toMillis(), note);
        }

        public Duration actualDuration() {
            return Duration.between(actualStart, actualEnd);
        }

        public Duration plannedDuration() {
            return Duration.ofMillis(plannedMillis);
        }

        /**
         * 实际比计划多用了多少。负数表示提前完成。
         *
         * <p>这个数值会被累积成"她的时间估计偏差", 进而用来修正
         * {@code PlanIntent.expectedDuration()} 的默认值 —— 也就是说<b>她可以学会</b>
         * 更准确地估计自己需要多久。
         */
        public Duration estimationError() {
            return actualDuration().minus(plannedDuration());
        }

        @Override
        public String describe() {
            return "完成「" + description + "」用时 " + actualDuration().toMinutes()
                    + " 分钟 (计划 " + plannedDuration().toMinutes() + " 分钟, 偏差 "
                    + estimationError().toMinutes() + " 分钟)";
        }
    }

    // ─────────────────────────── plan.item-interrupted.v1 ───────────────────────────

    /**
     * 正在做的事被打断了 —— <b>本设计里最重要的一条计划事件</b>。
     *
     * <h3>它的语义被用户亲自改写了一遍</h3>
     * 用户否定了 V2.1 的写法, 并明确要求:
     * <blockquote>
     * 打断当前正在做的计划 event, <b>不是</b>简单把当前在做的计划 event 更新剩余时间
     * 然后立马执行一个计划 event, 再把被中断的计划 event 继续执行,
     * 他<b>是真的改变了计划表</b>, 让 agent 重新思考重排计划表
     * </blockquote>
     *
     * <p>这条要求在数据上体现为: <b>本事件之后必定跟着一个新的
     * {@link PlanRevision}</b>。所以它的消费者是 {@code PlanReplanner}, 而不是
     * 某个"暂停"处理器。
     *
     * <h3>{@link #elapsedMillis} 是什么, 不是什么</h3>
     * 它是"到被打断为止已经做了多久"—— 一个<b>只读的观察值</b>, 用于让重排器判断
     * "这件事值得继续吗"（做了 5 分钟和做了 55 分钟, 正确的决定不同）。
     *
     * <p>它<b>不是</b> {@code remainingDuration} —— 不存在"剩余"这个概念, 因为
     * 一旦认为存在剩余量, 就等于假定了"她会继续"。而用户明确说了她可能
     * "完全不再继续写作业, 把写作业直接从计划表删掉"。
     *
     * <p>载荷里用 {@code elapsedMillis} 而不是 {@code elapsedMs} 之外的任何形式,
     * 是因为它会进 JSONB 并被人直接读 —— 毫秒整数比 ISO-8601 时长字符串更好做算术。
     *
     * <h3>为什么它<b>只</b>实现 {@link WorldEvent}, 而不是 {@link ScheduledEvent} —— 一处更正</h3>
     * 本类开头的表格原先写着这条事件的能力接口是 {@link ScheduledEvent}, 归宿是
     * "PlanBoard 侧（触发重排）"。那个判断是错的, 而它先以一个编译错误的形式暴露出来,
     * 顺带拦下了一个更严重的运行期错误。
     *
     * <p><b>① 编译不过: 组件名与方法名撞车。</b>
     * 本记录有一个 {@code String intentType} 组件（会自动生成 {@code String intentType()}）,
     * 而 {@link ScheduledEvent} 声明的是 {@code EventTypeId intentType()} —— 同名不同返回类型,
     * javac 报 {@code invalid accessor method in record ItemInterrupted}。
     *
     * <p>那次撞车逼出一个更该问的问题: {@code intentType()} 该返回什么? 原先的实现在那里
     * 返回 {@code ITEM_INTERRUPTED}, 也就是<b>本事件自己的类型</b>。那是答非所问 ——
     * 那个方法的契约是"被安排的那件事是什么"（{@link ScheduledEvent#intentType()} 的 javadoc
     * 举的例子是 {@code life.write-homework.v1}）, 而"我是一个 item-interrupted 事件"
     * 没有回答它。（现在那个方法已整个删掉; 身份信息在 {@link #typeId()} 里, 不缺。）
     *
     * <p><b>② 更严重的: 它会被当成"一条新的安排"喂回计划表。</b>
     * {@code DefaultEventFabric.route} 对 {@code ScheduledEvent} 的处理是投给所有
     * {@code ScheduledEventSink}, 而在生产装配里那个 sink 就是计划表。于是:
     *
     * <pre>
     *   她正写作业 → 被打断 → Life.interruptCurrent
     *      → 发 plan.item-interrupted.v1
     *      → fabric 按 ScheduledEvent 路由 → 计划表 sink
     *      → 计划表收到一个窗口为 [打断时刻 − 已做时长, 打断时刻] 的"安排"
     *      → 她被排上了一件<b>窗口已经在过去的</b>事 → 立刻是 overdue
     * </pre>
     *
     * <p>这正是用户明确否定的那件事的机器版 —— 把被打断的时间窗原样塞回计划表,
     * 就是"简单把当前在做的计划 event 更新剩余时间然后立马执行一个计划 event"。
     * 而它还会绕开 {@code PlanValidator}: 走 sink 进计划表是没有校验的一步,
     * 可"被校验的与被采纳的是同一条路径"是整条重排流水线存在的理由。
     *
     * <p><b>③ 正确的形状。</b>本类与 {@link ItemScheduled} / {@link ItemFinished} /
     * {@link RevisionCreated} 一样, 只实现 {@link WorldEvent} —— 它是<b>结果, 不是输入</b>。
     * 表格里 {@code revision-created} 那一行早就写着"它是结果, 不是输入。让它再进计划表
     * 会形成自环"; 本记录只是把同一条判据用在了同类的另一条事件上。
     *
     * <p>打断 → 重排那条路是显式的、由 Human 侧自己走的, 不绕事件总线:
     * <pre>
     *   Life.interruptCurrent(at, by, urgency)
     *     → 结束当前的 Activity
     *     → 发本事件（供 EventStore 与行为分析观察）
     *     → Mind 据此构造 ReplanningContext
     *     → Life.replan(context, replanner)
     *         → ReplanProposal → PlanValidator.accept → PlanBoard.apply
     * </pre>
     *
     * <p>信息一点没少, 而每一步都在它该在的那一侧。
     */
    @DomainType(value = "plan.item-interrupted",
            description = "正在执行的计划项被刺激打断, 必须触发重排")
    public record ItemInterrupted(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String itemId,
            String intentType,
            String description,
            String byStimulus,
            Instant interruptedAt,
            long elapsedMillis,
            double stimulusUrgency) implements WorldEvent {

        /** 被打断那一项的最小可打断度 —— 见 {@link #interruptibility()}。 */
        public static final double DEFAULT_INTERRUPTIBILITY = 0.5;

        public ItemInterrupted {
            Objects.requireNonNull(itemId, "被打断的计划项 id 不能为空");
            Objects.requireNonNull(description, "描述不能为空");
            Objects.requireNonNull(byStimulus, "必须说明是被什么打断的 —— "
                    + "这句话是'她为什么改主意了'的答案来源");
        }

        /**
         * 从被打断的那一项和打断它的刺激构造。
         *
         * <p>注意 {@code byStimulus} 是一个<b>可读的摘要</b>, 不是刺激的类型 id。
         * 理由: 这句话会直接进 LLM context 和行为分析报告, 而
         * {@code "device.notification.received.v1@2026-09-19T12:15:00Z"}
         * 对人（和模型）都不是一个有用的句子。
         */
        public static ItemInterrupted of(PlanItem item, Instant interruptedAt,
                                        String byStimulusSummary, double stimulusUrgency) {
            Duration elapsed = Duration.between(item.window().start(), interruptedAt);
            long elapsedMillis = Math.max(0, elapsed.toMillis());
            return new ItemInterrupted(ITEM_INTERRUPTED, interruptedAt,
                    "human.mind.decision.DecisionEngine", item.id().value(),
                    item.intent().activityType(), item.intent().description(),
                    byStimulusSummary, interruptedAt, elapsedMillis, stimulusUrgency);
        }

        /** 到被打断为止已经做了多久。见类注释"不是什么"。 */
        public Duration elapsed() {
            return Duration.ofMillis(elapsedMillis);
        }

        /**
         * 被打断那一项的窗口起点 —— <b>推导值, 不是 {@link ScheduledEvent#windowStart()} 的实现</b>。
         *
         * <p>本记录<b>刻意不实现 {@link ScheduledEvent}</b>, 所以下面这两个方法与那个接口无关
         * （连 {@code @Override} 都没有）。保留它们是因为它们有用: "她是从几点开始做这件事的"
         * 是重排器和行为分析都要问的问题, 而答案就藏在这里。
         */
        public Instant windowStart() {
            return interruptedAt.minusMillis(elapsedMillis);
        }

        /**
         * 窗口终点 = 被打断时刻 —— 因为这一项的时间窗<b>到此为止</b>了。
         *
         * <p>注意它是"到此为止", 不是"安排到这一刻"。见下面 {@link #whyNotScheduledEvent()}。
         */
        public Instant windowEnd() {
            return interruptedAt;
        }

        /**
         * 这一项有多容易被"让路"。
         *
         * <p><b>不是</b> {@link ScheduledEvent#interruptibility()} 的实现 —— 本记录不实现那个
         * 接口（理由见上面那段"为什么它只实现 WorldEvent"）, 所以这里没有 {@code @Override}。
         * 保留它是因为重排器确实要问这个问题。
         *
         * <p>刻意用固定值而不是从计划项推出来: 被打断的<b>难易</b>取决于她当时
         * 在做的事（写作业容易被打断, 手术不容易）, 而那需要具体 {@code PlanIntent}
         * 的知识 —— 本事件不该携带它（那会让消费方开始按类型判断）。
         * 需要更精细的打断判定时, 那个逻辑属于 {@code DecisionEngine}。
         */
        public double interruptibility() {
            return DEFAULT_INTERRUPTIBILITY;
        }

        @Override
        public String describe() {
            return "「" + description + "」被「" + byStimulus + "」打断 (已做 "
                    + elapsed().toMinutes() + " 分钟) —— 需要重排";
        }
    }

    // ─────────────────────────── plan.revision-created.v1 ───────────────────────────

    /**
     * 计划表产生了一个新版本。
     *
     * <h3>用户要求的核心语义</h3>
     * <blockquote>
     * 他是真的改变了计划表, 让 agent 重新思考重排计划表。因此这条事件之后跟着的是
     * 一个新的 {@link PlanRevision}
     * </blockquote>
     *
     * <h3>"旧版本永不删除"</h3>
     * 这条事件是审计、回放、调试、行为分析、Agent 学习五件事的共同地基。
     * 没有它, "她上周三的计划长什么样" 无法回答, 而"她为什么改主意了"
     * 也就失去了可对照的基线。
     *
     * <p>所以载荷里带着 {@code previousRevisionId} —— <b>版本链是显式的,
     * 而不是靠"按 revisionNumber 排序"隐含推出来的</b>。显式的链能经受住
     * "某个中间版本只在数据库里、内存里已经被淘汰"这种情况
     * （见 {@code PlanBoard.IN_MEMORY_HISTORY_LIMIT}）。
     */
    @DomainType(value = "plan.revision-created",
            description = "计划表的新版本, 带完整的改动清单与理由")
    public record RevisionCreated(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String revisionId,
            String previousRevisionId,
            long revisionNumber,
            String reason,
            List<String> mutations,
            int liveItemCount,
            int abandonedCount) implements WorldEvent {

        public RevisionCreated {
            Objects.requireNonNull(revisionId, "版本 id 不能为空");
            Objects.requireNonNull(reason, "版本必须有理由 —— 这是它存在的意义");
            mutations = mutations == null ? List.of() : List.copyOf(mutations);
        }

        /**
         * 从一个 {@link PlanRevision} 构造。
         *
         * <p>改动清单在这里被<b>转成字符串</b>（{@link PlanMutation#describe()}）,
         * 而不是保留成 {@code PlanMutation} 对象。理由: 这条事件的消费者是
         * 行为分析与前端, 它们要的是可读的句子; 而保留对象会要求它们认识
         * 全部六种 mutation 类型 —— 于是每一个消费方都会长出一个小小的
         * {@code switch}, 而那些 switch 就是"枚举思维"的复发。
         *
         * <p>需要结构化改动时去读 {@code plan_revision} 表的 JSONB 列 ——
         * 那一份是给机器读的。
         */
        public static RevisionCreated of(PlanRevision revision, Instant at) {
            return new RevisionCreated(REVISION_CREATED, at,
                    "human.life.plan.PlanBoard",
                    revision.revisionId(),
                    revision.previousRevisionId().orElse(""),
                    revision.revisionNumber(),
                    revision.reason(),
                    revision.mutations().stream().map(PlanMutation::describe).toList(),
                    revision.liveItems().size(),
                    revision.abandonedItems().size());
        }

        /** 这一版相对上一版做了哪些改动, 一行一条。 */
        public String mutationsSummary() {
            return mutations.isEmpty() ? "（没有改动）" : String.join("; ", mutations);
        }

        @Override
        public String describe() {
            return revisionId + " 「" + reason + "」 —— 现存 " + liveItemCount
                    + " 项, 改动 " + mutations.size() + " 处";
        }
    }

    // ─────────────────── system.plan-validation-failed.v1 ───────────────────

    /**
     * LLM 提出的计划没能通过 {@code PlanValidator} 的校验。
     *
     * <h3>它<b>不能</b>被静默丢弃</h3>
     * 设计文档 §3.5.6 把这条写成了硬要求:
     * <blockquote>
     * LLM 的输出<b>必须</b>经过 {@code PlanValidator} 校验才能成为 Revision ——
     * 校验不过的重排被丢弃, 并记录一条 {@code PlanValidationFailed}
     * （<b>这是可观测的, 不是静默丢弃</b>）
     * </blockquote>
     *
     * <p>理由在"可观测"三个字上: 一个悄悄失败的规划, 在行为分析里会表现为
     * <b>"她今天什么都没安排"</b> —— 而真相是"她的规划器一直在报错"。
     * 这两者在数据上完全不同, 但如果没有这条事件, 它们就长得一模一样。
     * 研究者会据此得出"她今天很消极"的结论, 而那个结论是假的。
     *
     * <h3>为什么载荷里带 {@code rejectedPlan}</h3>
     * 因为"LLM 错在哪"这件事只有看到它提出的东西才能回答。而这正是
     * 改进 prompt、改进工具描述、改进 {@code PlanValidator} 的唯一输入。
     * <b>丢掉被拒绝的方案, 就等于丢掉了唯一的反馈信号。</b>
     */
    @DomainType(value = "system.plan-validation-failed",
            description = "LLM 提出的计划未通过校验, 必须可观测")
    public record ValidationFailed(
            EventTypeId typeId,
            Instant occurredAt,
            String sourceObjectId,
            String proposedBy,
            String reason,
            Map<String, Object> rejectedPlan,
            List<String> violations) implements WorldEvent {

        public ValidationFailed {
            Objects.requireNonNull(reason, "必须说明为什么被拒 —— 它是改进的唯一输入");
            proposedBy = proposedBy == null ? "unknown" : proposedBy;
            rejectedPlan = rejectedPlan == null ? Map.of() : Map.copyOf(rejectedPlan);
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public static ValidationFailed of(String proposedBy, Instant at, String reason,
                                         Map<String, Object> rejectedPlan, List<String> violations) {
            return new ValidationFailed(VALIDATION_FAILED, at,
                    "human.life.plan.PlanValidator", proposedBy, reason, rejectedPlan, violations);
        }

        @Override
        public String describe() {
            return "计划校验失败 (" + proposedBy + "): " + reason
                    + (violations.isEmpty() ? "" : " —— " + String.join("; ", violations));
        }
    }
}
