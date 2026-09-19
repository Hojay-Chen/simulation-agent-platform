package com.luxera.companion.human.life.plan;

import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.event.PlanEventPublisher;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * <b>一条承诺: 构造器装入的那一版计划, 不会变成事件。</b>
 *
 * <h2>为什么这条承诺值得一个类</h2>
 * 它是"恢复"与"重排"的分界线。两者在代码上<b>长得一模一样</b> ——
 * 都是"一张计划表上出现了新的一版" —— 而它们的含义完全相反:
 *
 * <pre>
 *   重排: 她刚刚改了主意      → 必须发 plan.revision-created.v1, 行为分析要算这一次
 *   恢复: 她上次活着时就改过  → 那条事件早就发过了, 世界也已经记下了。再发一次是伪造
 * </pre>
 *
 * <p>伪造的后果不是"多了几条日志": 库里那一版计划与那些假事件之间<b>没有任何矛盾</b>
 * 能让人发现这件事, 于是"她今天重排了几次"这个数从此是错的, 而它看起来完全正常。
 *
 * <h2>为什么不能"先挂一个观察者, 再造表"来测</h2>
 * 因为那做不到: 观察者只能挂在一张<b>已经存在</b>的表上, 于是构造器跑的那一刻
 * 观察者集合必然是空的。那样的断言证明的是"观察者不会自己响", 不是"装载不发事件"。
 *
 * <p>所以这里测的是<b>后果</b>: 造表 → 挂发布器 → 断言零事件 → 再改一次表 → 断言有事件。
 * 最后一步是这条测试的阳性对照, 没有它前半句就退化成了"发布器坏了"的证明。
 */
class PlanBoardTest {

    private static final Instant T_0800 = Instant.parse("2026-03-02T08:00:00Z");
    private static final Instant T_0900 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Instant T_0915 = Instant.parse("2026-03-02T09:15:00Z");

    // ─────────────────────── 一、装载本身: 装进去了, 而且只有一版 ───────────────────────

    @Test
    @DisplayName("构造器装入的那一版就是当前版本 —— 装的是它本身, 不是它的副本")
    void 装入的那一版就是当前版本() {
        PlanRevision restored = revisionWithHomework();

        PlanBoard board = new PlanBoard(restored);

        // assertSame 而不是 assertEquals: 领域里的记录都是不可变值, 两者都会过 ——
        // 但这里要钉的是"装载没有经过一次改写", 而那正是同一个引用能证明的事。
        assertSame(restored, board.current(),
                "装入的那一版被换成了别的东西 —— 恢复出来的是她的历史, 不是一次新排的计划");
        assertEquals(1, board.current().size());
    }

    @Test
    @DisplayName("装载不发事件 —— 而紧接着的一次改动会发(阳性对照)")
    void 装载不发事件() {
        EventFabric fabric = fabric();
        PlanBoard board = new PlanBoard(revisionWithHomework());

        // 挂发布器发生在装载**之后** —— 这个顺序正是 Life 的构造器保证的那一件事。
        new PlanEventPublisher(fabric).attachTo(board);

        assertEquals(List.of(), fabric.recentEvents(10),
                "装载被当成了一次真实的计划变更发了出去 —— 每次重启, 世界都会收到一遍"
                        + "她生前的全部 plan.revision-created.v1 + plan.item-scheduled.v1, "
                        + "而行为分析会把它们算成'她刚刚做的决定'");

        // ├─ 阳性对照: 同样的表、同样的发布器, 一次真实的改动必须发得出来。
        //    没有这一步, 上面那条断言只是在证明"这个发布器不会发事件"。
        board.apply(T_0915, "加件衣服再出门", List.of(new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_0915, T_0915.plusSeconds(600))),
                "有点冷")));

        List<WorldEvent> published = fabric.recentEvents(10);
        assertEquals(2, published.size(),
                "一次改动应当发两条(先逐项安排、后版本)—— 收到: "
                        + published.stream().map(WorldEvent::typeId).toList());
    }

    // ─────────────────────── 二、观察者视角: 装载不经过通知 ───────────────────────

    @Test
    @DisplayName("挂上观察者之后到第一次改动之前, 它一次都没被通知")
    void 观察者在第一次改动之前是安静的() {
        AtomicInteger revisions = new AtomicInteger();
        AtomicInteger transitions = new AtomicInteger();
        PlanBoard board = new PlanBoard(revisionWithHomework());
        board.addObserver(counting(revisions, transitions));

        // 被读一遍 —— 读不是改动, 不该惊动任何人。
        board.current();

        assertEquals(0, revisions.get(), "装载通知了观察者 —— 见本类类注释");
        assertEquals(0, transitions.get(), "装载通知了观察者 —— 见本类类注释");

        board.apply(T_0915, "加件衣服再出门", List.of(new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"), TimeWindow.of(T_0915, T_0915.plusSeconds(600))),
                "有点冷")));

        assertEquals(1, revisions.get(),
                "一次真实的改动没有通知观察者 —— 那么上面那两条零就成了'观察者坏了'的证明, "
                        + "而不是'装载是静默的'的证明");
    }

    @Test
    @DisplayName("空表(她还没有任何计划)走的是同一条路 —— 它同样不发事件")
    void 空表也不发事件() {
        EventFabric fabric = fabric();
        PlanBoard board = new PlanBoard();
        new PlanEventPublisher(fabric).attachTo(board);

        assertEquals(List.of(), fabric.recentEvents(10),
                "'她还没有任何计划'被当成了一次计划变更 —— 那会让每一个刚出生的 agent "
                        + "在启动时都多出一条她从未做过的决定");
        assertEquals(0, board.current().size());
    }

    // ─────────────────────── 测试脚手架 ───────────────────────

    /** 一版"她下午要写作业"的计划 —— 这就是从库里恢复回来的那一版的样子。 */
    private static PlanRevision revisionWithHomework() {
        return PlanRevision.initial(T_0800,
                List.of(PlanItem.schedule(intent("homework", "写作业"),
                        TimeWindow.of(T_0900, T_0900.plusSeconds(3600)))),
                "早上把今天排一下");
    }

    private static PlanBoard.Observer counting(AtomicInteger revisions,
                                               AtomicInteger transitions) {
        return new PlanBoard.Observer() {
            @Override
            public void onRevision(PlanRevision revision) {
                revisions.incrementAndGet();
            }

            @Override
            public void onItemTransition(PlanItemId id, PlanLifecycle from,
                                         PlanLifecycle to, String why) {
                transitions.incrementAndGet();
            }
        };
    }

    private static PlanIntent intent(String id, String description) {
        return new FixedIntent(new PlanIntent.IntentId(id), description);
    }

    private static EventFabric fabric() {
        return new DefaultEventFabric("test-human", new EventHandlerRegistry());
    }

    /**
     * 一个不需要上下文的意图 —— 本测试测的是装载与发布, 不是领域知识。
     *
     * <p>{@code activityType()} 用接口的默认值({@code life.activity.other}) ——
     * 本测试不启动任何活动, 所以它只是一个必须被满足的契约。
     */
    private record FixedIntent(PlanIntent.IntentId id, String description) implements PlanIntent {

        @Override
        public Feasibility evaluate(PlanningContext context) {
            return Feasibility.yes("测试意图, 总是可行");
        }

        @Override
        public List<ActionIntent> decompose(PlanningContext context) {
            return new ArrayList<>();
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }
    }
}
