package com.luxera.companion.world.device;

import com.luxera.companion.boundary.action.ActionCommand;
import com.luxera.companion.boundary.action.ActionResult;
import com.luxera.companion.boundary.action.Capability;
import com.luxera.companion.boundary.action.CapabilityDescriptor;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.world.application.DeviceApplication;
import com.luxera.companion.world.object.ObjectId;
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
import java.util.function.BiFunction;

/**
 * V2.2 §4.3 —— <b>手机</b>: DigitalDeviceWorld 里最完整的一个对象。
 *
 * <h2>它是整条通知链的落点</h2>
 * 用户描述的完整因果链:
 * <pre>
 *   ①用户发消息
 *   ②聊天平台落库并判定免打扰(平台侧)
 *   ③产生 NotificationSignal —— <b>不含正文</b>
 *   ④WebSocket 推到 ChatApplication
 *   ⑤ChatApplication 收到
 *   ⑥交给 <b>本类的 notify()</b>  ← Phone 在这里介入
 *   ⑦按本机策略决定: 响铃 / 震动 / 亮屏
 *   ⑧投一条"手机响了"的世界事件(仍无正文)
 *   ⑨EventFabric → Human
 * </pre>
 * 第 ⑥ 步是 {@link #notify(NotificationRequest)}; 第 ⑦⑧ 步在
 * {@link NotificationSystem.Default#receive} 里。本类因此实现了
 * {@link NotificationSystem.SignalContext} —— 通知系统需要"手机在哪、有没有电"
 * 这两个数, 而这两个数的唯一真相就在本类里。
 *
 * <h2>用户点名的三档音量</h2>
 * <blockquote>
 *   我们正常手机有软件通知声音大小、手机铃声声音大小、闹钟声音大小
 * </blockquote>
 * 对应{@link #notificationVolume()} / {@link #ringtoneVolume()} / {@link #alarmVolume()}
 * 三个便捷读取(真正的存储与"怎么发声"在 {@link AudioSystem},
 * 因为音量是<b>被听见</b>这件事的属性, 不是手机壳的属性)。
 * 三路之外还有媒体音量 —— 一共四路, 见 {@link AudioChannel}。
 *
 * <h2>为什么它<b>不</b>直接持有聊天应用的任何东西</h2>
 * 手机不认识应用: {@link #applications()} 里的每个 {@link DeviceApplication}
 * 都是<b>它自己装进来的</b>, 手机只按 {@link DeviceApplication#id()} 记账、
 * 按 {@link DeviceApplication#capabilities()} 把它们的能力一并贡献出去。
 * 用户对这一点的原话是: "把要让 agent 能够使用的三方平台软件, 让他们自己来实现
 * 这个手机应用接口的实现类, 去对接他们的软件 api, 包括我们自己的聊天平台也是同样道理"
 * —— <b>包括我们自己的聊天平台也是同样道理</b>这半句是关键:
 * 平台自带的聊天应用与三方应用走<b>完全相同</b>的那一个接口, 没有内部捷径。
 *
 * <h2>静音模式与免打扰的分工(最容易做错的一处)</h2>
 * <table border="1">
 *   <tr><th>问题</th><th>谁回答</th><th>本类相关的方法</th></tr>
 *   <tr>
 *     <td>这条消息要不要<b>产生</b>通知信号</td>
 *     <td><b>聊天平台</b>(会话级免打扰设置)</td>
 *     <td>不在这里 —— 手机根本收不到被平台拦掉的信号</td>
 *   </tr>
 *   <tr>
 *     <td>收到信号后<b>怎么响</b></td>
 *     <td><b>本机</b>(响铃模式 + 三档音量 + 振动)</td>
 *     <td>{@link #ringerMode()} / {@link #notificationVolume()} / {@link #notifications()}</td>
 *   </tr>
 * </table>
 * 这两层必须分开, 因为它们的粒度与依据都不同: 平台知道"她免打扰的是某个群",
 * 手机知道"她此刻在开会"。各判各的之后, "她没听见"这个状态就有<b>两个都可查证</b>的解释。
 */
@Slf4j
public class Phone implements Device, NotificationSystem.SignalContext {

    /** 手机这个对象类型的标识 —— 与它产生的事件共用 {@code device.phone} 命名空间。 */
    public static final EventTypeId TYPE = EventTypeId.of("device", "phone");

    /** 平台约定的手机能力 key —— 见 §5.7 与 §5.6 的动作目录。 */
    public static final class Capabilities {

        private Capabilities() {
        }

        /** 读一眼手机状态 —— <b>只读</b>, 不会改变任何东西。 */
        public static final String READ_STATE = "device.phone.read-state";

        /** 调音量 —— 四路通道各调各的。 */
        public static final String SET_VOLUME = "device.phone.set-volume";

        /** 设置响铃模式(响铃/振动/静音)与亮屏开关。 */
        public static final String SET_NOTIFICATION_POLICY = "device.phone.set-notification-policy";

        /** 设一个闹钟。 */
        public static final String SET_ALARM = "device.phone.set-alarm";

        /** 取消一个闹钟。 */
        public static final String CANCEL_ALARM = "device.phone.cancel-alarm";

        /** 打开一个应用 —— 它会切到前台并点亮屏幕。 */
        public static final String OPEN_APPLICATION = "device.phone.open-application";

        /** 关掉一个应用(回到桌面)。 */
        public static final String CLOSE_APPLICATION = "device.phone.close-application";

        /** 调屏幕亮度。 */
        public static final String SET_BRIGHTNESS = "device.phone.set-brightness";

        /** 插上充电器。 */
        public static final String PLUG_CHARGER = "device.phone.plug-charger";

        /** 拔掉充电器。 */
        public static final String UNPLUG_CHARGER = "device.phone.unplug-charger";

        /** 平台认识的全部能力 key —— 给文档、诊断与授权面板用。 */
        public static List<String> known() {
            return List.of(READ_STATE, SET_VOLUME, SET_NOTIFICATION_POLICY, SET_ALARM,
                    CANCEL_ALARM, OPEN_APPLICATION, CLOSE_APPLICATION, SET_BRIGHTNESS,
                    PLUG_CHARGER, UNPLUG_CHARGER);
        }
    }

    // ═══════════════════════════ 身份与部件 ═══════════════════════════

    private final ObjectId id;
    private final String displayName;
    private final EventFabric eventFabric;

    private final AudioSystem audio;
    private final VibrationSystem vibration;
    private final ScreenSystem screen;
    private final BatterySystem battery;
    private final NotificationSystem notifications;

    /** 装在这台手机上的应用, 按 id 记账。LinkedHashMap: 装配顺序在诊断面板上要稳定。 */
    private final Map<String, DeviceApplication> applications = new LinkedHashMap<>();

    /** 闹钟: 标签 → 到点时刻。调度放在手机上, 发声交给音频系统(见 {@link AudioSystem.AlarmFired})。 */
    private final Map<String, Instant> alarms = new LinkedHashMap<>();

    /** 手机离她多近 —— 取值见 {@link Device.Proximity}。 */
    private String proximity;

    /** "没电了"只投一次 —— 否则每个 tick 都会投一条不可用事件。 */
    private boolean depletionAnnounced;

    /** 能力清单的缓存 —— 装配完应用之后不再变, 见 {@link #invalidateCapabilities()}。 */
    private List<Capability> cachedCapabilities;

    /**
     * 完整构造器: 五个部件由调用方给。
     *
     * <p>用它而不是让手机自己 new 出五个默认部件, 是为了让"换一个部件"这件事不需要
     * 改手机 —— 比如一块智能手表可能没有 {@code RINGTONE} 通道, 但它的振动系统更精细。
     * 装配便利性由 {@link #standard} 提供。
     */
    public Phone(EventFabric eventFabric, ObjectId id, String displayName,
                 AudioSystem audio, VibrationSystem vibration, ScreenSystem screen,
                 BatterySystem battery, NotificationSystem notifications, String proximity) {
        this.eventFabric = Objects.requireNonNull(eventFabric,
                "手机必须有一条投递通道 —— 它响的时候要有人知道");
        this.id = Objects.requireNonNull(id, "手机必须有身份 —— 事件要说明是哪台设备");
        this.displayName = displayName == null || displayName.isBlank() ? "手机" : displayName;
        this.audio = Objects.requireNonNull(audio, "手机必须有音频系统 —— 三档音量在那里");
        this.vibration = Objects.requireNonNull(vibration, "手机必须有振动系统");
        this.screen = Objects.requireNonNull(screen, "手机必须有屏幕系统");
        this.battery = Objects.requireNonNull(battery, "手机必须有电池系统 —— 没电的手机是另一台手机");
        this.notifications = Objects.requireNonNull(notifications, "手机必须有通知系统");
        this.proximity = proximity == null || proximity.isBlank()
                ? Device.Proximity.DESK : proximity;
    }

    /**
     * 装配一台<b>出厂状态</b>的手机: 四路典型音量 + 振动开 + 锁屏 + 满电。
     *
     * <p>它把五个默认部件接在一起, 并把 {@code this} 交给通知系统当
     * {@link NotificationSystem.SignalContext} —— 于是"手机在哪、有没有电"
     * 只有一处真相, 不会出现"通知系统以为手机在桌上、实际在包里"这种分叉。
     */
    public static Phone standard(EventFabric eventFabric, ObjectId id, String displayName,
                                 String proximity) {
        AudioSystem audio = new AudioSystem.Default(eventFabric, id.value(), Map.of());
        VibrationSystem vibration = new VibrationSystem.Default(true);
        ScreenSystem screen = new ScreenSystem.Default(eventFabric, id.value(), true);
        BatterySystem battery = new BatterySystem.Default(eventFabric, id.value(),
                Device.PowerState.FULL);
        Phone phone = new Phone(eventFabric, id, displayName, audio, vibration, screen, battery,
                null, proximity);
        // 通知系统需要"手机在哪、有没有电", 而这两件事只有 Phone 知道 ——
        // 所以这里把 this 传进去, 而不是再传两个 Supplier(见 SignalContext 的说明)
        NotificationSystem notifications = new NotificationSystem.Default(
                eventFabric, id.value(), audio, vibration, screen, phone);
        return phone.withNotifications(notifications);
    }

    /**
     * 用给定的通知系统重建这台手机 —— 只给 {@link #standard} 用。
     *
     * <p>它存在的唯一理由是 Java 的构造顺序: 通知系统需要 {@code this},
     * 而 {@code this} 必须在构造器里才有。用"先造壳、再补通知系统"两步装配,
     * 比让通知系统从构造器参数里拿两个 {@code Supplier} 更清楚地表达了依赖关系
     * —— 后者在阅读时看不出来"这两个参数是同一个对象的两个侧面"。
     */
    private Phone withNotifications(NotificationSystem replacing) {
        return new Phone(eventFabric, id, displayName, audio, vibration, screen, battery,
                replacing, proximity);
    }

    // ═══════════════════════════ WorldObject ═══════════════════════════

    @Override
    public ObjectId id() {
        return id;
    }

    @Override
    public EventTypeId typeId() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String describe() {
        return displayName + "(" + id.value() + ") " + battery.power()
                + " " + notifications.policy().describe()
                + (applications.isEmpty() ? "" : " 装应用 " + applications.size() + " 个");
    }

    // ═══════════════════════════ Device ═══════════════════════════

    /**
     * 这台手机(连同它装的应用)贡献的全部能力。
     *
     * <p>顺序刻意是"先硬件、后应用": 诊断面板与 LLM 的工具清单里,
     * {@code device.phone.*} 是这台设备<b>本来就有的</b>, 而 {@code chat.*} 是<b>装上去的</b>。
     * 顺序不会改变路由({@code CapabilityRegistry} 按 key 查), 但会改变人读清单时的理解顺序。
     */
    @Override
    public Collection<Capability> capabilities() {
        if (cachedCapabilities == null) {
            List<Capability> all = new ArrayList<>(hardwareCapabilities());
            for (DeviceApplication app : applications.values()) {
                all.addAll(app.capabilities());
            }
            cachedCapabilities = List.copyOf(all);
        }
        return cachedCapabilities;
    }

    @Override
    public PowerState power() {
        return battery.power();
    }

    @Override
    public Map<String, Object> state() {
        // 嵌套 Map 是刻意的: 它是 device.generic-state-changed.v1 的载荷形状
        // ({@code from: Map, to: Map}), 可以直接进事件而不需要再序列化一次
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deviceId", id.value());
        snapshot.put("proximity", proximity);
        snapshot.put("power", battery.state());
        snapshot.put("audio", audio.volumes());
        snapshot.put("notification", Map.of(
                "ringer", notifications.policy().ringer().name(),
                "vibrationEnabled", notifications.policy().vibrationEnabled(),
                "wakeScreen", notifications.policy().wakeScreen(),
                "unread", notifications.totalUnread()));
        snapshot.put("vibration", vibration.state());
        snapshot.put("screen", screen.state());
        snapshot.put("applications", List.copyOf(applications.keySet()));
        snapshot.put("alarms", alarms.size());
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 把一个通知请求交给这台手机 —— <b>通知链第 ⑥ 步的落点</b>。
     *
     * <h3>为什么手机<b>不</b>校验"我装没装这个应用"</h3>
     * 因为 {@code applicationKey} 只是一个<b>标签</b>, 不是一台注册表的键。校验它的后果是:
     * 一个第三方应用必须在手机里先注册才能响, 而"注册"这件事把第三方绑在了宿主上 ——
     * 正是 P4 要消灭的东西。手机要做的只有三件: 有多大声、震不震、屏幕亮不亮。
     *
     * @see NotificationSystem#receive(NotificationRequest)
     */
    @Override
    public void notify(NotificationRequest request) {
        Objects.requireNonNull(request, "通知请求不能为空");
        notifications.receive(request);
    }

    /**
     * 时间推进: 电量下降、闹钟到点、屏幕自动熄灭。
     *
     * <h3>为什么这里<b>只有</b>带时刻的那一个版本</h3>
     * 因为三件事里有两件需要绝对时刻(闹钟到点、事件的时间戳), 而"手机现在几点"
     * 只有仿真时钟知道。曾经有过一个 {@code advance(Duration)} 重载, 它内部写的是
     * {@code advance(elapsed, Instant.now())} —— 那是世界上第二个时钟源, 而且它
     * 恰好会在重放时把每一个闹钟都变成"现在响"。
     *
     * <p>驱动源只有一个: {@code runtime} 的仿真时钟。设备不读时钟, 它被推着走。
     *
     * @return 这次推进产生了几条世界事件 —— 电量跨阈值、闹钟响、手机没电各算一条
     */
    @Override
    public int advance(Duration elapsed, Instant now) {
        Objects.requireNonNull(now, "推进必须带仿真时刻 —— 闹钟与事件都靠它");
        if (elapsed == null || elapsed.isZero() || elapsed.isNegative()) {
            return 0;
        }
        int published = 0;
        published += battery.advance(elapsed, now);
        published += screen.advance(elapsed, now);
        published += fireDueAlarms(now);
        published += announceDepletionIfNeeded(now);
        return published;
    }

    @Override
    public boolean usable() {
        return battery.power().usable();
    }

    // ═══════════════════════════ SignalContext ═══════════════════════════

    /** 手机离她多近 —— 通知系统用它算 {@code perceptibility}。 */
    @Override
    public String proximity() {
        return proximity;
    }

    /** 她把手机放哪了 —— "她睡觉时把手机放在客厅"就是这一句。 */
    public void placeAt(String proximityKey) {
        if (proximityKey == null || proximityKey.isBlank()) {
            throw new IllegalArgumentException(
                    "手机位置不能为空 —— 想表达'不知道在哪'请用 Device.Proximity 里没有的取值, "
                            + "那时感知因子会回退到 UNKNOWN_FACTOR");
        }
        String previous = this.proximity;
        this.proximity = proximityKey;
        if (!proximityKey.equals(previous)) {
            // INFO: "手机当时在哪" 是解释"她为什么没听见"的两个关键事实之一(另一个是静音)
            log.info("[Phone/{}] 位置 {} → {}", id.value(), previous, proximityKey);
        }
    }

    // ═══════════════════════════ 三档音量 ═══════════════════════════

    /** 软件通知音量 —— 用户点名的第一档。 */
    public AudioVolume notificationVolume() {
        return audio.volumeOf(AudioChannel.NOTIFICATION);
    }

    /** 手机铃声音量 —— 用户点名的第二档。 */
    public AudioVolume ringtoneVolume() {
        return audio.volumeOf(AudioChannel.RINGTONE);
    }

    /** 闹钟音量 —— 用户点名的第三档。 */
    public AudioVolume alarmVolume() {
        return audio.volumeOf(AudioChannel.ALARM);
    }

    /** 媒体音量 —— 用户没点名但真实手机有的一路。 */
    public AudioVolume mediaVolume() {
        return audio.volumeOf(AudioChannel.MEDIA);
    }

    public AudioSystem audio() {
        return audio;
    }

    public VibrationSystem vibration() {
        return vibration;
    }

    public ScreenSystem screen() {
        return screen;
    }

    public BatterySystem battery() {
        return battery;
    }

    public NotificationSystem notifications() {
        return notifications;
    }

    /** 现在的响铃模式 —— 静音/振动/响铃。 */
    public NotificationPolicy.RingerMode ringerMode() {
        return notifications.policy().ringer();
    }

    // ═══════════════════════════ 应用装着的地方 ═══════════════════════════

    /**
     * 装一个应用到这台手机上。
     *
     * <p>装的过程<b>只有三步</b>: 交给应用一个"你在哪台设备上"的引用、
     * 把它的能力并进手机的能力清单、记一笔账。手机全程不认识这个应用的实现类 ——
     * 这正是用户要的"三方平台软件自己实现接口去对接"。
     *
     * <p>重复 id 的安装会被拒绝而不是覆盖: 覆盖会让"她手机上装了两个聊天软件"
     * 变成"后装的那个静默地顶掉了前一个", 而那是真实可能发生的场景
     * (工作号与私人号)。
     */
    public void install(DeviceApplication application) {
        Objects.requireNonNull(application, "要装的应用不能为空");
        String appId = Objects.requireNonNull(application.id(), "应用必须有 id —— 手机按它记账");
        if (applications.containsKey(appId)) {
            throw new IllegalStateException(
                    "手机上已经装了 id 为 " + appId + " 的应用 —— "
                            + "覆盖安装会让前一个应用的 onDetach 永远不被调用, 而它的后台连接会一直留着");
        }
        applications.put(appId, application);
        invalidateCapabilities();
        // 顺序刻意的: 先把应用记进账, 再调 onAttach —— 否则应用在自己的 onAttach 里
        // 问"我在哪台设备上/我还有哪些兄弟应用"会得到"你还不在"这个荒谬的答案
        application.onAttach(this);
        log.info("[Phone/{}] 已装应用 {} ({}), 它带来 {} 项能力",
                id.value(), appId, application.descriptor().displayName(),
                application.capabilities().size());
    }

    /** 卸载一个应用。没装过就什么都不做 —— 卸载一个不存在的应用不该是一次失败。 */
    public void uninstall(String applicationId) {
        DeviceApplication removed = applications.remove(applicationId);
        if (removed == null) {
            return;
        }
        invalidateCapabilities();
        try {
            removed.onDetach();
        } catch (RuntimeException e) {
            // 一个第三方应用的 onDetach 抛异常不该让卸载失败: 状态已经从手机上摘掉了,
            // 抛出去只会让调用方以为"还装着"。这里记一条 ERROR 并继续
            log.error("[Phone/{}] 应用 {} 的 onDetach 抛了异常, 但它已被卸载",
                    id.value(), applicationId, e);
        }
        log.info("[Phone/{}] 已卸载应用 {}", id.value(), applicationId);
    }

    /** 装着的应用 —— 不可变快照, 防止调用方从外面往手机里塞东西。 */
    public Collection<DeviceApplication> applications() {
        return List.copyOf(applications.values());
    }

    public Optional<DeviceApplication> application(String applicationId) {
        return Optional.ofNullable(applications.get(applicationId));
    }

    /** 打开一个应用: 它切到前台 + 屏幕亮起。没装过则返回 false, 由能力层翻译成 REJECTED。 */
    public boolean openApplication(String applicationId, Instant at) {
        if (!applications.containsKey(applicationId)) {
            return false;
        }
        // 顺序: 先亮屏再切前台。反过来的话 moveToForeground 会点亮一个"前台已设置"
        // 的屏幕, 于是 ScreenSystem 的亮屏事件带着 reason="user" 而不是真实原因
        if (!screen.isOn()) {
            screen.wake(ScreenSystem.Reasons.USER, at);
        }
        screen.moveToForeground(applicationId);
        log.debug("[Phone/{}] 打开应用 {}", id.value(), applicationId);
        return true;
    }

    /** 关掉前台应用(回到桌面)。 */
    public void closeForegroundApplication() {
        String foreground = screen.foregroundApplication();
        screen.clearForeground();
        log.debug("[Phone/{}] 关闭应用 {}", id.value(), foreground);
    }

    private void invalidateCapabilities() {
        this.cachedCapabilities = null;
    }

    // ═══════════════════════════ 闹钟 ═══════════════════════════

    /**
     * 设一个闹钟。
     *
     * <p>闹钟的<b>调度</b>归手机, <b>发声</b>归音频系统, <b>事件陈述</b>归
     * {@link AudioSystem.AlarmFired} —— 三者各一件事, 没有转发层。见那条事件的注释:
     * 我们不单独建一个 {@code AlarmSystem}, 因为"闹钟"不是一个系统,
     * 它是"在 ALARM 通道上放一段声音"。
     */
    public void setAlarm(String label, Instant at) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("闹钟必须有标签 —— 她要说得出'那个 7 点的'");
        }
        Objects.requireNonNull(at, "闹钟必须有一个到点时刻");
        alarms.put(label, at);
        log.info("[Phone/{}] 设了闹钟 {} @ {}", id.value(), label, at);
    }

    /** 取消一个闹钟。返回是否真的取消了 —— "她要取消一个不存在的闹钟"是一个可观测的事实。 */
    public boolean cancelAlarm(String label) {
        boolean removed = alarms.remove(label) != null;
        if (removed) {
            log.info("[Phone/{}] 取消闹钟 {}", id.value(), label);
        }
        return removed;
    }

    /** 现在挂着的闹钟 —— 不可变快照。 */
    public Map<String, Instant> alarms() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(alarms));
    }

    /**
     * 到点的闹钟响掉它。
     *
     * <p>闹钟的"到点"判断用 {@code !at.isAfter(now)} 而不是 {@code at.equals(now)}:
     * 仿真时间可能被大步推进(一次推进 10 分钟), 用相等判断会让闹钟被跳过一段时间,
     * 而"闹钟没响"这种 bug 在测试里极难复现。
     *
     * <p>响过就从表里删掉 —— 它是一次性的, 不是重复日程。重复日程属于
     * {@code ScheduledEvent}(C 类), 那在 Human 的计划表那一侧。
     */
    private int fireDueAlarms(Instant now) {
        int fired = 0;
        var iterator = alarms.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> alarm = iterator.next();
            if (alarm.getValue().isAfter(now)) {
                continue;
            }
            iterator.remove();
            // 走 ALARM 通道 —— 用的是她给闹钟设的那一档音量, 而不是通知音量
            audio.play(AudioSystem.AudioStimulus.alarm(alarm.getKey()), now);
            screen.wake(ScreenSystem.Reasons.ALARM, now);
            fired++;
        }
        return fired;
    }

    /**
     * 没电了就投一条"手机不可用"。
     *
     * <p>只投一次({@link #depletionAnnounced}): 一个每 tick 都投"我没电了"的手机
     * 会把队列塞满, 而真实世界里"手机没电"这件事只发生一次。
     */
    private int announceDepletionIfNeeded(Instant now) {
        if (depletionAnnounced || !battery.depleted()) {
            return 0;
        }
        this.depletionAnnounced = true;
        // 由 Phone 投而不是 BatterySystem 投 —— 见 BatterySystem 类注释里"生产者为什么写的是 Phone"
        //
        // since = now: 电量是"这一 tick 掉到 0 的"(可能上一 tick 还有 1%), 而世界只在
        // 跨过阈值这一刻才看得到它。给别的值都是编的, 而编出来的"从三点起就没电了"
        // 会变成一条她无法解释的、关于自己的假事实
        eventFabric.publish(Device.Unavailable.of(id.value(),
                "battery-depleted(" + battery.power() + ")", now, now));
        return 1;
    }

    // ═══════════════════════════ 硬件能力 ═══════════════════════════

    /**
     * 手机硬件本身提供的能力({@code device.phone.*})。
     *
     * <p>每个能力都做三件事: <b>校验参数 → 检查可用性 → 做那件事</b>,
     * 失败一律返回 {@link ActionResult#rejected} / {@link ActionResult#unavailable}
     * 而<b>不抛异常</b> —— 见 {@code Capability} 类注释: "手机没电、没信号
     * 不是异常, 是她生活的一部分"。
     */
    private List<Capability> hardwareCapabilities() {
        List<Capability> list = new ArrayList<>();

        list.add(action(CapabilityDescriptor.of(Capabilities.READ_STATE)
                .title("看手机状态")
                .description("读一眼这台手机现在什么样: 电量、四路音量、响铃模式、屏幕、装了哪些应用。"
                        + "只读, 不改变任何东西")
                .sideEffect(CapabilityDescriptor.SideEffect.READ)
                .tags("device", "phone")
                .build(), (command, context) -> ActionResult.succeeded(
                Capabilities.READ_STATE, context.now(), state())));

        list.add(action(CapabilityDescriptor.of(Capabilities.SET_VOLUME)
                .title("调音量")
                .description("调整一路通道的音量。四路通道: " + AudioChannel.knownKeys()
                        + "。level 为 0 表示静音这一路")
                .parameters(Map.of(
                        "channel", Map.of("type", "string", "description", "哪一路通道"),
                        "level", Map.of("type", "number", "description", "0..1 之间")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "audio")
                .build(), this::setVolume));

        list.add(action(CapabilityDescriptor.of(Capabilities.SET_NOTIFICATION_POLICY)
                .title("设置通知策略")
                .description("设置本机怎么响: 响铃模式(sound/vibrate/silent)、要不要振动、要不要亮屏。"
                        + "注意这与聊天平台的免打扰是<b>两件事</b>: 平台决定要不要发通知信号, "
                        + "这里决定收到之后怎么响")
                .parameters(Map.of(
                        "ringer", Map.of("type", "string", "description", "sound / vibrate / silent"),
                        "vibration", Map.of("type", "boolean"),
                        "wakeScreen", Map.of("type", "boolean")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "notification")
                .build(), this::setNotificationPolicy));

        list.add(action(CapabilityDescriptor.of(Capabilities.SET_ALARM)
                .title("设闹钟")
                .description("设一个闹钟, 到点会用闹钟音量响, 并点亮屏幕")
                .parameters(Map.of(
                        "label", Map.of("type", "string", "description", "闹钟标签"),
                        "at", Map.of("type", "string", "description", "ISO-8601 时刻")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "alarm")
                .build(), this::setAlarm));

        list.add(action(CapabilityDescriptor.of(Capabilities.CANCEL_ALARM)
                .title("取消闹钟")
                .description("按标签取消一个闹钟。取消不存在的闹钟会返回 REJECTED, 而不是静默成功")
                .parameters(Map.of("label", Map.of("type", "string")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "alarm")
                .build(), this::cancelAlarm));

        list.add(action(CapabilityDescriptor.of(Capabilities.OPEN_APPLICATION)
                .title("打开应用")
                .description("把某个应用切到前台并点亮屏幕。这是'她打开了聊天软件'这个动作, "
                        + "而不是'她读了消息' —— 后者是 chat.read-messages")
                .parameters(Map.of("application", Map.of("type", "string")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "application")
                .build(), this::openApplication));

        list.add(action(CapabilityDescriptor.of(Capabilities.CLOSE_APPLICATION)
                .title("关闭应用")
                .description("让前台应用回到后台(回到桌面)")
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "application")
                .build(), (command, context) -> {
            closeForegroundApplication();
            return ActionResult.succeeded(Capabilities.CLOSE_APPLICATION, context.now());
        }));

        list.add(action(CapabilityDescriptor.of(Capabilities.SET_BRIGHTNESS)
                .title("调屏幕亮度")
                .description("0..1。熄屏状态下只记住值, 不点亮屏幕")
                .parameters(Map.of("level", Map.of("type", "number")))
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "screen")
                .build(), (command, context) -> {
            double level = requireDouble(command, "level");
            if (level < 0 || level > 1) {
                return ActionResult.rejected(Capabilities.SET_BRIGHTNESS, context.now(),
                        "亮度必须在 0..1 之间, 收到 " + level);
            }
            screen.setBrightness(level);
            return ActionResult.succeeded(Capabilities.SET_BRIGHTNESS, context.now());
        }));

        list.add(action(CapabilityDescriptor.of(Capabilities.PLUG_CHARGER)
                .title("插上充电器")
                .description("插电后电量会回升, 电量焦虑随之解除")
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "power")
                .build(), (command, context) -> {
            battery.plugIn(context.now());
            return ActionResult.succeeded(Capabilities.PLUG_CHARGER, context.now(),
                    Map.of("charging", true));
        }));

        list.add(action(CapabilityDescriptor.of(Capabilities.UNPLUG_CHARGER)
                .title("拔掉充电器")
                .sideEffect(CapabilityDescriptor.SideEffect.WRITE)
                .tags("device", "phone", "power")
                .build(), (command, context) -> {
            battery.unplug(context.now());
            return ActionResult.succeeded(Capabilities.UNPLUG_CHARGER, context.now(),
                    Map.of("charging", false));
        }));

        return list;
    }

    // ─────────────────────────── 能力的实现 ───────────────────────────

    private ActionResult setVolume(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.SET_VOLUME;
        // 手机没电时连音量都调不了 —— 这正是 §5.5 里 UNAVAILABLE 的范例:
        // 能力仍然在表里(她"记得"自己会调音量), 只是此刻不可用
        if (!usable()) {
            return ActionResult.unavailable(key, context.now(),
                    "手机没电了(" + battery.power() + "), 调不了音量");
        }
        String channelName = command.str("channel").orElse(null);
        Optional<AudioChannel> channel = AudioChannel.tryParse(channelName);
        if (channel.isEmpty()) {
            return ActionResult.rejected(key, context.now(),
                    "不认识通道 " + channelName + ", 平台认识的是 " + AudioChannel.knownKeys());
        }
        double level = requireDouble(command, "level");
        if (level < 0 || level > 1) {
            return ActionResult.rejected(key, context.now(),
                    "音量必须在 0..1 之间, 收到 " + level
                            + " —— 越界的音量会被当成'比静音更静'或'比最大还大'");
        }
        AudioVolume volume = AudioVolume.of(level);
        audio.setVolume(channel.get(), volume);
        return ActionResult.succeeded(key, context.now(),
                Map.of("channel", channel.get().name(), "level", volume.level()));
    }

    private ActionResult setNotificationPolicy(ActionCommand command,
                                               Capability.CapabilityContext context) {
        String key = Capabilities.SET_NOTIFICATION_POLICY;
        if (!usable()) {
            return ActionResult.unavailable(key, context.now(), "手机没电了, 改不了设置");
        }
        NotificationPolicy current = notifications.policy();
        NotificationPolicy.RingerMode ringer = current.ringer();
        Optional<String> requested = command.str("ringer");
        if (requested.isPresent()) {
            Optional<NotificationPolicy.RingerMode> parsed =
                    parseRinger(requested.get());
            if (parsed.isEmpty()) {
                return ActionResult.rejected(key, context.now(),
                        "不认识响铃模式 " + requested.get() + ", 只能是 sound / vibrate / silent");
            }
            ringer = parsed.get();
        }
        boolean vibrationOn = command.bool("vibration").orElse(current.vibrationEnabled());
        boolean wakeOn = command.bool("wakeScreen").orElse(current.wakeScreen());
        notifications.setPolicy(NotificationPolicy.of(ringer, vibrationOn, wakeOn));
        // 响铃模式同时约束振动系统的开关 —— 两处状态必须一起改, 否则会出现
        // "静音但振动开着"这种自相矛盾的手机。这里以策略为准, 把它同步下去
        vibration.setEnabled(ringer != NotificationPolicy.RingerMode.SILENT && vibrationOn);
        return ActionResult.succeeded(key, context.now(),
                Map.of("ringer", ringer.name(), "describe", notifications.policy().describe()));
    }

    private ActionResult setAlarm(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.SET_ALARM;
        String label = command.str("label").orElse(null);
        if (label == null || label.isBlank()) {
            return ActionResult.rejected(key, context.now(), "闹钟必须有 label");
        }
        String at = command.str("at").orElse(null);
        Instant when;
        try {
            when = Instant.parse(at);
        } catch (RuntimeException e) {
            return ActionResult.rejected(key, context.now(),
                    "闹钟时刻要 ISO-8601(如 2026-09-19T07:00:00Z), 收到 " + at);
        }
        if (when.isBefore(context.now())) {
            return ActionResult.rejected(key, context.now(),
                    "闹钟不能设在过去(" + when + " 早于现在 " + context.now()
                            + ") —— 一个设了就响的闹钟和'她现在被叫醒'是两件事");
        }
        setAlarm(label, when);
        return ActionResult.succeeded(key, context.now(), Map.of("label", label, "at", when.toString()));
    }

    private ActionResult cancelAlarm(ActionCommand command, Capability.CapabilityContext context) {
        String key = Capabilities.CANCEL_ALARM;
        String label = command.str("label").orElse(null);
        if (label == null || label.isBlank()) {
            return ActionResult.rejected(key, context.now(), "要取消哪个闹钟? 需要 label");
        }
        if (!cancelAlarm(label)) {
            return ActionResult.rejected(key, context.now(),
                    "没有叫 " + label + " 的闹钟, 现在挂着的是 " + alarms.keySet());
        }
        return ActionResult.succeeded(key, context.now(), Map.of("label", label));
    }

    private ActionResult openApplication(ActionCommand command,
                                         Capability.CapabilityContext context) {
        String key = Capabilities.OPEN_APPLICATION;
        if (!usable()) {
            return ActionResult.unavailable(key, context.now(),
                    "手机没电了, 屏幕点不亮");
        }
        String appId = command.str("application").orElse(null);
        if (appId == null || appId.isBlank()) {
            return ActionResult.rejected(key, context.now(), "要打开哪个应用? 需要 application");
        }
        if (!openApplication(appId, context.now())) {
            // 没装过就是 REJECTED 而不是 UNAVAILABLE: 她"手机上没这个应用"是一个
            // 事实, 而不是一个暂时性的故障 —— 前者重试一百次也不会成功
            return ActionResult.rejected(key, context.now(),
                    "这台手机上没装 " + appId + ", 装的是 " + applications.keySet());
        }
        return ActionResult.succeeded(key, context.now(), Map.of("application", appId));
    }

    private static Optional<NotificationPolicy.RingerMode> parseRinger(String raw) {
        for (NotificationPolicy.RingerMode mode : NotificationPolicy.RingerMode.values()) {
            if (mode.name().equalsIgnoreCase(raw) || mode.label().equals(raw)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    private static double requireDouble(ActionCommand command, String key) {
        Object value = command.require(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // LLM 会把数字写成字符串 —— 这是它的正常工作方式, 所以这里宽容地解析,
        // 而不是把"5" 当成一次非法调用
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "参数 " + key + " 需要一个数字, 收到 " + value, e);
        }
    }

    // ═══════════════════════════ 能力的小工具 ═══════════════════════════

    /**
     * 一个"只有描述 + 一段实现"的能力。
     *
     * <p>它是嵌套的 record 而不是一个独立文件: 它是 {@code Phone} 的能力清单
     * 拼装用的<b>私有工具</b>, 不属于任何公共 API。{@code Capability} 需要两个方法
     * 所以不是函数式接口 —— 这个 record 存在的唯一理由就是把一个
     * {@code (command, context) -> ActionResult} 的 lambda 变成 {@code Capability}。
     */
    private static record SimpleCapability(
            CapabilityDescriptor descriptor,
            BiFunction<ActionCommand, Capability.CapabilityContext, ActionResult> body)
            implements Capability {

        @Override
        public ActionResult invoke(ActionCommand command, Capability.CapabilityContext context) {
            return body.apply(command, context);
        }
    }

    private static Capability action(CapabilityDescriptor descriptor,
                                     BiFunction<ActionCommand, Capability.CapabilityContext,
                                             ActionResult> body) {
        return new SimpleCapability(descriptor, body);
    }
}
