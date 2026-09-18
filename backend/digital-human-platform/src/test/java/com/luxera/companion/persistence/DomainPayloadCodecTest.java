package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.3 —— <b>"三个类型列 + 一列 JSON"那一层</b>自己的往返与边界。
 *
 * <h2>为什么这一层值得一个独立的测试文件</h2>
 * 其它文件的往返测试都是"通过某一个 store 走完整条路"。那能证明
 * "活动/事件/计划存下去读回来一样", 但证明不了这一层的三条规则本身 ——
 * 因为那些 store 各自只走到其中一条分支:
 * <table border="1">
 *   <tr><th>规则</th><th>哪条路径会踩到它</th></tr>
 *   <tr><td>三个列<b>全空</b>时回退到载荷里的 {@code _type}</td>
 *       <td>只有"未注册类型"与"外部工具写入的行"会踩到 ——
 *           而正常写入的行永远有三个列</td></tr>
 *   <tr><td>两份冲突时<b>列赢</b></td>
 *       <td>正常流程里两份来自同一次计算, 所以<b>永远</b>不冲突 ——
 *           它只在'有人手工改过其中一份'时才发生</td></tr>
 *   <tr><td>三个列<b>填了一半</b>必须抛</td>
 *       <td>写入路径上不可能产生半填的行</td></tr>
 * </table>
 * 也就是说, 这三条规则全都是"只在不正常的数据上才生效"的规则 ——
 * 而<b>没有被测过的规则等于不存在</b>: 它们会在最需要的那一天（有人在抢修数据）
 * 以一个没人预期的行为出现。
 *
 * <h2>本文件里有一条回归</h2>
 * {@link #版本列是装箱的_0_必须算空()} —— 一个"三个列全空"的行曾经被判成
 * "填了一半", 于是每一个未注册类型的对象都以"数据损坏"的名义抛异常。
 * 那不是一条罕见的路径: 未注册类型的行是本层的<b>常态</b>（它服务的正是
 * "三方插件没装"这个处境）。构造这个输入的方式必须<b>真的来自实体的列</b> ——
 * 所以这里传的是 {@code 0} 而不是 {@code null}, 见那条用例的说明。
 */
class DomainPayloadCodecTest {

    private static DomainPayloadCodec codec() {
        return PersistenceFixtures.codec();
    }

    // ═══════════════════════════ ① 写 ═══════════════════════════

    @Test
    @DisplayName("写: 一个对象摊成三元组 + 一列 JSON, 两份类型名来自同一次计算")
    void 写的时候三元组与载荷里的类型名一致() {
        Instant expiresAt = Instant.parse("2026-09-19T10:00:00Z");
        PersistenceFixtures.FixtureWarmth warmth =
                PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat", expiresAt);

        DomainPayloadCodec.PersistedForm form = codec().write(warmth);

        assertEquals("fixture", form.typeNamespace());
        assertEquals("warmth", form.typeName());
        assertEquals(1, form.majorVersion());
        assertEquals("fixture.warmth.v1", form.typeId());
        assertTrue(form.hasType());

        // ── 载荷里那一个 _type 是**冗余**的, 而它不是浪费 —— 见 DomainPayloadCodec 的类注释
        assertEquals(PersistenceFixtures.WARMTH_TYPE, form.payload().get(PolymorphicSerializer.TYPE_FIELD));
        assertEquals(0.85, form.payload().get("magnitude"),
                "业务字段全在 —— 少了它, 库里那一列就只是'一条有类型的空数据'");
        assertEquals("coat", form.payload().get("cancellationKey"));
        assertEquals(expiresAt.toString(), form.payload().get("expiresAt"),
                "时间是 ISO 字符串而不是 epoch 数字 —— 这一列是给人看的");

        // ── 分法只有一套: 三元组拼回去必须等于载荷里那个 _type
        assertEquals(form.payload().get(PolymorphicSerializer.TYPE_FIELD), form.typeId());
    }

    @Test
    @DisplayName("写: 类型没注册的对象照样落库, 三元组全空 + 载荷里留下 _untyped 与全部业务字段")
    void 未注册类型照样写得下去() {
        PersistenceFixtures.UnregisteredEffect effect = PersistenceFixtures.UnregisteredEffect
                .of(0.42, "body.chill", "coat", Instant.parse("2026-09-19T09:00:00Z"));

        DomainPayloadCodec.PersistedForm form = codec().write(effect);

        assertFalse(form.hasType(), "没有任何类声明过它 —— 三元组必须是空的");
        assertEquals(DomainPayloadCodec.PersistedForm.UNTYPED_NAMESPACE, form.typeNamespace());
        assertEquals(DomainPayloadCodec.PersistedForm.UNTYPED_NAME, form.typeName());
        assertEquals(DomainPayloadCodec.PersistedForm.UNTYPED_VERSION, form.majorVersion());
        assertEquals("", form.typeId());

        // ── 数据一个字节都没丢 —— 这是本层最重要的一条承诺
        assertNull(form.payload().get(PolymorphicSerializer.TYPE_FIELD));
        assertEquals(effect.getClass().getName(),
                form.payload().get(PolymorphicSerializer.UNTYPED_MARKER),
                "_untyped 是'它本该是什么'的唯一线索, 所以它必须留下");
        assertEquals(0.42, form.payload().get("magnitude"));
        assertEquals("body.chill", form.payload().get("effectChannel"));

        // ── 行确实写下去了, 只是没人认识它 —— tryRead 给空, 而这是**可预期**的
        //    （这一路正是"那个三方插件被卸载了"时回放历史事件的处境, 见 tryRead 的说明）
        assertTrue(codec().tryRead(form.typeNamespace(), form.typeName(), form.majorVersion(),
                        form.payload()).isEmpty(),
                "注册表里有 warmth/chill/test-intent, 但没有这个类型 —— 所以它读不回来");

        // ── 而真正要钉的是"数据没有静默消失": 抛出来的那句诊断必须点出写入时的类名。
        // 它是排查的唯一线索 —— 少了它, 这条数据在库里就是一坨没有名字的 JSON
        PolymorphicSerializer.UnknownDomainTypeException ex = assertThrows(
                PolymorphicSerializer.UnknownDomainTypeException.class,
                () -> codec().read(form.typeNamespace(), form.typeName(), form.majorVersion(),
                        form.payload(), StateEffectEvent.class));
        assertEquals(effect.getClass().getName(), ex.typeId());
        assertTrue(ex.getMessage().contains(effect.getClass().getName()), ex.getMessage());
        assertTrue(ex.getMessage().contains("补上 @DomainType"), ex.getMessage());
    }

    @Test
    @DisplayName("写: 载荷里那个 _type 不是合法的类型名时也不抛 —— 数据不丢, 只是没有类型可查")
    void 载荷里的类型名写坏了也不丢数据() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PolymorphicSerializer.TYPE_FIELD, "不是一个类型名");
        payload.put("magnitude", 0.5);

        DomainPayloadCodec.PersistedForm form = DomainPayloadCodec.PersistedForm.of(payload);

        assertFalse(form.hasType());
        assertEquals(0.5, form.payload().get("magnitude"), "整份载荷原样保留");
        assertEquals("不是一个类型名", form.payload().get(PolymorphicSerializer.TYPE_FIELD),
                "坏掉的那个 _type 也要留着 —— 它是排查的输入, 不是要清理的垃圾");
    }

    @Test
    @DisplayName("PersistedForm 是不可变的 —— 一个读出来的载体不该能被改回去")
    void 载体不可变() {
        DomainPayloadCodec.PersistedForm form =
                codec().write(PersistenceFixtures.FixtureWarmth.of(0.5, "c", "k", null));

        assertThrows(UnsupportedOperationException.class,
                () -> form.payload().put("magnitude", 99.0),
                "载荷是 unmodifiable 的: 一个可变的载体迟早会在某条路径上被就地改掉,"
                        + "而那次改动的表现是'库里某一列的值与业务对象不一致'");
        assertTrue(form.payloadOrEmpty().containsKey("magnitude"));
    }

    // ═══════════════════════════ ② 读: 列赢 ═══════════════════════════

    @Test
    @DisplayName("两份冲突时以列为准 —— 手工修过一列的人必须看到自己的修改生效")
    void 三列与载荷冲突时以列为准() {
        Instant expiresAt = Instant.parse("2026-09-19T10:00:00Z");
        PersistenceFixtures.FixtureChill chill = PersistenceFixtures.FixtureChill
                .of(0.3, "body.chill", "coat", Duration.ofHours(2));

        Map<String, Object> payload = new LinkedHashMap<>(codec().write(chill).payload());
        // 把载荷里那个 _type 改成**另一个已注册类型** —— 模拟"有人手工改了载荷、没改列"
        payload.put(PolymorphicSerializer.TYPE_FIELD, PersistenceFixtures.WARMTH_TYPE);

        Object back = codec().read("fixture", "chill", 1, payload, StateEffectEvent.class);

        assertInstanceOf(PersistenceFixtures.FixtureChill.class, back,
                "列说它是 chill, 载荷说它是 warmth —— 以列为准。"
                        + "反过来以载荷为准的话, 一次'有人手工修了三个列想把类型改对'的操作"
                        + "会静默无效: 他的修复看起来成功了, 行为没变");
        assertEquals(chill, back);

        // ── 反方向同样成立: 列说 warmth, 载荷说 chill → 拿到 warmth
        Map<String, Object> warmPayload = new LinkedHashMap<>(payload);
        warmPayload.put(PolymorphicSerializer.TYPE_FIELD, PersistenceFixtures.CHILL_TYPE);
        warmPayload.put("expiresAt", expiresAt.toString());
        Object warm = codec().read("fixture", "warmth", 1, warmPayload, StateEffectEvent.class);
        assertInstanceOf(PersistenceFixtures.FixtureWarmth.class, warm);
        assertEquals(expiresAt, ((PersistenceFixtures.FixtureWarmth) warm).expiresAt(),
                "字段是从载荷里读的 —— 只有类型名以列为准, 这正是那两份冗余的分工");
    }

    @Test
    @DisplayName("三个列全空时回退到载荷里的 _type —— 外部工具写入的行与历史行靠这一条活着")
    void 三列全空时回退到载荷里的类型名() {
        PersistenceFixtures.FixtureChill chill = PersistenceFixtures.FixtureChill
                .of(0.3, "body.chill", "coat", Duration.ofHours(2));
        Map<String, Object> payload = codec().write(chill).payload();

        // 外部工具（导入脚本、人工回填）手上只有一份 JSON, 而且它是零个关系型列
        Object back = codec().read(null, null, null, payload, StateEffectEvent.class);
        assertEquals(chill, back);

        // 空串与空白的写法同样算"空" —— 一个由 CSV 导入的行常常是空串而不是 NULL
        assertEquals(chill, codec().read("", "  ", null, payload, StateEffectEvent.class));
    }

    @Test
    @DisplayName("回归: 版本列是装箱的, 0 必须算空 —— 它曾经把每一条未注册的行判成'数据损坏'")
    void 版本列是装箱的_0_必须算空() {
        PersistenceFixtures.FixtureChill chill = PersistenceFixtures.FixtureChill
                .of(0.3, "body.chill", "coat", Duration.ofHours(2));
        Map<String, Object> payload = codec().write(chill).payload();

        // ── 这三个输入是等价的, 而它们来自不同的调用方:
        //    null      → 手上根本没有那一列（外部工具写的行）
        //    ""        → CSV 导入的空串
        //    0         → 实体上 int 类型的列读出来、装箱成 Integer 之后的样子
        // 0 那一条曾经抛 IllegalStateException（"三元组只填了一部分"）——
        // 因为当时的判据是 `majorVersion != null`, 而 `Integer(0) != null` 是 true。
        // 于是"未注册类型"这条**常态**路径全部以"数据损坏"的名义失败
        assertEquals(chill, codec().read(null, null, null, payload, StateEffectEvent.class));
        assertEquals(chill, codec().read(null, null, 0, payload, StateEffectEvent.class),
                "整数 0 是'这一列没有类型'的正常取值（PersistedForm.UNTYPED_VERSION）");
        assertEquals(chill, codec().read("", "", 0, payload, StateEffectEvent.class));

        // ── 而真正的半填仍然必须被抓住（见下一条用例）—— 这条区分是刻意的:
        //    "三个都空"是常态, "填了一半"是损坏
        DomainPayloadCodec.PersistedForm untyped =
                codec().write(PersistenceFixtures.UnregisteredEffect
                        .of(0.42, "body.chill", "coat", Instant.parse("2026-09-19T09:00:00Z")));
        assertEquals(0, untyped.majorVersion());
        assertEquals("", untyped.typeNamespace());
        assertFalse(untyped.hasType());
    }

    @Test
    @DisplayName("三个列填了一半必须抛 —— 那不是历史遗留, 是一处必须被看见的损坏")
    void 三列填了一半时抛异常() {
        Map<String, Object> payload =
                codec().write(PersistenceFixtures.FixtureWarmth.of(0.5, "c", "k", null)).payload();

        // 缺版本
        IllegalStateException a = assertThrows(IllegalStateException.class,
                () -> codec().read("fixture", "warmth", null, payload, StateEffectEvent.class));
        assertTrue(a.getMessage().contains("只填了一部分"), a.getMessage());
        assertTrue(a.getMessage().contains("version=null"), a.getMessage());

        // 缺命名空间
        assertThrows(IllegalStateException.class,
                () -> codec().read(null, "warmth", 1, payload, StateEffectEvent.class));

        // 有列但没有版本（0）—— 与"全空"只差一个非空的 namespace
        assertThrows(IllegalStateException.class,
                () -> codec().read("fixture", "warmth", 0, payload, StateEffectEvent.class),
                "namespace 非空而版本是 0 是**半填** —— 悄悄用载荷里的 _type 补上它,"
                        + "会让写入路径上那处 bug 永远不被发现, 直到某一天关系型列的查询"
                        + "与载荷的查询给出不同的答案");

        // 载荷为 null 是调用方的 bug, 不是本层能替它决定的事
        assertThrows(NullPointerException.class,
                () -> codec().read("fixture", "warmth", 1, null, StateEffectEvent.class));
    }

    @Test
    @DisplayName("期望的类型对不上时抛 —— 把'这一行其实是别的东西'挡在边界上")
    void 期望类型对不上时抛异常() {
        Map<String, Object> payload =
                codec().write(PersistenceFixtures.FixtureWarmth.of(0.5, "c", "k", null)).payload();

        // FixtureWarmth 是 StateEffectEvent 但不是 com.luxera...PlanIntent
        PolymorphicSerializer.UnknownDomainTypeException ex = assertThrows(
                PolymorphicSerializer.UnknownDomainTypeException.class,
                () -> codec().read("fixture", "warmth", 1, payload,
                        com.luxera.companion.human.life.plan.PlanIntent.class));
        assertTrue(ex.getMessage().contains("不是期望的"), ex.getMessage());
    }

    // ═══════════════════════════ ③ tryRead 的边界 ═══════════════════════════

    @Test
    @DisplayName("tryRead 只吞'类型没人认识'那一种失败; 载荷本身坏了必须往外抛")
    void tryRead只吞类型认不出来那一种() {
        DomainPayloadCodec codec = codec();

        // ── (a) 类型不认识 → 空 Optional（回放历史时的正常分支: 插件被卸载了）
        PersistenceFixtures.UnregisteredEffect retired = PersistenceFixtures.UnregisteredEffect
                .of(0.42, "body.chill", "coat", Instant.parse("2026-09-19T09:00:00Z"));
        DomainPayloadCodec.PersistedForm untyped = codec.write(retired);
        assertTrue(codec.tryRead(untyped.typeNamespace(), untyped.typeName(),
                untyped.majorVersion(), untyped.payload()).isEmpty(),
                "它是" + retired.getClass().getSimpleName() + "（没有 @DomainType）——"
                        + "读不回来是这个场景的**常态**, 不是故障");
        assertTrue(codec.tryRead("nobody", "here", 1,
                Map.of(PolymorphicSerializer.TYPE_FIELD, "nobody.here.v1")).isEmpty());

        // ── (b) 类型认识、但载荷坏得读不出来 → 必须抛。
        // 用一个已注册类型的载荷, 把它的一个字段改成解析不了的值
        Map<String, Object> broken = new LinkedHashMap<>(
                codec.write(PersistenceFixtures.FixtureIntent.studying("i-1")).payload());
        broken.put("expectedMinutes", "不是个数字");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> codec.tryRead("fixture", "test-intent", 1, broken));
        assertFalse(thrown instanceof PolymorphicSerializer.UnknownDomainTypeException,
                "载荷坏了与类型不认识是两种病: 前者是**这一条数据本身有问题**,"
                        + "静默跳过它会让'那个三方插件的载荷格式变了'这件事没有任何痕迹。"
                        + "实际抛出: " + thrown.getClass().getName());
    }

    @Test
    @DisplayName("tryRead 读得回来时给出的就是那个对象（而不是一个 Map）")
    void tryRead读得回来时给出对象() {
        PersistenceFixtures.FixtureWarmth warmth =
                PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat", null);
        DomainPayloadCodec.PersistedForm form = codec().write(warmth);

        Object back = codec().tryRead(form.typeNamespace(), form.typeName(),
                form.majorVersion(), form.payload()).orElseThrow();
        assertEquals(warmth, back);
    }

    @Test
    @DisplayName("装配用的那两个对象原样可取 —— 编解码器不偷偷换用一个自己的序列化器")
    void 装配进来的注册表与序列化器就是用的那两个() {
        DomainTypeRegistry registry = PersistenceFixtures.registry();
        PolymorphicSerializer serializer = new PolymorphicSerializer(registry);
        DomainPayloadCodec codec = new DomainPayloadCodec(registry, serializer);

        assertSame(registry, codec.registry());
        assertSame(serializer, codec.serializer(),
                "换 mapper 会改变库里 JSON 的写法（例如时间从 ISO 变成 epoch 数字）——"
                        + "本类如果偷偷用一个自己的默认 mapper, '写的时候用 A、读的时候用 B'"
                        + "就会变成一个只影响某几个字段的静默错位");

        assertThrows(NullPointerException.class, () -> new DomainPayloadCodec(null));
        assertThrows(NullPointerException.class,
                () -> new DomainPayloadCodec(registry, null));
    }
}
