package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.HumanRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2.2 §7.2 —— {@code human} 的读口: <b>刻意地小</b>。
 *
 * <h2>为什么这个接口这么小, 而这不是偷懒</h2>
 * 见 {@link HumanRecord} 的类注释: 这张表是<b>锚点</b>, 内容都在别处
 * （{@code companion} / {@code plan_revision} / {@code activity_record} /
 * {@code world_event}）。锚点表需要的操作只有三类:
 * <ol>
 *   <li>写出这个 agent 存在（{@code save} —— 继承来的, 不需要声明）;</li>
 *   <li>列出全部 agent —— 给运维与控制台, 见 {@link #findAllByOrderByCreatedAtAsc()}
 *       与 {@link #findByDisplayNameContainingOrderByCreatedAtAsc(String)};</li>
 *   <li>判断一个 id 是不是真 agent —— 见 {@code existsById}（继承来的）。</li>
 * </ol>
 * <p>除此之外<b>一个方法都不该加</b>。这张表上每多一个查询方法, 都意味着有人
 * 开始把 agent 级别的状态往这一行上挂 —— 而那正是本表存在的形式要防的事
 * （状态属于各自主语的表, 见 {@link HumanRecord} 的那张归属表）。
 *
 * <h2>{@code findAllBy...} 这个看起来多余的写法</h2>
 * {@code JpaRepository} 自带 {@code findAll()}。这里仍然声明一个带排序的版本,
 * 是因为"列出全部 agent"这个查询的排序<b>是它的语义的一部分</b>:
 * {@code human} 表里没有时间之外的任何排序依据, 而{@code ORDER BY created_at}
 * 决定了列表上"谁在前"。依赖数据库的返回顺序（{@code findAll()} 的默认行为）
 * 会让控制台上的 agent 顺序随 VACUUM 与查询计划变化 ——
 * 一个"列表顺序偶尔会变"的 bug 是几乎不可能被复现的。
 *
 * <h2>为什么没有"按名字查单个"</h2>
 * 名字<b>不唯一</b>（{@code HumanRecord.displayName} 只是给人看的冗余列,
 * 它来自 persona, 而 persona 的名字是 LLM 生成的 —— 重名是常态）。
 * 提供一个按名字查的方法会诱使调用方把它当成标识符使用,
 * 而那种错误的表现是"加载错了 agent"—— 一个数据完全错乱但系统不报错的故障。
 * 按 id 查是唯一的正确方式。
 */
public interface HumanRecordRepository extends JpaRepository<HumanRecord, String> {

    /**
     * 全部 agent, 按创建时间正序 —— 控制台与运维列表。
     *
     * <p>正序（最早的在前）而不是倒序: 这是一个<b>稳定的、与内容无关的</b>顺序,
     * 而"最近创建的在前"会让列表在每次新建 agent 之后整体位移,
     * 让人失去位置感。
     */
    List<HumanRecord> findAllByOrderByCreatedAtAsc();

    /** 按显示名筛 —— 只给控制台的模糊查找用, <b>不返回 {@code Optional}</b>: 见类注释"重名是常态"。 */
    List<HumanRecord> findByDisplayNameContainingOrderByCreatedAtAsc(String namePart);

    /** 一共几个 agent。 */
    long count();
}
