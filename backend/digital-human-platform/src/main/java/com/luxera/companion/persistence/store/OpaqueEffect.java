package com.luxera.companion.persistence.store;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.persistence.entity.ContinuousEffectRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * V2.2 §7.2 —— <b>一条读不回来的账目, 但仍然算得进加法的那份替身</b>。
 *
 * <h2>它为什么必须存在（而不能"跳过读不回来的行"）</h2>
 * {@code ContinuousEffectRecord} 的类注释把这件事论证过一次, 这里是它在代码里的落点:
 * <pre>
 *   外面 3 度, 她穿着羽绒服:
 *     environment.temperature-changed.v1   warmth -0.02
 *     device.clothing-worn.v1              warmth +0.85   ← 三方插件产生的
 *
 *   插件被卸载, 第二条读不回来:
 *     跳过它 → warmth = -0.02  → <b>她"不冷了"</b>
 *     用它   → warmth = +0.83  → 正确
 * </pre>
 * 而她身上那件羽绒服<b>并没有脱</b>。这是本设计里最刺眼的一类错误:
 * 状态凭空改变, 且没有任何事件解释这个改变。
 *
 * <h2>它凭什么算得对 —— 因为四个数在列上, 不在 JSON 里</h2>
 * 净效应是 {@code Σ magnitude}, 而 {@code magnitude} / {@code effect_channel} /
 * {@code cancellation_key} / {@code expires_at} 四个值都是 {@code continuous_effect}
 * 表上的<b>真列</b>（见 {@code ContinuousEffectRecord} 的类注释
 * "为什么这三个是真正的列"）。于是重建一条账目<b>不需要反序列化成功</b> ——
 * 只需要读四列。这正是那四列存在的理由, 而本类是被那个理由逼出来的东西。
 *
 * <h2>它丢了什么 —— 必须写清楚</h2>
 * <table border="1">
 *   <tr><th></th><th>读得回来时</th><th>用本类重建时</th></tr>
 *   <tr><td>加法</td><td>正确</td><td><b>正确</b></td></tr>
 *   <tr><td>替换判定</td><td>正确</td><td><b>正确</b>（{@code cancellationKey} 在列上）</td></tr>
 *   <tr><td>过期</td><td>正确</td><td><b>正确</b>（{@code expiresAt} 在列上）</td></tr>
 *   <tr><td>{@code Settlement.why(channel)} 的可读解释</td>
 *       <td>"{@code device.clothing-worn.v1} 贡献了 0.85"</td>
 *       <td><b>退化</b>: 只能说"有一条 0.85 的账目, 类型认不出来"</td></tr>
 * </table>
 * 最后一行是<b>可接受的损失</b>, 而前两行的任何一行出问题都是不可接受的 ——
 * 所以这个取舍的方向是明确的: 先保证她的状态对, 再谈解释得清不清楚。
 * 而"解释不清"这件事本身是可观测的: {@code EffectLedgerStore.restore} 会为
 * 每一条这样的账目记一条 WARN, 数量就是"有多少个三方类型已经没人认识了"。
 *
 * <h2>它<b>绝不</b>会被写回数据库</h2>
 * {@code EffectLedgerStore.snapshot} 在写入路径上会检查这件事并直接抛异常
 * （见那里的说明）。理由: 本类的类型名是<b>合成的</b>（原本那个三方类型已经
 * 认不出来了）, 把它写回去等于用"认不出来"覆盖掉"原本是什么" ——
 * 而那一列 JSON 是恢复这个类型的<b>唯一</b>线索, 一旦被覆盖, 插件重新装回来
 * 也救不回这些账目了。
 */
public final class OpaqueEffect implements StateEffectEvent {

    /**
     * 类型认不出来（或"库里已标失效"）时用的合成类型名。
     *
     * <p>它落在 {@code system} 命名空间而不是伪造一个三方的名字: 一个
     * {@code petfeeder.} 开头的假名字会让"这个类型是谁的"这个问题有答案,
     * 而那个答案是错的。用 {@code system} 明说"这是本层造出来的一个替身"。
     *
     * <p>{@link #invalidated} 造出来的那些也挂这个名字（理由见那里）——
     * "这一条是什么"与"这一条为什么不生效"是两件事, 后者在
     * {@link #unreadableReason()} 里, 不该占用类型名。
     */
    public static final EventTypeId TYPE = EventTypeId.of("system", "effect-unreadable");

    private final EventTypeId typeId;
    private final Instant occurredAt;
    private final String sourceObjectId;
    private final double magnitude;
    private final String effectChannel;
    private final Instant expiresAt;
    private final String cancellationKey;
    private final String unreadableReason;

    private OpaqueEffect(EventTypeId typeId, Instant occurredAt, String sourceObjectId,
                         double magnitude, String effectChannel, Instant expiresAt,
                         String cancellationKey, String unreadableReason) {
        this.typeId = Objects.requireNonNull(typeId, "替身也要有一个类型名 —— "
                + "它至少要说清'这是一个认不出来的东西'");
        this.occurredAt = Objects.requireNonNull(occurredAt, "账目必须有入账时刻");
        this.sourceObjectId = sourceObjectId;
        this.magnitude = magnitude;
        this.effectChannel = Objects.requireNonNull(effectChannel,
                "通道必须在 —— 没有通道的账目算不进任何一次结算, 那样它就等于不存在");
        this.expiresAt = expiresAt;
        this.cancellationKey = cancellationKey;
        this.unreadableReason = unreadableReason == null ? "" : unreadableReason;
    }

    /**
     * 从那一行造一个替身。
     *
     * <p>{@code occurredAt} 取 {@code started_at}（也就是账目的入账时刻）——
     * 不是"这条影响什么时候开始生效"的近似, 而是账本里那个值:
     * {@code ContinuousEffectLedger.book(event, bookedAt)} 收到的是同一个时刻。
     */
    public static OpaqueEffect of(ContinuousEffectRecord row, String unreadableReason) {
        Objects.requireNonNull(row, "要造替身的行不能为空");
        return new OpaqueEffect(typeOf(row), row.getStartedAt(), row.getSourceEventId(),
                row.getMagnitude(), row.getEffectChannel(), row.getExpiresAt(),
                row.getCancellationKey(), unreadableReason);
    }

    /**
     * 库里已经把这一行标成失效、而重放推不出那个失效时用的替身 —— <b>第三种来源</b>
     * （前两种是"类型读不回来"与"到期时刻在往返里变了", 三条并排写在
     * {@code EffectLedgerStore} 的类注释"重放的三个陷阱"里）。
     *
     * <h2>为什么是这里, 而不是"跳过这一行"</h2>
     * 跳过的代价不是"少算一条", 而是<b>后面每一条的 {@code entryId} 都前移一位</b>
     * （{@code book} 自己发号 {@code eff-N}）—— 错位的 id 会让下一次落库按错误的
     * 身份插入重复行。所以这一行<b>必须占住它的号</b>, 只是不许生效。
     *
     * <h2>怎么表达"不生效"</h2>
     * 账本里只有两条路能让一条账目不参与求和: {@code superseded} 或过期
     * （见 {@code ContinuousEffectLedger.settle}）。前者在内存里推不出来（这正是
     * 本替身存在的理由）, 所以只剩后者: 把到期时刻换成一个<b>不晚于"现在"</b>的时刻,
     * 于是它连第一次结算都过不去 —— {@code settle} 会把它扫成 {@code superseded},
     * 与库里的那一列就此一致。
     *
     * <p>四个数（通道 / magnitude / key / 入账时刻）仍然<b>全部来自列</b>,
     * 只有到期时刻是被换掉的那一个, 而它换掉的理由是"库里根本没记失效时刻"
     * （见 {@code EffectLedgerStore.invalidSince} 的说明）。代价与类注释那张表
     * 一致: 加法正确（它对这个通道的贡献是 0）, 解释退化（历史里这一条的类型是替身,
     * 而它四个数里的 magnitude 仍然是原来那个 —— "它本来是多少"与"它算不算数"
     * 是两件事, 不要把后者写进前者）。
     *
     * <p>类型名刻意<b>复用</b> {@link #TYPE} 而不是新造一个
     * {@code system.effect-invalidated}: 一个新的 system 命名空间事件类型要先过
     * {@code CoreEventCatalog}（架构守卫会核对"代码里声明的每一个核心事件类型都在目录里"）,
     * 而这不该由持久化层顺手决定。区别写在 {@code unreadableReason} 里, 它是文本,
     * 不承担"类型是谁"的语义。
     */
    public static OpaqueEffect invalidated(ContinuousEffectRecord row, String reason,
                                           Instant invalidSince) {
        Objects.requireNonNull(row, "要造替身的行不能为空");
        Objects.requireNonNull(invalidSince, "失效的见证时刻不能为空 —— "
                + "一个永不到期的替身会带着 magnitude 计进净效应");
        return new OpaqueEffect(typeOf(row), row.getStartedAt(), row.getSourceEventId(),
                row.getMagnitude(), row.getEffectChannel(), invalidSince,
                row.getCancellationKey(), reason);
    }

    /**
     * 行上的三个类型列 → 一个类型名。
     *
     * <p>三个列都空时（{@code _untyped} 的历史行）用 {@link #TYPE} ——
     * 见 {@code DomainPayloadCodec.PersistedForm.of} 关于"没有类型"的说明。
     */
    private static EventTypeId typeOf(ContinuousEffectRecord row) {
        if (row.getEffectNamespace() == null || row.getEffectNamespace().isBlank()
                || row.getEffectName() == null || row.getEffectName().isBlank()
                || row.getEffectVersion() <= 0) {
            return TYPE;
        }
        return EventTypeId.of(row.getEffectNamespace(), row.getEffectName(), row.getEffectVersion());
    }

    @Override
    public EventTypeId typeId() {
        return typeId;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    @Override
    public String sourceObjectId() {
        return sourceObjectId;
    }

    @Override
    public double magnitude() {
        return magnitude;
    }

    @Override
    public String effectChannel() {
        return effectChannel;
    }

    /**
     * 到期的绝对时刻 —— <b>直接来自列</b>（{@link #invalidated} 造的那一种除外）。
     *
     * <p>重写这个默认方法（而不是依赖账本用 {@code bookedAt + duration()} 去算）是
     * 刻意的: 算出来的值依赖 {@code started_at} 的精度与 {@code duration} 的往返,
     * 而列里那个值就是当年写下它的那个值。当"有一份原始数据"可用时,
     * 不要用一个能从别处推导出来的近似值去代替它。
     *
     * <p>唯一例外是"这一条在库里已经失效": 那种情况下<b>没有</b>可用的原始值
     * （库不记失效时刻）, 于是这个方法是它唯一的表达手段 —— 见 {@link #invalidated}。
     */
    @Override
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public Duration duration() {
        // 给一个"没有相对时长"的答案 —— 绝对到期时刻已经由 expiresAt() 给出,
        // 而这两个方法是二选一的关系（见 ContinuousEffectLedger.resolveExpiry:
        // 绝对到期优先）。给 null 而不是"expiresAt - startedAt"是为了不制造
        // 第二个关于同一件事的说法。
        return null;
    }

    @Override
    public String cancellationKey() {
        return cancellationKey;
    }

    @Override
    public String effectDescribe() {
        return "认不出类型的一条影响: " + effectChannel + " " + magnitude
                + (unreadableReason.isEmpty() ? "" : "（" + unreadableReason + "）");
    }

    /** 为什么它认不出来 —— 诊断面板与日志用。 */
    public String unreadableReason() {
        return unreadableReason;
    }

    @Override
    public String toString() {
        return "OpaqueEffect[" + typeId + " " + effectChannel + "=" + magnitude + "]";
    }
}
