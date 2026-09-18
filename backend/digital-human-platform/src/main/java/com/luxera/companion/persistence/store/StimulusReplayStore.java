package com.luxera.companion.persistence.store;

import com.luxera.companion.boundary.event.RealtimeEventQueue;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.persistence.entity.WorldEventRecord;
import com.luxera.companion.registry.CoreEventCatalog;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2.2 §7.2 —— <b>{@code RealtimeEventQueue} 的持久化 / 恢复路径</b>。
 *
 * <h2>它<b>没有</b>自己的表 —— 而这是一个判断, 不是遗漏</h2>
 * §7.2 给了账本（A 类）一张 {@code continuous_effect} 表, 却没给刺激队列（B 类）
 * 任何表。这不是文档的疏漏, 而是这两类状态的<b>本性不同</b>:
 * <table border="1">
 *   <tr><th></th><th>{@code ContinuousEffectLedger}（A 类）</th>
 *       <th>{@code RealtimeEventQueue}（B 类）</th></tr>
 *   <tr><td>状态的形状</td><td>累积的（{@code Σ magnitude}）</td>
 *       <td><b>派生的</b>（队列里的每一条都来自一条事件）</td></tr>
 *   <tr><td>丢了会怎样</td><td>她的体温凭空改变</td>
 *       <td>她<b>晚一点</b>才听到那条消息</td></tr>
 *   <tr><td>能不能从事件重建</td>
 *       <td><b>不能</b>: 一条 {@code device.clothing-worn.v1} 事件发生过之后,
 *           世界的状态已经变了, 重放它不会重新产生"穿着衣服"这个结论</td>
 *       <td><b>能</b>: 队列 = 还没递给她的事件, 而事件本身在 {@code world_event} 里
 *           一条不少</td></tr>
 * </table>
 * 于是这里的设计是: <b>队列不落库, 它的恢复 = 用 {@code world_event} 重放一遍</b>。
 * 多存一张队列表的代价是<b>两份真相</b>（队列表说"待投递有 3 条",
 * 事件表说"未投递有 5 条"）, 而分歧的那一天没有人能判断谁对。
 *
 * <h2>「已投递」这一列就是队列的持久化形态</h2>
 * {@code world_event.published_at} 为 {@code null} = "这件事还没进她的意识"。
 * 一次崩溃恢复就是:
 * <pre>
 *   1. 读 {@code world_id + published_at IS NULL} 的行（走 idx_world_event_unpublished）
 *   2. 留下 category 含 SENSORY 的那些（其余的是 A/C 类, 归宿不是队列）
 *   3. 按 {@code occurred_at} 正序 offer 进一个新的队列
 * </pre>
 * 第 3 步的顺序是硬要求: 投递顺序决定了她先被哪件事惊到。逆序恢复的后果是
 * "她先听到门铃、再听到有人敲门" —— 而现实中这两件事的顺序反了,
 * 她对前者的反应就建立在错误的前提上。
 *
 * <h2>{@link #markDelivered} 该在什么时候调 —— 这是本类唯一需要调用方想清楚的事</h2>
 * 不是"offer 进队列"的时候, 而是"<b>交到她手里</b>"的时候（{@code poll} 出来之后）。
 * 两者的差别在一次崩溃里会暴露:
 * <pre>
 *   offer 就标记 → 崩溃后不再重投 → 她<b>永远</b>没看到那条消息, 而她当时正在等它
 *   poll  才标记 → 崩溃后重新投递 → 她第二次看到它
 * </pre>
 * 两个都不完美, 但方向不同: 前者是<b>信息永久丢失</b>, 后者是<b>一次重复</b>。
 * 对一个要"像人一样生活"的 agent, 重复比丢失便宜得多 ——
 * 真人也会"这消息我好像看过"。所以规则是: <b>poll 之后才标记</b>。
 *
 * <h2>被淘汰的刺激会怎样</h2>
 * {@code RealtimeEventQueue} 有容量上限, 溢出时淘汰 {@code urgency} 最低的那条
 * （并记一条 {@code system.stimulus-dropped.v1}）。被淘汰的那条<b>没有</b>被标记为已投递,
 * 所以重启后它会被重新投递一次。这是刻意的:
 * 内存里的淘汰是"队列满了"这个瞬时压力造成的, 而不是"她不需要知道这件事"。
 * 让她在重启后有一次机会注意到它, 比让"她错过了那通电话"成为一个无法挽回的事实要好。
 */
@Slf4j
public class StimulusReplayStore {

    private final WorldEventStore events;

    public StimulusReplayStore(WorldEventStore events) {
        this.events = Objects.requireNonNull(events, "事件存储不能为空 —— 队列是从它重建出来的");
    }

    // ─────────────────────────── 恢复 ───────────────────────────

    /** 用默认容量重建队列。 */
    public ReplayResult replay(String worldId) {
        return replay(worldId, RealtimeEventQueue.DEFAULT_CAPACITY);
    }

    /**
     * 重建她"还没注意到"的那批刺激。
     *
     * <h2>为什么先按 {@code category} 列过滤, 再反序列化</h2>
     * 顺序很重要: 一次恢复要把这个 agent <b>全部</b>未投递的事件读出来
     * （里面混着 A 类和 C 类）, 而反序列化是这里面最贵的一步。
     * 更要紧的是<b>正确性</b>: 一条 A 类事件的类型认不出来（三方插件被卸载）
     * 不该让整次刺激重放失败 —— 它本来就不属于这条路径。
     * 先按列筛掉它, 那条坏数据就影响不到这里。
     *
     * <p>过滤用 {@link WorldEventStore#hasCategory}（逐段相等）而不是
     * {@code String.contains}: 见那个方法的说明。
     */
    public ReplayResult replay(String worldId, int capacity) {
        Objects.requireNonNull(worldId, "必须指明是哪个世界");
        RealtimeEventQueue queue = new RealtimeEventQueue(capacity);

        List<WorldEventRecord> rows = events.pendingRows(worldId);
        List<WorldEventRecord> sensoryRows = new ArrayList<>();
        List<WorldEventRecord> notSensory = new ArrayList<>();
        for (WorldEventRecord row : rows) {
            if (WorldEventStore.hasCategory(row.getCategory(), CoreEventCatalog.Category.SENSORY)) {
                sensoryRows.add(row);
            } else {
                notSensory.add(row);
            }
        }

        // 一次解码整批 —— 逐行解码会为每一行新建一个中间列表, 而一次恢复本来就要
        // 把全部未投递的事件读完。顺序由 decode 保证（与输入行一致, 即 occurred_at 正序）
        WorldEventStore.ReadResult<WorldEvent> decoded = events.decode(sensoryRows, SensoryEvent.class);
        int offered = 0;
        for (WorldEvent event : decoded.values()) {
            queue.offer((SensoryEvent) event);
            offered++;
        }
        List<WorldEventRecord> unreadable = decoded.unreadable();

        if (!unreadable.isEmpty()) {
            log.warn("[Persistence] 重放 {} 的刺激时, {} 条事件的类型认不出来 —— "
                            + "它们<b>没有</b>被标记为已投递, 所以不会丢: "
                            + "插件装回来之后它们还会在那批未投递的事件里。"
                            + "受影响的 id: {}", worldId, unreadable.size(),
                    ids(unreadable));
        }
        return new ReplayResult(queue, List.copyOf(notSensory), List.copyOf(unreadable), offered);
    }

    // ─────────────────────────── 投递 ───────────────────────────

    /**
     * 这几条刺激已经交到她手里了 —— 把 {@code published_at} 写上。
     *
     * <p>调用时机见类注释。委托给 {@link WorldEventStore#markPublished} 而不是
     * 自己写一遍: {@code published_at} 的语义只有一处定义, 而这个方法只是
     * 从队列那一侧给它起的名字。
     *
     * @return 本次真正被标记的条数（已投递的不计入）
     */
    public int markDelivered(List<String> eventIds, Instant deliveredAt) {
        return events.markPublished(eventIds, deliveredAt);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static List<String> ids(List<WorldEventRecord> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (WorldEventRecord row : rows) {
            out.add(row.getId());
        }
        return out;
    }

    /**
     * 一次重建的结果。
     *
     * @param queue     重建出来的队列。调用方接手它继续跑 —— 本类不再持有它
     * @param notSensory 未投递、但不是 B 类的事件。**这不是错误**: 它们是
     *                  A 类（账本）与 C 类（计划）的账, 各自有各自的恢复路径。
     *                  列出来只是为了让"这次恢复读了多少行"可核对
     * @param unreadable 是 B 类但类型读不回来的行 —— 这些要告警, 而且它们
     *                   <b>没有</b>被标记为已投递（见 {@link #replay} 的说明）
     * @param offered   真正进了队列的条数
     */
    public record ReplayResult(RealtimeEventQueue queue,
                               List<WorldEventRecord> notSensory,
                               List<WorldEventRecord> unreadable,
                               int offered) {

        public ReplayResult {
            Objects.requireNonNull(queue, "重放必须产出一个队列 —— 空队列也是一个队列");
            notSensory = notSensory == null ? List.of() : List.copyOf(notSensory);
            unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
        }

        /** 这次恢复读到的全部未投递事件。 */
        public int scanned() {
            return notSensory.size() + unreadable.size() + offered;
        }

        /** 有没有读不回来的东西 —— 告警与健康检查用。 */
        public boolean degraded() {
            return !unreadable.isEmpty();
        }

        /** 诊断用的一行字。 */
        public String describe() {
            return "扫过 " + scanned() + " 条未投递事件: 进队列 " + offered
                    + "、非刺激类 " + notSensory.size()
                    + "、读不回来 " + unreadable.size()
                    + "; 队列现在 " + queue.size() + "/" + queue.capacity();
        }
    }
}
