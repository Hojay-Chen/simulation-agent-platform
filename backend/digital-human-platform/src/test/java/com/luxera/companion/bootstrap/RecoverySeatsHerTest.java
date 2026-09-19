package com.luxera.companion.bootstrap;

import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.persistence.store.ActivityStore;
import com.luxera.companion.persistence.store.PlanStore;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.runtime.LiveHumanRegistry;
import com.luxera.companion.runtime.RecoveryRuntime;
import com.luxera.companion.runtime.WorldRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>端到端: 库里出现一个 agent 之后, 她真的在世界里活着 —— 并且带着她的历史。</b>
 *
 * <h2>为什么这一条非有不可</h2>
 * 本仓此前<b>没有任何一次</b> {@code new Human(...)} 的生产调用点 ——
 * 装配层的第 5/6/7 步是这一层唯一"看起来能跑、其实一个人都没有"的部分。
 * 它的失效方式全都是静默的:
 *
 * <pre>
 *   花名册读错表        → 座位 0, 而日志照打"装配完成"
 *   装好了但没 bind     → 她不在心跳里, 而座位表与日志都是空的
 *   绑了但没 registerOn → 她永不觉得冷(不是"不推进" —— 见下面那一段),
 *                         而每一拍都被计成一次成功的心跳
 *   没登记进登记处      → 控制台以为她不在这台机器上
 *   历史读出来却丢了    → 她照常活着、照常跳, 只是忘了自己下午要去实验室
 * </pre>
 *
 * <p>五个都不抛异常。所以这里不用"断言某个方法返回了真"来证明它们,
 * 而是走完整条链: <b>往库里插行 → 跑恢复 → 等真实的 tick → 读她的快照</b>。
 *
 * <h2>这一条能证明什么, 不能证明什么</h2>
 * 它能证明的是: <b>她真的被这台机器的心跳驱动着</b> —— 一个没被 bind 进座位表的她,
 * 在这里等多久 {@code lastTickAt} 都是空的, 因为没有任何东西会去 accept 一条时钟事件。
 * 这是"库里的一行变成了活着的她"这句话里唯一无法伪造的那一半。
 *
 * <p>它<b>不能</b>证明 {@code registerOn} —— 这一点值得写下来, 因为本类的
 * 第一版正是这么以为的: 当时读的是 {@code hasTicked()}, 而那个值在漏掉
 * {@code registerOn} 的装配下<b>照样为真</b>。原因是 {@code Human.acceptClockTicked}
 * 在 {@code fabric.tick} 之后<b>自己</b>调了一次 {@code body.advance}, 于是
 * "她的状态有没有前进"与"她在不在 tick 链上"是两件无关的事。
 * 那个缺口今天由 {@code Human} 的构造器当场拦住({@code requireBodyOnTheBus}),
 * 而钉住它的是 {@code HumanAssemblyTest} 里那条读 {@code TickReport.tickHandlers()} 的断言 ——
 * 只有"链上有几个人"能区分这两种装配。
 *
 * <h2>本类刚被改过一次: 第 ② 步从"拒绝"变成了"真的恢复"</h2>
 * {@code Life} 拿到装载入口之前, 凡是库里有计划存量的 agent 都会被
 * {@code RecoveryRuntime} 拒绝入座 —— 那条规则拒绝的其实是<b>本仓自己的功能缺口</b>。
 * 缺口补上之后, 留下来的拒绝只有一种: <b>库里的两行互相矛盾</b>
 * ("计划说她在做"与"活动表里没有那件事"对不上)。本类因此有两条对称的用例:
 * 装得回来的必须<b>真的装回来</b>({@link #库里的计划会被装回来()}),
 * 装不回来的必须被拒绝而不是被猜({@link #计划说她在做而活动表说没有时拒绝入座()})。
 *
 * <h2>为什么每个用例用一个新的 humanId, 而不是共用一个常量</h2>
 * 座位表与登记处是<b>内存态</b>的, 而库里的行是每个用例之后删掉的 ——
 * 两者不同步。共用一个 id 会让第二个调用 {@code recover()} 的用例读到
 * {@code skipped=1, seated=0}, 于是它的断言取决于"哪个用例先跑"。
 * 每个用例自己造一个 id, 就把这件事从"用例顺序"里拿了出来。
 *
 * <h2>为什么它用一个单独的上下文(改一个无关的属性)</h2>
 * 因为本类要往库里插人。{@code SimulationConfigurationTest} 断言的是
 * <b>启动那一刻</b>的几个数(座位数 vs 花名册), 而那种断言对"库里现在有几个人"
 * 是敏感的。Spring 的上下文缓存键包含 {@code properties}, 所以这里把
 * {@code tick-ms} 改掉一个值, 拿到的就是一个独立上下文 ——
 * 两个类互不干扰, 而它们的断言各自仍然是真的。
 *
 * <h2>它写完会自己清理</h2>
 * 用的是别人的库({@code companion_test_v22}), 所以 {@link #cleanup()} 在
 * <b>每个</b>用例之后把插进去的行删干净 —— 包括用例失败的那条路。
 * 留一行下去会让下一个人的启动摘要多出一个 agent, 而那种故障看起来
 * 与本层毫无关系。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "companion.sim.enabled=true",
        "companion.sim.tick-ms=53",
        "companion.sim.environment-interval=PT1S"
})
class RecoverySeatsHerTest {

    /** 本类所有用例的 humanId 前缀 —— 清理按前缀删, 于是不会碰到别人的行。 */
    private static final String PREFIX = "hum_e2e_";

    /** 场景时刻。全部由调用方给定, 不读系统时钟。 */
    private static final Instant T_0800 = Instant.parse("2026-03-01T08:00:00Z");
    private static final Instant T_0900 = Instant.parse("2026-03-01T09:00:00Z");

    @Autowired
    WorldRuntime runtime;
    @Autowired
    RecoveryRuntime recovery;
    @Autowired
    LiveHumanRegistry live;
    @Autowired
    PlanStore plans;
    @Autowired
    ActivityStore activities;
    @Autowired
    DomainTypeRegistry registry;
    @Autowired
    JdbcTemplate jdbc;

    /**
     * 把本类那个意图夹具登记进**这个上下文的那一个**注册表。
     *
     * <p>必须是那个 bean, 不能自己 {@code new} 一个: {@code PlanStore} 的编解码器
     * 持有的是容器里那一个 —— 登记到别处等于没登记, 而症状是写库时抛
     * {@code UnknownDomainTypeException}, 看起来像"夹具少了个注解"。
     *
     * <p>这会永久改变这个上下文的注册表, 而那是可接受的: 本类用的是
     * 一个只属于它的上下文({@code tick-ms=53}), 而注册是幂等的。
     */
    @BeforeEach
    void registerFixtureTypes() {
        registry.register(FixedIntent.class);
    }

    @AfterEach
    void cleanup() {
        // 顺序与依赖方向一致: plan_item / plan_constraint 按 revision_id 反向指向
        // plan_revision, 所以它们必须先走 —— 留一张没有版本的项表会让下一个人
        // 在一个"计划有几项"的问题上读到一份没有任何东西对应的答案。
        String like = PREFIX + "%";
        jdbc.update("delete from plan_constraint where revision_id in "
                + "(select revision_id from plan_revision where human_id like ?)", like);
        jdbc.update("delete from plan_item where human_id like ?", like);
        jdbc.update("delete from plan_revision where human_id like ?", like);
        jdbc.update("delete from activity_record where human_id like ?", like);
        jdbc.update("delete from continuous_effect where human_id like ?", like);
        jdbc.update("delete from agent_ownership where human_id like ?", like);
    }

    /** 一个本用例独有的 humanId。见类注释最后第二段。 */
    private static String freshHumanId() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 插一行归属。这是"平台上建了一个 agent"在这张表上的样子。
     *
     * <p>刻意<b>不</b>插 {@code human_record}: 本仓此前那次探针证明了
     * {@code AgentRegistry.roster()} 容忍身份行缺席(它给的是一个
     * {@code materialized() == false} 的档案), 而"归属行在、身份行还没建好"
     * 是一个真实会出现的中间状态。用最少的行去装人, 才证明装配不依赖
     * 那些它不是非要不可的东西。
     */
    private void seedOwnership(String humanId, String lifecycle) {
        jdbc.update("insert into agent_ownership "
                        + "(id, created_at, updated_at, human_id, lifecycle, owner_user_id) "
                        + "values (?, ?, ?, ?, ?, ?)",
                "own_" + humanId, LocalDateTime.now(), LocalDateTime.now(),
                humanId, lifecycle, "user_e2e");
    }

    // ─────────────────────── 一、装得回来 ───────────────────────

    @Test
    @DisplayName("库里出现一个该跑的 agent 之后, 她真的被装进座位表并被 tick 推进")
    void 库里的agent会被装进来并真的开始跳() {
        String id = freshHumanId();
        seedOwnership(id, "active");

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.seated(), "该跑的有一个人, 而入座 " + report.seated() + " 个 —— "
                + "完整的报告: " + report.describe());
        assertTrue(runtime.humanIds().contains(id),
                "她没有进座位表 —— 花名册读到了人, 而装配那一步没有走通");
        assertNotNull(live.contextOf(id),
                "她在座位表上却不在登记处里 —— 控制台会以为她不在这台机器上");

        // 最后一步, 也是唯一无法伪造的一步: 等真实的 tick 把时钟走到她身上。
        // 一个没被 bind 进座位表的她, 在这里等一整天 lastTickAt 都是空的 ——
        // 因为没有任何东西会去 accept 一条时钟事件。
        assertTrue(waitUntilTicked(id, Duration.ofSeconds(10)),
                "她在座位上坐了 10 秒而时钟一次都没有走到她身上 —— "
                        + "心跳在跳, 而她不在心跳里。这一条几乎总是 WorldRuntime.bind 那一环没接上"
                        + "(注意它**不是** body.registerOn 的证据: 漏掉那一行的她照样会 tick, "
                        + "见本类类注释最后一段)");
    }

    @Test
    @DisplayName("库里的那一版计划会被装回来 —— 走的是装载入口, 不是一张空白表")
    void 库里的计划会被装回来() {
        String id = freshHumanId();
        seedOwnership(id, "active");
        PlanRevision stored = PlanRevision.initial(T_0800,
                List.of(item("homework", "写作业", PlanLifecycle.PENDING)), "端到端测试: 造一版计划");
        // 用真的写入口去造这一版计划, 而不是手写 SQL —— 那样造出来的行
        // 一定是良构的, 于是这一条测的是**恢复**, 不是我的 SQL 对不对。
        plans.appendRevision(id, stored);

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.seated(), "库里有计划不该再是拒绝的理由 —— "
                + "那一条拒绝曾经拒绝的是本仓自己的功能缺口(Life 没有装载入口), 而它已经补上了。"
                + "完整的报告: " + report.describe());
        assertEquals(0, report.refused(), "她被拒绝了 —— 报告: " + report.describe());
        assertEquals(1, report.planItems(),
                "报告里的计划项数没有跟着恢复走 —— 那一格曾经是一个写死的零, "
                        + "因为装载入口不存在, 于是'补回来几项'这个问题没被问过");

        var context = live.contextOf(id);
        assertNotNull(context, "她被装进来了却不在登记处里 —— 控制台会以为她不在这台机器上");
        assertEquals(stored.revisionId(), context.life().revisionId(),
                "库里那一版计划没有被装到她身上 —— 她照常活着、照常跳, "
                        + "只是忘了自己下午要去实验室。这一条读的是**读面**(LiveHumanRegistry), "
                        + "所以它同时证明了外面看得到这件事");
        assertEquals(1, context.life().items().size());
    }

    @Test
    @DisplayName("库里的进行中活动也会被装回来 —— 她带着手上那件事继续活")
    void 库里的活动会被装回来() {
        String id = freshHumanId();
        seedOwnership(id, "active");
        PlanItem doing = item("homework", "写作业", PlanLifecycle.ACTIVE);
        plans.appendRevision(id, PlanRevision.initial(T_0800, List.of(doing),
                "端到端测试: 她正在写作业"));
        Activity running = ActivityFactory.start(doing.intent(), T_0900, doing.id());
        activities.append(id, running);

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.seated(), "报告: " + report.describe());
        assertEquals(0, report.refused(), "报告: " + report.describe());

        var context = live.contextOf(id);
        assertNotNull(context);
        assertNotNull(context.life().current(),
                "库里那一条 RUNNING 没有装回来 —— 于是 begin(...) 不再拒绝: "
                        + "同一段时间会被做两次, 而事后从数据上看不出来");
        assertEquals(running.id().value(), context.life().current().id().value(),
                "装回来的是另一件事 —— 她手上在做的事情与库里那一行对不上");
    }

    // ─────────────────────── 二、装不回来的: 拒绝, 不猜 ───────────────────────

    /**
     * <b>库里那两行互相矛盾时, 不许挑一个信。</b>
     *
     * <p>这一条钉的是 {@code RecoveryRuntime} 那个有主张的决定。它今天不会
     * 在生产里响(计划表与活动表都还没有生产者), 所以它只能在这里被钉住 ——
     * 而它必须被钉住: 那条规则的全部价值在于<b>下一个把计划生产者接上的人</b>
     * 会在第一次启动时看到它, 而不是在几天后从"她怎么什么都不做"倒推回来。
     *
     * <p>这里造的正是"写完计划表就崩了"的形状: 计划里有一项是 {@code ACTIVE},
     * 而活动表里没有对应的 {@code RUNNING} 行 —— 两次写入之间没有事务,
     * 第二次没写成。
     */
    @Test
    @DisplayName("计划说她在做而活动表里没有 —— 拒绝入座, 而不是猜一个")
    void 计划说她在做而活动表说没有时拒绝入座() {
        String id = freshHumanId();
        seedOwnership(id, "active");
        plans.appendRevision(id, PlanRevision.initial(T_0800,
                List.of(item("homework", "写作业", PlanLifecycle.ACTIVE)),
                "端到端测试: 计划说她在写作业, 而活动表里没有那条 RUNNING 行"));

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.refused(), "库里那两行对不上, 而恢复必须拒绝 —— "
                + "装计划那一半她会以为自己在做那件事(于是同一段时间被做两次), "
                + "装活动那一半什么也装不上。完整的报告: " + report.describe());
        assertFalse(runtime.humanIds().contains(id),
                "她被拒绝了却仍然进了座位表 —— 那么库里那一版计划与内存里的空白"
                        + "会同时存在, 而'她今天打算做什么'有两个答案");
        assertNull(live.contextOf(id),
                "她被拒绝了, 却仍然在登记处里 —— 控制台会看到一个不在心跳里的她");

        RecoveryRuntime.Refusal refusal = recovery.refusals().stream()
                .filter(r -> r.humanId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("拒绝清单里没有 " + id));

        // 理由必须**可操作**: "她被拒绝了"这句话本身不指向任何一处改动,
        // 而读它的人需要的正是"该去核对哪两行"。
        assertTrue(refusal.reason().contains("不自洽"), refusal.reason());
        assertTrue(refusal.reason().contains("计划"), refusal.reason());
        assertTrue(refusal.reason().contains("活动"), refusal.reason());

        // 摘要与拒绝清单的接线由 SimulationConfigurationTest 钉(那里 summary 与
        // recovery 是同一个上下文里的两个 bean)。这里刻意**不**比一次:
        // summary 是**启动那一刻**的快照, 而这次拒绝发生在它之后 ——
        // 拿它们相比会得到一个恒假的断言, 或者一个我自己跟自己比的恒真断言。
    }

    // ─────────────────────── 测试脚手架 ───────────────────────

    /** 一项计划 —— 用真的领域对象造, 不手写 JSON。 */
    private static PlanItem item(String intentId, String description, PlanLifecycle lifecycle) {
        return PlanItem.schedule(new FixedIntent(new PlanIntent.IntentId(intentId), description),
                        TimeWindow.of(T_0900, T_0900.plusSeconds(3600)))
                .withLifecycle(lifecycle);
    }

    /**
     * 轮询她的身体快照, 直到它说"我跳过"。
     *
     * <p>用 {@code LiveHumanRegistry.contextOf} 而不是直接读 {@code Body} ——
     * 那是 §3.1.4 为跨模块只读开的门, 也是控制台看到她的那条路。
     * 于是一条断言同时证了两件事: 她真的被推着走, 而且外面<b>看得到</b>她被推着走。
     *
     * <p>这里读墙上时钟是允许的: 测试不在 {@code human/world/boundary} 三个包里,
     * 而 §8.5.1 禁的是<b>仿真</b>逻辑读墙钟 —— "测试等一会儿"不是仿真的一部分。
     */
    private boolean waitUntilTicked(String humanId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var context = live.contextOf(humanId);
            if (context != null && context.body().hasTicked()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        var context = live.contextOf(humanId);
        return context != null && context.body().hasTicked();
    }

    /**
     * 一个不需要上下文的意图 —— 本测试测的是恢复, 不是领域知识。
     *
     * <h2>它为什么必须带 {@code @DomainType}</h2>
     * 因为 {@code PlanStore.appendRevision} 会把计划项的意图<b>多态地</b>写进 JSONB,
     * 而那条路要求实现类带 {@code @DomainType} 并<b>被注册过</b> —— 没有它,
     * 序列化会当场抛 {@code UnknownDomainTypeException}, 于是这条用例根本走不到恢复那一步。
     *
     * <p>这不是测试的脚手架要求, 是 P4 那条"平台源码里永远不出现第三方类型名"的正面形态:
     * <b>一个三方意图只要标了类型名并被注册, 就能被本层原样存取。</b>
     * 本仓的持久化测试用的是同一套做法(见 {@code PersistenceFixtures.FixtureIntent}),
     * 只是那份夹具是包级私有的, {@code bootstrap} 包用不了。
     *
     * <p><b>顺带暴露一个真实的缺口</b>: {@code DomainTypeAssembly} 里登记了十二类活动、
     * 十几种世界对象, 而 <b>一个 {@code PlanIntent} 实现都没有</b> ——
     * 连本仓唯一的那个实现 {@code IntentionPlanIntent} 也没标类型名。
     * 也就是说今天的生产代码<b>写不出任何一条计划项</b>, 而那正是
     * {@code RecoveryRuntime} 里那句"计划表还没有生产者"的另一半。
     */
    @DomainType(value = "test.e2e-intent", version = 1,
            description = "端到端恢复测试用的最小意图 —— 只为让计划项能被写进库里读回来")
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
