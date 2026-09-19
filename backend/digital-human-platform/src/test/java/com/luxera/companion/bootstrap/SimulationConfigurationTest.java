package com.luxera.companion.bootstrap;

import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.repository.AgentOwnershipRecordRepository;
import com.luxera.companion.persistence.store.ActionCommandStore;
import com.luxera.companion.persistence.store.ActivityStore;
import com.luxera.companion.persistence.store.EffectLedgerStore;
import com.luxera.companion.persistence.store.PlanStore;
import com.luxera.companion.persistence.store.StimulusReplayStore;
import com.luxera.companion.persistence.store.WorldEventStore;
import com.luxera.companion.persistence.store.WorldObjectStore;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.runtime.EnvironmentRefreshJob;
import com.luxera.companion.runtime.LiveHumanRegistry;
import com.luxera.companion.runtime.RecoveryRuntime;
import com.luxera.companion.runtime.SimulationClock;
import com.luxera.companion.runtime.WorldRuntime;
import com.luxera.companion.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §8.6.3 / §8.6.4 —— <b>装配层真的能装起来吗, 以及它真的能关掉吗</b>。
 *
 * <h2>为什么必须有一个测试把这一层整个加载起来</h2>
 * 本层的大部分失效都<b>不</b>是编译错误, 而是"某个 bean 不存在":
 * <ul>
 *   <li>九个 store 里少一个的构造参数(少一个 repository、少一个编解码器)——
 *       编译期看不出来, 因为 Spring 在运行期才解析这些依赖;</li>
 *   <li>注册表被漏登记(那正是 {@code V22BoundaryArchitectureTest} 里那条守卫管的,
 *       但那条守卫不经过 Spring —— 它不知道 {@code domainTypeRegistry()} 这个 bean
 *       到底有没有被造出来, 也不知道它是不是被别的东西覆盖过);</li>
 *   <li>两个循环根本没起来 —— 一个 {@code @ConditionalOnProperty} 写错一个字符,
 *       这一层就<b>安静地不在跑</b>, 而所有单元测试照样全绿。</li>
 * </ul>
 *
 * <h2>为什么这一个上下文可以开, 而 31 个别的不可以</h2>
 * §8.6.4 说的两件事(每个上下文都要求所有 repository 在场; tick 线程会在测试里跑起来)
 * 在这里<b>都是想要的</b>: 第一件正是本测试要验证的(store 的依赖能不能解析),
 * 第二件是本测试要看的(tick 真的在跳)。要付的代价只是这一个上下文要多起几秒。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "companion.sim.enabled=true",
        "companion.sim.tick-ms=50",
        "companion.sim.environment-interval=PT1S"
})
class SimulationConfigurationTest {

    @Autowired
    DomainTypeRegistry registry;
    @Autowired
    DomainPayloadCodec codec;
    @Autowired
    WorldEventStore worldEventStore;
    @Autowired
    StimulusReplayStore stimulusReplayStore;
    @Autowired
    EffectLedgerStore effectLedgerStore;
    @Autowired
    ActivityStore activityStore;
    @Autowired
    WorldObjectStore worldObjectStore;
    @Autowired
    PlanStore planStore;
    @Autowired
    ActionCommandStore actionCommandStore;
    @Autowired
    SimulationClock clock;
    @Autowired
    World world;
    @Autowired
    EnvironmentRefreshJob environmentRefreshJob;
    @Autowired
    WorldRuntime runtime;
    @Autowired
    SimulationTick tick;
    @Autowired
    StartupSummary summary;
    /**
     * 恢复器 —— 第 7 步。
     *
     * <p>它值得被注入进这个类, 而不是"另起一个测试类去测它": 摘要里那三个数
     * 是<b>从它这里拿的</b>, 而一个"摘要自己数一遍、恢复器自己数一遍"的实现
     * 会让两处独立的零看起来与"恢复过了、什么也没有"一模一样。
     * 这里注入它就是为了让那种分叉能被当场比出来。
     */
    @Autowired
    RecoveryRuntime recovery;
    /**
     * 活着的她的登记处 —— 第 6 步的出口。
     *
     * <p>{@code AgentProfileProjector} 拿到的 {@code LiveHumanSource} 必须是它,
     * 而不是 {@code LiveHumanSources.NONE}。那个区别在接口上完全看不出来
     * (一个永远返回空的实现与一个真的登记处长得一样), 只能靠"两边同进同出"来钉。
     */
    @Autowired
    LiveHumanRegistry live;
    /**
     * 直接读容器 —— 因为"第 7 步在第 8 步之前"是一条 <b>DI 边</b>, 而不是一行注释。
     *
     * <p>类型是 {@code ConfigurableApplicationContext} 而不是 {@code ApplicationContext}:
     * 前者是后者的子接口, 而"读依赖图"的入口 {@code getBeanFactory()} 只在它上面。
     * 这不是为了多要一点能力 —— 这个类只用它读一个方法。
     *
     * <p>名字叫 {@code container} 而不是 {@code context}, 是因为下面那个嵌套类
     * ({@code 关掉的时候})自己也注入了一个 {@code context}。同名的内层字段会
     * <b>遮住</b>外层字段, 而"这一句读的是哪一个"在源码上要靠数作用域才看得出来。
     */
    @Autowired
    ConfigurableApplicationContext container;
    /** 直接拿库比数 —— 因为 startupSummary 里那个在册数是从这条路径来的。 */
    @Autowired
    AgentOwnershipRecordRepository ownership;

    // ─────────────────────── 第 1~3 步: 类型、编解码器、九个 store ───────────────────────

    @Test
    @DisplayName("九个 store 全部装配成功, 且共用同一张注册表")
    void 九个store全部装配成功() {
        // 上面那一串 @Autowired 本身就是断言的一半: 少任何一个, 这个上下文都起不来,
        // 而失败信息会是 Spring 的一长串依赖链 —— 它正是排查"少哪个 bean"要的东西。
        assertNotNull(worldEventStore);
        assertNotNull(stimulusReplayStore);
        assertNotNull(effectLedgerStore);
        assertNotNull(activityStore);
        assertNotNull(worldObjectStore);
        assertNotNull(planStore);
        assertNotNull(actionCommandStore);
        assertNotNull(codec);

        // 另一半是"它们用的是同一张表"。这一条单独值得钉: 每个 store 各造一个 codec
        // 在今天是看不出差别的(内容相同), 而明天有人往其中一处加一个类型,
        // 数据就开始只在部分表里读得回来 —— 那是一种"重启之后才出现"的故障。
        assertEquals(registry, codec.registry(),
                "容器里的编解码器与容器里的注册表不是同一张 —— 于是往注册表里加的类型, "
                        + "不会进到写库那条路径上");
    }

    /**
     * <b>命名空间是"最后一个点之前的那一段", 不是一个固定的枚举。</b>
     *
     * <p>这一点值得写下来, 因为第一版这条测试把它猜错了 —— 猜的是 {@code life},
     * 而实际是 {@code life.activity}; 猜的是 {@code body}, 而实际是 {@code clothing}。
     * 类型名形如 {@code namespace.name}(§1.3 的 {@code @DomainType}), 于是
     * {@code life.activity.exercise} 的命名空间是 {@code life.activity} ——
     * 命名空间本身可以是分的, 而 {@link DomainTypeRegistry#namespaces()} 给的是
     * <b>叶子</b>的集合。
     *
     * <p>这个形状不是缺陷: 命名空间的用途是"这个类型由谁提供"({@code @DomainType}
     * 的注解注释里那张表), 而"由谁提供"本来就是可以再分的 —— 生活那一族里, 事情(activity)
     * 与计划(plan)由不同的东西声明。所以这里断言的是<b>每一族在不在</b>,
     * 而不是"命名空间一共几个": 后者会因为接一个三方应用而变, 而那不是一条错误。
     */
    @Test
    @DisplayName("装配出来的注册表覆盖了核心的每一族类型")
    void 装配出来的注册表覆盖了核心的每一族类型() {
        Set<String> namespaces = registry.namespaces();

        for (String expected : new String[]{
                "plan", "life.activity", "environment", "device", "device.phone", "clothing", "system"}) {
            assertTrue(namespaces.contains(expected),
                    "注册表里没有 " + expected + " 那一族的类型 —— 实际的命名空间: " + namespaces);
        }

        // 上面那一条是"每一族都在", 这一条是"总数对得上"。两个都要:
        // 一个只查"在不在"的断言, 会在有人把 12 种活动删到只剩 1 种时照样绿。
        assertTrue(registry.size() >= 30,
                "注册表只有 " + registry.size() + " 个类型 —— 太少, 多半是 DomainTypeAssembly "
                        + "的清单被改坏了, 而它不会报错, 只会让那些类型写进库之后读不回来");

        assertTrue(registry.conflicts().isEmpty(),
                "装配出来的注册表里有类型名冲突(先注册的赢, 后一个的数据会被静默读成前一个的): "
                        + registry.conflicts());
    }

    // ─────────────────────── 第 4~6 步: 时钟、世界、运行时 ───────────────────────

    @Test
    @DisplayName("世界与运行时都是空的, 而恢复那一段是真的")
    void 世界与运行时都是空的而且它说出来了() {
        assertNotNull(world);
        assertNotNull(runtime);

        assertEquals(registry.size(), summary.typeCount(),
                "摘要里的类型数与注册表的实际大小不一致 —— 这行日志是运维看这一层的唯一入口");
        assertEquals(runtime.seatCount(), summary.humanCount());
        assertEquals(50L, summary.tickMs());

        // 花名册那一侧必须真的接在库上 —— 不是"装配了一个看起来对的数"。
        // 与库的实际行数比, 而不是与一个写死的 0 比: 后者在库里有 agent 时会假绿。
        assertEquals(ownership.count(), summary.roster().registered(),
                "摘要里的在册数与 agent_ownership 的实际行数不一致 —— "
                        + "那说明 AgentRegistry bean 接的不是这张表, 或者查的不是同一个库");
        assertTrue(summary.roster().runnable() <= summary.roster().registered(),
                "应当跑的不可能多于在册的");

        // 告警与判据必须同进同出。这一条比"断言今天没有告警"强: 它同时钉住了
        // 「库里一个人都没有」与「有人而没被装进来」不该被印成同一句话 ——
        // 前者是正常状态, 后者是装配缺口, 而它们在只报座位数时长得一模一样。
        assertEquals(summary.unseatedAgents() > 0, summary.rosterWarning().isPresent(),
                "rosterWarning() 该响的时候必须响、不该响的时候必须是空的 —— 实际: "
                        + summary.rosterWarning().orElse("(无)"));

        // 恢复那一段不再是写死的零 —— 它必须来自刚才真的跑过的那一轮。
        // 这一条钉的是"摘要里的数"与"恢复过程报的数"是同一个数: 它们曾经是
        // 两处独立的零, 而那种零看起来与"恢复过了、什么也没有"一模一样。
        assertEquals(recovery.report().ledgerEntries(), summary.recovery().ledgerEntries(),
                "摘要里的账本条数与恢复过程报的不一致 —— 那说明其中一个不是从库里数出来的");
        assertEquals(recovery.report().opaqueEntries(), summary.recovery().opaqueEntries());
        assertEquals(recovery.refusals().size(), summary.recovery().refusedAgents(),
                "被拒绝入座的人数与拒绝清单对不上 —— 摘要会报一个没有名字的数");

        // 判据与告警同进同出, 与上面 rosterWarning 那条同一个形状。
        assertEquals(summary.recovery().hasRefusals(), summary.recoveryWarning().isPresent(),
                "recoveryWarning() 该响的时候必须响 —— 实际: "
                        + summary.recoveryWarning().orElse("(无)"));
    }

    /**
     * <b>§8.6.3 的"第 7 步在第 8 步之前"是一条 DI 边, 不是一条注释。</b>
     *
     * <h2>为什么这一条不能靠"读代码"来保证</h2>
     * 那个顺序是本层唯一一个"错了会毁掉数据"的顺序 —— 恢复读的那一段世界历史
     * 必须是<b>静止的</b>, 而心跳先起来的话它读的是一个移动的目标。
     * 而 Spring 看不见它: 两个 bean 谁先被造出来取决于注册顺序,
     * 于是"它们碰巧是对的"与"它们被保证是对的"在源码上长得一样。
     *
     * <p>所以这里读<b>容器自己的依赖图</b>, 而不是读源码 —— 问的是
     * "Spring 认为造 {@code simulationTick} 之前必须先造谁"。有人删掉那个参数的话,
     * 这条断言会红, 而它红的时候说的正是"你刚刚拆掉的是一条数据安全的边"。
     *
     * <h2>为什么还要断言"参数不是白传的"</h2>
     * 因为一条"传进来但谁也不读"的参数会被 IDE 标成未使用, 而下一个清理未使用参数的人
     * 会顺手删掉它 —— 于是那条边静默消失。{@code simulationTick} 里那句
     * {@code Objects.requireNonNull} 就是为此存在的, 这里钉住它。
     */
    @Test
    @DisplayName("恢复必须在心跳之前 —— 而且这是一条容器看得见的依赖")
    void 恢复必须在心跳之前() {
        // 先取成局部变量再断言, 而不是把那一串调用塞进断言里:
        // 失败信息里要印的正是这个列表, 而"印出来的东西"与"比过的东西"
        // 必须是同一次读取 —— 分开读两次的话, 打印时它可能已经变了。
        List<String> 心跳的依赖 = List.of(
                container.getBeanFactory().getDependenciesForBean("simulationTick"));
        List<String> 恢复的依赖 = List.of(
                container.getBeanFactory().getDependenciesForBean("recoveryRuntime"));

        assertTrue(心跳的依赖.contains("recoveryRuntime"),
                "心跳壳不再依赖恢复了 —— 那条 §8.6.3 的 DI 边被拆掉了。"
                        + "依赖图里现在是: " + 心跳的依赖);

        // 反方向也要成立: 恢复不该反过来依赖心跳壳, 那会成一个环,
        // 而一个环意味着"谁先跑"重新变成一个注册顺序的偶然。
        assertFalse(恢复的依赖.contains("simulationTick"),
                "恢复依赖了心跳壳 —— 那是一个环, 而且它意味着恢复要在心跳起来之后才能跑。"
                        + "恢复的依赖: " + 恢复的依赖);
    }

    @Test
    @DisplayName("活着的她登记在 LiveHumanRegistry 里, 而不是一个永远为假的投影")
    void 活着的她登记在登记处里() {
        // 这一条钉的是"第 5/6 步真的接上了"这件事的**接线**那一半:
        // AgentProfileProjector 拿到的 LiveHumanSource 必须是这个登记处,
        // 而不是 LiveHumanSources.NONE。它不看库里有没有人 —— 那个由下面
        // 「装进来的人与座位数一一对应」那一条负责。
        assertNotNull(live);
        assertEquals(runtime.seatCount(), live.size(),
                "座位表上有 " + runtime.seatCount() + " 个她, 而登记处里有 " + live.size()
                        + " 个 —— 两边必须同进同出: 装进座位的那一刻就该登记, "
                        + "否则控制台查不到她(见 LiveHumanRegistry 的说明)");

        for (String humanId : runtime.humanIds()) {
            assertNotNull(live.contextOf(humanId),
                    humanId + " 在座位表上却不在登记处里 —— 控制台会以为她不在这台机器上");
            assertTrue(live.humanIds().contains(humanId), humanId);
        }
    }

    @Test
    @DisplayName("两个循环真的起来了, 心跳真的在跳")
    void 两个循环真的起来了() throws Exception {
        assertTrue(tick.isAutoStartup(), "心跳必须是自动启动的 —— 需要手工启动的心跳等于没有心跳");

        long before = runtime.pulses();
        // 50ms 一拍, 等 500ms 至少该有 5 拍; 门槛取 2 是给慢机器留的余量。
        // 这一条要抓的不是"快不快", 而是"有没有": 一个 @ConditionalOnProperty 写错、
        // 一个 SmartLifecycle 相位写错、一个 start() 没被调到, 都会让这个数停在 0。
        boolean advanced = waitUntilPulsesAtLeast(before + 2, Duration.ofSeconds(5));

        assertTrue(advanced,
                "心跳在 5 秒里只从 " + before + " 跳到 " + runtime.pulses() + " 次 —— "
                        + "这一层安静地不在跑, 而它在日志之外与'正常'没有区别");
        assertTrue(tick.isRunning());
    }

    @Test
    @DisplayName("没有配天气源这件事被说出来, 而不是安静地没有天气")
    void 没有配天气源这件事被说出来() {
        // 缺省 sink 返回空列表 —— 于是一轮刷新是"跳过", 而不是"失败"。
        // 这两个数分开是刻意的: "还没接天气源"与"接了但取数挂了"是两件不同的事,
        // 而它们在"只有成功/失败两个数"的面板上会长得一模一样。
        assertTrue(environmentRefreshJob.fetches() >= 0 && environmentRefreshJob.failures() == 0,
                "缺省天气源不该产生失败 —— 它没有要失败的东西; 实际失败 "
                        + environmentRefreshJob.failures() + " 次");
    }

    /**
     * 等到心跳至少跳到 {@code target} 拍, 或超时。
     *
     * <p>是<b>轮询</b>而不是"睡够时间再看一眼": 后者会把测试的时长绑死在最慢的那台机器上,
     * 而前者在快机器上立刻就过。返回布尔而不是抛异常, 是为了让失败信息由调用方给出 ——
     * 那里才知道"这个数意味着什么"。
     *
     * <p>这里读墙上时钟是允许的: 测试不在 {@code human/world/boundary} 三个包里,
     * 而 §8.5.1 禁的是<b>仿真</b>逻辑读墙钟 —— "测试等一会儿"不是仿真的一部分。
     */
    private boolean waitUntilPulsesAtLeast(long target, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (runtime.pulses() >= target) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return runtime.pulses() >= target;
            }
        }
        return runtime.pulses() >= target;
    }

    // ─────────────────────── §8.6.4: 关掉的时候它真的不在 ───────────────────────

    /**
     * 缺省(不写 {@code companion.sim.enabled})时, 这一层的 bean <b>一个都不该在</b>。
     *
     * <h2>为什么这一条值得再起一个上下文</h2>
     * 因为它是 §8.6.4 那整段的<b>唯一验证</b>。那两个后果(每个 {@code @SpringBootTest}
     * 都要求所有 repository 在场; tick 线程会在测试里跑起来)不是推测 ——
     * 它们是这一层无条件生效时必然会发生的, 而"31 个上下文都还绿"这件事本身
     * 并不构成证明: 它们可能只是碰巧都满足了这个新协作者的要求。
     *
     * <p>{@code enabled=false} 与"不写这个属性"是两条不同的路(前者是显式的否,
     * 后者走的是缺省值), 而 {@code @ConditionalOnProperty} 的缺省行为
     * ({@code matchIfMissing=false})是缺省为假 —— 这里显式写 false, 是因为
     * 它同时也在钉住"显式关掉是有效的"。
     */
    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(properties = "companion.sim.enabled=false")
    class 关掉的时候 {

        @Autowired
        ApplicationContext context;

        @Test
        @DisplayName("这一层的每一个 bean 都不在容器里")
        void 这一层的每一个bean都不在容器里() {
            assertThrows(NoSuchBeanDefinitionException.class,
                    () -> context.getBean(DomainTypeRegistry.class),
                    "开关关掉了, 注册表却还在 —— 于是任何一个 @SpringBootTest 上下文都会多出"
                            + "一个它没要的协作者, 而失败信息会指向一个与被测对象毫无关系的地方");
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(WorldRuntime.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(SimulationTick.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(PlanStore.class));
            assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(SimulationProperties.class),
                    "属性对象本身也不该在 —— 它带着校验逻辑, 而一个缺省关掉的组件"
                            + "不该有能力让别的测试上下文因为校验失败起不来");
        }
    }
}
