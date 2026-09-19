package com.luxera.companion.runtime;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.action.DefaultActionFabric;
import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.Human;
import com.luxera.companion.human.HumanId;
import com.luxera.companion.human.body.Body;
import com.luxera.companion.human.life.Life;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.human.mind.Mind;
import com.luxera.companion.human.mind.cognition.MindDecisionPlanner;
import com.luxera.companion.human.mind.decision.LanguageEngine;
import com.luxera.companion.human.mind.intention.IntentionPlanIntent;
import com.luxera.companion.human.mind.intention.IntentionPriority;
import com.luxera.companion.human.mind.intention.ProposedIntention;
import com.luxera.companion.human.mind.percept.PerceptLexicon;
import com.luxera.companion.human.mind.relationship.ChatAccountId;
import com.luxera.companion.human.mind.relationship.PersonaSpec;
import com.luxera.companion.human.mind.relationship.RelationshipGraph;
import com.luxera.companion.registry.CapabilityRegistry;
import com.luxera.companion.registry.EventHandlerRegistry;
import com.luxera.companion.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldRuntime} 的测试。
 *
 * <h2>为什么这个类值得有自己的测试</h2>
 * 它是**唯一的心跳**, 而心跳最坏的一类失效是"它还在跑, 只是顺序错了" ——
 * 那时没有异常、没有红字, 只有"她好像总是慢半拍"或"她好像没听见"。
 * 本类里的三条约束都属于这一类:
 * <ul>
 *   <li><b>① 在 ④ 之前</b> —— 写反了, 到点的提醒会晚一拍才到她那儿;</li>
 *   <li><b>一个座位登记两样东西</b> —— 分开登记就会出现"actor 在、调度器不在",
 *       症状是她的计划表永远不走到点;</li>
 *   <li><b>闸门只有一处</b> —— 运行档若在这里也筛一遍, 就有两个读者了。</li>
 * </ul>
 * 这三条都写得出来、都编译得过、都能跑 —— 所以它们必须被测, 不能靠"看起来对"。
 *
 * <h2>为什么这个测试要真的造一个 Human</h2>
 * 因为本类唯一一件真事就是"按那个顺序推一个她"。用 mock 替掉 {@code HumanActor} 之后,
 * 被测的就只剩一个 for 循环 —— 而那正是本类不会出错的部分。
 * 造一个真的她需要身体/生活/认知三块都装配起来, 这在本仓是**第一次**
 * ({@code new Human(...)} 此前没有任何调用点), 所以这个夹具本身也有价值:
 * 它顺带证明了 §3.1 的装配守卫是能通过的, 而不只是会拦人。
 *
 * <h2>这个夹具曾经漏了一行, 而且漏得完全没有症状</h2>
 * 它的 {@code Seat} 一开始<b>没有</b>调 {@code body.registerOn(fabric)} ——
 * 于是 {@code ThresholdDetector} 从来不在 tick 链上, 而她永远不觉得冷。
 * 本类一直是绿的, 因为它测的是计划表与座位的接线, 身体在它那里不参与;
 * 而那个缺口在别处也不会有症状: {@code Human.acceptClockTicked} 自己会调
 * {@code body.advance}, 所以她的状态照样前进、心跳照样每拍成功。
 *
 * <p>现在 {@code Human} 的构造器会当场拦住它({@code requireBodyOnTheBus}),
 * 于是那一行是<b>被守卫逼出来的</b>, 不再是"照抄时要记得带上的一步"。
 * 这也是它值得留在这里的原因: 它是本仓第一处被那道守卫抓到的真实遗漏。
 */
class WorldRuntimeTest {

    private static final Instant T = Instant.parse("2026-03-01T12:00:00Z");
    private static final String WHO = "human-under-test";

    /**
     * 一个装好的她, 以及推着她的那两样东西。
     *
     * <p><b>注意 {@code Mind} 收到的是 {@code life.plan()}, 不是 {@code new PlanBoard()}。</b>
     * 这正是 §8.6.3 那处隐式约定; 写错的话 {@code Human} 的构造器会当场拒绝
     * (见 {@code requireOnePlanBoard}), 所以这里写错不会静默 —— 那正是那道守卫的意义。
     */
    private static final class Seat implements AutoCloseable {

        final String who;
        final SimulationClock clock = SimulationClock.at(T);
        final World world = new World();
        final Body body;
        final EventFabric fabric;
        final Life life;
        final RelationshipGraph relationships;
        final Mind mind;
        final Human human;
        final ActionFabric actions = new DefaultActionFabric(new CapabilityRegistry());
        final HumanActor actor;
        final PlanSchedulerJob scheduler;

        Seat() {
            this(WHO);
        }

        /**
         * 每个座位要有自己的人 —— 共用一个 humanId 会在 {@code bind} 那里当场被拒,
         * 而这正是座位表要的: 两个 actor 抢一个身份是静默的丢失, 不该能写出来。
         */
        Seat(String who) {
            this.who = who;
            this.body = new Body(who);
            this.fabric = new DefaultEventFabric(who, new EventHandlerRegistry());
            // 把身体接到总线上 —— Body(order 10) 与 ThresholdDetector(order 20) 由此进入 tick 链。
            // 这一行此前是漏的, 而本类一直是绿的: 它测的是计划表与座位的接线, 身体不参与,
            // 而**漏掉它不会有任何症状**(状态照样推进、心跳照样成功, 消失的只有感官)。
            // 现在漏掉它会在 Human 的构造器那里当场抛 —— 所以这一行不是"补个形式",
            // 是这道守卫逼出来的第一处真实修复。
            this.body.registerOn(fabric);
            this.life = new Life(who, fabric, PlanningContext.HumanSnapshot::unknown);
            this.relationships = RelationshipGraph.bootstrap(
                    PersonaSpec.neutral("阿澈"), ChatAccountId.of("account-owner"), T);
            this.mind = new Mind(who, fabric, life.plan(), relationships,
                    new MindDecisionPlanner(), LanguageEngine.silent(), PerceptLexicon.generic());
            this.human = new Human(HumanId.of(who), body, life, mind, fabric);
            this.actor = new HumanActor(human, actions);
            this.scheduler = new PlanSchedulerJob(clock, life);
        }

        /**
         * 让她那张计划表上多一项 —— {@code [T, T+1h)} 写作业, 而<b>排它的时刻是 T 之前</b>。
         *
         * <h2>为什么"排的时刻"必须早于"那一项的开始时刻", 否则这个夹具测不到东西</h2>
         * {@code PlanBoard.applyOne} 里有一条: 插入的项若<b>包含</b>重排发生的那个时刻,
         * 它当场变成 {@code ACTIVE} 并立刻被标记为已触发 —— 那正是用户说的
         * "想立马执行就把其触发时间调成现在"。于是若在 {@code T} 排一项 {@code [T, T+1h)}:
         * 它已经是"正在做"了, {@code dueAt} 永远不再返回它, 调度器也就永远不触发。
         *
         * <p>所以这里在 {@code T} 之前排, 让它停在 {@code PENDING} ——
         * 于是 {@code pulse(T)} 的 ① 里它会真的到点。这两条路是<b>互斥</b>的,
         * 而它们是两个不同的用户需求: "现在就做"与"到点了提醒我"。
         */
        void planHomework() {
            PlanIntent intent = new IntentionPlanIntent(
                    ProposedIntention.of("study", "写作业", IntentionPriority.ROUTINE));
            life.plan().apply(T.minusSeconds(3600), "测试: 排一项",
                    List.of(new PlanMutation.Insert(
                            PlanItem.schedule(intent, TimeWindow.startingAt(T, Duration.ofHours(1))),
                            "她打算写作业")));
        }

        @Override
        public void close() {
            // 没有需要关的东西 —— 这个夹具存在的意义是让 try-with-resources 读起来
            // 像"这一段有一个边界", 而不是留一个空壳。
        }
    }

    /** 一个不取任何东西的环境源 —— 本类的测试都不该真的外呼。 */
    private static EnvironmentRefreshJob envJob(SimulationClock clock) {
        return new EnvironmentRefreshJob(clock, at -> List.of());
    }

    private static WorldRuntime runtimeFor(Seat... seats) {
        World world = seats[0].world;
        EnvironmentRefreshJob job = envJob(seats[0].clock);
        WorldRuntime runtime = new WorldRuntime(world, seats[0].clock, job);
        for (Seat seat : seats) {
            runtime.bind(seat.actor, seat.scheduler);
        }
        return runtime;
    }

    // ─────────────────────────── 座位表 ───────────────────────────

    @Test
    @DisplayName("同一个 humanId 不能登记两次")
    void 同一个humanId不能登记两次() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);

            // 第二次登记必须当场炸。让它过的症状是"第二次的 actor 覆盖第一次的",
            // 而第一个 actor 的生命周期、计数、以及它已经收到的刺激全部静默丢失 ——
            // 一个已经在跑的她凭空消失, 且没有任何一行日志说这件事。
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> runtime.bind(seat.actor, seat.scheduler),
                    "重复登记同一个 humanId 必须被拒 —— 覆盖是静默的, 而静默的丢失查不出来");

            assertTrue(e.getMessage().contains(WHO), e.getMessage());
            assertEquals(1, runtime.seatCount(), "被拒之后座位数不该变");
        }
    }

    @Test
    @DisplayName("没有登记的 humanId 不许被操作")
    void 没有登记的humanId不许被操作() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> runtime.applyLifecycle("someone-else", AgentLifecycle.PAUSED));

            // 消息里必须带上"已登记的是谁": 只说"没有这个 id"的话, 读日志的人
            // 第一件事就是去翻装配代码 —— 而答案本可以就写在这一行里。
            assertTrue(e.getMessage().contains(WHO), e.getMessage());
        }
    }

    // ─────────────────────────── 心跳 ───────────────────────────

    @Test
    @DisplayName("一次心跳把每一个座位都推到了")
    void 一次心跳把每一个座位都推到了() {
        try (Seat one = new Seat(); Seat two = new Seat("human-two")) {
            WorldRuntime runtime = runtimeFor(one, two);

            runtime.pulse(T);

            assertEquals(1, runtime.pulses());
            assertEquals(1, one.actor.pulses(), "座位一没被推到");
            assertEquals(1, two.actor.pulses(), "座位二没被推到");
            assertEquals(T, runtime.lastInstant().orElseThrow());
        }
    }

    @Test
    @DisplayName("运行档只被 HumanActor 判一次 —— 本类照样调她")
    void 运行档只被HumanActor判一次() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);
            runtime.applyLifecycle(WHO, AgentLifecycle.PAUSED);

            runtime.pulse(T);

            // 关键在于这三个数各自说明什么:
            //   runtime.pulses() == 1  → 心跳走到了这个座位(它没有自己筛掉她)
            //   actor.pulses()   == 0  → 她没真的跑
            //   actor.pausedPulses() == 1 → 而"没跑"这件事被记下来了
            // 本类若也筛一遍运行档, 第二个数仍然是 0, 但第三个数也会是 0 ——
            // 于是"她被暂停了"与"她压根不在座位表上"在计数上无法区分。
            assertEquals(1, runtime.pulses());
            assertEquals(0, seat.actor.pulses(), "暂停的座位不该真的跑认知");
            assertEquals(1, seat.actor.pausedPulses(),
                    "被跳过的那一拍必须留下痕迹, 否则它与'她不在座位表上'分不开");

            // 刺激会积压 —— 那是 §3.6.6 的"她不看", 不是故障。
            assertFalse(seat.actor.lastPulseAt().isPresent(),
                    "暂停的一拍不该更新 lastPulseAt: 它是'她最后一次真的跑过'的时刻");
        }
    }

    @Test
    @DisplayName("时钟倒流当场抛出, 不被吞掉")
    void 时钟倒流当场抛出() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);
            runtime.pulse(T);

            // 倒流必须抛, 而且必须抛到调用方。若被 pulseNow 里那个 catch(RuntimeException)
            // 吞掉, 症状是"她的一切照常, 只是账本按一个更早的时刻结算" ——
            // 她身上的影响会凭空变强, 且没有任何事件解释这个变化。
            assertThrows(IllegalArgumentException.class, () -> runtime.pulse(T.minusSeconds(1)));

            assertEquals(1, runtime.pulses(), "被拒的那一拍不该计入");
            assertEquals(1, seat.actor.pulses(), "被拒的那一拍不该推到她身上");
        }
    }

    @Test
    @DisplayName("到点的提醒在同一个 tick 就出队 —— 不是下一个 tick")
    void 到点的提醒在同一个tick就出队() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);
            seat.planHomework();

            assertEquals(0, seat.actor.stimuliFromWorld(),
                    "还没心跳过, 队列里不该有东西");

            runtime.pulse(T);

            // 先分开钉住前半段: ① 到底有没有真的触发。没有这一步的话,
            // 下面那条断言失败时分不清是"调度器没触发"还是"触发的事件没进队列" ——
            // 而这两件事的修法完全不同。
            assertEquals(1, seat.scheduler.triggersFired(),
                    "① 应该触发一次 —— 否则下面的 0 是在说调度器, 不是在说队列");

            // 这一条钉的是 §8.5.5 那处**写反过的**论证: ① 里发出的 plan.item-due.v1,
            // 在同一个 tick 的 ④b 就躺在队列里等着出队(因为 EventFabric.route 是在
            // publish 内部同步塞进去的)。若① 排在 ④ 之后, 这个数会是 0, 而下一个
            // tick 才会变成 1 —— 那意味着"她总是晚一拍才想起来该做什么"。
            assertTrue(seat.actor.stimuliFromWorld() >= 1,
                    "到点的提醒必须在同一个 tick 里被她看到, 实际 stimuliFromWorld="
                            + seat.actor.stimuliFromWorld());
        }
    }

    @Test
    @DisplayName("设备演化每拍都在推进")
    void 设备演化每拍都在推进() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);

            runtime.pulse(T);
            runtime.pulse(T.plusSeconds(1));

            assertEquals(2, seat.world.advanceCount(),
                    "advanceDevices 每拍必须被调到 —— 少了它, 手机电量、闹钟、自动熄屏全都不动了");
            assertEquals(T.plusSeconds(1), seat.world.lastAdvanceAt().orElseThrow());
        }
    }

    // ─────────────────────────── 环境: 取在别处, 用在这里 ───────────────────────────

    @Test
    @DisplayName("没有读数的一轮刷新被跳过, 而不是当成故障")
    void 没有读数的一轮刷新被跳过() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);
            EnvironmentRefreshJob job = runtime.environmentRefreshJob();

            // 一份读数都没取到是正常结果(每个地点都没人)。它必须与"取数失败"分开:
            // 前者是安静的跳过, 后者要计数、要告警。
            runtime.applyRefresh(job.pulse(T), T);

            assertEquals(1, runtime.refreshSkips(),
                    "空的一轮刷新要计入跳过 —— 否则'取回来了但没用上'与'本来就没事'分不开");
            assertEquals(0, runtime.refreshApplications());
        }
    }

    // ─────────────────────────── 停机 ───────────────────────────

    @Test
    @DisplayName("关闭之后再心跳是编程错误, 不是静默无事")
    void 关闭之后再心跳是编程错误() {
        try (Seat seat = new Seat()) {
            WorldRuntime runtime = runtimeFor(seat);
            runtime.pulse(T);
            runtime.close();

            // 必须抛而不是静默返回: 关掉之后的 pulse 会往一条不再执行的队列里塞任务,
            // 而 submit 是成功的 —— 于是"她还在被推进"这句话在调用方看来为真,
            // 实际上她已经停了。一个成功的假动作比一个错误更难查。
            assertThrows(IllegalStateException.class, () -> runtime.pulse(T.plusSeconds(1)));

            // close 是幂等的: Spring 的停机路径可能在多个地方调它。
            runtime.close();
        }
    }

    @Test
    @DisplayName("摘要把座位数、暂停中的人和心跳数都说出来")
    void 摘要说全了() {
        try (Seat one = new Seat(); Seat two = new Seat("human-two")) {
            WorldRuntime runtime = runtimeFor(one, two);
            runtime.applyLifecycle(WHO, AgentLifecycle.PAUSED);
            runtime.pulse(T);

            String line = runtime.describe();

            assertTrue(line.contains("2 个座位"), line);
            assertTrue(line.contains(WHO), "暂停中的人必须被点名, 只说'有 1 个暂停'等于没说: " + line);
            assertTrue(line.contains("心跳 1 次"), line);
        }
    }
}
