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
import javax.persistence.Table;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * V2.2 §7.2 —— {@code continuous_effect}: <b>A 类事件的落点, {@code ContinuousEffectLedger} 的账页</b>。
 *
 * <h2>账本为什么需要一张表 —— 不存会怎样</h2>
 * {@code ContinuousEffectLedger} 的类注释把"账本是内存里的一个 {@code LinkedHashMap}"
 * 论证得很完整, 但它一个字都没说重启。
 * 进程重启后账本是空的, 于是:
 * <pre>
 *   重启前: 外面 3 度, 她身上穿着羽绒服   → warmth = +0.85 - 0.02 = +0.83
 *   重启后: 账本为空                      → warmth = 0.00  → 她"不冷了"
 * </pre>
 * 而她<b>身上那件羽绒服并没有脱</b>。这是本设计里最刺眼的一类错误:
 * 状态凭空改变, 且没有任何事件解释这个改变。
 *
 * <h2>为什么一行不是"一条事件"而是"一条账目"</h2>
 * 账本与事件流的差别是"替换"({@code cancellationKey}):
 * <pre>
 *   12:00  穿 T恤    key=body.thermal  +0.20   → 记账
 *   12:05  穿羽绒服  key=body.thermal  +0.85   → T恤那条被标 superseded
 * </pre>
 * 若这张表只 append 事件、恢复时靠"重放一遍 {@code book()}"重建, 结果<b>是对的</b>
 * —— 这正是 {@link #sequence} 存在的理由(重放顺序必须稳定)。那为什么还要存
 * {@code superseded} 这个已经能算出来的字段?
 * <table border="1">
 *   <tr><th>做法</th><th>代价</th></tr>
 *   <tr><td>只存事件, 恢复时全量重放</td>
 *       <td>每次重启要把这个 agent 有史以来的<b>每一件</b>影响事件读出来按序重放。
 *           环境每 10 分钟刷新一次, 跑一个月就是四千多条, 而其中绝大多数早已过期。
 *           恢复时间随运行时长线性增长 —— 这是"跑了三个月的实例重启要两分钟"的来源</td></tr>
 *   <tr><td><b>存账目终态（本实现）</b></td>
 *       <td>一行 = 一条账目, {@code superseded} / {@code expires_at} 都是<b>写下来的</b>结果。
 *           恢复只需一次 {@code WHERE human_id = ?} —— 账目总量由"同时挂着多少条影响"
 *           决定(个位数), 与运行了多久无关</td></tr>
 * </table>
 *
 * <p>代价是这张表<b>不是</b>纯 append-only: 收尾时会被 UPDATE 一次
 * ({@code superseded} 从 false 变 true、{@code expires_at} 可能被补上)。
 * 这与 {@code world_event} 的严格 append-only 纪律不同, 所以这里明确写出来:
 * <b>允许的 UPDATE 只有"标记失效"这一种, 且不可逆</b>（false → true 单向）。
 *
 * <h2>为什么 {@code effect_channel} / {@code magnitude} / {@code cancellation_key}
 * 是真正的列, 而不是只躺在 {@code effect_json} 里</h2>
 * 这是本表最重要的一条设计, 也是 §7.2 没写的那部分。三个具体理由:
 * <ol>
 *   <li><b>结算是一个加法, 加法不该依赖反序列化成功。</b>
 *       一批账目的净效应是 {@code Σ magnitude}。若这三个数只能在
 *       {@code effect_json} 反序列化出来之后才拿得到, 那么一旦某个三方的
 *       {@code StateEffectEvent} 实现类不再注册（插件被卸载、包名改了、版本没对上）,
 *       那条账目就会在恢复时被静默跳过 —— <b>她的保暖值少算一件衣服</b>。
 *       拆成列之后最坏的结果是"这条账目的来源类型认不出来了", 而加法仍然正确;</li>
 *   <li><b>{@code cancellation_key} 是替换判定的键。</b>恢复时必须能在
 *       <b>不构造任何事件对象</b>的前提下回答"这两个账目是不是同一族"——
 *       见 {@code ContinuousEffectLedger} 的 {@code activeByKey} 索引。
 *       把键藏在 JSON 里会让那个索引无法重建;</li>
 *   <li><b>{@code effect_channel} 要能按通道筛。</b>
 *       "把保暖通道的账目全捞出来"是一次 {@code WHERE effect_channel = ?},
 *       而不是把她的全部账目读进内存再过滤。</li>
 * </ol>
 *
 * <p>这几列冗余的代价是明确的: 写入时多写三个值, 且<b>它们必须与 {@code effect_json}
 * 保持一致</b>。写入方只有一个（{@code EffectLedgerStore}）, 而读回来时
 * <b>以列为准</b> —— 与 {@code DomainPayloadCodec} 对类型三元组的处理同一条纪律:
 * 当一个值有两份副本时, 必须钉死"哪一份说了算", 否则两边不一致的那一天
 * 没有任何人能判断谁是对的。
 */
@Entity
@Table(name = "continuous_effect", indexes = {
        // ① 恢复账本的主查询: "这个 agent 身上现在挂着哪些账目"。
        //    没有它, 恢复要扫全部 agent 的全部账目 —— 一张随时间增长的表。
        @Index(name = "idx_continuous_effect_human", columnList = "human_id"),
        // ② 替换判定: 同一人 + 同一通道 + 同一 cancellationKey 上谁生效。
        //    列序不是随手写的: human_id 与 effect_channel 都是高选择性的等值列,
        //    cancellation_key 放最后是为了让"按通道查全部账目"(不带 key)也能用上这条索引的前缀。
        @Index(name = "idx_continuous_effect_key",
                columnList = "human_id,effect_channel,cancellation_key"),
        // ③ "这件衣服产生的账目还在不在" —— 撤销与来源追溯。
        @Index(name = "idx_continuous_effect_source_event", columnList = "source_event_id")
})
@Getter
@Setter
public class ContinuousEffectRecord {

    /**
     * 账目 id —— {@code ContinuousEffectLedger.EffectRecord.entryId()}。
     *
     * <p>注意它与 {@code id} 是同一个东西, 不是两个: 账本自己生成 entryId
     * （形如 {@code eff-N}）, 而 {@code source_event_id} 才是"哪条事件产生它的"。
     * §7.2 把这一列写成 {@code id UUID}; 这里跟领域, 因为撤销
     * （{@code ledger.cancel(channel, key, at)}）要靠这个 id 定位账目,
     * 而它已经由领域给出。
     */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 作用在谁身上 —— agent 的 id。 */
    @Column(name = "human_id", nullable = false, length = 36)
    private String humanId;

    /** 影响类型三元组 —— {@code effect_json} 的读法。{@code environment.temperature-changed} 之类。 */
    @Column(name = "effect_namespace", nullable = false, length = 96)
    private String effectNamespace;

    @Column(name = "effect_name", nullable = false, length = 96)
    private String effectName;

    @Column(name = "effect_version", nullable = false)
    private int effectVersion;

    /**
     * 作用通道 —— {@code StateEffectEvent.effectChannel()}, 例如 {@code body.thermal}。
     *
     * <p>见类注释"为什么是真正的列"的第 3 条。取值集合由
     * {@code CoreEventCatalog.channels()} 约定, 但这一列<b>不做校验</b>:
     * 一个三方自定义通道在这里是合法数据, 拒收它等于拒绝扩展性。
     */
    @Column(name = "effect_channel", nullable = false, length = 64)
    private String effectChannel;

    /**
     * 强度与方向 —— {@code StateEffectEvent.magnitude()}。
     *
     * <p>用 {@code DOUBLE} 而不是 {@code BigDecimal}: 它会被<b>累加</b>再交给
     * {@code HomeostasisModel} 做连续量的运算, 而 BigDecimal 的 scale 在连加中
     * 会不断增长（0.1 + 0.2 的 scale 是 2 还是 17 取决于运算次数）——
     * 一个"她的保暖值比较结果取决于加了多少件衣服"的 bug 是极难定位的。
     */
    @Column(name = "magnitude", nullable = false)
    private double magnitude;

    /**
     * 同族身份 —— {@code StateEffectEvent.cancellationKey()}。{@code null} = 不参与替换。
     *
     * <p>见类注释"为什么是真正的列"的第 2 条。
     */
    @Column(name = "cancellation_key", length = 128)
    private String cancellationKey;

    /**
     * 事件载荷 —— 完整的那条 {@code StateEffectEvent}。
     *
     * <p>它承载的是"这条影响是什么"（描述、来源对象、{@code isChange()}…）,
     * 而不只服务于加法 —— 见类注释关于"列与 JSON 的分工"那一段。
     */
    @Convert(converter = StringMapConverter.class)
    @Column(name = "effect_json", columnDefinition = "text")
    private Map<String, Object> effectJson;

    /**
     * 由哪条 {@link WorldEventRecord} 产生。<b>可以为空</b> —— 空是常见情况, 不是异常。
     *
     * <p>什么时候为空: 影响来自一条<b>没有落库</b>的事件 —— 例如她自己执行
     * {@code ActionCommand} 之后穿上一件衣服, 那条 {@code ActionCommand} 的后果事件
     * 可能只走了内存。什么时候非空: 影响来自一条已经写进 {@code world_event} 的事件
     * （环境刷新、设备的通知）。
     *
     * <p>为什么这很重要: 它让"她为什么觉得冷"可以一路追到一条可重放的事件。
     * 为空时追不到, 而"追不到"本身是必须能被看见的信息 —— 用一个假的 UUID 填上它,
     * 会让这条线索永远指向一个不存在的行。
     */
    @Column(name = "source_event_id", length = 36)
    private String sourceEventId;

    /**
     * 入账时刻（仿真时刻）—— {@code ContinuousEffectLedger.book() 的 bookedAt}。
     *
     * <p>§7.2 的列名是 {@code started_at}, 这里沿用它的名字而不是账本里的
     * {@code bookedAt} —— 同一个量, 但表设计里 {@code started_at} 更贴合
     * "这条影响从什么时候开始生效"这个读法。
     *
     * <p>必须存下来而不是恢复时用"现在"补: {@code duration} 是<b>相对</b>入账时刻算的
     * （见 {@code StateEffectEvent.duration()} 的说明）。丢了入账时刻, 一条
     * "跑完步后 20 分钟心率偏高"的影响会在每次重启时重新开始 20 分钟 ——
     * 她永远不会平静下来。
     */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * 失效时刻（仿真时刻）。{@code null} = 永久, 直到被撤销。
     *
     * <p>注意这里是<b>算好的绝对时刻</b>（{@code expiresAt} 优先, 否则
     * {@code bookedAt + duration}）, 而不是原始事件里的那两种表达之一 ——
     * 结算只需要绝对时刻, 而把 "绝对还是相对" 的判定留在恢复路径上是没必要的重复。
     * 判定规则见 {@code ContinuousEffectLedger.resolveExpiry}。
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * 是否已被同 key 的新影响取代。
     *
     * <p>{@code ContinuousEffectLedger} 的纪律: 被取代的账目<b>不删除, 留在历史里</b>
     * ——"她 12:05 换了件厚的"与"她从没穿过 T恤"是两件事。这一列就是那条纪律的存储形态。
     */
    @Column(name = "superseded", nullable = false)
    private boolean superseded;

    /**
     * 同刻定序 —— 重放时的稳定顺序。
     *
     * <p>{@code Instant} 的精度足以区分真实事件, 但同一纳秒入账两条在测试里是常事。
     * 没有这一列, 恢复后的"谁替换了谁"取决于数据库返回行的顺序 ——
     * 那是一个只在生产上偶发、在测试里永远复现不出来的不确定性。
     */
    @Column(name = "sequence", nullable = false)
    private long sequence;

    /** 这一行什么时候被写进来的（墙上时钟）。与 {@code started_at} 是两种时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 这条账目现在还在起作用吗。与 {@code EffectRecord.active(now)} 同一语义。 */
    public boolean activeAt(Instant now) {
        return !superseded && (expiresAt == null || now.isBefore(expiresAt));
    }

    public String describe() {
        return "[" + id + "] " + effectNamespace + "." + effectName
                + " " + effectChannel + (magnitude >= 0 ? " +" : " ") + magnitude
                + (cancellationKey == null ? "" : " key=" + cancellationKey)
                + (superseded ? " (已被取代)" : expiresAt == null ? " (永久)" : " 至 " + expiresAt);
    }
}
