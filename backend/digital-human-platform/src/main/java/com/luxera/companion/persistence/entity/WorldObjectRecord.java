package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §7.2 —— {@code world_object}: <b>世界里一切东西的登记簿</b>。
 *
 * <h2>它存的是"世界的形状", 不是"世界的行为"</h2>
 * 这张表里没有任何一行包含对象的方法。{@code WorldObject} 的类注释把这条边界
 * 论证得很清楚（"Agent 永远拿不到一个 {@code WorldObject} 引用"）:
 * <ul>
 *   <li><b>能存的</b>: {@code id}（{@code ObjectId}）、{@code typeId}
 *       （{@code EventTypeId}）、{@code displayName}、以及它的多态状态;</li>
 *   <li><b>不能存的</b>: 行为。行为在实现类里, 而实现类由 {@code DomainTypeRegistry}
 *       在启动时装配 —— 一个第三方把自己的包从 {@code classpath} 里拿掉之后,
 *       库里那些行依然在, 只是<b>读不回来</b>（见 {@code DomainPayloadCodec}
 *       关于未注册类型的处理: 写一个标记、读回 {@code Optional.empty()} 并告警,
 *       <b>绝不静默丢弃</b>）。</li>
 * </ul>
 *
 * <h2>{@code state_json} 是这张表存在的全部理由</h2>
 * 没有它, "她给手机改了个名"和"实验室离心机转起来了"这两件事就无处可存,
 * 于是每加一种世界对象都要一次 DDL。而 §7.1 已经把这条路堵死:
 * <blockquote>
 * 如果 {@code StudyPlan} / {@code WorkPlan} / … 各建一张表, 会立刻重新形成类型耦合
 * —— 每加一种行为就要一次 DDL。这是 V2.1 §5.4.1 已经论证过的。
 * </blockquote>
 * 对象类型是三方最有可能扩展的一处（灯、门、衣柜、宠物喂食器、未来的机器人）,
 * 所以"加一个对象类型不动 schema"这条属性在这里比在任何一张表上都重要。
 *
 * <h2>为什么 {@code emitsEvents} 没有被存下来</h2>
 * {@code WorldObject.emitsEvents()} 是一个 {@code default} 方法, 它的值是
 * <b>类的属性</b>, 不是实例的属性 —— 同一个类的两个实例不可能一个投事件一个不投。
 * 存成列会制造出第二个真相源: 三方某天把默认值从 {@code true} 改成 {@code false},
 * 而库里那一行还写着 {@code true}。恢复出来的对象于是与它的类<b>不一致</b>,
 * 而"不一致"会比"缺失"更难发现 —— 它会表现成"这个对象偶尔投事件, 偶尔不投"。
 *
 * <h2>关于 §7.2 的 {@code version} 乐观锁列: 本实现<b>没有</b>它, 理由如下</h2>
 * 这是与文档的一处显式分歧, 按"既有约定优先"处理:
 * <pre>
 *   grep -rn "@Version" backend/digital-human-platform/src/main/java   →  0 命中
 *   grep -rn "@Lock\|OptimisticLock"                            →  0 命中
 * </pre>
 * 本仓五十多个实体<b>没有一个</b>带版本列, 包括那些明显会被并发读写的
 * （{@code persona/Companion} 的情感值、{@code relationship/Relationship} 的亲密值）。
 * 只给这一张表加上它, 代价不是"多一列", 而是<b>引入一个只在一个地方存在的失败模式</b>:
 * {@code save()} 会抛 {@code ObjectOptimisticLockingFailureException}, 而
 * 全仓没有任何一处处理过这个异常, 于是它的表现是<b>一次未捕获的 500</b>,
 * 而且恰好发生在"世界正在变"的时候。
 *
 * <p>更具体的一条: 本仓的写路径是<b>单线程 tick</b> —— {@code ContinuousEffectLedger}
 * 的类注释把这条纪律写死了（"同一 agent 的 tick 串行", 并明确拒绝在账本内部加锁,
 * 因为"一个真的被两个 tick 并发结算的账本, 即使每个方法都加锁也仍然是错的"）。
 * 并发控制在调度层, 不在实体层。
 *
 * <p><b>这条判断的代价必须说清楚</b>: 若将来真的出现两个进程同时写同一个
 * {@code world_object}, 丢失更新会静默发生（后写的覆盖先写的, 且不报错）。
 * 触发它的条件是把同一个 agent 跑在两个实例上 —— 而那不是一条受支持的部署形态。
 * 如果那一天到来, 正确的动作是<b>先给全仓加统一的乐观锁策略</b>, 而不是给这一张表打补丁。
 */
@Entity
@Table(name = "world_object", indexes = {
        // ① "这个世界里有哪些对象" —— 世界实例装配时的第一问。
        //    等值列 owner_world_id + 排序/筛选列 display_name。
        //    为什么 display_name 在索引里: 装配时对象要按名字排好显示给她/控制台,
        //    而"给我这个世界的对象, 按名字排"是一次索引扫描就能出结果的形态。
        @Index(name = "idx_world_object_world_name", columnList = "owner_world_id,display_name"),
        // ② "这台设备装了哪些应用" —— 按类型筛对象。
        //    与 world_event 的类型索引同一个形状, 理由是同一件事:
        //    "这一类东西有哪些" 是三方接入后最常问的问题。
        @Index(name = "idx_world_object_type",
                columnList = "object_type_namespace,object_type_name,object_type_version")
})
@Getter
@Setter
public class WorldObjectRecord {

    /**
     * 对象 id —— {@code ObjectId.value()}。
     *
     * <p>{@code ObjectId} 允许带作用域分隔符 {@code :}（{@code device.phone:home-1}）,
     * 所以这一列比 UUID 长。192 是按"命名空间 + 分隔符 + 局部名"留的余量 ——
     * 而"对象 id 能被人写出来"正是 {@code ObjectId} 相对于 UUID 的全部好处,
     * 把长度卡到 36 会让那个好处消失。
     */
    @Id
    @Column(name = "id", length = 192)
    private String id;

    /** 类型三元组 —— 由实现类上的 {@code @DomainType} 经 {@code DomainTypeRegistry} 推出。 */
    @Column(name = "object_type_namespace", nullable = false, length = 96)
    private String objectTypeNamespace;

    @Column(name = "object_type_name", nullable = false, length = 96)
    private String objectTypeName;

    @Column(name = "object_type_version", nullable = false)
    private int objectTypeVersion;

    /**
     * 属于哪个 World —— 在本仓里就是那个 agent 的 id（见 {@code WorldObjectRecord} 的
     * 姊妹表 {@link WorldEventRecord} 关于 {@code world_id} 命名的说明）。
     */
    @Column(name = "owner_world_id", nullable = false, length = 36)
    private String ownerWorldId;

    /**
     * 人类可读名 —— 用于界面与 LLM 的 context。
     *
     * <p>128 而 §7.2 写的就是 128。"她的手机"很短, 而这个名字可以由她或用户改
     * （"给手机改个名"是 {@code WorldObject.displayName()} javadoc 举的例子）,
     * 所以它需要一个能装下自然语言短名的宽度, 而不是 64。
     */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /**
     * 多态状态 —— 这个世界对象此刻长什么样。
     *
     * <p>见类注释"state_json 是这张表存在的全部理由"。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "state_json", columnDefinition = "text")
    private Map<String, Object> stateJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 最后一次改动（墙上时钟）。
     *
     * <p>§7.2 要求 {@code updated_at}, 而本仓的既有约定里这个列是
     * {@code @UpdateTimestamp}（{@code persona/Companion}、{@code person/Person}
     * 都是这个写法）—— 而这个"约定"与 §7.2 <b>一致</b>, 不是分歧。
     * 它与 {@code world_event} 没有 {@code updated_at} 是同一道理:
     * 那张表 append-only, 没有"最后一次改动"这个概念。
     */
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            // 走到这里说明装配流程忘了给对象 id。给一个可辨认的兜底而不是随机 UUID:
            // "obj-unknown-<uuid>" 在库里一眼就是个 bug, 而随机 UUID 看起来像正常数据
            id = "obj-unknown-" + UUID.randomUUID();
        }
    }

    public String describe() {
        return objectTypeNamespace + "." + objectTypeName + "[" + id + "] \"" + displayName + "\"";
    }
}
