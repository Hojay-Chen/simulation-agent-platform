package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.action.DefaultActionFabric;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanPriority;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.registry.CapabilityRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §8.5.13 —— <b>心跳里那条候选生产者 ({@code HumanActor.candidatesAt}) 到底接通了什么</b>。
 *
 * <h2>这个文件同时是一份"接通到哪里为止"的诚实记录</h2>
 * §8.5.13 承诺规则桥能让"打断 = 真的去改计划表"这条链跑起来。这句话是对的, 但只对<b>一半</b> ——
 * 而两半的差别不会自己显形, 所以在这里钉住:
 *
 * <pre>
 *   候选来自 plan().activeAt(now)
 *        ↓
 *   决定引擎拿它与 currentItem(now) 比 —— 而 currentItem 正是同一个集合里
 *   优先级最高的那一个 (Mind.currentItem 与 DecisionEngine 读的是同一张表)
 *        ↓
 *   任何一条候选都不可能"显著压过"它自己 (outranksCurrent 要 chosen ≥ current + 15)
 *        ↓
 *   ⇒ 决定恒为 keeping ⇒ 计划表<b>真的</b>多一版 + 一条 KeepActive
 *     而 commandsSent 恒为 0 —— 她不会送出任何动作
 * </pre>
 *
 * <p>所以本文件断言 {@code commandsSent() == 0}。这不是在说"这样是对的", 而是在说
 * <b>今天是这样的</b>: 动作的那一半需要一个<b>不是当前项</b>的候选来源(LLM 提议器,
 * 或一条由感知驱动的响应), 而那件东西还没有。等到它有了, 这个断言会红 ——
 * 那是它存在的意义, 请那时回读本节再决定怎么改。
 *
 * <h2>另有一个会静默损坏仿真的陷阱, 第 ④ 条测试专门盯着它</h2>
 * 候选<b>绝不能</b>从 {@code PlanBoard.dueAt} 读: 那个方法在返回之前会把每一项
 * 记进内部的 {@code triggered} 表, 于是心跳问一次就等于<b>把"到点了"从调度器手里偷走</b>
 * —— 那一项此后永远不会被触发, 而她按计划该做的事一件都不会发生, 没有异常也没有变红的计数。
 */
class HumanActorCandidatesTest {

    private static final Instant T = Instant.parse("2026-09-19T09:00:00Z");
    private static final String HUMAN = "human-candidates";
    private static final String CAP = "chat.reply";

    private static ActionFabric emptyFabric() {
        // 与 HumanAssemblyTest 一致: 一个刻意空的能力注册表。
        // 于是就算某天这条链真的走到了 execute, 结果也只会是 REJECTED("没有名为 X 的能力"),
        // 而不是一次真实外呼 —— 测试不许有任何外部副作用。
        return new DefaultActionFabric(new CapabilityRegistry());
    }

    /**
     * 一个能排进计划表的意图: 可行、有动作、有时长 —— 形状与将来真实的生产者一致。
     *
     * <p>刻意写成匿名类而不是 lambda 或 record: {@code PlanIntent} 有六个方法
     * (其中两个是 {@code decompose} 与 {@code requiredCapabilities}),
     * 而一个"能跑通"的实现必须把这几件事都说出来。哪一件没说, 那一件就会拿默认值 ——
     * 而默认值在这里恰好是"没动作", 于是测试会以"她什么都没做"的方式通过,
     * 却看不出是为什么。
     */
    private static PlanIntent planIntent(String key, String what, Duration howLong) {
        return new PlanIntent() {
            private final PlanIntent.IntentId id = PlanIntent.IntentId.of(key);

            @Override
            public PlanIntent.IntentId id() {
                return id;
            }

            @Override
            public String description() {
                return what;
            }

            @Override
            public PlanIntent.Feasibility evaluate(
                    com.luxera.companion.human.life.plan.PlanningContext context) {
                return PlanIntent.Feasibility.yes("她此刻做得了这件事");
            }

            @Override
            public List<PlanIntent.ActionIntent> decompose(
                    com.luxera.companion.human.life.plan.PlanningContext context) {
                return List.of(PlanIntent.ActionIntent.of(CAP, "做一步 " + what));
            }

            @Override
            public Set<String> requiredCapabilities() {
                return Set.of(CAP);
            }

            @Override
            public Duration expectedDuration() {
                return howLong;
            }
        };
    }

    /** 覆盖住 {@code moment} 的那一项 —— 候选就是从这里来的。 */
    private static PlanItem covering(Instant moment) {
        return PlanItem.schedule(planIntent("intent-study", "把实验做完", Duration.ofMinutes(30)),
                TimeWindow.startingAt(moment.minusSeconds(600), Duration.ofMinutes(60)),
                PlanPriority.ROUTINE);
    }

    /**
     * 一个<b>早已过了窗口、却还是 PENDING</b> 的项 —— 它是第 ④ 条测试的探针。
     *
     * <p>它必须是 PENDING 且 {@code start <= T}(否则 {@code dueAt(T)} 本来就返回空,
     * 探针测不出任何东西), 而它的窗口<b>不能</b>覆盖 T(否则它也会成为候选,
     * 也就会被 KeepActive 翻成 ACTIVE, 于是它不再是"到点待触发"的)。
     */
    private static PlanItem overdueButPending() {
        return PlanItem.schedule(planIntent("intent-old", "昨天没写完的那一页",
                        Duration.ofMinutes(30)),
                TimeWindow.startingAt(T.minusSeconds(7200), Duration.ofMinutes(600)),
                PlanPriority.ROUTINE);
    }

    private static HumanAssembly.Parts assembleWith(PlanRevision seed) {
        return HumanAssembly.assemble(HUMAN, AgentLifecycle.ACTIVE, emptyFabric(),
                SimulationClock.at(T), seed, null);
    }

    // ─────────────────────── ① 计划表上有一项覆盖此刻 ───────────────────────

    @Test
    @DisplayName("★ 计划表上有覆盖此刻的一项时, 心跳真的会去改计划表 —— 打断不是一句日志, 是一个新版本")
    void anInterruptionReallyProducesANewRevision() {
        PlanItem item = covering(T);
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));

        assertEquals(1, parts.human().life().plan().revisionCount(), "起手只有种子那一版");

        parts.actor().pulse(T);

        assertEquals(1, parts.actor().pulses(), "这一拍要真的跑完, 不是被锁或档位挡掉的");
        assertEquals(0, parts.actor().skipped());
        assertEquals(0, parts.actor().failures());
        assertEquals(2, parts.human().life().plan().revisionCount(),
                "§8.5.13 全部承诺就是这一条: 有候选 → 决定 → 计划表<b>多一版</b>。"
                        + "仍然只有 1 版意味着这条链在某个环节断掉了, 而它不会抛异常, "
                        + "只会让她安静地坐着");
    }

    @Test
    @DisplayName("★ 那一版里记的是一条 KeepActive, 点名了被打断的那一项 —— '她想过但决定不改'必须留痕")
    void theNewRevisionCarriesAKeepActiveForThatItem() {
        PlanItem item = covering(T);
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));

        parts.actor().pulse(T);

        List<PlanMutation> mutations = parts.human().life().plan().current().mutations();
        assertEquals(1, mutations.size(),
                "决定引擎在'压不过当前项'时只产出一条改动 —— 多了说明它同时在改别的: " + mutations);

        PlanMutation.KeepActive keep = assertInstanceOf(PlanMutation.KeepActive.class,
                mutations.get(0),
                "应该是 KeepActive。若是 Insert, 说明这条候选拆出了动作并想插进计划表 —— "
                        + "那是另一半能力(见类注释), 今天不该发生: " + mutations);
        assertEquals(item.id(), keep.itemId(),
                "被打断的必须是那一项本身。换了 id 意味着决定引擎拿错了 current, "
                        + "而她接下来会去'保持'一件不存在的事");
    }

    @Test
    @DisplayName("★ 那一项被 KeepActive 翻成了 ACTIVE —— 用户要的'打断改变计划表'是状态真的变了, 不是记了一笔")
    void keepActiveActuallyMovesTheItemIntoActive() {
        PlanItem item = covering(T);
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));

        parts.actor().pulse(T);

        PlanItem after = parts.human().life().plan().activeAt(T).stream()
                .filter(i -> i.id().equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "被打断之后它应当仍在'此刻覆盖着她的'那一组里"));
        assertEquals(PlanLifecycle.ACTIVE, after.lifecycle(),
                "PlanBoard 的 KeepActive 分支只有一行实质动作: 窗口覆盖此刻且仍 PENDING 时翻成 ACTIVE。"
                        + "它没发生的话, '打断'就退化成了纯记账 —— 而用户专门纠正过两次, "
                        + "要的是<b>计划表真的变了</b>");
    }

    // ─────────────────────── ② 诚实的那一半 ───────────────────────

    @Test
    @DisplayName("★★ 但这一条候选永远送不出动作: decisionsDispatched 与 commandsSent 都停在 0")
    void thisCandidateSourceCanNeverProduceAnAction() {
        PlanItem item = covering(T);
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));

        parts.actor().pulse(T);

        assertEquals(0, parts.actor().commandsSent(),
                "§8.5.13 的标题句说没有候选则 commandsSent 恒为 0, 而它给的临时规则桥"
                        + "<b>并不能</b>把它抬起来 —— 请读本文件类注释那张因果链: "
                        + "候选与 currentItem 取自同一个 activeAt 集合, 于是"
                        + " outranksCurrent(自己, 自己) 恒假, 决定恒为 keeping。"
                        + "这一行红了是好事: 说明多了一个<b>不是当前项</b>的候选来源, "
                        + "而那正是动作那一半所缺的东西");
        assertEquals(0, parts.actor().decisionsDispatched(),
                "它甚至进不了'派发'那一步: DecisionEngine 给出的决定没有动作, "
                        + "而 HumanActor 只对带动作的决定计数 —— 这个 0 是同一个原因的另一面");
    }

    @Test
    @DisplayName("★ 计划表里有候选但决定不改任何东西时, 她仍然'什么都没做' —— 与空计划同形, 这正是要点的")
    void aSeededPlanStillLooksQuietOnTheCounters() {
        PlanItem item = covering(T);
        HumanAssembly.Parts seeded = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));
        HumanAssembly.Parts bare = assembleWith(null);

        seeded.actor().pulse(T);
        bare.actor().pulse(T);

        // 这一条是"面板上分不出来"这个事实的机器可读版本:
        // 有内容与没内容, 在计数的这一层长得一模一样。所以诊断不能只看计数 ——
        // 要看 plan.revisionCount()(它区分得开, 见下)。
        assertEquals(bare.actor().commandsSent(), seeded.actor().commandsSent());
        assertEquals(bare.actor().decisionsDispatched(), seeded.actor().decisionsDispatched());
        assertEquals(1, bare.human().life().plan().revisionCount(),
                "空计划的一拍不该产生任何版本 —— 没有候选就没有决定, 没有决定就没有重排");
        assertEquals(2, seeded.human().life().plan().revisionCount(),
                "有内容的那一侧多了一版。这是两者<b>唯一</b>分得开的地方 —— "
                        + "而它恰好不在任何计数上, 所以'她安静地坐着'与'她坏了'"
                        + "在运维面上长得一样(见 HumanActor.candidatesAt 的注释)");
    }

    // ─────────────────────── ③ 空计划是正常的 ───────────────────────

    @Test
    @DisplayName("空计划表 → 一拍安静地跑完, 没有失败、没有假的派发 —— 今天生产就是这个形状")
    void anEmptyPlanProducesAQuietPulse() {
        HumanAssembly.Parts parts = assembleWith(null);

        parts.actor().pulse(T);

        assertEquals(1, parts.actor().pulses());
        assertEquals(0, parts.actor().skipped(), "没被锁挡掉 —— 它是真的跑了");
        assertEquals(0, parts.actor().failures(), "也没有异常被吞掉");
        assertEquals(0, parts.actor().decisionsDispatched());
        assertEquals(0, parts.actor().commandsSent());
        assertEquals(1, parts.human().life().plan().revisionCount());
    }

    // ─────────────────────── ④ dueAt 陷阱 ───────────────────────

    @Test
    @DisplayName("★★ dueAt 陷阱: 跑完一拍之后, 调度器仍然拿得到那一项 —— 证明候选没偷走它的触发")
    void candidatesDoNotStealTheSchedulersTrigger() {
        PlanItem overdue = overdueButPending();
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(covering(T), overdue), "测试种子"));

        parts.actor().pulse(T);

        List<PlanItem> due = parts.human().life().plan().dueAt(T);
        assertTrue(due.stream().anyMatch(i -> i.id().equals(overdue.id())),
                "PlanBoard.dueAt 在返回结果<b>之前</b>就把每一项记进内部的 triggered 表, "
                        + "所以从心跳里问一次 dueAt = 把'到点了'从调度器手里偷走, 那一项"
                        + "永远不会被触发, 而她按计划该做的事一件都不会发生 —— "
                        + "没有异常、没有变红的计数、没有任何日志。"
                        + "本方法为 0 条说明这件事已经发生了: " + due);
        assertEquals(1, due.size(),
                "此刻到点待触发的恰好是那一项(覆盖此刻的那一项已被 KeepActive 翻成 ACTIVE, "
                        + "而 dueAt 要求 PENDING)—— 这条是上一条的阳性对照: " + due);
    }

    @Test
    @DisplayName("★ 反复心跳不会把同一项重复排队 —— 每一拍都重读计划表, 但读的是纯查询")
    void repeatedPulsesStayConsistent() {
        PlanItem item = covering(T);
        HumanAssembly.Parts parts = assembleWith(
                PlanRevision.initial(T, List.of(item), "测试种子"));

        parts.actor().pulse(T);
        parts.actor().pulse(T);
        parts.actor().pulse(T);

        assertEquals(3, parts.actor().pulses());
        assertEquals(0, parts.actor().failures());
        // 第二拍起那一项已是 ACTIVE, 而 activeAt 仍然收 ACTIVE(occupiesFuture),
        // 于是候选每一拍都在、决定每一拍都是 keeping、计划表每一拍都多一版。
        // 这<b>不是</b>想要的终局(它意味着一次持续存在的打断会被反复记账),
        // 但它是今天这条链的真实形状 —— 而"反复记账"正是需要被看见的那种信号。
        assertEquals(4, parts.human().life().plan().revisionCount(),
                "种子 1 版 + 每一拍 1 版。这个数会随心跳线性增长, 而触发它的是"
                        + "'候选恒等于当前项'这个结构性事实(见类注释) —— "
                        + "真实生产者到位后, 这条断言应当重新推导, 而不是照抄改数");
    }
}
