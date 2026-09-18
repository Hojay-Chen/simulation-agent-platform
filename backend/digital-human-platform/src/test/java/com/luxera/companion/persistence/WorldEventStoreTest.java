package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.EventTypeId;
import com.luxera.companion.boundary.event.WorldEvent;
import com.luxera.companion.human.life.plan.event.PlanEvents;
import com.luxera.companion.persistence.entity.WorldEventRecord;
import com.luxera.companion.persistence.repository.WorldEventRecordRepository;
import com.luxera.companion.persistence.store.WorldEventStore;
import com.luxera.companion.registry.CoreEventCatalog;
import com.luxera.companion.registry.DomainType;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.2 / §7.3 —— {@code world_event} 的<b>写入 / 读回</b>。
 *
 * <h2>本文件证明的两件事, 以及它们为什么必须由测试来说</h2>
 * <ol>
 *   <li><b>{@link #存下去再读回来是同一个对象()} —— 多态往返。</b>
 *       §7.1 那张表的设计前提是"稳定的关系型元数据 + 一列多态载荷", 而这句话
 *       只有在"写进去的那一类, 读回来还是那一类、且字段一个不差"时才成立。
 *       一个字段丢了不会报错: 它会变成一个 {@code null} 或者一个默认值,
 *       而症状是"她某天开始闻不到味道"—— 离序列化很远。</li>
 *   <li><b>{@link #未注册的类型不会静默丢数据()} —— "读不出来"与"丢掉了"
 *       是两件事。</b> 这是本层最容易被做错的一处: 一个方便的写法是
 *       "读不出来就跳过", 而那个写法的后果是<b>数据静默消失</b>
 *       （三方插件卸载 = 她的历史被删除）。这里的断言必须细到
 *       "那一列 JSON 里还剩什么", 因为"没丢"的全部证据就在那几行 payload 里。</li>
 * </ol>
 *
 * <h2>"未注册"这件事有<b>两种</b>成因, 而它们的断言不同</h2>
 * <pre>
 *   (a) 写入时就没注册（漏了一行 register、扫描范围写窄了）
 *       → 库里那一行只有 _untyped, 没有任何类型信息;
 *   (b) 写入时注册了、读取时没了（插件被卸载）
 *       → 库里那一行有完好的三列与 _type, 只是读的这一刻没人认识它。
 * </pre>
 * (b) 的"数据不丢"是显然的（类型名就在列上）, 而 (a) 的"数据不丢"是需要证明的
 * —— 它看上去与"什么都没存下"没有区别。{@link #未注册的类型不会静默丢数据()}
 * 测的是 (a), 而 {@link #人工回填_type之后那一行能读回来()} 把 (a) 的结论
 * 推到底: 补齐名字之后, 读回来的对象与当初写下去的那个<b>相等</b>。
 * (b) 由 {@code StimulusReplayStoreTest} 用两个注册表模拟。
 */
class WorldEventStoreTest {

    private static final String WORLD = "world.test-01";
    private static final Instant T0 = Instant.parse("2026-09-19T09:00:00Z");

    // ═══════════════════════════ ① 往返 ═══════════════════════════

    @Test
    @DisplayName("存下去再读回来是同一个对象 —— 一条 B 类刺激的三列元数据与全部载荷")
    void 存下去再读回来是同一个对象() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        PlanEvents.ItemDue due = new PlanEvents.ItemDue(
                PlanEvents.ITEM_DUE, T0, "phone:main",
                "pi-0001", "fixture.test-intent", "写作业",
                T0, T0.plus(Duration.ofMinutes(45)), 70, 120_000L);

        WorldEventRecord row = store.append(WORLD, due);

        // ── 关系型那三列是"给人查的", 单独断言: 它们是索引与目录的落点
        assertEquals("plan", row.getTypeNamespace(), "命名空间被拆成了列, 而不是留在 JSON 里");
        assertEquals("item-due", row.getTypeName());
        assertEquals(1, row.getMajorVersion());
        assertEquals("SENSORY", row.getCategory(), "它实现了 SensoryEvent, 所以类别是 SENSORY");
        assertEquals(T0, row.getOccurredAt());
        assertNull(row.getPublishedAt(), "刚写下的事还没投进她的意识 —— 由恢复流程负责投递");

        // ── 载荷里同时留着 _type（给人看、给外部工具读）
        assertEquals("plan.item-due.v1", row.getPayloadJson().get(PolymorphicSerializer.TYPE_FIELD),
                "类型在两处冗余存着: 三列给索引, 载荷里的 _type 给任何直接读那一列的人");

        // ── 读回来
        WorldEventStore.ReadResult<WorldEvent> back = store.pending(WORLD);
        assertTrue(back.complete(), "没有读不回来的行");
        assertEquals(1, back.values().size());
        assertEquals(due, back.values().get(0),
                "往返之后必须与原来那个对象相等 —— 一个字段变了都会在这里露出来");
    }

    @Test
    @DisplayName("A 类事件的三列与类别 —— 账本那一侧靠 category 列找到自己的行")
    void 持续影响事件的类别是_STATE_EFFECT() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        WorldEventRecord row = store.append(WORLD,
                PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat", T0.plusSeconds(7200)));

        assertEquals("fixture", row.getTypeNamespace());
        assertEquals("warmth", row.getTypeName());
        assertEquals("STATE_EFFECT", row.getCategory());

        // 读回来的是同一个值对象
        WorldEventStore.ReadResult<WorldEvent> back = store.pending(WORLD);
        assertEquals(1, back.values().size());
        assertEquals(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), back.values().get(0));
    }

    @Test
    @DisplayName("未投递的行按发生时刻正序 —— 恢复的顺序决定了她先被哪件事惊到")
    void 未投递的行按发生时刻正序() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        // 故意乱序写入
        store.append(WORLD, due("pi-3", T0.plusSeconds(300)));
        store.append(WORLD, due("pi-1", T0));
        store.append(WORLD, due("pi-2", T0.plusSeconds(60)));

        List<WorldEventRecord> rows = store.pendingRows(WORLD);
        assertEquals(3, rows.size());
        assertEquals(T0, rows.get(0).getOccurredAt());
        assertEquals(T0.plusSeconds(60), rows.get(1).getOccurredAt());
        assertEquals(T0.plusSeconds(300), rows.get(2).getOccurredAt());
    }

    @Test
    @DisplayName("别的世界的事件不会被读进来 —— world_id 是这一层唯一的隔离")
    void 别的世界的事件不会被读进来() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        store.append(WORLD, due("pi-1", T0));
        store.append("world.other", due("pi-9", T0));

        assertEquals(1, store.pendingRows(WORLD).size());
        assertEquals("pi-1", store.pendingRows(WORLD).get(0).getPayloadJson().get("itemId"));
    }

    // ═══════════════════════════ ② 未注册的类型不丢数据 ═══════════════════════════

    @Test
    @DisplayName("未注册的类型不会静默丢数据 —— 整行还在, 只是缺一个名字")
    void 未注册的类型不会静默丢数据() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        RetiredPluginEvent event = new RetiredPluginEvent(EventTypeId.of("fixture", "retired-plugin"),
                T0, "phone:main", 0.4, Duration.ofMinutes(30));
        WorldEventRecord row = store.append(WORLD, event);

        // ── 类型列是空的（它没有 @DomainType）, 这是一个**应当被看见**的空
        assertEquals("", row.getTypeNamespace());
        assertEquals(0, row.getMajorVersion());

        // ── 但数据一个都没丢: 全部业务字段都在那一列 JSON 里
        Map<String, Object> payload = row.getPayloadJson();
        assertFalse(payload.isEmpty(), "载荷不能是空的 —— 那才是'丢数据'");
        assertEquals("phone:main", payload.get("sourceObjectId"));
        assertEquals("fixture", ((Map<?, ?>) payload.get("typeId")).get("namespace"));
        assertEquals(0.4, ((Number) payload.get("magnitude")).doubleValue(), 1e-9);
        assertEquals("fixture.retired-plugin.v1",
                ((Map<?, ?>) payload.get("typeId")).get("namespace") + "."
                        + ((Map<?, ?>) payload.get("typeId")).get("name") + ".v"
                        + ((Map<?, ?>) payload.get("typeId")).get("majorVersion"),
                "连它自己的 typeId 都完整地留在 JSON 里");

        // ── 而"为什么读不回来"的唯一线索也在里面
        assertEquals(RetiredPluginEvent.class.getName(),
                payload.get(PolymorphicSerializer.UNTYPED_MARKER),
                "写入时打的 _untyped 标记必须留下 —— 剥掉它等于把诊断信息从数据里删掉");

        // ── 读取路径: 它进 unreadable, 不进 values, 也不崩
        WorldEventStore.ReadResult<WorldEvent> back = store.pending(WORLD);
        assertFalse(back.complete());
        assertTrue(back.values().isEmpty(), "读不出来 ≠ 读成了别的东西");
        assertEquals(1, back.unreadable().size());
        assertEquals(1, back.total(), "它仍然被算进'这次恢复碰了几行'");
        assertSame(row, back.unreadable().get(0), "保留整行, 而不是只留一个 id");
        assertNull(row.getPublishedAt(),
                "它没有被标记为已投递 —— 插件装回来之后它还在待投递里, 不会被跳过");
    }

    @Test
    @DisplayName("未注册的行不拖累同一批里已注册的行 —— 一个坏插件不该让整次回放失败")
    void 未注册的行不拖累同批次的其他行() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        store.append(WORLD, new RetiredPluginEvent(EventTypeId.of("fixture", "retired-plugin"),
                T0, "phone:main", 0.4, Duration.ofMinutes(30)));
        store.append(WORLD, due("pi-1", T0.plusSeconds(10)));
        store.append(WORLD, new RetiredPluginEvent(EventTypeId.of("fixture", "retired-plugin"),
                T0.plusSeconds(20), "phone:main", 0.2, Duration.ofMinutes(30)));
        store.append(WORLD, due("pi-2", T0.plusSeconds(30)));

        WorldEventStore.ReadResult<WorldEvent> back = store.pending(WORLD);
        assertEquals(2, back.values().size(), "两条好的读回来了");
        assertEquals(2, back.unreadable().size(), "两条坏的被单独列出");
        assertEquals(4, back.total());
        assertEquals("pi-1", ((PlanEvents.ItemDue) back.values().get(0)).itemId(),
                "顺序没被打乱 —— 好的仍然是好的, 按发生时刻");
        assertEquals("pi-2", ((PlanEvents.ItemDue) back.values().get(1)).itemId());
    }

    @Test
    @DisplayName("人工把 _type 回填之后, 那一行就活过来了 —— 这就是'没丢'的可操作含义")
    void 人工回填_type之后那一行能读回来() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore writer = new WorldEventStore(repo, PersistenceFixtures.codec());

        RetiredPluginEvent event = new RetiredPluginEvent(EventTypeId.of("fixture", "retired-plugin"),
                T0, "phone:main", 0.4, Duration.ofMinutes(30));
        WorldEventRecord row = writer.append(WORLD, event);

        // 一个"把插件装回来"的读侧: 类回来了, 但那一行仍然没有类型列与 _type。
        // 它必须先被人工回填 —— 而"能回填"这件事的全部前提是数据还在
        DomainTypeRegistry readerRegistry = PersistenceFixtures.registry();
        readerRegistry.register(RetiredPluginEvent.class);
        WorldEventStore reader = new WorldEventStore(repo,
                new DomainPayloadCodec(readerRegistry, new PolymorphicSerializer(readerRegistry)));

        assertFalse(reader.pending(WORLD).complete(), "只把类装回来还不够: 名字仍然缺着");

        Map<String, Object> patched = new LinkedHashMap<>(row.getPayloadJson());
        patched.put(PolymorphicSerializer.TYPE_FIELD, "fixture.retired-plugin.v1");
        row.setPayloadJson(patched);

        WorldEventStore.ReadResult<WorldEvent> back = reader.pending(WORLD);
        assertTrue(back.complete());
        assertEquals(1, back.values().size());
        assertEquals(event, back.values().get(0),
                "回填之后读回来的, 与当初写下去的是同一个对象 —— 一个字节都没丢");
    }

    // ═══════════════════════════ ③ 类别判定 ═══════════════════════════

    @Test
    @DisplayName("类别判定是逐段相等, 不是包含 —— 'SENSORY_DROPPED' 不是 'SENSORY'")
    void 类别判定必须逐段相等() {
        assertTrue(WorldEventStore.hasCategory("SENSORY", CoreEventCatalog.Category.SENSORY));
        assertTrue(WorldEventStore.hasCategory("STATE_EFFECT,SENSORY,SCHEDULED",
                CoreEventCatalog.Category.SENSORY));
        assertTrue(WorldEventStore.hasCategory("STATE_EFFECT, SENSORY",
                CoreEventCatalog.Category.SENSORY), "逗号后的空格要被容忍");

        assertFalse(WorldEventStore.hasCategory("SENSORY_DROPPED", CoreEventCatalog.Category.SENSORY),
                "contains 会把它当成 SENSORY —— 而那种误判的方向是**多投**刺激");
        assertFalse(WorldEventStore.hasCategory("STATE_EFFECT", CoreEventCatalog.Category.SENSORY));
        assertFalse(WorldEventStore.hasCategory(null, CoreEventCatalog.Category.SENSORY),
                "本层落地之前的历史行没有这一列, 而那不是故障");
        assertFalse(WorldEventStore.hasCategory("", CoreEventCatalog.Category.SENSORY));
    }

    @Test
    @DisplayName("三类都不实现的事件得到空串而不是 null —— '它不属于任何一类'是一个事实")
    void 没有类别的事件得到空串() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());

        WorldEventRecord row = store.append(WORLD, new PlainEvent(T0));
        assertEquals("", row.getCategory(), "空串 = '它确实不属于任何一类'");
        assertFalse(WorldEventStore.hasCategory(row.getCategory(),
                CoreEventCatalog.Category.SENSORY));
        assertEquals("", WorldEventStore.categoryOf(new PlainEvent(T0)));
        assertNotNull(row.getCategory(),
                "刻意不是 null: null 会与'这一列没写'混淆, 而两者在读的时候要做不同的事");
    }

    // ═══════════════════════════ ④ 投递标记 ═══════════════════════════

    @Test
    @DisplayName("已投递的不会被重复标记 —— 第一次投递的时刻是数据, 不是'最后一次被触碰的时刻'")
    void 已投递的不会被重复标记() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());
        WorldEventRecord row = store.append(WORLD, due("pi-1", T0));

        assertEquals(1, store.markPublished(List.of(row.getId()), T0.plusSeconds(5)));
        assertEquals(T0.plusSeconds(5), row.getPublishedAt());

        assertEquals(0, store.markPublished(List.of(row.getId()), T0.plusSeconds(99)),
                "第二次返回 0 —— '这次恢复到底补投了几条'因此成了一个可核对的数字");
        assertEquals(T0.plusSeconds(5), row.getPublishedAt(),
                "覆盖它会让她'隔了多久才注意到那条消息'永远等于上次重启的时间");
    }

    @Test
    @DisplayName("请求标记的行数多于找到的行数时不崩 —— 但少掉的那几条要说出来")
    void 标记不存在的行时不崩() {
        WorldEventRecordRepository repo = FakeRepositories.worldEvents();
        WorldEventStore store = new WorldEventStore(repo, PersistenceFixtures.codec());
        WorldEventRecord row = store.append(WORLD, due("pi-1", T0));

        assertEquals(1, store.markPublished(List.of(row.getId(), "evt-does-not-exist"),
                T0.plusSeconds(5)));
        assertEquals(0, store.markPublished(List.of(), T0), "空列表是合法的, 返回 0");
        assertEquals(0, store.markPublished(null, T0), "null 也是 —— 但绝不读时钟");
    }

    // ═══════════════════════════ 夹具 ═══════════════════════════

    private static PlanEvents.ItemDue due(String itemId, Instant at) {
        return new PlanEvents.ItemDue(PlanEvents.ITEM_DUE, at, "phone:main",
                itemId, "fixture.test-intent", "写作业", at, at.plus(Duration.ofMinutes(45)), 70, 0L);
    }

    /**
     * 一个三方事件: <b>有 {@code @DomainType}, 但忘了注册</b>。
     *
     * <p>刻意用"标了注解却没注册"这个形状, 而不是"根本没有注解": 后者是个纯粹的
     * 配置错误（代码作者不知道有这件事）, 而前者是<b>真实世界里的主要成因</b> ——
     * 装配时漏了一行 {@code register(...)}、或者一次 classpath 扫描范围写窄了。
     * 而它在数据上的表现与后者完全相同: 类型列是空的, 载荷里只有
     * {@code _untyped}。这正是"注册表而不是注解才是权威"这句话的测试形式。
     */
    @DomainType(value = "fixture.retired-plugin", version = 1,
            description = "测试用的三方事件")
    record RetiredPluginEvent(EventTypeId typeId, Instant occurredAt, String sourceObjectId,
                              double magnitude, Duration duration) implements WorldEvent {

        @Override
        public String toString() {
            return "RetiredPluginEvent[" + typeId + "]";
        }
    }

    /** 只实现 {@code WorldEvent}、不属于任何一类的事件。 */
    record PlainEvent(Instant occurredAt) implements WorldEvent {

        @Override
        public EventTypeId typeId() {
            return EventTypeId.of("fixture", "plain");
        }

        @Override
        public String sourceObjectId() {
            return null;
        }
    }
}
