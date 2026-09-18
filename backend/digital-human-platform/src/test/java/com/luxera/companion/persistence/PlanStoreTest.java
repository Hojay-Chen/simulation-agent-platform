package com.luxera.companion.persistence;

import com.luxera.companion.human.life.plan.PlanConstraint;
import com.luxera.companion.human.life.plan.PlanItem;
import com.luxera.companion.human.life.plan.PlanItemId;
import com.luxera.companion.human.life.plan.PlanLifecycle;
import com.luxera.companion.human.life.plan.PlanMutation;
import com.luxera.companion.human.life.plan.PlanOrigin;
import com.luxera.companion.human.life.plan.PlanPriority;
import com.luxera.companion.human.life.plan.PlanRevision;
import com.luxera.companion.human.life.plan.TimeWindow;
import com.luxera.companion.persistence.entity.PlanConstraintRecord;
import com.luxera.companion.persistence.entity.PlanItemRecord;
import com.luxera.companion.persistence.entity.PlanRevisionRecord;
import com.luxera.companion.persistence.repository.PlanConstraintRecordRepository;
import com.luxera.companion.persistence.repository.PlanItemRecordRepository;
import com.luxera.companion.persistence.repository.PlanRevisionRecordRepository;
import com.luxera.companion.persistence.store.PlanConstraintCodec;
import com.luxera.companion.persistence.store.PlanItemCodec;
import com.luxera.companion.persistence.store.PlanMutationCodec;
import com.luxera.companion.persistence.store.PlanStore;
import com.luxera.companion.registry.DomainTypeRegistry;
import com.luxera.companion.registry.PolymorphicSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §7.2 + §3.5.3 —— <b>计划版本链</b>（{@code plan_revision} / {@code plan_item} /
 * {@code plan_constraint}）的往返测试。
 *
 * <h2>本文件要钉住的那一件事: 三张表描述的是同一个东西</h2>
 * 一个计划项在库里出现在<b>两个地方</b>:
 * <pre>
 *   plan_item 一行                            ← 供 dueAt / 冲突检测那些"要真列与真索引"的查询
 *   plan_revision.mutations_json 里一个对象   ← 因为 Insert / Replace 各自嵌着整整一项
 * </pre>
 * 而一条约束也出现在两个地方（{@code plan_constraint} 一行, 以及嵌入形态里一个对象）。
 * 于是"这一版计划长什么样"会取决于你是用哪条路读的 —— 除非两条路写的是
 * <b>同样的信息</b>。{@code PlanStore} 的类注释把这条一致性要求写成了对本文件的承诺
 * （"三种写法描述同一件事, 而它们的一致性由 {@code PlanStoreTest} 的往返用例保证"）。
 * 下面的用例就是兑现那句话。
 *
 * <h2>为什么断言是"逐字段 / 逐项相等", 而不是一次 {@code equals}</h2>
 * {@code PlanRevision} 是 {@code final class}（它有派生索引 {@code timelineIndex}
 * 与 {@code byId}, 所以不能是 record）, 它<b>没有</b>结构化的 {@code equals}。
 * 而 {@code PlanItem} 与六种 {@code PlanMutation} 都是 record —— 它们有。
 * 于是本文件的写法是: 值对象用 {@code equals}（那是一次真的结构化比对）,
 * 版本本身用逐字段断言（版本号、前驱、理由、时刻, 加上项集合与改动集合的
 * {@code equals}）。
 *
 * <h2>本文件暴露的两个真实缺陷, 一个是本层的、一个在 {@code PlanStore}</h2>
 * <ol>
 *   <li><b>同一个 {@code PlanItemId} 会出现在多版里。</b> {@code PlanRevision.next}
 *       把上一版<b>没被碰过的项原样带进新版</b>, 而 {@code Move} 是"改窗口"不是"换一项"
 *       —— 于是 {@code plan_item} 的主键不能是 {@code itemId}。见
 *       {@link #同一项在两版里各有一行()};</li>
 *   <li><b>{@code PlanStore.repairOrSkip} 曾经"谎报修复"</b>: 它的补写分支只记一条
 *       WARN 就返回, 一个字节都没有写, 而且判据是"行数 &gt; 0"而不是"行数等于项数"
 *       —— 于是"写到一半崩了"的版本会永远停在一半, 而日志说它会补上。
 *       见 {@link #写到一半崩了的那一版再写一次会被补全()}（这条用例的第一版跑出的
 *       就是那个缺陷本身）。</li>
 * </ol>
 */
class PlanStoreTest {

    private static final String HUMAN = "human.test-01";
    private static final Instant T0 = Instant.parse("2026-09-19T09:00:00Z");

    // ═══════════════════════════ ① 往返 ═══════════════════════════

    @Test
    @DisplayName("第一版落库再读回来: 版本号 / 前驱 / 理由 / 时刻 / 每一项 / 约束一个不差")
    void 第一版往返之后每个字段都相等() {
        Bench bench = Bench.create();

        PlanItem study = item("pi-a", "写作业", T0, 45)
                .withPriority(PlanPriority.IMPORTANT)
                .withConstraints(List.of(PersistenceFixtures.FixtureConstraint
                        .of("no-phone", "写作业时不看手机")));
        PlanItem sleep = item("pi-b", "睡觉", T0.plus(Duration.ofHours(12)), 40);
        PlanItem lab = item("pi-c", "去实验室", T0.plus(Duration.ofHours(1)), 60)
                .withFixed(true)
                .withOrigin(PlanOrigin.system())
                .withDependencies(List.of(PlanItemId.of("pi-a")))
                .withNote("带上门禁卡");

        PlanConstraint global = PersistenceFixtures.FixtureConstraint
                .violated("no-allnighter", "不许熬夜");

        PlanRevision rev1 = new PlanRevision(1, null, "开学第一天的安排", T0,
                List.of(study, sleep, lab), List.of(global), List.of());
        bench.appendRevision(HUMAN, rev1);

        PlanRevision back = bench.store.revision("rev-1").orElseThrow();

        assertEquals(1, back.revisionNumber());
        assertEquals("rev-1", back.revisionId());
        assertTrue(back.previousRevisionId().isEmpty(), "第一版没有前驱");
        assertEquals("开学第一天的安排", back.reason());
        assertEquals(T0, back.createdAt());

        // ── 项: PlanItem 是 record, 所以这里是一次真的结构化比对。
        // 顺序也被一起断言了 —— PlanRevision 按 start 排序（同刻按 id 定序）
        assertEquals(List.of(study, lab, sleep), back.items(),
                "项集合与顺序都必须一致 —— 顺序变了会让'她今天的计划表'每次打印都不一样");

        // ── 全局约束（plan_item_id 为 NULL 的那一行）
        assertEquals(List.of(global), back.constraints());
        assertEquals(3, back.size(), "size() 数的是项, 不含约束");

        // ── 两条读路径（按 id 与按"最新"）给出同一个东西
        assertEquals(back.items(), bench.store.latest(HUMAN).orElseThrow().items());

        // ── 往返之后约束仍然判得动 —— 一个只把 id 保住、字段丢了的实现过不了这一条
        PlanConstraint backGlobal = back.constraints().get(0);
        assertFalse(backGlobal.evaluate(study, null).satisfied(),
                "夹具约束的判定只取决于它自己的字段, 所以它可以不读 context");
        assertEquals("不许熬夜", backGlobal.describe());
    }

    @Test
    @DisplayName("两版串成链: 第二版记得自己是从哪儿来的, 而第一版一个字节都没被改")
    void 版本链往返之后仍然连得上() {
        Bench bench = Bench.create();
        Chain chain = bench.writeTwoRevisions();

        List<PlanRevisionRecord> rows = bench.store.chain(HUMAN);
        assertEquals(2, rows.size());
        assertEquals("rev-1", rows.get(0).getRevisionId());
        assertEquals("rev-2", rows.get(1).getRevisionId(), "链是正序的 —— 从早到晚");
        assertEquals("rev-1", rows.get(1).getPreviousRevisionId());
        assertNull(rows.get(0).getPreviousRevisionId());

        PlanRevision second = bench.store.latest(HUMAN).orElseThrow();
        assertEquals(2, second.revisionNumber());
        assertEquals("rev-1", second.previousRevisionId().orElseThrow());
        assertEquals("有点冷, 先加件衣服", second.reason());

        // ── 历史不可变: 第二版写进去之后, 第一版读出来仍然是当初那一版
        PlanRevision first = bench.store.revision("rev-1").orElseThrow();
        assertEquals(chain.rev1().items(), first.items(),
                "老版本的行一个新字节都不该被碰 —— '她 12:15 时以为自己几点写作业'"
                        + "这个问题的答案就靠这条");
        assertNotEquals(chain.rev1().items(), second.items());
    }

    @Test
    @DisplayName("同一个 item id 出现在两版里 —— 复合主键就是为这件事存在的")
    void 同一项在两版里各有一行() {
        Bench bench = Bench.create();
        Chain chain = bench.writeTwoRevisions();

        // pi-a 在这一版里被移动了, 它的 id 没有变（Move 是"改窗口", 不是"换一项"）
        List<PlanItemRecord> rows = bench.items.findByItemIdOrderByRevisionIdAsc("pi-a");
        assertEquals(2, rows.size(), "同一项在两版里各有一行 —— 这就是复合主键的理由");
        assertEquals("rev-1#pi-a", rows.get(0).getId());
        assertEquals("rev-2#pi-a", rows.get(1).getId());

        // ── 两版各自读出来的 pi-a 是不同的窗口 —— 而两行都还在
        PlanItem inFirst = bench.store.revision("rev-1").orElseThrow()
                .find(PlanItemId.of("pi-a")).orElseThrow();
        PlanItem inSecond = bench.store.revision("rev-2").orElseThrow()
                .find(PlanItemId.of("pi-a")).orElseThrow();
        assertEquals(T0, inFirst.window().start());
        assertEquals(chain.movedStart(), inSecond.window().start());

        // ── 同一项上的约束也各归各版（约束的行键同样带版本号）
        List<PlanConstraintRecord> onItemInFirst =
                bench.constraints.findByRevisionIdAndPlanItemId("rev-1", "pi-a");
        List<PlanConstraintRecord> onItemInSecond =
                bench.constraints.findByRevisionIdAndPlanItemId("rev-2", "pi-a");
        assertEquals(1, onItemInFirst.size());
        assertEquals(1, onItemInSecond.size());
        assertNotEquals(onItemInFirst.get(0).getId(), onItemInSecond.get(0).getId(),
                "只按 constraint id 做行键的话, 第二版会覆盖第一版那一行");
    }

    // ═══════════════════════════ ② 约束的两种归属 ═══════════════════════════

    @Test
    @DisplayName("全局约束与项约束落在同一张表, 靠 plan_item_id 是否为 NULL 区分")
    void 全局约束与项约束靠空列区分() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        // 第一版的全局约束是那一条, 项 pi-a 上挂的是另一条
        List<PlanConstraintRecord> rev1Global =
                bench.constraints.findByRevisionIdAndPlanItemIdIsNull("rev-1");
        assertEquals(1, rev1Global.size());
        assertEquals("no-allnighter", rev1Global.get(0).getConstraintId());
        assertTrue(rev1Global.get(0).revisionWide());

        List<PlanConstraintRecord> onItem =
                bench.constraints.findByRevisionIdAndPlanItemId("rev-1", "pi-a");
        assertEquals(1, onItem.size());
        assertEquals("no-phone", onItem.get(0).getConstraintId());
        assertFalse(onItem.get(0).revisionWide());

        // ── 读回来的归属是对的: 全局的在 revision 上, 项自己的在 item 上
        PlanRevision rev1 = bench.store.revision("rev-1").orElseThrow();
        assertEquals(List.of("no-allnighter"),
                rev1.constraints().stream().map(c -> c.id().value()).toList());
        assertEquals(List.of("no-phone"),
                rev1.find(PlanItemId.of("pi-a")).orElseThrow().constraints()
                        .stream().map(c -> c.id().value()).toList());
        assertTrue(rev1.find(PlanItemId.of("pi-b")).orElseThrow().constraints().isEmpty(),
                "没挂约束的那一项读出来是空列表, 而不是别人那一项上的约束");

        // ── 第二版继承了那条全局约束（PlanRevision.next 原样传递）,
        // 而它是**另写的一行** —— 行键里带着版本
        List<PlanConstraintRecord> rev2Global =
                bench.constraints.findByRevisionIdAndPlanItemIdIsNull("rev-2");
        assertEquals(1, rev2Global.size());
        assertEquals("no-allnighter", rev2Global.get(0).getConstraintId());
        assertNotEquals(rev1Global.get(0).getId(), rev2Global.get(0).getId(),
                "同一条约束在两版里是两行 —— 共用一个行键的话第二版会覆盖第一版");

        PlanRevision rev2 = bench.store.revision("rev-2").orElseThrow();
        assertEquals(1, rev2.constraints().size(),
                "PlanRevision.constraints() 只列这一版的**全局**约束 —— 项自己挂的那一条"
                        + "在 item 上（见下一句）。库里两种约束在同一张表, 但读回来是两个地方:"
                        + "合成一个列表会让'这一版整体受什么管'与'这一项受什么管'再也分不开");
        assertEquals(List.of("no-phone"),
                rev2.find(PlanItemId.of("pi-a")).orElseThrow().constraints()
                        .stream().map(c -> c.id().value()).toList(),
                "pi-a 被 Move 之后仍然带着它自己的那条约束");
    }

    @Test
    @DisplayName("约束读不回来时抛异常 —— 一个'少了几条约束'的 agent 看起来完全正常")
    void 约束读不回来时不降级() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        Bench blind = bench.withCodec(
                PersistenceFixtures.codec(PersistenceFixtures.emptyRegistry()));
        assertThrows(PolymorphicSerializer.UnknownDomainTypeException.class,
                () -> blind.store.revision("rev-1"),
                "读不出来时不能降级成'这一版没有约束': 她会在'以为有约束、实际上没有'"
                        + "的状态下被排出去, 于是去做一件本该被挡住的事 —— "
                        + "而那表现为'她偶尔做出不该做的事', 没有任何线索指向这里");
    }

    // ═══════════════════════════ ③ 六种改动 ═══════════════════════════

    @Test
    @DisplayName("六种改动全部往返成功, 而且顺序原样保留")
    void 六种改动全部往返成功() {
        Bench bench = Bench.create();
        Chain chain = bench.writeTwoRevisions();

        PlanRevision back = bench.store.latest(HUMAN).orElseThrow();
        assertEquals(chain.mutations(), back.mutations(),
                "六种都是 record（值对象）, 所以这是一次结构化比对");

        // 六种都在场 —— 少了一种就说明某个分支的编解码没有被这次往返覆盖
        assertInstanceOf(PlanMutation.Insert.class, back.mutations().get(0));
        assertInstanceOf(PlanMutation.Remove.class, back.mutations().get(1));
        assertInstanceOf(PlanMutation.Move.class, back.mutations().get(2));
        assertInstanceOf(PlanMutation.Resize.class, back.mutations().get(3));
        assertInstanceOf(PlanMutation.Replace.class, back.mutations().get(4));
        assertInstanceOf(PlanMutation.KeepActive.class, back.mutations().get(5));

        // ── 两个派生量在往返之后仍然算得对（它们读的就是 mutations）
        assertEquals(1, back.keepActiveCount(), "'被打扰但选择忽略'的次数");
        assertEquals(List.of("pi-gone", "pi-old"),
                back.abandonedItems().stream().map(PlanItemId::value).toList(),
                "被删除与被替换掉的都要算进来 —— 它是'她今天放弃了哪几件事'的答案");

        // ── Insert 里嵌着的那一整项也要逐字段回来（嵌入形态的 PlanItem）
        PlanMutation.Insert insert = (PlanMutation.Insert) back.mutations().get(0);
        assertEquals(chain.inserted(), insert.item(),
                "嵌入形态与实体形态承载同样的信息 —— 这正是本文件存在的理由");
    }

    @Test
    @DisplayName("mutations_json 那一列包了一层 {\"mutations\": [...]} —— 裸数组不是个 Map")
    void 改动列表包了一层键() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        Map<String, Object> column = bench.revisions
                .findById("rev-2").orElseThrow().getMutationsJson();
        assertEquals(Set.of("mutations"), column.keySet());
        assertInstanceOf(List.class, column.get("mutations"),
                "StringMapConverter 读的是 Map<String,Object>, 所以数组必须包一层");
        List<?> body = (List<?>) column.get("mutations");
        assertEquals(6, body.size());
        assertInstanceOf(Map.class, body.get(0));
        assertEquals("INSERT", ((Map<?, ?>) body.get(0)).get("kind"),
                "kind 是线格式的一部分 —— 改了它就读不懂旧数据");
    }

    @Test
    @DisplayName("包一层键存在、值读不懂时必须抛 —— 那不是'没有改动', 而是'改动读不出来'")
    void 改动读不懂时不当作没有改动() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        PlanRevisionRecord row = bench.revisions.findById("rev-2").orElseThrow();
        row.setMutationsJson(new LinkedHashMap<>(Map.of("mutations", "不是数组")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> bench.store.revision("rev-2"));
        assertTrue(ex.getMessage().contains("不是'没有改动'"), ex.getMessage());
    }

    @Test
    @DisplayName("列是 null / 空数组时读成空列表 —— 第一版本来就是'没记任何改动'")
    void 空改动列读成空列表() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        PlanRevisionRecord row = bench.revisions.findById("rev-2").orElseThrow();
        row.setMutationsJson(null);
        assertTrue(bench.store.revision("rev-2").orElseThrow().mutations().isEmpty());

        row.setMutationsJson(new LinkedHashMap<>(Map.of("mutations", List.of())));
        assertTrue(bench.store.revision("rev-2").orElseThrow().mutations().isEmpty());
    }

    // ═══════════════════════════ ④ 幂等与补写 ═══════════════════════════

    @Test
    @DisplayName("同一版写两次: 第二次什么都不做, 而且不会多出重复的行")
    void 重复保存是安全的() {
        Bench bench = Bench.create();
        PlanRevision rev1 = bench.rev1();

        PlanRevisionRecord first = bench.appendRevision(HUMAN, rev1);
        assertEquals("rev-1", first.getRevisionId());
        long itemsAfterFirst = bench.items.countByRevisionId("rev-1");
        assertEquals(3, itemsAfterFirst);

        PlanRevisionRecord second = bench.appendRevision(HUMAN, bench.rev1());
        assertNull(second, "返回 null = 这一版已经完整写过, 这次保存是一次无害的重放");
        assertEquals(itemsAfterFirst, bench.items.countByRevisionId("rev-1"), "行数不变");
        assertEquals(2, bench.constraints.findByRevisionId("rev-1").size(),
                "约束行数也不变 —— 全局一条 + pi-a 自己一条");
    }

    /**
     * <b>回归: 这里曾经是一个"谎报修复"的分支。</b>
     *
     * <h2>旧实现做了什么</h2>
     * <pre>
     *   long written = items.countByRevisionId(revisionId);
     *   if (written &gt; 0) { return null; }              // 判据是"大于零"
     *   log.warn("本次调用会把缺失的项补上");
     *   return existing;                                // ← 什么也没写
     * </pre>
     * 两个问题叠在一起, 而且方向一致:
     * <ol>
     *   <li>"大于零"把"写到一半"（{@code saveAll} 途中失败）当成"写完了"。
     *       它与类注释里那句"检查它的项是否写齐了"不符 —— 判据是更宽的那一个;</li>
     *   <li>补写分支只记日志。于是那个版本<b>永远</b>停在写了一半的样子,
     *       而每次重启都会再打印一遍"本次调用会把缺失的项补上"——
     *       一句会让人以为问题已经自愈的日志, 比没有日志更坏。</li>
     * </ol>
     *
     * <h2>为什么这条用例必须断言"库里真的有那些行"</h2>
     * 因为那个缺陷的<b>返回值是对的</b>（返回 non-null 的那一行, 语义上等于"我处理了一版"）,
     * 而日志也是对的措辞。只有"再读一遍库"才能看见什么也没发生 ——
     * 而"什么也没发生"的表现是: 她的一版计划里少了两项, 她<b>看起来完全正常</b>,
     * 只是偶尔忘了自己下午要去实验室。
     *
     * <h2>两种"写到一半"都测</h2>
     * 版本行写完了、项与约束都没写（上述旧实现的场景）;
     * 以及项写齐了、约束一条都没写（两次数据库往返之间的另一个窗口）。
     */
    @Test
    @DisplayName("写到一半崩了的那一版, 再写一次会被补全（而不是只记一条日志）")
    void 写到一半崩了的那一版再写一次会被补全() {
        // ① 只落下了版本行
        Bench halfRow = Bench.create();
        PlanRevision rev1 = halfRow.rev1();
        halfRow.saveVersionRow(rev1);
        assertEquals(0, halfRow.items.countByRevisionId("rev-1"));
        assertEquals(0, halfRow.constraints.findByRevisionId("rev-1").size());

        PlanRevisionRecord repaired = halfRow.appendRevision(HUMAN, halfRow.rev1());
        assertSame(halfRow.revisions.findById("rev-1").orElseThrow(), repaired,
                "补的是同一行, 不是新写一行");
        assertEquals(3, halfRow.items.countByRevisionId("rev-1"),
                "旧实现在这里会停在 0 —— 而它的日志说'会把缺失的项补上'");
        assertEquals(2, halfRow.constraints.findByRevisionId("rev-1").size(),
                "约束也要一起补: 只有项没有约束的计划班底是不完整的");
        assertEquals(rev1.items(), halfRow.store.revision("rev-1").orElseThrow().items());

        // ② 项写齐了, 约束一条都没写
        Bench halfConstraints = Bench.create();
        PlanRevision other = halfConstraints.rev1();
        halfConstraints.saveVersionRow(other);
        halfConstraints.saveItemRows(other);
        assertEquals(0, halfConstraints.constraints.findByRevisionId("rev-1").size());

        halfConstraints.appendRevision(HUMAN, halfConstraints.rev1());
        PlanRevision back = halfConstraints.store.revision("rev-1").orElseThrow();
        assertEquals(1, back.constraints().size(), "全局那条被补上了");
        assertEquals(List.of("no-phone"),
                back.find(PlanItemId.of("pi-a")).orElseThrow().constraints()
                        .stream().map(c -> c.id().value()).toList(),
                "项自己那条也被补上了");
    }

    @Test
    @DisplayName("判据是行数而不是内容: 行数对了就不再碰这一行 —— 这是一个刻意的取舍")
    void 已经写齐的版本不会被再写一遍() {
        Bench bench = Bench.create();
        bench.appendRevision(HUMAN, bench.rev1());

        // 把那一列改成一个哨兵值（模拟"内容坏了但行数是对的"）——
        // 判据只看行数, 所以这次调用不该碰库里任何一行
        PlanRevisionRecord row = bench.revisions.findById("rev-1").orElseThrow();
        Map<String, Object> sentinel = new LinkedHashMap<>(Map.of("mutations", List.of("哨兵")));
        row.setMutationsJson(sentinel);

        assertNull(bench.appendRevision(HUMAN, bench.rev1()));
        assertSame(sentinel, row.getMutationsJson(),
                "刻意不看内容是否一致: 一次'内容不同但数量相同'的补写在正常流程里"
                        + "不可能发生（同一个版本号只会被产生一次）, 而为此写一段逐字段比对"
                        + "的代码, 需要它的时候恰恰是最不需要它的时候");
        assertEquals(3, bench.items.countByRevisionId("rev-1"));
        assertEquals(2, bench.constraints.findByRevisionId("rev-1").size());
    }

    // ═══════════════════════════ ⑤ 读路径的边角 ═══════════════════════════

    @Test
    @DisplayName("库里一条版本都没有时 latest 是 Optional.empty, 而不是一个空壳计划")
    void 没有任何版本时取不到东西() {
        Bench bench = Bench.create();
        assertTrue(bench.store.latest(HUMAN).isEmpty());
        assertTrue(bench.store.revision("rev-99").isEmpty());
        assertTrue(bench.store.chain(HUMAN).isEmpty());

        bench.writeTwoRevisions();
        assertTrue(bench.store.latest("human.other").isEmpty(),
                "本层不做'当前 agent'这种隐式假设 —— human_id 必须显式给");
    }

    @Test
    @DisplayName("取最新的一版是版本号最大的那一版, 不是最近写进去的那一版")
    void 取最新的一版是版本号最大的那一版() {
        Bench bench = Bench.create();
        Chain chain = bench.buildTwoRevisions();

        // 反序写入: 先 rev-2, 后 rev-1 —— 于是"最近写进去的"是第一版
        bench.appendRevision(HUMAN, chain.rev2());
        bench.appendRevision(HUMAN, chain.rev1());

        assertEquals(2, bench.store.latest(HUMAN).orElseThrow().revisionNumber(),
                "恢复结果不能随写入顺序变 —— 否则两次重启会得到不同的计划表");
        assertEquals(chain.rev2().reason(), bench.store.latest(HUMAN).orElseThrow().reason());
    }

    @Test
    @DisplayName("诊断行说得清这是第几版、承接谁、为什么")
    void 诊断行说清了这一版是什么() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        String described = PlanStore.describe(bench.revisions.findById("rev-2").orElseThrow());
        assertTrue(described.contains("rev-2"), described);
        assertTrue(described.contains("第 2 版"), described);
        assertTrue(described.contains("rev-1"), described);
        assertTrue(described.contains("有点冷, 先加件衣服"), described);
    }

    @Test
    @DisplayName("项的行键是 '版本#项' —— 拼法只有一处, 写与读不会拼岔")
    void 项的行键是复合的() {
        Bench bench = Bench.create();
        bench.writeTwoRevisions();

        PlanItemRecord row = bench.items.findByRevisionIdOrderByStartAtAsc("rev-1").get(0);
        assertEquals(PlanItemRecord.rowKeyOf("rev-1", row.getItemId()), row.getId());
        assertEquals("rev-1#" + row.getItemId(), row.getId());
        assertEquals(HUMAN, row.getHumanId(),
                "human_id 是冗余列 —— 它在 plan_item 上, 但不是它主键的一部分");
    }

    // ═══════════════════════════ 夹具 ═══════════════════════════

    /**
     * 一次"两版链"的全部产物 —— 多个用例共用同一份输入形状。
     *
     * <p>为什么用一个封装而不是每个用例各写各的: 这条链的<b>每一版都必须包含
     * 同一个 item id</b>（那正是复合主键要解决的问题）, 而"某个用例不小心把第二版
     * 的 id 改了"会让那个用例悄悄不再测它以为在测的东西。
     */
    private record Chain(PlanRevision rev1, PlanRevision rev2, PlanItem inserted,
                         List<PlanMutation> mutations, Instant movedStart) {
    }

    /** 三张表 + 一个 store, 共用同一份替身。 */
    private record Bench(PlanRevisionRecordRepository revisions,
                         PlanItemRecordRepository items,
                         PlanConstraintRecordRepository constraints,
                         PlanStore store,
                         PlanItemCodec itemCodec) {

        static Bench create() {
            return create(codec());
        }

        static Bench create(DomainPayloadCodec codec) {
            PlanRevisionRecordRepository revisions = FakeRepositories.planRevisions();
            PlanItemRecordRepository items = FakeRepositories.planItems();
            PlanConstraintRecordRepository constraints = FakeRepositories.planConstraints();

            PlanConstraintCodec constraintCodec = new PlanConstraintCodec(codec);
            PlanItemCodec itemCodec = new PlanItemCodec(codec, constraintCodec);
            PlanStore store = new PlanStore(revisions, items, constraints,
                    itemCodec, constraintCodec, new PlanMutationCodec(itemCodec));
            return new Bench(revisions, items, constraints, store, itemCodec);
        }

        /** 换一个读侧编解码器, 但<b>共用同一个仓库</b> —— 差别只在"谁在注册表里"。 */
        Bench withCodec(DomainPayloadCodec other) {
            PlanConstraintCodec constraintCodec = new PlanConstraintCodec(other);
            PlanItemCodec otherItemCodec = new PlanItemCodec(other, constraintCodec);
            return new Bench(revisions, items, constraints,
                    new PlanStore(revisions, items, constraints,
                            otherItemCodec, constraintCodec, new PlanMutationCodec(otherItemCodec)),
                    otherItemCodec);
        }

        /** 第一版 —— 三项, 其中一项挂了它自己的约束。 */
        PlanRevision rev1() {
            PlanItem study = item("pi-a", "写作业", T0, 45)
                    .withPriority(PlanPriority.IMPORTANT)
                    .withConstraints(List.of(PersistenceFixtures.FixtureConstraint
                            .of("no-phone", "写作业时不看手机")));
            PlanItem sleep = item("pi-b", "睡觉", T0.plus(Duration.ofHours(12)), 40);
            PlanItem lab = item("pi-c", "去实验室", T0.plus(Duration.ofHours(1)), 60)
                    .withFixed(true)
                    .withOrigin(PlanOrigin.system())
                    .withDependencies(List.of(PlanItemId.of("pi-a")))
                    .withNote("带上门禁卡");
            return new PlanRevision(1, null, "开学第一天的安排", T0,
                    List.of(study, sleep, lab),
                    List.of(PersistenceFixtures.FixtureConstraint
                            .violated("no-allnighter", "不许熬夜")),
                    List.of());
        }

        /**
         * 造出两版但<b>不落库</b> —— 让"反序写入"那条用例能自己决定写入顺序。
         *
         * <p>第二版里有六种改动各一条, 这是刻意的: {@code PlanMutationCodec} 的六个分支
         * 里任何一个写错了, 都会让"存下去再读回来是同一个对象"在那一条上失败 ——
         * 而少了其中一条的表现是"那个分支从来没被跑过"。
         */
        Chain buildTwoRevisions() {
            PlanRevision first = rev1();

            Instant movedStart = T0.plus(Duration.ofMinutes(10));
            PlanItem movedStudy = first.find(PlanItemId.of("pi-a")).orElseThrow()
                    .withWindow(TimeWindow.startingAt(movedStart, Duration.ofMinutes(45)));
            PlanItem inserted = item("pi-d", "运动", T0.plus(Duration.ofHours(7)), 60)
                    .withCreatedInRevision(2);
            PlanItem replacement = item("pi-e", "散步", T0.plus(Duration.ofHours(7)), 30)
                    .withCreatedInRevision(2);

            List<PlanMutation> mutations = List.of(
                    new PlanMutation.Insert(inserted, "她说想立马执行"),
                    new PlanMutation.Remove(PlanItemId.of("pi-gone"), "今天不写了"),
                    new PlanMutation.Move(PlanItemId.of("pi-a"),
                            TimeWindow.startingAt(movedStart, Duration.ofMinutes(45)), "先加件衣服"),
                    new PlanMutation.Resize(PlanItemId.of("pi-c"), Duration.ofMinutes(50),
                            "实验比预计的短"),
                    new PlanMutation.Replace(PlanItemId.of("pi-old"), replacement,
                            "不写作业了, 去散步"),
                    new PlanMutation.KeepActive(PlanItemId.of("pi-a"), "被打扰但选择继续"));

            PlanRevision second = first.next(T0.plus(Duration.ofHours(1)), "有点冷, 先加件衣服",
                    List.of(movedStudy, inserted, first.find(PlanItemId.of("pi-c")).orElseThrow()),
                    mutations);
            return new Chain(first, second, inserted, mutations, movedStart);
        }

        /** 造两版并按版本号正序落库 —— 绝大多数用例要的是这个。 */
        Chain writeTwoRevisions() {
            Chain chain = buildTwoRevisions();
            appendRevision(HUMAN, chain.rev1());
            appendRevision(HUMAN, chain.rev2());
            return chain;
        }

        PlanRevisionRecord appendRevision(String humanId, PlanRevision revision) {
            return store.appendRevision(humanId, revision);
        }

        /** 手工制造"版本行写完了、其余都没写"的痕迹。 */
        void saveVersionRow(PlanRevision revision) {
            PlanRevisionRecord row = new PlanRevisionRecord();
            row.setRevisionId(revision.revisionId());
            row.setHumanId(HUMAN);
            row.setRevisionNumber(revision.revisionNumber());
            row.setPreviousRevisionId(revision.previousRevisionId().orElse(null));
            row.setReason(revision.reason());
            row.setCreatedSimAt(revision.createdAt());
            revisions.save(row);
        }

        /** 手工制造"项写齐了、约束都没写"的痕迹。 */
        void saveItemRows(PlanRevision revision) {
            for (PlanItem planItem : revision.items()) {
                items.save(itemCodec.toRecord(planItem, HUMAN, revision.revisionId()));
            }
        }
    }

    /**
     * 一项最小的计划 —— 必填的那几个字段给全, 其余走默认。
     *
     * <p>{@code intent} 用的是夹具意图, 于是这一项里的<b>意图</b>走的是多态那条路
     * （类型名走三个关系型列、内容走 JSON）—— 而 {@code PlanItemCodec} 的类注释里
     * "为什么不能把 {@code PlanItem} 直接交给 Jackson"的理由正是它。
     */
    private static PlanItem item(String id, String description, Instant start, long minutes) {
        return new PlanItem(
                PlanItemId.of(id),
                PersistenceFixtures.FixtureIntent.of("intent-" + id, description,
                        "life.activity.study", minutes),
                TimeWindow.startingAt(start, Duration.ofMinutes(minutes)),
                Duration.ofMinutes(minutes),
                List.of(), PlanPriority.DEFAULT, PlanLifecycle.PENDING, PlanOrigin.SELF,
                List.of(), false, 1, "");
    }

    /**
     * 一个认识夹具意图与夹具约束的编解码器。
     *
     * <p>真装配时的注册表会把 {@code human/life/plan} 下的内建类型一起注册;
     * 这里复现的是那个装配结果的最小切片 —— 少了 {@code FixtureConstraint},
     * 约束的类型三元组会是空的, 而 {@code PlanConstraintCodec.fromRecord} 读回时会抛。
     * 本仓目前<b>没有</b>任何 {@code PlanConstraint} 的实现类, 所以这一条夹具
     * 同时也在证明"一个三方约束只要标了 {@code @DomainType} 并被注册就能原样存取"。
     */
    private static DomainPayloadCodec codec() {
        DomainTypeRegistry registry = PersistenceFixtures.registry();
        registry.register(PersistenceFixtures.FixtureConstraint.class);
        return PersistenceFixtures.codec(registry);
    }
}
