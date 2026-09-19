package com.luxera.companion.bootstrap;

import com.luxera.companion.boundary.action.ActionFabric;
import com.luxera.companion.boundary.action.DefaultActionFabric;
import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.repository.ActionCommandRecordRepository;
import com.luxera.companion.persistence.repository.ActivityRecordRepository;
import com.luxera.companion.persistence.repository.AgentOwnershipRecordRepository;
import com.luxera.companion.persistence.repository.ContinuousEffectRecordRepository;
import com.luxera.companion.persistence.repository.ConversationAccountBindingRecordRepository;
import com.luxera.companion.persistence.repository.DeviceApplicationRecordRepository;
import com.luxera.companion.persistence.repository.HumanRecordRepository;
import com.luxera.companion.persistence.repository.PlanConstraintRecordRepository;
import com.luxera.companion.persistence.repository.PlanItemRecordRepository;
import com.luxera.companion.persistence.repository.PlanRevisionRecordRepository;
import com.luxera.companion.persistence.repository.WorldEventRecordRepository;
import com.luxera.companion.persistence.repository.WorldObjectRecordRepository;
import com.luxera.companion.persistence.store.ActionCommandStore;
import com.luxera.companion.persistence.store.ActivityStore;
import com.luxera.companion.persistence.store.EffectLedgerStore;
import com.luxera.companion.persistence.store.PlanConstraintCodec;
import com.luxera.companion.persistence.store.PlanItemCodec;
import com.luxera.companion.persistence.store.PlanMutationCodec;
import com.luxera.companion.persistence.store.PlanStore;
import com.luxera.companion.persistence.store.StimulusReplayStore;
import com.luxera.companion.persistence.store.WorldEventStore;
import com.luxera.companion.persistence.store.WorldObjectStore;
import com.luxera.companion.registry.CapabilityRegistry;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.runtime.AgentProfileProjector;
import com.luxera.companion.runtime.AgentRegistry;
import com.luxera.companion.runtime.EnvironmentRefreshJob;
import com.luxera.companion.runtime.LiveHumanRegistry;
import com.luxera.companion.runtime.LiveHumanSource;
import com.luxera.companion.runtime.RecoveryRuntime;
import com.luxera.companion.runtime.SimulationClock;
import com.luxera.companion.runtime.WorldRuntime;
import com.luxera.companion.world.World;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

/**
 * V2.2 §8.6.3 —— <b>把这一层装配起来, 并且只在被要求的时候装</b>。
 *
 * <h2>顺序不是风格问题</h2>
 * 下面每个 {@code @Bean} 方法上方都标了它对应 §8.6.3 的第几步, 以及"为什么必须在它前面那些之后"。
 * 这些依赖有一部分由 Spring 的构造器注入<b>自动</b>保证(要 {@code DomainPayloadCodec} 就得先有
 * {@code DomainTypeRegistry}), 而另一部分 Spring 看不见 —— 后者才是这个文件存在的理由:
 *
 * <table border="1">
 *   <tr><th>Spring 保证的</th><th>Spring 看不见的</th></tr>
 *   <tr>
 *     <td>{@code codec} 拿到的是<b>已经登记好</b>的注册表(构造器参数)</td>
 *     <td>注册表里<b>该有多少</b>个类型 —— 少登记一个, 注入照样成功,
 *         而那条数据写下去就成了 {@code _untyped} 替身</td>
 *   </tr>
 *   <tr>
 *     <td>tick 壳拿到的 {@code WorldRuntime} 已经建好</td>
 *     <td>壳<b>什么时候开始跑</b> —— 早于恢复就会对着一个正在移动的状态重建(第 7 步)</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么整层挂在一个开关上(§8.6.4)</h2>
 * 因为 {@code DigitalHumanTestApplication} 的 {@code @ComponentScan} 扫的是
 * {@code com.luxera.companion} 整棵树。一个无条件的 {@code @Configuration} 会自动进入
 * <b>全部 31 个 {@code @SpringBootTest} 上下文</b>, 并带来两个后果:
 * <ol>
 *   <li>每个上下文都要求"所有 repository bean 都在场"(本类的第 3 步要九个仓库),
 *       于是这个模块里任何一个碰巧不碰数据库的测试都会因为一个<b>它不需要的协作者</b>
 *       起不来而变红。这类失败最难读: 它跟被测对象毫无关系;</li>
 *   <li>两个测试应用都带 {@code @EnableScheduling}, 于是 tick 循环<b>会在测试里跑起来</b>。
 *       一个每秒推进仿真时间的后台线程, 会让"她在 12:00 应该做什么"这类断言
 *       从确定性变成<b>概率</b>。</li>
 * </ol>
 *
 * <p>所以 {@code companion.sim.enabled} <b>缺省为假</b>。规律是仓里已经有的那两条
 * ({@code app.v11.*.enabled} 与 {@code GhostChatSweeper}): <b>一个会自己动起来的组件,
 * 必须是一个显式打开的组件</b> —— 因为"它安静地不在跑"和"它安静地在跑但什么也没发生"
 * 在日志里长得一模一样。
 *
 * <h2>这个类<b>做到</b>哪一步了</h2>
 *
 * §8.6.3 的九步里, 第 1–8 步都落地了, 其中第 5/6/7 步合在
 * {@link #recoveryRuntime} 一个 bean 里(理由见那里)。于是
 * "<b>库里有几个 agent</b>"与"<b>这台机器上坐着几个她</b>"第一次可能不相等 ——
 * 而它们不相等的每一种原因都有一条自己的读面:
 *
 * <pre>
 *   库里没人                  → 座位 0。正常, 不打告警;
 *   库里有, 应当跑, 座位 0    → 装配缺口, rosterWarning();
 *   应当跑而被恢复拒绝入座     → 恢复的判定, recoveryWarning() 与 RecoveryRuntime.refusals();
 *   账本里有读不回来的行       → Recovery.hasOpaqueEntries()
 * </pre>
 *
 * <p>这四条是四个数, 不是一句"人少了"。前三条都让座位数变小, 而修它们要做的事
 * 完全不同(接线 / 补装载入口 / 补类型注册), 所以它们从一开始就必须分得开。
 *
 * <h2>这个类还<b>没有</b>做什么</h2>
 *
 * 第 9 步的环境刷新是接了但上游没通的(缺省 sink 返回空列表, 每一轮记一次<b>跳过</b>);
 * 而恢复那一侧有三处是空的, 各自的理由写在 {@link RecoveryRuntime} 的类注释那张表里
 * —— 其中 ② 与 ④ 是"读得回来却装不回去"与"两端都还没定下来", ③ 是"记忆根本没有
 * 持久化, 所以那个处境不可能出现"。三处都没有被伪装成正常数据。
 *
 * <p><b>没有生产者, 所以世界是空的</b>: {@code WorldEventStore.append}、
 * {@code PlanStore.appendRevision}、{@code ActivityStore.append} 三处全仓没有一个
 * 生产调用者(见 {@code RecoveryRuntime} 的注释)。这意味着今天启动之后
 * <b>她会坐在座位上, 而世界无事发生</b> —— 心跳照跳、账本恒空、计划表恒空。
 * 这不是装配的缺陷, 是下一批生产者的活; 而它必须被说出来,
 * 因为"她安静地坐着"与"她坏了"在面板上是同一个样子。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "companion.sim", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationConfiguration {

    // ───────────────────── 第 1 步: 类型注册表 ─────────────────────

    /**
     * <b>必须最先</b> —— 后面每一个 store 都要拿着它才有意义。
     *
     * <p>{@link DomainTypeAssembly#assemble()} 返回的是<b>已经登记好</b>的注册表,
     * 而不是一个空表。这个形状是刻意的: 一个"先拿到空表、以后再填"的合法姿态,
     * 会让人写出一个用空表造出来的 codec —— 而那种 codec 让所有写下去的载荷
     * 变成 {@code _untyped}, 且看起来完全正常(§8.6.3 第 2 步)。
     */
    @Bean
    public DomainTypeRegistry domainTypeRegistry() {
        DomainTypeRegistry registry = DomainTypeAssembly.assemble();
        log.info("[Sim] 类型注册表就绪: {} 个类型, {} 个命名空间", registry.size(), registry.namespaces().size());
        return registry;
    }

    // ───────────────────── 第 2 步: 编解码器 ─────────────────────

    /**
     * 只是第 1 步的包装, 顺序上无自由度 —— 但<b>必须在任何 store 之前</b>。
     *
     * <p>把它写成 Spring 的 {@code @Bean} 而不是让每个 store 自己 {@code new} 一个,
     * 理由不是"省一次 new": 而是让"这个 codec 是用哪张注册表造的"这件事<b>只有一个答案</b>。
     * 每个 store 各造一个的话, 它们会各自拿着一张注册表 —— 今天它们内容相同,
     * 而明天有人往其中一处加了一个类型, 数据就开始只在部分表里读得回来。
     */
    @Bean
    public DomainPayloadCodec domainPayloadCodec(DomainTypeRegistry registry) {
        return new DomainPayloadCodec(registry);
    }

    // ───────────────────── 第 3 步: 各 store ─────────────────────

    /**
     * 九个 store —— 它们是"这个世界的记忆中, 哪些部分要活过重启"的<b>完整清单</b>。
     *
     * <h2>为什么 store 是 bean, 而 World/Human 不是</h2>
     * store 是<b>无状态的服务</b>(它们的方法每一个都吃调用方传进来的仿真时刻, 见
     * {@code persistence/package-info} 的第一条纪律), 所以"一个进程一个实例"是对的。
     * 而 {@code World} 与 {@code Human} 是<b>有状态的领域对象</b> —— 它们各有一个
     * 单线程的执行体({@code WorldRuntime}), 被 Spring 当作单例到处注入正是它们不该有的用法。
     *
     * <h2>为什么这一批必须一起在场</h2>
     * 因为它们不是七个独立的选择, 而是同一份历史的七个侧面: 事件、账本、计划、
     * 执行记录、世界对象、命令、刺激回放。缺任何一个, 恢复出来的她就少一段记忆,
     * 而"少的那段"不会表现为故障 —— 它表现为"她好像不记得那件事", 一个无法与
     * "那件事本来就没发生过"区分的症状。所以宁可让上下文起不来。
     */
    @Bean
    public WorldEventStore worldEventStore(WorldEventRecordRepository repository, DomainPayloadCodec codec) {
        return new WorldEventStore(repository, codec);
    }

    /** 回放队列是从事件表重建出来的, 所以它依赖上面那个 store, 而不是自己再读一次库。 */
    @Bean
    public StimulusReplayStore stimulusReplayStore(WorldEventStore events) {
        return new StimulusReplayStore(events);
    }

    @Bean
    public EffectLedgerStore effectLedgerStore(ContinuousEffectRecordRepository repository,
                                               DomainPayloadCodec codec) {
        return new EffectLedgerStore(repository, codec);
    }

    @Bean
    public ActivityStore activityStore(ActivityRecordRepository repository, DomainPayloadCodec codec) {
        return new ActivityStore(repository, codec);
    }

    @Bean
    public WorldObjectStore worldObjectStore(WorldObjectRecordRepository objects,
                                             DeviceApplicationRecordRepository applications,
                                             DomainPayloadCodec codec) {
        return new WorldObjectStore(objects, applications, codec);
    }

    /**
     * 三个计划编解码器 —— 顺序由它们的构造器定, 不由这里的书写顺序定。
     *
     * <p>它们<b>不是</b>通用编解码器的复制品: 计划项里嵌着约束、重排里嵌着计划项,
     * 而嵌套的那两层各自也是多态的。所以它们是同一条链上的三个环,
     * 而不是三个可以各自替换的实现。
     */
    @Bean
    public PlanConstraintCodec planConstraintCodec(DomainPayloadCodec codec) {
        return new PlanConstraintCodec(codec);
    }

    @Bean
    public PlanItemCodec planItemCodec(DomainPayloadCodec codec, PlanConstraintCodec constraintCodec) {
        return new PlanItemCodec(codec, constraintCodec);
    }

    @Bean
    public PlanMutationCodec planMutationCodec(PlanItemCodec itemCodec) {
        return new PlanMutationCodec(itemCodec);
    }

    @Bean
    public PlanStore planStore(PlanRevisionRecordRepository revisions,
                               PlanItemRecordRepository items,
                               PlanConstraintRecordRepository constraints,
                               PlanItemCodec itemCodec,
                               PlanConstraintCodec constraintCodec,
                               PlanMutationCodec mutationCodec) {
        return new PlanStore(revisions, items, constraints, itemCodec, constraintCodec, mutationCodec);
    }

    /** 动作命令 —— "她想做一件事"与"这件事真的做了"之间的那条记录。 */
    @Bean
    public ActionCommandStore actionCommandStore(ActionCommandRecordRepository repository) {
        return new ActionCommandStore(repository);
    }

    // ───────────────────── 第 4 步: 唯一的时钟 ─────────────────────

    /**
     * 所有"现在几点"的唯一来源(§8.5.1)。
     *
     * <h2>为什么这里可以读一次墙上时钟, 而别处一次都不许</h2>
     * 因为仿真时钟总有<b>长出第一秒</b>的那一刻, 而那一刻必须由真实世界给它。
     * 这一行 {@code Instant.now()} 是整个新架构里唯一合法的墙钟读取 ——
     * 它是<b>种子</b>, 不是查询: 读一次, 之后所有时刻都从 {@link SimulationClock} 走。
     *
     * <p>{@code SimulationClock.realtime(...)} 而不是 {@code at(...)}: 生产要的是
     * "她的一天按真实速度过"。回放与压测才用 {@code at(...)} 或一个高倍速,
     * 而那是另一个 profile 的事, 不是这个 bean 的默认值。
     */
    @Bean
    public SimulationClock simulationClock() {
        SimulationClock clock = SimulationClock.realtime(Instant.now());
        log.info("[Sim] 仿真时钟从 {} 起步 (实时 1:1)", clock.startedAt());
        return clock;
    }

    // ─────────────── 第 5/6 步的来源: 花名册与她在这台机器上的位置 ───────────────

    /**
     * <b>活着的她在哪</b> —— {@link LiveHumanSource} 的那个实现, 第 5/6 步之前它一直是
     * {@link LiveHumanSource#NONE}。
     *
     * <h2>为什么它必须是可变的, 而不是在装配时就算好</h2>
     * 因为 {@code AgentProfileProjector} 要靠它回答"她在这台机器上吗", 而
     * {@code AgentRegistry} 要靠那个投影才造得出来, 而装人的人又要靠
     * {@code AgentRegistry} 才知道该装谁。三者构成一个环, 而把环拉直的唯一办法
     * 是让其中一环<b>先存在、后填充</b>: 登记处先造出来(空的), 装配过程往里写,
     * 投影从里读。写成 {@code LiveHumanSource} 的另一个不可变实现就必然成环。
     *
     * <p>它的时刻来自那个唯一的 {@link SimulationClock} —— 见
     * {@link LiveHumanRegistry} 关于"为什么不调 {@code Human.context()}"的说明。
     */
    @Bean
    public LiveHumanRegistry liveHumanRegistry(SimulationClock clock) {
        return new LiveHumanRegistry(clock);
    }

    /**
     * 把归属行、身份行、聊天账号绑定拼成一份档案 —— <b>不落库</b>(§3.6.7)。
     *
     * <p>它拿的 {@code live} 就是上面那个登记处, 于是档案上那句
     * {@code materialized()} 从"永远为假"变成了<b>跟着座位表走的事实</b>:
     * 装进来的人为真、没装进来的为假。这正是 §3.6.7 要的 ——
     * 一个"看起来活着、身体各项是 0"的档案比一个说"她不在这台机器上"的档案坏得多。
     *
     * <p>这里<b>不</b>用 {@code ObjectProvider} 去问容器要一个 {@code LiveHumanSource}:
     * 那会让读者以为外面已经有一个实现可以盖过它, 而活着的 {@code Human} 的引用
     * 本来就归装配层持有({@code HumanActor} 刻意不暴露它持有的那一个),
     * 所以外面<b>不可能</b>有第二个实现是合理的。{@code sinkOrDefault} 用
     * {@code ObjectProvider} 是因为环境数据源真有一个可能来自三方的实现(§6), 这里没有。
     */
    @Bean
    public AgentProfileProjector agentProfileProjector(ConversationAccountBindingRecordRepository bindings,
                                                       LiveHumanRegistry live) {
        return new AgentProfileProjector(bindings, live);
    }

    /**
     * 花名册 —— "平台上有哪些 agent、它们归谁、现在跑不跑"。
     *
     * <h2>为什么它是这个类里的一个 bean, 而它其实属于平台而不属于仿真</h2>
     * §8.5.7 说得很清楚: 本类的读者是<b>平台</b>(控制台、运维面、{@code /api/**}),
     * 不是她 —— 心跳只从它读一个 {@code AgentLifecycle}。所以它最终应当是一个
     * <b>无条件</b>的 bean, 好让控制台在 {@code companion.sim.enabled=false}
     * (今天就是缺省值)的配置下也能列人。
     *
     * <p>此刻它留在这个类里, 是因为它今天的唯一读者是同一行启动日志。把它挪出去
     * 会顺带决定"控制台在没有仿真时读到的是什么", 而那是一个还没做的决定 ——
     * 现在挪, 等于用一次重排假装那个决定已经做了。挪出去时只多一个
     * {@code @Configuration} 类, 本类这一行删掉即可。
     *
     * <p>它不是有状态的领域对象, 所以做单例是对的: 三个 {@code final} 的仓库引用,
     * 没有可变字段。这与上面那批 store 同一条理由, 与 {@code World}/{@code Human}
     * 相反。
     */
    @Bean
    public AgentRegistry agentRegistry(AgentOwnershipRecordRepository ownership,
                                       HumanRecordRepository humans,
                                       AgentProfileProjector projector) {
        return new AgentRegistry(ownership, humans, projector);
    }

    // ───────────────────── 第 5 步: 世界 ─────────────────────

    /**
     * 这个世界。它是数字世界与数字设备世界的容器 —— 但此刻它是<b>空的</b>。
     *
     * <p>里面的地点、设备、应用由各自的装载过程填(数字设备世界里"手机"与它的应用
     * 都是三方实现类, §2 的接口)。这里刻意不预置任何东西: 一个预置了"一台手机、
     * 一个家、一份初始天气"的装配层, 会让"没人往世界里放过东西"与"世界本来就是这样的"
     * 变得无法区分 —— 而后者迟早会变成一条写死的业务假设。
     */
    @Bean
    public World world() {
        return new World();
    }

    /**
     * 动作出口 —— 她的决定从这里走出去, 而<b>数字设备世界的能力就挂在这里</b>。
     *
     * <h2>为什么它全机一个, 而不是每个人一个</h2>
     * 因为能力表回答的是"这个世界里能被触发的东西有哪些"(§2 的接口、以及用户所要求的
     * "暴露成 skill/tool/MCP"), 而<b>世界只有一个</b>。人手一份能力表会让
     * "手机响了"这件事在不同的她那里对应到两个不同的对象上 —— 而它们本该是同一条线。
     *
     * <p>这也解释了它为什么排在第 5 步这一带: 它的内容(设备、应用)与
     * {@link #world()} 是同一批东西的两种视角 —— 世界那侧是"东西在哪",
     * 这一侧是"她能对它做什么"。
     *
     * <p>此刻它是空的({@code new CapabilityRegistry()} 里一个能力都没有), 与
     * {@link #world()} 是同一个状态, 同一条理由: 预置几个能力会让
     * "没人往世界里注册过东西"与"世界本来就只有这些"变得无法区分。
     */
    @Bean
    public ActionFabric actionFabric() {
        return new DefaultActionFabric(new CapabilityRegistry());
    }

    // ───────────────────── 第 9 步: 环境刷新(取在别处, 用在这里) ─────────────────────

    /**
     * 环境数据的来源 —— <b>可选的</b>, 缺省时用"返回空列表"的实现。
     *
     * <h2>缺省实现为什么是"返回空列表", 而不是"抛异常说没配"</h2>
     * 因为它<b>不是</b>一个故障, 它是一个尚未接通的上游。两者在日志里的区别由
     * {@code WorldRuntime.refreshSkips()} 回答: 空读数会被计成一次<b>跳过</b>,
     * 而不是一次失败 —— 于是"还没接天气源"与"接了但取数失败"从一开始就是两个数。
     *
     * <p>反过来, 让它抛异常会让 {@code companion.sim.enabled=true} 变成"起不来",
     * 而那会挡住整条链路 —— 包括那些与她今天冷不冷毫无关系的部分。
     *
     * <p><b>但"安静的缺省"必须说出口</b>: 用缺省时打一条 WARN。这是本仓对
     * "安静地不在跑"的一贯处理 —— 默认状态可以是沉默的, 但<b>选择</b>沉默这件事
     * 必须留下痕迹。接真实天气源的做法是提供自己的 {@code EnvironmentSink} bean
     * (§6 的 digital world 那一半: 拉取真实地区天气), 它会盖过这里。
     *
     * <h2>为什么用 {@code ObjectProvider} 而不是 {@code @ConditionalOnMissingBean}</h2>
     * 那个注解的语义是"到这一步为止还没有" —— 它在自动配置类里可靠, 因为那里的
     * bean 定义顺序是被规定的; 而在一个普通 {@code @Configuration} 里, 结果取决于
     * 两个 {@code @Bean} 谁先被注册。<b>一个取决于注册顺序的存在性判断, 会随一次
     * 无关的重排而改变行为</b>, 而它改变的是"她在不在用真实天气"。
     * {@code getIfAvailable} 问的是容器最终有没有, 与顺序无关。
     */
    private static EnvironmentRefreshJob.EnvironmentSink sinkOrDefault(
            org.springframework.beans.factory.ObjectProvider<EnvironmentRefreshJob.EnvironmentSink> provided) {
        EnvironmentRefreshJob.EnvironmentSink custom = provided.getIfAvailable();
        if (custom != null) {
            return custom;
        }
        log.warn("[Sim] 没有配置环境数据源(容器里没有 EnvironmentSink bean) —— 世界会停在初始天气上。"
                + "每一轮刷新都会被计入 refreshSkips, 而不是失败: 这两者在面板上是两个数, "
                + "所以这件事查得出来, 但此刻它确实没有在跑");
        return now -> List.of();
    }

    /**
     * 取数任务 —— 它<b>自己不读时钟</b>, 每一个仿真时刻都由调用方传进来。
     *
     * <p>间隔取 {@link SimulationProperties#getEnvironmentInterval()} 而不是默认的
     * 常量: 真实天气在十分钟里几乎不变, 而每一次刷新是一次外呼; 这个值同时也是
     * "刷新永远落在 12:00 / 12:10 / 12:20"的对齐间隔, 由它决定比由"上一次什么时候跑的"
     * 决定好 —— 后者会累积漂移(§8.5.0)。
     */
    @Bean
    public EnvironmentRefreshJob environmentRefreshJob(
            SimulationClock clock,
            SimulationProperties properties,
            org.springframework.beans.factory.ObjectProvider<EnvironmentRefreshJob.EnvironmentSink> sink) {
        return new EnvironmentRefreshJob(clock, sinkOrDefault(sink), properties.getEnvironmentInterval());
    }

    // ───────────────────── 第 6 步(的部分): 运行时 ─────────────────────

    /**
     * 唯一的心跳, 也是唯一允许往 {@code World} 里写的那条线的持有者。
     *
     * <h2>为什么它现在是空的, 而这正是对的</h2>
     * 座位由 {@code bind(actor, scheduler)} 一个一个登记, 而那需要一份"她是哪几个人"的
     * 来源 —— 见类注释最后一段。空座位表的运行时<b>不会</b>假装自己在跑:
     * 它的 {@code describe()} 会说出"0 个座位", {@code StartupSummary} 会把它打进启动那一行。
     *
     * <p>{@code destroyMethod} 写死成 {@code close}: Spring 对 {@code AutoCloseable}
     * 本来就会推断它, 但这里写出来是因为<b>关闭顺序</b>是有讲究的 ——
     * tick 壳({@link SimulationTick})是 {@code SmartLifecycle}, 它在 bean 销毁<b>之前</b>
     * 被停掉, 于是 {@code close()} 执行时已经没有人在往这条线程上塞活了。
     * 靠推断得到这个顺序是运气, 写出来才是约定。
     */
    @Bean(destroyMethod = "close")
    public WorldRuntime worldRuntime(World world, SimulationClock clock,
                                     EnvironmentRefreshJob environmentRefreshJob) {
        return new WorldRuntime(world, clock, environmentRefreshJob);
    }

    // ───────────────────── 第 7 步: 恢复 + 物化 + 入座 ─────────────────────

    /**
     * <b>第 5/6/7 步合起来的那一件事</b> —— 把花名册上的人装成活的她, 装回历史, 放进座位表。
     *
     * <h2>为什么这三步是一个 bean, 而不是三个</h2>
     * §8.6.3 把"第 7 步在第 8 步之前"称为<b>这一层唯一一个"错了会毁掉数据"的顺序</b>。
     * 而这三步之所以不能拆开落地, 原因在 {@code Life} 上: 它<b>自己</b>
     * {@code new PlanBoard()}, 而且今天<b>没有</b>装载一版计划的入口 —— 于是
     * "先装一个空白的她、再把历史补上"这条路走不通。恢复因此必须是
     * <b>装配的前置条件</b>, 不是装配之后的修补: 装出来的那个她, 从第一纳秒起
     * 就是"装不进历史时宁可拒绝"的那个她({@code RecoveryRuntime} 的拒绝规则)。
     *
     * <p>拆成三个 bean 的代价是具体的: 中间那一刻世界上存在一个
     * <b>装了人却还没装历史</b>的运行时, 而它看起来完全正常 —— 心跳会跳、计数会涨、
     * 日志会印"装配完成"。所以这里刻意只留一个入口, 让那个中间态<b>不可能被观察到</b>。
     *
     * <h2>为什么装配动作发生在 {@code @Bean} 方法体里</h2>
     * 因为"第 7 步在第 8 步之前"要成为一条 <b>DI 边</b>而不是一条注释。
     * {@link #simulationTick} 把本 bean 声明成构造依赖, 于是 Spring 必须先造出本 bean
     * —— 而造它的那一刻 {@code recover()} 就跑完了。谁把那条参数删掉,
     * {@code SimulationConfigurationTest} 会红。
     *
     * <p>这正是 §8.6.3 那张表里"Spring 看不见的"那两行之一:
     * "tick 壳拿到的 {@code WorldRuntime} 已经建好"是 Spring 保证的,
     * 而"壳什么时候开始跑"不是 —— 它现在由这条边保证。
     */
    @Bean
    public RecoveryRuntime recoveryRuntime(WorldRuntime runtime,
                                          AgentRegistry agents,
                                          EffectLedgerStore ledgers,
                                          PlanStore plans,
                                          ActivityStore activities,
                                          SimulationClock clock,
                                          ActionFabric actionFabric,
                                          LiveHumanRegistry live) {
        RecoveryRuntime recovery = new RecoveryRuntime(
                runtime, agents, ledgers, plans, activities, clock, actionFabric, live);
        recovery.recover();
        return recovery;
    }

    // ───────────────────── 第 8 步: tick 壳 ─────────────────────

    /**
     * 心跳壳。它<b>最后</b>被造出来, 也最后开始跑 —— 而"开始跑"这件事由
     * {@code SmartLifecycle} 的相位决定, 不由 bean 的创建顺序决定。
     *
     * <h2>那个 {@code recovery} 参数不是给构造器用的</h2>
     * {@link SimulationTick} 的构造器只收前两个参数, 而第三个参数存在<b>唯一</b>的理由是
     * 让"恢复必须在心跳之前跑完"成为一条 <b>Spring 能看见的依赖边</b>:
     *
     * <pre>
     *   有这条边: 造壳之前 Spring 必须先造 RecoveryRuntime, 而它的构造过程里
     *             recover() 已经跑完 —— 心跳第一次跳的时候, 世界上的人与历史都是静止的
     *   没有它:   两个 bean 谁先被造出来取决于注册顺序(§8.6.3 的表里
     *             "Spring 看不见的"那一行), 于是恢复可能读到一份正在被推进的状态
     * </pre>
     *
     * <p>{@code Objects.requireNonNull} 写在这里不是为了防御 —— 是为了让这个参数
     * <b>有实质作用</b>: 一条“传进来但谁也不读”的参数会被 IDE 标成未使用,
     * 而下一个"清理未使用参数"的人会顺手删掉它, 于是那条边静默消失。
     * 一句空的检查让删掉它变成一次<b>会编译过但会红测试</b>的改动。
     */
    @Bean
    public SimulationTick simulationTick(WorldRuntime runtime, SimulationProperties properties,
                                         RecoveryRuntime recovery) {
        java.util.Objects.requireNonNull(recovery,
                "心跳壳必须依赖恢复 —— 这条参数是 §8.6.3 '第 7 步在第 8 步之前'那条 DI 边, "
                        + "见本方法的注释");
        return new SimulationTick(runtime, properties);
    }

    // ───────────────────── 第 9 步: 启动那一行日志 ─────────────────────

    /**
     * §8.6.7 —— 让"这个世界里有多少种事件、有没有人、tick 多快"成为一行可读的话。
     *
     * <p>它属于装配层而不属于任何一个领域对象: 它是<b>装配结果</b>的陈述, 而不是领域事实。
     * 领域对象不知道"一共有多少类型", 它只知道自己那几种 —— 让 {@code World} 或
     * {@code Human} 去数一个全局的数, 就是让它们认识一个它们不该认识的全局。
     */
    @Bean
    public StartupSummary startupSummary(DomainTypeRegistry registry,
                                         World world,
                                         WorldRuntime runtime,
                                         AgentRegistry agents,
                                         RecoveryRuntime recoveryRuntime,
                                         SimulationProperties properties) {
        // 恢复那一段现在是**真的**: 账本条数、替身数、拒绝入座数都来自刚才那一轮恢复。
        //
        // 仍然有两个数是写死的零, 而它们各自缺的东西不一样(见 RecoveryRuntime 那张表):
        //   planItems   —— 必然与 refusedAgents 互补。有计划存量的 agent 会被拒绝入座,
        //                  所以"装进来的计划项"永远是 0, 直到 Life 有了装载入口;
        //   historyDays —— world_event 表还没有生产者, 而 World 没有 id,
        //                  所以"世界历史覆盖了几天"这个问题今天没有被问过。
        // 写成零而不是省略: 一个"少一段"的日志会让人以为那一段是最近才加的,
        // 而零会说"它现在确实是空的"。
        RecoveryRuntime.Report r = recoveryRuntime.report();
        StartupSummary.Recovery recovery = new StartupSummary.Recovery(
                r.ledgerEntries(), r.opaqueEntries(), 0, 0, r.refused());

        // 座位数与应当跑的人数并排报 —— 单看座位那个数分不出
        // "库里还没有人"与"有人而没被装进来", 而这两件事要做的事完全不同。
        StartupSummary.Roster roster = new StartupSummary.Roster(
                agents.total(), agents.runnable().size());

        StartupSummary summary = new StartupSummary(
                registry.size(),
                registry.namespaces().size(),
                registry.conflicts(),
                1,
                runtime.seatCount(),
                roster,
                recovery,
                properties.getTickMs());

        log.info(summary.describe());
        summary.conflictWarning().ifPresent(log::warn);
        summary.rosterWarning().ifPresent(log::warn);
        summary.recoveryWarning().ifPresent(log::warn);
        return summary;
    }
}
