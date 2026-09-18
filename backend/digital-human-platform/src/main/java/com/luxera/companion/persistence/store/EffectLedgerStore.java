package com.luxera.companion.persistence.store;

import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.persistence.DomainPayloadCodec;
import com.luxera.companion.persistence.entity.ContinuousEffectRecord;
import com.luxera.companion.persistence.repository.ContinuousEffectRecordRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V2.2 §7.2 —— {@code continuous_effect}: <b>内存态 {@code ContinuousEffectLedger}
 * 的落库 / 恢复 / 重置</b>。
 *
 * <h2>不存会怎样（这是本类存在的全部理由）</h2>
 * <pre>
 *   重启前: 外面 3 度, 她穿着羽绒服   → warmth = +0.83
 *   重启后: 账本为空                 → warmth = 0.00 → 她"不冷了"
 * </pre>
 * 而她身上那件羽绒服并没有脱。这是本设计里最刺眼的一类错误:
 * <b>状态凭空改变, 且没有任何事件解释这个改变</b>。
 *
 * <h2>恢复是"重放", 而不是"把对象读回来"</h2>
 * {@code ContinuousEffectLedger} 只暴露一个写入路径 —— {@code book(event, bookedAt)}
 * （{@code entries} 与 {@code activeByKey} 都是私有的, 没有 setter）。
 * 这是好事, 而不是障碍: 它意味着账本<b>永远是事件序列的一个投影</b>,
 * 而不可能变成"某一次手工修补过的状态"。于是:
 * <pre>
 *   restore = 按 sequence 正序对每一行调一次 book()
 * </pre>
 * 而 {@code sequence} 列之所以被存下来, 正是为了这件事的顺序<b>确定</b>
 * （{@code ContinuousEffectLedger.Entry} 的 javadoc: "没有稳定定序会让
 * '谁替换了谁'变得不确定, 那是一个只在 CI 上偶发的不稳定测试"）。
 *
 * <h2>重放的三个陷阱, 以及本类的处理</h2>
 * <ol>
 *   <li><b>类型认不出来。</b> 一个三方的 {@code StateEffectEvent} 实现被卸载了,
 *       那一行的 JSON 读不回来。跳过它 = 她的保暖值少算一件衣服。
 *       处理: 用 {@link OpaqueEffect} 从那四列重建一条账目 —— 加法仍然正确,
 *       代价是解释不清（见 {@code OpaqueEffect} 的对照表）;</li>
 *   <li><b>到期时刻的往返。</b> {@code ContinuousEffectLedger.book} 是按
 *       {@code event.expiresAt() != null ? expiresAt : bookedAt + duration()} 算到期的。
 *       而 {@code expiresAt()}/{@code duration()} <b>不是</b> JavaBean 命名的 getter
 *       —— 它们会不会被写进 JSON、读回来还是不是同一个值, 取决于那个三方实现类
 *       把它们写成了 record 组件还是默认方法。
 *       处理: 重放后<b>逐条核对</b>算出来的到期时刻与 {@code expires_at} 列是否一致
 *       （见 {@link #fidelityOf}）, 不一致就用 {@link OpaqueEffect} 兜底并告警。
 *       一个"她身上的暖意比预期多留了两个小时"的 bug, 症状会晚很久才出现,
 *       而那时已经没有任何线索指向序列化了;</li>
 *   <li><b>库里标了失效, 而重放无从还原 —— "失效在重放里怎么被还原"。</b>
 *       {@code superseded} 那一列有<b>两类</b>来源, 而它们能不能被重放出来是两回事:
 *       <ul>
 *         <li><b>账本自己写下的。</b> 同 key 的新影响挤掉旧的（{@code book} 的
 *             {@code old.supersededBy(...)}）, 或 {@code settle} 扫掉的过期账目。
 *             这一类在重放里<b>自然重现</b> —— 后者靠 {@link #restore} 最后那次
 *             {@code settle(now)}, 前者靠"后一行会再把前一行挤掉";
 *             显式撤销（{@code cancel}）也属于这一类, 因为它是一场<b>真的入账</b>:
 *             {@code magnitude = 0} 的同 key 账目<b>作为一行</b>写进库, 重放时它照样
 *             把前一条挤掉。而"挤掉"的判据是 {@code cancellation_key} 与
 *             {@code magnitude} 这两个<b>列</b>（见 {@link OpaqueEffect} 的对照表）,
 *             与那一行 JSON 读不读得回来无关 —— 这就是它经得起重启的原因;</li>
 *         <li><b>绕过账本写下的。</b> {@link #reset} 把整列直接写成 {@code true}
 *             （一次 out-of-band 的标记, 内存里没有对应的那一步）。这一类在重放里
 *             <b>推不出来</b> —— 没有任何东西能把那个 {@code true} 标回去, 于是
 *             她身上的暖意归零之后重启一次, 暖意<b>全回来了</b>。
 *             处理: 第一遍原样重放, 然后<b>核对</b>"库里标了失效的行有没有真的失效",
 *             只把核对不过的那些行在第二遍里换成"已失效"的替身
 *             （{@code OpaqueEffect.invalidated}, 到期时刻压到不晚于"现在"）。
 *             注意这是<b>核对</b>而不是猜规则: 第一遍跑的就是重放本身,
 *             所以"能不能还原"是观测到的结果, 不是抄来的判据（对照
 *             {@link #fidelityOf} 那种确实在抄规则的写法）;</li>
 *       </ul>
 *       两条被否掉的修法也写在这里 —— 它们都很短, 但都是错的:
 *       <ul>
 *         <li><b>"不生效的行就不入账"</b> —— {@code entryId = "eff-" + 序号} 是
 *             {@code book} 自己发的号, 跳过一行会让后面所有行的 id 前移一位,
 *             而错位的 id 会让下一次 {@code snapshot} 按错误的身份<b>插入重复行</b>,
 *             账目在库里悄悄翻倍（{@code EffectLedgerStoreTest} 有一条专门钉这件事）;</li>
 *         <li><b>"把库里的行删掉"</b> —— 见 {@link #reset}: DELETE 是一种抹掉,
 *             不是一种表达。而"她的暖意为什么突然归零"恰恰是最需要有账可查的那种事件。</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>本类<b>不</b>做的事</h2>
 * <ul>
 *   <li><b>不读时钟。</b> {@code restore} 要一个"现在"来判断过期, 而那个"现在"
 *       由调用方（{@code Body} 的 tick）给 —— 账本自己读墙上时钟会让
 *       "把仿真加速 60 倍"变成一个改变她会不会觉得冷的操作
 *       （{@code ContinuousEffectLedger.book} 的 javadoc 已经为这件事立过规矩,
 *       本类只是跟着走）;</li>
 *   <li><b>不删行。</b> 见 {@link #reset} —— "清空账本"在库里不是 DELETE。</li>
 * </ul>
 */
@Slf4j
public class EffectLedgerStore {

    private final ContinuousEffectRecordRepository repository;
    private final DomainPayloadCodec codec;

    public EffectLedgerStore(ContinuousEffectRecordRepository repository, DomainPayloadCodec codec) {
        this.repository = Objects.requireNonNull(repository, "账目仓库不能为空");
        this.codec = Objects.requireNonNull(codec, "编解码器不能为空");
    }

    // ─────────────────────────── 落库 ───────────────────────────

    /**
     * 把账本现在的样子写进库 —— <b>增量</b>, 不是全量覆盖。
     *
     * <h2>为什么是增量</h2>
     * 账目总量是个位数, 看起来"删光重写"更简单。但它会做两件坏事:
     * <ol>
     *   <li><b>丢掉 {@code source_event_id}。</b> 那一列指向产生这条账目的事件行,
     *       而账本对象里没有这个字段（给它加一个字段是改领域类型, 不是本层该做的事）。
     *       删光重写会让"这条暖意是哪件事造成的"在第二次落库之后永久消失。
     *       增量写只在<b>首次</b>插入那一行时写入它, 之后那一列不会被碰;</li>
     *   <li><b>让"已失效"这件事无法表达。</b> 一条被替代的账目留在库里是本表的价值
     *       （"她 12:05 换了件厚的"与"她从没穿过 T恤"是两件不同的事）。
     *       DELETE 是一种抹掉, 而不是一种表达。</li>
     * </ol>
     *
     * <h2>允许的 UPDATE 只有一种</h2>
     * {@code superseded} 从 {@code false} 变成 {@code true}, <b>单向</b>。
     * 这也是本表唯一一处不是 append-only 的地方（见
     * {@code ContinuousEffectRecord} 的类注释）。反向的更新（true → false）
     * 在这里被静默忽略并记一条 WARN: 一个被替换掉的账目重新生效, 会让
     * {@code Σ magnitude} 多出一份而她身上什么都没变。
     *
     * @param sourceEventIds 账目 id → 产生它的事件行 id。可以给空表 ——
     *                       不确定来源时给 {@code null} 值, 而不是随手编一个 id
     * @return 真正写了几行（新插入的 + 被标记失效的）
     */
    public int snapshot(ContinuousEffectLedger ledger, String humanId,
                        Map<String, String> sourceEventIds) {
        Objects.requireNonNull(ledger, "要落库的账本不能为空");
        Objects.requireNonNull(humanId, "账目必须属于某个人 —— 没有人的账目查不出来");
        Map<String, String> sources = sourceEventIds == null ? Map.of() : sourceEventIds;

        List<ContinuousEffectLedger.EffectRecord> history = ledger.history();
        Map<String, ContinuousEffectRecord> existing = new HashMap<>();
        for (ContinuousEffectRecord row : repository.findByHumanIdOrderBySequenceAsc(humanId)) {
            existing.put(row.getId(), row);
        }

        List<ContinuousEffectRecord> changed = new ArrayList<>();
        int inserted = 0;
        int superseded = 0;
        for (ContinuousEffectLedger.EffectRecord entry : history) {
            ContinuousEffectRecord row = existing.get(entry.entryId());
            if (row == null) {
                rejectOpaqueWrite(entry);
                row = newRecord(entry, humanId, sources.get(entry.entryId()));
                changed.add(row);
                inserted++;
                continue;
            }
            if (entry.superseded() && !row.isSuperseded()) {
                row.setSuperseded(true);
                changed.add(row);
                superseded++;
            } else if (!entry.superseded() && row.isSuperseded()) {
                // 账本说它还生效, 库里说它已经失效 —— 这是账本被重新加载过
                // 却没有走 restore（于是 sequence 重新从 1 开始, 对上了别的行）
                log.warn("[Persistence] 账目 {} 在库里已被标记失效, 但账本认为它仍然生效 —— "
                                + "保持库里的判断（单向）。如果你刚重建过账本却没有走 restore, "
                                + "这就是那个症状", entry.entryId());
            }
        }

        if (!changed.isEmpty()) {
            repository.saveAll(changed);
        }
        return inserted + superseded;
    }

    /** {@link #snapshot(ContinuousEffectLedger, String, Map)} 的简写: 不记录来源事件。 */
    public int snapshot(ContinuousEffectLedger ledger, String humanId) {
        return snapshot(ledger, humanId, Map.of());
    }

    /**
     * 一个从 {@link OpaqueEffect} 重建出来的账目<b>不该</b>走到插入分支。
     *
     * <p>它只可能来自一行已经存在的记录（{@code OpaqueEffect.of(row)} 需要一个
     * {@code ContinuousEffectRecord}）, 所以那一行必然在 {@code existing} 里。
     * 走到这里说明有人手写了一个替身并试图写入 —— 那会用
     * "认不出来"覆盖掉"原本是什么", 而那一列 JSON 是恢复那个类型的唯一线索。
     */
    private static void rejectOpaqueWrite(ContinuousEffectLedger.EffectRecord entry) {
        if (entry.event() instanceof OpaqueEffect) {
            throw new IllegalStateException(
                    "账目 " + entry.entryId() + " 是一个 OpaqueEffect（类型认不出来的替身）, "
                            + "而它在库里没有对应的行。拒绝写入: 写进去会用'认不出来'"
                            + "覆盖掉那一列 JSON, 而那是把原来的类型找回来的唯一线索。"
                            + "正确做法是先有行、再有替身 —— 替身只是读的产物, 不是写的输入");
        }
    }

    /**
     * 一条账目 → 一行。
     *
     * <p>四个值有<b>两个来源</b>, 而这两个来源在写入的那一刻必然一致 ——
     * 这一点必须明说, 因为它是"读回来时以列为准"这条纪律成立的前提:
     * <pre>
     *   四列（channel/magnitude/key/expiresAt）← 事件对象上的同名方法
     *   payload_json                          ← 同一个事件对象的完整序列化
     * </pre>
     * 它们来自同一次调用, 所以在写入点不可能不一致。不一致只可能被两种操作造出来:
     * 人工改数据, 或另一个绕过本类的写入方。读的时候以列为准
     * （见 {@code ContinuousEffectRecord} 的类注释"当一个值有两份副本时"）。
     */
    private ContinuousEffectRecord newRecord(ContinuousEffectLedger.EffectRecord entry,
                                             String humanId, String sourceEventId) {
        StateEffectEvent event = entry.event();
        DomainPayloadCodec.PersistedForm form = codec.write(event);

        ContinuousEffectRecord row = new ContinuousEffectRecord();
        row.setId(entry.entryId());
        row.setHumanId(humanId);
        row.setEffectNamespace(form.typeNamespace());
        row.setEffectName(form.typeName());
        row.setEffectVersion(form.majorVersion());
        row.setEffectJson(form.payload());
        row.setEffectChannel(event.effectChannel());
        row.setMagnitude(event.magnitude());
        row.setCancellationKey(event.cancellationKey());
        row.setSourceEventId(sourceEventId);
        row.setStartedAt(entry.bookedAt());
        row.setExpiresAt(entry.expiresAt());
        row.setSuperseded(entry.superseded());
        row.setSequence(sequenceOf(entry.entryId()));
        return row;
    }

    // ─────────────────────────── 恢复 ───────────────────────────

    /**
     * 从库里把账本重建出来。
     *
     * <h2>重放的两遍</h2>
     * <pre>
     *   第一遍: 按 sequence 正序, 每一行 book 一次 —— 与库里的样子一一对应
     *   核对:   库里标了 superseded 的行, 重放过之后是不是真的失效了
     *   第二遍: 只把"核对不过"的那几条换成"已失效"的替身, 再重放一次
     * </pre>
     * 第一遍是常态, 第二遍只在<b>真的有绕过账本写下的失效标记</b>时才跑
     * （{@link #reset} 是唯一的生产来源, 见类注释"失效在重放里怎么被还原"）。
     * 之所以要跑两遍而不是一开始就把标了失效的行都换成替身: 那种写法会让
     * "被同 key 的新影响挤掉"这类<b>本来读得回来</b>的账目也退化成替身,
     * 而它们在内存里的样子（类型、到期时刻）是行为分析要看的。
     * 决定"哪几条要换"的是第一遍的<b>观测结果</b>, 不是一条抄来的判据 ——
     * 判据抄错的方向是"算错", 观测不会。
     *
     * @param now 仿真时刻的"现在" —— 由调用方给, 本层不读时钟。恢复完会用它
     *            跑一次 {@code settle}, 让"已经过期"这件事在内存里与库里一致
     * @return 一个新的账本。**不是**在调用方的账本上追加 —— 见下面"为什么返回新对象"
     */
    public ContinuousEffectLedger restore(String humanId, Instant now) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        Objects.requireNonNull(now, "恢复账本必须给出'现在' —— 过期判定是关于时间的");

        List<ContinuousEffectRecord> rows = repository.findByHumanIdOrderBySequenceAsc(humanId);
        List<StateEffectEvent> events = new ArrayList<>(readAll(rows, humanId));

        // 第一遍: 原样重放
        ContinuousEffectLedger ledger = replay(rows, events, now);

        // 核对: 库里说失效、这一遍却没能让它失效的行 —— 那是绕过账本写下的标记
        List<Integer> unreproducible = new ArrayList<>();
        List<ContinuousEffectLedger.EffectRecord> history = ledger.history();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).isSuperseded() && !history.get(i).superseded()) {
                unreproducible.add(i);
            }
        }

        if (!unreproducible.isEmpty()) {
            for (int i : unreproducible) {
                events.set(i, OpaqueEffect.invalidated(rows.get(i),
                        "库里的这一行已被标记为失效（reset 或人工改库）—— 这一层推不出来它是"
                                + "怎么失效的, 只能照着那一列办",
                        invalidSince(rows.get(i), now)));
            }
            ledger = replay(rows, events, now);
            log.warn("[Persistence] 恢复 {} 的账本时, {} 条账目在库里已被标记失效, 而重放"
                            + "重现不了那个失效（它们是被绕过账本的手直接写成 superseded=true 的）"
                            + "—— 它们用'已失效'的替身重建: 不再计入任何一次结算, 但仍然留在"
                            + "历史里。这个数不会自己降到 0, 它每次恢复都会出现, 直到这些行"
                            + "被真正需要它们的人归档", humanId, unreproducible.size());
        }

        return ledger;
    }

    /**
     * 逐行读出事件 —— 读不回来或到期时刻对不上的, 用 {@link OpaqueEffect} 顶上。
     *
     * <p>它是"重放"唯一的上游: 两遍跑的是同一个列表（第二遍只替换其中几条）,
     * 否则两遍的差别就不止"那几条失效"了。
     */
    private List<StateEffectEvent> readAll(List<ContinuousEffectRecord> rows, String humanId) {
        List<StateEffectEvent> events = new ArrayList<>(rows.size());
        int opaque = 0;
        int expiryMismatch = 0;
        for (ContinuousEffectRecord row : rows) {
            StateEffectEvent event;
            Optional<Object> value = codec.tryRead(row.getEffectNamespace(), row.getEffectName(),
                    row.getEffectVersion(), row.getEffectJson());
            StateEffectEvent readable = value
                    .filter(StateEffectEvent.class::isInstance)
                    .map(StateEffectEvent.class::cast)
                    .orElse(null);

            if (readable == null) {
                event = OpaqueEffect.of(row,
                        value.isEmpty() ? "类型没有任何实现类" : "载荷读出来不是持续影响事件");
                opaque++;
            } else if (!fidelityOf(readable, row)) {
                event = OpaqueEffect.of(row, "到期时刻在 JSON 往返里变了");
                expiryMismatch++;
            } else {
                event = readable;
            }
            events.add(event);
        }

        if (opaque > 0 || expiryMismatch > 0) {
            log.warn("[Persistence] 恢复 {} 的账本时, {} 条账目的类型认不出来、{} 条账目的"
                            + "到期时刻与列上的值不一致 —— 它们用 OpaqueEffect 重建: "
                            + "加法和替换都仍然正确, 但 Settlement.why() 解释不清这两类账目。"
                            + "前者通常意味着一个三方插件被卸载了（或忘了注册）, "
                            + "后者是一个序列化保真问题, 值得查",
                    humanId, opaque, expiryMismatch);
        }
        return events;
    }

    /**
     * 把一份"已经读好的事件"按 sequence 正序喂进一个新账本, 然后结算一次。
     *
     * <p>把 sequence 交回给账本是不可能的（{@code book} 自己发号）, 但顺序是对的:
     * 账本从空的开始, 每 {@code book} 一次号加一, 而我们是按 sequence 正序喂的。
     * 于是重放后的 {@code entryId} 与原库里的一一对应 —— 这一条不成立时
     * {@code snapshot} 会走"插入新行"分支, 而那个分支有自己的守卫。
     *
     * <p>结尾那次 {@code settle} 是为了让"已经过期"在内存里与库里一致。不做这一步的
     * 后果是: 一条 expired 的账目会以 active 的样子存在到下一次 settle —— 而 settle
     * 在下一个 tick 就会跑, 所以这是一个"只影响恢复后那一瞬间"的差异。做它是为了
     * 消灭"恢复后的读数与恢复前的读数不一样"这一类难查的观测差异。
     */
    private static ContinuousEffectLedger replay(List<ContinuousEffectRecord> rows,
                                                 List<StateEffectEvent> events, Instant now) {
        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        for (int i = 0; i < rows.size(); i++) {
            ledger.book(events.get(i), rows.get(i).getStartedAt());
        }
        ledger.settle(now);
        return ledger;
    }

    /**
     * 一条"推不出失效时刻"的账目, 在重放里该从哪一刻起不生效。
     *
     * <p>库里<b>没有</b>记"这一行是什么时候失效的"这一列（{@code reset} 不写时刻,
     * 它只翻转一个布尔）—— 所以这里只能用行自己的入账时刻作为那句话的见证:
     * "它从入账那一刻起就不生效"。这是不编造新时刻的最小陈述。
     *
     * <p>但入账时刻<b>可能晚于</b>恢复用的"现在"（一条预排到未来的持续影响）——
     * 那就用"现在"。这一支不是洁癖: 用一个未来的时刻当到期时刻, 这条账目在
     * 第一次结算里<b>不会</b>失效, 于是它会带着 magnitude 计进净效应 ——
     * 方向正是本类最不能接受的那一侧（算错, 而不只是解释不清）。
     */
    private static Instant invalidSince(ContinuousEffectRecord row, Instant now) {
        Instant startedAt = row.getStartedAt();
        return startedAt != null && startedAt.isBefore(now) ? startedAt : now;
    }

    /**
     * 重放后的到期时刻与列上的是不是同一个值。
     *
     * <p>判据严格照抄 {@code ContinuousEffectLedger.resolveExpiry} 的算法
     * （绝对到期优先, 其次 {@code bookedAt + duration()}, 都没有就是永不过期）——
     * 抄写而不是调用, 因为那是账本的私有实现。这里有一个已知的维护风险, 写出来:
     * <b>账本改了到期算法, 这个方法不会跟着改</b>。代价是一旦不一致, 结果只是
     * "多走一次 OpaqueEffect 分支 + 一条 WARN", 而不是算错 ——
     * 这个方向是刻意选的: 宁可解释不清, 不能算错。
     */
    private static boolean fidelityOf(StateEffectEvent event, ContinuousEffectRecord row) {
        Instant expected = row.getExpiresAt();
        if (event.expiresAt() != null) {
            return event.expiresAt().equals(expected);
        }
        if (event.duration() != null) {
            return row.getStartedAt().plus(event.duration()).equals(expected);
        }
        return expected == null;
    }

    // ─────────────────────────── 重置 ───────────────────────────

    /**
     * 清空她身上的全部影响 —— <b>库里不是 DELETE, 是"把全部账目标记为失效"</b>。
     *
     * <h2>为什么不做 DELETE</h2>
     * 因为 {@code ContinuousEffectLedger.clear()} 的 javadoc 说的是
     * "全部清空 —— <b>只给测试与'重新初始化'用</b>"。那个操作在内存里是破坏性的
     * （对象没了就没了）, 而在库里它可以做得更好:
     * <pre>
     *   内存 clear():  那张"她 12:05 换了件厚的"的账页消失了
     *   本方法:        账页还在, 只是不再参与求和
     * </pre>
     * 而"她身上的暖意为什么突然归零"恰恰是<b>最需要</b>有账可查的那种事件。
     * 库比内存能做得好一点的时候, 就该好一点 —— 这是本方法唯一的理由。
     *
     * <h2>为什么它不叫 {@code clear}</h2>
     * 因为它<b>不是</b> {@code clear} 的等价物: 内存里的账目没了, 库里的还在。
     * 用一个不同的名字让这件事在调用处看得见 —— 一个叫 {@code clear} 的方法
     * 返回"没有删掉任何东西"会是一个非常难查的误会。
     *
     * <p>调用方在调完本方法之后<b>也要</b>清掉内存里的账本（
     * {@code ledger.clear()}）—— 本层不持有那个对象, 也不该持有:
     * 让持久化层去改领域对象的状态, 会把"谁是真相的来源"这件事弄反。
     *
     * @return 被标记失效的条数
     */
    public int reset(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");

        List<ContinuousEffectRecord> rows = repository.findByHumanIdOrderBySequenceAsc(humanId);
        List<ContinuousEffectRecord> changed = new ArrayList<>();
        for (ContinuousEffectRecord row : rows) {
            if (!row.isSuperseded()) {
                row.setSuperseded(true);
                changed.add(row);
            }
        }
        if (!changed.isEmpty()) {
            repository.saveAll(changed);
            log.warn("[Persistence] {} 的账本被重置: {} 条账目被标记为失效。"
                            + "它们<b>没有</b>被删除 —— 若你在问'她的保暖值为什么突然归零', "
                            + "答案就在这些行里", humanId, changed.size());
        }
        return changed.size();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 账目 id → 序号。
     *
     * <p>{@code ContinuousEffectLedger} 里 {@code entryId = "eff-" + sequence},
     * 所以这一列是<b>从 id 推出来的</b>, 而不是另一次发号。存它是为了让
     * "按入账顺序读回来"能用一条索引扫描做到 —— {@code ORDER BY id} 是字符串序
     * （{@code eff-10} 会排在 {@code eff-9} 前面）。
     *
     * <p>推不出来时（id 不是那个形状）返回 0 并<b>不抛</b>: 这一列只服务于排序,
     * 一个排不上序的账目不该让整次落库失败。
     */
    private static long sequenceOf(String entryId) {
        if (entryId == null || !entryId.startsWith("eff-")) {
            return 0L;
        }
        try {
            return Long.parseLong(entryId.substring("eff-".length()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 诊断: 她身上现在挂着什么。 */
    public String describe(String humanId) {
        Objects.requireNonNull(humanId, "必须指明是哪个人");
        List<ContinuousEffectRecord> rows = repository.findByHumanIdOrderBySequenceAsc(humanId);
        StringBuilder sb = new StringBuilder("[EffectLedgerStore] ").append(humanId)
                .append(" 共 ").append(rows.size()).append(" 条账目");
        for (ContinuousEffectRecord row : rows) {
            sb.append("\n  · ").append(row.describe());
        }
        return sb.toString();
    }
}
