package com.luxera.companion.world.digital;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.registry.CoreEventCatalog;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.world.environment.Environment;
import com.luxera.companion.world.object.ObjectId;
import com.luxera.companion.world.object.WorldObject;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §4.1 / §4.4 —— <b>地点</b>: 家、实验室、公司、地铁上。它属于 <b>DigitalWorld</b>。
 *
 * <h2>它为什么属于 DigitalWorld: 判别标准的唯一一次例外讨论</h2>
 * 用户的判别标准只有一条:
 * <pre>
 * Agent 能不能通过一个 ActionCommand 改变它?
 *     能   → DigitalDeviceWorld
 *     不能 → DigitalWorld
 * </pre>
 * {@code Place} 是这条标准上唯一需要解释一下的成员 —— 因为她当然能<b>移动自己</b>
 * ({@code world.move-to} 这类 Action)。但那个 Action 改变的是 <b>她的位置</b>,
 * 不是 {@code Place} 这个对象:"实验室在哪"、"家在哪"是世界固有的属性,
 * 她搬家不会让"家"这个对象消失或者变成别的东西。
 *
 * <p>这个区别在代码里的落点是<b>接口形状</b>: 本类实现 {@link WorldObject} 而
 * <b>不</b>实现 {@code Device} —— 于是它<b>没有</b> {@code capabilities()},
 * 也就没有任何一条从 agent 到它的路径。她不能"让实验室变成晴天",
 * 她只能<b>感知</b>实验室现在的样子, 然后决定自己要做什么(带伞、不出门)。
 * 这正是设计文档 §1.3 P2 说的"不可修改, 只能感知"。
 *
 * <h2>它与 {@link Location} 的分工</h2>
 * 见 {@link Location} 的类注释。一句话: <b>本类有身份, 坐标只是它的一个属性</b>。
 * 传感器报错、坐标被修正、时区被改 —— 这些都不改变"这是实验室"这件事。
 *
 * <h2>它为什么持有一个 {@link Environment}</h2>
 * 因为"她所处地点的天气"这句话里, "地点"是主语、"天气"是它的一个侧面。
 * 于是"她在哪"这个问题一旦回答, "她那儿什么天气"就有了唯一的答案 ——
 * 不需要在任何地方再维护一份 {@code placeId → environment} 的映射表
 * (那种表会在某一次搬家之后与 {@code Place} 自己不一致, 而症状是
 * "她到了实验室, 但收到的还是家里的温度")。
 */
public final class Place implements WorldObject {

    /**
     * 地点这个对象类型的标识。
     *
     * <p>命名空间用 {@code world} 而不是 {@code place}: 与
     * {@code device.phone} 是"设备世界里的手机"同构, {@code world.place} 表达的是
     * "环境世界里的地点"。域名里<b>不</b>出现 {@code digital} ——
     * 世界的二分是设计上的分类, 不是类型名的一部分; 把它塞进 id 会让
     * 以后"某个对象在两个世界之间换边"变成一次 id 迁移。
     */
    public static final EventTypeId TYPE = EventTypeId.of("world", "place");

    /** 平台约定的地点 id 命名空间 —— 装配时用 {@code ObjectId.of(NAMESPACE, "lab")}。 */
    public static final String ID_NAMESPACE = "world.place";

    private final ObjectId id;
    private final String displayName;
    private final boolean indoor;
    private final Map<String, Object> attributes;

    /** 坐标 —— 可被修正, 所以不是 final 的语义而是"换一个新的"。 */
    private volatile Location location;

    /** 这个地方的环境。可以是空的("还没接上气象数据"), 而那不是错误状态。 */
    private volatile Environment environment;

    /**
     * 完整构造器。
     *
     * @param id          地点身份
     * @param displayName 给她与 LLM 看的名字("实验室")
     * @param location    坐标
     * @param indoor      室内还是室外。<b>它不是装饰性字段</b>: 它决定
     *                    {@code EnvironmentSnapshot} 里光照与降水要不要折算到她身上
     *                    —— 一个在室内的人不会被雨淋到, 但她仍然能听见雨声, 也能从
     *                    窗户看出天黑了
     * @param attributes  自由属性(楼层、房间号、有没有网络)。刻意是 Map 而不是
     *                    强类型字段: "一个地点有哪些属性"会随世界生长, 而 P4 禁止
     *                    用类型/enum 去表达会生长的那部分
     */
    public Place(ObjectId id, String displayName, Location location, boolean indoor,
                 Map<String, Object> attributes) {
        this.id = Objects.requireNonNull(id, "地点必须有身份");
        this.displayName = displayName == null || displayName.isBlank() ? id.localName() : displayName;
        this.location = Objects.requireNonNull(location,
                "地点必须有坐标 —— 没有坐标的地点查不了天气, 而'她的环境'正是由坐标决定的");
        this.indoor = indoor;
        this.attributes = attributes == null || attributes.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    // ─────────────────────────── 构造 ───────────────────────────

    /** 一个室外的地点 —— 用 id 的本地名当显示名。 */
    public static Place of(String localName, Location location) {
        return new Place(ObjectId.of(ID_NAMESPACE, localName), localName, location, false, Map.of());
    }

    /** 一个有名字、有室内外属性的地点。 */
    public static Place of(String localName, String displayName, Location location, boolean indoor) {
        return new Place(ObjectId.of(ID_NAMESPACE, localName), displayName, location, indoor, Map.of());
    }

    /** 屋里 —— "家"、"实验室"。 */
    public static Place indoor(String localName, String displayName, Location location) {
        return new Place(ObjectId.of(ID_NAMESPACE, localName), displayName, location, true, Map.of());
    }

    /** 露天 —— "公园"、"地铁站口"。 */
    public static Place outdoor(String localName, String displayName, Location location) {
        return new Place(ObjectId.of(ID_NAMESPACE, localName), displayName, location, false, Map.of());
    }

    // ─────────────────────────── WorldObject ───────────────────────────

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

    /**
     * 本地点确实会产生事件 —— 但只有一类: {@code environment.location-changed.v1},
     * 即"她从这个地方到了那个地方"(见 {@link LocationChanged})。
     *
     * <p>覆盖它的默认值(true)在这里是<b>有意义的声明</b>而不是复述:
     * 它明确地说出了"地点唯一的事件是关于移动的, 而不是关于它自己变化的" ——
     * 因为 {@code Place} 本身不会变(它不可被 agent 修改)。一个真会变的地点
     * (比如"施工中的路")是另一个对象, 不是本类的一个字段。
     */
    @Override
    public boolean emitsEvents() {
        return true;
    }

    @Override
    public String describe() {
        return displayName + "(" + id.value() + (indoor ? " 室内" : " 露天") + " "
                + location.describe()
                + (environment == null ? " 无环境数据" : " 有环境数据") + ")";
    }

    // ─────────────────────────── 坐标与环境 ───────────────────────────

    public Location location() {
        return location;
    }

    public boolean indoor() {
        return indoor;
    }

    /** 自由属性 —— 只读快照。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    /**
     * 修正坐标。
     *
     * <p>它<b>不</b>投事件: "我们修正了一个录入错误"与"她换了个地方"是两件完全不同的事,
     * 而后者才是 {@code environment.location-changed.v1} 要表达的东西。
     * 把它们混在一起的后果是历史里出现"她搬到实验室去了"这种从未发生过的叙述。
     */
    public void correctLocation(Location corrected) {
        this.location = Objects.requireNonNull(corrected, "修正后的坐标不能为空");
    }

    /**
     * 接上这个地方的环境。
     *
     * <p>刻意<b>不</b>在构造器里要求一个 {@code Environment}: 一个地点的环境数据来自
     * 外部气象服务, 而"气象服务现在拿不到数据"不该阻止她把"家"这个概念装进世界。
     * 装配顺序因此是"先建地点, 再接环境" —— 与 {@code Phone} 先建壳再补通知系统
     * 是同一类两步装配(理由也一样: 环状依赖要用装配顺序解决, 不要用 setter 的
     * 可空性去掩盖)。
     */
    public void attachEnvironment(Environment attaching) {
        this.environment = Objects.requireNonNull(attaching, "要接上的环境不能为空");
    }

    /**
     * 这个地方的环境 —— 空表示"还没接上气象数据"。
     *
     * <p>用 {@code Optional} 而不是一个默认的空环境: 一个"什么数据都没有"的环境
     * 会让 {@code environment.temperature-changed.v1} 投出 0℃, 而她会因此真的觉得冷。
     * <b>"没有数据"与"数据是 0"必须能分开</b> —— 这条纪律在 Body 那一侧同样成立。
     */
    public Optional<Environment> environment() {
        return Optional.ofNullable(environment);
    }

    /**
     * 这个地方的环境 id —— 给需要"按 id 查环境"的调用方(诊断、重放、事件载荷)。
     *
     * <p>返回 {@code Optional} 而不是一个兜底 id: 兜底 id 查不回来东西,
     * 而调用方会以为"环境存在但没有数据"。
     */
    public Optional<ObjectId> environmentId() {
        return environment().map(Environment::id);
    }

    // ─────────────────────────── 几何 ───────────────────────────

    /** 到另一个地点有多远(公里)。 */
    public double distanceKmTo(Place other) {
        Objects.requireNonNull(other, "要算距离必须给另一个地点");
        return location.distanceKmTo(other.location);
    }

    /**
     * 是不是同一个地方 —— 按 id 判断, <b>不</b>按坐标判断。
     *
     * <p>两个 {@code Place} 的坐标可能完全一样(同一栋楼里的两个房间),
     * 而它们是两个地方; 同一个 {@code Place} 的坐标被修正之后,
     * 它还是同一个地方。所以这里的判据只能是 id。
     */
    public boolean sameAs(Place other) {
        return other != null && id.equals(other.id);
    }

    /**
     * 到另一个地点大概要多久。
     *
     * <p>用它而不是让调用方自己除: 速度的默认值(30 km/h)是一个<b>关于城市的假设</b>
     * (含等车与红绿灯), 把它写在调用点会让"她 8:00 出发能不能 9:00 到实验室"
     * 这个判断在每一处都略有不同。
     *
     * @param other 目标地点
     * @param kilometersPerHour 用什么速度(走路 5、骑车 15、开车 40); 空则用 30
     */
    public java.time.Duration roughTravelTimeTo(Place other, Double kilometersPerHour) {
        Objects.requireNonNull(other, "要算行程必须给目标地点");
        double speed = kilometersPerHour == null || kilometersPerHour <= 0 ? 30.0 : kilometersPerHour;
        double hours = distanceKmTo(other) / speed;
        return java.time.Duration.ofMinutes(Math.round(hours * 60));
    }

    // ═══════════════════════════ 移动这件事 ═══════════════════════════

    /**
     * {@code environment.location-changed.v1} —— <b>她换了个地方</b>。
     *
     * <h2>为什么这条事件由"地点"这一侧投, 而不是由她投</h2>
     * 因为它是<b>世界</b>在陈述一个事实: "她从家到了实验室"。她(C 类: 计划)
     * 那边当然也有"我要去实验室"的意图, 但意图与事实是两件事 ——
     * 意图可能失败(地铁停运), 而世界这里记录的是真的发生了的那一次。
     *
     * <p>目录里这条事件的 producer 写的是 {@code world.digital.Place / AgentMovement} ——
     * 本类就是那个 {@code Place} 一侧; {@code AgentMovement}(移动的执行者)属于
     * 装配层, 它调用 {@link #move} 把事实投出去。
     *
     * <h2>为什么它同时触发一次环境重抓</h2>
     * 因为新地方的天气与旧地方无关 —— 没有这一条, 她到了实验室却仍然收到家里的温度。
     * 重抓的触发点在世界那一侧(它知道谁在哪), 见 {@code World} 的推进逻辑。
     *
     * @param fabric 投给谁 —— 这条事件是投给她一个人的(世界知道她在哪), 所以是
     *               {@code EventFabric}, 而不是一个广播器
     * @param from   从哪
     * @param to     到哪
     * @param reason 为什么移动(她自己决定的/被人叫走的/赶时间)。它是一个自由字符串 ——
     *               "为什么移动"会随生态生长, 不是可以枚举完的东西
     */
    public static LocationChanged move(EventFabric fabric, Place from, Place to, String reason,
                                       Instant at) {
        Objects.requireNonNull(fabric, "移动事件必须有一条投递通道 —— 事件要投给具体的人");
        LocationChanged event = LocationChanged.of(from, to, reason, at);
        fabric.publish(event);
        return event;
    }

    /**
     * {@code environment.location-changed.v1} 的载荷。
     *
     * <h2>它为什么是 B 类(实时感官)而不是 A 类(持续影响)</h2>
     * 因为"换了个地方"是<b>发生了一次</b>的事, 不是"一直成立"的状态 ——
     * 她一进门就注意到了(视觉上环境变了), 而注意力系统需要当下就处理它
     * (她在实验室里需要知道"这里比家里冷")。持续影响那一侧由环境的
     * 温度/湿度事件承担, 它们会各自入账。
     *
     * <h2>载荷里为什么只有 id</h2>
     * 与所有事件的纪律一致: 事件载荷是<b>已经落库的历史</b>, 它不该把活对象钉在内存里。
     * 谁想看得更细, 就拿 id 去查那个 {@code Place} —— 而那时看到的是<b>现在</b>的样子,
     * 不是当时的样子。这个区别很重要: "她当时的实验室"与"现在的实验室"在建了
     * 新设备之后不是同一个东西, 历史要能回答前者。
     */
    @DomainType("environment.location-changed")
    public record LocationChanged(String fromPlaceId, String toPlaceId, String reason,
                                 Instant occurredAt) implements SensoryEvent {

        public static final EventTypeId TYPE = EventTypeId.of("environment", "location-changed");

        public LocationChanged {
            Objects.requireNonNull(occurredAt, "事件必须带发生时刻 —— 仿真时钟下不许读墙上时钟");
            if (fromPlaceId == null && toPlaceId == null) {
                throw new IllegalArgumentException(
                        "从一个地点到另一个地点 —— 两边都空的话这条事件什么都没说");
            }
            reason = reason == null || reason.isBlank() ? "unspecified" : reason;
        }

        public static LocationChanged of(Place from, Place to, String reason, Instant at) {
            return new LocationChanged(from == null ? null : from.id().value(),
                    to == null ? null : to.id().value(), reason, at);
        }

        @Override
        public EventTypeId typeId() {
            return TYPE;
        }

        /** 来源是<b>到达的那个地方</b> —— 她是"到了实验室"才看见这一幕的。 */
        @Override
        public String sourceObjectId() {
            return toPlaceId;
        }

        @Override
        public String modality() {
            return CoreEventCatalog.Modalities.VISUAL;
        }

        /**
         * 0.4 —— 换地方是一种"背景变化": 她会注意到, 但它不会像警报那样抢走注意力。
         *
         * <p>给太高会让"她一天里去了 6 个地方"变成 6 次打断;
         * 给太低则她到了新环境却毫无察觉, 而那会让"她在实验室里穿着羽绒服"变得无法解释。
         */
        @Override
        public double urgency() {
            return 0.4;
        }

        /**
         * {@code null} —— 不参与折叠。
         *
         * <p>两次移动是两件事, 而不是"同一件事的延续"。给一个折叠键会让
         * "她去公司又去了医院"在后一条到达时把前一条挤掉, 于是她的一天里只剩最后一次移动
         * —— 而"她中途还在公司"这个事实本该影响她回到家时的状态。
         */
        @Override
        public String foldingKey() {
            return null;
        }

        @Override
        public String describe() {
            return "移动 " + (fromPlaceId == null ? "?" : fromPlaceId)
                    + " → " + (toPlaceId == null ? "?" : toPlaceId) + "(" + reason + ")";
        }
    }
}
