package com.luxera.companion.human.life.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * V2.2 §3.5.3 —— <b>某一刻, 她对未来的完整安排</b>。
 *
 * <h2>为什么是"版本"而不是"可变的表"</h2>
 * 设计文档 §3.5.3 把这一点讲得很直白:
 * <blockquote>
 * 计划的变化不是"更新几行", 而是<b>"她对未来的一次重新安排"</b>。
 * 这两个东西必须能分开看, 否则用户追问"你不是说要去跑步吗"时, 系统无法解释。
 * </blockquote>
 *
 * <p>于是每一次重排都产出一个<b>新的</b> Revision, 旧的进 {@link PlanBoard} 的
 * {@code revisionHistory} —— <b>永不删除</b>。这条"永不删除"是审计、回放、调试、
 * 行为分析、Agent 学习五件事的共同地基。没有它, "她为什么改主意了"无法回答。
 *
 * <h2>三个结构, 各司其职</h2>
 * <table border="1">
 *   <tr><th>结构</th><th>支撑的问题</th><th>复杂度</th></tr>
 *   <tr>
 *     <td>{@code items}（按 start 排序的 List）</td>
 *     <td>"她今天都安排了什么"</td><td>顺序遍历</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #timelineIndex}</td>
 *     <td>"到点了该触发哪些"</td>
 *     <td><b>O(log n)</b> 而不是每秒全表扫描</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #byId}</td>
 *     <td>"item-3 现在是什么状态"</td><td>O(1)</td>
 *   </tr>
 * </table>
 *
 * <p><b>为什么 {@link #timelineIndex} 是必需的而不是优化</b>: 现有实现
 * {@code LifeTickJob} 每 tick 扫描全部活动判断"该结束了吗"。活动数量不大时看不出问题,
 * 但它的复杂度是 O(n)/tick, 而 tick 是秒级的。索引把它变成"只查
 * {@code start_at <= t} 的那一小撮" —— 与本设计的"每个 tick 都要结算账本"
 * 叠加时, 这个差别决定了 agent 能不能在实时约束下跑。
 *
 * <h2>为什么这个类是 {@code final class} 而不是 {@code record}</h2>
 * 它有<b>派生状态</b>: {@link #timelineIndex} 和 {@link #byId} 都是从 {@code items}
 * 算出来的, 而 record 不允许有额外的实例字段。三个选择:
 * <table border="1">
 *   <tr><th>做法</th><th>为什么不行</th></tr>
 *   <tr><td>把索引做成 record 组件</td>
 *       <td>它会进 {@code equals}/{@code hashCode}/{@code toString} ——
 *           于是两个"内容相同"的 Revision 会因索引实现不同而不相等,
 *           而 {@code toString} 会打印出一大坨索引</td></tr>
 *   <tr><td>每次查询时重算索引</td>
 *       <td>把 O(log n) 的查询变回 O(n) —— 正好抵消了索引存在的理由</td></tr>
 *   <tr><td><b>final class + 构造时算一次</b></td>
 *       <td>—— 就是本实现</td></tr>
 * </table>
 *
 * <p>不可变性由三件事保证: 全部字段 {@code final}; 构造时对传入集合做防御性拷贝;
 * 对外暴露的集合都是不可变视图。<b>没有 setter, 也没有返回内部引用的 getter。</b>
 */
public final class PlanRevision {

    private static final Comparator<PlanItem> BY_START =
            Comparator.comparing((PlanItem i) -> i.window().start())
                    .thenComparing(i -> i.window().end())
                    // 同一时刻开始的项按 id 定序 —— 没有这个 tie-breaker,
                    // 排序结果会依赖输入顺序, 于是"她今天的计划表"每次打印都不一样
                    .thenComparing(i -> i.id().value());

    private final long revisionNumber;
    private final String revisionId;
    private final String previousRevisionId;
    private final String reason;
    private final Instant createdAt;
    private final List<PlanItem> items;
    private final List<PlanConstraint> constraints;
    private final List<PlanMutation> mutations;

    /** {@code start_at} → 该时刻开始的项。{@link NavigableMap} 支撑 {@code floorEntry} 等范围查询。 */
    private final NavigableMap<Instant, Set<PlanItemId>> timelineIndex;

    private final Map<PlanItemId, PlanItem> byId;

    public PlanRevision(long revisionNumber,
                        String previousRevisionId,
                        String reason,
                        Instant createdAt,
                        Collection<PlanItem> items,
                        Collection<PlanConstraint> constraints,
                        Collection<PlanMutation> mutations) {
        this.revisionNumber = revisionNumber;
        this.revisionId = "rev-" + revisionNumber;
        this.previousRevisionId = previousRevisionId;
        this.reason = Objects.requireNonNull(reason,
                "每个 Revision 都必须说明为什么产生 —— 这句话要进行为分析报告");
        this.createdAt = Objects.requireNonNull(createdAt, "Revision 的产生时刻不能为空");
        this.constraints = constraints == null ? List.of() : List.copyOf(constraints);
        this.mutations = mutations == null ? List.of() : List.copyOf(mutations);

        List<PlanItem> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort(BY_START);
        this.items = Collections.unmodifiableList(sorted);

        Map<PlanItemId, PlanItem> index = new LinkedHashMap<>();
        NavigableMap<Instant, Set<PlanItemId>> timeline = new TreeMap<>();
        for (PlanItem item : sorted) {
            index.put(item.id(), item);
            timeline.computeIfAbsent(item.window().start(), k -> new java.util.LinkedHashSet<>())
                    .add(item.id());
        }
        this.byId = Collections.unmodifiableMap(index);
        this.timelineIndex = Collections.unmodifiableNavigableMap(timeline);
    }

    /** 第一个 Revision 用的工厂 —— 没有前驱。 */
    public static PlanRevision initial(Instant createdAt, Collection<PlanItem> items, String reason) {
        return new PlanRevision(1, null, reason, createdAt, items, List.of(), List.of());
    }

    // ─────────────────────────── 基本读取 ───────────────────────────

    public long revisionNumber() {
        return revisionNumber;
    }

    /** 形如 {@code rev-18} —— 会出现在日志、界面和行为分析报告里。 */
    public String revisionId() {
        return revisionId;
    }

    public Optional<String> previousRevisionId() {
        return Optional.ofNullable(previousRevisionId);
    }

    /** 为什么产生这个版本。用户追问"你不是说要去跑步吗"时的答案来源。 */
    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** 全部计划项, 按开始时间排序。 */
    public List<PlanItem> items() {
        return items;
    }

    /** 这一版生效的全局约束（各项自己附加的在 {@link PlanItem#constraints()} 上）。 */
    public List<PlanConstraint> constraints() {
        return constraints;
    }

    /** 这一版相对上一版做了哪些改动。第一个 Revision 时为空。 */
    public List<PlanMutation> mutations() {
        return mutations;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public Optional<PlanItem> find(PlanItemId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    // ─────────────────────────── 时间轴查询 ───────────────────────────

    /**
     * 在 {@code moment} 或之前<b>开始</b>、且还没结束的项。
     *
     * <p>实现走 {@link #timelineIndex} 的 {@code headMap} —— 只扫"起点 ≤ moment"的那一段,
     * 而不是全部项。这正是加索引的目的。
     */
    public List<PlanItem> startedBy(Instant moment) {
        Objects.requireNonNull(moment, "查询必须带时刻");
        List<PlanItem> out = new ArrayList<>();
        for (Set<PlanItemId> ids : timelineIndex.headMap(moment, true).values()) {
            for (PlanItemId id : ids) {
                PlanItem item = byId.get(id);
                if (item != null && item.lifecycle().occupiesFuture()) {
                    out.add(item);
                }
            }
        }
        out.sort(BY_START);
        return List.copyOf(out);
    }

    /**
     * 在 {@code moment} 或之前该触发、但还没触发过的项 —— {@link PlanScheduler} 用。
     *
     * <p>判断条件收在 {@link PlanItem#dueAt} 里（它含"仍然是 PENDING"这一条）,
     * 这样"重复触发"这个 bug 只可能出现在那一个地方。
     */
    public List<PlanItem> dueAt(Instant moment) {
        return startedBy(moment).stream().filter(i -> i.dueAt(moment)).toList();
    }

    /**
     * {@code moment} 这一瞬间她"归哪一项"。
     *
     * <p>正常情况下至多一项（窗口不重叠）。返回 List 而不是 Optional 是因为
     * <b>重叠是可能真实存在的</b> —— {@link PlanValidator} 用硬约束挡住它, 但历史数据里
     * 可能有校验引入之前写入的重叠项。返回列表让读取方不必假设"永远最多一个"。
     */
    public List<PlanItem> activeAt(Instant moment) {
        Objects.requireNonNull(moment, "查询必须带时刻");
        return items.stream()
                .filter(i -> i.lifecycle().occupiesFuture() && i.window().contains(moment))
                .toList();
    }

    /** 仍然占着未来时间的项（PENDING + ACTIVE）。 */
    public List<PlanItem> liveItems() {
        return items.stream().filter(i -> i.lifecycle().occupiesFuture()).toList();
    }

    /**
     * 一对一对重叠的项 —— 诊断与 {@link PlanValidator} 用。
     *
     * <p>O(n²) 的双重循环, 而 n 是"她一天的计划项数"（几十量级）。
     * 用区间树能做到 O(n log n), 但那会让本方法从十行变成一百行, 而收益是
     * 在一个不可能变大的 n 上省几微秒。<b>知道 n 的上界, 是选择算法的一部分。</b>
     */
    public List<String> overlaps() {
        List<String> out = new ArrayList<>();
        List<PlanItem> live = liveItems();
        for (int i = 0; i < live.size(); i++) {
            for (int j = i + 1; j < live.size(); j++) {
                PlanItem a = live.get(i);
                PlanItem b = live.get(j);
                if (a.window().overlaps(b.window())) {
                    out.add(name(a) + " 与 " + name(b) + " 时间重叠");
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 一句话说清"是哪一项" —— <b>先说人话, 再给编号</b>。
     *
     * <p>这个顺序是刻意的, 因为这句话有两条去路, 而两条都要求"先人话":
     * <ol>
     *   <li>它会进入 {@code system.plan-validation-failed.v1} 的载荷, 而那条事件是
     *       {@link PlanValidator} 驳回一份提案时<b>写给模型看的</b>。模型下次重排时
     *       要能看懂"哪两项撞了", 而它认得的是
     *       {@code intent().description()} 里那些词, 不是 {@code item-27} 这种内部编号。</li>
     *   <li>它也会出现在她的控制台与日志里。她问"为什么这次安排没通过",
     *       答案是"「写作业」和「吃饭」撞了", 不是"item-27 和 item-28 撞了"。</li>
     * </ol>
     *
     * <p>编号与窗口<b>没有省略</b>, 而是放在括号里 —— 因为描述会重复:
     * 她一天里可以有两个"写作业"。光有描述, 人在读日志时无法分清是哪两个;
     * 光有编号, 人和模型都读不懂。两个都要, 但主次分明。
     */
    private static String name(PlanItem item) {
        return "「" + item.intent().description() + "」(" + item.id().value()
                + " " + item.window() + ")";
    }

    // ─────────────────────────── 版本链 ───────────────────────────

    /**
     * 产生下一个版本。
     *
     * <p>这是"重排"在数据上的落点。注意它<b>不会</b>去修改本对象 —— 本对象是
     * 历史, 它必须保持它被创建时的样子。
     *
     * @param reason    为什么重排（第一人称、面向人）
     * @param mutations 相对本版做了哪些改动
     */
    public PlanRevision next(Instant at, String reason,
                             Collection<PlanItem> newItems,
                             Collection<PlanMutation> mutations) {
        return new PlanRevision(revisionNumber + 1, revisionId, reason, at, newItems,
                constraints, mutations);
    }

    /**
     * 与另一版的差异 —— <b>只用于诊断展示, 不用于重建历史</b>。
     *
     * <p>为什么明确划定这个边界: 差异比对能算出"哪些项消失了", 但算不出
     * "那是 REMOVE 还是 REPLACE 的一半" —— 见 {@link PlanMutation} 的说明。
     * 想回答"她改了什么主意"必须读 {@link #mutations()}, 而不是比对。
     * 这个方法存在的意义只是给界面一个"变化概览"。
     */
    public List<String> diffFrom(PlanRevision older) {
        Objects.requireNonNull(older, "要比对的旧版本不能为空");
        List<String> out = new ArrayList<>();
        for (PlanItem old : older.items) {
            Optional<PlanItem> now = find(old.id());
            if (now.isEmpty()) {
                out.add("消失: " + old.describe());
            } else if (!now.get().window().equals(old.window())) {
                out.add("改时间: " + old.window() + " → " + now.get().window()
                        + "  (" + old.intent().description() + ")");
            } else if (now.get().lifecycle() != old.lifecycle()) {
                out.add("改状态: " + old.lifecycle().label() + " → " + now.get().lifecycle().label()
                        + "  (" + old.intent().description() + ")");
            }
        }
        Set<PlanItemId> oldIds = older.byId.keySet();
        for (PlanItem item : items) {
            if (!oldIds.contains(item.id())) {
                out.add("新增: " + item.describe());
            }
        }
        return List.copyOf(out);
    }

    /** 这一版改了几次主意 —— 行为分析关心的"计划稳定性"指标。 */
    public int keepActiveCount() {
        return (int) mutations.stream().filter(PlanMutation::isNoop).count();
    }

    /** 这一版放弃/替换掉了几项。 */
    public List<PlanItemId> abandonedItems() {
        return mutations.stream().map(PlanMutation::removedItem).flatMap(Optional::stream).toList();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(revisionId);
        if (previousRevisionId != null) {
            sb.append(" (承接 ").append(previousRevisionId).append(')');
        }
        sb.append(" 「").append(reason).append("」 —— ").append(items.size()).append(" 项:");
        if (items.isEmpty()) {
            sb.append(" 空");
        } else {
            sb.append('\n');
            for (PlanItem item : items) {
                sb.append("    ").append(item.describe()).append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return revisionId + "{" + items.size() + " 项, " + reason + "}";
    }
}
