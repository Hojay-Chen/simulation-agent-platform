package com.luxera.companion.persistence;

import com.luxera.companion.boundary.event.ContinuousEffectLedger;
import com.luxera.companion.boundary.event.StateEffectEvent;
import com.luxera.companion.persistence.entity.ContinuousEffectRecord;
import com.luxera.companion.persistence.repository.ContinuousEffectRecordRepository;
import com.luxera.companion.persistence.store.EffectLedgerStore;
import com.luxera.companion.persistence.store.OpaqueEffect;
import com.luxera.companion.registry.PolymorphicSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.2 —— {@code continuous_effect}: <b>{@code ContinuousEffectLedger}
 * 那份内存态怎么落库、怎么恢复、怎么重置</b>。
 *
 * <h2>为什么这个文件里最重要的断言是"那个数没变"</h2>
 * 账本的全部意义是"她身上现在挂着什么"。它落库失败的表现不是一条报错, 而是
 * <pre>
 *   重启前: 外面 3 度, 她穿着羽绒服   → warmth = +0.83
 *   重启后: 账本为空                 → warmth = 0.00 → 她"不冷了"
 * </pre>
 * 而她身上那件羽绒服并没有脱。所以每个用例都必须回答同一个问题:
 * <b>恢复之后的读数与恢复之前的读数是不是同一个数</b>。
 *
 * <h2>恢复是"重放", 不是"把对象读回来"</h2>
 * {@code ContinuousEffectLedger} 只暴露 {@code book(event, bookedAt)} 一个写入口,
 * 于是恢复只能是"按 {@code sequence} 正序逐条重放"。这条约束带来一个必须被证明
 * 的副产物: <b>重放之后的 {@code entryId} 与原库里的行一一对应</b>
 * （{@code book} 自己发号 {@code eff-N}, 而我们按 sequence 正序喂）。
 * 它不成立时, 下一次 {@code snapshot} 会走"插入新行"分支 —— 也就是账目在库里
 * 悄悄地翻倍。{@link #重放之后的账目_id_与原账本一一对应()} 就是钉这件事的。
 *
 * <h2>三条降级路径也在这里被证明</h2>
 * <ol>
 *   <li>类型认不出来（三方插件被卸载）→ {@link OpaqueEffect} 重建,
 *       <b>加法仍然正确</b>;</li>
 *   <li>到期时刻在往返里变了 → 同样用 {@link OpaqueEffect} 兜底,
 *       并且用<b>列上那个值</b>而不是从 JSON 推出来的值;</li>
 *   <li>库里的 {@code superseded} 列说失效、而重放推不出那个失效 → 用"已失效"的替身
 *       占住它自己的号（到期时刻压到不晚于"现在"）。</li>
 * </ol>
 * 三者的取舍方向是一致的: 宁可解释不清, 不能算错。
 *
 * <h2>"失效"有两个来源, 而它们经不经得起重启是两回事</h2>
 * <pre>
 *   来源一  cancel（显式撤销）与 book（同 key 挤掉旧的）/ settle（过期）
 *           → 全是账本自己走的路, 库里留下了那一行, 重放<b>自然重现</b>
 *   来源二  reset —— 绕过账本直接把整列写成 true, 内存里没有对应的一步
 *           → 重放<b>推不出来</b>, 只能照着那一列办（第三条降级路径）
 * </pre>
 * 两条各有一条用例: {@link #显式撤销在重放里自然重现()} 与
 * {@link #绕开账本写下的失效也经得起重启()}。分开测是有意的 ——
 * 把它们混在一起, 就分不清"重放不行"是账本的语义问题, 还是这一层读写的问题。
 */
class EffectLedgerStoreTest {

    private static final String HUMAN = "human.test-01";
    private static final Instant T0 = Instant.parse("2026-09-19T12:00:00Z");
    private static final Instant LATER = T0.plusSeconds(600);

    // ═══════════════════════════ ① 往返 ═══════════════════════════

    @Test
    @DisplayName("落库再恢复之后净效应还是同一个数 —— 这条不成立, 重启就会改变她的体温")
    void 落库再恢复之后净效应不变() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        // 两个通道: 胸口暖了与脚还是冷能同时成立 —— 净效应按通道各算各的
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling", "window",
                Duration.ofHours(2)), T0.plusSeconds(60));

        double before = ledger.netEffect("body.warmth", LATER);
        double coolBefore = ledger.netEffect("body.cooling", LATER);
        assertEquals(2, store.snapshot(ledger, HUMAN), "两条都是新插入的");

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(before, restored.netEffect("body.warmth", LATER), 1e-9,
                "重启前后的同一个读数必须相等 —— 这不是一个可以四舍五入的差异");
        assertEquals(coolBefore, restored.netEffect("body.cooling", LATER), 1e-9);
    }

    @Test
    @DisplayName("重放之后的账目 id 与原账本一一对应 —— 不对应的话下次落库会让账目翻倍")
    void 重放之后的账目_id_与原账本一一对应() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        String first = ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth",
                "coat", T0.plusSeconds(7200)), T0);
        String second = ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling",
                "window", Duration.ofHours(2)), T0.plusSeconds(60));
        assertEquals(List.of("eff-1", "eff-2"), List.of(first, second), "账目 id 是顺序号");

        store.snapshot(ledger, HUMAN);
        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(List.of("eff-1", "eff-2"),
                restored.history().stream().map(ContinuousEffectLedger.EffectRecord::entryId).toList(),
                "顺序号必须一模一样 —— 否则再落一次库会按新号插入重复行");

        // 这条断言把上面那句话变成可核对的: 再落一次, 一行都不该变
        assertEquals(0, store.snapshot(restored, HUMAN),
                "重放之后立刻再落一次库, 应当没有任何变化（0 新插入 + 0 新失效）");
        assertEquals(2, repo.findByHumanIdOrderBySequenceAsc(HUMAN).size(),
                "库里还是那两行 —— 账目没有翻倍");
    }

    @Test
    @DisplayName("同一 key 上的先后两条: 前一条的失效状态也一起被恢复")
    void 替换关系在往返之后仍然成立() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        // 先穿 T恤, 再换羽绒服 —— 同一个 (通道, key), 后者挤掉前者而不是叠加
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.20, "body.warmth", "thermal", null), T0);
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "thermal", null),
                T0.plusSeconds(300));

        assertTrue(ledger.history().get(0).superseded(), "前置条件: 第一条在内存里已经失效");
        store.snapshot(ledger, HUMAN);
        assertEquals(1, repo.findByHumanIdOrderBySequenceAsc(HUMAN).stream()
                .filter(ContinuousEffectRecord::isSuperseded).count(),
                "那一列被写下来了: '她 12:05 换了件厚的'与'她从没穿过 T恤'是两件事");

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(0.85, restored.netEffect("body.warmth", LATER), 1e-9,
                "不是 1.05 —— 那不叫穿衣服, 那叫堆衣服");
        assertTrue(restored.history().get(0).superseded(),
                "重放按 sequence 正序, 所以第二次 book 自然把第一条挤掉了");
        assertInstanceOf(PersistenceFixtures.FixtureWarmth.class, restored.history().get(0).event(),
                "被挤掉的那条仍然读得回它自己 —— '已失效替身'只留给重放推不出来的那些"
                        + "（reset 那种）, 不能变成所有失效账目的样子");
    }

    @Test
    @DisplayName("显式撤销经得起重启 —— 撤销是一条真账目, 不是一次标志位翻转")
    void 显式撤销在重放里自然重现() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        assertEquals(0.85, ledger.netEffect("body.warmth", LATER), 1e-9);

        // 她脱掉了羽绒服 —— 撤销是"入账一条 magnitude=0 的同 key 影响", 不是删掉上一条
        assertNotNull(ledger.cancel("body.warmth", "coat", T0.plusSeconds(300)));
        assertEquals(0.0, ledger.netEffect("body.warmth", LATER), 1e-9);
        assertEquals(2, ledger.history().size(), "撤销自己也是一条账目");

        store.snapshot(ledger, HUMAN);
        List<ContinuousEffectRecord> rows = repo.findByHumanIdOrderBySequenceAsc(HUMAN);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isSuperseded(), "被撤销的那条在库里标了失效");
        assertFalse(rows.get(1).isSuperseded(), "撤销那条自己生效 —— 它 magnitude=0, 不改变净效应");
        assertEquals(0.0, rows.get(1).getMagnitude(), 1e-9);

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(0.0, restored.netEffect("body.warmth", LATER), 1e-9,
                "撤销在重放里自然重现 —— 它是库里的一行, 不是内存里的一个标志位");
        assertEquals(2, restored.history().size(),
                "撤销那条也在账上 —— 少了它, '她为什么开始觉得冷'又没答案了");
        assertTrue(restored.history().get(0).superseded(),
                "重放时后一条（同 key）把前一条挤掉了 —— 这一条判据不依赖 reset 那种 out-of-band 标记");
        assertEquals(0.0, restored.history().get(1).event().magnitude(), 1e-9,
                "撤销那条的 magnitude 从列上读回来还是 0");
        assertEquals(List.of("eff-1", "eff-2"),
                restored.history().stream()
                        .map(ContinuousEffectLedger.EffectRecord::entryId).toList(),
                "两条都占住自己的号");
        assertEquals(0, store.snapshot(restored, HUMAN),
                "重放完再落一次库: 0 新插入 + 0 条新失效");
    }

    /**
     * 撤销那一行必须**带着自己的类型**落库、原样读回来。
     *
     * <h2>它和上面那条用例差在哪 —— 一个只有数字的断言证明不了这件事</h2>
     * {@link #显式撤销在重放里自然重现()} 已经断言了"撤销之后净效应是 0"。
     * 而那个断言在<b>类型丢失的情况下同样成立</b>: {@code OpaqueEffect} 的四个数
     * (magnitude / channel / key / expiresAt)全部从<b>真列</b>上读, 加法因此是正确的。
     * 换句话说: <b>她会不会冷, 由列决定; 她为什么冷, 由类型决定</b>。
     * 上面那条测的是前者, 这一条测的是后者 —— 而"她为什么开始觉得冷"正是
     * {@code ContinuousEffectLedger} 存在的理由(见其类注释"撤销也是入账")。
     *
     * <h2>这个缺陷为什么能活到今天</h2>
     * 因为它的三个症状都很容易被解释成别的东西: 一条 WARN
     * (被当成"三方插件没注册"的常规噪音)、一份读得出来的数字(对的)、
     * 以及一个只在调试时才被问起的问题("她为什么冷")。它是<b>静默</b>的:
     * 没有任何断言、任何异常、任何指标会因为类型丢失而变红。
     * 所以它需要一条专门为它写的用例 —— 就像这一条。
     */
    @Test
    @DisplayName("撤销那一行带着 system.effect-cancelled 落库, 而不是退化成 _untyped")
    void 撤销的类型经得起重启() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        ledger.cancel("body.warmth", "coat", T0.plusSeconds(300));
        store.snapshot(ledger, HUMAN);

        // 写侧: 三列元数据必须被真的填上。没有这三列, 读侧连"该 new 哪个类"都无从问起
        ContinuousEffectRecord cancellationRow = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(1);
        assertEquals("system", cancellationRow.getEffectNamespace(),
                "撤销的命名空间不该是 _untyped —— 它是平台内建事件, 有确定的域");
        assertEquals("effect-cancelled", cancellationRow.getEffectName());
        assertEquals(1, cancellationRow.getEffectVersion());

        // 读侧: 它必须变回一个**认得出类型**的事件, 而不是替身
        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        StateEffectEvent replayed = restored.history().get(1).event();

        assertFalse(replayed instanceof OpaqueEffect,
                "撤销那一行退化成替身了 —— 加法仍然对, 但\"她 12:30 之后为什么开始觉得冷\""
                        + "这个问题在账本里就再也没有答案了");
        assertEquals("system.effect-cancelled.v1", replayed.typeId().toString(),
                "类型名是它被读回来的唯一线索, 也是 CoreEventCatalog 里登记的那一个");
        assertTrue(replayed.describe().contains(PersistenceFixtures.WARMTH_TYPE),
                "被撤销的是哪一类影响, 必须能从这一行本身读出来 —— 且**只**依赖这一行: "
                        + "即便 fixture.warmth 那个类型被卸载了, 这句话依然成立。实际: "
                        + replayed.describe());
    }

    @Test
    @DisplayName("来源事件 id 只在首次插入时写入 —— 增量写不碰它")
    void 来源事件_id_不会被第二次落库抹掉() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        String entryId = ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth",
                "coat", T0.plusSeconds(7200)), T0);
        store.snapshot(ledger, HUMAN, Map.of(entryId, "evt-42"));

        ContinuousEffectRecord row = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(0);
        assertEquals("evt-42", row.getSourceEventId());
        assertEquals(1L, row.getSequence(), "sequence 是从 entryId 推出来的, 为的是'按入账顺序读回来'能用索引");

        // 再落一次（这次不带来源表）—— "这条暖意是哪件事造成的"不能因此消失
        assertEquals(0, store.snapshot(ledger, HUMAN));
        assertEquals("evt-42", row.getSourceEventId(), "第二次落库一个字节都没碰它");
    }

    // ═══════════════════════════ ② 类型认不出来 ═══════════════════════════

    @Test
    @DisplayName("认不出类型的账目用替身重建, 加法仍然正确 —— 她的保暖值不该少算一件衣服")
    void 认不出类型的账目用替身重建且加法仍然正确() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        // 这条的类型没有任何实现类（三方插件被卸载 / 忘了注册）
        ledger.book(PersistenceFixtures.UnregisteredEffect.of(0.40, "body.warmth", "hat", null),
                T0.plusSeconds(10));
        assertEquals(1.25, ledger.netEffect("body.warmth", LATER), 1e-9);

        store.snapshot(ledger, HUMAN);
        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(1.25, restored.netEffect("body.warmth", LATER), 1e-9,
                "跳过读不回来的行会让它变成 0.85 —— 而她头上那顶帽子并没有摘");

        List<ContinuousEffectLedger.EffectRecord> history = restored.history();
        assertInstanceOf(PersistenceFixtures.FixtureWarmth.class, history.get(0).event(),
                "读得回来的那条仍然是它自己");
        OpaqueEffect opaque = assertInstanceOf(OpaqueEffect.class, history.get(1).event(),
                "读不回来的那条变成替身, 但仍然在账上");
        assertEquals("类型没有任何实现类", opaque.unreadableReason());
        assertEquals("hat", opaque.cancellationKey(), "替换判定也仍然正确 —— key 在列上");
        assertEquals(0.40, opaque.magnitude(), 1e-9, "加法正确 —— magnitude 在列上");
        assertEquals(OpaqueEffect.TYPE, opaque.typeId(),
                "合成类型名落在 system 命名空间: 伪造一个三方的名字会让'这是谁的'有一个错误答案");
    }

    @Test
    @DisplayName("替身的 JSON 列一个字节都没被改 —— 那是把原类型找回来的唯一线索")
    void 替身不会覆盖原来那一列_JSON() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.UnregisteredEffect.of(0.40, "body.warmth", "hat", null), T0);
        store.snapshot(ledger, HUMAN);

        ContinuousEffectRecord row = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(0);
        Map<String, Object> originalJson = Map.copyOf(row.getEffectJson());
        assertNotNull(originalJson.get(PolymorphicSerializer.UNTYPED_MARKER),
                "写入时打的 _untyped 是诊断这条数据的关键线索");

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        // 把恢复出来的账本再落一次库 —— 走的是"替身不会被写回"的守卫
        assertEquals(0, store.snapshot(restored, HUMAN), "一行都没变");
        assertEquals(originalJson, row.getEffectJson(),
                "那一列原封不动 —— 插件装回来之后这些账目还有救");
    }

    @Test
    @DisplayName("凭空造一个替身并试图写入库会被拒绝 —— 那会用'认不出来'覆盖'原本是什么'")
    void 替身不能作为写入的输入() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectRecord ghost = new ContinuousEffectRecord();
        ghost.setId("eff-1");
        ghost.setHumanId(HUMAN);
        ghost.setEffectChannel("body.warmth");
        ghost.setMagnitude(0.4);
        ghost.setEffectNamespace("");
        ghost.setEffectName("");
        ghost.setEffectJson(Map.of());
        ghost.setStartedAt(T0);

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(OpaqueEffect.of(ghost, "手工造的"), T0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> store.snapshot(ledger, HUMAN));
        assertTrue(ex.getMessage().contains("拒绝写入"), ex.getMessage());
    }

    // ═══════════════════════════ ③ 到期时刻的保真 ═══════════════════════════

    @Test
    @DisplayName("绝对到期的账目: 往返之后到期时刻是同一个值, 不需要替身")
    void 绝对到期的账目往返保真() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        Instant expiresAt = T0.plusSeconds(7200);
        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat", expiresAt), T0);
        store.snapshot(ledger, HUMAN);

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        StateEffectEvent event = restored.history().get(0).event();
        assertInstanceOf(PersistenceFixtures.FixtureWarmth.class, event,
                "到期时刻原样往返, 所以没有退化成替身");
        assertEquals(expiresAt, restored.history().get(0).expiresAt(),
                "到期时刻就是列上那个值");
        // 而"还没过期"这件事在恢复时就被 settle 过了
        assertEquals(0.85, restored.netEffect("body.warmth", LATER), 1e-9);
    }

    @Test
    @DisplayName("相对时长的账目: bookedAt + duration 这一支也算得对")
    void 相对时长的账目往返保真() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling", "window",
                Duration.ofHours(2)), T0);
        store.snapshot(ledger, HUMAN);

        ContinuousEffectRecord row = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(0);
        assertEquals(T0.plus(Duration.ofHours(2)), row.getExpiresAt(),
                "到期时刻被算出来之后写进了列 —— 这条列是账本唯一的'到期'来源");

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        assertInstanceOf(PersistenceFixtures.FixtureChill.class, restored.history().get(0).event(),
                "没有退化 —— 时长这一支也往返成功了");
        assertEquals(T0.plus(Duration.ofHours(2)), restored.history().get(0).expiresAt());
    }

    @Test
    @DisplayName("已经过期的账目: 恢复之后仍然不参与求和")
    void 过期的账目恢复之后仍然不计入() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling", "window",
                Duration.ofMinutes(5)), T0);
        store.snapshot(ledger, HUMAN);

        // LATER 是 T0 + 10 分钟, 那条 5 分钟的已经过去了
        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        assertEquals(0.0, restored.netEffect("body.cooling", LATER), 1e-9);
        assertEquals(0, restored.activeCount(LATER));
    }

    @Test
    @DisplayName("到期时刻在 JSON 往返里变了: 用列上那个值兜底, 而不是用推出来的那个")
    void 列上的到期时刻与载荷推出来的不一致时以列为准() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling", "window",
                Duration.ofHours(2)), T0);
        store.snapshot(ledger, HUMAN);

        // 人工把列改成一个与 startedAt + duration 对不上的值 —— 这模拟的是
        // "手工修过数据"或"另一个绕过本类的写入方"。它会让重放算出来的到期时刻
        // 与列上的值分叉, 而那正是 fidelityOf 要拦住的东西
        ContinuousEffectRecord row = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(0);
        Instant columnValue = T0.plus(Duration.ofHours(9));
        row.setExpiresAt(columnValue);

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        OpaqueEffect opaque = assertInstanceOf(OpaqueEffect.class, restored.history().get(0).event(),
                "分叉被拦住了 —— 它退回替身而不是让一个错的到期时刻生效");
        assertEquals("到期时刻在 JSON 往返里变了", opaque.unreadableReason());
        assertEquals(columnValue, restored.history().get(0).expiresAt(),
                "而且用的是列上那个值: 有一份原始数据时, 不要用能从别处推导出来的近似值代替它");
        assertEquals(-0.20, restored.netEffect("body.cooling", LATER), 1e-9,
                "加法不受影响 —— 退化的只是解释, 不是数");
    }

    // ═══════════════════════════ ④ 重置 ═══════════════════════════

    @Test
    @DisplayName("重置不是 DELETE: 账目还在库里, 只是被标记为失效")
    void 重置是标记失效而不是删除() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        ledger.book(PersistenceFixtures.FixtureChill.of(-0.20, "body.cooling", "window",
                Duration.ofHours(2)), T0.plusSeconds(60));
        store.snapshot(ledger, HUMAN);

        assertEquals(2, store.reset(HUMAN), "两条被标记失效");
        List<ContinuousEffectRecord> rows = repo.findByHumanIdOrderBySequenceAsc(HUMAN);
        assertEquals(2, rows.size(), "一行都没被删掉 —— DELETE 是一种抹掉, 不是一种表达");
        assertTrue(rows.stream().allMatch(ContinuousEffectRecord::isSuperseded));

        assertEquals(0, store.reset(HUMAN), "再重置一次是幂等的 —— 0 条新变化");

        // 调用方那一半的契约也在类注释里写着: 调完 reset 之后要自己 ledger.clear()。
        // 本层不持有那个对象 —— 让持久化层去改领域对象的状态会把"谁是真相的来源"弄反
        ledger.clear();
        assertEquals(0.0, ledger.netEffect("body.warmth", LATER), 1e-9);
        assertEquals(0.0, ledger.netEffect("body.cooling", LATER), 1e-9);
    }

    /**
     * <b>绕过账本写下的失效（{@code reset}）也经得起重启。</b>
     *
     * <p>"她身上的暖意归零之后重启一次, 暖意全回来了"是本类最想消灭的那类症状 ——
     * 状态凭空改变, 且没有任何事件解释这个改变。会造出这个症状的是
     * {@code EffectLedgerStore.reset}: 它<b>不</b>走账本, 直接把整列写成
     * {@code superseded = true}（一次 out-of-band 的标记, 内存里没有对应的一步）。
     *
     * <h2>为什么它不能靠"跳过那一行"来修</h2>
     * {@code entryId = "eff-" + 序号} 是 {@code book} 自己发的号, 跳过一行会让后面
     * 所有行的 id 前移一位, 而错位的 id 会让下一次 {@code snapshot} 按错误的身份
     * <b>插入重复行</b>（{@link #重放之后的账目_id_与原账本一一对应()} 钉的正是这件事）。
     * 而 DELETE 更不行: 见 {@link #重置是标记失效而不是删除()}。
     * 所以这一行必须<b>占住它自己的号, 只是不许生效</b> ——
     * {@code restore} 的修法就是这句话, 而"不生效"在账本里只能靠到期时刻表达。
     *
     * <p>这条用例是<b>正向</b>断言（钉住具体读数）, 不是"没抛异常":
     * 归零、条数、id、失效标记、替身的理由与四个数、以及"再落一次库一行都不变"。
     */
    @Test
    @DisplayName("绕过账本写下的失效（reset）也经得起重启 —— 恢复照着库里那一列办")
    void 绕开账本写下的失效也经得起重启() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        store.snapshot(ledger, HUMAN);
        assertEquals(1, store.reset(HUMAN), "reset 把这一行直接标成失效");
        ContinuousEffectRecord row = repo.findByHumanIdOrderBySequenceAsc(HUMAN).get(0);
        assertTrue(row.isSuperseded(), "前置条件: 库里那一行确实已经被标记失效");
        Map<String, Object> jsonBefore = Map.copyOf(row.getEffectJson());

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(0.0, restored.netEffect("body.warmth", LATER), 1e-9,
                "归零之后重启一次, 暖意不该自己回来");
        assertEquals(1, restored.history().size(),
                "它仍然在历史里 —— 跳过不入账会让后面所有账目的号前移");
        assertEquals("eff-1", restored.history().get(0).entryId(),
                "而且占住的是它自己那个号: 一一对应是'下一次落库不会翻倍'的前提");
        assertTrue(restored.history().get(0).superseded(),
                "内存里的失效状态与库里那一列一致 —— 这就是那个缺口的形状的反面");

        OpaqueEffect invalid = assertInstanceOf(OpaqueEffect.class, restored.history().get(0).event(),
                "重放推不出它是怎么失效的, 于是它用'已失效'的替身占住这个号");
        assertTrue(invalid.unreadableReason().contains("失效"), invalid.unreadableReason());
        assertEquals(0.85, invalid.magnitude(), 1e-9,
                "四个数仍然来自列（替身不改变它是什么, 只表达它不生效）");
        assertEquals(T0, restored.history().get(0).expiresAt(),
                "库里没记'什么时候失效的', 所以不编造一个新时刻 —— 用行自己的入账时刻");

        assertEquals(0, store.snapshot(restored, HUMAN),
                "重放之后的账本与库完全一致: 0 新插入（号没错位）+ 0 条新失效");
        assertEquals(1, repo.findByHumanIdOrderBySequenceAsc(HUMAN).size(), "一行都没被删掉");
        assertEquals(jsonBefore, row.getEffectJson(),
                "那一列 JSON 一个字节都没动 —— 它仍然是解释这条账目的唯一线索");
        assertTrue(row.isSuperseded(), "单向纪律: 库里那一列没有被改回去");
    }

    @Test
    @DisplayName("入账时刻排在未来、又在库里被标记失效的那条: 恢复之后仍然不计入")
    void 未来的入账时刻不会让失效的账目复活() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        // 一条预排到未来的持续影响（入账时刻晚于恢复用的"现在"）
        Instant future = T0.plusSeconds(3600);
        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat", null), future);
        store.snapshot(ledger, HUMAN);
        store.reset(HUMAN);

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);

        assertEquals(0.0, restored.netEffect("body.warmth", LATER), 1e-9,
                "替身的到期时刻不能直接用入账时刻 —— 一个未来的到期时刻会让它照样计进净效应");
        assertEquals(LATER, restored.history().get(0).expiresAt(),
                "晚于'现在'的入账时刻被压到'现在': 这一支不是洁癖, 它是'算错'与'解释不清'的分界");
    }

    @Test
    @DisplayName("诊断输出把每一条账目都说清楚 —— 它是'她身上挂着什么'的答案")
    void 诊断输出列出每一条账目() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        ContinuousEffectLedger ledger = ContinuousEffectLedger.empty();
        ledger.book(PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                T0.plusSeconds(7200)), T0);
        store.snapshot(ledger, HUMAN);

        String described = store.describe(HUMAN);
        assertTrue(described.contains(HUMAN), described);
        assertTrue(described.contains("body.warmth"), described);
    }

    @Test
    @DisplayName("外部工具写入的行（id 不是 eff-N 形状）一样能恢复 —— 但它的顺序号拿到 0 而不是崩")
    void 外部工具写入的行也能恢复() {
        ContinuousEffectRecordRepository repo = FakeRepositories.continuousEffects();
        EffectLedgerStore store = new EffectLedgerStore(repo, PersistenceFixtures.codec());

        // 一个绕过本类的写入方: 它自己编了 id, 于是 sequence 推不出来。
        // 这是真实存在的行（导入脚本、人工回填), 而"排不上序"不该让恢复失败
        ContinuousEffectRecord odd = new ContinuousEffectRecord();
        odd.setId("imported-0007");
        odd.setHumanId(HUMAN);
        odd.setEffectChannel("body.warmth");
        odd.setMagnitude(0.85);
        odd.setEffectNamespace("fixture");
        odd.setEffectName("warmth");
        odd.setEffectVersion(1);
        odd.setEffectJson(PersistenceFixtures.codec().serializer().toMap(
                PersistenceFixtures.FixtureWarmth.of(0.85, "body.warmth", "coat",
                        T0.plusSeconds(7200))));
        odd.setStartedAt(T0);
        // 这四个数都在列上 —— 这正是"恢复不需要反序列化成功"那句话的依据
        odd.setExpiresAt(T0.plusSeconds(7200));
        odd.setCancellationKey("coat");
        odd.setSequence(0L);
        repo.save(odd);

        ContinuousEffectLedger restored = store.restore(HUMAN, LATER);
        assertEquals(0.85, restored.netEffect("body.warmth", LATER), 1e-9,
                "它的四个数都在列上, 所以恢复得了");
        assertInstanceOf(PersistenceFixtures.FixtureWarmth.class, restored.history().get(0).event(),
                "类型三元组也在列上, 所以不需要替身");
        assertNotNull(restored.history().get(0).entryId());
    }
}
