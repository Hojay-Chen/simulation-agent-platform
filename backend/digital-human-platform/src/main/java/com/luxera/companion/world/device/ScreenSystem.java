package com.luxera.companion.world.device;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.boundary.event.EventFabric;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V2.2 §4.3.6 —— <b>屏幕系统</b>: 亮着还是灭着、锁没锁、谁在前台。
 *
 * <h2>它为什么与"通知"有关, 又不属于通知</h2>
 * 真实手机收到通知时会<b>亮屏</b> —— 而"她正好在看手机"是一个真实会发生的情况:
 * <pre>
 *   她在刷短视频, 手机亮着, 一条消息进来
 *   → 通知音可能被视频盖住, 但屏幕上弹出了横幅 → 她看见了
 * </pre>
 * 所以{@link NotificationPolicy#wakeScreen()} 是一个独立于响铃模式的开关:
 * 静音模式下屏幕照样会亮。把"亮屏"并进响铃模式会丢掉"静音但她看见了"这个状态,
 * 而它是"她为什么秒回"最常见的解释之一。
 *
 * <h2>它投一条视觉事件, 而"她看不看得见"不由它判断</h2>
 * {@code CoreEventCatalog} 给本系统的产物写得很清楚:
 * <pre>
 *   device.phone.screen-changed.v1  (SENSORY / visual)
 *   载荷: phoneId, screenOn, brightness
 *   语义: 屏幕亮起或熄灭。只有当她看得见手机时才是视觉刺激 ——
 *         手机在包里时这条事件仍然会投, 但 AttentionService 按处境给极低的 salience
 * </pre>
 *
 * <p>这段话里有一个<b>值得单独拎出来的分工</b>: 世界只陈述"屏幕亮了"这个事实,
 * "这算不算一次刺激"由她的注意力那一侧结合处境算。这正是
 * {@code WorldEvent} 类注释里那条边界 —— <b>"同一个刺激对不同处境的人重要程度不同,
 * 所以那个数只能由 Mind 结合自身状态算出来, 不能由世界写在事件上"</b>。
 *
 * <p>曾经考虑过另一种做法: 让本类自己判断"手机在包里就不投事件"。那样做是错的, 有两个理由:
 * <ol>
 *   <li>它要求世界知道"她此刻看不看得见" —— 那是她处境的函数, 而世界没有她的处境
 *       (手机的位置只是手机的位置, 不是她的注意力);</li>
 *   <li>"环境在包里"是一个<b>可能</b>, 不是事实。世界投出事件、注意力的那一侧把它压到
 *       极低 salience, 得到的日志是"她没注意到"; 而不投事件得到的日志是"什么都没发生" ——
 *       <b>后者抹掉了一次可解释的机会</b>。</li>
 * </ol>
 *
 * <h2>{@link #foregroundApplication()} 为什么在这里</h2>
 * 因为"她正开着哪个应用"决定了通知<b>弹不弹横幅</b>: 她正在聊天软件里时, 新消息
 * 直接出现在会话里, 不需要再弹一条。它只是屏幕系统的一格状态, 不是应用注册表 ——
 * 应用注册表在 {@code Phone#applications()}。这两者刻意分开: 一个应用可以在后台装着
 * (注册表里有, 前台没有), 而"装了什么"与"在看什么"是两个不同的问题。
 *
 * <h2>亮屏与解锁是两件事</h2>
 * 锁屏时收到通知会亮屏但<b>保持锁定</b> —— 屏幕上只显示"某应用来了一条通知",
 * 看不到正文。这不是安全细节, 而是本设计在物理设备上的对应物:
 * <b>通知不携带内容</b>这条纪律, 在真机上就表现为"锁屏上看不到内容"。
 * 若 {@link #wake} 顺手解了锁, 那么每次通知都等于把手机递到她眼前。
 */
public interface ScreenSystem {

    /** 屏幕是亮着的吗。 */
    boolean isOn();

    /** 锁屏了吗 —— 锁屏时收到通知会先亮屏, 而"她要不要解锁去看"是她自己的决定。 */
    boolean isLocked();

    /**
     * 屏幕亮度 —— {@code [0, 1]}。
     *
     * <p>它是连续值而不是 {@code DIM/BRIGHT} 枚举, 与 {@link AudioVolume} 同一个论证:
     * "太暗了看不清"是一条<b>与她有关</b>的判断(深夜她调得很暗, 阳光下她会调到最亮),
     * 而阈值属于她的认知。它也是"她是不是在偷偷看手机"的一条线索。
     */
    double brightness();

    /** 前台应用 key; 没有(锁屏或桌面)时为 {@code null}。 */
    String foregroundApplication();

    /**
     * 点亮屏幕, <b>并投一条视觉世界事件</b>。
     *
     * <p>时刻是参数而不是读系统时钟 —— 与
     * {@link VibrationSystem#vibrate(VibrationSystem.VibrationPattern, Instant)}
     * 同一条纪律, 论证见那里。这里曾经有过一个 {@code wake(reason)} 的默认方法,
     * 它内部调 {@code Instant.now()}; 它被删掉了, 因为屏幕状态事件是
     * <b>她"看没看见"的判据</b> —— 一条带着错误时刻的屏幕事件会让
     * "她当时是醒着的"这个推断在重放里得出不同的答案。
     *
     * @param reason 为什么亮 —— 见 {@link Reasons}。它是<b>字符串而不是枚举</b>,
     *               因为将来会有"相机启动"、"支付确认"、"第三方应用的常亮需求"
     *               这些平台想不到的理由(P4)。这里用常量给出平台认识的取值
     * @param at     亮屏时刻
     */
    void wake(String reason, Instant at);

    /** 她把手机锁了(按了电源键)。屏幕会同时熄灭, 并投一条事件。 */
    void lock(Instant at);

    /** 她解锁了 —— 这通常紧跟在一次 {@code chat.read-messages} 之前。 */
    void unlock(Instant at);

    /** 某个应用切到了前台 —— 由应用自己或她的操作触发。 */
    void moveToForeground(String applicationKey);

    /** 回到桌面 —— 没有前台应用了。 */
    void clearForeground();

    /** 熄屏。 */
    void sleep(Instant at);

    /** 调亮度 —— <b>她自己可以调</b>({@code device.phone.set-brightness})。 */
    void setBrightness(double level);

    /**
     * 时间推进 —— 无操作自动熄屏。
     *
     * <p>它是屏幕系统唯一需要"时间"的行为, 也是把 {@code advance} 挂在设备上而不是
     * 做成全局 tick 循环的理由之一: 一台关着的设备不需要成本。
     *
     * <p>两个参数都要: {@code elapsed} 用来累加"她多久没碰手机了", {@code at} 用来
     * 给自动熄屏那条事件盖时间戳。<b>少一个都不行</b> —— 只有 {@code elapsed} 时,
     * 那条事件只能去读系统时钟, 于是"屏幕什么时候黑的"在重放里会变成另一个时刻,
     * 而"她当时是不是醒着"正是从这条事件推出来的。
     */
    int advance(Duration elapsed, Instant at);

    /** 屏幕状态的快照 —— 进 {@code Device.state()} 与诊断面板。 */
    Map<String, Object> state();

    // ═══════════════════════════ 亮屏原因 ═══════════════════════════

    /**
     * 平台认识的亮屏原因 —— <b>常量, 不是白名单</b>。
     *
     * <p>写成类而不是枚举的理由与 {@code CoreEventCatalog.Channels} 相同:
     * 一个第三方应用完全可以带着自己的理由把屏幕点亮, 而平台不该在类型层面拦住它。
     * 与 {@link NotificationPolicy.RingerMode} 的枚举形成对照 ——
     * 那个是真的固定分类, 这个会长。
     */
    final class Reasons {

        private Reasons() {
        }

        /** 收到通知而亮屏 —— {@link NotificationSystem} 用的就是这个。 */
        public static final String NOTIFICATION = "notification";

        /** 来电而亮屏。 */
        public static final String CALL = "call";

        /** 闹钟到点而亮屏。 */
        public static final String ALARM = "alarm";

        /** 她主动按了电源键/拿起手机 —— 这是她做的, 不是设备做的。 */
        public static final String USER = "user";

        /** 平台认识的全部取值。 */
        public static List<String> known() {
            return List.of(NOTIFICATION, CALL, ALARM, USER);
        }
    }

    // ═══════════════════════════ 事件 ═══════════════════════════

    /**
     * {@code device.phone.screen-changed.v1} —— <b>屏幕状态变了</b>。
     *
     * <h2>为什么带上 {@code reason}</h2>
     * 目录里给的载荷是 {@code phoneId, screenOn, brightness}。本实现多加了一个
     * {@code reason} —— {@code EventTypeId} 的规则允许"加一个可选字段不升版本"。
     *
     * <p>理由是这条:<b>"她自己按亮的"与"手机自己亮的"在认知上是两件事</b>。
     * <pre>
     *   reason = "user"         → 她手里正拿着这台手机, 屏幕上的内容<b>就在她眼前</b>
     *   reason = "notification" → 手机躺在包里亮了一下, 她多半什么都没看见
     * </pre>
     * 少了这个字段, 下游只能靠"当时手机在哪"去猜, 而那个信息未必有 ——
     * 于是"她眼皮底下弹出的横幅她没看见"会变成一条无法解释的记录。
     *
     * <p>{@code brightness} 也一并带上: 它是"她是不是在夜里偷偷看手机"的唯一线索,
     * 而 0 与 0.7 的区别在诊断面板上比 {@code screenOn} 更有信息量。
     */
    @DomainType("device.phone.screen-changed")
    record ScreenChanged(String phoneId, boolean screenOn, double brightness,
                         String reason, Instant occurredAt) implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("device.phone", "screen-changed");

        public ScreenChanged {
            Objects.requireNonNull(phoneId, "屏幕事件必须说明是哪台设备");
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻");
            reason = reason == null || reason.isBlank() ? Reasons.USER : reason;
            if (Double.isNaN(brightness) || brightness < 0 || brightness > 1) {
                throw new IllegalArgumentException(
                        "亮度必须在 0..1 之间, 收到 " + brightness + " —— 它是屏幕的物理量");
            }
        }

        public static ScreenChanged of(String phoneId, boolean screenOn, double brightness,
                                       String reason, Instant at) {
            return new ScreenChanged(phoneId, screenOn, brightness, reason, at);
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
        public String modality() {
            return "visual";
        }

        /**
         * 0.3 —— 低于默认(0.5)。
         *
         * <p>理由: 屏幕亮一下是<b>背景噪声</b>。它每天都在发生几十次(看一眼时间、
         * 消息弹窗、她自己的操作), 给高了会把 {@code RealtimeEventQueue} 的头部
         * 占满, 而真正要找她的那条消息排不进去。
         *
         * <p>给 0.3 而不是 0.05 是因为它<b>不是没有意义</b>: "手机在你眼前亮了一下"
         * 是"她为什么拿起手机"的一个合理解释, 不该被完全压掉。
         */
        @Override
        public double urgency() {
            return 0.3;
        }

        /**
         * 按设备折叠。
         *
         * <p>屏幕<b>不可能同时既亮又灭</b> —— 同一个设备上的屏幕状态永远只有一个当前值。
         * 所以后一条完全覆盖前一条({@code RealtimeEventQueue} 的折叠语义), 队列里
         * 不会因为夜间反复亮灭而堆积。这与通知刻意<b>不</b>折叠形成对照:
         * 手机可以响五次, 但屏幕只能有一个状态。
         */
        @Override
        public String foldingKey() {
            return "screen:" + phoneId;
        }

        @Override
        public String describe() {
            return "设备 " + phoneId + " 屏幕" + (screenOn ? "亮起" : "熄灭")
                    + "(" + reason + ", 亮度 " + brightness + ")";
        }
    }

    // ═══════════════════════════ 默认实现 ═══════════════════════════

    /**
     * 默认实现: 四格状态(亮/锁/亮度/前台) + 状态跃迁时投一条事件。
     *
     * <h2>只投"跃迁", 不投"每一次变化"</h2>
     * 调亮度、切前台应用都不会投事件 —— 它们只在 {@link #state()} 里变化。
     * 只在 {@code 亮↔灭} 的跃迁上投, 是因为那是唯一一件<b>她可能看见</b>的事。
     * 给每一次亮度微调都投一条视觉事件, 会让队列被她自己的手指动作刷屏。
     *
     * <h2>两个亮度为什么必须分开存</h2>
     * {@code brightness} 是"此刻屏幕上有多亮", {@code userBrightness} 是"她把亮度调到了多少"。
     * 熄屏时前者是 0 而后者仍有值 —— 合并成一个字段的后果是: 她熄屏再亮屏之后
     * 亮度会变成 0(黑屏), 或者诊断面板认为"她一整夜都在看手机"。
     */
    @Slf4j
    final class Default implements ScreenSystem {

        /** 亮屏后无操作多久自动熄屏 —— 与真实手机的默认量级一致。 */
        public static final Duration AUTO_SLEEP_AFTER = Duration.ofSeconds(30);

        private final EventFabric eventFabric;
        private final String phoneId;

        private boolean on;
        private boolean locked;
        private double brightness;
        private String foregroundApplication;

        /** 她自己调的亮度 —— 熄屏时仍然保留, 见类注释。 */
        private double userBrightness;

        /**
         * 距离上一次交互过了多久 —— 自动熄屏以它为基准。
         *
         * <h3>为什么是一个累计时长而不是"最近一次交互的时刻"</h3>
         * 因为它可以由 {@link #advance(Duration, Instant)} 单独累加出来, <b>不需要读墙上时钟</b>。
         * 存一个 {@code Instant} 就必须回答"这个 Instant 从哪来", 而唯一不引入墙上时钟的
         * 答案是让每次交互都带一个时刻参数 —— 那会把每一次"她碰了一下屏幕"都变成
         * 一次带时刻的调用, 而屏幕熄不熄其实不需要精确到毫秒。累计值在仿真里更稳:
         * 时间加速十倍时它跟着加速。
         */
        private Duration idle = Duration.ZERO;

        public Default(EventFabric eventFabric, String phoneId, boolean initiallyLocked) {
            this(eventFabric, phoneId, initiallyLocked, 0.7);
        }

        public Default(EventFabric eventFabric, String phoneId, boolean initiallyLocked,
                       double brightnessWhenOn) {
            this.eventFabric = Objects.requireNonNull(eventFabric,
                    "屏幕系统必须有一个投递通道 —— 亮屏这件事要有地方陈述");
            this.phoneId = Objects.requireNonNull(phoneId, "屏幕系统必须知道自己在哪台设备上");
            this.locked = initiallyLocked;
            this.userBrightness = clamp(brightnessWhenOn, "初始亮度");
            // 关着的屏幕亮度是 0 而不是"上一次的值": 一个黑着的屏幕不该报出 0.7 的亮度,
            // 否则诊断面板上"她是不是在看手机"这个判断会永远为真
            this.brightness = 0.0;
            this.on = false;
        }

        private static double clamp(double level, String what) {
            if (Double.isNaN(level) || level < 0 || level > 1) {
                throw new IllegalArgumentException(
                        what + " 必须在 0..1 之间, 收到 " + level + " —— 它是屏幕的物理量");
            }
            return level;
        }

        // ─────────────────────────── 读取 ───────────────────────────

        @Override
        public boolean isOn() {
            return on;
        }

        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public double brightness() {
            return brightness;
        }

        @Override
        public String foregroundApplication() {
            return foregroundApplication;
        }

        // ─────────────────────────── 改变状态 ───────────────────────────

        @Override
        public void wake(String reason, Instant at) {
            Objects.requireNonNull(at, "亮屏必须带时刻 —— 仿真时钟下不许读墙上时钟");
            String why = reason == null || reason.isBlank() ? Reasons.USER : reason;
            boolean wasOff = !on;
            this.on = true;
            this.brightness = userBrightness;
            this.idle = Duration.ZERO;
            // INFO: "屏幕在什么时候亮过"是解释"她看见了什么"的第一手材料
            log.info("[ScreenSystem/{}] 亮屏({}){}", phoneId, why, locked ? ", 仍处于锁定" : "");
            if (wasOff) {
                // 只在 灭→亮 的跃迁上投事件 —— 见默认实现类注释
                publish(true, why, at);
            }
        }

        @Override
        public void lock(Instant at) {
            Objects.requireNonNull(at, "锁屏必须带时刻 —— 仿真时钟下不许读墙上时钟");
            boolean wasOn = on;
            this.locked = true;
            // 锁屏顺手熄屏 —— 这是真实手机的行为(按电源键锁屏即黑屏)
            this.on = false;
            this.brightness = 0.0;
            this.foregroundApplication = null;
            this.idle = Duration.ZERO;
            log.debug("[ScreenSystem/{}] 已锁屏 {}", phoneId, at);
            if (wasOn) {
                publish(false, Reasons.USER, at);
            }
        }

        @Override
        public void unlock(Instant at) {
            Objects.requireNonNull(at, "解锁必须带时刻 —— 仿真时钟下不许读墙上时钟");
            boolean wasOff = !on;
            this.locked = false;
            this.on = true;
            this.brightness = userBrightness;
            this.idle = Duration.ZERO;
            log.info("[ScreenSystem/{}] 已解锁 {} —— 她接下来多半会去看某个应用", phoneId, at);
            if (wasOff) {
                publish(true, Reasons.USER, at);
            }
        }

        @Override
        public void moveToForeground(String applicationKey) {
            if (applicationKey == null || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                        "前台应用 key 不能为空 —— 想表达'回到桌面'请调 clearForeground()");
            }
            // 真实手机: 应用切到前台时屏幕一定是亮的。这里<b>不</b>投事件:
            // 屏幕本来就是亮的, 这不是一次视觉跃迁
            this.on = true;
            if (this.brightness <= 0.0) {
                this.brightness = userBrightness;
            }
            this.idle = Duration.ZERO;
            String previous = this.foregroundApplication;
            this.foregroundApplication = applicationKey;
            if (!applicationKey.equals(previous)) {
                log.debug("[ScreenSystem/{}] 前台应用: {} → {}", phoneId, previous, applicationKey);
            }
        }

        @Override
        public void clearForeground() {
            this.foregroundApplication = null;
        }

        @Override
        public void sleep(Instant at) {
            Objects.requireNonNull(at, "熄屏必须带时刻 —— 仿真时钟下不许读墙上时钟");
            if (!on) {
                return;
            }
            this.on = false;
            this.brightness = 0.0;
            this.idle = Duration.ZERO;
            log.debug("[ScreenSystem/{}] 熄屏", phoneId);
            publish(false, Reasons.USER, at);
        }

        @Override
        public void setBrightness(double level) {
            this.userBrightness = clamp(level, "亮度");
            // 关着的屏幕上调亮度只记住值, 不点亮 —— 真实手机的设置页就是这样。
            // 也不投事件: 亮度不是一种"她看得见/看不见"的跃迁
            if (on) {
                this.brightness = this.userBrightness;
            }
        }

        @Override
        public int advance(Duration elapsed, Instant at) {
            Objects.requireNonNull(at, "推进必须带仿真时刻 —— 自动熄屏也会投事件");
            if (!on || locked || elapsed == null || elapsed.isZero()) {
                return 0;
            }
            this.idle = this.idle.plus(elapsed);
            if (idle.compareTo(AUTO_SLEEP_AFTER) >= 0) {
                log.debug("[ScreenSystem/{}] 无操作 {} 秒, 自动熄屏", phoneId, idle.toSeconds());
                // 自动熄屏也算一次跃迁, 所以它投事件 —— 理由与她的按键一样:
                // 从外面看, "屏幕黑了"是同一件事
                sleep(at);
                return 1;
            }
            return 0;
        }

        /**
         * 投一条屏幕状态事件。
         *
         * <p>用一个私有方法而不是在四处各写一遍 {@code eventFabric.publish(...)}:
         * 四处里迟早会有一处漏掉 {@code reason}, 而漏掉的那个字段正好是
         * "她到底看没看见"的判据。
         */
        private void publish(boolean screenOn, String reason, Instant at) {
            eventFabric.publish(ScreenChanged.of(phoneId, screenOn, brightness, reason, at));
        }

        /**
         * 屏幕状态的快照 —— 进 {@code Device.state()} 与诊断面板。
         *
         * <p>用 {@code LinkedHashMap} + {@code Collections.unmodifiableMap} 而不是
         * {@code Map.copyOf}: 后者<b>不保证迭代顺序</b>(它内部是散列结构),
         * 而字段顺序在这里是有用的 —— 诊断面板的两次快照要做 diff, 顺序一变
         * 每一行都会显示成"改了"。
         *
         * <p>不可变是另一条纪律: 调用方拿着快照改不回设备状态。<b>一条只能读的观测
         * 不该有副作用</b>, 否则"谁把手机调静音了"会变成一道无解的题。
         */
        @Override
        public Map<String, Object> state() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("screenOn", on);
            snapshot.put("locked", locked);
            snapshot.put("brightness", brightness);
            snapshot.put("userBrightness", userBrightness);
            snapshot.put("foregroundApplication", foregroundApplication);
            return java.util.Collections.unmodifiableMap(snapshot);
        }
    }
}
