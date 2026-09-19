package com.luxera.companion.world.device;

import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.world.object.WorldObject;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.1 —— <b>DigitalDeviceWorld 里的一个对象</b>: 它的方法<b>可以</b>被 Agent 直接触发执行。
 *
 * <h2>它属于哪个世界的唯一判别标准</h2>
 * <pre>
 * Agent 能不能通过一个 ActionCommand 改变它?
 *     ├── 能   → DigitalDeviceWorld  ← 本接口在这里
 *     └── 不能 → DigitalWorld        （Environment / Place ）
 * </pre>
 * 这条标准在代码里不是一句注释, 而是<b>接口形状</b>: 本接口有
 * {@link #capabilities()}(世界对 Agent 暴露的入口), 而 {@code Environment} 没有 ——
 * 给环境一个空的能力集合会让"环境有没有能力"永远有一个"有, 但是空的"的答案。
 *
 * <h2>关于 §4.3.1 里的 {@code execute(DeviceAction, DeviceExecutionContext)}</h2>
 * 设计文档草稿里有这个方法, 但它<b>在已实现的边界层里没有对应物</b> ——
 * {@code boundary/action} 只有 {@code Capability.invoke(ActionCommand, CapabilityContext)},
 * 而 {@code ActionFabric} 的路由键是 {@code capabilityKey}, 不是设备。</p>
 *
 * <p>所以本接口<b>不</b>再提供 {@code execute}: 设备被调用的唯一入口是它贡献的
 * {@link Capability}。多开一个 {@code Device.execute} 会让同一个动作有两条路径
 * ("经 ActionFabric 调能力" vs "直接调设备方法"), 而两条路径中的第二条必然绕过
 * 授权、幂等与审计 —— 那三项正是 ActionFabric 存在的全部理由。
 *
 * <h2>状态为什么是 {@code Map} 而不是强类型</h2>
 * 因为设备的种类是开放领域(P4): 手机有屏幕与电量, 音箱有音量与配对状态, 车有油量,
 * 未来的机器人有电量+姿态+负载。给它们定义一个共同的强类型 {@code DeviceState},
 * 会得到一个 20 个字段、其中 17 个在任何一个具体设备上都是 {@code null} 的类 ——
 * 而那正是"用类型系统假装统一了本来就不同的东西"。
 *
 * <p>{@link #state()} 用 Map 的第二个理由: 它要能直接被塞进
 * {@code device.generic-state-changed.v1} 的载荷({@code from: Map, to: Map})。
 * 强类型对象要先序列化一次才能进事件, 而"为了记一条日志先做一次序列化"是不必要的。
 */
public interface Device extends WorldObject {

    /**
     * 这台设备当前能做什么。
     *
     * <p>返回的是<b>这台设备连同它上面装的应用</b>贡献的全部能力 —— 手机的能力
     * 因此包含 {@code chat.*}(聊天应用)与 {@code device.phone.*}(手机硬件)。
     *
     * <p>刻意返回 {@code Collection} 而不是 {@code Set}: 能力之间存在"同一个 key 在
     * 不同设备上各有一份"的情况(两台手机各装了一个聊天软件), 用 Set 会把这个事实
     * 悄悄吃掉, 而吃掉它的后果是<b>装配顺序决定了哪一台手机响应</b>({@code CapabilityRegistry}
     * 保留先注册的那个)。返回一个集合, 让"有几个"这个问题保持可见。
     */
    Collection<Capability> capabilities();

    /**
     * 电源状态 —— <b>没电的设备什么都做不了</b>(这是 §5.5 里 {@code UNAVAILABLE} 的范例)。
     *
     * <p>它与"能力不存在"必须分开: 手机没电时 {@code chat.send-message} <b>仍然注册在表里</b>,
     * 只是 {@code currentlyAvailable()} 返回 false。合成一件事的后果是: 她"忘了"自己会发消息,
     * 而不是"试着发但发不出去" —— 前者是失忆, 后者是生活。
     */
    PowerState power();

    /**
     * 设备当前状态的快照。Map 形状 —— 见类注释"状态为什么是 Map"。
     *
     * <p>实现应当返回<b>不可变</b>的 Map, 且其中的值必须是可 JSON 化的(数字、字符串、
     * 布尔、嵌套 Map/List)。它是 {@code device.generic-state-changed.v1} 的载荷来源,
     * 也是诊断面板看到的"这台设备现在什么样"。
     */
    Map<String, Object> state();

    /**
     * 把一个通知请求交给这台设备 —— <b>通知链的第 ⑥ 步落点</b>。
     *
     * <h3>为什么要放在 {@code Device} 上, 而不是只留在 {@code Phone} 上</h3>
     * 因为应用跑在<b>某台设备</b>上, 而它不该知道那是一台手机还是一个音箱。聊天应用说
     * "我有新消息", 至于是响铃、亮灯、还是什么都不做, 由<b>设备</b>决定。
     *
     * <p>默认实现<b>什么都不做</b>, 这是刻意的: 一台没有扬声器的冰箱不该因为装了聊天软件
     * 而开始响; 一台墨水屏阅读器可以在收到通知时刷新一次屏幕。默认"不会响"保证了
     * 第三方设备在实现 {@code Device} 时<b>不需要为它没打算支持的能力写空方法</b> ——
     * 而"没实现的功能默认等于没有"正是一个插件边界该有的默认值。
     *
     * <p>实现者<b>不要</b>在这里投递事件: 投什么事件是设备内部的策略(手机投
     * {@code device.phone.notification-raised.v1}, 音箱可能投
     * {@code device.speaker.prompt-started.v1})。本方法只是入口。
     */
    default void notify(NotificationRequest request) {
        Objects.requireNonNull(request, "通知请求不能为空");
        // 默认不响 —— 见方法注释。刻意不打日志: 一台不支持的设备收到通知是常态,
        // 为它每 10 分钟打一行 WARN 会把真正的问题淹掉
    }

    /**
     * 时间推进 —— 设备的<b>自身演化</b>: 电量下降、闹钟到点、屏幕自动熄灭。
     *
     * <p>刻意不是 tick 循环的一部分而是设备自己的方法: 一台关机的设备不需要成本,
     * 而一个"所有设备每个 tick 都被遍历一遍"的世界会在设备数增长时线性变慢。
     * 由 {@code World} 决定推进谁。
     *
     * <p>默认空实现 —— 多数设备(桌子上的灯)不随时间自己改变。
     *
     * <h2>为什么时刻必须是参数, 而不是设备自己去读</h2>
     * 仿真时刻只有一个来源: {@code runtime} 的仿真时钟。设备读一次系统时钟,
     * 世界就多了第二个时间源, 而它的症状很隐蔽 —— 一切照常运行,
     * 只有"把上周三的数据喂回来重跑"时会得到不同的一天。到那时读时钟的地方
     * 已经有几十处了, 而每一处都要单独判断"这个时刻当时是几点"。
     *
     * <h2>为什么<b>没有</b> {@code advance(Duration)} 这个无时刻的重载</h2>
     * 因为"过了多久"算不出"现在几点", 而闹钟、定时免打扰、屏幕超时这三件事
     * 全都需要绝对时刻。一个只收 {@code elapsed} 的接口会逼着实现者在内部
     * 补一个 {@code Instant.now()} —— 也就是说, <b>接口的形状在鼓励违规</b>。
     * 收两个参数没有这个风险, 而调用方(世界)手里两个值都有: 它刚刚从一个
     * 带时刻的 tick 里走出来。
     *
     * <h2>第三方设备怎么办</h2>
     * 收下默认实现即可 —— 默认实现什么都不做, 这与 {@link #notify} 的默认"不响"
     * 是同一条纪律: <b>没实现的功能默认等于没有</b>, 第三方不该被迫写一个空方法。
     * 需要绝对时刻的设备(带闹钟、带定时预约的)直接覆盖本方法。
     *
     * <p>这里<b>刻意没有</b>
     * {@code if (device instanceof Phone)} 这种写法: 那个分支每多一种带定时的设备
     * 就要加一条, 而第三方实现的设备永远拿不到那条分支(P5/P6)。
     *
     * @param elapsed 距离上次推进过了多久(仿真时间)
     * @param now     这一 tick 的仿真时刻
     * @return 这次推进产生了几条世界事件(给诊断与测试用)
     */
    default int advance(java.time.Duration elapsed, Instant now) {
        Objects.requireNonNull(now, "推进必须带仿真时刻 —— 不许读墙上时钟");
        return 0;
    }

    /** 设备此刻可用吗(有电、在线、没被关机)。 */
    default boolean usable() {
        return power().usable();
    }

    // ═══════════════════════════ 电源 ═══════════════════════════

    /**
     * 电源状态。
     *
     * <h2>为什么是连续电量而不是 {@code FULL/LOW/EMPTY} 枚举</h2>
     * 与 {@link AudioVolume} 同一个论证: 电量是连续值, 而"电量低"是一条<b>与她有关</b>的
     * 判断(她急着出门时 30% 就焦虑, 在家时 5% 也不在意)。压成枚举之后, 那个阈值就只能
     * 写死在设备里, 而它属于她的认知。
     *
     * <p>{@link #charging()} 与电平并存而不是做成第三个枚举值: "充电中"与"电量多少"
     * 是两个正交的事 —— "充电中但只有 3%"(刚插上)与"没充电但有 80%"都是需要被区分的状态。
     */
    record PowerState(double level, boolean charging) {

        /** 低于这个电量就"什么都做不了" —— 与真实手机的关机阈值同量级。 */
        public static final double DEPLETED_THRESHOLD = 0.01;

        /** 低于这个电量算"电量焦虑"区。 */
        public static final double LOW_THRESHOLD = 0.15;

        /** 满电。 */
        public static final PowerState FULL = new PowerState(1.0, false);

        public PowerState {
            if (Double.isNaN(level) || level < 0.0 || level > 1.0) {
                throw new IllegalArgumentException(
                        "电量必须在 0..1 之间, 收到 " + level + " —— "
                                + "越界的电量会让没电与满电的判定同时失效");
            }
        }

        public static PowerState of(double level, boolean charging) {
            return new PowerState(level, charging);
        }

        /** 没电了 —— 设备对外的能力全部转为 {@code UNAVAILABLE}。 */
        public boolean depleted() {
            return level <= DEPLETED_THRESHOLD;
        }

        /** 电量低 —— 会让"手机快没电了"成为一条持续压力(见 {@code device.phone.battery-changed.v1})。 */
        public boolean low() {
            return level <= LOW_THRESHOLD;
        }

        /** 设备此刻能不能干活。 */
        public boolean usable() {
            return !depleted();
        }

        public PowerState drainedBy(double delta) {
            return new PowerState(Math.max(0.0, level - Math.max(0.0, delta)), charging);
        }

        public PowerState chargedBy(double delta) {
            return new PowerState(Math.min(1.0, level + Math.max(0.0, delta)), charging);
        }

        public PowerState asCharging(boolean nowCharging) {
            return new PowerState(level, nowCharging);
        }

        public int percent() {
            return (int) Math.round(level * 100);
        }

        @Override
        public String toString() {
            return percent() + "%" + (charging ? "(充电中)" : "")
                    + (depleted() ? "[已关机]" : low() ? "[电量低]" : "");
        }
    }

    // ═══════════════════════════ 接近程度 ═══════════════════════════

    /**
     * 设备离她有多近 —— <b>决定"响了但她听不听得见"</b>。
     *
     * <h2>为什么这是一组字符串常量而不是枚举</h2>
     * 因为它的取值<b>会长</b>: 今天有手/桌/包/另一个房间, 明天会有"在车里"、"在另一个人的手里"、
     * "连着蓝牙耳机"。而 P4 禁的正是"枚举承担领域扩展职责"。所以这里学
     * {@code CoreEventCatalog.Channels} 的做法: 常量给出<b>平台认识的</b>取值, 语法保持开放。
     *
     * <p>未知取值的回退因子是 {@link #UNKNOWN_FACTOR}(不是 0): 一个第三方设备报了一个
     * 我们没见过的位置时, 让"她可能听得见"比让"她确定听不见"安全 —— 后者会静默地
     * 吞掉一条本该惊动她的刺激。
     *
     * <p>用户对这条链的原始描述: "她睡觉时把手机放在客厅(phoneLocation =
     * 'living-room' → 听不见)"。那个"听不见"就是本因子在 {@link VibrationSystem#perceptibility}
     * 与 {@code device.phone.notification-raised.v1} 的 {@code perceptibility} 字段上的体现。
     */
    final class Proximity {

        private Proximity() {
        }

        /** 拿在手上 —— 任何响动她都会察觉。 */
        public static final String HAND = "hand";

        /** 放在桌上(同一房间) —— 听得见, 但没有触觉感受。 */
        public static final String DESK = "desk";

        /** 在包里 —— 声音被闷住, 震动还在。 */
        public static final String BAG = "bag";

        /** 在另一个房间 —— 隔了墙, 基本上听不见。 */
        public static final String OTHER_ROOM = "other-room";

        /** 不认识的位置时的回退因子 —— 见类注释。 */
        public static final double UNKNOWN_FACTOR = 0.5;

        /**
         * 每个位置的感知因子 —— {@code [0, 1]}, 乘到刺激强度上。
         *
         * <p>表中刻意<b>没有</b> 0.0 以外的"物理隔绝": {@code OTHER_ROOM} 给 0.05 而不是 0,
         * 因为门可能开着、她可能正好路过 —— 一个绝对不可能被听见的设备会让
         * "她为什么没听见"这个问题有一个太干净的答案, 而现实里没有这么干净的答案。
         */
        public static double factorOf(String proximityKey) {
            if (proximityKey == null) {
                return UNKNOWN_FACTOR;
            }
            return switch (proximityKey) {
                case HAND -> 1.0;
                case DESK -> 0.75;
                case BAG -> 0.3;
                case OTHER_ROOM -> 0.05;
                default -> UNKNOWN_FACTOR;
            };
        }

        /** 平台认识的全部取值 —— 给诊断面板与三方设备作者看的清单。 */
        public static List<String> known() {
            return List.of(HAND, DESK, BAG, OTHER_ROOM);
        }
    }

    // ═══════════════════════════ 设备不可用 ═══════════════════════════

    /**
     * {@code device.phone.unavailable.v1} —— 设备不可用了(没电/关机/不在身边)。
     *
     * <h2>它存在的理由只有一个: 让"她没回消息"有一个可查证的解释</h2>
     * 没有这条事件时, 行为分析看到的是"用户发了消息, 她 6 小时没回", 于是推断
     * "她不在乎" —— 而真相可能是"她手机没电了"。<b>一个只能推断出道德结论的系统,
     * 会把仿真研究引向完全错误的方向</b>, 而修复它的成本就是这一条事件。
     *
     * <p>它是 B 类(实时感官)事件, 走触觉通道: 手机没电这件事她是<b>感觉到</b>的
     * (掏出来发现黑屏), 不是"思考出来"的。
     */
    @DomainType("device.phone.unavailable")
    record Unavailable(String deviceId, String reason, Instant since, Instant occurredAt)
            implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "unavailable");

        public Unavailable {
            Objects.requireNonNull(deviceId, "设备不可用事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
            since = since == null ? occurredAt : since;
        }

        public static Unavailable of(String deviceId, String reason, Instant since, Instant at) {
            return new Unavailable(deviceId, reason, since, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return deviceId;
        }

        @Override
        public String modality() {
            return "tactile";
        }

        /**
         * 0.6 —— 比手机响(默认 0.5)急一点, 但远低于火警。
         *
         * <p>理由: "手机没电了"是一件<b>需要现在就处理</b>的事(她要找充电器), 但它的代价
         * 是可控的。给太高会把真正紧急的刺激挤出队列头部。
         */
        @Override
        public double urgency() {
            return 0.6;
        }

        /** 没电这件事会一直持续到她充上电, 所以用来源做折叠键 —— 见 {@link SensoryEvent#foldingKey()}。 */
        @Override
        public String foldingKey() {
            return "device-unavailable:" + deviceId;
        }

        @Override
        public String describe() {
            return "设备 " + deviceId + " 不可用(" + reason + ")";
        }
    }

    // ─────────────────────────── 类型登记 ───────────────────────────

    /**
     * <b>本文件里的 {@link Unavailable} 由本接口自己登记</b> —— 装配层调用。
     *
     * <h2>为什么登记归声明处, 而不是归一个"设备事件总目录"</h2>
     * 设备这一侧<b>没有</b>那样一个类, 而且不该有: 一台设备上有哪几路声音、屏幕会不会亮、
     * 电量怎么变, 是三套子系统各自的事({@link AudioSystem} / {@link ScreenSystem} /
     * {@link BatterySystem}), {@link Phone} 只负责把它们组合起来 ——
     * 它不解析事件名, 也不该被逼着认识每一个载荷的形状。
     * <p>于是这条约定在这里退化成最朴素的形式: <b>谁声明这个 record, 谁登记它</b>。
     * 好处与 {@code ContinuousEffectLedger} 那处一样 —— 类型名与载荷形状在同一个文件里,
     * 不可能不一致; 代价是装配层多几行(五个文件各一行), 而抵住"漏了一行"的仍然是
     * §8.6.6 那条守卫。
     *
     * @param registry 装配层正在拼的那个注册表
     * @return 登记了几条(恒为 1)。可重复调用: 同一个类登记两次是一次空操作
     */
    public static int registerTypes(DomainTypeRegistry registry) {
        Objects.requireNonNull(registry, "注册表不能为空");
        registry.register(Unavailable.class);
        return 1;
    }
}
