package com.luxera.companion.bootstrap;

import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.persistence.store.PlanStore;
import com.luxera.companion.runtime.LiveHumanRegistry;
import com.luxera.companion.runtime.RecoveryRuntime;
import com.luxera.companion.runtime.WorldRuntime;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>端到端: 库里出现一个 agent 之后, 她真的在世界里活着。</b>
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
 * </pre>
 *
 * <p>四个都不抛异常。所以这里不用"断言某个方法返回了真"来证明它们,
 * 而是走完整条链: <b>往库里插一行 → 跑恢复 → 等真实的 tick →
 * 读她的身体快照, 看时钟是不是真的走到了她身上</b>。
 *
 * <h2>这一条能证明什么, 不能证明什么</h2>
 * 它能证明的是: <b>她真的被这台机器的心跳驱动着</b> —— 一个没被 bind 进座位表的她,
 * 在这里等多久 {@code lastTickAt} 都是空的, 因为没有任何东西会去 accept 一条时钟事件。
 * 这是"库里的一行变成了活着的她"这句话里唯一无法伪造的那一半。
 *
 * <p>它<b>不能</b>证明第三行({@code registerOn}) —— 这一点值得写下来, 因为本类的
 * 第一版正是这么以为的: 当时读的是 {@code hasTicked()}, 而那个值在漏掉
 * {@code registerOn} 的装配下<b>照样为真</b>。原因是 {@code Human.acceptClockTicked}
 * 在 {@code fabric.tick} 之后<b>自己</b>调了一次 {@code body.advance}, 于是
 * "她的状态有没有前进"与"她在不在 tick 链上"是两件无关的事。
 * 那个缺口今天由 {@code Human} 的构造器当场拦住({@code requireBodyOnTheBus}),
 * 而钉住它的是 {@code HumanAssemblyTest} 里那条读 {@code TickReport.tickHandlers()} 的断言 ——
 * 只有"链上有几个人"能区分这两种装配。
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

    /** 每个用例自己的前缀 —— 清理按前缀删, 于是不会碰到别人的行。 */
    private static final String SEATED = "hum_e2e_seated";
    private static final String REFUSED = "hum_e2e_refused";

    @Autowired
    WorldRuntime runtime;
    @Autowired
    RecoveryRuntime recovery;
    @Autowired
    LiveHumanRegistry live;
    @Autowired
    PlanStore plans;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        // 先删计划, 再删归属 —— 顺序与依赖方向一致, 让人读得出谁属于谁。
        jdbc.update("delete from plan_revision where human_id = ?", REFUSED);
        jdbc.update("delete from agent_ownership where human_id in (?, ?)", SEATED, REFUSED);
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

    @Test
    @DisplayName("库里出现一个该跑的 agent 之后, 她真的被装进座位表并被 tick 推进")
    void 库里的agent会被装进来并真的开始跳() {
        assertFalse(runtime.humanIds().contains(SEATED),
                "用例开始前她就已经在座位上了 —— 上一个用例没有清理干净");

        seedOwnership(SEATED, "active");

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.seated(), "该跑的有一个人, 而入座 " + report.seated() + " 个 —— "
                + "完整的报告: " + report.describe());
        assertTrue(runtime.humanIds().contains(SEATED),
                "她没有进座位表 —— 花名册读到了人, 而装配那一步没有走通");
        assertNotNull(live.contextOf(SEATED),
                "她在座位表上却不在登记处里 —— 控制台会以为她不在这台机器上");

        // 最后一步, 也是唯一无法伪造的一步: 等真实的 tick 把时钟走到她身上。
        // 一个没被 bind 进座位表的她, 在这里等一整天 lastTickAt 都是空的 ——
        // 因为没有任何东西会去 accept 一条时钟事件。
        assertTrue(waitUntilTicked(SEATED, Duration.ofSeconds(10)),
                "她在座位上坐了 10 秒而时钟一次都没有走到她身上 —— "
                        + "心跳在跳, 而她不在心跳里。这一条几乎总是 WorldRuntime.bind 那一环没接上"
                        + "(注意它**不是** body.registerOn 的证据: 漏掉那一行的她照样会 tick, "
                        + "见本类类注释最后一段)");
    }

    /**
     * <b>读得回来却装不回去的历史, 换来一个拒绝而不是一张空表。</b>
     *
     * <p>这一条钉的是 {@code RecoveryRuntime} 那个有主张的决定。它今天不会
     * 在生产里响(计划表还没有生产者), 所以它只能在这里被钉住 ——
     * 而它必须被钉住: 那条规则的全部价值在于<b>下一个把计划生产者接上的人</b>
     * 会在第一次启动时看到它, 而不是在几天后从"她怎么什么都不做"倒推回来。
     */
    @Test
    @DisplayName("有计划存量却装不回去的 agent 被拒绝入座, 而不是装成一张空表")
    void 装不回去的历史换来一个拒绝() {
        seedOwnership(REFUSED, "active");
        // 用真的写入口去造这一版计划, 而不是手写 SQL —— 那样造出来的行
        // 一定是良构的, 于是这一条测的是**拒绝规则**, 不是我的 SQL 对不对。
        plans.appendRevision(REFUSED,
                PlanRevision.initial(Instant.parse("2026-03-01T08:00:00Z"), List.of(),
                        "端到端测试: 造一版装不回去的计划"));

        RecoveryRuntime.Report report = recovery.recover();

        assertEquals(1, report.refused(), "库里那一版计划读得回来却没有装载入口, "
                + "她必须被拒绝 —— 完整的报告: " + report.describe());
        assertFalse(runtime.humanIds().contains(REFUSED),
                "她被拒绝了却仍然进了座位表 —— 那么库里那一版计划与内存里的空表"
                        + "会同时存在, 而'她今天打算做什么'有两个答案");
        assertNull(live.contextOf(REFUSED),
                "她被拒绝了, 却仍然在登记处里 —— 控制台会看到一个不在心跳里的她");

        RecoveryRuntime.Refusal refusal = recovery.refusals().stream()
                .filter(r -> r.humanId().equals(REFUSED))
                .findFirst()
                .orElseThrow(() -> new AssertionError("拒绝清单里没有 " + REFUSED));

        // 理由必须**可操作**: "她被拒绝了"这句话本身不指向任何一处改动,
        // 而读它的人需要的正是"补哪个入口"。
        assertTrue(refusal.reason().contains("计划"), refusal.reason());
        assertTrue(refusal.reason().contains("Life"), refusal.reason());

        // 摘要与拒绝清单的接线由 SimulationConfigurationTest 钉(那里 summary 与
        // recovery 是同一个上下文里的两个 bean)。这里刻意**不**比一次:
        // summary 是**启动那一刻**的快照, 而这次拒绝发生在它之后 ——
        // 拿它们相比会得到一个恒假的断言, 或者一个我自己跟自己比的恒真断言。
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
}
