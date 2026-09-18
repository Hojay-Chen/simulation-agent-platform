package com.luxera.companion.persistence.store;

import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.DeviceApplicationRecord;
import com.luxera.companion.persistence.entity.WorldObjectRecord;
import com.luxera.companion.persistence.repository.DeviceApplicationRecordRepository;
import com.luxera.companion.persistence.repository.WorldObjectRecordRepository;
import com.luxera.companion.world.application.DeviceApplication;
import com.luxera.companion.world.object.ObjectId;
import com.luxera.companion.world.object.WorldObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code world_object} 与 {@code application} 的读写路径:
 * <b>"她的世界里有什么"</b>。
 *
 * <h2>为什么这两个放在同一个 store 里</h2>
 * 它们是<b>同一次装配</b>的两半: 世界装配出对象（手机、房间、窗外的天气）,
 * 手机再把应用装上（聊天、日历）。把它们拆成两个 store 的唯一后果是
 * 调用方每次都要同时注入两个 —— 而"装配"这件事在调用方看来是一件事。
 *
 * <h2>为什么世界对象能读回来, 而设备应用<b>只写不读</b></h2>
 * 这是本类最重要的一条判断, 它由两种东西的本性决定:
 * <table border="1">
 *   <tr><th></th><th>{@code WorldObject}</th><th>{@code DeviceApplication}</th></tr>
 *   <tr><td>是什么</td><td>值对象: id、类型、名字、状态</td>
 *       <td><b>活对象</b>: 它持有能力、持有装在哪台设备上的引用,
 *           还有 {@code onAttach} / {@code onDetach} 两个生命周期回调</td></tr>
 *   <tr><td>反序列化出来的东西是什么</td>
 *       <td>一个等价的世界对象</td>
 *       <td><b>一个没有挂到任何设备上的空壳</b>: 它的能力没有注册进
 *           {@code CapabilityRegistry}, {@code onAttach} 没有被调过,
 *           而 {@code onDetach} 会对一个它从没连上的东西做清理</td></tr>
 * </table>
 * 于是 {@code application} 表的用途是<b>清单与诊断</b>:
 * "她这台手机上装了什么"、"这个应用是谁装的、什么版本"。真正的装配永远由
 * 领域代码重新执行一遍（{@code Phone.install}）, 而不是从 JSON 里复原 ——
 * 复原出来的应用会在第一次收到通知时暴露它是个空壳, 而那时的报错
 * 离根因（"有人以为应用可以从 JSON 建出来"）非常远。
 *
 * <p>换句话说: 这张表是<b>账</b>, 不是<b>工厂</b>。把这一点写清楚,
 * 是为了让下一个人<b>不要</b>顺手加一个 {@code readApplication} ——
 * 那个方法的签名会很有诱惑力, 而它会安静地产出一个坏对象。
 *
 * <h2>{@code application} 与 {@code world_object} 的关系</h2>
 * 应用<b>不是</b>世界对象: 它没有 {@code ObjectId}, 没有 {@code emitsEvents}。
 * 它挂在一台设备（那台设备是世界对象）上, 由 {@code device_id} 关联。
 * 本仓实体之间没有外键, 所以 {@code device_id} 是一个裸列 ——
 * 见 {@code WorldObjectRecord} 的类注释关于"没有 {@code @ManyToOne}"的说明。
 */
@Slf4j
public class WorldObjectStore {

    private final WorldObjectRecordRepository objects;
    private final DeviceApplicationRecordRepository applications;
    private final DomainPayloadCodec codec;

    public WorldObjectStore(WorldObjectRecordRepository objects,
                            DeviceApplicationRecordRepository applications,
                            DomainPayloadCodec codec) {
        this.objects = Objects.requireNonNull(objects, "世界对象仓库不能为空");
        this.applications = Objects.requireNonNull(applications, "应用仓库不能为空");
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
    }

    // ─────────────────────────── 世界对象 ───────────────────────────

    /**
     * 把这个对象登记进这个世界。
     *
     * <p>{@code worldId} 是参数而不是从对象上取: {@link WorldObject} 没有
     * "我在哪个世界"这个成员（一个"手机"这个类型不该知道是哪一台）。
     *
     * <p>主键就是 {@code ObjectId.value()}（形如 {@code phone:main}）——
     * 不是另发一个 UUID: 世界对象在领域里已经有稳定标识, 而两份 id 之间的映射
     * 会让"日志里那个 id 在库里查不到"成为一类纯人为的故障。
     */
    public WorldObjectRecord register(String worldId, WorldObject object) {
        Objects.requireNonNull(worldId, "对象必须属于某一个世界");
        Objects.requireNonNull(object, "要登记的对象不能为空");

        DomainPayloadCodec.PersistedForm form = codec.write(object);
        WorldObjectRecord record = new WorldObjectRecord();
        record.setId(object.id().value());
        record.setObjectTypeNamespace(form.typeNamespace());
        record.setObjectTypeName(form.typeName());
        record.setObjectTypeVersion(form.majorVersion());
        record.setOwnerWorldId(worldId);
        record.setDisplayName(object.displayName());
        record.setStateJson(form.payload());
        return objects.save(record);
    }

    /**
     * 这个世界里现在有什么 —— 按名字排, 一次读齐。
     *
     * <p>用宽容读（读不回来的单独列出）而不是会抛的读: 一个三方设备类型被卸载之后,
     * 它的历史行还在库里, 而"这个世界里还有别的对象"是事实。
     * 让整次装配因为其中一个对象读不出来而失败, 等于让"卸载一个插件"
     * 变成"她起不来"。
     */
    public WorldObjectStoreResult objectsOf(String worldId) {
        Objects.requireNonNull(worldId, "必须指明是哪个世界");
        List<WorldObjectRecord> rows = objects.findByOwnerWorldIdOrderByDisplayNameAsc(worldId);
        List<WorldObject> values = new ArrayList<>(rows.size());
        List<WorldObjectRecord> unreadable = new ArrayList<>();
        for (WorldObjectRecord row : rows) {
            Optional<Object> value = codec.tryRead(row.getObjectTypeNamespace(),
                    row.getObjectTypeName(), row.getObjectTypeVersion(), row.getStateJson());
            if (value.isPresent() && value.get() instanceof WorldObject object) {
                values.add(object);
            } else {
                unreadable.add(row);
            }
        }
        if (!unreadable.isEmpty()) {
            log.warn("[Persistence] 世界 {} 里有 {} 个对象的类型读不回来 —— "
                            + "它们不会出现在装配结果里, 而'她家里少了一个台灯'"
                            + "是这类故障唯一的表现。受影响的 id: {}",
                    worldId, unreadable.size(), idsOf(unreadable));
        }
        return new WorldObjectStoreResult(List.copyOf(values), List.copyOf(unreadable));
    }

    /** 这个对象在吗 —— 一条事件引用了某个对象 id 时, 判它是不是真的。 */
    public boolean has(String worldId, String objectId) {
        Objects.requireNonNull(worldId, "必须指明是哪个世界");
        Objects.requireNonNull(objectId, "对象 id 不能为空");
        return objects.existsByOwnerWorldIdAndId(worldId, objectId);
    }

    // ─────────────────────────── 设备应用 ───────────────────────────

    /**
     * 这台设备上装了这个应用 —— <b>记账</b>（见类注释"只写不读"）。
     *
     * <p>两个标识都被存下来, 而它们不是一回事:
     * <pre>
     *   application_key        = "chat"        ← 应用自己声明的稳定标识, 通知靠它认人
     *   application_type_*     = "device.application.chat.v1"  ← 实现类上的 @DomainType
     * </pre>
     * {@code DeviceApplication} 接口<b>没有</b> {@code typeId()} 方法
     * （不像 {@code WorldObject} / {@code WorldEvent}）, 于是类型名由
     * {@code DomainTypeRegistry} 从注解反推。这意味着两件事必须写清楚:
     * <ul>
     *   <li>一个三方应用如果<b>没有</b>标 {@code @DomainType}, 它的类型列会是空的
     *       （{@code _untyped}）—— 而 {@code application_key} 仍然是对的。
     *       这正是把两个标识都存下来的价值: 认不出类型时, 清单还能用;</li>
     *   <li>"{@code application_key} 与类型名目前不重合"是一句<b>会过期</b>的话:
     *       它们由两个不同的地方给, 将来完全可能有人让它们一致。本层不做
     *       "有 key 就不用存类型"这种优化 —— 那种优化的前提是两者等价,
     *       而它们不等价。</li>
     * </ul>
     */
    public DeviceApplicationRecord attach(String deviceId, DeviceApplication application) {
        Objects.requireNonNull(deviceId, "应用必须装在某台设备上");
        Objects.requireNonNull(application, "要记账的应用不能为空");
        Objects.requireNonNull(application.descriptor(),
                "应用必须能介绍自己 —— 没有描述的清单对人没有用");

        DomainPayloadCodec.PersistedForm form = codec.write(application);
        DeviceApplicationRecord record = new DeviceApplicationRecord();
        record.setDeviceId(deviceId);
        record.setApplicationKey(application.descriptor().applicationKey());
        record.setApplicationTypeNamespace(form.typeNamespace());
        record.setApplicationTypeName(form.typeName());
        record.setApplicationTypeVersion(form.majorVersion());
        // 状态 JSON 写的是描述而不是应用本身: 应用对象里可能挂着设备引用与能力表,
        // 而它们都不是"状态"。描述是应用愿意对外说的那一部分。
        //
        // 刻意<b>不走</b> {@code codec.write(...)}: 那会给这一份 JSON 打上
        // {@code _untyped: …Descriptor} —— 而 {@code Descriptor} 是一个嵌套 record,
        // 它永远不会有 {@code @DomainType}（它不是领域类型, 只是应用的一句自我介绍）。
        // 打一个"没有类型"的标记在数据里, 只会让读它的人去找一个不存在的类型
        record.setStateJson(toMap(application.descriptor()));
        return applications.save(record);
    }

    /**
     * 这台设备装了哪些应用 —— 按 key 排。
     *
     * <p>这一问在<b>装配</b>路径上的用途是"这台设备应该有哪些应用"的核对,
     * 而不是"用这些行去构造应用"（见类注释）。
     */
    public List<DeviceApplicationRecord> installedOn(String deviceId) {
        Objects.requireNonNull(deviceId, "必须指明是哪台设备");
        return applications.findByDeviceIdOrderByApplicationKeyAsc(deviceId);
    }

    /** 这台设备上装了某个应用吗。 */
    public Optional<DeviceApplicationRecord> application(String deviceId, String applicationKey) {
        Objects.requireNonNull(deviceId, "必须指明是哪台设备");
        Objects.requireNonNull(applicationKey, "应用 key 不能为空");
        return applications.findByDeviceIdAndApplicationKey(deviceId, applicationKey);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 一个普通对象 → 可存进 {@code text} 列的 Map。
     *
     * <p>用<b>序列化器背后的同一个 {@code ObjectMapper}</b>（{@code codec.serializer().mapper()}）
     * 而不是 {@code new ObjectMapper()}: 两个 mapper 意味着两套配置, 而"写的时候用 A、
     * 读的时候用 B"的差异会只体现在某几个字段上（时间格式、null 的处理）——
     * 那是一种最难归因的不一致。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return codec.serializer().mapper().convertValue(value, Map.class);
    }

    private static List<String> idsOf(List<WorldObjectRecord> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (WorldObjectRecord row : rows) {
            out.add(row.getId());
        }
        return out;
    }

    /** 诊断: 这个世界里有什么。 */
    public String describe(String worldId) {
        Objects.requireNonNull(worldId, "必须指明是哪个世界");
        List<WorldObjectRecord> rows = objects.findByOwnerWorldIdOrderByDisplayNameAsc(worldId);
        StringBuilder sb = new StringBuilder("[WorldObjectStore] ").append(worldId)
                .append(" 共 ").append(rows.size()).append(" 个对象");
        for (WorldObjectRecord row : rows) {
            sb.append("\n  · ").append(row.describe());
        }
        return sb.toString();
    }

    /**
     * 一次装配读到的东西: <b>读回来的对象</b>与<b>读不回来的行</b>。
     *
     * <p>与 {@code WorldEventStore.ReadResult} 是同一个形状, 但刻意不复用同一个类型:
     * 两个结果的"读不回来"含义不同（对象读不回来是"她的房间里少了一个台灯",
     * 事件读不回来是"她漏掉了一件事"）, 而共用一个类型会让调用方把它们当同一回事处理。
     */
    public record WorldObjectStoreResult(List<WorldObject> objects,
                                         List<WorldObjectRecord> unreadable) {

        public WorldObjectStoreResult {
            objects = objects == null ? List.of() : List.copyOf(objects);
            unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
        }

        /** 装配完整吗。 */
        public boolean complete() {
            return unreadable.isEmpty();
        }

        /** 按 {@code ObjectId} 找装配出来的那个对象 —— 装配流程的常见后续。 */
        public Optional<WorldObject> byId(ObjectId id) {
            Objects.requireNonNull(id, "要查的对象 id 不能为空");
            return objects.stream().filter(o -> o.id().equals(id)).findFirst();
        }
    }
}
