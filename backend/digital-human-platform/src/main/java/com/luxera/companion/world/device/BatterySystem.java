package com.luxera.companion.world.device;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.registry.CoreEventCatalog;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.boundary.event.EventFabric;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.7 —— <b>电池系统</b>: 电量会掉, 掉了要充。
 *
 * <h2>为什么电量必须是一个会自己变化的量, 而不是一个固定字段</h2>
 * 它是整条链上最容易被做成"常量"的东西 —— 装配手机时给 {@code PowerState(1.0, false)},
 * 然后它就一直是满的。那样做的后果不是"手机永远有电"这么轻:
 * <pre>
 *   真实的场景(用户描述过的): 她半夜刷手机刷到没电, 第二天早上手机是关机的
 *   → 用户发的三条消息全都"没有被察觉"
 *   → 而一个"电量恒为 1"的模型里, 这三条消息只能被解释成"她不在乎"
 * </pre>
 * <b>一个不能没电的手机, 会把"设备故障"这类平凡原因从解释空间里删掉</b>,
 * 只剩下关于她的道德结论。这与 {@link Device.Unavailable} 那条事件是同一个论证。
 *
 * <h2>它投一条 A 类事件, 而不是 B 类</h2>
 * {@code CoreEventCatalog} 把 {@code device.phone.battery-changed.v1} 定为
 * <b>STATE_EFFECT / body.stress</b>:
 * <pre>
 *   电量变化。电量极低时产生轻微持续焦虑 —— 一个现代人才会有的压力源
 * </pre>
 * 这个分类是对的, 而且值得解释为什么它<b>不能</b>是 B 类(实时感官):
 * A 类影响"一直在"({@link StateEffectEvent} 的类注释里那个"外面 3 度"的例子),
 * 而"手机快没电了"正是这样一种状态 —— 它不是"叮"的一下, 是<b>一整晚都在的背景压力</b>。
 * 把它做成 B 类, 她会每掉 1% 就被惊动一次, 而现实里她只是隐隐地不安。
 *
 * <h2>只报"跨过阈值", 不报"每一个百分点"</h2>
 * 账本按 {@code cancellationKey} 替换同族影响, 所以每分钟投一条一模一样的账没有意义;
 * 而每次都投还会把事件表撑大十倍。本实现只在三个阶段跃迁上投:
 * <table border="1">
 *   <tr><th>跃迁</th><th>投什么</th></tr>
 *   <tr><td>电量进入"低"区</td><td>{@code magnitude > 0} 的焦虑影响</td></tr>
 *   <tr><td>电量耗尽</td><td>更强的焦虑影响({@link Device.Unavailable} 由 {@link Phone} 投)</td></tr>
 *   <tr><td>充上电(焦虑解除)</td><td>{@code magnitude = 0} 的<b>同 key</b> 影响 —— 不是"删除"<br>
 *       理由见 {@link StateEffectEvent} 类注释: "她脱掉了"和"她从没穿过"是两件不同的事</td></tr>
 * </table>
 *
 * <h2>生产者为什么写的是 {@code Phone}</h2>
 * {@code CoreEventCatalog} 把本事件的生产者记作 {@code world.device.phone.Phone}。
 * 本类实现后, 实际投递它的是 {@link BatterySystem.Default} —— 这是<b>名称层面的分叉,
 * 不是架构层面的</b>: 电池系统是手机的一个部件, 事件语义("这台手机的电量")
 * 与所有权("电量属于手机")完全一致, 只是写在了更细的粒度上。
 * 所以我们<b>不改目录</b>, 而是把 {@link Device.Unavailable} 的投递留给 {@link Phone}
 * (它才是"手机不可用了"这件事的主语), 两者分工清楚。
 */
public interface BatterySystem {

    /** 当前电量与充电状态。 */
    Device.PowerState power();

    /**
     * 按时间耗电, <b>并在跨过阈值时以仿真时刻陈述它</b>。
     *
     * <h2>为什么它必须收一个 {@code at}, 而不是自己读系统时钟</h2>
     * 因为"电量焦虑"那条事件({@code device.phone.battery-changed.v1})是要落到
     * 时间轴上的历史: 它带着 {@code occurredAt}。若那一刻是从墙上时钟读出来的,
     * 那么把上周三的日志喂回来重放时, 这条事件会带着<b>今天</b>的时刻 ——
     * 于是"她那天下午为什么突然开始找充电器"再也无法复现, 而电量本身明明是对的。
     * <b>只有一条事件带着错误的时刻, 就足以让整段历史失去可复现性。</b>
     *
     * <p>第二个理由是本类自己做不到这件事: 它只知道"过了多久", 不知道"现在是几点"。
     * 把"现在几点"从 {@link Phone} 一路传进来, 是让这个知识<b>只有一处</b>
     * (仿真时钟), 而不是让每一个部件都偷一份系统时间。
     *
     * @param elapsed 距上次推进过了多久。耗电速率由 {@link #drainPerHour()} 决定
     * @param at      这一 tick 的仿真时刻 —— 事件载荷用它
     * @return <b>跨过阈值</b>产生的事件数(0 或 1)。逐百分点返回 1 会让调用方
     *         以为"每次推进都发生了值得记录的事"
     */
    int advance(Duration elapsed, Instant at);

    /** 插上充电器。 */
    void plugIn(Instant at);

    /** 拔掉充电器 —— 绝大多数时间手机是没在充电的。 */
    void unplug(Instant at);

    /**
     * 每小时耗电比例 —— {@code [0, 1]}。
     *
     * <p>它是连续值而不是 {@code FAST/SLOW} 枚举, 理由与文化里的所有"程度"字段一致:
     * 不同设备(手机/平板/手表)的耗电速度不同, 而"刷视频比待机费电"是一个真实的差异 ——
     * 一个枚举会逼着所有设备共用一个数。
     */
    double drainPerHour();

    void setDrainPerHour(double perHour);

    /** 直接设置电量 —— 装配与测试用; 正常运行中它是被时间推着走的。 */
    void setPower(Device.PowerState power);

    /** 是不是正在充电。 */
    default boolean charging() {
        return power().charging();
    }

    /** 彻底没电了吗。 */
    default boolean depleted() {
        return power().depleted();
    }

    Map<String, Object> state();

    // ═══════════════════════════ 焦虑阈值 ═══════════════════════════

    /**
     * 电量焦虑的强度 —— <b>常量, 不是枚举</b>。
     *
     * <p>这些数是 {@code body.stress} 通道上的量纲, 与"外面 3 度"打在
     * {@code body.warmth} 上的数可加。给得很小是因为这条压力远不支配她 ——
     * 一个会因为手机剩 20% 而无法工作的人不是我们要仿真的常态。
     */
    final class Stress {

        private Stress() {
        }

        /** 电量进入"低"区(≤15%)时的持续焦虑。 */
        public static final double AT_LOW = 0.04;

        /** 电量耗尽时的持续焦虑 —— 更高, 因为她此刻<b>联系不到任何人</b>。 */
        public static final double AT_DEPLETED = 0.12;

        /** 充电中手机不在手上时的轻微不安 —— 手机离了手, 但她知道它在充电。 */
        public static final double AT_CHARGING = 0.01;

        /**
         * 这一格电量对应多少焦虑。
         *
         * <p>刻意做成<b>独立函数</b>而不是撒在 {@code advance} 里的几个 if:
         * 装配代码、诊断面板与测试都要问同一个问题"现在该是多少", 而三处各写一遍
         * 迟早会出现"面板显示 0.04 但没有账"这种不一致。
         */
        public static double of(Device.PowerState power) {
            if (power.depleted()) {
                return AT_DEPLETED;
            }
            if (power.charging()) {
                return AT_CHARGING;
            }
            return power.low() ? AT_LOW : 0.0;
        }

        /** 同族影响的身份 —— 见 {@link StateEffectEvent#cancellationKey()}。 */
        public static String cancellationKeyOf(String phoneId) {
            return "device.phone.battery-stress:" + phoneId;
        }

        /** 平台认识的档位 —— 给文档与诊断用。 */
        public static List<Double> known() {
            return List.of(AT_LOW, AT_DEPLETED, AT_CHARGING);
        }
    }

    // ═══════════════════════════ 事件 ═══════════════════════════

    /**
     * {@code device.phone.battery-changed.v1} —— <b>电量或充电状态变了</b>。
     *
     * <h2>它<b>同时</b>是一条账与一条记录</h2>
     * 作为 {@link StateEffectEvent}, 它的 {@link #magnitude()} 是打在
     * {@code body.stress} 上的持续影响; 而 {@link #level()} 与 {@link #charging()}
     * 是诊断面板要看的原始事实。两者放在一条事件里而不是拆成两条, 是因为它们
     * <b>永远是同时变化的</b> —— 拆开会让"电量低"与"焦虑入账"这两件事有可能不同步,
     * 而不同步的那一次就是账本里永远消不掉的一条压力。
     *
     * <p>{@code magnitude} 允许为 0: 那是"她充上电了, 焦虑解除"的表达方式 ——
     * 见 {@link Stress} 与 {@link StateEffectEvent} 的"撤销长什么样"。
     */
    @DomainType("device.phone.battery-changed")
    record BatteryChanged(String phoneId, double level, boolean charging,
                          double magnitude, Instant occurredAt)
            implements StateEffectEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "battery-changed");

        public BatteryChanged {
            Objects.requireNonNull(phoneId, "电量事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            if (Double.isNaN(level) || level < 0 || level > 1) {
                throw new IllegalArgumentException(
                        "电量必须在 0..1 之间, 收到 " + level);
            }
            if (Double.isNaN(magnitude) || magnitude < 0) {
                throw new IllegalArgumentException(
                        "焦虑强度不能为负, 收到 " + magnitude
                                + " —— 0 表示解除, 而“负的焦虑”没有意义");
            }
        }

        public static BatteryChanged of(String phoneId, double level, boolean charging,
                                        double magnitude, Instant at) {
            return new BatteryChanged(phoneId, level, charging, magnitude, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        @Override
        public String sourceObjectId() {
            return phoneId;
        }

        @Override
        public double magnitude() {
            return magnitude;
        }

        /**
         * {@code body.stress} —— 标准通道名, <b>必须</b>用平台约定的那一个。
         *
         * <p>理由见 {@link StateEffectEvent#effectChannel()}: 不同通道的数值会被加在一起,
         * 而两个插件各自发明 {@code "stress"} 与 {@code "anxiety"} 会得到两条互不干扰的账,
         * 表现是"手机快没电了但她毫无压力"。
         */
        @Override
        public String effectChannel() {
            return CoreEventCatalog.Channels.STRESS;
        }

        /**
         * 同族影响的身份 —— 见 {@link BatterySystem.Stress#cancellationKeyOf(String)}。
         *
         * <p>有了它, 手机从 15% 掉到 3% 只<b>替换</b>账本里那一条, 而不是叠加成
         * 四条焦虑; 充电到 20% 时投一条 {@code magnitude = 0} 的同 key 影响, 精准解除。
         */
        @Override
        public String cancellationKey() {
            return Stress.cancellationKeyOf(phoneId);
        }

        /** 永久有效, 直到被同 key 的下一条替换 —— 电量不会"过一会儿自己满"。 */
        @Override
        public Duration duration() {
            return null;
        }

        @Override
        public String describe() {
            return "设备 " + phoneId + " 电量 " + (int) Math.round(level * 100) + "%"
                    + (charging ? "(充电中)" : "") + ", 焦虑 +" + magnitude;
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 默认实现: 按小时速率耗电 + 跨阈值时投一条 A 类影响。
     *
     * <h2>为什么阈值判定不用"上一次的记录"而用"这一次的档位"</h2>
     * {@link #advance} 只在<b>档位变化</b>时投事件。判断"变没变"要拿旧档位比,
     * 而旧档位是从<b>旧的电量</b>现算的 —— 不额外存一个 {@code lastStress} 字段。
     * 存那个字段的代价是两份真相: 一旦有人绕过 {@code setPower} 直接改了电量,
     * 那个字段就会永久地对不上, 而表现是"手机明明快没电了却没有焦虑"。
     */
    @Slf4j
    final class Default implements BatterySystem {

        /** 手机待机耗电的默认速率 —— 约 100 小时从满到空, 与真机同量级。 */
        public static final double DEFAULT_DRAIN_PER_HOUR = 0.01;

        private final EventFabric eventFabric;
        private final String phoneId;

        private Device.PowerState power;
        private double drainPerHour;

        /** 攒不够一个整点的余量 —— 见 {@link #advance}。 */
        private Duration carry = Duration.ZERO;

        public Default(EventFabric eventFabric, String phoneId, Device.PowerState initial) {
            this(eventFabric, phoneId, initial, DEFAULT_DRAIN_PER_HOUR);
        }

        public Default(EventFabric eventFabric, String phoneId, Device.PowerState initial,
                       double drainPerHour) {
            this.eventFabric = Objects.requireNonNull(eventFabric,
                    "电池系统必须有一个投递通道 —— 电量这件事要有地方陈述");
            this.phoneId = Objects.requireNonNull(phoneId, "电池系统必须知道自己在哪台设备上");
            this.power = Objects.requireNonNull(initial, "初始电量不能为空 —— 想表达'满电'请用 PowerState.FULL");
            this.drainPerHour = checkRate(drainPerHour);
        }

        private static double checkRate(double perHour) {
            if (Double.isNaN(perHour) || perHour < 0 || perHour > 1) {
                throw new IllegalArgumentException(
                        "每小时耗电比例必须在 0..1 之间, 收到 " + perHour
                                + " —— 大于 1 表示手机撑不过一小时, 那多半是单位写错了(秒/小时)");
            }
            return perHour;
        }

        @Override
        public Device.PowerState power() {
            return power;
        }

        @Override
        public double drainPerHour() {
            return drainPerHour;
        }

        @Override
        public void setDrainPerHour(double perHour) {
            this.drainPerHour = checkRate(perHour);
        }

        @Override
        public void setPower(Device.PowerState newPower) {
            Objects.requireNonNull(newPower, "电量不能为空");
            this.power = newPower;
        }

        // ─────────────────────────── 时间推进 ───────────────────────────

        @Override
        public int advance(Duration elapsed, Instant at) {
            Objects.requireNonNull(at, "推进必须带仿真时刻 —— 事件里不许出现墙上时钟");
            if (elapsed == null || elapsed.isZero() || elapsed.isNegative()) {
                return 0;
            }
            double stressBefore = Stress.of(power);

            if (power.charging()) {
                // 充电速率固定为"两小时充满" —— 与真机同量级, 且刻意不做成可配置:
                // 没有哪个场景需要模拟快充与慢充的差别
                double gained = elapsed.toMinutes() / 120.0;
                this.power = power.chargedBy(gained);
            } else if (!power.depleted()) {
                // 秒级 tick 下按比例算而不是按整点取整, 否则一次 1 秒的推进
                // 会因为"不够一小时"而永远不耗电 —— 这是最容易写错的一处
                double lost = elapsed.toMillis() / 3_600_000.0 * drainPerHour;
                this.power = power.drainedBy(lost);
            }

            double stressAfter = Stress.of(power);
            if (Double.compare(stressBefore, stressAfter) == 0) {
                // 没跨阈值 —— 不投事件。见接口注释"只报跨过阈值"
                return 0;
            }
            log.info("[BatterySystem/{}] 电量 {} , 焦虑 {} → {}", phoneId, power, stressBefore, stressAfter);
            eventFabric.publish(BatteryChanged.of(phoneId, power.level(), power.charging(),
                    stressAfter, at));
            return 1;
        }

        @Override
        public void plugIn(Instant at) {
            Objects.requireNonNull(at, "插电必须带时刻 —— 仿真时钟下不许读墙上时钟");
            if (power.charging()) {
                return;
            }
            double stressAfter = Stress.of(power.asCharging(true));
            this.power = power.asCharging(true);
            log.info("[BatterySystem/{}] 插上充电器, 当前 {}", phoneId, power);
            // 充电把"电量焦虑"换成了一条极轻的"手机不在手上"的不安 ——
            // 它同样走 Stress.of, 所以这里投什么完全由那一处决定
            eventFabric.publish(BatteryChanged.of(phoneId, power.level(), true, stressAfter, at));
        }

        @Override
        public void unplug(Instant at) {
            Objects.requireNonNull(at, "拔电必须带时刻 —— 仿真时钟下不许读墙上时钟");
            if (!power.charging()) {
                return;
            }
            this.power = power.asCharging(false);
            double stressAfter = Stress.of(power);
            log.info("[BatterySystem/{}] 拔掉充电器, 当前 {}", phoneId, power);
            eventFabric.publish(BatteryChanged.of(phoneId, power.level(), false, stressAfter, at));
        }

        /** 攒下的时间余量 —— 给诊断与测试看, 正常运行中它只影响下一次推进的精度。 */
        public Duration carry() {
            return carry;
        }

        @Override
        public Map<String, Object> state() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("level", power.level());
            snapshot.put("charging", power.charging());
            snapshot.put("depleted", power.depleted());
            snapshot.put("drainPerHour", drainPerHour);
            return Collections.unmodifiableMap(snapshot);
        }
    }
}
