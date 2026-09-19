package com.luxera.companion.runtime;

import com.luxera.companion.boundary.HumanRuntimeContext;
import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.action.DefaultActionFabric;
import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanEvent;
import com.luxera.companion.human.HumanId;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.mind.Mind;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import com.luxera.companion.registry.CapabilityRegistry;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §8.6.3 第 5 步 —— <b>装出来的她是不是一个能活的她</b>。
 *
 * <h2>这个测试防的是一个"不会抛异常"的错</h2>
 * 装配一个 {@code Human} 要七行, 而其中<b>只有一行会静默失效</b>:
 * {@code body.registerOn(fabric)}。漏掉它之后, 消失的<b>不是</b>"她的状态推进",
 * 而是她的<b>感官</b> —— 这一点第一版写错了, 值得留在这里:
 *
 * <pre>
 *   fabric.tick(now, ctx)      → 在一张空处理器链上正常返回 (tickHandlers == 0)
 *   Human.acceptClockTicked    → <b>自己</b>调 body.advance(...)  ★ 状态照样推进!
 *   hasTicked() / lastTickAt   → 照样变成真
 *   HumanActor.pulse(now)      → 把这一拍计成一次成功的心跳
 *   ThresholdDetector          → 不在链上, 于是<b>一条刺激都产不出来</b>
 *   她冷不冷 / 饿不饿 / 累不累  → 永远不变(不是因为没有结算, 是因为没有人判断越界)
 * </pre>
 *
 * <p>没有任何计数会涨、没有任何日志会打、没有任何异常会抛。她在每一个面板上
 * 都与一个健康的 agent 长得一模一样, 唯一的区别是<b>她什么都不经历</b>。
 *
 * <h2>由此得到的一个教训: "她在动"不是一个能用的读数</h2>
 * 本类第一版断言的是 {@code hasTicked()}, 而阳性对照当场证伪了它 ——
 * {@code acceptClockTicked} 在 {@code fabric.tick} 之后<b>自己</b>推进身体一次,
 * 所以那个值与接线无关。真正能区分的只有<b>链上有几个 handler</b>,
 * 于是这里读 {@code EventFabric.TickReport.tickHandlers()} —— 那是总线自己的报告,
 * 不是我替它算的。
 *
 * <p>而这个缺口今天已经<b>不能到达生产</b>: {@code Human} 的构造器会当场拦住它
 * ({@code requireBodyOnTheBus})。所以下面那条阳性对照测的不是"她会不会静默失效",
 * 而是两件仍然需要钉住的事: 总线在那份装配下<b>确实是空的</b>(这正是构造器要拦的东西),
 * 以及那个守卫<b>确实会响</b>。
 */
class HumanAssemblyTest {

    private static final String HUMAN = "hum_assembly_test";
    private static final Instant T = Instant.parse("2026-03-01T08:00:00Z");

    /** tick 链上应当有的两个人: {@code Body}(order 10) 与 {@code ThresholdDetector}(order 20)。 */
    private static final int TICK_HANDLERS_EXPECTED = 2;

    private static ActionFabric actions() {
        return new DefaultActionFabric(new CapabilityRegistry());
    }

    /**
     * 跳两拍 —— 而不是一拍。
     *
     * <p>因为 {@code Body.advance} 要算 Δt, 而第一拍没有"上一拍"可比。
     * 跳两拍让"她确实被推着走"与"她只是被记了一下时刻"分开 ——
     * 后者在任何面板上都不是活着的样子。
     */
    private static void tickTwice(Human human) {
        human.accept(new HumanEvent.ClockTicked(T), HumanRuntimeContext.at(HUMAN, T));
        human.accept(new HumanEvent.ClockTicked(T.plusSeconds(60)),
                HumanRuntimeContext.at(HUMAN, T.plusSeconds(60)));
    }

    @Test
    @DisplayName("装出来的她被接在心跳链上, 而且真的被推着走")
    void 装出来的她能跳心跳() {
        HumanAssembly.Parts parts = HumanAssembly.assemble(
                HUMAN, AgentLifecycle.ACTIVE, actions(), SimulationClock.at(T));

        assertEquals(HUMAN, parts.humanId());
        assertEquals(HUMAN, parts.actor().humanId().value(),
                "执行体不是她的 —— 那意味着心跳驱动的是另一个人");
        assertNotNull(parts.fabric());
        assertNotNull(parts.scheduler());

        // ① 她还没跳过。这一条必须**在下面那次 tick 之前**: tick 本身就会推进身体,
        //    所以放在后面它恒为真 —— 而一个恒真的前置断言会让 ③ 无法证伪。
        assertFalse(parts.human().contextAt(T).body().hasTicked(),
                "刚装好的她不该已经跳过 —— 一个恒真的 hasTicked 会让 ③ 空转");

        // ② 她<b>在链上</b>。这一条才是 registerOn 的证明, 而且它是总线自己报的数。
        //    用 >= 而不是 ==: 今天链上就该是 Body 与 ThresholdDetector 两个人,
        //    而将来往她身上加第三个结算型 handler 是一件合法的事 ——
        //    那时候这条断言不该红。少了人(尤其是少到 0)才是故障。
        assertTrue(parts.fabric().tick(T, HumanRuntimeContext.at(HUMAN, T)).tickHandlers() >= TICK_HANDLERS_EXPECTED,
                "她这条总线的 tick 链上不足 " + TICK_HANDLERS_EXPECTED + " 个人 —— "
                        + "装配时漏了 body.registerOn(fabric)。注意这个缺口**不会**让她的状态停住: "
                        + "acceptClockTicked 自己会调 body.advance, 于是 hasTicked() 照样为真。"
                        + "真正消失的是 ThresholdDetector, 于是她永远不觉得冷");

        tickTwice(parts.human());

        // ③ 时钟真的走到了她身上。这一条与 ② 是两件事, 而且它**不能**替代 ②:
        //    一个没接线的她也照样会通过这一条(见类注释那张表)。
        assertTrue(parts.human().contextAt(T.plusSeconds(60)).body().hasTicked(),
                "她跳了两拍而身体没有被推进 —— Human.acceptClockTicked 没有把时钟转给 Body");
    }

    @Test
    @DisplayName("漏掉接线时总线是空的, 而且装配当场把它拦下来 —— 上一条断言的阳性对照")
    void 漏掉接线时装配当场被拦住() {
        // 手工装一份**故意漏掉 registerOn** 的聚合。它必须与
        // HumanAssembly.assemble 的其余部分一模一样, 否则这条对照证明的是别的东西。
        Body body = new Body(HUMAN);
        EventFabric fabric = new DefaultEventFabric(HUMAN, new EventHandlerRegistry());
        // ← 这里刻意没有 body.registerOn(fabric)
        Life life = new Life(HUMAN, fabric, PlanningContext.HumanSnapshot::unknown);
        Mind mind = new Mind(HUMAN, fabric, life.plan(), RelationshipGraph.empty(),
                new MindDecisionPlanner(), LanguageEngine.silent(), PerceptLexicon.generic());

        // 第一半: 这份装配下总线真的是空的。没有这一条, 下面那个"守卫会响"就
        // 只是"某个异常被抛了", 而它拦的到底是哪一件事没有证据。
        assertEquals(0, fabric.tick(T, HumanRuntimeContext.at(HUMAN, T)).tickHandlers(),
                "漏掉 registerOn 的装配居然还有人接在 tick 链上 —— "
                        + "那么本类的主断言(链上至少有 2 个人)证明不了接线这件事");

        // 第二半: 守卫必须响, 而且理由必须指向"少了一次 registerOn"。
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Human(HumanId.of(HUMAN), body, life, mind, fabric),
                "漏掉 body.registerOn(fabric) 的装配必须当场被拦住 —— "
                        + "否则它会以'她永远不觉得冷'的形式潜伏下去, 而每一个计数都是正常的");

        assertTrue(thrown.getMessage().contains("registerOn"),
                "异常里必须点名缺的那一次调用, 而不是只说'装配错了': " + thrown.getMessage());
    }

    @Test
    @DisplayName("Life 与 Mind 拿到不同计划表时装配当场失败")
    void 两张计划表会被当场拦住() {
        // 这是 HumanAssembly 里那一行 `life.plan()` 的理由。
        // 传 new PlanBoard() 不会让任何东西变慢或报错 —— 它让她**重排进一张没人调度的表**:
        // 决定不影响行为, 重排一条事件都不发, 而日志里一片正常。
        // 用户专门纠正过两次的那条"打断 = 真的去改计划表"整条是死的。
        Body body = new Body(HUMAN);
        EventFabric fabric = new DefaultEventFabric(HUMAN, new EventHandlerRegistry());
        body.registerOn(fabric);
        Life life = new Life(HUMAN, fabric, PlanningContext.HumanSnapshot::unknown);
        Mind mind = new Mind(HUMAN, fabric, new PlanBoard(), RelationshipGraph.empty(),
                new MindDecisionPlanner(), LanguageEngine.silent(), PerceptLexicon.generic());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new Human(HumanId.of(HUMAN), body, life, mind, fabric),
                "两张不同的计划表必须当场被拦住 —— 它不会以别的方式显形");

        assertTrue(thrown.getMessage().contains("不同的"),
                "异常里必须说清是'两张不同的计划表', 而不是别的原因: " + thrown.getMessage());
    }

    @Test
    @DisplayName("运行档被原样带进执行体")
    void 运行档被原样带进执行体() {
        // §3.6.6: PAUSED 停的是**认知**, 所以判断点在 tick 的入口, 不在装配处 ——
        // 装配照样装, 她只是不被推进。这一点必须由装配层负责传对,
        // 因为用 ACTIVE 兜底会让一个本该暂停的 agent 在装配那一刻就动起来。
        HumanAssembly.Parts paused = HumanAssembly.assemble(
                HUMAN, AgentLifecycle.PAUSED, actions(), SimulationClock.at(T));

        assertEquals(AgentLifecycle.PAUSED, paused.actor().lifecycle(),
                "装配时传进去的运行档没有到执行体上 —— 她会在被暂停的状态下跑起来");
    }

    @Test
    @DisplayName("装配不写库, 也不入座 —— 那两件事归恢复器")
    void 装配只负责装配() {
        // 这一条钉的是**职责分界**, 而不是某个行为: HumanAssembly 只把三块部件接到
        // 同一条总线上, 它不 bind 座位(那是 WorldRuntime 的)、不登记(那是
        // LiveHumanRegistry 的)、也不读库(那是 RecoveryRuntime 的)。
        // 边界一旦糊掉, 就会出现第二条通向"活着的她"的路径, 而两条路径
        // 迟早会对同一件事给出两个答案。
        HumanAssembly.Parts a = HumanAssembly.assemble(
                "hum_a", AgentLifecycle.ACTIVE, actions(), SimulationClock.at(T));
        HumanAssembly.Parts b = HumanAssembly.assemble(
                "hum_b", AgentLifecycle.ACTIVE, actions(), SimulationClock.at(T));

        assertFalse(a.fabric() == b.fabric(), "两个她共用了一条总线 —— 那是把两个人接到同一个身体上");
        assertFalse(a.human().id().equals(b.human().id()));
        assertEquals("hum_a", a.humanId());
        assertEquals("hum_b", b.humanId());
    }

    @Test
    @DisplayName("空参数当场拒绝")
    void 空参数当场拒绝() {
        assertThrows(NullPointerException.class,
                () -> HumanAssembly.assemble(null, AgentLifecycle.ACTIVE, actions(), SimulationClock.at(T)));
        assertThrows(NullPointerException.class,
                () -> HumanAssembly.assemble(HUMAN, null, actions(), SimulationClock.at(T)));
        assertThrows(NullPointerException.class,
                () -> HumanAssembly.assemble(HUMAN, AgentLifecycle.ACTIVE, null, SimulationClock.at(T)));
        assertThrows(NullPointerException.class,
                () -> HumanAssembly.assemble(HUMAN, AgentLifecycle.ACTIVE, actions(), null));
    }
}
