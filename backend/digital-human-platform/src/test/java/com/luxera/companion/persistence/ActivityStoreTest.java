package com.luxera.companion.persistence;

import com.luxera.companion.human.life.activity.Activity;
import com.luxera.companion.human.life.activity.ActivityFactory;
import com.luxera.companion.human.life.activity.ActivityState;
import com.luxera.companion.human.life.activity.ExerciseActivity;
import com.luxera.companion.human.life.activity.OtherActivity;
import com.luxera.companion.human.life.activity.SleepActivity;
import com.luxera.companion.human.life.activity.StudyActivity;
import com.luxera.companion.human.life.plan.PlanIntent;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.persistence.entity.ActivityRecord;
import com.luxera.companion.persistence.repository.ActivityRecordRepository;
import com.luxera.companion.persistence.store.ActivityStore;
import com.luxera.companion.registry.DomainTypeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.2 —— {@code activity_record}:
 * <b>{@code AbstractActivity} 的 javadoc 承诺的那个"扁平形态"的落点</b>。
 *
 * <h2>这张表要回答的问题只有一个</h2>
 * "她重启之前正在做什么, 做到哪一步了"。不存它, 进程重启后她<b>手上是空的</b> ——
 * 而那与"她刚好做完了"在数据上完全一样, 于是没有人能回答
 * "她昨天下午的那两个小时去哪了"。
 *
 * <h2>本文件最要紧的一条: {@code activity_type} 与 {@code intent_type} 是两套三元组</h2>
 * 它们看起来是同一个东西的两份拷贝, 其实不是:
 * <pre>
 *   intent_type   = 她当时<b>想</b>做的那一类   （fixture.unregistered-activity）
 *   activity_type = 她<b>实际</b>做的那一类   （life.activity.other）
 * </pre>
 * 两者不同<b>不是数据损坏, 而是一条真实发生过的事实</b>: 三方应用没注册, 于是
 * {@code ActivityFactory} 把她落到了 {@code OtherActivity}。
 * {@link #实际做的那一类与她想做的那一类不一致时按实际的那一类恢复()}
 * 就是把这件事钉住的用例 —— 它的反面（按 {@code intent} 找类）有一个非常具体
 * 的后果: 那一次"她本来要做的事没做成"的记录会在重启后<b>消失</b>,
 * 于是她下次重启会突然开始打游戏, 而库里没有任何东西解释这个转变。
 *
 * <h2>为什么断言是逐字段的, 而不是一次 {@code equals}</h2>
 * 因为 {@code Activity} <b>不是值对象</b>: {@code OtherActivity} 是一个普通
 * {@code final class}（见它的类注释"为什么不是 record"）, 它的 {@code equals}
 * 是身份相等。于是"往返之后是同一个对象"这句话在这里的准确形式是
 * "往返之后每一个字段都相等" —— 一个把 {@code equals} 当成证明的测试,
 * 会在这个类上永远为假（或者更糟: 在 record 子类上为真而在 {@code OtherActivity}
 * 上为假, 于是同一个测试对不同活动类型说不同的话）。
 */
class ActivityStoreTest {

    private static final String HUMAN = "human.test-01";
    private static final Instant T0 = Instant.parse("2026-09-19T09:00:00Z");

    // ═══════════════════════════ ① 往返 ═══════════════════════════

    @Test
    @DisplayName("正在做的事落库再读回来: id / 意图 / 时间 / 状态 / 计划项一个不差")
    void 正在做的事往返之后每个字段都相等() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        Activity original = ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, PlanItemId.of("pi-1"));
        assertInstanceOf(StudyActivity.class, original, "前置条件: 意图声明的是已注册的类型");

        ActivityRecord row = store.append(HUMAN, original);

        // ── 两套三元组都写下来了, 而且这一条里两者一致。
        // 切分规则是"最后一个点之前全是命名空间": life.activity.study.v1
        // → (life.activity, study, 1)。这与 EventTypeId.toString() 互为逆运算,
        // 于是 ActivityStore.activityTypeOf 拼回去之后正好是 ActivityFactory 的注册键
        assertEquals("life.activity", row.getActivityTypeNamespace());
        assertEquals("study", row.getActivityTypeName());
        assertEquals(1, row.getActivityTypeVersion());
        assertEquals("fixture", row.getIntentTypeNamespace());
        assertEquals("test-intent", row.getIntentTypeName());
        assertEquals("RUNNING", row.getState());
        assertNull(row.getEndedAt(), "还在做 —— 结束时刻是空的");
        assertNull(row.getClosingNote());
        assertNull(row.getFinalProgress(),
                "进行中的活动这一列是 null —— 它与'她结束了但一点没做'(0.0) 是两件事");
        assertEquals("pi-1", row.getPlanItemId());
        assertEquals(T0, row.getStartedAt());

        // ── 读回来
        Activity restored = store.restore(row);
        assertEquals(original.id(), restored.id(), "id 必须原样复用 —— "
                + "新生成一个会让所有指向这条执行的后续事件悬空");
        assertEquals(original.intent(), restored.intent(),
                "意图是'她在做什么'的全部内容, 而它是值对象, 所以可以用 equals");
        assertEquals(original.startedAt(), restored.startedAt());
        assertEquals(ActivityState.RUNNING, restored.state());
        assertEquals(original.planItemId(), restored.planItemId());
        assertTrue(restored.endedAt().isEmpty());
        assertTrue(restored.closingNote().isEmpty());
        assertInstanceOf(StudyActivity.class, restored);
    }

    @Test
    @DisplayName("重启后的第一问: 她现在是不是正在做某件事 —— 走 idx_activity_human_state")
    void 正在做的那一件事能被查出来() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        Activity activity = ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, PlanItemId.of("pi-1"));
        assertTrue(store.runningActivity(HUMAN).isEmpty(), "还没开始做任何事");

        store.append(HUMAN, activity);

        Activity running = store.runningActivity(HUMAN).orElseThrow();
        assertEquals(activity.id(), running.id());
        assertEquals(activity.intent(), running.intent());

        // 别人的执行不会被读进来
        assertTrue(store.runningActivity("human.other").isEmpty(),
                "本层不做'当前 agent'这种隐式假设 —— human_id 必须显式给");
    }

    @Test
    @DisplayName("结束一件事是把同一行改写成它的终局, 而不是新写一行")
    void 结束是改同一行() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        Activity activity = ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, PlanItemId.of("pi-1"));
        ActivityRecord running = store.append(HUMAN, activity);

        Instant endedAt = T0.plus(Duration.ofMinutes(30));
        Activity concluded = activity.conclude(endedAt, ActivityState.CONCLUDED, "写完了");
        ActivityRecord row = store.conclude(running, concluded);

        assertSameId(running, row);
        assertEquals("CONCLUDED", row.getState());
        assertEquals(endedAt, row.getEndedAt());
        assertEquals("写完了", row.getClosingNote());
        assertNotNull(row.getFinalProgress(),
                "收尾时把进度凝固下来 —— 此后无论谁在什么时刻问 progressAt, 答案都一样");

        // 她手上现在是空的
        assertTrue(store.runningActivity(HUMAN).isEmpty());

        // 但那一次执行还在时间轴上 —— 这就是"她昨天下午那两个小时去哪了"的答案
        List<ActivityRecord> timeline = store.timeline(HUMAN, T0.minusSeconds(3600),
                endedAt.plusSeconds(3600));
        assertEquals(1, timeline.size());
        assertEquals("CONCLUDED", timeline.get(0).getState());
    }

    @Test
    @DisplayName("收尾只接受终态 —— 允许写一个进行中的状态会让'她还在做吗'有两个答案")
    void 收尾只接受终态() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        Activity activity = ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, null);
        ActivityRecord running = store.append(HUMAN, activity);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.conclude(running, activity));
        assertTrue(ex.getMessage().contains("不是终态"), ex.getMessage());
    }

    @Test
    @DisplayName("同一项计划执行过几次 —— 计划说'下午写作业', 而实际做了三次")
    void 同一项计划的执行记录能被查出来() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        leave(repo, store, "pi-1", T0, 30);
        leave(repo, store, "pi-1", T0.plus(Duration.ofHours(2)), 20);
        leave(repo, store, "pi-2", T0.plus(Duration.ofHours(4)), 15);

        List<ActivityRecord> executions = store.executionsOf("pi-1");
        assertEquals(2, executions.size(), "那一项实际被执行过两次");
        assertTrue(executions.get(0).getStartedAt().isBefore(executions.get(1).getStartedAt()),
                "正序 —— 分析的顺序不能随数据库的返回顺序变");
        assertTrue(store.executionsOf("pi-9").isEmpty(), "从没被执行过的那一项是空列表");
    }

    // ═══════════════════════════ ② 两套三元组 ═══════════════════════════

    @Test
    @DisplayName("实际做的那一类与她想做的那一类不一致时, 按实际的那一类恢复")
    void 实际做的那一类与她想做的那一类不一致时按实际的那一类恢复() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        // 她想做一件"三方应用"的事, 而那个类型没有注册实现 ——
        // ActivityFactory 于是把她落到了 OtherActivity（这是真实会发生的降级）
        PlanIntent intent = PersistenceFixtures.FixtureIntent.of(
                "intent-1", "打一局游戏", "fixture.unregistered-activity", 30);
        Activity original = ActivityFactory.start(intent, T0, PlanItemId.of("pi-1"));
        assertInstanceOf(OtherActivity.class, original);

        ActivityRecord row = store.append(HUMAN, original);

        // 两套三元组<b>不同</b>, 而这正是这张表要保住的信息
        assertEquals("life.activity", row.getActivityTypeNamespace());
        assertEquals("other", row.getActivityTypeName());
        assertEquals("fixture.unregistered-activity",
                row.getIntentJson().get("activityType"),
                "她想做的那一类留在 intent_json 里");
        assertEquals("fixture", row.getIntentTypeNamespace(),
                "意图自己的类型名是它实现类的名字, 与 activityType 那个字段无关");

        Activity restored = store.restore(row);

        assertInstanceOf(OtherActivity.class, restored,
                "按 activity_type 那一列找类 —— 按 intent.activityType() 找会落到别的类上");
        assertEquals("fixture.unregistered-activity", restored.intent().activityType(),
                "而'她本来要做的事'这条记录没有丢: 重启之后她仍然是'想做那件事但没做成'");
        assertEquals("打一局游戏", restored.intent().description());
        assertEquals(original.id(), restored.id());
        assertEquals(ActivityState.RUNNING, restored.state());
    }

    @Test
    @DisplayName("意图三元组认不出来时必须炸 —— 她不能带着半个自己继续活")
    void 意图读不出来时抛异常() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        ActivityRecord row = store.append(HUMAN, ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, null));

        // 一个不认识 fixture.test-intent 的读侧（意图实现被卸载）
        ActivityStore reader = new ActivityStore(repo,
                PersistenceFixtures.codec(PersistenceFixtures.emptyRegistry()));

        assertThrows(com.luxera.companion.registry.PolymorphicSerializer
                        .UnknownDomainTypeException.class,
                () -> reader.restore(row),
                "事件读不出来可以跳过, 活动读不出来不行: 一个状态不全的 agent "
                        + "比一个起不来的 agent 难查得多 —— 它的表现只是'她今天有点奇怪'");
    }

    @Test
    @DisplayName("状态字符串认不出来时不降级 —— 它是'她当时做完了没有'的唯一依据")
    void 状态认不出来时不降级() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        ActivityRecord row = store.append(HUMAN, ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, null));

        // 人工写一个当前代码不认识的状态（例如某个被删掉的常量留下的历史行）
        row.setState("PAUSED");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.restore(row));
        assertTrue(ex.getMessage().contains("PAUSED"), ex.getMessage());
        assertTrue(ex.getMessage().contains("唯一依据"), ex.getMessage());
    }

    @Test
    @DisplayName("诊断行说得清是谁在什么时候做的什么")
    void 诊断行说清了这一行是什么() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        ActivityRecord row = store.append(HUMAN, ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, PlanItemId.of("pi-1")));

        String described = ActivityStore.describe(row);
        assertTrue(described.contains(HUMAN), described);
        assertTrue(described.contains("life.activity.study"), described);
        assertTrue(described.contains("RUNNING"), described);
        assertTrue(described.contains(row.getId()), described);

        assertEquals(1, store.describeAll(List.of(row)).size());
    }

    /**
     * <b>回归: 这里曾经拼出一个永远匹配不上的查表键。</b>
     *
     * <p>以前的 {@code ActivityStore.activityTypeOf} 拼的是
     * {@code namespace + "." + name + ".v" + version}（{@code EventTypeId} 的形状）,
     * 而 {@code ActivityFactory.CREATORS} 的键是 {@code @DomainType} 的<b>原值</b>
     * —— 于是 {@code "life.activity.study.v1"} 永远查不到 {@code "life.activity.study"},
     * 每一行都落到 {@code OtherActivity}。
     *
     * <h2>为什么这个测试的断言必须是"类名相等"而不是"没抛异常"</h2>
     * 因为那个 bug 的降级路径<b>不抛异常, 只记一条 WARN</b>。它造成的后果是
     * 每一次重启都把活动的档案换成中庸的那一份（注意力曲线、疲劳累积、可打断性全部变样）,
     * 而库里每一列看起来都是对的 —— 是一个典型的"只在她的行为上看得见"的故障。
     * 所以这里必须钉住<b>具体是哪一个类</b>。
     */
    @Test
    @DisplayName("回归: 恢复出来的活动就是当初那一类, 而不是落到 OtherActivity")
    void 恢复出来的活动是当初那一类() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        // 三个内建类型各来一条 —— 一条能过不能证明第二条, 而这是同一处拼串逻辑
        for (String activityType : List.of("life.activity.study", "life.activity.sleep",
                "life.activity.exercise")) {
            PlanIntent intent = PersistenceFixtures.FixtureIntent.of(
                    "intent-" + activityType, "一件她要做的事", activityType, 30);
            Activity original = ActivityFactory.start(intent, T0, null);
            assertFalse(original instanceof OtherActivity,
                    "前置条件: " + activityType + " 是注册过的内建类型");

            Activity restored = store.restore(store.append(HUMAN, original));

            assertEquals(original.getClass(), restored.getClass(),
                    activityType + " 恢复之后必须是同一类 —— 落到 OtherActivity 会让她的行为变样");
            assertFalse(restored instanceof OtherActivity, activityType);
        }
    }

    @Test
    @DisplayName("版本列仍然是关系型元数据: 它记在库里, 只是不参与查表")
    void 版本列仍然被写下来() {
        ActivityRecordRepository repo = FakeRepositories.activities();
        ActivityStore store = new ActivityStore(repo, codec());

        ActivityRecord row = store.append(HUMAN, ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-1"), T0, null));

        assertEquals(1, row.getActivityTypeVersion(),
                "版本是给审计与将来的迁移判据用的 —— 它不进查表键, 不等于它不被存");
        assertEquals("life.activity", row.getActivityTypeNamespace());
        assertEquals("study", row.getActivityTypeName());
        // 三段拼回去是 EventTypeId 的形状, 而前两段拼起来才是工厂的键
        assertEquals("life.activity.study.v1", ActivityStore.typeIdOf(row));
        assertEquals("life.activity.study", ActivityStore.activityTypeOf(row));
    }

    // ═══════════════════════════ 夹具 ═══════════════════════════

    /** 起一件事、做完、落库 —— 供"同一项执行过几次"那条用例复用。 */
    private static void leave(ActivityRecordRepository repo, ActivityStore store,
                              String planItemId, Instant at, int minutes) {
        Activity activity = ActivityFactory.start(
                PersistenceFixtures.FixtureIntent.studying("intent-" + planItemId), at,
                PlanItemId.of(planItemId));
        ActivityRecord running = store.append(HUMAN, activity);
        store.conclude(running, activity.conclude(at.plus(Duration.ofMinutes(minutes)),
                ActivityState.CONCLUDED, "做完了"));
    }

    private static void assertSameId(ActivityRecord expected, ActivityRecord actual) {
        assertEquals(expected.getId(), actual.getId(), "收尾改的是同一行");
        assertSame(expected, actual, "而且是同一个对象 —— 不是新写的一行");
    }

    /**
     * 一个把内建活动类也注册进去的编解码器。
     *
     * <p>{@code activity_type} 那三个列来自 {@code codec.write(activity)},
     * 也就是"实现类的 {@code @DomainType} 有没有被注册"。真装配时的注册表会把
     * {@code human/life/activity} 下的十二个内建活动一起注册, 这里复现的是那个
     * 装配结果的最小切片 —— 少了它, 活动类型那三个列会是空的,
     * 而 {@code ActivityStore.restore} 会在 {@code ActivityFactory} 里
     * 悄悄落到 {@code OtherActivity}, 测试却仍然是绿的。
     */
    private static DomainPayloadCodec codec() {
        DomainTypeRegistry registry = PersistenceFixtures.registry();
        registry.register(StudyActivity.class);
        registry.register(SleepActivity.class);
        registry.register(ExerciseActivity.class);
        registry.register(OtherActivity.class);
        return PersistenceFixtures.codec(registry);
    }
}
