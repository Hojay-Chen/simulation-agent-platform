package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.ContinuousEffectRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2.2 §7.2 —— {@code continuous_effect} 的读口。
 *
 * <h2>恢复只需要一个方法</h2>
 * {@link #findByHumanIdOrderBySequenceAsc} —— 读回她身上挂着的全部账目,
 * 按入账顺序。{@code ContinuousEffectLedger} 的重建就是"把这些账目按顺序
 * 重新入账一遍"（它没有别的入口, 见 {@code EffectLedgerStore} 的说明）。
 *
 * <h2>"按 {@code sequence} 排序"不能换成"按 {@code started_at} 排序"</h2>
 * 看起来两者等价, 但 {@code Instant} 的精度虽然足以区分真实事件,
 * 同一纳秒入账两条在测试与批量重排里都是常事 —— 而
 * {@code ContinuousEffectLedger} 的 {@code sequence} 字段的 javadoc 明写了
 * 它就是为这件事存在的: "没有稳定定序会让'谁替换了谁'变得不确定,
 * 那是一个只在 CI 上偶发的不稳定测试"。
 *
 * <p>落到这张表上: 重放必须用<b>写入时记下的那个 sequence</b>, 而不是
 * 重放时重新分配一个 —— 重新分配的值只取决于数据库返回行的顺序,
 * 而那个顺序没有保证。这正是这一列被存下来的全部意义。
 *
 * <h2>为什么没有 {@code delete} 方法（尽管这张表会被 UPDATE）</h2>
 * 这张表不是严格 append-only（"标记失效"是一次单向 UPDATE,
 * 见 {@link ContinuousEffectRecord} 的类注释）。但"可以改"与"可以删"是两件事:
 * 被取代的账目是<b>历史</b>（"她 12:05 换了件厚的"与"她从没穿过 T恤"是两件不同的事）,
 * 删掉它就等于抹掉那次换衣服。所以这里仍然不声明任何删除方法。
 */
public interface ContinuousEffectRecordRepository extends JpaRepository<ContinuousEffectRecord, String> {

    /**
     * 恢复账本 —— 走 {@code idx_continuous_effect_human}, 按 {@code sequence} 正序。
     *
     * <p>刻意<b>不加</b> "还没失效的" 这个条件: 过期判定需要一个"现在"（仿真时刻）,
     * 而本层不读时钟（见 {@code persistence/package-info}）。
     * 把全部账目交给 {@code EffectLedgerStore}, 由它拿着领域给的"现在"过滤 ——
     * 这样"什么算过期"这条规则只有<b>一个</b>实现（{@code ContinuousEffectLedger.isExpired}）,
     * 而不是在 SQL 里再写一遍、并且写得不一样。
     */
    List<ContinuousEffectRecord> findByHumanIdOrderBySequenceAsc(String humanId);

    /**
     * 替换判定与撤销: 同一通道 + 同一 key 上的账目。
     *
     * <p>走 {@code idx_continuous_effect_key} 的全部三列。
     * 它是热路径吗? 不是 —— 恢复时每个通道的每个 key 查一次,
     * 而账目总量是个位数。列进来是因为 {@code ledger.cancel(channel, key, at)}
     * 需要一个"这条账目现在在哪一行"的入口。
     */
    List<ContinuousEffectRecord> findByHumanIdAndEffectChannelAndCancellationKey(
            String humanId, String effectChannel, String cancellationKey);

    /** "这条事件产生了哪些账目" —— 走 {@code idx_continuous_effect_source_event}, 用于来源追溯与撤销。 */
    List<ContinuousEffectRecord> findBySourceEventId(String sourceEventId);

    /** 她身上现在挂着几条账目（含已失效的）—— 诊断与行为分析用。 */
    long countByHumanId(String humanId);
}
