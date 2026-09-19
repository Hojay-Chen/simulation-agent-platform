package com.luxera.companion.world;

import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.world.device.Device;
import com.luxera.companion.world.digital.Place;
import com.luxera.companion.world.environment.Environment;
import com.luxera.companion.world.environment.Environment.EnvironmentRefresh;
import com.luxera.companion.world.object.ObjectId;
import com.luxera.companion.world.object.WorldObject;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * V2.2 §4 —— <b>World: 她是被放进去的那个世界</b>。数字设备世界 + 数字世界, 合成一个聚合根。
 *
 * <h2>它到底是什么: 一张注册表 + 一张座位表 + 一个推进器</h2>
 * 世界本身<b>没有业务</b> —— 天气怎么变在 {@link Environment} 里, 手机怎么响在
 * {@code Phone} 里, 地点在哪在 {@link Place} 里。世界负责的是三件只有它才知道的事:
 * <table border="1">
 *   <tr><th>职责</th><th>数据</th><th>为什么只有世界知道</th></tr>
 *   <tr>
 *     <td><b>有什么</b></td>
 *     <td>对象注册表: id → {@link WorldObject}</td>
 *     <td>装配代码把手机、地点、环境一次性装进世界, 之后没有人再问"这台手机是谁造的"</td>
 *   </tr>
 *   <tr>
 *     <td><b>谁在哪</b></td>
 *     <td>座位表: humanId → placeId, 以及 humanId → {@link EventFabric}</td>
 *     <td>它是"环境事件该投给谁"的唯一依据: 上海的天气只能投给此刻在上海的人</td>
 *   </tr>
 *   <tr>
 *     <td><b>推进谁</b></td>
 *     <td>{@link #advanceDevices(Duration, Instant)} +
 *         {@link #fetchEnvironments(Instant)} + {@link #applyEnvironments(List, Instant)}
 *         (外加把前两段合成一步的 {@link #advance(Duration, Instant)})</td>
 *     <td>时间是世界的, 不是设备自己的。设备没有 tick 循环, 它被推着走</td>
 *   </tr>
 * </table>
 *
 * <p>推进那一格为什么要<b>三个</b>入口: 设备演化是纯内存的(每秒一次), 环境刷新会外呼
 * (每 10 分钟一次, 一次可能挂几十秒), 而"改这个世界"只能由一条线程做。三者的交集只有
 * 一种切法(§8.5.9): <b>取</b>在刷新线程上(只读, 一个字都不改世界), <b>用</b>在仿真线程上
 * (纯内存, 几微秒)。把它们留在同一个方法里, 一次气象 API 超时就会让她的一天停半分钟
 * —— 详见 §8.5.0 / §8.5.3, 以及那三个入口各自的注释。
 *
 * <h2>两个世界为什么要合在一个聚合根里</h2>
 * 用户把世界分成两半(DigitalDeviceWorld / DigitalWorld), 判别标准是"agent 能不能
 * 用一个 ActionCommand 改变它"。但<b>分成两半不等于分成两个对象</b>:
 * <ul>
 *   <li>她所在的<b>地点</b>决定了她那儿的<b>天气</b>(digital world 内部) ——
 *       这条关系由座位表记着;</li>
 *   <li>她的<b>手机</b>在哪个地点、离她多近(device world) ——
 *       {@code Phone.placeAt} 拿到的那个位置, 与座位表里她在哪必须一致,
 *       否则会出现"她在实验室, 手机在家里的桌上"这种自相矛盾的状态;</li>
 *   <li>推进一个 tick 是<b>一件事</b>: 先推设备、再刷环境、然后把环境变化投给
 *       在那个地点的人。拆成两个对象要立刻回答"谁先"以及"两边各自持有的
 *       座位表如何保持一致"。</li>
 * </ul>
 * 所以: <b>两分是分类, 不是拆分。</b>结构上的体现是 —— 一半的成员有
 * {@code capabilities()}({@link Device}), 另一半没有({@link Place} / {@link Environment}),
 * 而它们挂在同一个聚合根下。这条分界线于是是<b>类型层面</b>的, 不是"两个并列的容器"。
 *
 * <h2>Human 与 World 零交互: 在这个类里怎么落地</h2>
 * 本类不认识 {@code human.*} 里的任何类型, 也没有一个叫做 {@code Human} 的字段。
 * 它关于人的全部知识是两条:
 * <pre>{@code
 *   Map<String, EventFabric> fabricsByHuman;   // 往哪投 —— 一条通道, 一个 id
 *   Map<String, String>       placeOfHuman;    // 她此刻在哪 —— 一个 id
 * }</pre>
 * 换句话说: 世界知道"有人在", 不知道"她是谁"。这个区别不是洁癖, 它是几句
 * 设计承诺的技术实现:
 * <ul>
 *   <li>世界不能问"她此刻忙不忙"来决定手机响不响 —— 因为那句话根本无从写起
 *       (没有 {@code Human} 对象可问), 而它<b>也不该</b>由世界回答:
 *       手机响不响是手机的规则, "要不要被打断"是她的事, 发生在事件投递之后;</li>
 *   <li>回放可复现: 世界这一侧的全部输入是"外部推进的时刻"与"外部的 Action",
 *       输出是"投进 fabric 的事件流"。同一个世界 + 同一份 Action 序列 = 同一条事件流。</li>
 * </ul>
 * 这条约束由 {@code V22BoundaryArchitectureTest.theWorldNeverReachesIntoTheHuman} 守着。
 *
 * <h2>设备的能力是怎么变成 agent 的工具的</h2>
 * {@link #capabilities()} 把全部设备的 {@code capabilities()} 汇总出来, 装配代码把它
 * 交给 {@code CapabilityRegistry} / {@code ActionFabric}。<b>世界自己不路由</b> ——
 * 路由键是 {@code capabilityKey}, 而同一个 key 可能出现在两台设备上(两台手机各装了
 * 一个聊天软件)。世界只负责把它们都交出去, 并让"有几个"这件事保持可见(见
 * {@link Device#capabilities()} 关于 {@code Collection} 的说明)。
 *
 * <h2>线程语义</h2>
 * 本类<b>不是</b>线程安全的, 也刻意不加锁: 它由一个仿真循环单线程驱动
 * ({@code runtime} 里的那个推进任务)。每个 {@link EventFabric} 自己的并发语义由
 * 它自己的实现负责 —— 用一把锁把整个世界串起来, 会在第一次出现"投递时回调进世界"
 * 的路径时变成死锁, 而那时候没人会想到是这里。
 *
 * <p><b>拆分之后这条约束要重新说一遍, 因为"三个入口"不等于"三条线程"。</b>
 * §8.5.0 说的"两个调度器"是<b>两套节奏</b>(设备每秒、环境每 10 分钟边界), 而不是
 * "两条线程各改一次这个世界"。§8.5.9 把它落成了一条具体的分工:
 * <table border="1">
 *   <tr><th>入口</th><th>线程</th><th>它对世界做什么</th></tr>
 *   <tr><td>{@link #advanceDevices(Duration, Instant)}</td>
 *       <td>仿真线程(心跳)</td>
 *       <td>写 {@code devices} / {@code lastAdvanceAt} / {@code advanceCount} /
 *           {@code publishedEventCount}, 并向各自的 fabric 投设备事件</td></tr>
 *   <tr><td>{@link #fetchEnvironments(Instant)}</td>
 *       <td><b>刷新线程</b>({@code EnvironmentRefreshJob})</td>
 *       <td><b>什么都不写</b> —— 只读 {@code environments} 与 {@code placeOfHuman},
 *           外呼, 返回一批纯数据的读数</td></tr>
 *   <tr><td>{@link #applyEnvironments(List, Instant)}</td>
 *       <td>仿真线程(由 {@code WorldRuntime} 提交)</td>
 *       <td>写 {@code lastEnvironmentAttemptAt} / {@code publishedEventCount} /
 *           环境的现状, 并投环境事件</td></tr>
 * </table>
 * 也就是说: <b>写世界的永远只有那一条仿真线程</b> —— 前一版那段"两个入口之间不能并发"
 * 的约束没有被放弃, 而是被变成了一个更强的、可以照着接线的事实。
 *
 * <p>让刷新线程能安全地"读世界", 需要的结构改动只有一处:
 * {@link #environments} 与 {@link #placeOfHuman} 这两张被它读的表改成了 copy-on-write
 * (见那两个字段的注释)。其余的表({@code devices} / {@code objects} / {@code places} /
 * {@code fabricsByHuman} / {@code lastEnvironmentAttemptAt})<b>刻意不</b>改 ——
 * 刷新线程碰都不碰它们, 给一张没人并发读的表加 COW 只是把成本买在了一个假想的问题上。
 *
 * <p>仍然必须在装配层回答的问题是: <b>谁把 {@link #applyEnvironments(List, Instant)}
 * 提交到仿真线程上</b>。{@code runtime.EnvironmentRefreshJob} 是节奏的所有者(它只负责
 * 取), 而 {@code WorldRuntime} 拥有那条仿真线程 —— 这条线如果接错(比如把 apply 直接
 * 放在刷新线程上), 症状是"两个写者"那套老毛病, 而这里不会用一把锁去掩盖它
 * (加锁的代价见上一段: 投递回调进世界时就是死锁)。
 */
@Slf4j
public final class World {

    /**
     * 环境默认的刷新节奏 —— 与用户的要求一致: "定期执行比如每分钟或每 10 分钟更新一次
     * 形成一个 event"。取 10 分钟而不是 1 分钟: 真实气象数据的更新频率本来就是十分钟级,
     * 而每分钟抓一次只会得到同一份数据十次, 外加十倍的失败重试噪音。
     */
    public static final Duration DEFAULT_ENVIRONMENT_REFRESH_INTERVAL = Duration.ofMinutes(10);

    // ═══════════════════════════ 注册表 ═══════════════════════════

    /** 全部世界对象, 按 id。{@code LinkedHashMap} 让诊断面板与日志的顺序稳定。 */
    private final Map<String, WorldObject> objects = new LinkedHashMap<>();

    /** 设备世界 —— 这一个映射里的东西<b>有</b>能力。 */
    private final Map<String, Device> devices = new LinkedHashMap<>();

    /** 数字世界的地点 —— 没有能力, 只有身份与坐标。 */
    private final Map<String, Place> places = new LinkedHashMap<>();

    /**
     * 数字世界的环境 —— 没有能力, 只会被外部数据改变。
     *
     * <h2>它为什么是 copy-on-write 的(唯一一处真的为并发改的结构)</h2>
     * §8.5.9 把环境刷新拆成了"刷新线程取数 + 仿真线程应用", 于是这张表成了
     * <b>第一条被两条线程同时看着的表</b>: 刷新线程在 {@link #fetchEnvironments(Instant)}
     * 里遍历它("有哪些地方"), 仿真线程在 {@link #applyEnvironments(List, Instant)}
     * 与 {@link #addEnvironment(Environment)} 里改它。
     *
     * <p>不这么改的后果不是"少一条事件": 一个 {@code LinkedHashMap} 在被遍历的同时
     * 被 {@code put}, 得到的是 {@code ConcurrentModificationException} —— 或者更糟,
     * 是遍历到一半的一条"半个旧、半个新"的桶链, 静默地漏掉一个环境。而它的复现条件
     * 是"装配恰好在 10 分钟边界上发生", 谁都不会往这儿想。
     *
     * <h2>为什么是 copy-on-write 而不是 {@code synchronizedMap}/一把锁</h2>
     * 这张表是<b>读多写极少</b>的: 装配时写几次, 之后每 10 分钟读一次。
     * COW 下读者永远拿到的是一份完整、不可变、顺序稳定的表, <b>零阻塞</b> ——
     * 没有任何一条路径需要在持有锁的时候去投事件(投事件会回调进世界, 那正是
     * {@code synchronizedMap} 会死锁的形状, 见类注释最后一段)。
     * 代价是每次写复制一份表: 装配期无所谓, 而运行期这张表几乎不变。
     *
     * <p>写只有 {@link #addEnvironment} 一处, <b>而它只在装配期被调</b> ——
     * 这条"只有一个写者"的前提没有被放弃, 只是从"一个线程"放宽到
     * "一条线程, 且不是刷新线程"。
     */
    private final AtomicReference<Map<String, Environment>> environments =
            new AtomicReference<>(emptyOrderedMap());

    // ═══════════════════════════ 座位表与通道 ═══════════════════════════

    /** 往哪投 —— 每一个 Human 一条通道。 */
    private final Map<String, EventFabric> fabricsByHuman = new LinkedHashMap<>();

    /**
     * 她此刻在哪 —— 环境事件"投给谁"的唯一依据。
     *
     * <p>与 {@link #environments} 同一个理由、同一套写法: 刷新线程在
     * {@link #fetchEnvironments(Instant)} 里用它判断"这个地点有没有人"
     * (那是配额过滤器), 而仿真线程在 {@link #placeHumanAt} / {@link #unbind} 里改它。
     * 换人的频率远高于换地点, 所以这张表的 COW 比分环境那张更值得 ——
     * 一次搬家复制一张"人 → 地点"的小表, 买到的是"刷新线程永远看到一个自洽的座位表"。
     *
     * <p>顺带解决了一件事: {@link #unbind} 的"先忘位置、再摘通道"那个两步窗口
     * (见它的注释)对刷新线程<b>不再存在</b> —— 它要么看到两步之前的整张表, 要么
     * 看到两步之后的。那两步之间的一致性对仿真线程自己仍然是必须的(它读两张表)。
     */
    private final AtomicReference<Map<String, String>> placeOfHuman =
            new AtomicReference<>(emptyOrderedMap());

    /**
     * 每个环境上一次<b>尝试</b>刷新的时刻 —— 刷新节奏靠它算, 而<b>不</b>靠环境自己记
     * (它不该知道 tick 这回事)。
     *
     * <p>记"尝试"而不是"成功": 失败也必须占用这个节奏, 否则一条挂掉的气象 API
     * 会让世界在每个 tick 都重试一次。详见 {@link #refreshOne}。
     */
    private final Map<String, Instant> lastEnvironmentAttemptAt = new LinkedHashMap<>();

    private Duration environmentRefreshInterval = DEFAULT_ENVIRONMENT_REFRESH_INTERVAL;

    // ═══════════════════════════ 统计 ═══════════════════════════

    private Instant lastAdvanceAt;
    private int advanceCount;
    private int publishedEventCount;
    private int failedRefreshCount;

    /** 一个空世界 —— 装配代码从它开始 {@code addDevice} / {@code addPlace} / {@code bind}。 */
    public World() {
        // 刻意不做任何默认装配: 一个"自带一台手机一个地点"的世界会让
        // "她到底有几个家"这个问题有一个平台替她回答的答案
    }

    // ═══════════════════════════ 装配: 有什么 ═══════════════════════════

    /**
     * 把一台设备装进世界 —— <b>它因此获得了能力, 也就进入了 agent 的可达范围</b>。
     *
     * <p>为什么有 {@code addDevice} / {@code addPlace} / {@code addEnvironment} 三个
     * 分开的方法, 而不是一个 {@code add(WorldObject)} 内部做 {@code instanceof} 分派:
     * 因为"这个对象属于哪一半"这件事, <b>由它自己实现的接口声明</b> ——
     * 调用 {@code addDevice} 就是在说"它是设备世界的一员"。
     * 一个内部做类型判断的 {@code add} 会把同一个判断写在两个地方(调用点与实现里),
     * 而它们迟早会不一致 —— 那正是 P5("用能力接口取代类型判断")要消灭的形状。
     *
     * @return {@code this} —— 装配可以链式写
     */
    public World addDevice(Device device) {
        Objects.requireNonNull(device, "要加进世界的设备不能为空");
        String id = device.id().value();
        Device existing = devices.get(id);
        if (existing != null) {
            // 重复装配同一个对象是幂等的(测试与重连会走到这里), 但用同一个 id 装了
            // <b>两个不同的东西</b>是装配错误: 能力表里会出现两条同 key 的能力,
            // 而"哪一台手机响应"取决于装配顺序 —— 那种 bug 没人能复现
            if (existing == device) {
                return this;
            }
            throw new IllegalArgumentException(
                    "设备 id " + id + " 已经被 " + existing.describe() + " 占用 —— "
                            + "同一个 id 装两台不同的设备会让能力表无法回答"
                            + "「这条 chat.send-message 是哪一台的」");
        }
        registerObject(device);
        devices.put(id, device);
        log.info("[World] 装入设备 {} (能力 {} 项)", device.describe(), device.capabilities().size());
        return this;
    }

    /**
     * 把一个地点装进世界。
     *
     * <p>地点<b>没有</b>能力, 所以它不是"装进来就能被她操作"的东西 ——
     * 装进来只意味着"她可以去那儿", 以及"她那儿的天气有一个查询点"。
     */
    public World addPlace(Place place) {
        Objects.requireNonNull(place, "要加进世界的地点不能为空");
        String id = place.id().value();
        Place existing = places.get(id);
        if (existing != null) {
            if (existing == place) {
                return this;
            }
            throw new IllegalArgumentException(
                    "地点 id " + id + " 已经被 " + existing.describe() + " 占用");
        }
        registerObject(place);
        places.put(id, place);
        log.info("[World] 装入地点 {}", place.describe());
        return this;
    }

    /**
     * 把一个环境装进世界。
     *
     * <p>环境自己带 {@code place()}, 所以世界<b>不需要</b>再维护一张
     * {@code environment → place} 的表 —— 那张表会在某一次地点重装之后与
     * {@link Environment#place()} 不一致, 而症状是"她到了实验室, 收到的还是家里的温度"。
     *
     * <p>装进来的环境还要由地点持有({@code Place.attachEnvironment})才能被
     * {@link Place#environment()} 找到 —— 这是<b>两处引用同一件事</b>, 而这里是刻意的:
     * 世界用它做刷新与投递, 地点用它回答"这个地方的天气是什么"。
     * 装配一次、两边都指向同一个对象, 比让世界当地点的"环境查询代理"要好 ——
     * 后者会让"这个地方的天气"必须问世界才拿得到, 而地点是更自然的持有者。
     */
    public World addEnvironment(Environment environment) {
        Objects.requireNonNull(environment, "要加进世界的环境不能为空");
        String id = environment.id().value();
        Environment existing = environmentTable().get(id);
        if (existing != null) {
            if (existing == environment) {
                return this;
            }
            throw new IllegalArgumentException(
                    "环境 id " + id + " 已经被 " + existing.describe() + " 占用");
        }
        putEnvironment(environment);
        log.info("[World] 装入环境 {}", environment.describe());
        return this;
    }

    /**
     * 装进一个<b>不属于上面三类</b>的世界对象(墙上的画、衣柜、地铁闸机)。
     *
     * <p>它只进"有什么"那张表, <b>刻意不</b>尝试判断它是不是设备或地点 ——
     * 那需要 {@code instanceof}, 而"它属于哪一半"应当由它实现的接口来声明。
     * 想让它具备能力, 就让它实现 {@code Device} 并用 {@link #addDevice} 装进来。
     *
     * <p>这个方法存在的意义是<b>给未来的对象留门</b>(P6: 平台不垄断扩展点):
     * 一个第三方对象类型可以立刻被世界认识、被 {@link #find(String)} 查到、
     * 被事件引用, 而不需要平台先为它加一个容器。
     */
    public World addObject(WorldObject object) {
        Objects.requireNonNull(object, "要加进世界的对象不能为空");
        registerObject(object);
        return this;
    }

    private void registerObject(WorldObject object) {
        String id = object.id().value();
        WorldObject existing = objects.get(id);
        if (existing != null && existing != object) {
            throw new IllegalArgumentException(
                    "对象 id " + id + " 已经被 " + existing.typeId() + " 占用 —— "
                            + "世界里两个对象共用一个 id 会让每一处按 id 的查找都变成一枚硬币");
        }
        objects.put(id, object);
    }

    // ═══════════════ 那两张被刷新线程读的表: copy-on-write 的写法 ═══════════════

    /**
     * 一张空的<b>有序</b>不可变表 —— {@link #environments} 与 {@link #placeOfHuman} 的初值。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.of()}: 后者的迭代顺序不保证,
     * 而这两张表的顺序会出现在日志、诊断面板与"先服务谁"的遍历顺序里 ——
     * 一个每次启动都不同的顺序会让"两次运行的事件序列对比"这种排查手段失效。
     */
    private static <K, V> Map<K, V> emptyOrderedMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>());
    }

    /**
     * 写 {@link #environments} 的<b>唯一</b>一处 —— 见那个字段的注释。
     *
     * <p>{@code Collections.unmodifiableMap} 是包装而不是拷贝, 但那不重要:
     * 底下的那份 {@code next} 在 {@code set} 之后再没有人碰过, 而每一次写都是一份新的。
     */
    private void putEnvironment(Environment environment) {
        Map<String, Environment> next = new LinkedHashMap<>(environments.get());
        next.put(environment.id().value(), environment);
        environments.set(Collections.unmodifiableMap(next));
    }

    /** 写 {@link #placeOfHuman} —— 返回被替换掉的旧值(与 {@code Map.put} 同义)。 */
    private String putPlace(String humanId, String placeId) {
        Map<String, String> next = new LinkedHashMap<>(placeOfHuman.get());
        String previous = next.put(humanId, placeId);
        placeOfHuman.set(Collections.unmodifiableMap(next));
        return previous;
    }

    /** 从 {@link #placeOfHuman} 里摘掉一个人 —— 返回她原来的位置(没有则 null)。 */
    private String removePlace(String humanId) {
        Map<String, String> current = placeOfHuman.get();
        if (!current.containsKey(humanId)) {
            // 没这个人就不必复制一张表 —— 下线一个从没被摆位过的人是很常见的路径
            return null;
        }
        Map<String, String> next = new LinkedHashMap<>(current);
        String previous = next.remove(humanId);
        placeOfHuman.set(Collections.unmodifiableMap(next));
        return previous;
    }

    /**
     * 当前的全部环境 —— 每一处遍历都用它, <b>不要在方法内部反复调 {@code get()}</b>:
     * 两次 {@code get()} 之间装配可能已经换了表, 于是"遍历 A 表、按 B 表查"会读到
     * 一个完全合理的错答案。
     */
    private Map<String, Environment> environmentTable() {
        return environments.get();
    }

    private Map<String, String> placeTable() {
        return placeOfHuman.get();
    }

    // ═══════════════════════════ 装配: 谁在哪 ═══════════════════════════

    /**
     * 把一个 Human 的通道交给世界 —— <b>这是世界通往她的唯一一条线</b>。
     *
     * <p>参数是 {@code humanId} 与 {@link EventFabric}, <b>不是</b>一个 {@code Human}:
     * 世界需要知道的只有"往哪投"与"投给谁", 而这两件事就是这两个参数的全部。
     * 传一个 {@code Human} 进来会让世界获得"读她状态"的能力, 而那种能力一旦存在,
     * 就会有人用它("手机响之前先看看她睡没睡") —— 那正是
     * {@code theWorldNeverReachesIntoTheHuman} 要拦下的第一步。
     *
     * <p>重复绑定同一个人: 同一个 fabric 再绑一次是幂等的; 换了一个 fabric
     * (她重启了运行时)则<b>替换</b>并打一条 WARN。替换是必要的 —— 不替换会让
     * 那个人的世界事件投进一条已经没人听的旧通道, 而症状是"她好像对天气毫无反应"。
     */
    public World bind(String humanId, EventFabric fabric) {
        Objects.requireNonNull(humanId, "绑定通道必须说明是谁的通道");
        Objects.requireNonNull(fabric, "绑定通道必须给一条 EventFabric");
        EventFabric existing = fabricsByHuman.put(humanId, fabric);
        if (existing == null) {
            // 刻意不在这里打印 fabric.describe() —— 它<b>需要时刻</b>(账本结算依赖时刻,
            // 见 EventFabric 的说明), 而装配这一步手里没有仿真时刻。给一个假的"现在"
            // 会让日志里的结算值对不上任何一天。想知道通道状态, 就在有时刻的地方问
            log.info("[World] 绑定通道 {} → {}", humanId, fabric.humanId());
        } else if (existing != fabric) {
            log.warn("[World] {} 的通道被替换(旧通道将被弃用) —— 若这是重启, 属正常; "
                    + "若不然, 说明有两条运行时在抢同一个人", humanId);
        }
        return this;
    }

    /**
     * 解除绑定并忘掉她在哪(她下线了)。
     *
     * <p>顺序是"先忘位置、再摘通道": 反过来的话, 在两步之间到达的
     * {@link #advance} 会看到"有位置但没有通道", 于是那条环境变化被静默丢掉 ——
     * 一个只在竞态下出现的丢事件。
     */
    public World unbind(String humanId) {
        String placeId = removePlace(humanId);
        fabricsByHuman.remove(humanId);
        log.info("[World] 解除通道 {}(上次所在: {})", humanId, placeId == null ? "未知" : placeId);
        return this;
    }

    /**
     * 让她出现在某个地点 —— <b>移动这件事的唯一入口</b>。
     *
     * <h2>它做三件事, 顺序不能换</h2>
     * <ol>
     *   <li><b>改座位表</b> —— 先改, 因为接下来两件事都以"她已经在新的地方"为前提;</li>
     *   <li><b>投一条 {@code environment.location-changed.v1}</b> —— 她是"到了"才看见
     *       这一幕的, 所以事件的时刻与来源都是新的那个地点
     *       (见 {@link Place#move});</li>
     *   <li><b>立刻刷新新地点的环境</b> —— 这一步是最容易被漏掉、后果最具体的一步:
     *       少了它, 她会在搬家之后<b>继续收到出发地的天气</b>, 直到下一次 10 分钟的
     *       定时刷新为止。那段时间里她"在下雨的城市里觉得天气很好"。</li>
     * </ol>
     *
     * <h2>为什么从世界这一侧调, 而不是由她自己改一个字段</h2>
     * 因为"她在哪"是<b>世界的事实</b>, 不是她的属性: 同一件事还要决定
     * "环境事件投给谁", 而那个判断是世界的。她(C 类: 计划)那一侧有的是
     * "我要去实验室"这个意图, 意图与事实是两件事 —— 意图可能失败。
     *
     * @param humanId 谁在动
     * @param placeId 到哪去。<b>必须是已经装进世界的地点</b>: 拼错的 id 会让她
     *                静默地留在原地, 而那种 bug 的症状是"她明明去了实验室,
     *                收到的还是家里的天气" —— 与本方法第 ③ 步要防的是同一个症状
     * @param now     移动发生的仿真时刻
     * @param reason  为什么移动 —— 自由字符串, 见 {@link Place.LocationChanged}
     * @return {@code this}
     */
    public World placeHumanAt(String humanId, String placeId, Instant now, String reason) {
        Objects.requireNonNull(humanId, "移动必须说明是谁在动");
        Objects.requireNonNull(now, "移动必须带仿真时刻 —— 世界不许读墙上时钟");
        Place target = places.get(placeId);
        if (target == null) {
            throw new IllegalArgumentException(
                    "世界里没有 id 为 " + placeId + " 的地点 —— 她不能去一个不存在的地方, "
                            + "而静默地留在原地会让「她到底在哪」变成一句假话。"
                            + "已知地点: " + places.keySet());
        }

        String previousPlaceId = putPlace(humanId, placeId);
        if (placeId.equals(previousPlaceId)) {
            // 同地重放(上层每个 tick 都可能这么调)不是一次移动 ——
            // 每 tick 投一条"她到了实验室"的历史会让时间轴被噪音填满
            return this;
        }

        Place from = previousPlaceId == null ? null : places.get(previousPlaceId);
        EventFabric fabric = fabricsByHuman.get(humanId);
        if (fabric == null) {
            // 没有通道也照样移动: "她在哪"是世界的事实, 与有没有订阅者是两件事。
            // 只打 DEBUG: 一个还没绑定通道的人在装配阶段被摆位是正常的
            log.debug("[World] {} 移动 {} → {}, 但它没有通道, 事件未投递", humanId,
                    previousPlaceId, placeId);
        } else {
            Place.move(fabric, from, target, reason == null || reason.isBlank() ? "unspecified" : reason, now);
            publishedEventCount++;
        }

        // ③ 到了就立刻查一次天气 —— 见方法注释。
        // 这一次外呼<b>就发生在这条线程上</b>, 这是刻意的: 触发它的是"她刚进门",
        // 是一次性的、由她自己的动作带来的等待, 而不是每 10 分钟一次的周期外呼。
        // 让她等这一下(几百毫秒)换来的是"她进门那一刻的天气是真的" —— 而这个代价
        // 只在搬家时付一次。§8.5.9 拆走的是<b>定时</b>那条路上的外呼
        target.environment().ifPresent(environment -> {
            EnvironmentReport sweep = refreshOne(environment, now);
            log.info("[World] {} 到达 {}, 环境即时刷新: {}", humanId, target.displayName(),
                    sweep.describe());
        });
        return this;
    }

    /** 刷新节奏 —— 装配置器用。空或非正值保持默认。 */
    public World setEnvironmentRefreshInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.warn("[World] 忽略非法的环境刷新节奏 {}, 保持 {}", interval, environmentRefreshInterval);
            return this;
        }
        this.environmentRefreshInterval = interval;
        return this;
    }

    // ═══════════════════════════ 推进 ═══════════════════════════

    /**
     * 让世界走一步 —— <b>设备演化 + 环境刷新 + 环境事件投递</b>。
     *
     * <h2>三步的顺序是刻意的</h2>
     * <pre>{@code
     *   ① 设备演化   device.advance(elapsed, now)     → 电量、闹钟、自动熄屏 → 直接投各自的事件
     *   ② 环境刷新   environment.refresh(now)         → 产出"天气变了"这些事件(还没投)
     *   ③ 环境投递   投给此刻在该地点的人               → 只有世界知道谁在那儿
     * }</pre>
     * ① 在 ② 之前, 因为设备事件是<b>她自己的东西</b>("她的手机响了"), 而环境事件是
     * <b>外面的世界</b>。先内后外让同一 tick 内的事件顺序与她的感知顺序一致 ——
     * 她先注意到手里的手机, 再注意到窗外下雨了。
     *
     * <p>② 与 ③ 分开, 是因为 {@link Environment} 不知道谁在它那儿(它只知道地点),
     * 而"投给谁"需要一个座位表。这个拆分是 {@link EnvironmentRefresh#publishTo}
     * 存在的原因。
     *
     * <h2>哪些环境会被刷新</h2>
     * <b>只有此刻有人的那些。</b>没人的地方查天气是浪费真实 API 配额, 而且
     * "没人在的时候气温变了两度"这件事对任何人都不产生后果 —— 它只会在她的历史里
     * 留下一串她从未经历过的变化。她<b>到了</b>那个地方时会立刻得到一次刷新
     * (见 {@link #placeHumanAt}), 所以"她进门那一刻的天气"永远是对的。
     *
     * <h2>故障隔离</h2>
     * 一台设备的异常<b>不会</b>让这一 tick 剩下的事消失: 每一台设备与每一个环境各自的
     * 异常被就地捕获、计数、记日志。理由很实际 —— "她的手机闹钟崩了"不该导致
     * "整个世界停止推进", 后者的表现是所有设备一起冻住, 而排查的人会先去怀疑时钟。
     *
     * <h2>它现在是<b>两个入口的合成</b></h2>
     * 这个方法内部按原顺序调 {@link #advanceDevices(Duration, Instant)} 与
     * {@link #refreshDueEnvironments(Instant)}, 再把自己那一份 {@link AdvanceReport}
     * 拼出来。之所以保留它而不是让调用方各自去调那两个: 它已经被测试覆盖,
     * 而"离线回放时一口气把世界推到某个时刻"那种用法需要一个不关心节奏的入口 ——
     * 回放里没有调度器, 有的是"把这一段时间补上"。
     *
     * <p>生产路径<b>不该</b>用它: 心跳每秒调 {@code advanceDevices}(纯内存),
     * 环境由 {@code runtime.EnvironmentRefreshJob} 在自己的线程上按对齐后的边界调
     * {@code fetchEnvironments}, 再由 {@code WorldRuntime} 把读数提交给仿真线程上的
     * {@code applyEnvironments} —— 理由见 §8.5.0: 一条挂 30 秒的气象 API 若落在
     * 这条合成路径上, 会让她的一天停半分钟。
     *
     * @param elapsed 距上次推进过了多久。<b>可以为空</b> —— 空则用
     *                {@code lastAdvanceAt} 与 {@code now} 之差推算(第一次推进时为 0)
     * @param now     这一 tick 的仿真时刻
     * @return 这一 tick 的摘要, 给诊断、测试与离线回放用
     */
    public AdvanceReport advance(Duration elapsed, Instant now) {
        AdvanceReport deviceHalf = advanceDevices(elapsed, now);
        EnvironmentReport environmentHalf = refreshDueEnvironments(now);

        AdvanceReport report = new AdvanceReport(deviceHalf.elapsed(), now,
                deviceHalf.deviceEvents(), deviceHalf.deviceFailures(),
                environmentHalf.refreshes(), environmentHalf.events(),
                environmentHalf.failures(), environmentHalf.humansNotified());
        if (report.anythingHappened()) {
            log.debug("[World] {}", report.describe());
        }
        return report;
    }

    /**
     * <b>只推进设备</b> —— 心跳里每秒调一次, <b>纯内存, 不碰网络</b>。
     *
     * <p>它是 §8.5.3 拆出来的第一半。设备事件是"她自己的东西"(手机响了、屏幕灭了),
     * 它们的演化不需要问任何人 —— 所以这一半可以安心地待在每秒一次的心跳里。
     * 环境那一半会外呼, 于是被赶去了 {@link #fetchEnvironments(Instant)}(取)
     * 与 {@link #applyEnvironments(List, Instant)}(用) —— §8.5.9 的两跳,
     * 理由见 §8.5.0: 一次 30 秒的超时不该让她的一天停摆。
     *
     * <h2>"上一次推进时刻"与推进次数为什么属于<b>这一半</b></h2>
     * 拆开之后 {@code lastAdvanceAt} 与 {@code advanceCount} 只能归一边, 而选择不是随便的:
     * <ul>
     *   <li>{@code lastAdvanceAt} 的唯一消费者是 {@link #inferredStep}({@code elapsed}
     *       为空时用它推算这一步走了多久), 而那个时长唯一的去向是
     *       {@code device.advance(step, now)} —— 电量、闹钟、自动熄屏全靠它。
     *       若让 {@link #applyEnvironments(List, Instant)} 也去更新它, 后果是具体的:
     *       每 10 分钟一次的环境刷新会把"上一次推进"拉到刚刚, 于是下一次
     *       {@code advance(null, now)} 推出来的 step 接近 0, 她的手机"一次只掉 0 秒的电" ——
     *       而若反过来说环境刷新<b>不</b>更新它、设备推进更新它, 那么两次心跳之间的
     *       环境刷新不影响设备那一步的时长, 这正是想要的:
     *       <b>设备的时间由设备被推进的频率决定, 与环境被刷新的频率无关</b>;</li>
     *   <li>{@code advanceCount} 是"世界被推了多少个 tick"。心跳(fixedDelay 1 秒)调的是
     *       {@code advanceDevices}, 而环境刷新的节奏是 10 分钟 —— 两者混进同一个计数,
     *       会让 {@code describe()} 里那个数既不是 tick 数也不是刷新轮数。
     *       所以它跟着设备走。环境那一侧的计数在 {@link EnvironmentReport} 里,
     *       以及 {@code EnvironmentRefreshJob} 自己的计数器里。</li>
     * </ul>
     * 代价要说清楚: 一个<b>只</b>调 {@code applyEnvironments} 的世界, 其
     * {@code advanceCount} 会一直是 0。这不是漏计 —— 它说的是"世界一次都没被设备推进过",
     * 而那句话是真的。
     *
     * @param elapsed 距上次推进过了多久。<b>可以为空</b> —— 空则用
     *                {@code lastAdvanceAt} 与 {@code now} 之差推算(第一次推进时为 0)
     * @param now     这一 tick 的仿真时刻
     * @return 设备那一半的摘要: {@code elapsed} / {@code at} / {@code deviceEvents} /
     *         {@code deviceFailures} 有值, 环境那四个字段为 0。返回整个
     *         {@link AdvanceReport} 而不是一个小一号的类型, 是刻意的: 调用方
     *         (心跳、诊断面板)拿到的东西与 {@link #advance} 返回的是<b>同一种</b>,
     *         于是"把两个入口的摘要打印成同一张表"不需要任何适配代码
     */
    public AdvanceReport advanceDevices(Duration elapsed, Instant now) {
        Objects.requireNonNull(now, "推进必须带仿真时刻 —— 世界不许读墙上时钟");
        Duration step = elapsed != null ? elapsed : inferredStep(now);
        if (step.isNegative()) {
            // 时刻倒退(回滚、重放)时把时长当作 0, 而不是把设备"倒着推进"一次。
            // 倒推会让电量增加、闹钟退回未响 —— 那些都不是回滚的语义
            log.warn("[World] 推进时刻倒退({} → {}), 本次按 0 时长推进", lastAdvanceAt, now);
            step = Duration.ZERO;
        }
        advanceCount++;

        int deviceEvents = 0;
        int deviceFailures = 0;
        for (Device device : devices.values()) {
            try {
                deviceEvents += device.advance(step, now);
            } catch (RuntimeException e) {
                deviceFailures++;
                log.error("[World] 设备 {} 推进失败, 本 tick 跳过它", device.id().value(), e);
            }
        }

        // 见方法注释: "上一次推进时刻"属于设备这一半 —— 环境刷新不碰它
        publishedEventCount += deviceEvents;
        lastAdvanceAt = now;

        AdvanceReport report = new AdvanceReport(step, now, deviceEvents, deviceFailures, 0, 0, 0, 0);
        if (report.anythingHappened()) {
            log.debug("[World] {}", report.describe());
        }
        return report;
    }

    /**
     * <b>取</b>一轮环境数据 —— 外呼全在这里, 而它<b>一个字都不改这个世界</b>(§8.5.9)。
     *
     * <h2>为什么"取"和"用"是两条路, 而不是一个方法</h2>
     * 因为它们是两件事, 挂在两条不同的线程上, 而各自都有一条不能破的约束:
     * <table border="1">
     *   <tr><th></th><th>哪条线程</th><th>为什么必须是它</th></tr>
     *   <tr>
     *     <td>{@code fetchEnvironments}</td>
     *     <td>刷新线程 —— {@code EnvironmentRefreshJob} 那条每 10 分钟一拍的</td>
     *     <td>它外呼, 而一次气象 API 超时可以挂 30 秒。那 30 秒若落在心跳线上,
     *         她的一天就<b>停了半分钟</b>(§8.5.0 那张 12:00:00 的时间轴)</td>
     *   </tr>
     *   <tr>
     *     <td>{@link #applyEnvironments(List, Instant)}</td>
     *     <td>仿真线程 —— 唯一写这个世界的那条</td>
     *     <td>改环境就是改世界, 而本类的前提是<b>只有一个写者</b>(见类注释)。
     *         一个内存里的几微秒操作, 放在心跳上是免费的</td>
     *   </tr>
     * </table>
     * 于是本方法有两条禁令, 两条都是硬约束:
     * <ol>
     *   <li><b>不改任何字段。</b>没有 {@code publishedEventCount += }, 没有
     *       {@code lastEnvironmentAttemptAt.put}, 也没有 {@code failedRefreshCount++}。
     *       那三个数记的是"世界被推进成什么样了" —— 由刷新线程去写它们,
     *       等于在一条没有写者的线程上改世界; 而且"取到了数据"与"数据被用上了"
     *       是两件事, 前者不构成一次成功的刷新(拿到读数却没人交付的窗口里,
     *       把它算成成功会让运维面板显示一切正常)。环境自己那四个字段同理:
     *       {@link Environment#fetch(Instant)} 的契约就是只读;</li>
     *   <li><b>不投事件。</b>投递需要"谁在这个地点", 而座位表可能在这一行的下一纳秒
     *       就变了; 更要紧的是投递会回调进 {@link EventFabric}, 而<b>回调里没有人
     *       保证不碰世界</b>。投递属于 {@link #applyEnvironments(List, Instant)}。</li>
     * </ol>
     * 它读的两张表({@code environments} 与 {@code placeOfHuman})是 copy-on-write 的
     * —— 那正是为了这条路径, 见那两个字段的注释。
     *
     * <h2>它<b>只</b>取"此刻有人的"那些环境</h2>
     * 没人的地方查天气是浪费真实 API 配额, 而"没人在的时候气温变了两度"对任何人都不产生
     * 后果(见 {@link #advance} 的说明)。这个过滤器留在世界这一侧而不是交给调用方:
     * 它是"该不该花这次配额"的判断, 而配额是世界的事 —— 她到家门口时那一次即时刷新
     * (见 {@link #placeHumanAt}) 保证"她进门那一刻的天气"永远是对的。
     *
     * <p>代价要说清楚: 过滤器在<b>取</b>这一侧, 而"谁在"可能在取与用之间变 ——
     * 一个人在边界那一刻在家、在半分钟后(应用之前)出了门, 那么这一份读数仍然会被
     * 应用(环境的数据是准的, 没理由扔), 但投给 0 个人。
     * {@link #applyEnvironments(List, Instant)} 会为这种情况留一条 DEBUG。
     *
     * <h2>故障隔离</h2>
     * 一个环境抛异常<b>不会</b>让这一批剩下的读数消失 —— 与 {@link #advance} 的
     * 故障隔离同一个理由("她的手机闹钟崩了"不该让整个世界停止推进)。那个环境在这一批里
     * 缺席, 于是 apply 那一侧看不到它, 它的计数器也不会动。
     *
     * <p>这个代价也要说清楚: <b>这一批里少了它, 而失败计数不会加</b> —— 加计数是一次写。
     * 所以"取数抛异常"只留下一条 ERROR 日志。会进计数的是"取回来了但失败了"
     * ({@link EnvironmentRefresh#ok()} 为 false 的那一种), 那条路 apply 看得见。
     *
     * @param now 这一轮的仿真时刻。<b>由调用方给</b>, 而且应当是它算边界时用的那一个
     *            (同一个时刻既算边界又记尝试, 两者才会一致)
     * @return 每个"此刻有人的"环境一份读数, 顺序与装配顺序一致(诊断时这一点有用)。
     *         返回 {@code List} 而不是 {@code Map}: 它是一批<b>读数</b>, 不是一张表 ——
     *         顺序对排查有意义("哪一份对应哪个环境"由 id 说清楚), 而按 id 找环境
     *         是 {@link #applyEnvironments(List, Instant)} 的事
     */
    public List<EnvironmentReading> fetchEnvironments(Instant now) {
        Objects.requireNonNull(now, "取环境数据必须带仿真时刻 —— 世界不许读墙上时钟");
        // 只读一次那张表: 见 environmentTable() 的注释
        Map<String, Environment> table = environmentTable();
        List<EnvironmentReading> readings = new ArrayList<>(table.size());
        for (Environment environment : table.values()) {
            if (humansAt(environment.place().value()).isEmpty()) {
                continue;
            }
            try {
                readings.add(new EnvironmentReading(environment.id(), environment.fetch(now)));
            } catch (RuntimeException e) {
                log.error("[World] 环境 {} 取数抛异常 —— 本批跳过它"
                        + "(它不会出现在读数里, 所以 apply 那一侧也不会为它记账)",
                        environment.id().value(), e);
            }
        }
        log.debug("[World] 取环境数据 @ {}: {} 个环境里 {} 个有人, 取回 {} 份读数",
                now, table.size(), readings.size(), readings.size());
        return List.copyOf(readings);
    }

    /**
     * <b>用</b>那一批取回的读数 —— 纯内存, 微秒级, <b>由仿真线程提交</b>(§8.5.9)。
     *
     * <p>它做三件只有世界才知道的事, 顺序是刻意的:
     * <ol>
     *   <li><b>把读数落到环境上</b>({@link Environment#apply(EnvironmentRefresh)})
     *       —— 先落, 因为接下来投出去的事件必须与"落完之后的世界"一致;</li>
     *   <li><b>投给此刻真的在那个地点的人</b> —— 一个需要座位表的判断;</li>
     *   <li><b>记账</b> —— 刷新了几个、投了几条、失败几个、通知了几个人。</li>
     * </ol>
     *
     * <h2>它<b>不</b>自己 gate(与 {@link #advance} 的差别在这里)</h2>
     * 这个方法<b>无条件</b>应用拿到的每一份读数, 不看
     * {@link #refreshDue}(环境, 时刻)。理由: <b>节奏的所有者是调用方</b> ——
     * {@code EnvironmentRefreshJob} 已经用 {@code clock.floorTo(10min)} 算过
     * "这个边界该不该刷", 那是 §8.5.0 指定要接管这件事的地方, 也是唯一用对齐写法
     * 算节奏的地方。若 World 这一层再 gate 一次, 就出现了<b>两个节奏的所有者</b>,
     * 而它们的间隔是各自可配的:
     * <ul>
     *   <li>job 配 10 分钟、World 的 gate 配 1 分钟 —— 现在能跑, 但 World 那个门形同虚设;</li>
     *   <li>job 配 1 分钟、World 的 gate 配 10 分钟 —— job 报告"<b>取回了</b>",
     *       而 World 把九成的读数丢掉了(三个世纪里有两个世纪的边界被静默吃掉)。
     *       这种"上层说做了、下层说没做"的分歧没有任何异常与日志, 只有在对比两个
     *       计数器时才会被发现 —— 那是这个仓最不想留下的那类故障。</li>
     * </ul>
     * {@link #refreshDue} 于是<b>只剩一个消费者</b>: {@link #advance} 那条旧路径
     * (离线回放、已有测试)。它保留漂移写法是刻意的 —— 那条路径的输入是"外部告诉我
     * 过了一个 tick", 而不是"现在落在哪个边界上", 它没有对齐的着力点。
     *
     * <h2>它不碰设备, 也不碰"上一次推进时刻"</h2>
     * 一次只应用环境读数、不推设备的调用<b>不会</b>改变 {@code advanceCount} 与
     * {@code lastAdvanceAt} —— 理由见 {@link #advanceDevices(Duration, Instant)}:
     * 那两个数记的是"设备被推了多少次/被推到何时", 让 10 分钟一次的外呼去改它们,
     * 会让下一次推算出的 step 变成"距上一次环境刷新过了多久", 而她的手机电池
     * 会一次掉 10 分钟的电。
     *
     * <h2>失败与"不认识的读数"都记在这一侧</h2>
     * 为什么失败计数不在取的那一侧加: 加计数是一次写(见
     * {@link #fetchEnvironments(Instant)} 的第 ① 条禁令), 而且"这个环境这一轮到底
     * 算不算失败"要等它真的被应用了才算数。失败的读数在这里让
     * {@code failedRefreshCount++}, 与旧的单跳路径一字不差。
     *
     * <p>一份读数的环境 id 在注册表里找不到(装配期被换过、或者一份读数被交付到了
     * 错误的世界): 记 WARN + 计入 {@code failures}, <b>不</b>计入
     * {@code failedRefreshCount} —— 前者是"这一轮有一份读数没用上", 后者是
     * "某条气象数据链出问题了", 把它们混成一个数会让运维去查一条根本没坏的数据链。
     *
     * @param readings 要应用的读数, 通常是 {@link #fetchEnvironments(Instant)} 的返回值。
     *                 也可以为空或 null —— 那是"这一轮没取到任何东西", 一种正常结果
     * @param now      这一轮的仿真时刻。<b>由调用方给</b>, 且应当与取数那一次同源
     *                 (同一个时刻既落状态又记事件, 两者才会一致)
     * @return 这一轮的摘要, 与 {@link #advance} 里环境那一半的字段同义
     */
    public EnvironmentReport applyEnvironments(List<EnvironmentReading> readings, Instant now) {
        Objects.requireNonNull(now, "应用环境读数必须带仿真时刻 —— 世界不许读墙上时钟");
        if (readings == null || readings.isEmpty()) {
            return new EnvironmentReport(now, 0, 0, 0, 0, 0);
        }

        Map<String, Environment> table = environmentTable();
        int inScope = 0;
        int refreshes = 0;
        int events = 0;
        int failures = 0;
        int humansNotified = 0;

        for (EnvironmentReading reading : readings) {
            Environment environment = table.get(reading.environmentId().value());
            if (environment == null) {
                failures++;
                log.warn("[World] 收到一份世界里没有对应环境的读数({}) —— 丢弃",
                        reading.environmentId().value());
                continue;
            }
            inScope++;
            // "我们试过了"记在应用这一侧 —— 见 refreshOne 的注释: 失败也要占用节奏。
            // 生产路径上节奏的所有者是那个 job, 这里记的只是诊断要看的数
            lastEnvironmentAttemptAt.put(environment.id().value(), now);

            if (!reading.fetched().ok()) {
                // 失败也要过 apply: 环境那四个字段里唯一该动的是失败计数,
                // 而那条规则属于 Environment.apply(见它的注释)
                environment.apply(reading.fetched());
                failedRefreshCount++;
                failures++;
                log.warn("[World] 环境刷新失败: {}", reading.fetched().describe());
                continue;
            }

            environment.apply(reading.fetched());
            refreshes++;

            int audience = 0;
            for (String humanId : humansAt(environment.place().value())) {
                EventFabric fabric = fabricsByHuman.get(humanId);
                if (fabric == null) {
                    continue;
                }
                events += reading.fetched().publishTo(fabric);
                audience++;
            }
            humansNotified += audience;
            if (reading.fetched().hasChanges() && audience == 0) {
                // 变化发生了但没人在那儿 —— 正常情况下走不到这里(取数前已过滤过),
                // 只有"取与用之间她出门了"这种边界会碰到它
                log.debug("[World] 环境 {} 有变化但无人在场, 事件未投递",
                        environment.id().value());
            }
        }

        publishedEventCount += events;
        EnvironmentReport report =
                new EnvironmentReport(now, inScope, refreshes, events, failures, humansNotified);
        if (report.anythingHappened()) {
            log.debug("[World] {}", report.describe());
        }
        return report;
    }

    /**
     * 刷新<b>到期的</b>环境 —— {@link #advance} 那条旧路径用, 保留 {@link #refreshDue}
     * 的漂移门, 于是 {@code advance} 的行为与拆分之前一字不差。
     *
     * <p>刻意是 {@code private}: 除 {@code advance} 之外没有第二个调用方,
     * 而"谁可以决定节奏"这件事只应有一个答案(见
     * {@link #applyEnvironments(List, Instant)})。
     *
     * <p>它走的仍然是<b>单跳</b>(取与用在同一条线程上一个接一个做,
     * 也就是 {@link Environment#refresh(Instant)}) —— 那是刻意的:
     * 这条路径的调用方是离线回放与已有测试, 它们手上就<b>是</b>那条唯一的线程,
     * 没有"另一条线程正等着取数"这回事。§8.5.9 要拆走外呼的是<b>生产的那条心跳</b>,
     * 而不是这条回放路径 —— 拆开它只会让回放的代码去模拟一个它不拥有的调度器。
     */
    private EnvironmentReport refreshDueEnvironments(Instant now) {
        return sweepEnvironments(now, true);
    }

    /**
     * 环境刷新的<b>单跳</b>循环 —— 只给 {@link #advance} 那条旧路径用(见
     * {@link #refreshDueEnvironments(Instant)})。生产路径拆成了
     * {@link #fetchEnvironments(Instant)} 与 {@link #applyEnvironments(List, Instant)}
     * 两跳, 它们各自的那一段循环写在各自的方法里 —— 那段代码之所以没有共用一个
     * 私有方法, 是因为两跳的<b>输入与输出都不同</b>(一批 id + 读数 vs 一批读数 + 一份
     * 摘要), 硬合并出来的那个方法会立刻长出一堆开关, 而它正是上一次把外呼留在心跳上的形状。
     *
     * <p>把"跳过没人的环境"与"跳过没到期的环境"分开成两个 {@code continue},
     * 而不是合成一个复合条件: 它们被跳过时计入的字段不同(前者不计入
     * {@code environmentsInScope} —— 它压根不在这一轮的范围内; 后者计入),
     * 而那个区别正是诊断"这一轮为什么什么都没刷"时要看的东西。
     */
    private EnvironmentReport sweepEnvironments(Instant now, boolean onlyIfDue) {
        Objects.requireNonNull(now, "刷新环境必须带仿真时刻 —— 世界不许读墙上时钟");
        int inScope = 0;
        int refreshes = 0;
        int events = 0;
        int failures = 0;
        int humansNotified = 0;
        for (Environment environment : environmentTable().values()) {
            String placeId = environment.place().value();
            List<String> audience = humansAt(placeId);
            if (audience.isEmpty()) {
                continue;
            }
            inScope++;
            if (onlyIfDue && !refreshDue(environment, now)) {
                continue;
            }
            EnvironmentReport sweep = refreshOne(environment, now);
            refreshes += sweep.refreshes();
            events += sweep.events();
            failures += sweep.failures();
            humansNotified += sweep.humansNotified();
        }

        publishedEventCount += events;
        EnvironmentReport report =
                new EnvironmentReport(now, inScope, refreshes, events, failures, humansNotified);
        if (report.anythingHappened()) {
            log.debug("[World] {}", report.describe());
        }
        return report;
    }

    private Duration inferredStep(Instant now) {
        if (lastAdvanceAt == null) {
            return Duration.ZERO;
        }
        Duration between = Duration.between(lastAdvanceAt, now);
        return between.isNegative() ? Duration.ZERO : between;
    }

    private boolean refreshDue(Environment environment, Instant now) {
        Instant last = lastEnvironmentAttemptAt.get(environment.id().value());
        if (last == null) {
            // 第一次推进必定刷新: 一个装好却从未刷新过的环境里, current() 是装配时给的
            // 那份"大致合理"的初始快照 —— 而它不该被当成观测结果一直用下去
            return true;
        }
        Duration age = Duration.between(last, now);
        return age.isNegative() || age.compareTo(environmentRefreshInterval) >= 0;
    }

    /**
     * 刷一个环境, 并把它的变化投给此刻在那儿的人 —— <b>单跳</b>, 给那条旧路径用。
     *
     * <p>它调的是 {@link Environment#refresh(Instant)}(取 + 用的合成),
     * 而不是分别调 fetch 与 apply: 这条路径上外呼<b>就在调用它的那条线程上</b> ——
     * 那是允许的, 因为调用它是"离线回放推一步"或者"她刚进门"(见
     * {@link #placeHumanAt} 的第 ③ 步), 而不是每 10 分钟一次的周期外呼。
     * 拆开写在这里只会多两行代码和一次对"我是哪条线程"的重复判断。
     *
     * <p>成功/失败两条路径都返回 {@link EnvironmentReport}, 而不是抛异常:
     * "这一轮没拿到数据"是<b>一种正常结果</b>(气象 API 会超时), 它要被计数、
     * 要被看见, 但不该让世界的推进停下来。
     *
     * <p>返回的是 {@code environmentsInScope == 1} 的那一份报告 —— 一个环境的
     * 一轮刷新<b>就是</b>整轮刷新在一个环境上的特例, 于是
     * {@link #sweepEnvironments} 可以直接把它加起来。这里刻意不为单个环境另立一个
     * 类型: 两个字段一模一样的 record 迟早会在某次改动里只剩一个是新的。
     */
    private EnvironmentReport refreshOne(Environment environment, Instant now) {
        // "我们试过了"先记下来 —— 成功与否都算一次尝试。
        // 失败时不记的后果很具体: 气象 API 挂掉的十分钟里, 每个 tick 都会重试一次,
        // 于是一次外部故障被放大成几百次外呼, 而日志会被同一条 WARN 刷满。
        // 重试节奏与刷新节奏相同, 是刻意的: 数据的时效性由 cadence 决定,
        // 而不是由"上一次失败得多快"决定
        lastEnvironmentAttemptAt.put(environment.id().value(), now);

        EnvironmentRefresh refresh;
        try {
            refresh = environment.refresh(now);
        } catch (RuntimeException e) {
            failedRefreshCount++;
            log.error("[World] 环境 {} 刷新异常 —— 沿用旧快照", environment.id().value(), e);
            return new EnvironmentReport(now, 1, 0, 0, 1, 0);
        }
        if (!refresh.ok()) {
            failedRefreshCount++;
            log.warn("[World] 环境刷新失败: {}", refresh.describe());
            return new EnvironmentReport(now, 1, 0, 0, 1, 0);
        }

        int events = 0;
        int audience = 0;
        for (String humanId : humansAt(environment.place().value())) {
            EventFabric fabric = fabricsByHuman.get(humanId);
            if (fabric == null) {
                continue;
            }
            events += refresh.publishTo(fabric);
            audience++;
        }
        if (refresh.hasChanges() && audience == 0) {
            // 变化发生了但没人在那儿 —— 正常情况下走不到这里(刷新前已过滤过),
            // 只有 placeHumanAt 的即时刷新会在"她刚走"这种边界上碰到它
            log.debug("[World] 环境 {} 有变化但无人在场, 事件未投递", environment.id().value());
        }
        return new EnvironmentReport(now, 1, 1, events, 0, audience);
    }

    // ═══════════════════════════ 查询 ═══════════════════════════

    /** 全部世界对象(含设备、地点、第三方对象) —— 诊断用。 */
    public Collection<WorldObject> objects() {
        return Collections.unmodifiableCollection(objects.values());
    }

    /** 按 id 找一个世界对象。 */
    public Optional<WorldObject> find(String objectId) {
        return objectId == null ? Optional.empty() : Optional.ofNullable(objects.get(objectId));
    }

    /** 全部设备 —— <b>这一个集合里的东西有能力</b>。 */
    public Collection<Device> devices() {
        return Collections.unmodifiableCollection(devices.values());
    }

    /** 按 id 找一台设备。 */
    public Optional<Device> device(String deviceId) {
        return deviceId == null ? Optional.empty() : Optional.ofNullable(devices.get(deviceId));
    }

    /** 全部地点。 */
    public Collection<Place> places() {
        return Collections.unmodifiableCollection(places.values());
    }

    /** 按 id 找一个地点。 */
    public Optional<Place> place(String placeId) {
        return placeId == null ? Optional.empty() : Optional.ofNullable(places.get(placeId));
    }

    /** 全部环境。 */
    public Collection<Environment> environments() {
        return Collections.unmodifiableCollection(environmentTable().values());
    }

    /** 按 id 找一个环境。 */
    public Optional<Environment> environment(String environmentId) {
        return environmentId == null ? Optional.empty()
                : Optional.ofNullable(environmentTable().get(environmentId));
    }

    /** 她此刻在哪 —— 空表示"她还没有被摆进世界"。 */
    public Optional<Place> placeOf(String humanId) {
        String placeId = humanId == null ? null : placeTable().get(humanId);
        return placeId == null ? Optional.empty() : Optional.ofNullable(places.get(placeId));
    }

    /** 她此刻在哪(地点 id) —— 给日志与诊断用, 避免为了打印而取一个对象。 */
    public Optional<String> placeIdOf(String humanId) {
        return humanId == null ? Optional.empty() : Optional.ofNullable(placeTable().get(humanId));
    }

    /**
     * 此刻在这个地点的全部人 —— <b>环境事件投向谁的唯一依据</b>。
     *
     * <p>它由 {@link #fetchEnvironments(Instant)}(配额过滤器)与
     * {@link #applyEnvironments(List, Instant)}(投给谁)共用, 而这两次调用发生在
     * 两条不同的线程上、时刻也不同 —— 所以同一个地点在这两次里可能给出不同的答案。
     * 这是<b>对的</b>: 取数问的是"值不值得花这次配额", 投递问的是"此刻谁在这儿"。
     * 两张表各自是一次完整快照(见 {@code placeOfHuman} 的注释), 所以这里不会出现
     * "半个旧表半个新表"的答案 —— 只可能出现"半分钟内她出门了"这种真事。
     */
    public List<String> humansAt(String placeId) {
        if (placeId == null) {
            return List.of();
        }
        List<String> present = new ArrayList<>();
        for (Map.Entry<String, String> entry : placeTable().entrySet()) {
            if (placeId.equals(entry.getValue())) {
                present.add(entry.getKey());
            }
        }
        return List.copyOf(present);
    }

    /** 世界认识的全部 humanId —— 不是"全部人", 而是"被绑进来的人"。 */
    public Collection<String> humans() {
        return Collections.unmodifiableCollection(fabricsByHuman.keySet());
    }

    /** 她的通道 —— 装配与诊断用。世界<b>不会</b>用它去读她的状态, 只往里投。 */
    public Optional<EventFabric> fabricOf(String humanId) {
        return humanId == null ? Optional.empty() : Optional.ofNullable(fabricsByHuman.get(humanId));
    }

    /**
     * 世界此刻能提供的<b>全部能力</b> —— 她(经由 ActionFabric)能对世界做的事, 就是这一串。
     *
     * <p>它是 {@link Device#capabilities()} 的汇总, 而不是一份平台自己维护的清单:
     * 任何一台新装进来的设备(包括第三方实现)都会自动出现在这里, 不需要改世界的代码。
     * 这就是 P6 说的"平台提供默认实现, 但不垄断扩展点"在工具清单上的样子。
     *
     * <p>同一个 key 出现两次是<b>合法</b>的(两台手机各装了一个聊天软件) ——
     * 见 {@link Device#capabilities()}。世界不替它们决定谁优先, 那个决定属于装配。
     */
    public List<Capability> capabilities() {
        List<Capability> all = new ArrayList<>();
        for (Device device : devices.values()) {
            all.addAll(device.capabilities());
        }
        return List.copyOf(all);
    }

    /** 某台设备的能力 —— 诊断与授权面板用。 */
    public List<Capability> capabilitiesOf(String deviceId) {
        return device(deviceId).map(d -> List.copyOf(d.capabilities())).orElseGet(List::of);
    }

    // ═══════════════════════════ 统计 ═══════════════════════════

    /** 推进过多少个 tick。 */
    public int advanceCount() {
        return advanceCount;
    }

    /** 世界累计投出过多少条事件(按收件人计)。 */
    public int publishedEventCount() {
        return publishedEventCount;
    }

    /** 环境刷新失败过多少次 —— 大于 0 说明那条数据链需要看一眼。 */
    public int failedRefreshCount() {
        return failedRefreshCount;
    }

    /** 上一次推进的时刻。 */
    public Optional<Instant> lastAdvanceAt() {
        return Optional.ofNullable(lastAdvanceAt);
    }

    public Duration environmentRefreshInterval() {
        return environmentRefreshInterval;
    }

    /** 世界一览 —— 日志与诊断面板用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("World[")
                .append(objects.size()).append(" 个对象: ")
                .append(devices.size()).append(" 设备 / ")
                .append(places.size()).append(" 地点 / ")
                .append(environmentTable().size()).append(" 环境");
        if (!fabricsByHuman.isEmpty()) {
            // 座位表只读一次 —— 见 placeTable() 的注释
            Map<String, String> seats = placeTable();
            sb.append(" | ").append(fabricsByHuman.size()).append(" 人: ");
            String joined = fabricsByHuman.keySet().stream()
                    .map(humanId -> humanId + "@" + seats.getOrDefault(humanId, "未摆位"))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            sb.append(joined);
        }
        sb.append(" | 推进 ").append(advanceCount).append(" 次, 投出 ")
                .append(publishedEventCount).append(" 条");
        if (failedRefreshCount > 0) {
            sb.append(", 环境刷新失败 ").append(failedRefreshCount).append(" 次");
        }
        return sb.append(']').toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    // ═══════════════════════════ 一次推进的摘要 ═══════════════════════════

    /**
     * 一次 {@link #advance(Duration, Instant)} 的结果。
     *
     * <h2>为什么带这么多计数, 而不是只返回"总共几条事件"</h2>
     * 因为"世界这一 tick 什么都没发生"有四种完全不同的原因, 而它们的处置方式不同:
     * <table border="1">
     *   <tr><th>看起来</th><th>是哪一个</th><th>该怎么办</th></tr>
     *   <tr><td rowspan="4">事件数 0</td>
     *       <td>{@code deviceEvents == 0}</td><td>正常: 没有设备在这一刻越界(电量没跨阈值)</td></tr>
     *   <tr><td>{@code environmentRefreshes == 0}</td>
     *       <td>正常: 还没到 10 分钟, 或者没有人在任何地点(没人就不查天气)</td></tr>
     *   <tr><td>{@code refreshFailures > 0}</td>
     *       <td><b>不正常</b>: 气象数据链断了, 她一直在用旧快照</td></tr>
     *   <tr><td>{@code deviceFailures > 0}</td>
     *       <td><b>不正常</b>: 某台设备推进时抛异常, 这一 tick 它没有演化</td></tr>
     * </table>
     * 一个只返回总数的签名会让上面四行长得一模一样, 于是"世界安静"与"世界坏了"
     * 在监控上无法区分 —— 而那正是这类仿真最难查的一类问题。
     *
     * @param elapsed               这一 tick 推进了多久
     * @param at                    这一 tick 的仿真时刻
     * @param deviceEvents          设备投出的事件数(各自的电量、闹钟、屏幕事件)
     * @param deviceFailures        推进时抛异常的设备数
     * @param environmentRefreshes  这一 tick 真的刷新的环境数
     * @param environmentEvents     环境事件投出数 —— <b>按收件人计</b>:
     *                              两个人在同一地点时, 一条天气变化算两条
     * @param refreshFailures       刷新失败的环境数
     * @param humansNotified        收到了环境事件的人数
     */
    public record AdvanceReport(Duration elapsed, Instant at, int deviceEvents, int deviceFailures,
                                int environmentRefreshes, int environmentEvents,
                                int refreshFailures, int humansNotified) {

        public AdvanceReport {
            Objects.requireNonNull(at, "推进摘要必须带时刻");
            elapsed = elapsed == null ? Duration.ZERO : elapsed;
        }

        /** 这一 tick 她那边会看到几条新事件。 */
        public int totalEvents() {
            return deviceEvents + environmentEvents;
        }

        /** 这一 tick 有故障吗 —— 监控面板上唯一需要报警的那一栏。 */
        public boolean hasFailures() {
            return deviceFailures > 0 || refreshFailures > 0;
        }

        /** 这一 tick 有值得记的事吗。 */
        public boolean anythingHappened() {
            return totalEvents() > 0 || environmentRefreshes > 0 || hasFailures();
        }

        /** 一行摘要 —— 日志用。 */
        public String describe() {
            return "推进 " + elapsed.toSeconds() + "s @ " + at
                    + ": 设备事件 " + deviceEvents
                    + ", 环境刷新 " + environmentRefreshes + " 次/" + environmentEvents + " 条"
                    + ", 通知 " + humansNotified + " 人"
                    + (hasFailures() ? " [设备失败 " + deviceFailures
                    + ", 环境失败 " + refreshFailures + "]" : "");
        }
    }

    /**
     * <b>一份取回的环境读数</b> —— {@link #fetchEnvironments(Instant)} 的产出,
     * 也是 {@link #applyEnvironments(List, Instant)} 的输入(§8.5.9 的两跳之间传的就是它)。
     *
     * <h2>为什么它带的是环境 <b>id</b> 而不是一个 {@link Environment} 引用</h2>
     * 因为这两跳之间隔着一整条线程边界, 而"环境"是一个<b>活的、可变的</b>对象:
     * 带着引用过去等于让刷新线程顺手就能改它(而它不许改), 也等于假装那个对象在
     * 交付的那一刻还在注册表里。带 id 则强制 apply 那一侧<b>重新查一次注册表</b> ——
     * 查不到就是"这个世界里没有它"(装配期换过), 那种情况要被记成失败而不是被
     * 悄悄地应用到一个已经没人持有的对象上。
     *
     * <p>它自身是<b>纯数据</b>: 一个不可变 record 里装着一份不可变的
     * {@link EnvironmentRefresh}(快照是 record, 事件列表是 {@code List.copyOf})。
     * 于是它可以在两条线程之间传递而不需要任何同步 —— 这条性质是整套拆分的地基:
     * 两个线程之间传的是一份值, 不是一段共享的可变状态。
     *
     * @param environmentId 这份读数属于哪个环境
     * @param fetched       取回来的那一份(成功或失败都在里面 —— 失败也是一种要交付的
     *                      结果, 它要在仿真线程上被记成一次失败, 见
     *                      {@link #applyEnvironments(List, Instant)})
     */
    public record EnvironmentReading(ObjectId environmentId, EnvironmentRefresh fetched) {

        public EnvironmentReading {
            Objects.requireNonNull(environmentId, "一份读数必须说明它属于哪个环境");
            Objects.requireNonNull(fetched, "一份读数里必须有一份取回的结果");
        }

        /** 这一份取到了吗 —— 转发自 {@link EnvironmentRefresh#ok()}。 */
        public boolean ok() {
            return fetched.ok();
        }

        /** 一行摘要 —— 日志与排查用。 */
        public String describe() {
            return "读数[" + environmentId.value() + "] " + fetched.describe();
        }
    }

    /**
     * 一轮环境刷新的结果 —— {@code applyEnvironments} 与 {@code advance} 里
     * 环境那一半的计数来源。
     *
     * <h2>为什么它是公共的, 而字段与当初那个私有的 {@code EnvSweep} 一样</h2>
     * 它原本是一个 {@code private record EnvSweep}, 当时的理由是"世界怎么数环境事件
     * 是实现细节, 不该成为别人可以依赖的东西"。§8.5.3 把这条判断推翻了:
     * 环境刷新被拆成独立节奏之后, <b>调用方(以及看运维面板的人)必须知道
     * 这一轮到底刷了几个环境、投了几条、失败了几条</b> —— 否则"job 说它取回了"
     * 与"世界说它什么都没用上"之间的分歧无从发现, 而那正是
     * {@link #applyEnvironments(List, Instant)} 那段注释里描述的故障。
     *
     * <p>于是真正的边界不再是"公共/私有", 而是<b>作用域</b>: 一份报告要么是
     * 单个环境的一轮({@code environmentsInScope == 1}), 要么是整轮(各字段是求和)。
     * 两者刻意共用同一个类型 —— 字段完全一样, 分成两个类型只会让它们在
     * 某一次改动里分叉。{@link #sweepEnvironments} 把单个环境的报告加起来,
     * 这件事之所以能一行写完, 正是因为这个选择。
     *
     * <p><b>注意它与 {@link EnvironmentReading} 是两个方向上的东西</b>:
     * 读数说的是"外面现在是多少"(取那一侧的产出), 报告说的是"这一轮我们改了几处、
     * 告诉了谁、坏了几个"(用那一侧的产出)。§8.5.9 之前它们是同一件事的两半。
     *
     * <h2>为什么失败也在这份摘要里, 而不是抛出去</h2>
     * 与 {@link AdvanceReport} 同一个理由: "这一轮什么都没发生"有几种完全不同的原因
     * (没人在地点上 / 没到边界 / 气象数据链断了), 而它们的处置方式不同。
     * {@code refreshes == 0 && failures == 0 && environmentsInScope == 0} 是"没人",
     * {@code failures > 0} 是"链断了"。一个只返回条数的签名会让这两行长得一模一样。
     *
     * @param at                  这一轮的仿真时刻
     * @param environmentsInScope 这一轮里<b>真的被应用了读数</b>的环境数(失败的那几个
     *                            也在内)。它大于 {@code refreshes} 的那部分, 就是
     *                            "应用了但没成功"
     * @param refreshes           真的刷新了的环境数
     * @param events              投出去的环境事件数 —— <b>按收件人计</b>:
     *                            两个人在同一地点时, 一条天气变化算两条
     * @param failures            失败的环境数(取数失败 + 读到一份不认识的 id)
     * @param humansNotified      收到了环境事件的人数
     */
    public record EnvironmentReport(Instant at,
                                    int environmentsInScope,
                                    int refreshes,
                                    int events,
                                    int failures,
                                    int humansNotified) {

        public EnvironmentReport {
            Objects.requireNonNull(at, "环境刷新摘要必须带时刻");
        }

        /** 这一轮有值得记的事吗。 */
        public boolean anythingHappened() {
            return refreshes > 0 || events > 0 || failures > 0;
        }

        /** 这一轮有故障吗 —— 与 {@link AdvanceReport#hasFailures()} 同义。 */
        public boolean hasFailures() {
            return failures > 0;
        }

        /** 一行摘要 —— 日志用。 */
        public String describe() {
            return "环境@" + at + ": 刷新 " + refreshes + "/" + environmentsInScope + " 个"
                    + ", 事件 " + events + " 条"
                    + ", 通知 " + humansNotified + " 人"
                    + (failures > 0 ? " [失败 " + failures + " 个]" : "");
        }
    }
}
