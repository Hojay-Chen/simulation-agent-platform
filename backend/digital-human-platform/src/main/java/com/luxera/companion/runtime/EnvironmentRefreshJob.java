package com.luxera.companion.runtime;

import com.luxera.companion.world.World;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * V2.2 §8.5.0 —— <b>环境刷新那条独立节奏的所有者</b>。
 *
 * <h2>它为什么必须独立于心跳: 一个具体的时间线</h2>
 * 它不是"第二个每秒跑的 tick", 而是一件<b>会外呼</b>的事。如果它与设备演化待在同一个
 * 方法里(或者同一个调度器的同一个线程上):
 *
 * <pre>{@code
 *   12:00:00  天气 API 开始超时（它最坏可以挂 30 秒）
 *   12:00:01  计划 tick 排在这个调用后面, 等着
 *   ...
 *   12:00:30  API 超时返回, 这 30 秒里她的计划表一次都没被推进
 * }</pre>
 *
 * 症状是"<b>她的一天莫名其妙停了半分钟</b>", 而日志里只有一条 WARN 说天气没取到 ——
 * 没有人会把它和"她 12:00 该做的事没做"联系起来。这就是本类存在的全部理由,
 * 也是它与 {@link PlanSchedulerJob} 唯一重要的区别: <b>那个 job 只碰内存, 这个 job 碰网络。</b>
 *
 * <h2>它做的唯一一件事: 取</h2>
 * <pre>{@code
 *   pulse(now)  →  floorTo(now, 间隔)  →  与上一次真的取过的边界比  →  (跨过边界才) 取一批读数
 * }</pre>
 *
 * <p>不跨边界时它只是一次内存里的比较(纳秒级); 跨边界时才真的调一次
 * {@link EnvironmentSink#fetchEnvironments(Instant)}(那是几十毫秒到几十秒)。
 * 于是它可以被高频地"问"(比如每秒一次), 而真的外呼永远只落在
 * {@code :00 / :10 / :20} 这些边界上。
 *
 * <h2>它<b>只取不用</b> —— 这是 §8.5.9 定的, 也是本类最容易被写错的一条</h2>
 * 这一拍结束时, 世界<b>一个字都没有变</b>: 取回来的读数被装进返回值, 由调用方
 * ({@code WorldRuntime})提交到仿真线程上, 由那一侧的
 * {@code World.applyEnvironments(readings, now)} 落到世界里(纯内存, 几微秒)。
 *
 * <pre>{@code
 *   刷新线程(本类)      ① fetchEnvironments(now)  → List<EnvironmentReading>   外呼, 只读
 *   仿真线程(WorldRuntime) ② applyEnvironments(readings, now) → EnvironmentReport 改环境 + 投事件
 * }</pre>
 *
 * <p>为什么不能合成一跳(哪怕那样本类就能同步返回一份漂亮的 {@code EnvironmentReport}):
 * 因为那三件事没有交集 —— 外呼必须离开心跳线程(否则回到开头那张时间线),
 * 改世界必须留在唯一那条仿真线程上({@code World} 刻意不加锁, 见它的类注释),
 * 而"一次心跳只读一次时刻"(§8.5.1)要求那个时刻由心跳读、往下传。三条约束唯一的
 * 交点就是这条"取 / 用"分工。
 *
 * <p><b>代价, 说清楚</b>: 本类因此报不出"改了几个环境、投了几条事件" ——
 * 那些数在仿真线程返回的那份 {@code EnvironmentReport} 里。本类能保证的只有
 * "我取回来了什么"(见 {@link #fetchedReadings()}), 而它<b>不</b>假装知道后面那一步
 * 有没有发生。运维口径上"取到了"与"用上了"是两个数: 前者是本类的
 * {@link #describe()}, 后者是 {@code World.describe()} 与那份报告 ——
 * 只剩一个数的时候, 一次"取了但没交付"的故障会看起来一切正常。
 *
 * <h2>为什么用 {@code clock.floorTo(...)} 而不是"距上次刷新够久了"</h2>
 * 这条是 {@link SimulationClock} 类注释里"时间边界的对齐"那一节说的:
 *
 * <pre>{@code
 *   // ❌ 从"上次刷新"起算: 会漂移
 *   if (Duration.between(lastRefresh, now).compareTo(TEN_MINUTES) >= 0) { refresh(); }
 *
 *   // ✅ 对齐到时间边界: 永远落在 :00 / :10 / :20
 *   if (clock.floorTo(TEN_MINUTES).isAfter(lastRefresh)) { refresh(); }
 * }</pre>
 *
 * <p>第一种写法每次执行都有几毫秒误差, 而误差会累积 —— 跑三天之后"每 10 分钟"变成
 * "每 10 分钟零 4 秒", 她日记里的天气变化时刻与真实天气数据再也对不上。
 * 本类<b>不自己攒任何 {@code lastRun + interval} 的游标</b>, 它只记"上一次是哪个边界",
 * 于是也没有一个会被写错的游标。{@code World.refreshDue} 里那份漂移写法仍然存在,
 * 但它只剩一个消费者({@code World.advance} 那条离线回放路径) —— 生产路径的节奏
 * 从这里出。
 *
 * <h2>一个必须说清的前提: 边界是相对仿真起点算的</h2>
 * {@link SimulationClock#floorTo(Instant, Duration)} 的对齐基准是
 * {@link SimulationClock#startedAt()}, 不是 UTC 的整十分。所以"刷新永远落在 :00/:10/:20"
 * 这句话成立的<b>前提</b>是仿真起点本身落在整十分上。起点若在 12:03, 边界就是
 * 12:03 / 12:13 / …(仍然是每 10 分钟一次、仍然不漂移, 只是不在整十分上)。
 * 想要"整十分", 就让装配时的起点对齐 —— 本类不替调用方圆这件事, 因为悄悄把
 * 起点挪到边界上会让"仿真从 12:03 开始"这句话变成假的。
 *
 * <h2>三种失败, 三种处置</h2>
 * <table border="1">
 *   <tr><th>失败</th><th>处置</th><th>为什么</th></tr>
 *   <tr>
 *     <td>取数抛异常(气象 API 挂了、解析炸了)</td>
 *     <td><b>吞掉</b>, 记 ERROR 并计数, <b>下一个边界照常</b></td>
 *     <td>一次外部故障不该让她的一天停摆 —— 她那一侧有的是旧快照, 而旧快照的天气
 *         至少是"曾经真实过"的。这正是 {@link PlanSchedulerJob} 的失败表里那一行,
 *         只是这里的"一个坏处理器"换成了"一条挂掉的数据链"。
 *         <b>同时这个边界被记为"试过了"</b>(见下一条), 否则失败会被放大成
 *         每个 pulse 重试一次</td>
 *   </tr>
 *   <tr>
 *     <td>上一次还没跑完, 这一次又来了</td>
 *     <td><b>跳过</b>这次, 记 WARN 并计数, <b>并把当前边界记为"试过了"</b></td>
 *     <td>不排队: 在跑的那一次正在拿的天气数据与这一轮的<b>一样新鲜</b>(它就在此刻
 *         问上游), 而排队会让积压的调用在它返回时集中释放 —— 同一个边界上跑起两份
 *         取数, 两份读数一起被交付。自 §8.5.9 起"两个写者"那种更坏的结果没有了
 *         (取数只读, 写只有仿真线程一条), 但重复交付仍然会让她在同一条时间轴上
 *         看到两次一模一样的"气温从 3℃ 变成 12℃"。
 *         把边界一并记掉是为了不出现"每秒一条 WARN 刷满日志" —— 那正是这条
 *         失败处置要避免的噪音。详见 {@link #pulse(Instant)}</td>
 *   </tr>
 *   <tr>
 *     <td>时钟被要求倒流</td>
 *     <td><b>不吞, 直接抛出去</b></td>
 *     <td>与 {@link PlanSchedulerJob} 同一条: 这是编程错误, 不是运行期故障。
 *         更具体地说, 本类算边界用的是 {@code floorTo}, 它<b>本来就不会</b>因为
 *         时刻倒退而算错(取整是幂等的) —— 倒流会先被 {@code SimulationClock}
 *         挡在 {@code advanceTo} 那里, 那是它该在的地方</td>
 *   </tr>
 * </table>
 *
 * <h2>它<b>不</b>做的</h2>
 * <ul>
 *   <li><b>不读墙钟。</b>时刻只有一个来源, 而且由参数传进来 —— 见
 *       {@link #pulse(Instant)} 关于"为什么不提供无参版本"的说明;</li>
 *   <li><b>不认识 Human, 也不认识谁在哪。</b>它只知道"该取了", 把这一跳交给
 *       {@link EnvironmentSink}。哪些地点有人(配额过滤器)与投给谁是 {@code World}
 *       的事(它才有座位表);</li>
 *   <li><b>不认识 {@code WorldRuntime}, 也不投递读数。</b>它把读数<b>还回去</b>
 *       (见 {@link #pulse(Instant)} 的返回值), 交付这件事由调用方决定 ——
 *       本类拿不到那条仿真线程, 也不该拿(拿到就会有人顺手在那里 apply 一下,
 *       于是外呼又回到了驱动 pulse 的那条线程上);</li>
 *   <li><b>不含任何 Spring 注解</b> —— 照 {@link PlanSchedulerJob} 的做法,
 *       于是它能在测试里被一步步推着走。生产接线在配置层做, 见下一节。</li>
 * </ul>
 *
 * <h2>装配(生产接线), 以及"必须配在自己的调度器上"是什么意思</h2>
 * 那个包装方法本身很薄:
 * <pre>{@code
 * @Scheduled(fixedDelayString = "${companion.sim.env-poll-ms:1000}")
 * public void pollEnvironment() {
 *     WorldRuntime runtime = ...;
 *     runtime.submit(() -> runtime.world().applyEnvironments(
 *             environmentRefreshJob.pulse(runtime.clock().now()).readings(),
 *             runtime.lastInstant()));   // 与心跳那一次读的是同一个时刻
 * }
 * }</pre>
 *
 * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}: 同 {@link PlanSchedulerJob},
 * 前者是"上一次跑完再等这么久", 于是同一个线程上永远不会有两次执行重叠。
 *
 * <p><b>但这一条只有在它拿到自己那个调度器时才是真的。</b>Spring 默认的
 * {@code @Scheduled} 调度器<b>只有一个线程</b>, 而它已经给了每秒一次的心跳 ——
 * 两个任务共享那一个线程的后果, 就是本类开头那张时间线: 一次 30 秒的天气超时
 * 把她的计划 tick 一起拖住(而且更隐蔽: 拖住的是<b>别的</b>类, 所以排查的人
 * 会先去怀疑时钟)。所以接线时要么把它注册到一个专属的单线程
 * {@code ThreadPoolTaskScheduler} 上(例如通过一个只注册这一个任务的
 * {@code ScheduledTaskRegistrar} 配 {@code setScheduler}, 或用
 * {@code ScheduledExecutorService} 直接 {@code scheduleWithFixedDelay}),
 * 要么就让心跳只做"边界比较"、把真的外呼投给另一个线程。
 *
 * <p><b>与之配套的一条约束在 {@code World} 那一侧。</b>"两套节奏"不等于"两条线程各改一次
 * 那个世界": 本类这一条线程<b>只读</b>(它读的两张表在 {@code World} 里是 copy-on-write 的,
 * 那正是为了这条路径), 而改世界的那一下由 {@code WorldRuntime} 提交到唯一那条仿真线程上。
 * 也就是说: 本类与心跳可以真的并行跑, 但"提交读数"这一步必须串行 ——
 * 本类的重入保护只保护<b>它自己</b>不会同时发起两次取数, 它保护不了别的写者。
 *
 * <p><b>§8.5.1 那张顺序图怎么读。</b>图里的第 ③ 步现在是"<b>应用</b>"
 * ({@code world.applyEnvironments(readings, now)}, 纯内存 —— 它的代价是几微秒,
 * 待在心跳里没有关系), 而本类那次外呼是图外的一件事, 由它自己的调度器驱动。
 * 逻辑顺序仍然是"设备 → 环境", 线程上则是"取在别处、用在心跳"。
 *
 * <h2>两个常数, 两个所有者</h2>
 * {@link #DEFAULT_REFRESH_INTERVAL} 与
 * {@link World#DEFAULT_ENVIRONMENT_REFRESH_INTERVAL} 现在恰好都是 10 分钟,
 * 但它们是<b>两件事</b>, 所以刻意不合并:
 * <ul>
 *   <li>{@code World} 那个是<b>World 的门</b> —— 它决定"这个环境距上一次尝试够久了没有",
 *       只在 {@code World.advance} 那条旧路径上生效;</li>
 *   <li>本类这个是<b>job 的节奏</b> —— 它决定"现在落在哪个边界上、这个边界取过没有"。</li>
 * </ul>
 * 合并成一个常量的代价是具体的: 有人把 World 的门调成 1 分钟(想让进门时的天气更新鲜)
 * 会顺手把 job 的外呼频率也调成 1 分钟, 而气象数据的更新频率本来就是十分钟级 ——
 * 每分钟抓一次只会得到同一份数据十次, 外加十倍的失败重试噪音。
 */
@Slf4j
public class EnvironmentRefreshJob {

    /**
     * 默认刷新节奏。十分钟 —— 与用户的要求"比如每分钟或每 10 分钟更新一次"里的后者一致,
     * 也与 {@link World#DEFAULT_ENVIRONMENT_REFRESH_INTERVAL} 一致(它们恰好相等,
     * 但所有者不同, 见类注释最后一节)。
     */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(10);

    /**
     * 环境刷新这一跳的出口 —— <b>本类与 {@code World} 之间唯一的一条线</b>。
     *
     * <h2>为什么是函数式接口, 而不是直接收一个 {@code World}</h2>
     * 收 {@code World} 会让这个 job 拿到的能力远多于它需要的: 它能读
     * {@code devices()}、能 {@code placeHumanAt(...)}、能 {@code advance(...)} ——
     * 而一个"什么都能做"的依赖, 迟早会有人在这里顺手做点别的(比如"刷新前先看看
     * 有没有人在那个地点", 或者更糟: "取回来顺手 apply 一下")。自 §8.5.9 起这一点
     * 是<b>硬约束</b>而不是品味: 本类跑在刷新线程上, 而它有资格做的只有
     * {@code World} 里那一个只读方法。收一个只有一个方法的接口, 这件事在<b>类型上</b>
     * 就写清楚了 —— 那个接口的名字里连 "apply" 都不该出现。
     *
     * <p>另一个理由是测试: 一个 lambda 就能把它推着走, 不需要装配一个完整的
     * {@code World}(装配它要设备、地点、环境、{@code EventFabric}、还有一个
     * 假的 {@code EnvironmentProvider})。而这个 job 的失败路径(超时、异常、
     * 重入)恰恰是<b>最需要被测</b>的那几条 —— 让它们难以测, 等于让它们不被测。
     *
     * <h2>为什么返回类型是 {@code List<World.EnvironmentReading>}</h2>
     * 因为那是 {@code World.fetchEnvironments} 已经发布的契约(§8.5.9), 而且它
     * <b>原样就是</b>下一步的输入: 调用方把同一个列表交给
     * {@code applyEnvironments} 就行, 中间不需要任何转录 —— 而转录会漏字段、
     * 会漏掉将来新增的字段。
     *
     * <p>它<b>不</b>返回 {@code World.EnvironmentReport}: 那个类型的每一个字段都
     * 说的是"世界被改成了什么样"(刷了几个、投了几条、失败几个), 而这一跳结束时
     * 世界还没有被改。一个报告了一个还没发生的事实的返回类型, 迟早会有人照着它去报警。
     *
     * <p>空列表是<b>正常结果</b>: "每个地点都没人"是完全正常的十分钟(见
     * {@code World.fetchEnvironments} 的配额过滤器)。
     */
    @FunctionalInterface
    public interface EnvironmentSink {

        /** 取一轮环境数据(只读, 外呼) —— 见 {@code World.fetchEnvironments(Instant)}。 */
        List<World.EnvironmentReading> fetchEnvironments(Instant now);
    }

    private final SimulationClock clock;
    private final EnvironmentSink sink;
    private final Duration interval;

    /**
     * 重入保护 —— 见类注释的失败表。
     *
     * <p><b>它在这里的概率与 {@link PlanSchedulerJob} 完全不同, 值得说清楚。</b>
     * 那个 job 的周期是 1 秒、单次是纳秒级, 重入意味着"有什么东西真的卡住了";
     * 本 job 的周期是 10 分钟、单次可能挂 30 秒 —— 单看这两个数, 重入<b>不该</b>发生
     * (30 秒 < 10 分钟, 差了 20 倍)。真正会让它发生的不是"慢", 而是<b>驱动方式</b>:
     * 若 {@code pulse} 被心跳(每秒)和它自己的调度器<b>同时</b>驱动, 那么一个边界上
     * 会有两个线程同时进到这里。那时它是唯一的一道闸。
     * 换句话说: 在"只有自己的调度器、且是 fixedDelay"的接线里它是死代码,
     * 而在并存的接线里它是必需的 —— 一个只在某种接线方式下才起作用的守卫,
     * 比一个自以为不需要它的假设要便宜得多。
     *
     * <p>§8.5.9 之后它要防的东西变轻了(两次并发取数都是只读的, 不会再同时改世界),
     * 但"同一个边界交付两批一模一样的读数"仍然是错的 —— 见 {@link #pulse(Instant)}
     * 里跳过那一段的说明。所以这道闸留着。
     */
    private final AtomicBoolean fetching = new AtomicBoolean();

    /**
     * 上一次<b>试过</b>的边界 —— 节奏的真相在这里。
     *
     * <p>记"试过"而不是"成功": 失败与"因重入而跳过"都占用边界。理由与
     * {@code World.lastEnvironmentAttemptAt} 上那段话是同一句 ——
     * 不记的后果是"一条挂掉的数据链在每个 pulse 上重试一次", 于是一次外部故障
     * 被放大成几百次外呼, 而日志会被同一条 ERROR 刷满。
     *
     * <p>用 {@code accumulateAndGet} 取最大值而不是 {@code set}: 它必须<b>单调</b>。
     * 一个晚到的、属于较早边界的写入(比如上面那个在飞的调用终于返回了)
     * 绝不能把游标往回拉 —— 那会让这个边界被再取一遍。
     */
    private final AtomicReference<Instant> lastAttemptedBoundary = new AtomicReference<>();

    /**
     * 上一次<b>真的取回读数</b>的边界 —— 运维口径的"上次天气取数在几点"。
     *
     * <p>名字刻意用 {@code fetched} 而不是 {@code refreshed}: 本类能保证的只有
     * "我在这个边界上取回了一批读数", 而"那批读数被用上了没有"发生在另一条线程上,
     * 由 {@code World} 的那份 {@code EnvironmentReport} 回答。一个叫
     * {@code lastRefreshedBoundary} 的字段会在"取了但交付失败"的时候说谎 ——
     * 而那种故障的表现恰恰是"面板上看一切都好"。
     */
    private final AtomicReference<Instant> lastFetchedBoundary = new AtomicReference<>();

    /** 真的调过 sink 多少次(每一轮跨过的边界算一次)。 */
    private final AtomicLong fetches = new AtomicLong();

    /** 累计取回多少份读数 —— 它等于"交给下一步多少份", 是个交接量而不是结果量。 */
    private final AtomicLong fetchedReadings = new AtomicLong();

    /**
     * 累计取回<b>但失败</b>的读数份数(提供方查不到、超时返回的失败快照)。
     *
     * <p>为什么要在这里数一遍, 而 {@code World} 那一侧也会数: 因为两份读数之间隔着一个
     * 交付步骤。本类这一份数的是"上游这条数据链坏了几次"(它在外呼的那一刻就知道);
     * {@code World} 那一份数的是"有几份失败的读数真的被应用到环境上了"。
     * 交付失败(读数被丢掉)时, 本类这个数仍然是对的 —— 而它正是排查那种故障的入口。
     */
    private final AtomicLong failedReadings = new AtomicLong();

    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile Instant lastAttemptAt;
    private volatile Instant lastFetchedAt;
    private volatile String lastError;

    public EnvironmentRefreshJob(SimulationClock clock, EnvironmentSink sink) {
        this(clock, sink, DEFAULT_REFRESH_INTERVAL);
    }

    public EnvironmentRefreshJob(SimulationClock clock, EnvironmentSink sink, Duration interval) {
        this.clock = Objects.requireNonNull(clock, "环境刷新任务必须有一个时间来源 —— 它自己不读墙钟");
        this.sink = Objects.requireNonNull(sink, "环境刷新任务必须知道去哪儿取数");
        Objects.requireNonNull(interval, "刷新间隔不能为空");
        if (interval.isNegative() || interval.isZero()) {
            // 零间隔的后果与本类的存在理由正好相反: 每个 pulse 都落在"新边界"上,
            // 于是它变成每秒一次的外呼 —— 而气象数据的更新频率本来就是十分钟级,
            // 每分钟抓一次只会得到同一份数据十次, 外加十倍的失败重试噪音。
            // 负间隔更糟: 边界运算会反向。(SimulationClock.floorTo 也会拒绝它们,
            // 但那是第一次 pulse 时 —— 构造器抛能让人在装配阶段就看见)
            throw new IllegalArgumentException(
                    "刷新间隔必须为正, 收到 " + interval
                            + " —— 零间隔会让它每次 pulse 都外呼一次烧掉真实配额, "
                            + "而它不会让天气更新得更快; 负间隔会让边界运算反向");
        }
        this.interval = interval;
    }

    // ─────────────────────────── 推动 ───────────────────────────

    /**
     * 问一次"现在该取了吗", 该取就取, 然后把取回来的读数<b>还给调用方</b>。
     *
     * <p>这是本类唯一的入口, <b>而且刻意没有无参的那个版本</b>({@link PlanSchedulerJob}
     * 有 {@code tick()} 与 {@code tickAt(Instant)} 两个)。理由: §8.5.1 要求
     * "一次心跳只读一次时刻", 而这一跳的时刻是<b>调用方已经读过的那一个</b> ——
     * 它要用同一个 {@code now} 去推进设备、去算边界、去记账。
     * 如果这里再 {@code clock.now()} 一次, 那两次读取会跨越秒边界,
     * 于是"设备在 T 秒、环境在 T+1 秒", 而 {@code ContinuousEffectLedger} 的结算
     * 是依赖时刻的 —— 它会算出一个她从未有过的值, 且印在一行看起来很正常的日志里。
     * 入口少一个, 这个错误就没有地方可以发生。
     *
     * <h2>取回来之后去哪儿(这一节是 §8.5.9 的落点)</h2>
     * 读数在返回值里, <b>本类不管交付</b>。调用方({@code WorldRuntime})拿到它之后,
     * 把它提交给自己那条仿真线程, 在那里调
     * {@code World.applyEnvironments(readings, now)} —— 用<b>同一个</b> {@code now}。
     * 本类不替调用方做这件事, 有两个具体理由:
     * <ul>
     *   <li>它拿不到那条线程, 而"没有线程就 apply"等于让刷新线程去写世界 ——
     *       那正是 §8.5.9 要拆掉的东西;</li>
     *   <li>交付策略是运行时的(fire-and-forget? 带背压? 排到下一拍?),
     *       而本类只该负责节奏。</li>
     * </ul>
     * 于是有一条<b>调用方要负的责</b>: 若返回值被丢掉, 这一批读数就永远没了 ——
     * 而边界已经被记为"取过", 所以到下一个边界之前不会有第二次机会。
     * 症状是"她的天气每 10 分钟才更新一次, 而且那次也没更新" —— 一句听起来像悖论的话,
     * 靠 {@link #fetchedReadings()} 与 {@code World.describe()} 这两个数一起看才能定位。
     *
     * <h2>顺序: 先比边界, 再抢锁</h2>
     * 边界比较在 CAS <b>之前</b>。同一边界内的第二次及以后的调用在比较那一步就返回了
     * (一次内存读, 不产生 WARN 也不计数), 于是"每秒问一次"不会把 {@link #skipped}
     * 刷成一个看起来像故障的数 —— 那个数只记<b>真的</b>撞上重入的次数。
     *
     * @param now 这一轮的仿真时刻(调用方读的那一次)
     * @return 这一次 pulse 到底做了什么。{@code FETCHED} 时
     *         {@link PulseResult#readings()} 是这一批读数(可能为空 —— 每个地点都没人)
     */
    public PulseResult pulse(Instant now) {
        Objects.requireNonNull(now, "环境刷新必须带仿真时刻 —— 本类自己不读墙钟");
        Instant boundary = clock.floorTo(now, interval);
        Instant attempted = lastAttemptedBoundary.get();
        if (attempted != null && !boundary.isAfter(attempted)) {
            // 常态路径: 这个边界已经处理过了(取过、试过、或者因为上一次在跑而跳过)。
            // 不记日志 —— 它每秒都会走到这里一次
            return PulseResult.idle(now, boundary);
        }

        if (!fetching.compareAndSet(false, true)) {
            long n = skipped.incrementAndGet();
            consumeBoundary(boundary);
            log.warn("[EnvironmentRefreshJob] {} 的上一次取数还没跑完, 跳过边界 {}（累计 {} 次）"
                            + "—— 不排队: 在跑的那一次正在拿的数据与这一轮的一样新鲜, "
                            + "而排队会让积压的调用在它返回时集中释放(同一个边界上交付两批一样的读数, "
                            + "她会在时间轴上看到两次一模一样的变化)",
                    now, boundary, n);
            return PulseResult.skipped(now, boundary, n);
        }

        try {
            List<World.EnvironmentReading> readings = sink.fetchEnvironments(now);
            if (readings == null) {
                // 一个写错的 sink(返回 null)若放过去, 会被下面那个 catch 当成"取数失败" ——
                // 而它其实是我们自己的接线错误。它应该是可见的, 但也不该让这一拍炸掉
                log.warn("[EnvironmentRefreshJob] 边界 {} 的 sink 返回了 null, 按空批次处理, "
                        + "请检查装配", boundary);
                readings = List.of();
            }
            long failed = readings.stream().filter(reading -> !reading.ok()).count();

            fetches.incrementAndGet();
            fetchedReadings.addAndGet(readings.size());
            failedReadings.addAndGet(failed);
            consumeBoundary(boundary);
            lastFetchedBoundary.accumulateAndGet(boundary, EnvironmentRefreshJob::later);
            lastAttemptAt = now;
            lastFetchedAt = now;
            lastError = null;
            log.debug("[EnvironmentRefreshJob] 边界 {} 取数完成: {} 份读数({} 份失败) —— 待交付",
                    boundary, readings.size(), failed);
            return PulseResult.fetched(now, boundary, readings);

        } catch (RuntimeException e) {
            // 见类注释的失败表: 吞噬 + 计数 + ERROR, 下一个边界照常。
            // 注意 World 那一侧通常不会抛(它把失败变成读数里的失败快照), 所以
            // 这里接住的是"比一次环境取数更外面的事" —— 而它也照样不该让她的一天停摆
            long n = failures.incrementAndGet();
            consumeBoundary(boundary);
            lastAttemptAt = now;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[EnvironmentRefreshJob] 边界 {} 的环境取数失败（累计 {} 次）"
                            + "—— 沿用旧快照, 下一个边界照常",
                    boundary, n, e);
            return PulseResult.failed(now, boundary, lastError);

        } finally {
            fetching.set(false);
        }
    }

    /**
     * 把这个边界记为"试过了" —— 成功、失败、因重入而跳过, 三条路径都调它。
     *
     * <p>它只往<b>后</b>走(取最大值), 见 {@link #lastAttemptedBoundary} 的说明。
     */
    private void consumeBoundary(Instant boundary) {
        lastAttemptedBoundary.accumulateAndGet(boundary, EnvironmentRefreshJob::later);
    }

    /** 取两者中较晚的一个(空视为"还没有"), 供上面两处单调游标用。 */
    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    public Duration interval() {
        return interval;
    }

    /**
     * 下一次<b>真的取数</b>在何时 —— 运维面板上"下一次天气取数在几点"那个数。
     *
     * <p>定义: 取 {@code floorTo(now)}; 若这个边界还没被处理过, 那"下一次"就是<b>它</b>
     * (也就是说: 取数就在此刻, 只要有人 pulse 一下); 否则是再往后一个边界。
     *
     * <p>为什么不是简单地 {@code clock.nextBoundary(now, interval)}(严格晚于 now):
     * 在边界<b>上</b>问它时, 那个式子会说"下一次在 10 分钟后", 而实际上此刻 pulse
     * 就会真的去取 —— 一个会让翻面板的人等十分钟的显示值。用它做告警阈值之类的东西时,
     * 差这一格就是"以为还有十分钟, 其实已经该取了"。
     *
     * <p>它算的是<b>节奏</b>, 不是承诺: 若这条数据链一直在失败, 显示的仍然是下一个边界
     * (下一个边界<b>会</b>再试一次) —— 那正是想要的, 失败不进这个式子。
     */
    public Instant nextBoundaryAt(Instant now) {
        Objects.requireNonNull(now, "要问'下一次刷新在何时'必须带时刻");
        Instant boundary = clock.floorTo(now, interval);
        Instant attempted = lastAttemptedBoundary.get();
        if (attempted == null || boundary.isAfter(attempted)) {
            return boundary;
        }
        return boundary.plus(interval);
    }

    /** 真的调过 sink 多少次(每一轮跨过的边界算一次)。 */
    public long fetches() {
        return fetches.get();
    }

    public long skipped() {
        return skipped.get();
    }

    /** sink 抛异常的次数。 */
    public long failures() {
        return failures.get();
    }

    /** 累计取回多少份读数 —— <b>交接量</b>: 它是"交给下一步多少份", 不是"用上了多少份"。 */
    public long fetchedReadings() {
        return fetchedReadings.get();
    }

    /** 累计取回但失败的读数份数 —— 大于 0 说明上游那条数据链需要看一眼。 */
    public long failedReadings() {
        return failedReadings.get();
    }

    /** 上一次真的取回读数的边界 —— 空表示一次都还没取到过。 */
    public Instant lastFetchedBoundary() {
        return lastFetchedBoundary.get();
    }

    /** 上一次试过的边界(含失败与跳过) —— 它决定下一次什么时候取。 */
    public Instant lastAttemptedBoundary() {
        return lastAttemptedBoundary.get();
    }

    public Instant lastFetchedAt() {
        return lastFetchedAt;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    /** 最近一次失败的消息 —— 正常为 {@code null}。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 一行摘要 —— <b>只讲"取"这一侧的数</b>。
     *
     * <p>它刻意不含"改了几个环境、投了几条事件": 那些数在 {@code World} 的报告里,
     * 而把它们抄一份到这里会让两个数在出现分歧时都能自称是真的。
     * 想知道"取回来之后发生了吗", 把这一行与 {@code World.describe()} 摆在一起看。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("[EnvironmentRefreshJob] ");
        sb.append("节奏 ").append(interval.toMinutes()).append(" 分钟");
        sb.append(", 取数 ").append(fetches.get()).append(" 轮");
        sb.append(", 读数 ").append(fetchedReadings.get()).append(" 份");
        if (failedReadings.get() > 0) {
            sb.append("(其中失败 ").append(failedReadings.get()).append(" 份)");
        }
        if (skipped.get() > 0) {
            sb.append(", 跳过 ").append(skipped.get()).append(" 次");
        }
        if (failures.get() > 0) {
            sb.append(", 失败 ").append(failures.get()).append(" 次 ← 需要处理");
        }
        sb.append(", 上次成功取数边界 ")
                .append(lastFetchedBoundary.get() == null ? "尚未成功过" : lastFetchedBoundary.get());
        sb.append(", 上次尝试 ").append(lastAttemptAt == null ? "尚未跑过" : lastAttemptAt);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ─────────────────────────── 一次 pulse 的摘要 ───────────────────────────

    /**
     * 一次 {@link #pulse(Instant)} 到底做了什么。
     *
     * <p>返回值存在的理由与 {@link PlanSchedulerJob.TickResult} 是同一条: 调用方
     * (监控、测试、面板)需要区分"取了"、"还没到边界"、"撞上重入跳过了"、"失败了"
     * 这四件完全不同的事 —— 它们的计数在同一个 {@code fetches} 上长得一模一样。
     *
     * <p><b>它也是这一跳的交付物。</b>§8.5.9 之后 {@code pulse} 不能同步返回一份
     * 最终的 {@code EnvironmentReport}(那份报告要等读数被应用之后才有), 于是返回值
     * 里带的是<b>要交付的东西</b>: {@link #readings()}。这不是"少了个字段",
     * 而是这一跳的语义变了 —— 它从"刷完了"变成了"取回来了一批, 交给谁"。
     *
     * @param at           这一次 pulse 的仿真时刻
     * @param boundary     这一次算出的时间边界
     * @param outcome      处置结果
     * @param skippedTotal 累计因重入而跳过了多少次(诊断抖动用; 只有 {@code SKIPPED} 时有意义)
     * @param readings     这一批读数, <b>绝不是 null</b>(只在 {@code FETCHED} 时非空,
     *                     而且"有人的地点一个都没有"时是空列表)
     * @param error        失败消息; 只有 {@code FAILED} 时非空
     */
    public record PulseResult(Instant at,
                              Instant boundary,
                              Outcome outcome,
                              long skippedTotal,
                              List<World.EnvironmentReading> readings,
                              String error) {

        /** 一次 pulse 的四种去处 —— 取值集合由本类的逻辑决定, 不是外部世界的多样性。 */
        public enum Outcome {
            /** 真的取了一批读数回来(可能一份都没有 —— 每个地点都没人)。 */
            FETCHED,
            /** 还没跨过边界, 什么都没做 —— 绝大多数 pulse 都是这个。 */
            IDLE,
            /** 上一次还没跑完, 这一次跳过。 */
            SKIPPED,
            /** 取数抛异常, 已吞掉并计数。 */
            FAILED
        }

        public PulseResult {
            Objects.requireNonNull(at, "一次 pulse 的摘要必须带时刻");
            Objects.requireNonNull(boundary, "一次 pulse 的摘要必须带边界");
            Objects.requireNonNull(outcome, "一次 pulse 的摘要必须说明去了哪儿");
            readings = readings == null ? List.of() : List.copyOf(readings);
        }

        static PulseResult fetched(Instant at, Instant boundary,
                                   List<World.EnvironmentReading> readings) {
            return new PulseResult(at, boundary, Outcome.FETCHED, 0L, readings, null);
        }

        static PulseResult idle(Instant at, Instant boundary) {
            return new PulseResult(at, boundary, Outcome.IDLE, 0L, null, null);
        }

        static PulseResult skipped(Instant at, Instant boundary, long skippedTotal) {
            return new PulseResult(at, boundary, Outcome.SKIPPED, skippedTotal, null, null);
        }

        static PulseResult failed(Instant at, Instant boundary, String error) {
            return new PulseResult(at, boundary, Outcome.FAILED, 0L, null, error);
        }

        /** 真的取了一批回来吗。 */
        public boolean fetched() {
            return outcome == Outcome.FETCHED;
        }

        /** 被跳过了吗(重入)。 */
        public boolean wasSkipped() {
            return outcome == Outcome.SKIPPED;
        }

        /** 失败了吗。 */
        public boolean failed() {
            return outcome == Outcome.FAILED;
        }

        /** 这一批里有没有真的要交付的东西 —— 空批次不必往仿真线程上提交一次。 */
        public boolean hasReadings() {
            return !readings.isEmpty();
        }

        /** 这一批里有几份是失败的读数(提供方没给数据的那几份)。 */
        public int failedReadings() {
            int failed = 0;
            for (World.EnvironmentReading reading : readings) {
                if (!reading.ok()) {
                    failed++;
                }
            }
            return failed;
        }

        /** 这一次 pulse 有没有需要报警的地方。 */
        public boolean troubled() {
            return outcome == Outcome.SKIPPED || outcome == Outcome.FAILED;
        }

        public String describe() {
            switch (outcome) {
                case IDLE:
                    return "pulse@" + at + " 未到边界(" + boundary + ")";
                case SKIPPED:
                    return "pulse@" + at + " 边界 " + boundary + " 跳过（累计 " + skippedTotal + " 次）";
                case FAILED:
                    return "pulse@" + at + " 边界 " + boundary + " 失败: " + error;
                default:
                    return "pulse@" + at + " 边界 " + boundary + " 已取数: "
                            + readings.size() + " 份读数(" + failedReadings() + " 份失败) —— 待交付";
            }
        }
    }
}
