package com.luxera.companion.registry;

import com.luxera.companion.boundary.event.EventTypeId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * V2.2 §5.4 —— <b>平台内置事件类型目录</b>。
 *
 * <h2>为什么这份目录必须存在, 而它又不能是一个枚举</h2>
 * 用户对 V2.1 有一条明确、且完全正确的批评:
 * <blockquote>
 *   你定义了 event 类结构, 给了 eventtype 字段, 但是没定义 eventtype 有哪些枚举值,
 *   这是很不好的, 不了解的人看完完全不知道都有哪些 event, 然后分别有什么用
 * </blockquote>
 *
 * <p>这个批评命中了一个真实的失败模式: <b>一个开放性设计如果不说清"现有的有哪些",
 * 它对读代码的人就是不可用的</b>。他只能看到一堆接口和一个空荡荡的注册表,
 * 完全不知道从哪儿开始。
 *
 * <p>但把它做成枚举又会违反 P4(枚举不做领域扩展机制)。所以 V2.2 的做法是:
 * <b>目录是数据, 不是类型</b>。
 * <ul>
 *   <li>它是一个普通对象, 内容来自 {@link #CORE} 这个静态表;</li>
 *   <li>它被<b>注册进</b> {@code DomainTypeRegistry}, 与第三方注册的事件类型走同一条路;</li>
 *   <li>加一条内置事件 = 在表里加一行, <b>不需要改任何分派逻辑</b>;</li>
 *   <li>一个全新的事件类型可以完全不进这张表 —— 它就是第三方类型。</li>
 * </ul>
 *
 * <p>于是同时满足了两件事: 人看得到清单, 而系统不依赖清单。
 *
 * <h2>怎么读这张表</h2>
 * 每一条回答五个问题:
 * <table border="1">
 *   <tr><th>字段</th><th>回答的问题</th></tr>
 *   <tr><td>{@link EventSpec#typeId}</td><td>它叫什么(唯一标识)</td></tr>
 *   <tr><td>{@link EventSpec#category}</td>
 *       <td><b>它是哪一类</b> —— A 持续影响 / B 感官实时 / C 安排。<b>这一列决定了它被哪个数据结构接住</b></td></tr>
 *   <tr><td>{@link EventSpec#payload}</td><td>它带什么信息</td></tr>
 *   <tr><td>{@link EventSpec#semantics}</td><td>它的语义 —— 什么情况下会产生它</td></tr>
 *   <tr><td>{@link EventSpec#producer} / {@link EventSpec#consumer}</td>
 *       <td>谁产生它, 谁消费它。<b>一条没有生产者的目录项是死代码, 一条没有消费者的目录项是无人认领的</b></td></tr>
 * </table>
 *
 * <h2>标准通道</h2>
 * 除事件外, 本目录还登记两类"通道"名: 持续影响的 {@link #CHANNELS} 与感官的
 * {@link #MODALITIES}。它们必须集中的理由与事件类型略有不同 ——
 * 事件类型拼错只是"没人订阅", 而通道名拼错会让两条本该叠加的影响分成两笔互不相干的账
 * (见 {@code StateEffectEvent.effectChannel()} 的说明)。
 */
public final class CoreEventCatalog {

    private CoreEventCatalog() {
    }

    // ═══════════════════════════ 事件类别 ═══════════════════════════

    /**
     * 事件的三大类别 —— 与三个数据结构一一对应。
     *
     * <p>它是枚举, 这与 P4 不冲突: 它<b>不是扩展机制</b>, 而是这套仿真对
     * "世界如何影响一个人"这个问题给出的<b>完整分类</b>。加第四类意味着改架构,
     * 不是加功能。一个把"加第四类"当成常规操作的目录, 说明前三类的划分是错的。
     */
    public enum Category {
        /** A 类: 持续影响。归宿 {@code ContinuousEffectLedger}。 */
        STATE_EFFECT("持续影响", "一直生效直到条件改变, 由账本每 tick 结算"),
        /** B 类: 感官实时。归宿 {@code RealtimeEventQueue}。 */
        SENSORY("感官实时", "发生在某一刻, 需要她立刻知道或反应"),
        /** C 类: 安排。归宿 {@code PlanBoard}。 */
        SCHEDULED("安排", "世界在某段时间里留了一件事, 要不要做由她决定");

        private final String label;
        private final String note;

        Category(String label, String note) {
            this.label = label;
            this.note = note;
        }

        public String label() {
            return label;
        }

        public String note() {
            return note;
        }
    }

    // ═══════════════════════════ 标准通道 ═══════════════════════════

    /**
     * 持续影响的<b>标准通道</b>。
     *
     * <p>为什么必须是常量而不是各写各的: 同一通道上的影响会被<b>相加</b>
     * (见 {@code ContinuousEffectLedger} 的求和模型)。两个人各写
     * {@code "warmth"} 和 {@code "body.warmth"} 会得到两笔互不相干的账 ——
     * 表现是"羽绒服穿了但没用", 且没有任何报错。
     */
    public static final class Channels {
        private Channels() {
        }

        /** 保暖。+ 是保暖, - 是失温。适中值见 {@code HomeostasisModel.comfortBand("body.warmth")}。 */
        public static final String WARMTH = "body.warmth";
        /** 干湿。+ 是湿, - 是干。 */
        public static final String WETNESS = "body.wetness";
        /** 能量。+ 是补充, - 是消耗。 */
        public static final String ENERGY = "body.energy";
        /** 疲劳累积。只增不减, 靠睡眠清。 */
        public static final String FATIGUE = "body.fatigue";
        /** 饥饿。 */
        public static final String HUNGER = "body.hunger";
        /** 口渴。 */
        public static final String THIRST = "body.thirst";
        /** 睡意压力。 */
        public static final String SLEEP_PRESSURE = "body.sleep-pressure";
        /** 不适/疼痛。 */
        public static final String PAIN = "body.pain";
        /** 心理压力。 */
        public static final String STRESS = "body.stress";
        /** 舒适度 —— 前几个通道的综合体现, 由 HomeostasisModel 算, 不直接入账。 */
        public static final String COMFORT = "body.comfort";
        /** 注意力占用 —— 她正在做的事占了多大心力。 */
        public static final String ATTENTION_LOAD = "mind.attention-load";
        /** 情绪底色 —— 与情绪状态机配合, 表达"这段时间心情偏什么色"。 */
        public static final String MOOD = "mind.mood";

        /** 全部标准通道, 供校验用。 */
        public static final Set<String> ALL = Set.of(
                WARMTH, WETNESS, ENERGY, FATIGUE, HUNGER, THIRST, SLEEP_PRESSURE,
                PAIN, STRESS, COMFORT, ATTENTION_LOAD, MOOD);
    }

    /**
     * 感官通道 —— <b>封闭集合</b>, 因为人只有这五种感官。
     *
     * <p>与 {@link Channels} 不同, 这里的封闭性是物理事实而非设计选择, 所以
     * 拼错一个通道名不会有"两个通道各算各的"那种后果, 而是"刺激投进了一个
     * 没有接收者的通道" —— 表现是"她没听见", 且没有报错。因此它值得被校验。
     */
    public static final class Modalities {
        private Modalities() {
        }

        public static final String AUDITORY = "auditory";
        public static final String VISUAL = "visual";
        public static final String OLFACTORY = "olfactory";
        public static final String GUSTATORY = "gustatory";
        public static final String TACTILE = "tactile";

        public static final Set<String> ALL = Set.of(
                AUDITORY, VISUAL, OLFACTORY, GUSTATORY, TACTILE);

        /** 这个通道名是不是标准的。 */
        public static boolean isStandard(String modality) {
            return modality != null && ALL.contains(modality);
        }
    }

    // ═══════════════════════════ 目录项 ═══════════════════════════

    /**
     * 目录里的一条。
     *
     * @param typeId     类型标识
     * @param category   类别 —— <b>决定它被哪个数据结构接住</b>
     * @param payload    载荷的形状描述(一个结构说明, 不是运行时类型)
     * @param semantics  语义: 什么情况下会产生它
     * @param producer   谁产生它
     * @param consumer   谁消费它
     * @param channel    若是 A 类, 作用在哪个通道上; 否则为空
     * @param modality   若是 B 类, 走哪个感官通道; 否则为空
     */
    public record EventSpec(EventTypeId typeId,
                            Category category,
                            String payload,
                            String semantics,
                            String producer,
                            String consumer,
                            String channel,
                            String modality) {

        public EventSpec {
            Objects.requireNonNull(typeId, "目录项必须有类型标识");
            Objects.requireNonNull(category, "目录项必须属于某一类 —— 类别决定它被哪个结构接住");
            semantics = semantics == null ? "" : semantics;
            producer = producer == null ? "?" : producer;
            consumer = consumer == null ? "(未认领)" : consumer;
        }

        /** 这一条有没有消费者。没有的要么是留给将来的, 要么是订阅键写错了。 */
        public boolean claimed() {
            return !"(未认领)".equals(consumer);
        }

        /** 一行摘要, 给文档生成与诊断面板用。 */
        public String describe() {
            return typeId + "  [" + category.label() + "]  "
                    + (channel != null ? "channel=" + channel + " " : "")
                    + (modality != null ? "modality=" + modality + " " : "")
                    + "\n    语义: " + semantics
                    + "\n    载荷: " + payload
                    + "\n    " + producer + " → " + consumer;
        }
    }

    /** 流式构造一条目录项 —— 见类注释"这份目录必须存在"。 */
    private static EventSpecBuilder def(String type) {
        return new EventSpecBuilder(type);
    }

    private static final class EventSpecBuilder {
        private final EventTypeId typeId;
        private Category category;
        private String payload = "";
        private String semantics = "";
        private String producer = "?";
        private String consumer = "(未认领)";
        private String channel;
        private String modality;

        private EventSpecBuilder(String type) {
            this.typeId = EventTypeId.parse(type);
        }

        EventSpecBuilder category(Category v) {
            this.category = v;
            return this;
        }

        EventSpecBuilder payload(String v) {
            this.payload = v;
            return this;
        }

        EventSpecBuilder semantics(String v) {
            this.semantics = v;
            return this;
        }

        EventSpecBuilder producer(String v) {
            this.producer = v;
            return this;
        }

        EventSpecBuilder consumer(String v) {
            this.consumer = v;
            return this;
        }

        EventSpecBuilder channel(String v) {
            this.channel = v;
            return this;
        }

        EventSpecBuilder modality(String v) {
            this.modality = v;
            return this;
        }

        EventSpec build() {
            return new EventSpec(typeId, category, payload, semantics, producer, consumer,
                    channel, modality);
        }
    }

    // ═══════════════════════════ 核心目录 ═══════════════════════════

    /**
     * <b>平台内置的全部事件类型。</b>
     *
     * <p>按命名空间分组, 顺序即阅读顺序:
     * {@code environment.*}(真实世界输入) → {@code device.*}(设备世界) →
     * {@code body.*}(身体结算) → {@code mind.*}(心智) → {@code plan.*}(计划) →
     * {@code object.*}(世界对象) → {@code system.*}(系统自身)。
     */
    private static final List<EventSpec> CORE = List.of(

            // ══════════ environment.* — 真实世界输入 (A/B 类) ══════════
            // 用户: "environment 当前实现是根据 agent 所处地域查询真实环境数据
            //        (包括气温、湿度等等), 这些数据产生的 event 类型是要对 human 的
            //        body 产生持续的改变的"
            def("environment.temperature-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WARMTH)
                    .payload("celsius: double, feelsLike: double, locationId: String, observedAt: Instant")
                    .semantics("环境刷新时, 抓取到的区域气温与上一次不同。产生一条持续影响: "
                            + "气温低于舒适带时 magnitude 为负, 让她持续失温, 直到她加衣服或气温回升。"
                            + "注意: 这条事件本身不惊动她 —— 惊动她的是账本结算后保暖值跌破阈值时产生的 body.cold-stimulus")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger (入账)")
                    .build(),

            def("environment.humidity-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WETNESS)
                    .payload("relativeHumidity: double, locationId: String, observedAt: Instant")
                    .semantics("环境刷新时湿度变化。高湿让体感更冷, 由 HomeostasisModel 把它折算进保暖通道的修正项, "
                            + "不单独产生冷刺激")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("environment.weather-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("from: WeatherCondition, to: WeatherCondition, locationId: String")
                    .semantics("天气状况本身变化(晴转雨)。它是一个组合影响: 下雨同时作用于 wetness 与 comfort 两条通道, "
                            + "由 Environment 侧拆成两条 StateEffectEvent 投递")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("environment.rain-started.v1")
                    .category(Category.SENSORY).modality(Modalities.AUDITORY)
                    .payload("intensity: double, locationId: String")
                    .semantics("开始下雨。这是一条<b>实时</b>刺激: 雨声是听到的, 而听到这件事需要她当下处理 —— "
                            + "她可能去关窗。与 environment.weather-changed 的区别是后者只改状态, 前者会惊动她")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            def("environment.wind-started.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WARMTH)
                    .payload("level: double, locationId: String")
                    .semantics("起风。让体感温度进一步下降, 是保暖通道上的负向影响")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("environment.air-quality-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.STRESS)
                    .payload("aqi: int, level: String, locationId: String")
                    .semantics("空气质量变化。差空气会带来轻微持续压力, 并在极差时产生嗅觉刺激")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("environment.daylight-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.MOOD)
                    .payload("daylightHours: double, sunrise: Instant, sunset: Instant, locationId: String")
                    .semantics("日照时长变化。作用于情绪底色, 是季节性情绪的那个「季节」")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("environment.snapshot-refreshed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("snapshot: EnvironmentSnapshot")
                    .semantics("每 N 分钟一次的整份环境快照。它是上面那些「变化」事件的<b>载体</b>: "
                            + "Environment 每次刷新先投这一条(记录完整现状, 供诊断与重放), "
                            + "再投那些真正有差异的通道。零差异时只投这一条")
                    .producer("world.environment.EnvironmentRefreshJob")
                    .consumer("EventStore (落库) / 诊断面板")
                    .build(),

            def("environment.location-changed.v1")
                    .category(Category.SENSORY).modality(Modalities.VISUAL)
                    .payload("fromPlaceId: String, toPlaceId: String, reason: String")
                    .semantics("她换了个地方(从家到公司)。视觉上她会注意到环境变了, 同时触发一次环境重抓 —— "
                            + "因为新地方的天气与旧地方无关")
                    .producer("world.digital.Place / AgentMovement")
                    .consumer("RealtimeEventQueue → AttentionService; EnvironmentRefreshJob (触发重抓)")
                    .build(),

            // ══════════ device.* — 设备世界 (B 类为主) ══════════
            // 用户: "手机对象, 我们正常手机有软件通知声音大小、手机铃声声音大小、闹钟声音大小"
            def("device.phone.notification-raised.v1")
                    .category(Category.SENSORY).modality(Modalities.AUDITORY)
                    .payload("phoneId: String, applicationKey: String, volume: double, "
                            + "vibration: boolean, signalCount: int")
                    .semantics("手机因为某条通知信号而响。产生条件有三: ①聊天平台发来了 NotificationSignal; "
                            + "②本机通知策略允许(非静音/非免打扰); ③通知音量 > 0。"
                            + "<b>载荷里没有消息正文, 也没有发信人是谁</b> —— 那是一部真手机在响时她所知道的全部。"
                            + "用户要求: 「每一条都根据用户在聊天软件设置的通知规则来判断是否要通知到用户, "
                            + "然后如果用户没开免打扰, 每一条消息都会根据聊天软件自己的通知策略进行通知」")
                    .producer("world.device.phone.NotificationSystem")
                    .consumer("world.device.phone.AudioSystem → RealtimeEventQueue")
                    .build(),

            def("device.phone.ringtone-started.v1")
                    .category(Category.SENSORY).modality(Modalities.AUDITORY)
                    .payload("phoneId: String, callerAccountId: String, volume: double")
                    .semantics("有人打电话来, 铃声响起。与通知的区别是它<b>不会自己停</b> —— "
                            + "它会一直响到被接听或对方挂断, 因此 urgency 更高(0.85), 且 foldingKey 不为空")
                    .producer("world.device.phone.AudioSystem (RINGTONE 通道)")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            def("device.phone.alarm-fired.v1")
                    .category(Category.SENSORY).modality(Modalities.AUDITORY)
                    .payload("phoneId: String, alarmLabel: String, volume: double")
                    .semantics("闹钟响了。用户提到的三种音量里的一种: "
                            + "「软件通知声音大小、手机铃声声音大小、闹钟声音大小」")
                    .producer("world.device.phone.AlarmSystem")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            def("device.phone.sound-emitted.v1")
                    .category(Category.SENSORY).modality(Modalities.AUDITORY)
                    .payload("phoneId: String, channel: String, soundProfile: String, "
                            + "effectiveVolume: double, durationMillis: long, occurredAt: Instant")
                    .semantics("<b>扬声器发出了一个声音</b> —— 一个纯粹的物理事实。"
                            + "它是“没有专属语义事件的那一路”的兜底, 当前由媒体播放使用; "
                            + "通知/来电/闹钟各自有专属事件(上面三条), 因为“为什么响”决定了她该怎么反应。"
                            + "载荷里<b>没有来源</b> —— 因为她听见的只有声音; "
                            + "“那是谁打的电话”要等她去看, 而那一步走的是 chat.read-messages")
                    .producer("world.device.phone.AudioSystem")
                    .consumer("RealtimeEventQueue → AuditoryChannel")
                    .build(),

            def("device.phone.screen-changed.v1")
                    .category(Category.SENSORY).modality(Modalities.VISUAL)
                    .payload("phoneId: String, screenOn: boolean, brightness: double")
                    .semantics("屏幕亮起或熄灭。只有当她<b>看得见</b>手机时才是视觉刺激 —— "
                            + "手机在包里时这条事件仍然会投, 但 AttentionService 按处境给极低的 salience")
                    .producer("world.device.phone.ScreenSystem")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            def("device.phone.battery-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.STRESS)
                    .payload("phoneId: String, level: double, charging: boolean")
                    .semantics("电量变化。电量极低时产生轻微持续焦虑 —— 一个现代人才会有的压力源")
                    .producer("world.device.phone.Phone")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("device.phone.unavailable.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("phoneId: String, reason: String, since: Instant")
                    .semantics("手机不可用了(没电/关机/不在身边)。"
                            + "它的价值在于让“她没回消息”有一个<b>可查证</b>的解释 —— "
                            + "而不是只能推断成“她不在乎”")
                    .producer("world.device.phone.Phone")
                    .consumer("RealtimeEventQueue; 行为分析")
                    .build(),

            def("device.notification-dropped.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("phoneId: String, reason: String, droppedCount: int")
                    .semantics("通知被本机策略拦下了(免打扰/静音)。<b>她感知不到它, 但行为分析需要它</b> —— "
                            + "见 RealtimeEventQueue 类注释“容量与丢弃”里同一个论证")
                    .producer("world.device.phone.NotificationSystem")
                    .consumer("行为分析 / 诊断面板")
                    .build(),

            def("device.headphones-connected.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.ATTENTION_LOAD)
                    .payload("deviceId: String, kind: String")
                    .semantics("戴上耳机。它会改变其他听觉刺激的 salience —— "
                            + "这正是“同一个刺激对不同处境的人重要程度不同”的一个实例")
                    .producer("world.device.Device")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("device.screen-viewed.v1")
                    .category(Category.SENSORY).modality(Modalities.VISUAL)
                    .payload("deviceId: String, contentKind: String, durationMs: long")
                    .semantics("她看了一段时间某块屏幕。用于建模“她是不是在忙”")
                    .producer("world.device.Device")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            def("device.generic-state-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("deviceId: String, from: Map, to: Map")
                    .semantics("<b>第三方设备的通用状态变化</b>。存在的意义是让一个还没被平台认识的新设备"
                            + "也能投递事件 —— 它的载荷是自由 Map, 由设备自己解释。"
                            + "这是 P4 在本目录里的体现: 目录不是白名单, 只是已知清单")
                    .producer("任意 world.device.Device 实现")
                    .consumer("由设备自己注册的 EventHandler")
                    .build(),

            // ══════════ body.* — 身体结算 (A/B 类) ══════════
            // 用户: "温度低应该让 human 的 body 持续降低保暖值, 除非其多穿衣服
            //        (因此衣服可能也要抽象成对象)"
            def("body.warmth-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WARMTH)
                    .payload("from: double, to: double, delta: double, cause: String")
                    .semantics("保暖值发生了一次可感知的变化。<b>它是账本结算的产物, 不是入账</b> —— "
                            + "所以它不会反过来再进账本。它存在的意义是给行为分析一条可读的时间线: "
                            + "“她 12:15 的保暖值是 0.42, 12:25 变成了 0.81”")
                    .producer("human.body.HomeostasisModel")
                    .consumer("EventStore / 行为分析")
                    .build(),

            def("body.cold-stimulus.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("warmth: double, comfortLow: double, deficit: double, contributors: List<String>")
                    .semantics("用户描述的那条链的<b>终点</b>: "
                            + "「environment 逐渐降温产生了持续性变更的 event, 使得 body 的保暖值下降, "
                            + "进一步导致 body 的保暖值远低于合适值产生了触觉实时 event 即觉得冷, "
                            + "然后 agent 需要立马处理这个实时 event」。"
                            + "产生条件是保暖值<b>跌破</b>舒适带下沿(边沿触发, 不是每 tick 都发) —— "
                            + "否则她会在一分钟内被自己的体温吵死")
                    .producer("human.body.ThresholdDetector (TickAware)")
                    .consumer("RealtimeEventQueue → Mind")
                    .build(),

            def("body.heat-stimulus.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("warmth: double, comfortHigh: double, excess: double")
                    .semantics("保暖值<b>高于</b>舒适带上沿(热)。与冷刺激对称")
                    .producer("human.body.ThresholdDetector")
                    .consumer("RealtimeEventQueue → Mind")
                    .build(),

            def("body.wetness-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WETNESS)
                    .payload("from: double, to: double, cause: String")
                    .semantics("干湿变化。淋雨会持续让她变湿, 而湿会通过 HomeostasisModel 折算成额外的失温")
                    .producer("human.body.HomeostasisModel")
                    .consumer("EventStore / 行为分析")
                    .build(),

            def("body.fatigue-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.FATIGUE)
                    .payload("from: double, to: double, cause: String")
                    .semantics("疲劳累积。只增不减(靠睡眠清), 因此它是一条单调的线 —— "
                            + "而“她今天特别容易走神”这个问题要在它上面找答案")
                    .producer("human.body.HomeostasisModel")
                    .consumer("EventStore / 行为分析")
                    .build(),

            def("body.energy-depleted.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("energy: double, threshold: double")
                    .semantics("能量跌破阈值(饿/累到发昏)。与冷刺激同构: 边沿触发, 由阈值检测产生")
                    .producer("human.body.ThresholdDetector")
                    .consumer("RealtimeEventQueue → Mind")
                    .build(),

            def("body.hunger-stimulus.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("hunger: double, lastMealAt: Instant")
                    .semantics("饿了。触觉通道 —— 因为饿是一种体感, 不是嗅到或看到")
                    .producer("human.body.ThresholdDetector")
                    .consumer("RealtimeEventQueue → Mind")
                    .build(),

            def("body.clothing-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.WARMTH)
                    .payload("worn: List<String>, removed: List<String>, "
                            + "totalThermalInsulation: double")
                    .semantics("穿脱衣物。用户: “多穿衣服也是一个 event, 能够持续影响 body 的保暖值的”。"
                            + "它的 cancellationKey 是 body.thermal-insulation —— "
                            + "换一件更厚的衣服时, 薄的那条的账会被自动挤掉, 而不是叠加。"
                            + "见 StateEffectEvent.cancellationKey()")
                    .producer("human.body.clothing.ClothingSet")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("body.sleep-pressure-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.SLEEP_PRESSURE)
                    .payload("from: double, to: double, hoursAwake: double")
                    .semantics("睡眠压力。醒着时单调上升, 睡着后清零。它是“她为什么今天话少”的一个候选解释")
                    .producer("human.body.HomeostasisModel")
                    .consumer("boundary.event.ContinuousEffectLedger; 唤醒调度")
                    .build(),

            // ══════════ mind.* — 心智 (B 类) ══════════
            def("mind.emotion-shifted.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.MOOD)
                    .payload("from: String, to: String, intensity: double, cause: String")
                    .semantics("情绪发生转移。作用于情绪底色通道, 影响后续所有刺激的 salience —— "
                            + "心情差的时候同样一条消息显得更烦人, 这是真人会有的现象")
                    .producer("human.mind.emotion.EmotionEngine")
                    .consumer("boundary.event.ContinuousEffectLedger; EventStore")
                    .build(),

            def("mind.attention-shifted.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.ATTENTION_LOAD)
                    .payload("from: String, to: String, load: double, reason: String")
                    .semantics("注意力转移。她开始专注某件事时, 别的刺激的 salience 会下降 —— "
                            + "这正是“她在忙所以没回”在架构里的落点")
                    .producer("human.mind.attention.AttentionService")
                    .consumer("boundary.event.ContinuousEffectLedger")
                    .build(),

            def("mind.intrusive-thought.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("contentRef: String, intensity: double")
                    .semantics("一个念头自己冒出来(想起某件没做完的事)。它<b>没有外部来源</b> —— "
                            + "sourceObjectId 为空, 而这是它与其他所有刺激最本质的区别")
                    .producer("human.mind.cognition.ReasoningEngine")
                    .consumer("RealtimeEventQueue → AttentionService")
                    .build(),

            // ══════════ plan.* — 计划表 (C 类 + 系统) ══════════
            // 用户: "还有一类 event 其实是安排 event...可以理解为这类 event 其实是计划表,
            //        然后可以随时修改的, 而且是时间段类型的"
            def("plan.item-scheduled.v1")
                    .category(Category.SCHEDULED)
                    .payload("itemId: String, intentType: String, windowStart: Instant, "
                            + "windowEnd: Instant, originator: String")
                    .semantics("有一件事被安排进了计划表。用户在打断场景里要求的那个动作: "
                            + "「把增添衣服的计划 event 安排在当下触发, 把写作业这个计划 event "
                            + "安排在增添衣物 event 之后触发」 —— 落在这里就是两条 plan.item-scheduled")
                    .producer("human.life.plan.PlanBoard")
                    .consumer("PlanScheduler; EventStore; 前端日程视图")
                    .build(),

            def("plan.item-due.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("itemId: String, intentType: String, windowStart: Instant, windowEnd: Instant")
                    .semantics("某一项安排的时间到了。用户: 「到了 12:00 agent 就会按照当前时间触发这个"
                            + "写作业 event, 然后 agent 就处于写作业状态了」。"
                            + "注意它是 SENSORY 而不是 SCHEDULED —— <b>已经到点了</b>这件事需要她当下处理, "
                            + "而“有一件事被安排了”不需要")
                    .producer("human.life.plan.PlanScheduler")
                    .consumer("RealtimeEventQueue → Mind")
                    .build(),

            def("plan.item-finished.v1")
                    .category(Category.SCHEDULED)
                    .payload("itemId: String, intentType: String, actualStart: Instant, "
                            + "actualEnd: Instant, outcome: String")
                    .semantics("一项安排做完了(或提前放弃了)。它会腾出后面的时间窗口 —— "
                            + "这是“她写完了作业所以可以早点睡”在架构里的来源")
                    .producer("human.life.plan.PlanScheduler")
                    .consumer("PlanBoard (释放窗口); EventStore")
                    .build(),

            def("plan.item-interrupted.v1")
                    .category(Category.SCHEDULED)
                    .payload("itemId: String, byStimulus: String, interruptedAt: Instant, "
                            + "elapsedMs: long")
                    .semantics("正在做的事被打断了。用户明确要求打断<b>不是</b>"
                            + "「简单把当前在做的计划 event 更新剩余时间然后立马执行一个计划 event, "
                            + "再把被中断的计划 event 继续执行」, 而是“真的改变了计划表, 让 agent "
                            + "重新思考重排计划表”。因此这条事件之后跟着的是一个<b>新的 PlanRevision</b>, "
                            + "而载荷里的 elapsedMs 只是<b>记录</b>, 不是“剩余时长”")
                    .producer("human.mind.decision.DecisionEngine")
                    .consumer("human.life.plan.PlanReplanner; EventStore")
                    .build(),

            def("plan.revision-created.v1")
                    .category(Category.SCHEDULED)
                    .payload("revisionId: String, previousRevisionId: String, "
                            + "mutations: List<PlanMutation>, reason: String")
                    .semantics("计划表产生了一个新版本。用户要求的核心语义: "
                            + "“他是真的改变了计划表, 让 agent 重新思考重排计划表”。"
                            + "<b>旧版本永不删除</b> —— 因为“她上周三的计划长什么样”必须可回答, "
                            + "而那正是行为分析要问的问题")
                    .producer("human.life.plan.PlanBoard")
                    .consumer("PlanScheduler (重新索引); EventStore; 行为分析")
                    .build(),

            // ══════════ object.* / system.* — 世界对象与系统自身 ══════════
            def("object.interaction-started.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("objectId: String, kind: String, force: double")
                    .semantics("她的身体碰到了某个世界对象。触觉通道同时接收外部接触与内部稳态越界 —— "
                            + "见 TactileChannel")
                    .producer("world.object.WorldObject")
                    .consumer("RealtimeEventQueue → TactileChannel")
                    .build(),

            def("object.state-changed.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("objectId: String, from: Map, to: Map, cause: String")
                    .semantics("世界对象状态变化的通用事件。与 device.generic-state-changed 的区别是"
                            + "后者专指设备, 本条约定的是一般对象(门、灯、杯子)")
                    .producer("world.object.WorldObject")
                    .consumer("由对象自己注册的 EventHandler")
                    .build(),

            def("system.clock-tick.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("tickAt: Instant, sequence: long")
                    .semantics("仿真时钟走了一格。<b>它的 channel 是占位符</b> —— 它不改变任何通道, "
                            + "存在的意义是让重放与因果链有一根可对齐的时间轴。"
                            + "它是唯一一条“有类型但没有实际影响”的事件, 而这一点必须写在文档里, "
                            + "否则读代码的人会去找它的效果")
                    .producer("runtime.SimulationClock")
                    .consumer("EventStore (时间轴锚点)")
                    .build(),

            def("system.stimulus-dropped.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("droppedTypeId: String, reason: String, queueSize: int, urgency: double")
                    .semantics("一条刺激因为队列满被丢弃了。<b>它的价值全部在于让“她没反应”可查证</b>: "
                            + "没有它, 行为分析会看到“她没反应”而推断“她不在乎”, "
                            + "而真相是“那条刺激根本没递到她面前” —— 一个会让整个仿真研究"
                            + "得出错误结论的假象")
                    .producer("boundary.event.RealtimeEventQueue")
                    .consumer("行为分析 / 诊断面板")
                    .build(),

            def("system.effect-cancelled.v1")
                    .category(Category.STATE_EFFECT).channel(Channels.COMFORT)
                    .payload("channel: String, cancellationKey: String, originalTypeId: String")
                    .semantics("一条持续影响被显式撤销(脱掉衣服、雨停了)。"
                            + "<b>它不是删除, 是一次新的入账</b>(magnitude = 0) —— "
                            + "因为“她 12:30 之后为什么开始觉得冷”这个问题, "
                            + "只有在历史里能看到“12:30 她脱了外套”时才答得出来")
                    .producer("boundary.event.ContinuousEffectLedger")
                    .consumer("EventStore; 行为分析")
                    .build(),

            def("system.plan-validation-failed.v1")
                    .category(Category.SCHEDULED)
                    .payload("proposedBy: String, reason: String, rejectedPlan: Map")
                    .semantics("LLM 提出的计划没能通过 PlanValidator 的校验。"
                            + "它<b>不能</b>被静默丢弃 —— 一个悄悄失败的规划会在行为分析里"
                            + "表现成“她今天什么都没安排”, 而真相是“她的规划器一直在报错”")
                    .producer("human.life.plan.PlanValidator")
                    .consumer("EventStore; 运维告警")
                    .build(),

            def("system.error.v1")
                    .category(Category.SENSORY).modality(Modalities.TACTILE)
                    .payload("component: String, errorType: String, message: String")
                    .semantics("某个组件出错了。<b>它进刺激队列是刻意的</b> —— "
                            + "一个真人在自己的手机出问题时确实会感到一阵烦躁, "
                            + "而这让“她今天心情不好”至少有一个可查的原因")
                    .producer("任意组件")
                    .consumer("RealtimeEventQueue; 运维告警")
                    .build()
    );

    /** 按类型标识索引的目录。 */
    private static final Map<String, EventSpec> BY_ID;

    /** 按命名空间分组的目录 —— 供文档生成与诊断面板用。 */
    private static final Map<String, List<EventSpec>> BY_NAMESPACE;

    static {
        Map<String, EventSpec> byId = new TreeMap<>();
        Map<String, List<EventSpec>> byNs = new TreeMap<>();
        for (EventSpec spec : CORE) {
            String key = spec.typeId().toString();
            EventSpec previous = byId.put(key, spec);
            if (previous != null) {
                // 目录里出现了重复类型标识 —— 这是一个会在"到底哪条生效"上
                // 产生静默歧义的错误, 因此用异常而不是日志
                throw new IllegalStateException(
                        "CoreEventCatalog 里有重复的类型标识: " + key
                                + " —— 同一类型只能有一条, 否则「用它的时候是哪条」没有答案");
            }
            byNs.computeIfAbsent(spec.typeId().namespace(), k -> new java.util.ArrayList<>())
                    .add(spec);
        }
        BY_ID = Map.copyOf(byId);
        Map<String, List<EventSpec>> frozenNs = new LinkedHashMap<>();
        byNs.forEach((k, v) -> frozenNs.put(k, List.copyOf(v)));
        BY_NAMESPACE = Map.copyOf(frozenNs);
    }

    // ═══════════════════════════ 查询 ═══════════════════════════

    /** 全部内置事件类型。 */
    public static List<EventSpec> all() {
        return CORE;
    }

    /** 按类型标识查。 */
    public static Optional<EventSpec> find(EventTypeId typeId) {
        return typeId == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(typeId.toString()));
    }

    /** 按字符串查 —— 诊断与测试用。 */
    public static Optional<EventSpec> find(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return EventTypeId.tryParse(typeId).flatMap(CoreEventCatalog::find);
    }

    /** 某一类的事件。 */
    public static List<EventSpec> of(Category category) {
        return CORE.stream().filter(s -> s.category() == category).toList();
    }

    /** 某个命名空间下的事件。 */
    public static List<EventSpec> inNamespace(String namespace) {
        return BY_NAMESPACE.getOrDefault(namespace, List.of());
    }

    /** 全部命名空间。 */
    public static Set<String> namespaces() {
        return BY_NAMESPACE.keySet();
    }

    /** 这条类型是不是内置的。第三方类型返回 {@code false}, 这<b>不是</b>错误。 */
    public static boolean isCore(EventTypeId typeId) {
        return find(typeId).isPresent();
    }

    /**
     * <b>没有消费者的内置事件</b>。
     *
     * <p>这个方法存在的意义与 V11 那条"白名单不是空壳"的断言相同:
     * 一条登记了却没人消费的事件, 要么是留给将来的(可以, 但要知道),
     * 要么是某个 handler 的订阅键写错了(必须查)。这个方法让那个区别可被回答,
     * 而不是靠人去逐条比对。
     */
    public static List<EventSpec> unclaimed() {
        return CORE.stream().filter(s -> !s.claimed()).toList();
    }

    /** 按类别统计 —— 诊断面板与文档用。 */
    public static Map<Category, Long> countByCategory() {
        return CORE.stream().collect(Collectors.groupingBy(EventSpec::category,
                () -> new java.util.EnumMap<>(Category.class), Collectors.counting()));
    }

    /**
     * 生成一份人类可读的目录清单。
     *
     * <p>它<b>就是</b>用户要求的那份"都有哪些 event"的答案, 而且它是从运行时的
     * 同一份数据生成的 —— 所以文档不会与实现漂移。这也是"目录是数据不是枚举"
     * 论点的最后一个好处: <b>枚举没法生成文档, 数据可以</b>。
     */
    public static String renderMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 平台内置事件类型目录\n\n");
        Map<Category, Long> counts = countByCategory();
        sb.append("共 ").append(CORE.size()).append(" 条: ");
        for (Category c : Category.values()) {
            sb.append(c.label()).append(" ").append(counts.getOrDefault(c, 0L)).append(" 条  ");
        }
        sb.append("\n\n");

        for (Map.Entry<String, List<EventSpec>> entry : BY_NAMESPACE.entrySet()) {
            sb.append("## ").append(entry.getKey()).append(".*\n\n");
            for (EventSpec spec : entry.getValue()) {
                sb.append("### `").append(spec.typeId()).append("`\n\n");
                sb.append("- **类别**: ").append(spec.category().label())
                        .append(" —— ").append(spec.category().note()).append('\n');
                if (spec.channel() != null) {
                    sb.append("- **通道**: `").append(spec.channel()).append("`\n");
                }
                if (spec.modality() != null) {
                    sb.append("- **感官**: `").append(spec.modality()).append("`\n");
                }
                sb.append("- **载荷**: `").append(spec.payload()).append("`\n");
                sb.append("- **语义**: ").append(spec.semantics()).append('\n');
                sb.append("- **产生**: ").append(spec.producer()).append('\n');
                sb.append("- **消费**: ").append(spec.consumer()).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** 目录里的全部类型标识。 */
    public static Collection<EventTypeId> typeIds() {
        return CORE.stream().map(EventSpec::typeId).toList();
    }
}
