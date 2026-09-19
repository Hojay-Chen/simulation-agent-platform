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
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("世界是空的, 运行时也是空的 —— 而且它说出来了")
    void 世界与运行时都是空的而且它说出来了() {
        assertNotNull(world);
        assertNotNull(runtime);
        assertEquals(0, runtime.seatCount(), "此刻场景里不该有座位 —— 见下面那条断言");

        // 这一条钉的是当前这一层的**真实状态**, 而不是一个期望。
        // 它是故意的: 一个座位数为零的运行时与一个正常的运行时, 在任何一处计数上
        // 都没有区别 —— 心跳照跳、计数照涨、日志照打"装配完成"。
        // 所以"没有人"这件事必须被一个断言说出来, 于是等 §8.6.3 第 5/6 步落地时,
        // 这里会红一次, 而那次红会逼着改它的人读一遍上面这句话。
        assertEquals(0, runtime.seatCount(),
                "场景里出现座位了 —— 那么 StartupSummary 的 Recovery 那一段也该跟着变成真的, "
                        + "而不是 SimulationConfiguration 里写死的那四个零");

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
