package com.luxera.companion.persistence.repository;

import com.luxera.companion.persistence.entity.WorldEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * V2.2 §7.2 —— {@code world_event} 的读口。
 *
 * <h2>这个接口里为什么没有删除方法</h2>
 * {@code world_event} 是 append-only 的（§7.2: "append-only"）。
 * 本接口<b>一个 {@code delete*} 都不声明</b> —— 但必须说清楚这不是保证:
 * 继承来的 {@code JpaRepository} 自带 {@code deleteAll()} / {@code deleteById()}。
 * 真正的保证是"没有任何业务代码调它们", 这一点只能靠纪律, 靠不了类型系统。
 * 写在这里是为了让下一个想调 {@code deleteById} 的人先看到这段话。
 *
 * <h2>三个 {@code findBy...OrderBy...} 的排序方向是查询的一部分, 不是习惯</h2>
 * 方法名里 <b>{@code Asc} / {@code Desc} 不能省</b>: Spring Data 从方法名生成
 * 排序子句, 而"按时间正序"与"按时间倒序"是本表两个<b>方向相反</b>的真实用例:
 * <ul>
 *   <li>重放必须<b>正序</b> —— 逆序重放会让"她 12:00 觉得冷"这条影响在
 *       "12:10 加了件衣服"之后才入账, 于是衣服被 12:00 的降温取代。
 *       一个重放顺序错误的表现是"重启之后她冷了 10 分钟又自己好了";</li>
 *   <li>界面与诊断必须<b>倒序</b> —— 人要看的是最近的。</li>
 * </ul>
 */
public interface WorldEventRecordRepository extends JpaRepository<WorldEventRecord, String> {

    /**
     * 重放与行为分析的主查询 —— 走 {@code idx_world_event_world_time}。
     *
     * <p>「她这段时间经历了什么」, <b>正序</b>（见类注释"排序方向是查询的一部分"）。
     */
    List<WorldEventRecord> findByWorldIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            String worldId, Instant from, Instant to);

    /** 一天的事件 —— 界面与诊断面板。倒序, 因为人看的是最近的。 */
    List<WorldEventRecord> findTop200ByWorldIdOrderByOccurredAtDesc(String worldId);

    /**
     * 崩溃恢复: 所有还没投递的事件, 正序。
     *
     * <p>走 {@code idx_world_event_unpublished (world_id, published_at)}。
     * <b>正序是硬要求</b>: 投递顺序决定了她先被哪件事惊到。
     * 逆序恢复的后果是"她先听到门铃、再听到有人敲门" —— 而现实中这两件事
     * 的顺序反了, 她对前者的反应就建立在错误的前提上。
     *
     * <p>注意这里<b>不能用 {@code PublishedAtIsNull}</b> 那种写法当唯一条件:
     * 本方法刻意带上 {@code worldId}, 让查询落在那条复合索引上
     * （只按 {@code published_at} 筛会退化成全表扫, 而这张表是 append-only 的）。
     */
    List<WorldEventRecord> findByWorldIdAndPublishedAtIsNullOrderByOccurredAtAsc(String worldId);

    /**
     * 重放某一类事件 —— 例如"把这个 agent 收到过的全部 {@code SENSORY} 事件重放一遍"。
     *
     * <p>走 {@code idx_world_event_type}。{@code category} 是逗号分隔的多值列,
     * 所以这里用 {@code Containing} 而不是 {@code Is}: 一个事件可以同时属于多个类别
     * （见 {@link WorldEventRecord#getCategory()} 的说明）。
     * {@code Containing} 生成的是 {@code LIKE '%SENSORY%'}, <b>它无法使用上面那条索引</b>
     * —— 这一点必须明写, 否则下一个人会以为它很快。
     *
     * <p>真正的重放路径（{@code StimulusReplayStore}）不走这个方法: 它按
     * {@code worldId + publishedAtIsNull} 取行, 在<b>内存里</b>按 category 过滤
     * （因为一次恢复本来就要把所有未投递的事件读出来）。这个方法留给诊断面板,
     * 那里的调用频率是"人点了才查一次"。
     */
    List<WorldEventRecord> findTop200ByCategoryContainingOrderByOccurredAtDesc(String categoryPart);

    /** "这东西产生过哪些事件" —— 走 {@code idx_world_event_source}。 */
    List<WorldEventRecord> findBySourceObjectIdOrderByOccurredAtAsc(String sourceObjectId);

    /**
     * 这一类事件最近发生过吗 —— 走 {@code idx_world_event_type} 的全部三列。
     *
     * <p>handler 自检与诊断用。刻意用 <b>三列全带</b>的形式: 少了
     * {@code majorVersion}, 查询会变成"所有版本" —— 而两个 major 版本之间的
     * 载荷形状是不兼容的（那是 major 的定义）, 混在一起读会拿到读不懂的行。
     */
    List<WorldEventRecord> findTop50ByTypeNamespaceAndTypeNameAndMajorVersionOrderByOccurredAtDesc(
            String typeNamespace, String typeName, int majorVersion);

    /** 计数 —— "她这一天经历了多少件事"。 */
    long countByWorldIdAndOccurredAtBetween(String worldId, Instant from, Instant to);
}
