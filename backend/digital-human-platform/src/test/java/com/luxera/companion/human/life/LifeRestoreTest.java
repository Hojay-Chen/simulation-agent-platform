package com.luxera.companion.human.life;

import com.luxera.companion.boundary.event.DefaultEventFabric;
import com.luxera.companion.boundary.event.EventFabric;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.LifeSnapshot;
import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.PlanningContext;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.registry.EventHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * <b>V2.2 §8.5.6 第 ② 步: 她的计划与手头那件事真的装得回来。</b>
 *
 * <h2>这条测试钉住的是"两种情况长得一样, 而含义相反"</h2>
 * <pre>
 *   她刚刚改了主意（重排）  → 必须发 plan.revision-created.v1
 *   她上次活着时改过（恢复）→ 那条事件早就发过了, 再发一次是伪造
 * </pre>
 *
 * <p>两者在代码上都表现为"一张计划表上出现了新的一版", 而它们唯一的区别是
 * <b>装载与挂发布器的先后</b>。所以这里的第一条断言不是"计划装进去了"
 * （那太弱了 —— 一个先挂发布器再装载的实现照样能让它过),
 * 而是<b>"装进去这件事没有发出任何事件"</b>。
 *
 * <h2>为什么断言必须走 LifeSnapshot</h2>
 * 因为那是控制台看到她的那条路({@code Human.contextAt} → {@code LifeSnapshot}),
 * 而"她带着哪一版计划"这个问题在运维面上只有一个答案:{@code revisionId}。
 * 直接读 {@code life.plan().current()} 会过, 但那只证明字段被赋了值 ——
 * 证明不了外面<b>看得到</b>她被恢复了。
 */
class LifeRestoreTest {

    private static final String HUMAN = "hum_restore";
    private static final Instant T_0800 = Instant.parse("2026-03-02T08:00:00Z");
    private static final Instant T_0900 = Instant.parse("2026-03-02T09:00:00Z");
    private static final Instant T_0950 = Instant.parse("2026-03-02T09:50:00Z");

    // ─────────────────────── 一、计划装回来了 ───────────────────────

    @Test
    @DisplayName("库里的那一版计划变成了她的计划 —— 而且外部看得到")
    void 计划装回来了() {
        PlanRevision restored = revisionWithActiveHomework();

        Life life = new Life(HUMAN, null, PlanningContext.HumanSnapshot::unknown,
                restored, null);

        assertEquals(restored.revisionId(), life.plan().current().revisionId(),
                "装载没有落到 life.plan() 上 —— 恢复读出来的那一版被丢掉了");

        LifeSnapshot snapshot = LifeSnapshot.of(life, T_0950);
        assertEquals(restored.revisionId(), snapshot.revisionId(),
                "控制台看不到她被恢复了 —— 她带着一版计划活着, 而读面上写着什么也没有。"
                        + "那正是'恢复静默地失败'的样子: 她只是偶尔忘了自己下午要去实验室");
        assertEquals(1, snapshot.items().size());
    }

    @Test
    @DisplayName("她手头那件事也装回来了 —— 不是'她以为自己在做'")
    void 活动也装回来了() {
        PlanRevision restored = revisionWithActiveHomework();
        PlanItem active = restored.items().get(0);
        Activity running = ActivityFactory.start(active.intent(), T_0900, active.id());

        Life life = new Life(HUMAN, null, PlanningContext.HumanSnapshot::unknown,
                restored, running);

        assertEquals(running.id(), life.currentActivity().orElseThrow().id(),
                "她手头那件事没有被装回来 —— 库里有一行 RUNNING 而内存里没有, "
                        + "于是 begin(...) 不再拒绝: 同一段时间会被做两次");

        LifeSnapshot snapshot = LifeSnapshot.of(life, T_0950);
        assertEquals(running.id().value(), snapshot.current().id().value(),
                "读面上她手头是空的 —— 那么'她在做什么'这个问题有两个答案, "
                        + "而面板拿到的是错的那一个");
        assertEquals(active.id().value(), snapshot.current().planItemId(),
                "装回来的活动指错了计划项 —— 她做完时 concludeCurrent 找不到它");
    }

    @Test
    @DisplayName("装回来的活动<b>不在</b>已完成日志里 —— 它还没结束")
    void 装回来的活动还没结束所以不进日志() {
        PlanRevision restored = revisionWithActiveHomework();
        PlanItem active = restored.items().get(0);
        Activity running = ActivityFactory.start(active.intent(), T_0900, active.id());

        Life life = new Life(HUMAN, null, PlanningContext.HumanSnapshot::unknown,
                restored, running);

        assertEquals(List.of(), life.activityLog(),
                "进行中的那件事被同时记进了「正在做」与「做完了」—— 她今天做过的事"
                        + "会凭空多出一件, 而行为分析读的就是这个数");
        assertEquals(0, LifeSnapshot.of(life, T_0950).activityLogSize());
    }

    // ─────────────────────── 二、装载是静默的 ───────────────────────

    @Test
    @DisplayName("装载不发一条事件 —— 而紧接着的一次重排会发(阳性对照)")
    void 装载不发假事件() {
        EventFabric fabric = fabric();
        PlanRevision restored = revisionWithActiveHomework();
        PlanItem active = restored.items().get(0);
        Activity running = ActivityFactory.start(active.intent(), T_0900, active.id());

        Life life = new Life(HUMAN, fabric, PlanningContext.HumanSnapshot::unknown,
                restored, running);

        assertEquals(List.of(), fabric.recentEvents(10),
                "恢复被当成了一次真实的计划变更发了出去 —— 每次重启, 世界都会收到一遍"
                        + "她生前的 plan.revision-created.v1 + plan.item-scheduled.v1, "
                        + "而行为分析会把它们算成'她刚刚做的决定'。库里那一版计划与这些"
                        + "假事件之间没有任何矛盾能让人发现这件事");

        // ├─ 阳性对照: 同样的 Life、同样的 fabric, 一次真实的重排必须发得出来。
        life.plan().apply(T_0950, "推后一点再写", List.of(
                new PlanMutation.Move(active.id(),
                        TimeWindow.of(T_0950, T_0950.plusSeconds(3600)), "有点冷")));

        List<WorldEvent> published = fabric.recentEvents(10);
        assertFalse(published.isEmpty(),
                "一次真实的重排没有发任何事件 —— 那么上面那条空断言只是在证明"
                        + "'发布器没挂上', 而不是'装载是静默的'");
    }

    @Test
    @DisplayName("没有历史时走的是同一条路 —— 结构一样, 只是两个参数是空的")
    void 没有历史时也一样() {
        EventFabric fabric = fabric();

        Life life = new Life(HUMAN, fabric, PlanningContext.HumanSnapshot::unknown,
                null, null);

        assertEquals(0, life.plan().current().size(),
                "没有历史 ≠ 空表? 她今天刚出生时计划表就该是空的 —— "
                        + "一个 null 被装成了一个 null 计划表");
        assertNull(LifeSnapshot.of(life, T_0950).current());
        assertEquals(List.of(), fabric.recentEvents(10));
    }

    // ─────────────────────── 三、装载不代替纪律: 表还是只有一张 ───────────────────────

    @Test
    @DisplayName("装回来的表就是她唯一的那张表 —— 不是另造的一张")
    void 装回来的表能被后续改动接上() {
        PlanRevision restored = revisionWithActiveHomework();
        Life life = new Life(HUMAN, null, PlanningContext.HumanSnapshot::unknown,
                restored, null);

        long before = life.plan().current().revisionNumber();
        life.plan().apply(T_0950, "加件衣服再出门", List.of(new PlanMutation.Insert(
                PlanItem.schedule(intent("dress", "穿衣服"),
                        TimeWindow.of(T_0950, T_0950.plusSeconds(600))), "有点冷")));

        assertEquals(before + 1, life.plan().current().revisionNumber(),
                "恢复出来的那一版没有被接上版本链 —— 于是'她的第几版计划'有两个答案");
        assertEquals(2, life.plan().current().size(),
                "新版里保留着上一版的项 —— 那是 PlanRevision 的语义, 见 PlanBoard 类注释");
    }

    // ─────────────────────── 测试脚手架 ───────────────────────

    /** 一版"她正在写作业"的计划 —— 库里那一行 {@code plan_item.lifecycle = ACTIVE} 的样子。 */
    private static PlanRevision revisionWithActiveHomework() {
        PlanItem homework = PlanItem.schedule(intent("homework", "写作业"),
                        TimeWindow.of(T_0900, T_0900.plusSeconds(3600)))
                .withLifecycle(PlanLifecycle.ACTIVE);
        return PlanRevision.initial(T_0800, List.of(homework), "早上把今天排一下");
    }

    private static PlanIntent intent(String id, String description) {
        return new FixedIntent(new PlanIntent.IntentId(id), description);
    }

    private static EventFabric fabric() {
        return new DefaultEventFabric(HUMAN, new EventHandlerRegistry());
    }

    /** 一个不需要上下文的意图 —— 本测试测的是装载, 不是领域知识。 */
    private record FixedIntent(PlanIntent.IntentId id, String description) implements PlanIntent {

        @Override
        public Feasibility evaluate(PlanningContext context) {
            return Feasibility.yes("测试意图, 总是可行");
        }

        @Override
        public List<ActionIntent> decompose(PlanningContext context) {
            return List.of();
        }

        @Override
        public Set<String> requiredCapabilities() {
            return Set.of();
        }
    }
}
