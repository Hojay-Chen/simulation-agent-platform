package com.luxera.companion.human.life.plan.event;

import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.human.life.plan.PlanBoard;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanRevision;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2.2 §3.5.6 —— <b>把"计划变了"变成世界事件, 而不让计划表认识事件总线</b>。
 *
 * <h2>为什么需要这个中间人</h2>
 * 用户对 Human ⟂ World 的要求是彻底的: 两边<b>完全没有交互</b>,
 * 唯一的通道是边界层。而 {@link PlanBoard} 住在 {@code human/life/} 里 ——
 * 它<b>不能</b>直接持有 {@link EventFabric}。
 *
 * <p>但"她重排了计划"这件事又必须变成世界事件: 行为分析要看到它、前端要展示它、
 * LLM 的 context 要包含它。于是 {@link PlanBoard} 暴露了
 * {@link PlanBoard.Observer}, 而本类是这个接口的一个实现 —— 它站在两边中间,
 * 是计划表唯一知道的"外面"。
 *
 * <pre>
 *   PlanBoard ──notify──> Observer（本类）──publish──> EventFabric ──> World
 *      ↑                                                                    │
 *      └──────────────── 计划表不知道 EventFabric 的存在 ────────────────────┘
 * </pre>
 *
 * <p>这不只是洁癖。它有一个具体的好处: 计划表可以被<b>单独测试</b> ——
 * 一个 {@code new PlanBoard()} 不需要事件总线、不需要 Spring 容器,
 * 而重排逻辑的测试占了 §8.2.5 的绝大部分。
 *
 * <h2>本类发哪两条事件, 以及为什么只有这两条</h2>
 * <table border="1">
 *   <tr><th>时机</th><th>事件</th><th>为什么在这里发</th></tr>
 *   <tr>
 *     <td>新版本里出现了本版新建的项</td>
 *     <td>{@code plan.item-scheduled.v1}</td>
 *     <td>"她安排了一件事"这个事实只有版本本身知道
 *         —— 判据是 {@code createdInRevision == revisionNumber}，
 *         它精确地圈出"这一版新排进来的", 而不会把上一版就有的项重复播报</td>
 *   </tr>
 *   <tr>
 *     <td>版本产生</td>
 *     <td>{@code plan.revision-created.v1}</td>
 *     <td>它是"她的想法变了"的唯一权威记录, 带改动清单与理由</td>
 *   </tr>
 * </table>
 *
 * <p><b>顺序是先逐项、后整体。</b>消费方（前端时间轴、行为分析）按顺序读下来
 * 会先看到"多了几件事", 再看到"这是一次重排, 理由是……"。反过来读的话,
 * 每条 item-scheduled 都要自己去找它属于哪次重排。
 *
 * <h2>为什么不在这里发 {@code plan.item-finished} / {@code plan.item-interrupted}</h2>
 * 因为它们需要的信息本类没有: "实际做了多久"、"被什么打断的"。
 * {@link PlanBoard.Observer#onItemTransition} 只给出
 * {@code (id, from, to, why)} —— 拿它去构造一条 {@code ItemFinished}
 * 就得去猜 {@code actualDuration}, 而猜出来的时长会让所有基于它的统计
 * （"她估时间准不准"）变成噪声。
 *
 * <p>那两条由 {@code human.life.Life} 发 —— 它是唯一同时知道
 * "她什么时候开始做的"和"她当时身体什么状态"的对象。
 * <b>本类是 {@code plan.item-scheduled.v1} 与 {@code plan.revision-created.v1}
 * 的唯一发布者</b> —— 别处再发一次就会产生两条描述同一件事的事件,
 * 而它们迟早会不一致。
 *
 * <h2>发布者分工一览（避免两处都发同一条）</h2>
 * <table border="1">
 *   <tr><th>事件</th><th>唯一发布者</th></tr>
 *   <tr><td>{@code plan.item-scheduled.v1}</td><td>本类（{@code onRevision} 里逐项）</td></tr>
 *   <tr><td>{@code plan.revision-created.v1}</td><td>本类</td></tr>
 *   <tr><td>{@code plan.item-due.v1}</td>
 *       <td>{@code Life.advanceTo} —— 只有它同时看到调度器与事件总线</td></tr>
 *   <tr><td>{@code plan.item-finished.v1}</td>
 *       <td>{@code Life.concludeCurrent} —— 它才有"实际做了多久"</td></tr>
 *   <tr><td>{@code plan.item-interrupted.v1}</td>
 *       <td>{@code Life.interruptCurrent} —— 它才有"被什么打断、多急"</td></tr>
 *   <tr><td>{@code system.plan-validation-failed.v1}</td>
 *       <td>{@link com.luxera.companion.human.life.plan.PlanValidator}</td></tr>
 * </table>
 */
@Slf4j
public class PlanEventPublisher implements PlanBoard.Observer {

    private final EventFabric fabric;
    private final AtomicLong publishedRevisions = new AtomicLong();
    private final AtomicLong publishedItems = new AtomicLong();
    private final AtomicLong suppressedTransitions = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public PlanEventPublisher(EventFabric fabric) {
        this.fabric = Objects.requireNonNull(fabric,
                "发布器必须有一个事件总线 —— 没有总线时请不要装配它, 而不是装配一个空壳");
    }

    /** 装配到一张计划表上, 此后它的每次改动都会变成事件。 */
    public void attachTo(PlanBoard board) {
        Objects.requireNonNull(board, "要挂载的计划表不能为空");
        board.addObserver(this);
        log.info("[PlanEventPublisher] 已挂载到计划表, 此后 {} 与 {} 将由这里发布",
                PlanEvents.ITEM_SCHEDULED, PlanEvents.REVISION_CREATED);
    }

    // ─────────────────────────── Observer ───────────────────────────

    @Override
    public void onRevision(PlanRevision revision) {
        Objects.requireNonNull(revision, "新版本不能为空");

        // ① 先播报这一版新排进来的项
        int scheduled = 0;
        for (PlanItem item : revision.items()) {
            if (item.createdInRevision() != revision.revisionNumber()) {
                continue;   // 上一版就在计划表里 —— 不是"她刚安排了它"
            }
            if (item.isTerminated()) {
                // 同一版里建了又取消（比如 Replace 的中间态）—— 播报"安排了一件事"
                // 会与紧随其后的"取消了它"互相矛盾, 而消费方只会看到前一条
                continue;
            }
            if (publish(PlanEvents.ItemScheduled.of(item, revision.revisionNumber(),
                    revision.createdAt()))) {
                scheduled++;
            }
        }

        // ② 再播报"这是一次重排"
        if (publish(PlanEvents.RevisionCreated.of(revision, revision.createdAt()))) {
            publishedRevisions.incrementAndGet();
        }
        publishedItems.addAndGet(scheduled);

        log.debug("[PlanEventPublisher] {} 「{}」→ {} 项新安排, {} 项生效",
                revision.revisionId(), revision.reason(), scheduled, revision.liveItems().size());
    }

    /**
     * 单项状态迁移 —— <b>只记数, 不发事件</b>。
     *
     * <p>理由: {@code plan.revision-created.v1} 的载荷里已经带了
     * "这一版改了哪些项、为什么"（{@link PlanEvents.RevisionCreated#mutations()}）。
     * 再为每个迁移发一条独立事件, 就是<b>两条事件描述同一件事</b> ——
     * 而两条描述同一件事的记录迟早会不一致, 且不一致时无法判断该信哪条。
     *
     * <p>设计文档 §3.5.6 把这条列成了硬要求:
     * "计划项状态变化<b>应尽量批量</b>地写一条 {@code plan.revision-created}，
     * 而不是逐项发事件"。
     *
     * <p>保留计数是为了让"重排次数"之外还能看到"每次重排平均动了多少项" ——
     * 那是判断"她是不是在反复微调"的入口。
     */
    @Override
    public void onItemTransition(PlanItemId id, PlanLifecycle from, PlanLifecycle to, String why) {
        suppressedTransitions.incrementAndGet();
        log.debug("[PlanEventPublisher] 状态迁移 {}({} → {}) 不单独发事件, 它已包含在 {} 里: {}",
                id.value(), from, to, PlanEvents.REVISION_CREATED, why);
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 发一条事件, 把异常吃掉。
     *
     * <p>与 {@link PlanBoard} 对观察者的态度一致: 一个坏的事件总线
     * <b>不该让她的计划表无法更新</b>。计划表已经改完了 —— 那是既成事实;
     * 而事件发不出去只是"这次改动没被记录下来"。
     *
     * <p>但失败会被计数（见 {@link #failures()}），因为它是一个必须被发现的
     * 数据缺口: 计划表说改过，而行为分析看不到, 这两者不一致时必须有人知道。
     *
     * @return 发成功了吗
     */
    private boolean publish(com.luxera.companion.boundary.event.WorldEvent event) {
        try {
            fabric.publish(event);
            return true;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("[PlanEventPublisher] 发布 {} 失败 —— 计划表已经改了, "
                            + "但这次改动没进事件流, 行为分析会看到一个缺口",
                    event.typeId(), e);
            return false;
        }
    }

    // ─────────────────────────── 诊断 ───────────────────────────

    /** 发过多少个版本事件。 */
    public long publishedRevisions() {
        return publishedRevisions.get();
    }

    /** 发过多少条"她安排了一件事"。 */
    public long publishedItems() {
        return publishedItems.get();
    }

    /** 有多少次状态迁移按批量原则被合并进了版本事件。 */
    public long suppressedTransitions() {
        return suppressedTransitions.get();
    }

    /**
     * 有多少条事件没发出去。
     *
     * <p>这个数<b>必须暴露给监控</b>: 它不为零时, 计划表与行为分析的数据
     * 已经不一致了, 而那种不一致不会自己恢复。
     */
    public long failures() {
        return failures.get();
    }

    public String describe() {
        return "PlanEventPublisher[版本 " + publishedRevisions.get()
                + ", 新安排 " + publishedItems.get()
                + ", 合并迁移 " + suppressedTransitions.get()
                + (failures.get() == 0 ? "" : ", 失败 " + failures.get() + " ← 需要处理")
                + "]";
    }

    @Override
    public String toString() {
        return describe();
    }
}
