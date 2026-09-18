package com.luxera.companion.world;

import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.world.device.Device;
import com.luxera.companion.world.digital.Place;
import com.luxera.companion.world.environment.Environment;
import com.luxera.companion.world.environment.Environment.EnvironmentRefresh;
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
 *     <td>{@link #advance(Duration, Instant)}</td>
 *     <td>时间是世界的, 不是设备自己的。设备没有 tick 循环, 它被推着走</td>
 *   </tr>
 * </table>
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

    /** 数字世界的环境 —— 没有能力, 只会被外部数据改变。 */
    private final Map<String, Environment> environments = new LinkedHashMap<>();

    // ═══════════════════════════ 座位表与通道 ═══════════════════════════

    /** 往哪投 —— 每一个 Human 一条通道。 */
    private final Map<String, EventFabric> fabricsByHuman = new LinkedHashMap<>();

    /** 她此刻在哪 —— 环境事件"投给谁"的唯一依据。 */
    private final Map<String, String> placeOfHuman = new LinkedHashMap<>();

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
        Environment existing = environments.get(id);
        if (existing != null) {
            if (existing == environment) {
                return this;
            }
            throw new IllegalArgumentException(
                    "环境 id " + id + " 已经被 " + existing.describe() + " 占用");
        }
        environments.put(id, environment);
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
        String placeId = placeOfHuman.remove(humanId);
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

        String previousPlaceId = placeOfHuman.put(humanId, placeId);
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

        // ③ 到了就立刻查一次天气 —— 见方法注释
        target.environment().ifPresent(environment -> {
            EnvSweep sweep = refreshOne(environment, now);
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
     * @param elapsed 距上次推进过了多久。<b>可以为空</b> —— 空则用
     *                {@code lastAdvanceAt} 与 {@code now} 之差推算(第一次推进时为 0)
     * @param now     这一 tick 的仿真时刻
     * @return 这一 tick 的摘要, 给诊断、测试与离线回放用
     */
    public AdvanceReport advance(Duration elapsed, Instant now) {
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

        int environmentRefreshes = 0;
        int environmentEvents = 0;
        int refreshFailures = 0;
        int humansNotified = 0;
        for (Environment environment : environments.values()) {
            String placeId = environment.place().value();
            List<String> audience = humansAt(placeId);
            if (audience.isEmpty()) {
                continue;
            }
            if (!refreshDue(environment, now)) {
                continue;
            }
            EnvSweep sweep = refreshOne(environment, now);
            environmentRefreshes += sweep.refreshes();
            environmentEvents += sweep.events();
            refreshFailures += sweep.failures();
            humansNotified += sweep.audience();
        }

        publishedEventCount += deviceEvents + environmentEvents;
        lastAdvanceAt = now;

        AdvanceReport report = new AdvanceReport(step, now, deviceEvents, deviceFailures,
                environmentRefreshes, environmentEvents, refreshFailures, humansNotified);
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
     * 刷一个环境, 并把它的变化投给此刻在那儿的人。
     *
     * <p>成功/失败两条路径都返回 {@code EnvSweep}, 而不是抛异常:
     * "这一轮没拿到数据"是<b>一种正常结果</b>(气象 API 会超时), 它要被计数、
     * 要被看见, 但不该让世界的推进停下来。
     */
    private EnvSweep refreshOne(Environment environment, Instant now) {
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
            return new EnvSweep(0, 0, 1, 0);
        }
        if (!refresh.ok()) {
            failedRefreshCount++;
            log.warn("[World] 环境刷新失败: {}", refresh.describe());
            return new EnvSweep(0, 0, 1, 0);
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
            // 变化发生了但没人在那儿 —— 正常情况下走不到这里(advance 已经过滤过),
            // 只有 placeHumanAt 的即时刷新会在"她刚走"这种边界上碰到它
            log.debug("[World] 环境 {} 有变化但无人在场, 事件未投递", environment.id().value());
        }
        return new EnvSweep(1, events, 0, audience);
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
        return Collections.unmodifiableCollection(environments.values());
    }

    /** 按 id 找一个环境。 */
    public Optional<Environment> environment(String environmentId) {
        return environmentId == null ? Optional.empty()
                : Optional.ofNullable(environments.get(environmentId));
    }

    /** 她此刻在哪 —— 空表示"她还没有被摆进世界"。 */
    public Optional<Place> placeOf(String humanId) {
        String placeId = humanId == null ? null : placeOfHuman.get(humanId);
        return placeId == null ? Optional.empty() : Optional.ofNullable(places.get(placeId));
    }

    /** 她此刻在哪(地点 id) —— 给日志与诊断用, 避免为了打印而取一个对象。 */
    public Optional<String> placeIdOf(String humanId) {
        return humanId == null ? Optional.empty() : Optional.ofNullable(placeOfHuman.get(humanId));
    }

    /** 此刻在这个地点的全部人 —— <b>环境事件投向谁的唯一依据</b>。 */
    public List<String> humansAt(String placeId) {
        if (placeId == null) {
            return List.of();
        }
        List<String> present = new ArrayList<>();
        for (Map.Entry<String, String> entry : placeOfHuman.entrySet()) {
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
                .append(environments.size()).append(" 环境");
        if (!fabricsByHuman.isEmpty()) {
            sb.append(" | ").append(fabricsByHuman.size()).append(" 人: ");
            String joined = fabricsByHuman.keySet().stream()
                    .map(humanId -> humanId + "@" + placeOfHuman.getOrDefault(humanId, "未摆位"))
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
     * 一次环境刷新的内部小结。
     *
     * <p>刻意是 {@code private} 的: 它是本类内部两个方法之间的一个约定,
     * 而不是世界对外承诺的一部分。把它提升为公共类型会让"世界怎么数环境事件"
     * 变成别人可以依赖的东西 —— 而那只是实现细节。
     */
    private record EnvSweep(int refreshes, int events, int failures, int audience) {

        String describe() {
            return refreshes + " 次刷新, " + events + " 条事件, " + audience + " 人收到"
                    + (failures > 0 ? ", " + failures + " 次失败" : "");
        }
    }
}
