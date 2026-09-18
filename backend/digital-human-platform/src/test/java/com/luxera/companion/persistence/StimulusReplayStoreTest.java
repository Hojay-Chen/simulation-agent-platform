package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.RealtimeEventQueue;
import com.luxera.companion.boundary.event.SensoryEvent;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import com.luxera.companion.persistence.entity.WorldEventRecord;
import com.luxera.companion.persistence.repository.WorldEventRecordRepository;
import com.luxera.companion.persistence.store.StimulusReplayStore;
import com.luxera.companion.persistence.store.WorldEventStore;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.2 —— <b>{@code RealtimeEventQueue} 没有自己的表</b>:
 * 它是 {@code world_event} 里 {@code category} 含 {@code SENSORY}
 * 且 {@code published_at IS NULL} 的那些行的一个<b>投影</b>。
 *
 * <h2>这个设计要付的代价, 以及本文件要盯住的那一件事</h2>
 * 队列不落库意味着"队列里有什么"这件事在库里只以行与两个列的形式存在,
 * 于是恢复路径上任何一处判断错了, 表现都是<b>她少注意到一件事</b> ——
 * 而不是一条报错。少投一条刺激与"那件事本来就没发生"在数据上完全一样。
 * 所以这里的断言必须细到"到底哪几条进了队列、哪几条没进、以及没进的那几条
 * 以后还有没有机会"。
 *
 * <h2>"未注册"的两种成因都在这里被证明</h2>
 * {@code WorldEventStoreTest} 测的是 (a)"写入时就没注册"（那一行只有
 * {@code _untyped}）。本文件测的是 (b)"<b>写入时注册了、读取时没了</b>"——
 * 也就是插件被卸载。两者在数据上的形状完全不同:
 * <pre>
 *   (a) 类型列全空, 载荷里只有 _untyped      → 需要人工回填才能救回来
 *   (b) 类型列完好（plan / item-due / 1）     → 插件装回来的那一刻它就自己活了
 * </pre>
 * (b) 的证明方式是<b>两个注册表</b>: 写的时候用一个认识它的注册表,
 * 读的时候换成一个不认识它的。这不是"模拟"—— 这正是运行时发生的事
 * （{@code DomainTypeRegistry} 的装配顺序与 classpath 扫描范围决定谁在表里）。
 */
class StimulusReplayStoreTest {

    private static final String WORLD = "world.test-01";
    private static final Instant T0 = Instant.parse("2026-09-19T09:00:00Z");

    // ═══════════════════════════ ① 基本重放 ═══════════════════════════

    @Test
    @DisplayName("未投递的刺激按发生时刻重建进队列 —— 顺序决定了她先被哪件事惊到")
    void 未投递的刺激按发生时刻重建进队列() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        // 故意乱序写入: 恢复的顺序必须由 occurred_at 决定, 而不是写入顺序
        store.append(WORLD, due("pi-3", T0.plusSeconds(300)));
        store.append(WORLD, due("pi-1", T0));
        store.append(WORLD, due("pi-2", T0.plusSeconds(60)));

        StimulusReplayStore replay = new StimulusReplayStore(store);
        StimulusReplayStore.ReplayResult result = replay.replay(WORLD);

        assertEquals(3, result.offered(), "三条都进了队列");
        assertFalse(result.degraded(), "没有读不回来的东西");
        assertTrue(result.notSensory().isEmpty(), "队列只收 B 类");

        List<SensoryEvent> queued = result.queue().snapshot();
        assertEquals(3, queued.size());
        // 三条的优先级相同, 于是队列的定序退化到"入队顺序"—— 而入队顺序
        // 由 decode 保证与行序一致, 行序由 occurred_at 正序保证
        assertEquals(T0, queued.get(0).occurredAt());
        assertEquals(T0.plusSeconds(60), queued.get(1).occurredAt());
        assertEquals(T0.plusSeconds(300), queued.get(2).occurredAt());
    }

    @Test
    @DisplayName("A 类事件落在 notSensory 里而不是队列里 —— 各有各的恢复路径, 这不是错误")
    void 非刺激类事件不进队列() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        store.append(WORLD, PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)));
        store.append(WORLD, due("pi-1", T0));

        StimulusReplayStore.ReplayResult result = new StimulusReplayStore(store).replay(WORLD);

        assertEquals(1, result.offered());
        assertEquals(1, result.notSensory().size(), "那条保暖影响是 A 类的账");
        assertEquals("STATE_EFFECT", result.notSensory().get(0).getCategory());
        assertEquals(2, result.scanned(), "扫过的行数 = 进队列 + 非刺激 + 读不回来");
        assertEquals(1, result.queue().size());
    }

    @Test
    @DisplayName("队列容量是重放的参数, 溢出时淘汰的是 urgency 最低的那条而不是最后进来的")
    void 溢出时淘汰的是最不重要的那条() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        // priorityLevel 决定 urgency: 95 → 0.87, 10 → 0.36
        store.append(WORLD, due("pi-urgent", T0, 95));
        store.append(WORLD, due("pi-boring", T0.plusSeconds(1), 10));

        // 容量 1: 两条都要进队列, 但只有一条留得下
        StimulusReplayStore.ReplayResult result =
                new StimulusReplayStore(store).replay(WORLD, 1);

        assertEquals(2, result.offered(), "两条都投过了 —— '她注意到了几条'与'留下了几条'是两件事");
        assertEquals(1, result.queue().size());
        assertEquals("pi-urgent", ((PlanEvents.ItemDue) result.queue().peek().orElseThrow()).itemId(),
                "留下的是紧急的那条 —— 一条手机横幅不该把火警挤出去");
        assertEquals(1, result.queue().droppedStimuli().size());
        assertEquals("pi-boring",
                ((PlanEvents.ItemDue) result.queue().droppedStimuli().get(0).event()).itemId(),
                "被淘汰的也要留下痕迹 —— 那是'她为什么没反应'的答案之一");
    }

    @Test
    @DisplayName("默认容量的重放不会因为条数少而丢弃任何东西")
    void 默认容量下不丢弃() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        store.append(WORLD, due("pi-1", T0));

        RealtimeEventQueue queue = new StimulusReplayStore(store).replay(WORLD).queue();
        assertEquals(RealtimeEventQueue.DEFAULT_CAPACITY, queue.capacity());
        assertTrue(queue.droppedStimuli().isEmpty());
    }

    // ═══════════════════════════ ② 投递标记 ═══════════════════════════

    @Test
    @DisplayName("投过的刺激不再被重建 —— 否则每次重启她都要重新被同一件事惊到")
    void 已投递的刺激不会被重复重建() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        WorldEventRecord first = store.append(WORLD, due("pi-1", T0));
        store.append(WORLD, due("pi-2", T0.plusSeconds(60)));

        StimulusReplayStore replay = new StimulusReplayStore(store);
        assertEquals(1, replay.markDelivered(List.of(first.getId()), T0.plusSeconds(120)));

        StimulusReplayStore.ReplayResult result = replay.replay(WORLD);
        assertEquals(1, result.offered(), "只剩那条没投过的");
        assertEquals("pi-2", ((PlanEvents.ItemDue) result.queue().peek().orElseThrow()).itemId());
        assertNotNull(first.getPublishedAt(), "第一次投递的时刻被写下来了");
    }

    @Test
    @DisplayName("别的世界的事件不参与这个世界的重放 —— world_id 是唯一的隔离")
    void 别的世界的事件不参与重放() {
        WorldEventStore store = store(PersistenceFixtures.codec());
        store.append(WORLD, due("pi-1", T0));
        store.append("world.other", due("pi-9", T0));

        StimulusReplayStore.ReplayResult result = new StimulusReplayStore(store).replay(WORLD);
        assertEquals(1, result.offered());
        assertEquals("pi-1", ((PlanEvents.ItemDue) result.queue().peek().orElseThrow()).itemId());
    }

    // ═══════════════════════════ ③ 插件被卸载 ═══════════════════════════

    @Test
    @DisplayName("插件被卸载: 那一行读不回来, 但类型名完好 —— 装回来它就自己活了")
    void 读取时类型不在注册表里则进_unreadable() {
        // 两次装配共用同一个仓库 —— 这一条测的是"同一个世界里换了注册表"
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore writer = new WorldEventStore(repo, PersistenceFixtures.codec());
        PlanEvents.ItemDue original = due("pi-1", T0);
        WorldEventRecord row = writer.append(WORLD, original);

        // 类型名与全部业务字段都在库里 —— 这是与"写入时就没注册"最关键的区别
        assertEquals("plan", row.getTypeNamespace());
        assertEquals("item-due", row.getTypeName());
        assertEquals(1, row.getMajorVersion());
        assertEquals("plan.item-due.v1", row.getPayloadJson().get(PolymorphicSerializer.TYPE_FIELD));

        // 换成一个"不认识 plan.item-due"的注册表来读 —— 也就是插件被卸载后的那一刻。
        // 注意这个类**仍然在 classpath 上**: 注册表才是权威, 而不是类存不存在
        DomainTypeRegistry withoutPlan = new DomainTypeRegistry();
        withoutPlan.register(PersistenceFixtures.FixtureWarmth.class);
        withoutPlan.register(PersistenceFixtures.FixtureChill.class);
        assertFalse(withoutPlan.resolve("plan.item-due.v1").isPresent(),
                "前置条件: 这个注册表确实不认识它");
        WorldEventStore reader = new WorldEventStore(repo, new DomainPayloadCodec(withoutPlan,
                new PolymorphicSerializer(withoutPlan)));

        StimulusReplayStore.ReplayResult result = new StimulusReplayStore(reader).replay(WORLD);
        assertEquals(0, result.offered(), "读不出来的不进队列 —— 而不是'进一条空的'");
        assertEquals(1, result.unreadable().size());
        assertTrue(result.degraded(), "这是一次降级, 健康检查要能看到");
        assertSame(row, result.unreadable().get(0), "保留整行, 而不只是 id");
        assertNull(row.getPublishedAt(),
                "它没有被标记已投递 —— 所以插件装回来之后它还在那批未投递里, 不会被跳过");
        assertEquals(1, result.scanned(), "它仍然被算进'这次恢复扫了几行'");
    }

    @Test
    @DisplayName("注册表装回来之后那一行还是原来的对象 —— '数据没丢'的可操作含义")
    void 注册表装回来之后那一行仍然可读() {
        // 同一个仓库, 两次装配: 一次带完整的注册表, 一次带一个缺了 plan.item-due 的。
        // 用同一个仓库是刻意的 —— 要证明的差别只在"读的这一刻谁在注册表里"
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        PlanEvents.ItemDue original = due("pi-1", T0);

        DomainTypeRegistry withoutPlan = new DomainTypeRegistry();
        withoutPlan.register(PersistenceFixtures.FixtureWarmth.class);
        DomainPayloadCodec codecWithout = new DomainPayloadCodec(withoutPlan,
                new PolymorphicSerializer(withoutPlan));

        // ① 插件在: 写下去
        new WorldEventStore(repo, PersistenceFixtures.codec()).append(WORLD, original);
        // ② 插件卸载: 读不回来, 但那一行一个字节都没被改
        assertFalse(new WorldEventStore(repo, codecWithout).pending(WORLD).complete());
        // ③ 插件装回来: 同一条记录读回来仍然是当初那个对象
        WorldEventStore.ReadResult<WorldEvent> back =
                new WorldEventStore(repo, PersistenceFixtures.codec()).pending(WORLD);
        assertTrue(back.complete());
        assertEquals(1, back.values().size());
        assertEquals(original, back.values().get(0),
                "类型名一直在列上, 所以它在②里也没有被改写成'读不回来的数据'");
    }

    // ═══════════════════════════ 夹具 ═══════════════════════════

    private static WorldEventStore store(DomainPayloadCodec codec) {
        return new WorldEventStore(FakeRepositories.worldEvents(), codec);
    }

    private static PlanEvents.ItemDue due(String itemId, Instant at) {
        return due(itemId, at, 70);
    }

    private static PlanEvents.ItemDue due(String itemId, Instant at, int priorityLevel) {
        return new PlanEvents.ItemDue(PlanEvents.ITEM_DUE, at, "phone:main",
                itemId, "fixture.test-intent", "写作业",
                at, at.plus(Duration.ofMinutes(45)), priorityLevel, 0L);
    }
}
