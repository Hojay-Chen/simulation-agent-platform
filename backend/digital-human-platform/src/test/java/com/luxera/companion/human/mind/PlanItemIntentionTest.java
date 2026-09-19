package com.luxera.companion.human.mind;

import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanPriority;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.human.mind.intention.Feasibility;
import com.luxera.companion.human.mind.intention.Intention;
import com.luxera.companion.human.mind.intention.IntentionContext;
import com.luxera.companion.human.mind.intention.IntentionId;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.PlanItemIntention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §8.5.13 —— {@link PlanItemIntention} 这条规则桥。
 *
 * <h2>这些断言在防什么</h2>
 * 桥的全部工作是<b>转述</b>: 把计划侧本来就会回答的那几件事, 原封不动地告诉认知侧。
 * 而"转述"最容易出的错是<b>某一条抄错了而其余都对</b> —— 那种错误不会让任何东西崩,
 * 只会让她在某一种处境下的判断与计划侧不一致, 而且只在同时走过两侧的路径上显形。
 *
 * <p>所以这里逐字段钉: 身份、描述、优先级(等级与 label <b>两个都要</b>)、
 * 需要的能力、活动类型、时长、能不能挪、可行性、动作拆分、以及回到计划侧时的身份。
 * 每一条都写清"抄错了会怎样" —— 因为下一处改动的人需要知道他在冒什么险。
 */
class PlanItemIntentionTest {

    private static final Instant T = Instant.parse("2026-09-19T09:00:00Z");
    private static final String CAP = "chat.reply";

    /** 一个测试用的意图: 可行性与动作都能当场指定。 */
    private record FakeIntention(IntentionId id, String description, IntentionPriority priority,
                                 Set<String> requiredCapabilities, String activityType,
                                 Duration expectedDuration, boolean movableInTime,
                                 Feasibility feasibility,
                                 List<PlanIntent.ActionIntent> steps) implements Intention {

        @Override
        public Feasibility evaluate(IntentionContext context) {
            return feasibility;
        }

        @Override
        public List<PlanIntent.ActionIntent> actions(IntentionContext context) {
            return steps;
        }
    }

    private static FakeIntention feasibleIntention(Duration expected) {
        return new FakeIntention(IntentionId.of("intent-1"), "把今天的实验做完",
                IntentionPriority.IMPORTANT, Set.of(CAP), "life.activity.study",
                expected, true, Feasibility.yes("课本在书包里"),
                List.of(PlanIntent.ActionIntent.of(CAP, "回她一句")));
    }

    private static PlanItem planItem(PlanIntent intent, PlanPriority priority, Duration windowLength) {
        return PlanItem.schedule(intent,
                        TimeWindow.startingAt(T.minusSeconds(600), windowLength), priority)
                .withCreatedInRevision(3);
    }

    /** 排定这一项时用的那份处境 —— 桥必须握着它。 */
    private static PlanningContext planningAt(Instant now) {
        return PlanningContext.minimal(now, PlanningContext.HumanSnapshot.unknown());
    }

    private static PlanItemIntention bridge(PlanItem item) {
        return new PlanItemIntention(item, planningAt(T));
    }

    // ─────────────────────────── 身份 ───────────────────────────

    @Test
    @DisplayName("★ 身份取自计划项, 不是它包着的那个意图 —— 否则重排后的新版本与老版本会撞名")
    void idComesFromThePlanItemNotTheIntention() {
        PlanItem item = planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60));

        PlanItemIntention it = bridge(item);

        assertEquals(item.id().value(), it.id().value(),
                "桥的身份必须是 PlanItemId。用 item.intent().id() 的话, 重排后新生成的那一项"
                        + "(PlanItem 的类注释: 任何改动都产生新的 id)会与老版本在候选清单里"
                        + "长得一模一样, 而它们说的不是同一件事了");
        assertNotEquals(item.intent().id().value(), it.id().value(),
                "这一条是上一条的阳性对照: 计划项 id 与意图 id 本来就不同, "
                        + "所以'取哪一个'是一个真的选择, 不是同一个值的两种写法");
    }

    // ─────────────────────────── 转述 ───────────────────────────

    @Test
    @DisplayName("描述与需要的能力原样转述 —— 它们是可行性判断与 LLM context 的依据")
    void descriptionAndCapabilitiesAreForwarded() {
        FakeIntention intent = feasibleIntention(Duration.ofMinutes(30));
        PlanItemIntention it = bridge(planItem(intent.toPlan(), PlanPriority.ROUTINE,
                Duration.ofMinutes(60)));

        assertEquals("把今天的实验做完", it.description());
        assertEquals(Set.of(CAP), it.requiredCapabilities());
        assertEquals("life.activity.study", it.activityType());
        assertTrue(it.movableInTime());
    }

    @Test
    @DisplayName("★ 优先级照抄计划侧的等级与 label —— 措辞不归一化, 因为'这条 label 是计划侧说的'要看得出来")
    void priorityIsCopiedVerbatimFromThePlanSide() {
        PlanItemIntention it = bridge(planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60)));

        assertEquals(PlanPriority.ROUTINE.level(), it.priority().level(),
                "等级是两边的共同量纲 —— 它错了会直接改变'这件事压不压得过她手上那件'的答案");
        assertEquals(PlanPriority.ROUTINE.label(), it.priority().label(),
                "label 必须照抄计划侧的说法");
        assertNotEquals(IntentionPriority.ROUTINE.label(), it.priority().label(),
                "两边对同一档的措辞本来就不同(计划侧叫「" + PlanPriority.ROUTINE.label()
                        + "」, 认知侧叫「" + IntentionPriority.ROUTINE.label() + "」)。"
                        + "这条断言钉住的是<b>不归一化</b>这个决定: 一旦有人把 label 换成认知侧的"
                        + "措辞, 上面那句 assertEquals 就会红 —— 那时请先想清楚"
                        + "'这个 label 是从计划表抄来的'这件事还有没有别的地方要靠");
    }

    @Test
    @DisplayName("时长取计划项上的值, 不是意图上的 —— 窗口留了缓冲是'排定的结果', 不是意图的一部分")
    void durationComesFromThePlanItem() {
        FakeIntention intent = feasibleIntention(Duration.ofMinutes(30));
        PlanItem item = planItem(intent.toPlan(), PlanPriority.ROUTINE, Duration.ofMinutes(60));

        // 排定时把窗口留长(60 分钟), 而意图自己觉得只要 30 分钟 —— 两个数都必须在, 且各归各的
        assertEquals(Duration.ofMinutes(30), item.expectedDuration());
        assertEquals(Duration.ofMinutes(30), bridge(item).expectedDuration(),
                "桥读的是 PlanItem.expectedDuration(), 它回答'她觉得要做多久'; "
                        + "换成 window.duration() 就会把'给自己留了余量'读成'她预计要做这么久', "
                        + "而 PlanItem 的类注释里那张表专门分开了这两个问题");
    }

    // ─────────────────────────── 可行性 ───────────────────────────

    @Test
    @DisplayName("可行性与动作拆分转发给计划侧的意图 —— 动作非空是 commandsSent 能动的唯一前提")
    void feasibilityAndActionsAreForwarded() {
        PlanItemIntention it = bridge(planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60)));
        IntentionContext situation = IntentionContext.from(planningAt(T));

        assertTrue(it.evaluate(situation).feasible(),
                "计划项上的意图说做得了, 桥就必须说做得了 —— 说反了会让这条候选被静默丢掉");
        assertEquals(1, it.actions(situation).size(),
                "拆不出动作的候选在 DecisionEngine 里只会变成一条 PlanMutation.Insert —— "
                        + "也就是'她一直在安排, 从来没做过'");
        assertEquals(CAP, it.actions(situation).get(0).capabilityKey());
    }

    @Test
    @DisplayName("计划侧说做不了时, 理由与缺失项一起带过来 —— 那是'她想过但没做'唯一的解释")
    void infeasibilityKeepsReasonAndMissing() {
        FakeIntention intent = new FakeIntention(IntentionId.of("intent-2"), "给她回个电话",
                IntentionPriority.ROUTINE, Set.of("phone.call"), "life.activity.other",
                Duration.ofMinutes(10), true,
                Feasibility.no("手机不在身边", List.of("phone.call")), List.of());
        PlanItemIntention it = bridge(planItem(intent.toPlan(), PlanPriority.ROUTINE,
                Duration.ofMinutes(30)));

        Feasibility f = it.evaluate(IntentionContext.from(planningAt(T)));

        assertFalse(f.feasible());
        assertEquals("手机不在身边", f.reason());
        assertEquals(List.of("phone.call"), f.missing());
    }

    @Test
    @DisplayName("★ 计划侧的 evaluate 返回 null 时给一条不可行, 不是 NPE —— '没判过'与'判过说不行'要分得开")
    void nullFeasibilityBecomesANamedInfeasibility() {
        FakeIntention intent = new FakeIntention(IntentionId.of("intent-3"), "说不清的一件事",
                IntentionPriority.ROUTINE, Set.of(), "life.activity.other",
                Duration.ofMinutes(5), true, null, List.of());
        PlanItemIntention it = bridge(planItem(intent.toPlan(), PlanPriority.ROUTINE,
                Duration.ofMinutes(30)));

        Feasibility f = it.evaluate(IntentionContext.from(planningAt(T)));

        assertFalse(f.feasible(), "null 被判成可行会让一条没人判过的候选直接送到执行侧");
        assertTrue(f.reason().contains("没有给出可行性判定"), "理由要说清是哪一种不行: " + f.reason());
        assertTrue(f.reason().contains("intent-3"),
                "理由里要点名是哪个意图没给判定 —— 否则这条日志只说明'有一件事没判过', "
                        + "而排查的人手里有几十个意图: " + f.reason());
    }

    /**
     * 一个<b>绕过适配器</b>的计划侧意图: {@code evaluate} 直接返回 null。
     *
     * <p>它与上面那条的区别在于 null 从哪一层出来 —— 上面是 mind 侧意图没实现
     * {@code evaluate}(被 {@code IntentionPlanIntent} 翻译掉), 这里是被桥直接收到。
     * 两条路都得通, 因为 {@code PlanIntent} 是一个公开接口, 它的实现者不必是适配器。
     */
    private record NullVerdictPlanIntent(PlanIntent.IntentId id) implements PlanIntent {
        @Override
        public String description() {
            return "一个没判过的事";
        }

        @Override
        public PlanIntent.Feasibility evaluate(PlanningContext context) {
            return null;
        }

        @Override
        public List<PlanIntent.ActionIntent> decompose(PlanningContext context) {
            return List.of();
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }
    }

    @Test
    @DisplayName("★ 计划侧意图直接返回 null 的判定时, 桥也翻译成不可行 —— 两条边对 null 的看法必须一致")
    void nullFromABarePlanIntentAlsoBecomesANamedInfeasibility() {
        PlanItemIntention it = bridge(planItem(new NullVerdictPlanIntent(PlanIntent.IntentId.of("x")),
                PlanPriority.ROUTINE, Duration.ofMinutes(30)));

        Feasibility f = it.evaluate(IntentionContext.from(planningAt(T)));

        assertFalse(f.feasible(),
                "从 plan 侧过来的 null 与从 mind 侧过来的 null 是同一件事 —— "
                        + "一边崩、一边判成不可行的话, '某个意图没实现 evaluate'这个症状"
                        + "就取决于它走了哪条路");
        assertTrue(f.reason().contains("没有给出可行性判定"), f.reason());
    }

    @Test
    @DisplayName("★ 动作拆分返回 null 时抛, 不是转成空列表 —— 空列表是合法结论, null 只能是编程错误")
    void nullStepsAreRefused() {
        FakeIntention intent = new FakeIntention(IntentionId.of("intent-4"), "拆不出来的一件事",
                IntentionPriority.ROUTINE, Set.of(), "life.activity.other",
                Duration.ofMinutes(5), true, Feasibility.yes(), null);
        PlanItemIntention it = bridge(planItem(intent.toPlan(), PlanPriority.ROUTINE,
                Duration.ofMinutes(30)));

        NullPointerException e = assertThrows(NullPointerException.class,
                () -> it.actions(IntentionContext.from(planningAt(T))),
                "空列表的语义是'这是一个原子意图', 是一个她会真的做出的决定; "
                        + "拿它表达 null 会让一个 bug 看起来像她决定不拆步骤");
        assertTrue(e.getMessage().contains("intent-4"), e.getMessage());
    }

    // ─────────────────────────── 回程 ───────────────────────────

    @Test
    @DisplayName("toPlan() 直接返回原本那一项 —— 绕一圈回适配器会让身份漂、还会把适配器插进计划表")
    void toPlanReturnsTheOriginalIntentItself() {
        FakeIntention intent = feasibleIntention(Duration.ofMinutes(30));
        PlanIntent planIntent = intent.toPlan();
        PlanItemIntention it = bridge(planItem(planIntent, PlanPriority.ROUTINE,
                Duration.ofMinutes(60)));

        assertSame(planIntent, it.toPlan(),
                "计划项变不回'更计划侧的东西' —— 它本来就是计划侧的东西。走默认实现"
                        + "(new IntentionPlanIntent(this))会让 id 从 PlanItemId 变成 IntentId, "
                        + "而 DecisionEngine 拆不出动作时会用这个返回值造 PlanMutation.Insert, "
                        + "于是插进计划表的是一个包着桥的适配器");
    }

    @Test
    @DisplayName("★ 快照守卫: 处境对不上时必须说出来, 不能拿老处境硬答")
    void staleSituationIsRefusedLoudly() {
        PlanItemIntention it = bridge(planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60)));
        IntentionContext laterOne = IntentionContext.from(planningAt(T.plusSeconds(1)));

        Feasibility f = it.evaluate(laterOne);
        assertFalse(f.feasible(),
                "拿 " + T + " 的处境去回答 " + T.plusSeconds(1) + " 的问题, 会给出一个"
                        + "不会被任何人发现的错误答案 —— 这条断言要求它改成一个响亮的拒绝");
        assertTrue(f.reason().contains(T.toString()) && f.reason().contains(T.plusSeconds(1).toString()),
                "理由里两个时刻都要在, 否则读日志的人不知道差在哪: " + f.reason());

        assertThrows(IllegalArgumentException.class, () -> it.actions(laterOne),
                "动作拆分对不上时必须抛: 返回空列表的语义是'这是一个原子意图'(见 Intention#actions), "
                        + "拿它表达一个编程错误会把 bug 伪装成一个正常的业务结论");
    }

    @Test
    @DisplayName("空的处境不是 NPE, 也是一条指名的不可行")
    void nullSituationIsRefusedWithAReason() {
        PlanItemIntention it = bridge(planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60)));

        assertFalse(it.evaluate(null).feasible());
        assertThrows(IllegalArgumentException.class, () -> it.actions(null));
    }

    @Test
    @DisplayName("Feasibility 两个方向的转换是往返一致的 —— 两条边各一条, 不在别处 new")
    void feasibilityRoundTrips() {
        Feasibility mine = new Feasibility(false, "课本没带", List.of("bag.book"));

        assertEquals(mine, Feasibility.fromPlan(mine.toPlan()),
                "toPlan 出去、fromPlan 回来必须逐字段相同(record 的 equals), "
                        + "否则'她的判断'与'计划侧看到的判断'会开始分叉");
        assertFalse(Feasibility.fromPlan(null).feasible(),
                "null 视为'从来没判过', 不是 NPE —— 换成抛异常会把'某个意图没实现 evaluate'"
                        + "表现为一次心跳里的崩溃");
    }

    @Test
    @DisplayName("describe() 带上计划项与它握着的那一刻处境 —— 日志里要认得出是哪一条")
    void describeNamesTheItemAndTheSituation() {
        PlanItemIntention it = bridge(planItem(feasibleIntention(Duration.ofMinutes(30)).toPlan(),
                PlanPriority.ROUTINE, Duration.ofMinutes(60)));

        assertTrue(it.describe().contains(T.toString()),
                "它是一张快照, 日志里必须能看出快照取的是哪一刻: " + it.describe());
        assertTrue(it.describe().contains("把今天的实验做完"), it.describe());
    }
}
