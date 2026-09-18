package com.luxera.companion.persistence.entity;

import com.luxera.companion.common.convert.StringMapConverter;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * V2.2 §7.2 —— {@code world_event}: <b>世界里发生过的每一件事, append-only</b>。
 *
 * <h2>它取代了四张表</h2>
 * §8.3 的迁移表把这件事列成一条:
 * <blockquote>
 * 六个事件词汇表 / 四张事件表 —— 并存 —— 收敛为一张 {@code world_event} + 一个目录
 * </blockquote>
 *
 * <p>本仓现存的事件表有 {@code digital_world_events}（{@code world/WorldEvent}）、
 * {@code world_events}（{@code runtime/WorldEventLog}）、{@code processed_event}、
 * {@code timeline_event}。它们各自的形状差别不大, 差别在<b>类型那一列怎么表达</b>:
 * 有的是自由字符串({@code "ACTIVITY_FINISHED"}), 有的是枚举名({@code AgentEventType} 的一个),
 * 有的是一个 {@code source} + {@code type} 的组合。四张表合起来的效果是
 * <b>"她这一天发生了什么"这个问题要问四遍</b>, 而四次查询的合并逻辑没人写过。
 *
 * <h2>表名为什么是单数 {@code world_event}</h2>
 * 本仓的既有习惯是复数({@code persons} / {@code relationships} / {@code digital_world_events}),
 * 而 §7.2 明写 {@code world_event}。这里<b>照 §7.2 的单数</b>, 理由不是服从文档,
 * 而是具体的一条: 复数的 {@code world_events} <b>已经被 {@code runtime/WorldEventLog} 占了</b>
 * （见 {@code runtime/WorldEventLog} 的 {@code @Table(name = "world_events")}）。
 * 叫 {@code world_events} 会让两条完全不同的链写同一张表 —— 那不是迁移, 那是数据互相踩。
 * 单数名让新旧两张表在 {@code psql} 的 {@code \dt} 里一眼可辨, 也让一个
 * 忘了改表名的查询<b>报错而不是返回半个世界</b>。
 *
 * <p>同一条理由适用于 {@code plan_revision}（旧 {@code plan_revisions} 仍在）。
 * 一旦 §8.3 的"直接替换"走到清理阶段, 旧表被删, 这一处不一致也就自然消失了。
 *
 * <h2>为什么 {@code occurred_at} 与 {@code published_at} 是两个列</h2>
 * 它们回答两个不同的问题, 而合并成一个会让其中一件事无法回答:
 * <table border="1">
 *   <tr><th>列</th><th>回答</th><th>用在哪</th></tr>
 *   <tr>
 *     <td>{@code occurred_at}</td>
 *     <td><b>世界里它什么时候发生的</b>（仿真时刻, 由生产者给）</td>
 *     <td>重放、行为分析、"她 12:15 那会儿怎么了"</td>
 *   </tr>
 *   <tr>
 *     <td>{@code published_at}</td>
 *     <td><b>它什么时候被投进她的意识</b>（{@code null} = 还没投）</td>
 *     <td>崩溃恢复: 重启后要重新投递的正是 {@code published_at IS NULL} 的那些</td>
 *   </tr>
 * </table>
 *
 * <p>合并成一个 {@code occurred_at} 的后果很具体: 进程在"事件已落库、还没投递"之间挂掉,
 * 重启时<b>无从区分</b>"这条已经处理过了"与"这条还没处理" —— 于是要么重复投递
 * （她在一秒内被同一件事惊到两次）, 要么丢掉（她永远没感觉到那次降温）。
 * {@code published_at} 让那个区别变成一个可查询的事实。
 *
 * <h2>为什么 {@code payload_json} 是 {@code text} 而不是 {@code jsonb}</h2>
 * 见 {@code DomainPayloadCodec} 的类注释 —— 那里有三条具体的理由（{@code ddl-auto: update}
 * 建不出、本仓已有 {@code StringMapConverter} 这一个读法、以及 §7.2 要 jsonb 的那个
 * 理由在本仓规模上不成立）。<b>这一处是与文档的显式分歧, 以既有约定为准。</b>
 */
@Entity
@Table(name = "world_event", indexes = {
        // ① "她这段时间经历了什么" —— 重放与行为分析的主查询。
        //    (world_id, occurred_at) 的复合顺序不是随手写的: 等值列在前、范围列在后,
        //    这样才能用一次索引扫描同时完成"哪个 agent"和"哪一段时间"。
        //    反过来 (occurred_at, world_id) 也能用, 但会先扫全部 agent 的时间段再过滤。
        @Index(name = "idx_world_event_world_time", columnList = "world_id,occurred_at"),
        // ② "这一类事件最近发生过吗" —— 诊断与 handler 自检。
        //    没有它, 那类查询会退化成全表扫 —— 而这张表是 append-only 的, 只增不减。
        @Index(name = "idx_world_event_type", columnList = "type_namespace,type_name,major_version"),
        // ③ 崩溃恢复: 捞出所有还没投递的事件重建队列。
        //    刻意<b>不加 partial index 条件</b>({@code WHERE published_at IS NULL}) ——
        //    JPA 的 @Index 表达不了它, 而写一个只有 Hibernate 建表时生效的半吊子条件,
        //    比一个稍大的索引更糟: 它会让"索引到底存不存在"取决于谁建的表。
        @Index(name = "idx_world_event_unpublished", columnList = "world_id,published_at"),
        // ④ "这东西产生过哪些事件" —— 例如一条手机通知引发的全部后续。
        @Index(name = "idx_world_event_source", columnList = "source_object_id")
})
@Getter
@Setter
public class WorldEventRecord {

    /**
     * 事件 id。
     *
     * <p>§7.2 写的是 {@code UUID}。这里存 {@code VARCHAR(36)} —— 因为
     * {@code WorldEvent} 接口<b>没有 id</b>（它是值, 不是行, 见它关于"不是 JPA 实体"的论证）,
     * 于是 id 由本层在落库时生成。用 {@code String} 而不是 {@code java.util.UUID} 的理由是
     * <b>沿用既有约定</b>: 本仓每一张表的 {@code id} 都是
     * {@code @Id @Column(length = 36) private String id} + {@code @PrePersist} 里
     * {@code UUID.randomUUID().toString()}（见 {@code runtime/WorldEventLog}、
     * {@code person/Person}）。用 {@code UUID} 类型会让本表成为唯一一个
     * "id 是 UUID 对象"的表, 而跨表 join 时两边类型不一致的代价是每一次都要写一次转换。
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    /**
     * 这条事件发生在哪个世界里 —— <b>在本仓里就是那个 agent 的 id</b>。
     *
     * <p>§7.2 的命名是 {@code world_id}。为什么不改名成 {@code agent_id}: 因为
     * {@code World} 的座位表是 {@code humanId → placeId / fabric}（见 {@code world/World}
     * 的"世界知道有人在, 不知道她是谁"）, 而本仓用 {@code companion_id} / {@code agent_id}
     * 两个名字指同一件事已经是一次历史遗留了。<b>再加第三个名字不如固定一个</b>,
     * 而 {@code world_id} 至少与 §7.2、与"一个 agent 一个世界实例"这句话对得上。
     */
    @Column(name = "world_id", nullable = false, length = 36)
    private String worldId;

    /** 类型名的命名空间段 —— {@code EventTypeId.namespace()}, 如 {@code device.phone}。 */
    @Column(name = "type_namespace", nullable = false, length = 96)
    private String typeNamespace;

    /** 类型名的名字段 —— {@code EventTypeId.name()}, 如 {@code notification-raised}。 */
    @Column(name = "type_name", nullable = false, length = 96)
    private String typeName;

    /** 主版本 —— {@code EventTypeId.majorVersion()}。**不设默认值**: 一个"忘了填"的 0 会让版本号变成一个摆设。 */
    @Column(name = "major_version", nullable = false)
    private int majorVersion;

    /**
     * 事件类别（{@code STATE_EFFECT} / {@code SENSORY} / {@code SCHEDULED}）。
     *
     * <h2>为什么长度是 64 而不是 §7.2 写的 24</h2>
     * §7.2 自己说明了这个列<b>可以多值、逗号分隔</b>, 同时又给了 24 —— 这两个要求
     * 在当前取值下直接冲突:
     * <pre>
     *   "STATE_EFFECT,SENSORY"            = 20 字符   ← 两值就已逼近 24
     *   "STATE_EFFECT,SENSORY,SCHEDULED"  = 32 字符   ← 三值直接溢出
     * </pre>
     * 一个 24 的列在 Postgres 上会以 {@code value too long for type character varying(24)} 失败,
     * 而那个失败发生在<b>她的事件落库那一刻</b> —— 也就是她正忙的时候。
     * 取 64 是"按最长取值留一倍余量", 不是随手放大。
     *
     * <h2>多值怎么来的</h2>
     * 一个事件可以<b>同时属于多个类别</b> —— {@code environment.temperature-changed.v1}
     * 既是一笔持续影响（A 类, 进账本）也是一条潜在冷刺激的来源（B 类）。
     * 这与 {@code WorldEvent} 的接口形态是同一件事: 它是接口而不是基类,
     * "因为一个事件常常同时属于多个类别"（见该接口的 javadoc）。
     *
     * <p>本列由 {@code CoreEventCatalog.EventSpec.category()} 推出（一个内置事件一个类别）。
     * 一个类型不在目录里时留空 —— 空表示"这个类型是三方带来的, 平台不知道它该进哪个结构",
     * 而那是<b>一条真实的、该被看见的信息</b>, 不是一个错误。
     */
    @Column(name = "category", length = 64)
    private String category;

    /**
     * 载荷 —— 带 {@code _type} 的平铺 JSON（见 {@link com.luxera.companion.persistence.DomainPayloadCodec}）。
     *
     * <p>用 {@code StringMapConverter} 而不是 {@code @Lob String} + 手写解析:
     * 见 {@code DomainPayloadCodec} 关于"本仓已经有一个读法"的那一段。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "payload_json", columnDefinition = "text")
    private Map<String, Object> payloadJson;

    /** 谁产生的 —— {@code WorldEvent.sourceObjectId()}。历史引用, 故意是字符串而不是活对象。 */
    @Column(name = "source_object_id", length = 128)
    private String sourceObjectId;

    /**
     * 世界里它什么时候发生的（仿真时刻）。
     *
     * <h2>为什么是 {@code Instant} 而不是本仓习惯的 {@code LocalDateTime}</h2>
     * 这是本层唯一一处<b>故意不沿用</b>本仓旧列类型的地方, 理由是一个具体的失败:
     * <pre>
     *   领域:   Instant occurredAt = 2026-09-19T04:30:00Z
     *   旧写法: LocalDateTime at = LocalDateTime.ofInstant(occurredAt, ZONE)   // ← 需要时区
     *   读回来: at.atZone(ZONE).toInstant()                                    // ← 需要同一个时区
     * </pre>
     * 只要写入侧与读取侧的时区常量不一致（或者有人改了那个常量、或者 JVM 默认时区不同）,
     * <b>时间就会平移</b>, 而且不会报错。平移的后果是"她 12:15 的计划窗口落在 20:15"——
     * 一个看起来像"她睡过头了"的仿真 bug。
     *
     * <p>{@code Instant} 由 Hibernate 直接映射为 {@code timestamp}（UTC 语义无歧义）,
     * 领域层 {@code 100%} 用 {@code Instant}（见 {@code WorldEvent.occurredAt}、
     * {@code TimeWindow}、{@code Activity.startedAt}）, 于是这一层不引入任何时区常量。
     * 代价是这张表的这一列在裸 {@code psql} 里显示的是 UTC —— 由
     * {@code spring.jpa.properties.hibernate.jdbc.time_zone: Asia/Shanghai} 统一处理。
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 什么时候被投进她的意识。{@code null} = 还没投 —— 崩溃恢复捞的就是这些。 */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * 这一行什么时候被写进来的（<b>墙上时钟</b>, 不是仿真时刻）。
     *
     * <p>它与 {@link #occurredAt} 是两个世界的时间: 前者是"数据库什么时候收到它",
     * 后者是"世界里它什么时候发生"。仿真加速 60 倍时, 前者几乎不变而后者会压缩 ——
     * 两者相减就是"落库这一跳有多慢", 是排查吞吐问题时的唯一线索。
     *
     * <p>这里用 {@code @CreationTimestamp}（Hibernate 读 JVM 时钟）, 沿用本仓既有约定
     * （{@code WorldEventLog.createdAt}、{@code PlanRevision.occurredAt}）。
     * 这一处<b>不违反</b> {@code persistence/package-info} 里那条"本包不读时钟":
     * 那条纪律针对的是<b>仿真时刻</b>, 而审计列记录的是"这一行被写入的物理时刻" ——
     * 用仿真时刻填它, 恰恰会让这条线索失掉意义。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 已经投递过吗 —— 恢复流程的第一个判断。 */
    public boolean published() {
        return publishedAt != null;
    }

    /** 一行摘要, 给日志与诊断用。刻意<b>不含载荷</b> —— 载荷里可能有正文。 */
    public String describe() {
        return typeNamespace + "." + typeName + ".v" + majorVersion
                + "[" + id + "] @ " + occurredAt + (published() ? " 已投递" : " 待投递");
    }
}
