package com.luxera.companion.human.life.plan;

import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.event.PlanEventPublisher;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2.2 §8.2.5 —— <b>重排的十条断言</b>, 全部围绕用户亲自纠正过的那段语义。
 *
 * <h2>被钉住的原始要求</h2>
 * <blockquote>
 * 打断当前正在做的计划 event, <b>不是</b>简单把当前在做的计划 event 更新剩余时间
 * 然后立马执行一个计划 event, 再把被中断的计划 event 继续执行,
 * 他<b>是真的改变了计划表</b>, 让 agent <b>重新思考重排计划表</b>,
 * 然后把穿衣服 event 排在第一位, 把写作业的启动时间设定为穿衣服执行结束的时间,
 * 但其也可以完全不再继续写作业, 把写作业直接从计划表删掉,
 * 然后插入去运动的计划 event, 想立马执行就把其触发事件调成现在,
 * 想过段时间就调成想要触发的时候。
 * </blockquote>
 *
 * <p>这里的每个测试都对应那句话里的一个分句。特别是
 * {@link #放弃写作业改成去运动()}: 它存在的唯一目的就是让"暂停/恢复"式的实现
 * <b>无法通过</b> —— 那是一个真实的风险, 因为暂停/恢复看起来总是能跑通那些
 * "她继续做完了"的测试。
 *
 * <h2>时刻约定</h2>
 * 全部用固定的仿真时刻（12:00 / 12:15 / …），<b>不读系统时钟</b>。
 * 一个读 {@code Instant.now()} 的测试在这里毫无意义: 这个包的全部价值在于
 * "同样的一天可以回放", 而回放的前提是时刻由外部给定。
 */
class PlanReplanningTest {

    // 场景时刻: 用户描述的那一天
    private static final Instant T_1200 = Instant.parse("2026-03-02T12:00:00Z");
    private static final Instant T_1210 = Instant.parse("2026-03-02T12:10:00Z");
    private static final Instant T_1215 = Instant.parse("2026-03-02T12:15:00Z");
    private static final Instant T_1225 = Instant.parse("2026-03-02T12:25:00Z");
    private static final Instant T_1230 = Instant.parse("2026-03-02T12:30:00Z");
    private static final Instant T_1235 = Instant.parse("2026-03-02T12:35:00Z");
    private static final Instant T_1240 = Instant.parse("2026-03-02T12:40:00Z");
    private static final Instant T_1300 = Instant.parse("2026-03-02T13:00:00Z");
    private static final Instant T_1310 = Instant.parse("2026-03-02T13:10:00Z");
    private static final Instant T_1400 = Instant.parse("2026-03-02T14:00:00Z");
    private static final Instant T_1500 = Instant.parse("2026-03-02T15:00:00Z");

    // ─────────────────────── 一、版本链: 重排不是原地改 ───────────────────────

    @Test
    @DisplayName("安排一件事会产生一个新版本, 而不是原地修改")
    void 安排一件事产生新版本() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        PlanBoard board = boardWith(homework);

        PlanRevision initial = board.current();
        PlanRevision next = board.apply(T_1200, "早上把今天排一下",
                List.of(new PlanMutation.Insert(
                        PlanItem.schedule(intent("dress", "穿衣服"),
                                TimeWindow.of(T_1200, T_1210)),
                        "先把衣服穿好")));

        assertEquals(initial.revisionNumber() + 1, next.revisionNumber(),
                "重排必须产生新版本号 —— 原地改会让'她改过几次主意'变成无法回答的问题");
        assertEquals(Optional.of(initial.revisionId()), next.previousRevisionId(),
                "新版本必须指回它的上一版, 否则 Revision 链是断的");
        assertEquals(2, board.revisionCount(), "两个版本都应留在历史里");
        assertTrue(board.revisionById(initial.revisionId()).isPresent(),
                "旧版本必须仍然可取 —— 它是'她当时怎么想的'的唯一记录");
    }

    @Test
    @DisplayName("12:15 被打断: 写作业被移到穿衣结束后, 而不是把结束时间往后推")
    void 打断后写作业被移到穿衣结束后() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        PlanBoard board = boardWith(homework);
        PlanItemId homeworkId = homework.id();

        // 用户描述的那个形状: 插入穿衣, 并把写作业的启动时间设为穿衣结束的时间。
        // 注意这里没有任何"剩余时长"——她是重新排的, 不是接着做的
        PlanMutation insertWear = new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_1215, T_1225)),
                "有点冷, 先加件衣服");
        PlanMutation pushHomework = new PlanMutation.Move(homeworkId,
                TimeWindow.of(T_1225, T_1310), "穿衣服占掉了十分钟");

        PlanRevision after = board.apply(T_1215, "觉得冷, 先加件衣服再继续写作业",
                List.of(insertWear, pushHomework));

        // ① 穿衣现在就在做（她的身体只有一个, 12:15 只能做一件事）
        PlanItem wear = liveItemNamed(after, "穿衣服");
        assertEquals(PlanLifecycle.ACTIVE, wear.lifecycle(),
                "插入的窗口包含当前时刻, 她应当场就在做它");
        assertEquals(T_1215, wear.window().start());

        // ② 写作业确实<b>被移到了</b>穿衣结束的时刻 —— 这是用户的原话
        PlanItem movedHomework = liveItemNamed(after, "写作业");
        assertEquals(T_1225, movedHomework.window().start(),
                "写作业的启动时间必须正好是穿衣执行的结束时间");
        assertEquals(T_1310, movedHomework.window().end());
        assertNotEquals(homeworkId, movedHomework.id(),
                "移动产生的是<b>新的一项</b>, 而不是改掉旧项的 end_at —— "
                        + "否则计划表上看不出'发生过打断'");

        // ③ 旧的那一项没有被删掉, 它转成了「已被新版本替代」
        PlanItem oldHomework = after.find(homeworkId).orElseThrow(
                () -> new AssertionError("被移动掉的旧项必须留在版本里"));
        assertEquals(PlanLifecycle.SUPERSEDED, oldHomework.lifecycle(),
                "旧项应当标为 SUPERSEDED 而不是消失 —— "
                        + "这样单独看一个版本就能回答'12:00-13:00 本来要写作业'");
        assertEquals(TimeWindow.of(T_1200, T_1300), oldHomework.window(),
                "旧项的窗口必须原样保留, 它是历史事实, 不该被改写");

        // ④ 计划表里没有任何重叠 —— 校验器会因此放行
        assertTrue(after.overlaps().isEmpty(), "重排后的窗口不该相交: " + after.overlaps());
    }

    @Test
    @DisplayName("也可以彻底放弃写作业 —— 删掉它, 换成去运动")
    void 放弃写作业改成去运动() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        PlanBoard board = boardWith(homework);

        PlanRevision after = board.apply(T_1215, "不想写了, 出去动一动",
                List.of(
                        new PlanMutation.Remove(homework.id(), "今天就是不想写了"),
                        new PlanMutation.Insert(
                                PlanItem.schedule(intent("run", "去运动"),
                                        TimeWindow.of(T_1215, T_1400)),
                                "心里烦, 出去跑一圈")));

        // 这一条是"暂停/恢复"式实现过不去的关口: 那个实现会让写作业继续存在
        assertEquals(TimeWindow.of(T_1215, T_1400),
                liveItemNamed(after, "去运动").window());
        assertTrue(after.liveItems().stream().noneMatch(i -> "写作业".equals(i.intent().description())),
                "写作业必须真的从生效项里消失 —— 她可能根本不想继续了, "
                        + "这正是用户否定'更新剩余时间后继续执行'的原因");

        PlanItem cancelled = after.find(homework.id()).orElseThrow(
                () -> new AssertionError("被删掉的项仍应保留在版本里"));
        assertEquals(PlanLifecycle.CANCELLED, cancelled.lifecycle(),
                "删除的语义是 CANCELLED（她放弃了）, 不是从历史里抹掉");
    }

    @Test
    @DisplayName("想立马执行就把触发时刻设成现在; 想过会儿就设成以后")
    void 触发时刻决定是立刻执行还是稍后执行() {
        PlanBoard nowBoard = boardWith();
        PlanRevision nowRevision = nowBoard.apply(T_1215, "现在就去",
                List.of(new PlanMutation.Insert(
                        PlanItem.schedule(intent("run", "去运动"), TimeWindow.of(T_1215, T_1400)),
                        "想立马执行")));
        assertEquals(PlanLifecycle.ACTIVE, liveItemNamed(nowRevision, "去运动").lifecycle(),
                "窗口覆盖当前时刻 → 她当场就进入这件事");

        PlanBoard laterBoard = boardWith();
        PlanRevision laterRevision = laterBoard.apply(T_1215, "下午再去",
                List.of(new PlanMutation.Insert(
                        PlanItem.schedule(intent("run", "去运动"), TimeWindow.of(T_1400, T_1500)),
                        "想过段时间再执行")));
        assertEquals(PlanLifecycle.PENDING, liveItemNamed(laterRevision, "去运动").lifecycle(),
                "窗口在未来 → 她还没开始做");
    }

    @Test
    @DisplayName("重排的每一次都留下痕迹: 她能看出自己改过几次主意")
    void 每一次重排都留下一个版本() {
        PlanBoard board = boardWith(homework(TimeWindow.of(T_1200, T_1300)));
        board.apply(T_1215, "加件衣服", List.of(new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_1225, T_1235)),
                "冷")));
        board.apply(T_1300, "写完作业了", List.of());

        assertEquals(3, board.revisionCount(), "初始版 + 两次重排");
        assertEquals(3, board.history().size());
        assertEquals("写完作业了", board.current().reason(),
                "版本的理由要留成一句人话 —— 它是行为分析唯一能读懂的线索");
        // history 应当按版本号递增, 而不是按存入顺序碰巧对
        assertEquals(List.of(1L, 2L, 3L),
                board.history().stream().map(PlanRevision::revisionNumber).toList());
    }

    // ─────────────────────── 二、校验: 算术不交给 LLM ───────────────────────

    @Test
    @DisplayName("时间重叠是硬违规, 整个版本被拒绝")
    void 时间重叠被判为硬违规() {
        PlanRevision candidate = PlanRevision.initial(T_1200, List.of(
                homework(TimeWindow.of(T_1200, T_1300)),
                PlanItem.schedule(intent("call", "打电话"), TimeWindow.of(T_1230, T_1240))),
                "一个 LLM 提议的版本");

        PlanValidator.ValidationResult result =
                new PlanValidator(null).validate(candidate, context(T_1200));

        assertFalse(result.valid(), "她的身体只有一个, 12:30 不能同时做两件事");
        assertFalse(result.violations().isEmpty());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("写作业")),
                "违规说明里要指名道姓地写出是哪两项撞了 —— 写 'overlap detected' 等于没写: "
                        + result.violations());
    }

    @Test
    @DisplayName("依赖倒置是硬违规 —— 前置要到她开始之后才结束")
    void 依赖倒置被判为硬违规() {
        PlanItem prep = PlanItem.schedule(intent("prep", "准备材料"),
                TimeWindow.of(T_1230, T_1300));
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1230))
                .withDependencies(List.of(prep.id()));

        PlanRevision candidate = PlanRevision.initial(T_1200, List.of(homework, prep),
                "写作业排在准备材料之前, 却依赖它");

        PlanValidator.ValidationResult result =
                new PlanValidator(null).validate(candidate, context(T_1200));

        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("顺序倒置")),
                "依赖倒置必须被点名: " + result.violations());
        assertTrue(candidate.overlaps().isEmpty(),
                "前提: 这两项时间上<不>重叠（半开区间）, 所以这条违规只可能来自依赖检查");
    }

    @Test
    @DisplayName("校验失败必须可观测 —— 发一条事件, 而不是静默丢弃")
    void 校验失败会发出一条可观测的事件() {
        EventFabric fabric = fabric();
        PlanValidator validator = new PlanValidator(fabric);
        PlanRevision bad = PlanRevision.initial(T_1200, List.of(
                homework(TimeWindow.of(T_1200, T_1300)),
                PlanItem.schedule(intent("call", "打电话"), TimeWindow.of(T_1230, T_1240))),
                "排重了的版本");

        Optional<PlanRevision> accepted = validator.accept(
                bad, context(T_1200), "llm", Map.of("revision", bad.revisionId()));

        assertTrue(accepted.isEmpty(), "没通过校验的版本不能成为当前版本");
        List<WorldEvent> published = fabric.recentEvents(10);
        assertEquals(1, published.size(),
                "必须恰好留下一条记录 —— 一条都不留的话, '她今天什么都没安排'会被误读成消极");
        assertEquals(PlanEvents.VALIDATION_FAILED, published.get(0).typeId(),
                "记录的类型必须是 system.plan-validation-failed.v1");
    }

    @Test
    @DisplayName("预演与实际应用走同一条代码路径")
    void 预演与实际应用一致() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        PlanRevision from = PlanRevision.initial(T_1200, List.of(homework), "起点");
        List<PlanMutation> mutations = List.of(new PlanMutation.Move(homework.id(),
                TimeWindow.of(T_1225, T_1310), "让位给穿衣服"));

        PlanRevision previewed = PlanValidator.materialize(from, T_1215, "让位给穿衣服", mutations);
        PlanBoard real = new PlanBoard(from);
        PlanRevision applied = real.apply(T_1215, "让位给穿衣服", mutations);

        assertEquals(previewed.revisionNumber(), applied.revisionNumber());
        assertEquals(previewed.liveItems().size(), applied.liveItems().size());
        assertEquals(
                previewed.liveItems().stream().map(i -> i.window().toString()).toList(),
                applied.liveItems().stream().map(i -> i.window().toString()).toList(),
                "试算说排得下、真应用却发现排不下, 是最难查的一类 bug —— "
                        + "两者共用同一个 apply, 这种分叉在结构上就不可能发生");
    }

    // ─────────────────────── 三、确定性重排器 ───────────────────────

    @Test
    @DisplayName("顺次问规则, 第一条认领的说了算")
    void 第一条认领处境的规则说了算() {
        PlanAdjustmentRule quiet = fixedRule("a-quiet", 10, List.of());
        PlanAdjustmentRule coldRule = new ColdRule("b-cold", 20);
        DefaultPlanReplanner replanner = new DefaultPlanReplanner(List.of(coldRule, quiet));

        ReplanningContext ctx = contextWithActive(T_1215);
        ReplanProposal proposal = replanner.replan(ctx);

        assertEquals("b-cold", proposal.proposedBy(),
                "priority 小的先被问; a-quiet 返回空列表表示'不认领', 于是轮到 b-cold");
        assertEquals(2, proposal.mutations().size(),
                "这条规则既插入穿衣、又推后写作业 —— 两者是一对, 不能拆开");
    }

    @Test
    @DisplayName("规则返回空列表 = 不认领这个处境, 而不是'建议不改'")
    void 空列表表示不认领() {
        DefaultPlanReplanner replanner = new DefaultPlanReplanner(
                List.of(fixedRule("no-opinion", 10, List.of())));

        ReplanningContext ctx = contextWithActive(T_1215);
        ReplanProposal proposal = replanner.replan(ctx);

        assertEquals(DefaultPlanReplanner.FALLBACK_PROPOSER, proposal.proposedBy(),
                "没有规则认领时应当走降级, 而不是把'没人管'伪装成'规则建议不改'");
        assertEquals(1, proposal.mutations().size());
        assertTrue(proposal.mutations().get(0) instanceof PlanMutation.KeepActive,
                "她手上正有事, 降级应当是'先把手头这点做完'");
        assertEquals(ctx.active().orElseThrow().itemId(),
                ((PlanMutation.KeepActive) proposal.mutations().get(0)).itemId(),
                "保留的必须是<正在做的那一项>, 而不是随便挑一项");
    }

    @Test
    @DisplayName("超出上限的提案被整份丢弃, 而不是截断")
    void 超限提案被整份丢弃() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        // 一条自称"最多改 1 项"却提了 2 项的规则
        PlanAdjustmentRule greedy = fixedRule("greedy", 1, 1, List.of(
                new PlanMutation.Move(homework.id(),
                        TimeWindow.of(T_1225, T_1310), "推后"),
                new PlanMutation.Insert(
                        PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_1215, T_1225)),
                        "加件衣服")));
        DefaultPlanReplanner replanner = new DefaultPlanReplanner(List.of(greedy));

        ReplanProposal proposal = replanner.replan(contextWithActive(T_1215));

        assertEquals(DefaultPlanReplanner.FALLBACK_PROPOSER, proposal.proposedBy());
        assertNotEquals("greedy", proposal.proposedBy(),
                "截断会留下互相依赖的碎片（只有穿衣没有推后, 于是两项时间重叠）—— "
                        + "那排出来的是一个<错的计划>, 而不是一个不完整的计划");
    }

    @Test
    @DisplayName("规则抛异常时跳过它继续问下一条, 而不是让她失去重排能力")
    void 规则抛异常不影响其他规则() {
        PlanAdjustmentRule broken = new PlanAdjustmentRule() {
            @Override public String name() { return "broken"; }
            @Override public int priority() { return 1; }
            @Override public List<PlanMutation> propose(ReplanningContext context) {
                throw new IllegalStateException("这条规则写坏了");
            }
        };
        DefaultPlanReplanner replanner = new DefaultPlanReplanner(
                List.of(broken, new ColdRule("b-cold", 20)));

        ReplanProposal proposal = replanner.replan(contextWithActive(T_1215));

        assertEquals("b-cold", proposal.proposedBy(),
                "一个坏规则不该让整个 agent 失去重排能力 —— 与 EventFabric 对第三方 handler 的态度一致");
    }

    @Test
    @DisplayName("没有规则、也没在做事时, 提案是空改动而不是 null")
    void 无规则无进行项时给出空提案() {
        ReplanningContext ctx = new ReplanningContext(T_1215,
                PlanRevision.initial(T_1200, List.of(homework(TimeWindow.of(T_1200, T_1300))),
                        "只有未来的一件"),
                Optional.empty(),
                PlanningContext.HumanSnapshot.unknown(),
                List.of(), List.of(),
                ReplanningContext.PlanningGoals.NONE,
                ReplanningContext.Trigger.stimulus("外面有点吵"));

        ReplanProposal proposal = new DefaultPlanReplanner().replan(ctx);

        assertNotNull(proposal, "重排器永远不能返回 null —— 调用方不该为它写空判断");
        assertTrue(proposal.mutations().isEmpty());
        assertTrue(proposal.isNoop());
        assertFalse(proposal.reason().isBlank(),
                "即使是'什么都不改', 也要说清为什么 —— 否则历史里就是一次无从解释的意外");
    }

    // ─────────────────────── 四、事件发布 ───────────────────────

    @Test
    @DisplayName("每个新排进来的项发一条安排事件, 随后发一条版本事件")
    void 发布器逐项安排再发版本事件() {
        EventFabric fabric = fabric();
        PlanBoard board = boardWith();
        new PlanEventPublisher(fabric).attachTo(board);

        board.apply(T_1215, "加件衣服", List.of(new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_1215, T_1225)),
                "有点冷")));

        List<WorldEvent> newestFirst = fabric.recentEvents(10);
        assertEquals(2, newestFirst.size(), "一条安排 + 一条版本");

        List<WorldEvent> inOrder = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(inOrder);   // recentEvents 是新的在前

        assertEquals(PlanEvents.ITEM_SCHEDULED, inOrder.get(0).typeId(),
                "先逐项播报'她安排了一件事'");
        assertEquals(PlanEvents.REVISION_CREATED, inOrder.get(1).typeId(),
                "再播报'这是一次重排, 理由是……' —— 反过来的话, "
                        + "每条安排事件都要自己去找它属于哪一次重排");
    }

    @Test
    @DisplayName("状态迁移不单独发事件 —— 它已经包含在版本事件里")
    void 状态迁移不单独发事件() {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300));
        EventFabric fabric = fabric();
        PlanBoard board = boardWith(homework);
        PlanEventPublisher publisher = new PlanEventPublisher(fabric);
        publisher.attachTo(board);

        // 移动会让旧项转成 SUPERSEDED —— 那是一次状态迁移
        board.apply(T_1215, "推后一点", List.of(new PlanMutation.Move(
                homework.id(), TimeWindow.of(T_1225, T_1310), "让位给穿衣服")));

        assertEquals(1, publisher.suppressedTransitions(),
                "迁移应当被合并进版本事件");
        List<WorldEvent> published = fabric.recentEvents(10);
        assertTrue(published.stream().allMatch(e ->
                        e.typeId().equals(PlanEvents.ITEM_SCHEDULED)
                                || e.typeId().equals(PlanEvents.REVISION_CREATED)),
                "不该出现描述同一件事的第二类事件 —— 两条描述同一件事的记录迟早会不一致: "
                        + published.stream().map(WorldEvent::typeId).toList());
        assertEquals(0, publisher.failures());
    }

    // ─────────────────────── 测试脚手架 ───────────────────────

    /** 一件"写作业"。 */
    private static PlanItem homework(TimeWindow window) {
        return PlanItem.schedule(intent("homework", "写作业"), window);
    }

    private static PlanIntent intent(String id, String description) {
        return new FixedIntent(new PlanIntent.IntentId(id), description);
    }

    private static PlanBoard boardWith(PlanItem... items) {
        return new PlanBoard(PlanRevision.initial(T_1200, List.of(items), "早上把今天排一下"));
    }

    private static PlanItem liveItemNamed(PlanRevision revision, String description) {
        return revision.liveItems().stream()
                .filter(i -> description.equals(i.intent().description()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "生效项里找不到「" + description + "」, 现在有: "
                                + revision.liveItems().stream()
                                .map(i -> i.intent().description()).toList()));
    }

    /** 一件"写作业 12:00-13:00 正在做"的处境。 */
    private static ReplanningContext contextWithActive(Instant now) {
        PlanItem homework = homework(TimeWindow.of(T_1200, T_1300))
                .withLifecycle(PlanLifecycle.ACTIVE);
        PlanRevision revision = PlanRevision.initial(T_1200, List.of(homework), "早上排的");
        return new ReplanningContext(now, revision,
                Optional.of(ReplanningContext.ActiveExecution.of(homework, T_1200)),
                PlanningContext.HumanSnapshot.unknown(),
                List.of(), List.of(),
                ReplanningContext.PlanningGoals.NONE,
                ReplanningContext.Trigger.stimulus("房间里的温度降下来了"));
    }

    private static PlanningContext context(Instant now) {
        return PlanningContext.minimal(now,
                PlanningContext.HumanSnapshot.unknown());
    }

    private static EventFabric fabric() {
        return new DefaultEventFabric("test-human", new EventHandlerRegistry());
    }

    /** 一条"提固定内容"的规则, 用来测调度而不是测领域知识。 */
    private static PlanAdjustmentRule fixedRule(String name, int priority,
                                               List<PlanMutation> proposal) {
        // 不声明 maxMutations —— 用接口的默认值(4)。绝大多数用例不关心这个上限,
        // 只有"提案超限"那一组才需要把它压到 1, 那一组用下面的四参重载。
        return fixedRule(name, priority, 4, proposal);
    }

    /**
     * 同上, 但<b>明确声明</b>这条规则"最多改几项"。
     *
     * <p>这个参数是新增的, 因为原来那个三参版本把它和 {@code priority} 混在一起了 ——
     * 调用的地方写着 {@code fixedRule("greedy", 10, ...)}, 读的人（以及写它的人）
     * 很容易把那个 {@code 10} 读成"上限 10 项"。它其实是优先级。
     * 于是那条用例从来没有真正测到"提案超限": 规则自称的上限一直是默认的 4,
     * 而它提了 2 项, 于是压根没超。
     *
     * <p><b>这正是"参数是裸 int"的代价</b> —— 两个都是 int 的重载放在一起,
     * 传错一个不会有任何编译错误, 只会让一条测试静静地测着别的东西。
     */
    private static PlanAdjustmentRule fixedRule(String name, int priority, int maxMutations,
                                               List<PlanMutation> proposal) {
        return new PlanAdjustmentRule() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public int maxMutations() { return maxMutations; }
            @Override public List<PlanMutation> propose(ReplanningContext context) {
                return proposal;
            }
        };
    }

    /**
     * "觉得冷就加件衣服, 并把手上那件事顺延" —— 用户描述的那条规则。
     *
     * <p>注意它<b>没有</b>去读 {@code context.events()} 里的事件类型来判断"是不是冷刺激"。
     * 如果它那样写, "房间变冷了"和"她走进了冷库"就得各写一个分支, 而第三个来源
     * （"她洗了个冷水澡"）出现时一定会漏掉。它读的是<b>处境</b>。
     */
    private static final class ColdRule implements PlanAdjustmentRule {

        private final String name;
        private final int priority;

        ColdRule(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override public String name() { return name; }
        @Override public int priority() { return priority; }

        @Override
        public List<PlanMutation> propose(ReplanningContext context) {
            if (context.active().isEmpty()) {
                return List.of();   // 不认领: 她手上没事, "冷"现在不需要改动计划
            }
            ReplanningContext.ActiveExecution active = context.active().get();
            Instant wearStart = context.now();
            Instant wearEnd = wearStart.plus(Duration.ofMinutes(10));
            Duration rest = Duration.between(active.startedAt(), active.window().end())
                    .minus(Duration.ofMinutes(10));
            if (rest.isNegative() || rest.isZero()) {
                rest = Duration.ofMinutes(5);
            }
            return List.of(
                    new PlanMutation.Insert(
                            PlanItem.schedule(intent("dress", "穿衣服"),
                                    TimeWindow.of(wearStart, wearEnd)),
                            "有点冷, 先加件衣服"),
                    new PlanMutation.Move(active.itemId(),
                            TimeWindow.of(wearEnd, wearEnd.plus(rest)),
                            "穿衣服占掉十分钟, 顺延"));
        }
    }

    // ────────── 三、封闭集合的绊线: 加第七种改动时必须有人做决定 ──────────

    /**
     * {@code PlanMutation} 是 sealed, 但 <b>sealed 本身不产生穷尽性检查</b> ——
     * 检查来自模式匹配 {@code switch}, 而它在 Java 17 还是 preview, 本仓库用不了。
     * 于是 {@code PlanBoard.applyOne} 写的是一条 {@code instanceof} 链, 兜底落在运行期一条 WARN。
     *
     * <p>这条测试不是"验证功能", 它是一条<b>绊线</b>: 加第七种改动时它会让构建失败,
     * 从而逼着那个人去回答"applyOne 里怎么处理它"。
     *
     * <p>没有它的话, 加了新成员却忘了改 applyOne 的结果是
     * <b>构建绿的、测试绿的</b>, 只有线上日志里多一行。
     */
    @Test
    @DisplayName("改动类型是封闭的六种 —— 加第七种时这里必须先失败")
    void 改动类型是封闭的六种() {
        Class<?>[] permitted = PlanMutation.class.getPermittedSubclasses();

        assertNotNull(permitted,
                "PlanMutation 不再是 sealed 了 —— 那意味着它不再是一个封闭集合, "
                        + "而'重排只能由这几种操作构成'这条推理随之失效");

        assertEquals(6, permitted.length,
                "PlanMutation 现在有 " + permitted.length + " 种改动, 而这个数字被写死成 6。"
                        + "如果你是刚加了第七种: 请<先>去 PlanBoard.applyOne 补上对应分支, "
                        + "<再>回来改这个数字 —— 这个顺序是刻意的, "
                        + "因为 applyOne 的兜底只是一条 WARN, 它不会替你拦住任何事");
    }

    /**
     * "每一种改动都必须说清为什么" —— 这条规则原本只写在 {@code PlanMutation.reason()} 的注释里,
     * 而注释不会拦住任何人。
     *
     * <p>这里把它变成可执行的: 每一个成员都必须有一个叫 {@code reason} 的组件。
     * 顺带钉住"必须是 record" —— 改动一旦可变, "这条改动是什么"就不再是一个
     * 可以拿去比较、复现、回放的值, 而回放是这个包的全部价值所在。
     */
    @Test
    @DisplayName("每一种改动都必须带 reason —— 包括将来新加的那种")
    void 每一种改动都必须带理由() {
        for (Class<?> variant : PlanMutation.class.getPermittedSubclasses()) {

            assertTrue(variant.isRecord(),
                    variant.getSimpleName() + " 不是 record —— 改动一旦可变, "
                            + "'这条改动是什么'就不再是一个能拿去比较、复现、回放的值");

            List<String> components = new ArrayList<>();
            for (java.lang.reflect.RecordComponent component : variant.getRecordComponents()) {
                components.add(component.getName());
            }

            assertTrue(components.contains("reason"),
                    variant.getSimpleName() + " 的组件是 " + components
                            + ", 里面没有 reason —— 行为分析的时间轴上那一格会是空的。"
                            + "理由不是可选的: 用户追问'你不是说要去跑步吗'时, "
                            + "系统能给出的答案只能来自这里");
        }
    }

    /** 一个不需要上下文的意图 —— 本测试测的是重排机制, 不是领域知识。 */
    private record FixedIntent(PlanIntent.IntentId id, String description) implements PlanIntent {

        @Override
        public Feasibility evaluate(PlanningContext context) {
            return Feasibility.yes("测试意图, 总是可行");
        }

        @Override
        public List<ActionIntent> decompose(PlanningContext context) {
            return List.of();
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }
    }
}
