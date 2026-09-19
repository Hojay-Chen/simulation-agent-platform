package com.luxera.companion.world.environment;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.registry.CoreEventCatalog;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.world.digital.Location;
import com.luxera.companion.world.object.ObjectId;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §4.4.1 —— <b>环境</b>: 某个地点的气温、湿度、风、光照、天气。它属于 <b>DigitalWorld</b>。
 *
 * <h2>它不可被 agent 修改 —— 这一条在代码里怎么落地</h2>
 * 用户的原话:
 * <blockquote>
 *   还有一个 world 是 digital world, 这个是 agent 所处环境, 是<b>无法被其修改触发的</b>,
 *   比如 environment 就属于 digital world。
 * </blockquote>
 * 落地点是三条, 缺一条都会让它重新变成"一个能被改的对象":
 * <ol>
 *   <li>本接口<b>没有</b> {@code setTemperature} 之类的方法 —— 只有 {@link #fetch(Instant)}
 *       这一个"拿数据"的入口(以及把拿回的东西落到现状上的 {@link #apply(EnvironmentRefresh)}),
 *       而数据源是 {@link EnvironmentProvider}(外部气象服务);</li>
 *   <li>本接口<b>不</b>贡献任何 {@code Capability} —— 于是
 *       {@code ActionFabric} 里没有任何一条路由能指到它。她不能"让天变晴",
 *       只能感知天气然后决定自己做什么(带伞、不出门);</li>
 *   <li>它是 {@code WorldObject} 世界里的东西但<b>不是</b> {@code Device} ——
 *       与 {@code Place} 同理, 形状本身就说明了它不在"可执行"的那一半。</li>
 * </ol>
 * 第 2 条是最关键的一条: 一个"有但永远返回 REJECTED 的 {@code environment.set-weather}
 * 能力"看起来更友好, 但它会让工具清单里出现一个她永远做不到的东西, 而
 * "她试了一次, 发现做不到"与"她根本没有这个念头"是两种完全不同的人格表现。
 *
 * <h2>它的输出为什么<b>不</b>是"她很冷"</h2>
 * 本接口产出的是事实({@link EnvironmentSnapshot} 与那些变化事件), 而不是影响。
 * 3℃ 变成一条 {@code body.warmth} 通道上的负向影响之后, 仍然要由 Body 的稳态模型
 * 结合<b>她穿了什么</b>才能算出"她冷不冷"。世界不知道她的衣柜 —— 也不该知道。
 *
 * <h2>刷新节奏: 每个 tick <b>不</b>算一条事件</h2>
 * 用户要求"定期执行比如每分钟或每 10 分钟更新一次形成一个 event"。所以:
 * <pre>
 *   fetch(now)                                     ← 只读, 外呼, 一个字段都不改
 *      ├── snapshot = provider.query(location, now) ← 拿不到就返回一条 failed
 *      ├── delta = snapshot.deltaFrom(现状)
 *      ├── 变化超过阈值的通道 → 各产生一条事件
 *      └── 永远产生一条 snapshot-refreshed(它是"当前现状"的载体)
 *   apply(那一份读数的)                             ← 纯内存, 改现状与计数
 *   refresh(now) = apply(fetch(now))               ← 单线程路径用的合成
 * </pre>
 * <b>为什么"取"和"用"是两跳而不是一跳</b>: §8.5.9。外呼要挂在另一条线程上(否则一次
 * 30 秒的气象超时会把她的当天停半分钟), 而改世界只能发生在唯一那条仿真线程上
 * (见 {@code World} 的类注释)。见 {@link #fetch(Instant)} 里的那张线程表。
 *
 * <p>{@link #fetch(Instant)} <b>只产出、不投递、也不改现状</b> —— 这一点是刻意的,
 * 理由见 {@link EnvironmentRefresh#publishTo(EventFabric)} 的说明: 环境不知道自己
 * 该投给谁, "谁在这个地方"这件事只有世界知道。
 */
public interface Environment {

    /** 环境这个对象类型的标识。 */
    EventTypeId TYPE = EventTypeId.of("world", "environment");

    /** 环境 id 的命名空间 —— 装配时用 {@code ObjectId.of(NAMESPACE, "lab-env")}。 */
    String ID_NAMESPACE = "world.environment";

    /**
     * 气温每偏离舒适带 1℃ 的台账权重。
     *
     * <p>这个数不是随便选的: 设计文档 §5.2 举的例子是"室外 3 度 → 保暖通道 -0.02",
     * 而 3℃ 比舒适带下限(18℃)低 15 度 —— {@code 15 × 0.0013 ≈ 0.02}, 与文档一致。
     * 把它写成一个有名字的常量而不是散在公式里, 是为了让"调冷暖"变成一次
     * 可以讨论的改动, 而不是在某个表达式里改一个神秘数字。
     */
    double MAGNITUDE_PER_DEGREE = 0.0013;

    /** 湿度每偏离 0.5 一个单位的湿度通道权重 —— 高湿让体感更冷、也让衣物更难干。 */
    double MAGNITUDE_PER_HUMIDITY_RATIO = 0.02;

    /** 风速每 1 m/s 的保暖通道负权重 —— 风寒效应的量级近似。 */
    double MAGNITUDE_PER_MPS_WIND = 0.01;

    /** AQI 超过 100 之后每 1 点的压力通道权重。 */
    double MAGNITUDE_PER_AQI_POINT = 0.0005;

    /** 日照时长每偏离 12 小时一个单位的情绪通道权重 —— 它是"季节性情绪"的那个季节。 */
    double MAGNITUDE_PER_DAYLIGHT_HOUR = 0.01;

    /** 天气分类变化时, 由"日照因子"差折算到舒适通道的比例。 */
    double MAGNITUDE_PER_WEATHER_STEP = 0.05;

    // ═══════════════════════════ 身份与现状 ═══════════════════════════

    /** 环境身份。 */
    ObjectId id();

    /** 这个环境属于哪个地点 —— 与 {@code Place} 互为对方的坐标。 */
    ObjectId place();

    /**
     * 这个环境的<b>查询点</b>。
     *
     * <p>它与"地点自己的坐标"通常是同一个数, 但两者<b>不是</b>同一个概念, 所以刻意
     * 分成两个地方存: 一个 {@code Environment} 可以服务一片区域(一个"上海"环境
     * 对应好几个 {@code Place}), 于是它的查询点是一个格点, 而不是某一个具体地点的门牌号。
     *
     * <p>把它暴露出来的第二个理由是诊断: "她那儿 3℃"与"我们查的是哪儿"是两个
     * 不同的问题, 而后者出错的症状是"她的天气总是比实际晚半小时"(查到了相邻城市的格点)。
     */
    Location queryPoint();

    /** 当前快照。<b>永远有值</b> —— 一个环境至少在装配时有一份初始快照。 */
    EnvironmentSnapshot current();

    /** 上一次快照 —— 第一次刷新之前为空。诊断与"她刚才经历了什么变化"用它。 */
    Optional<EnvironmentSnapshot> previous();

    /** 刷新过多少次、失败过多少次 —— 诊断面板用它回答"这条链上的环境数据到底活着没有"。 */
    int refreshCount();

    int failureCount();

    /**
     * <b>取</b>一次数据 —— 外呼在这里, 而它<b>一个字都不改本环境</b>。
     *
     * <h2>为什么"取"与"用"要分成两个方法(§8.5.9)</h2>
     * 因为这两件事必须发生在<b>两个不同的线程</b>上, 而它们各自有硬约束:
     * <table border="1">
     *   <tr><th></th><th>在哪条线程上</th><th>为什么</th></tr>
     *   <tr>
     *     <td>{@code fetch}</td><td>刷新线程(那条每 10 分钟一拍的)</td>
     *     <td>它要外呼, 而一次气象 API 超时可以挂 30 秒 —— 那 30 秒绝不能落在
     *         心跳线程上(§8.5.0: 否则"她的一天莫名其妙停了半分钟")</td>
     *   </tr>
     *   <tr>
     *     <td>{@link #apply(EnvironmentRefresh)}</td><td>仿真线程(唯一进世界的)</td>
     *     <td>{@code World} 刻意不加锁, 前提是只有一个写者(见它的类注释)。
     *         改环境就是改世界</td>
     *   </tr>
     * </table>
     * 所以本方法的契约里有一条<b>不许</b>: 不许改本环境的任何字段(快照、计数)。
     * 它读的现状必须是"读一次、后面都用那一份" —— 否则同一批读数里的两条事件会
     * 各自基于不同版本的现状算出来, 而那种不一致没有任何异常会报。
     *
     * <p>它<b>也不投递</b>: 返回值里带着事件, 由知道"谁在这个地方"的一方
     * ({@code World})决定投给谁。
     *
     * @param now 仿真时刻。提供方按它取数(见 {@link EnvironmentProvider#query}),
     *            "过了多久该重抓"的判断由上层按它做
     * @return 这一轮的结果。失败也在返回值里如实表达(reason 非空), <b>不是</b>抛异常。
     *         <b>失败不会被计入 {@link #failureCount()}</b> —— 计数是一次写,
     *         而写属于 {@link #apply(EnvironmentRefresh)}(见它)
     */
    EnvironmentRefresh fetch(Instant now);

    /**
     * <b>用</b>那一份取回的读数 —— 纯内存, 改本环境的现状与计数。
     *
     * <p>它<b>不许外呼</b>(数据已经在参数里了), 也<b>只由仿真线程调</b>(见
     * {@link #fetch(Instant)} 的两线程表)。
     *
     * <p>失败也走这里: 一次失败的读数只让 {@link #failureCount()} 加一,
     * 快照<b>一个字都不改</b> —— 与"外面还是 3 度(至少直到下一次成功刷新)"这个事实一致,
     * 一次网络抖动不该让她突然经历一次"气温数据消失"(见 {@code Default.refresh} 的注释)。
     */
    void apply(EnvironmentRefresh fetched);

    /**
     * 取一次数据并立刻用它 —— <b>"取"与"用"的合成</b>, 给<b>单线程</b>的那几条路径用。
     *
     * <p>它的实现就是 {@code apply(fetch(now))}, 刻意写成 {@code default}: 合成顺序
     * 只有一处, 不会有人写反。
     *
     * <p><b>谁可以用它</b>: 离线回放({@code World.advance} 那条路径)、她进门时的
     * 即时刷新({@code World.placeHumanAt})、以及任何"我这一条线程就是世界"的场合。
     * <b>谁不可以用</b>: 心跳线路上任何周期性的东西 —— 生产路径的节奏是
     * {@code World.fetchEnvironments} + {@code World.applyEnvironments} 两跳
     * (§8.5.9)。用错了的症状是"她的一天每 10 分钟停半分钟", 而日志里只有一条
     * 天气取数的 WARN。
     *
     * @param now 仿真时刻
     * @return 这一轮的结果(与 {@link #fetch(Instant)} 同义)
     */
    default EnvironmentRefresh refresh(Instant now) {
        EnvironmentRefresh fetched = fetch(now);
        apply(fetched);
        return fetched;
    }

    /** 一行摘要 —— 日志与诊断面板用。 */
    default String describe() {
        return "环境[" + id().value() + " @" + queryPoint().describe()
                + "] " + current().describe()
                + (failureCount() > 0 ? " 失败 " + failureCount() + " 次" : "");
    }

    // ═══════════════════════════ 一轮刷新的结果 ═══════════════════════════

    /**
     * {@link #fetch(Instant)} 的返回值(合成的 {@link #refresh(Instant)} 也原样返回它)
     * —— <b>把"没拿到数据"与"拿到了但没变"分开</b>。
     *
     * <h2>为什么它需要存在, 而不是直接返回 {@code List<WorldEvent>}</h2>
     * 因为"这一次没拿到数据"必须能被调用方看见: 它要计入失败次数、要在诊断面板上显示、
     * 要与"天气真的一点都没变"区分开。一个只返回事件列表的签名会让这两件事长得一模一样
     * —— 而它们的区别正是"我们该不该去修那条数据链"。
     *
     * @param fetched       这次是不是真的拿到了新数据
     * @param snapshot      拿到的是哪一份(没拿到时是<b>上一份</b>, 让它继续生效)
     * @param changes       这一轮要投出去的事件。没拿到数据时是空列表
     * @param failureReason 失败原因; 成功时为 {@code null}
     * @param providerId    数据来源 —— 失败时它是排查的第一条线索
     * @param at            这一轮的仿真时刻
     */
    record EnvironmentRefresh(boolean fetched,
                              EnvironmentSnapshot snapshot,
                              List<WorldEvent> changes,
                              String failureReason,
                              String providerId,
                              Instant at) {

        public EnvironmentRefresh {
            Objects.requireNonNull(snapshot, "一次刷新必须带上一份快照 —— 哪怕是旧的");
            changes = changes == null ? List.of() : List.copyOf(changes);
            if (!fetched && changes.isEmpty() && failureReason == null) {
                // 这个组合只可能来自一个写错的调用点: 没刷新、没变化、也没原因,
                // 那这次刷新就完全没有信息 —— 它会静默地让失败计数永远为 0
                throw new IllegalArgumentException(
                        "一次刷新必须至少说明一件事: 拿到了数据, 或者有一个失败原因");
            }
            providerId = providerId == null || providerId.isBlank() ? "未知来源" : providerId;
            Objects.requireNonNull(at, "刷新必须带仿真时刻");
        }

        static EnvironmentRefresh of(EnvironmentSnapshot snapshot, List<WorldEvent> changes,
                                     String providerId, Instant at) {
            return new EnvironmentRefresh(true, snapshot, changes, null, providerId, at);
        }

        static EnvironmentRefresh failed(EnvironmentSnapshot unchanged, String reason,
                                         String providerId, Instant at) {
            return new EnvironmentRefresh(false, unchanged, List.of(), reason, providerId, at);
        }

        /** 成功了吗。 */
        public boolean ok() {
            return fetched;
        }

        /** 这一轮有没有值得投出去的事件。 */
        public boolean hasChanges() {
            return !changes.isEmpty();
        }

        /**
         * 把这一轮的全部变化投给一个人的边界 —— <b>链的最后一米</b>。
         *
         * <h2>为什么投递动作在这里, 而"投给谁"在外面</h2>
         * 关键的一句是: <b>环境不知道自己该投给谁</b>。它不知道谁住在上海、
         * 谁正在去实验室的地铁上 —— 那是世界的知识({@code World} 记录了
         * {@code humanId → placeId})。所以分工是:
         * <pre>
         *   Environment.fetch()               → 产出"发生了什么变化"
         *   World.applyEnvironments()         → 决定"谁在受影响的地方" + 把读数落到现状上
         *   EnvironmentRefresh.publishTo()    → 把事件放进那个人的 EventFabric
         * </pre>
         * (离线回放那条路径上, {@code World.advance()} 一个人把这三件事都做了 ——
         * 那条路径上"世界"就是它自己那条线程。)
         * 一个把 {@code EventFabric} 直接注入 {@code Environment} 的实现会更省事,
         * 但它必须是<b>某一个人的</b> fabric —— 而环境是给所有人共用的。
         * 那样做的后果是"上海的天气只发给了她一个人", 而下一个在这个环境里的
         * 人永远收不到任何环境事件。
         *
         * @param fabric 投给谁的边界
         * @return 投了几条
         */
        public int publishTo(EventFabric fabric) {
            Objects.requireNonNull(fabric, "投递必须给一条 EventFabric");
            for (WorldEvent event : changes) {
                fabric.publish(event);
            }
            return changes.size();
        }

        /** 一行摘要 —— 日志用。 */
        public String describe() {
            if (!fetched) {
                return "环境刷新失败[" + providerId + "] " + failureReason + " —— 沿用上一份快照";
            }
            return "环境刷新[" + providerId + "] " + changes.size() + " 条事件: "
                    + snapshot.describe();
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 环境的标准实现: 一个提供方 + 一个查询点 + 一个"上一次快照"。
     *
     * <h2>阈值为什么全部写在这里, 而不是写在事件里</h2>
     * 因为"变了多少算变"是<b>策略</b>, 而事件只负责陈述事实。把它写在事件类里会让
     * "我觉得 0.5℃ 太小了"变成一次要改事件类的改动 —— 而事件类是历史和重放的形状,
     * 它不该因为一个调参而变。同理, 阈值全部是 {@code public static final} 常量:
     * 它们是可讨论、可测试、可被将来做成配置的数, 而不是散在 {@code if} 里的字面量。
     */
    @Slf4j
    final class Default implements Environment {

        /** 气温变化阈值(摄氏度)—— 低于它不产生事件。"外面 20.1 变 20.2"不该惊动任何人。 */
        public static final double TEMPERATURE_THRESHOLD_CELSIUS = 0.5;

        /** 湿度变化阈值(比例)。 */
        public static final double HUMIDITY_THRESHOLD = 0.05;

        /** 风速变化阈值(米/秒)。 */
        public static final double WIND_THRESHOLD_MPS = 0.5;

        /** 光照变化阈值(比例)—— 见 {@code EnvironmentSnapshot.Delta#illuminanceChangedBy}。 */
        public static final double ILLUMINANCE_RATIO_THRESHOLD = 0.25;

        /** AQI 变化阈值。 */
        public static final int AQI_THRESHOLD = 10;

        /** 日照时长变化阈值(小时)。 */
        public static final double DAYLIGHT_THRESHOLD_HOURS = 0.25;

        /** 雨声的强度阈值 —— 毛毛雨不该产生一条听觉刺激。 */
        public static final double RAIN_INTENSITY_THRESHOLD_MM = 0.5;

        private final ObjectId id;
        private final ObjectId place;
        private final Location queryPoint;
        private final EnvironmentProvider provider;

        /**
         * 现状、上一次、以及两个计数 —— 这四个是<b>唯一</b>被跨线程读写的字段(§8.5.9)。
         *
         * <h2>它们为什么是 volatile</h2>
         * 写只有一处(仿真线程上的 {@link #apply(EnvironmentRefresh)}), 但
         * {@link #fetch(Instant)} 在<b>刷新线程</b>上读 {@code current} 来算 delta。
         * 没有 volatile 的话, 刷新线程可能拿到一个更旧的引用, 于是它算出来的变化是
         * 相对<b>更早那份快照</b>的 —— 症状是"温度从 3℃ 变 12℃"那条事件被吞掉,
         * 或者一条早就投过的事件被重复投一次, 而日志里一切正常。
         *
         * <p>volatile 是这里<b>够用</b>的全部: 一个写者 + 若干读者, 读者要的只是
         * "读到某个完整版本的快照"(快照本身是不可变 record, 所以引用可见即内容可见),
         * 不需要复合原子性 —— 复合原子性要的是锁, 而 {@code World} 那一侧刻意不加锁
         * (见它的类注释: 加锁会在重入的投递路径上死锁)。
         */
        private volatile EnvironmentSnapshot current;
        private volatile EnvironmentSnapshot previous;
        private volatile int refreshCount;
        private volatile int failureCount;

        /**
         * @param id         环境 id
         * @param place      属于哪个地点
         * @param queryPoint 查询点(通常就是那个地点的坐标)
         * @param provider   数据来源
         * @param initial    初始快照 —— <b>必须给</b>: 一个"什么都没有"的环境会让
         *                   第一条变化事件变成"从 null 变成 20℃", 而那条事件的
         *                   差值是没有意义的。装配时给一份"大致合理"的初始快照
         *                   (见 {@link EnvironmentSnapshot#mild})比让第一次刷新去补要诚实
         *
         * <p><b>为什么是 public(而不是包级私有)</b>: 造环境这件事是<b>装配</b>
         * (§8.6), 而装配住在别的包里 —— 一个包级私有的构造器等于说"只有
         * {@code world.environment} 自己能造出一个环境", 于是装配层要么被迫写一个
         * 假的环境(那就绕开了 delta 与阈值这套真正的逻辑), 要么把装配塞进这个包。
         * 两条路都比多一个可见性关键字贵。这里刻意<b>不</b>提供静态工厂
         * {@code of(...)}: 一个构造器和它的名字已经说明了一切, 再加一层只是多一处
         * 可以不一致的地方。
         */
        public Default(ObjectId id, ObjectId place, Location queryPoint,
                EnvironmentProvider provider, EnvironmentSnapshot initial) {
            this.id = Objects.requireNonNull(id, "环境必须有身份");
            this.place = Objects.requireNonNull(place, "环境必须属于一个地点");
            this.queryPoint = Objects.requireNonNull(queryPoint, "环境必须有查询点");
            this.provider = Objects.requireNonNull(provider, "环境必须有数据来源");
            this.current = Objects.requireNonNull(initial, "环境必须有初始快照");
        }

        @Override
        public ObjectId id() {
            return id;
        }

        @Override
        public ObjectId place() {
            return place;
        }

        @Override
        public Location queryPoint() {
            return queryPoint;
        }

        @Override
        public EnvironmentSnapshot current() {
            return current;
        }

        @Override
        public Optional<EnvironmentSnapshot> previous() {
            return Optional.ofNullable(previous);
        }

        @Override
        public int refreshCount() {
            return refreshCount;
        }

        @Override
        public int failureCount() {
            return failureCount;
        }

        /**
         * 外呼一次, 算出"这一轮值得投出去的变化", 然后<b>什么都不改</b>。
         *
         * <p>失败时返回一条 {@link EnvironmentRefresh#failed} —— 旧快照继续是 current,
         * 计数也不动。这与"外面还是 3 度(至少直到下一次成功刷新)"这个事实一致 ——
         * 一次网络抖动不该让她突然经历一次"气温数据消失"。
         *
         * <h2>"只读一次现状"为什么是这段代码里最要紧的一行</h2>
         * {@code before} 只读一次, 后面 delta 与 {@code WeatherChanged} 都用它。
         * 如果两处各自去读 {@code current}, 那么当仿真线程在两条读之间刚刚
         * {@link #apply(EnvironmentRefresh)} 过一份新数据时, 我们会拿"新现状"去比
         * "更早那份"算变化 —— 于是天气从晴变雨那条事件里写着一个错的"从"。
         * 这种错误不会抛异常, 只会在时间轴上留一条读起来有点怪的记录。
         */
        @Override
        public EnvironmentRefresh fetch(Instant now) {
            Objects.requireNonNull(now, "取数必须带仿真时刻");
            // 现状只读一次 —— 见方法注释
            EnvironmentSnapshot before = this.current;

            EnvironmentSnapshot fetched;
            try {
                fetched = provider.query(queryPoint, now);
            } catch (RuntimeException e) {
                // WARN 而不是 ERROR: 一次气象 API 超时不是系统故障, 而且我们不希望
                // 一个每分钟重试的数据链把 ERROR 日志刷满, 从而盖住真正的错误。
                //
                // 计数只能报"此前"的次数: failureCount++ 是一次<b>写</b>, 而写属于
                // apply(§8.5.9 的两线程分工)。一份取不回来的读数到底算不算一次失败,
                // 由"它有没有被应用"决定 —— 所以真正的计数发生在 apply 里
                log.warn("[Environment/{}] 取数失败(此前已失败 {} 次): {}", id.value(),
                        failureCount, e.toString());
                return EnvironmentRefresh.failed(before, e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                        provider.providerId(), now);
            }

            EnvironmentSnapshot.Delta delta = fetched.deltaFrom(before);
            List<WorldEvent> changes = new ArrayList<>();

            // 快照本身永远先投 —— 它是"当前现状"的载体, 供落库、重放与诊断。
            // 用户要求"每 10 分钟形成一个 event", 说的就是这一条: 即使什么都没变,
            // 时间轴上也要有一个"我们看过一眼, 当时是这样"的点
            changes.add(SnapshotRefreshed.of(fetched, queryPoint, now));

            if (delta.temperatureChangedBy(TEMPERATURE_THRESHOLD_CELSIUS)) {
                changes.add(TemperatureChanged.of(fetched, queryPoint, now));
            }
            if (delta.humidityChangedBy(HUMIDITY_THRESHOLD)) {
                changes.add(HumidityChanged.of(fetched, queryPoint, now));
            }
            if (delta.conditionChanged()) {
                changes.add(WeatherChanged.of(before, fetched, queryPoint, now));
            }
            if (delta.precipitationStarted()
                    && fetched.precipitationMmPerHour() >= RAIN_INTENSITY_THRESHOLD_MM) {
                // 只有"开始"才是实时刺激 —— 已经在下的时候再投会变成每 10 分钟一次
                // 的重复噪音, 而"雨还在下"这件事由 snapshot-refreshed 陈述
                changes.add(RainStarted.of(fetched, queryPoint, now));
            }
            if (delta.windChangedBy(WIND_THRESHOLD_MPS)) {
                changes.add(WindStarted.of(fetched, queryPoint, now));
            }
            if (delta.airQualityChangedBy(AQI_THRESHOLD)) {
                changes.add(AirQualityChanged.of(fetched, queryPoint, now));
            }
            if (delta.daylightChangedBy(DAYLIGHT_THRESHOLD_HOURS)) {
                changes.add(DaylightChanged.of(fetched, queryPoint, now));
            }

            // 光照只在跨阈值时产生事件, 且它是 B 类(视觉)—— "天黑了该开灯了"是她
            // 当下会注意到的事。目录里没有 environment.illuminance-changed,
            // 所以这一处<b>暂时不做</b>: 见类注释末尾的说明
            if (delta.illuminanceChangedBy(ILLUMINANCE_RATIO_THRESHOLD)) {
                log.debug("[Environment/{}] 光照变化显著({} → {} lux), 但目录里没有对应事件类型",
                        id.value(), before.illuminanceLux(), fetched.illuminanceLux());
            }

            EnvironmentRefresh result = EnvironmentRefresh.of(fetched, changes, provider.providerId(), now);
            log.debug("[Environment/{}] 取数成功: {}", id.value(), result.describe());
            return result;
        }

        /**
         * 把一份取回的读数落到本环境上 —— 纯内存, <b>一行代码都没有外呼</b>。
         *
         * <p>成功: {@code previous ← current, current ← 新快照, refreshCount++}。
         * 失败: 只有 {@code failureCount++} —— 快照与 previous 都不动(见
         * {@link #fetch(Instant)} 里那句"外面还是 3 度")。
         *
         * <p>注意这两条路的<b>顺序</b>: 先看失败、再看成功。一个失败的读数带的是
         * {@code snapshot == before}(fetch 里就是这么造的), 谁要是不小心把它当成
         * 成功应用下去, previous 与 current 会变成同一份 —— 下一轮 delta 恒为空,
         * 她的天气就"冻住"了, 而且再一次异常都不会报。
         */
        @Override
        public void apply(EnvironmentRefresh fetched) {
            Objects.requireNonNull(fetched, "要应用的读数不能为空");
            if (!fetched.ok()) {
                failureCount++;
                return;
            }
            this.previous = this.current;
            this.current = fetched.snapshot();
            this.refreshCount++;
        }

        @Override
        public String describe() {
            return Environment.super.describe() + " 刷新 " + refreshCount + " 次";
        }
    }

    // ═══════════════════════════ 事件: 持续影响(A 类) ═══════════════════════════

    /**
     * {@code environment.temperature-changed.v1} —— 气温变了。
     *
     * <h2>它为什么是 A 类(持续影响)而不是"她冷了"</h2>
     * 用户的要求是"温度低应该让 human 的 body <b>持续</b>降低保暖值"。
     * "持续"两个字决定了它必须是 A 类: 3℃ 不是一个发生一次的事, 而是一个
     * <b>一直成立</b>的状态。入账之后, 每个 tick 都由台账反复施加影响, 直到
     * 气温回升或者她被搬到别的地方。
     *
     * <p>它<b>不惊动她</b> —— 惊动她的是保暖值跌破阈值时 Body 产生的冷刺激
     * ({@code body.cold-stimulus})。这条分工是"环境是事实, 她冷不冷是她的身体"的
     * 直接推论, 也是本设计里最容易被绕过的一处: 一个"温度低于 10℃ 就投一条
     * 冷刺激"的实现看起来很直观, 但它把"她穿没穿外套"这件事从模型里删掉了。
     *
     * @param celsius     气温
     * @param feelsLike   体感温度(风速与湿度折算之后)
     * @param locationId  哪个地点的天气
     * @param occurredAt  观测时刻
     */
    @DomainType("environment.temperature-changed")
    record TemperatureChanged(double celsius, double feelsLike, String locationId,
                              Instant occurredAt) implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "temperature-changed");

        public TemperatureChanged {
            if (Double.isNaN(celsius) || Double.isNaN(feelsLike)) {
                throw new IllegalArgumentException("气温与体感温度都不能是 NaN");
            }
            Objects.requireNonNull(locationId, "气温事件必须说明是哪个地点 —— 否则不知道给谁入账");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static TemperatureChanged of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new TemperatureChanged(snapshot.temperatureCelsius(), snapshot.feelsLikeCelsius(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /**
         * 保暖通道上的权重 —— <b>由体感温度而不是气温算</b>。
         *
         * <p>理由是体感才是她真正经历的东西: 3℃ 无风与 3℃ 七级风对身体的压力
         * 不是一回事, 而后者会被风寒折算进来。用气温算会让"刮大风的 3 度"
         * 与"无风的 3 度"在她的身体上完全一样 —— 那显然不对。
         *
         * <p>舒适带内为 0: 20℃ 既不让她失温也不让她过热, 但它<b>仍然产生这条事件</b>
         * (差值驱动), 只是入账的强度是 0 —— "从 19℃ 暖到 21℃"是一个要记录的事实。
         */
        @Override
        public double magnitude() {
            double deviation;
            if (feelsLike < EnvironmentSnapshot.COMFORT_LOW_CELSIUS) {
                deviation = feelsLike - EnvironmentSnapshot.COMFORT_LOW_CELSIUS;
            } else if (feelsLike > EnvironmentSnapshot.COMFORT_HIGH_CELSIUS) {
                deviation = feelsLike - EnvironmentSnapshot.COMFORT_HIGH_CELSIUS;
            } else {
                return 0.0;
            }
            return deviation * MAGNITUDE_PER_DEGREE;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.WARMTH;
        }

        /**
         * 同地点同通道的影响互相替换 —— 新的读数就是"现在的天气", 上一个读数作废。
         *
         * <p>不给 {@code expiresAt}: 一份环境读数的有效期不是"多久之后失效",
         * 而是"直到下一次刷新替换它"。用一个固定的 expiresAt 会制造出一段
         * "旧读数已经失效但新读数还没来"的空窗, 而那段空窗里她既不在冷也不在暖 ——
         * 那不是任何真实世界的状态。
         */
        @Override
        public String cancellationKey() {
            return EnvironmentKeys.effectKey(CoreEventCatalog.Channels.WARMTH, locationId);
        }

        @Override
        public String describe() {
            return String.format("气温 %.1f℃(体感 %.1f℃) @%s", celsius, feelsLike, locationId);
        }
    }

    /**
     * {@code environment.humidity-changed.v1} —— 湿度变了。
     *
     * <p>它走 {@code body.wetness} 通道而不是保暖通道: 高湿让衣物与皮肤更难干,
     * 而"体感更冷"这件事已经由 {@link TemperatureChanged} 的 {@code feelsLike}
     * 提前折算掉了。两条通道各表达一件事, 于是"湿冷"与"干冷"在她的身体上
     * 是可以被区分开的。
     */
    @DomainType("environment.humidity-changed")
    record HumidityChanged(double relativeHumidity, String locationId, Instant occurredAt)
            implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "humidity-changed");

        public HumidityChanged {
            if (relativeHumidity < 0 || relativeHumidity > 1) {
                throw new IllegalArgumentException(
                        "湿度必须是比例(0..1), 收到 " + relativeHumidity);
            }
            Objects.requireNonNull(locationId, "湿度事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static HumidityChanged of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new HumidityChanged(snapshot.humidity(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /** 以 0.5 为中性点: 干燥是负、潮湿是正, 两者都让"湿冷/闷热"更难挨。 */
        @Override
        public double magnitude() {
            return (relativeHumidity - 0.5) * MAGNITUDE_PER_HUMIDITY_RATIO;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.WETNESS;
        }

        @Override
        public String cancellationKey() {
            return EnvironmentKeys.effectKey(CoreEventCatalog.Channels.WETNESS, locationId);
        }

        @Override
        public String describe() {
            return String.format("湿度 %.0f%% @%s", relativeHumidity * 100, locationId);
        }
    }

    /**
     * {@code environment.weather-changed.v1} —— 天气分类变了(晴转雨)。
     *
     * <p>它是一条"组合影响": 下雨同时作用于体感与舒适度。文档说这个拆解由环境侧做,
     * 所以这里只投<b>一条</b> {@code comfort} 通道的影响(体感那部分已经由
     * {@link TemperatureChanged} 的 {@code feelsLike} 承担), 而不是投两条 ——
     * 两条同源的影响会让"这次变化到底贡献了多少"在台账里变成一笔糊涂账。
     */
    @DomainType("environment.weather-changed")
    record WeatherChanged(EnvironmentSnapshot.WeatherCondition from,
                         EnvironmentSnapshot.WeatherCondition to,
                         String locationId, Instant occurredAt) implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "weather-changed");

        public WeatherChanged {
            Objects.requireNonNull(from, "要说明从什么天气变成什么天气");
            Objects.requireNonNull(to, "要说明从什么天气变成什么天气");
            Objects.requireNonNull(locationId, "天气事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static WeatherChanged of(EnvironmentSnapshot previous, EnvironmentSnapshot current,
                                 Location location, Instant at) {
            return new WeatherChanged(previous.condition(), current.condition(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /**
         * 用两个分类的"日照因子"之差折算。
         *
         * <p>为什么用日照因子而不是一张手写的"每种天气舒服度"表: 后者会是第二份
         * 关于天气的知识, 而它迟早与 {@link EnvironmentSnapshot.WeatherCondition}
         * 里那张表漂移(加了一种天气, 只改了一处)。日照因子本来就表达了"这个天气
         * 有多少太阳", 而"有多少太阳"正是舒适度里最主要的那一项。
         */
        @Override
        public double magnitude() {
            return (to.daylightFactor() - from.daylightFactor()) * MAGNITUDE_PER_WEATHER_STEP;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.COMFORT;
        }

        @Override
        public String cancellationKey() {
            return EnvironmentKeys.effectKey(CoreEventCatalog.Channels.COMFORT, locationId);
        }

        @Override
        public String describe() {
            return "天气 " + from.label() + " → " + to.label() + " @" + locationId;
        }
    }

    /** {@code environment.wind-started.v1} —— 起风了。 */
    @DomainType("environment.wind-started")
    record WindStarted(double level, String locationId, Instant occurredAt)
            implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "wind-started");

        public WindStarted {
            if (Double.isNaN(level) || level < 0) {
                throw new IllegalArgumentException("风速不能为负, 收到 " + level);
            }
            Objects.requireNonNull(locationId, "风事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static WindStarted of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new WindStarted(snapshot.windSpeedMetersPerSecond(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /**
         * 负值 —— 风只让热量散得更快, 不会让人变暖。
         *
         * <p>与 {@link TemperatureChanged} 用的不是同一个数(那里是体感温度),
         * 这是刻意的: 体感温度已经含了风, 而这条事件是风<b>自己</b>对保暖的影响
         * —— 两者投进同一条通道会被相加, 于是风被算了两次。
         * <b>这个重复计入是已知的、刻意保留的</b>: 体感温度负责"她觉得温度是多少",
         * 风负责"热量散得多快", 它们是两个不同的物理过程。
         */
        @Override
        public double magnitude() {
            return -level * MAGNITUDE_PER_MPS_WIND;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.WARMTH;
        }

        @Override
        public String cancellationKey() {
            return EnvironmentKeys.effectKey("wind", locationId);
        }

        @Override
        public String describe() {
            return String.format("风 %.1f m/s @%s", level, locationId);
        }
    }

    /** {@code environment.air-quality-changed.v1} —— 空气变差了(或者变好了)。 */
    @DomainType("environment.air-quality-changed")
    record AirQualityChanged(int aqi, String level, String locationId, Instant occurredAt)
            implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "air-quality-changed");

        public AirQualityChanged {
            if (aqi < 0) {
                throw new IllegalArgumentException("AQI 不能为负, 收到 " + aqi);
            }
            level = level == null || level.isBlank() ? AirQualityChanged.levelOf(aqi) : level;
            Objects.requireNonNull(locationId, "空气质量事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static AirQualityChanged of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new AirQualityChanged(snapshot.aqi(), levelOf(snapshot.aqi()),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        /**
         * 按 AQI 给一个档位名 —— <b>常量式的取值, 不是白名单</b>。
         *
         * <p>用字符串而不是枚举, 理由同 P4: "空气分几档"是可以随标准变化的
         * (各国标准不同、名称也会改), 而它不承担"agent 能做什么"的扩展职责
         * —— 她无论空气多差都只能选择关窗、戴口罩或者不出门。
         */
        static String levelOf(int aqi) {
            if (aqi <= 50) {
                return "good";
            }
            if (aqi <= 100) {
                return "moderate";
            }
            if (aqi <= 150) {
                return "unhealthy-sensitive";
            }
            if (aqi <= 200) {
                return "unhealthy";
            }
            if (aqi <= 300) {
                return "very-unhealthy";
            }
            return "hazardous";
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /** 100 以下不入账(那是正常空气), 超过之后线性增加一点压力。 */
        @Override
        public double magnitude() {
            return aqi <= 100 ? 0.0 : (aqi - 100) * MAGNITUDE_PER_AQI_POINT;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.STRESS;
        }

        @Override
        public String cancellationKey() {
            return EnvironmentKeys.effectKey(CoreEventCatalog.Channels.STRESS, locationId);
        }

        @Override
        public String describe() {
            return "AQI " + aqi + "(" + level + ") @" + locationId;
        }
    }

    /** {@code environment.daylight-changed.v1} —— 日照时长变了。它是"季节性情绪"的那个季节。 */
    @DomainType("environment.daylight-changed")
    record DaylightChanged(double daylightHours, Instant sunrise, Instant sunset,
                           String locationId, Instant occurredAt) implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "daylight-changed");

        public DaylightChanged {
            if (Double.isNaN(daylightHours) || daylightHours < 0 || daylightHours > 24) {
                throw new IllegalArgumentException(
                        "日照时长必须在 [0, 24] 小时之间, 收到 " + daylightHours);
            }
            Objects.requireNonNull(locationId, "日照事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static DaylightChanged of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new DaylightChanged(snapshot.daylightHours(), snapshot.sunrise(), snapshot.sunset(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /** 以 12 小时为中性点: 白天越长情绪底色越亮, 越短越暗。 */
        @Override
        public double magnitude() {
            return (daylightHours - 12.0) * MAGNITUDE_PER_DAYLIGHT_HOUR;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.MOOD;
        }

        /**
         * 日照的影响<b>不参与同键替换</b>(返回 {@code null})。
         *
         * <p>理由是它的时间尺度不同: 气温与天气是"今天这一刻"的, 会一次次被改写;
         * 而日照时长是"这个季节"的, 一天之内几乎不变。给它一个替换键的话,
         * 它会被自己那些细微的刷新反复改写, 于是"季节感"这条慢变量在一个
         * 每 10 分钟刷新的系统里被搅成了噪声。
         */
        @Override
        public String cancellationKey() {
            return null;
        }

        @Override
        public String describe() {
            return String.format("日照 %.2f 小时(日出 %s, 日落 %s) @%s",
                    daylightHours, sunrise, sunset, locationId);
        }
    }

    // ═══════════════════════════ 事件: 快照载体(A 类) ═══════════════════════════

    /**
     * {@code environment.snapshot-refreshed.v1} —— <b>每轮刷新必投的那一条</b>。
     *
     * <h2>它存在的两个理由</h2>
     * <ol>
     *   <li><b>零差异时也有一个时间轴上的点。</b>用户要求环境"定期形成一个 event",
     *       而"每 10 分钟看过一眼"本身就是事实 —— 它是"我们当时没瞎"的证据,
     *       也是重放时重建环境状态的依据;</li>
     *   <li><b>它是上面那些"变化"事件的载体。</b>变化事件只说差了多少,
     *       而"现在的绝对值是多少"记在这里。少了它, 一个只在变化时投事件的系统
     *       在重放时只能靠累加差值还原气温, 而任何一个丢包都会让那个和永久偏掉。</li>
     * </ol>
     *
     * <p>它的 magnitude 恒为 0: 它陈述现状, 不施加影响。{@code comfort} 通道是
     * 目录给定的占位(channel 不能为空), 与 {@code system.clock-tick.v1} 的
     * 占位通道是同一个性质 —— "有类型但没有实际影响", 这一点必须写在注释里,
     * 否则读代码的人会去找它的效果。
     */
    @DomainType("environment.snapshot-refreshed")
    record SnapshotRefreshed(EnvironmentSnapshot snapshot, String locationId, Instant occurredAt)
            implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "snapshot-refreshed");

        public SnapshotRefreshed {
            Objects.requireNonNull(snapshot, "快照事件必须带一份快照");
            Objects.requireNonNull(locationId, "快照事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static SnapshotRefreshed of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new SnapshotRefreshed(snapshot,
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        /** 恒为 0 —— 见类注释。 */
        @Override
        public double magnitude() {
            return 0.0;
        }

        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.COMFORT;
        }

        /** 不参与替换: 它是"当时的现状", 每一条都该留在历史里。 */
        @Override
        public String cancellationKey() {
            return null;
        }

        /** 它不代表任何"变化", 所以值不值得记为一次变更的答案是"不"。 */
        @Override
        public boolean isChange() {
            return false;
        }

        @Override
        public String describe() {
            return "环境快照 @" + locationId + " " + snapshot.describe();
        }
    }

    // ═══════════════════════════ 事件: 实时刺激(B 类) ═══════════════════════════

    /**
     * {@code environment.rain-started.v1} —— <b>开始下雨了, 她听得见</b>。
     *
     * <h2>它与 {@link WeatherChanged} 的区别是什么</h2>
     * 一句话: 后者<b>只改状态</b>, 前者<b>会惊动她</b>。文档写得很清楚 ——
     * "雨声是听到的, 而听到这件事需要她当下处理(她可能去关窗)"。
     * 所以这条是 B 类(实时刺激, 走听觉通道), 而天气变化是 A 类(入台账)。
     *
     * <p>把它做成 A 类的后果很具体: 她永远不会"被雨声打断", 于是
     * "她起身去关窗"这个行为在仿真里就没有起因 —— 它只有"她计划关窗"这一种可能。
     */
    @DomainType("environment.rain-started")
    record RainStarted(double intensity, String locationId, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "rain-started");

        public RainStarted {
            if (Double.isNaN(intensity) || intensity < 0) {
                throw new IllegalArgumentException("雨量不能为负, 收到 " + intensity);
            }
            Objects.requireNonNull(locationId, "雨声事件必须说明是哪个地点");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
        }

        static RainStarted of(EnvironmentSnapshot snapshot, Location location, Instant at) {
            return new RainStarted(snapshot.precipitationMmPerHour(),
                    location.labelIfNamed().orElse(location.describe()), at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return locationId;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.AUDITORY;
        }

        /**
         * urgency 随雨量增加, 上限 0.6。
         *
         * <p>为什么封顶在 0.6: 雨声再大也不该把"手机响了"或者火警挤出队列头部。
         * 一个 1.0 的雨声会让真正紧急的刺激在她那儿排到后面 —— 而那正是
         * {@code urgency} 这个字段存在的意义所在(见 {@code SensoryEvent} 的注释)。
         */
        @Override
        public double urgency() {
            return Math.min(0.6, 0.3 + intensity * 0.05);
        }

        /**
         * 折叠键按地点给 —— 同一次刷新里"雨开始"与"雨变大"只该留下一条。
         *
         * <p>{@code ongoing()} 保持默认的 {@code false}("刚开始"): 本事件只在
         * 从"没下雨"变成"下雨"的那一刻产生, 所以它每一次都是<b>开始</b>。
         * "雨还在下"由快照事件陈述, 不重复投刺激。
         */
        @Override
        public String foldingKey() {
            return "environment:rain:" + locationId;
        }

        @Override
        public String describe() {
            return String.format("开始下雨 %.1f mm/h @%s", intensity, locationId);
        }
    }

    /**
     * 环境事件的内部小工具 —— <b>刻意是嵌套类而不是公共类型</b>。
     *
     * <p>它只做一件事: 拼出同族影响的取消键。放在这里而不是提成公共类, 是因为
     * "取消键长什么样"是环境域<b>内部</b>的约定 —— 没有第三方会来复用这个格式,
     * 而一个公共类型会让它看起来像是一个被承诺过的接口。
     */
    final class EnvironmentKeys {

        private EnvironmentKeys() {
        }

        /**
         * 同族影响的身份: {@code environment:<通道>:<地点>}。
         *
         * <p>带上通道名是必须的: 同一个地点同时挂着保暖、湿冷、压力三笔账,
         * 如果键只按地点给, 一次湿度刷新会把气温那笔账顶掉 —— 而那表现成
         * "她一刷新就不冷了", 一个极难定位的 bug。
         */
        static String effectKey(String channel, String locationId) {
            return "environment:" + channel + ":" + locationId;
        }
    }

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>环境域的全部八条事件, 由本接口自己登记</b> —— 装配层调用。
     *
     * <h2>为什么登记这件事归这里</h2>
     * 因为"外面发生了什么值得她知道"这件事<b>只有本文件说得清</b>: 八条 record 全在这里,
     * 而"哪一次读数该变成哪一条事件"的判断也在 {@link #apply(EnvironmentRefresh)} 那一条
     * 路径上(见 {@code Default} 的注释)。八个类型各登记各的不会让任何一处变清楚 ——
     * 它们共享同一个 {@code EnvironmentKeys.effectKey} 约定与同一段"为什么是这八条"
     * 的推理, 拆开之后那份推理就没有主人了。
     *
     * <p>于是这里的 {@code return 8} 不是"八条恰好同在一个文件里的类型", 而是
     * <b>环境这一个世界侧的全部现象</b> —— 也正是启动日志里那个类型数要回答的问题。
     *
     * <h2>为什么这条清单不能靠"扫 environment.* 前缀"推出来</h2>
     * 因为 {@code environment.location-changed} 这条<b>不在本文件里</b> ——
     * 它声明在 {@code world.digital.Place}(移动是地点侧发生的事)。
     * 一条按前缀猜的装配会把它漏掉, 而漏掉的后果是"她什么时候到过实验室"
     * 在重启之后读不回来 —— 而那恰是"她那天为什么穿了羽绒服"的上游。
     * 它由 {@code Place.registerTypes} 登记, 装配层两行都要写。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条。可重复调用: 同一个类登记两次在注册表那边是一次空操作
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(TemperatureChanged.class);
        registry.register(HumidityChanged.class);
        registry.register(WeatherChanged.class);
        registry.register(WindStarted.class);
        registry.register(AirQualityChanged.class);
        registry.register(DaylightChanged.class);
        registry.register(SnapshotRefreshed.class);
        registry.register(RainStarted.class);
        return 8;
    }
}
