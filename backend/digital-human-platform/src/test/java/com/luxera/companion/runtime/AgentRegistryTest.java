package com.luxera.companion.runtime;

import com.luxera.companion.human.HumanContext;
import com.luxera.companion.persistence.entity.AgentOwnershipRecord;
import com.luxera.companion.persistence.entity.ConversationAccountBindingRecord;
import com.luxera.companion.persistence.entity.HumanRecord;
import com.luxera.companion.persistence.repository.AgentOwnershipRecordRepository;
import com.luxera.companion.persistence.repository.ConversationAccountBindingRecordRepository;
import com.luxera.companion.persistence.repository.HumanRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 花名册 —— {@link AgentRegistry} / {@link AgentProfileProjector} / {@link AgentProfileView}。
 *
 * <h2>这一组测试里最要紧的是哪一个</h2>
 *
 * {@link 应当跑的那批用枚举判而不是用SQL判()}. 它是本文件存在的理由, 因为这里有一个
 * <b>两种写法都编译得过、都能过评审、而给出的答案相反</b>的分歧点:
 *
 * <pre>
 *   A. repository.findByLifecycleAndDeletedAtIsNullOrderByCreatedAtAsc("active")
 *   B. findAll() 之后用 AgentLifecycle.of(...) 判
 * </pre>
 *
 * <p>A 的每一处看起来都更好: 它走索引、它把过滤交给数据库、它的方法名就是一句人话。
 * 而它是<b>错的</b> —— 因为 §3.6.5 的读侧兜底是"只有字面写着 {@code paused} 才停",
 * 于是那一列上出现任何别的值（{@code "sleeping"}、拼错的 {@code "pausd"}）时,
 * A 把那一行<b>排除在外</b>（判成"不要跑"）, 而 {@code AgentLifecycle.of} 说 ACTIVE
 * （"照常跑"）。两个答案相反, 而症状是"她被静默地停了, 而库里没有任何一列写着 paused"。
 *
 * <p>所以那条测试里有一行 {@code lifecycle = "sleeping"} 的记录, 而它<b>必须</b>出现在
 * {@code runnable()} 里。这一条断言只有 B 那种实现能过 —— 也就是说它是一道真的守卫,
 * 不是对当前实现的复述。
 *
 * <h2>第二要紧的: 两个口径<b>不该</b>被对齐</h2>
 *
 * {@link 逐字统计与能不能跑是两个口径()} 钉住的是相反方向的一件事: {@code runnable()}
 * 用枚举判, 而 {@code countsByLiteralLifecycle()} 用字面值分组。把两者"统一"起来看起来
 * 是在消除不一致, 实际是抹掉了运维唯一能看见"有 agent 的运行档认不出来、而它们会一直跑"
 * 的那个信号。所以那条测试断言的是两者<b>故意不一致</b>。
 *
 * <h2>为什么是 Mockito 而不是真库</h2>
 * 与 {@code SimulationConfigurationTest} 那类 {@code @SpringBootTest} 不同, 这里没有一条
 * 断言与 Spring 或 SQL 有关 —— 全部是"给这几行, 该投影出什么"。用真库反而会把
 * "这张表上有没有唯一约束"之类的问题混进来, 而那是 {@code AgentOwnershipRecord}
 * 那一层的测试该管的事。
 *
 * <p>而且真库这条路在 {code companion_test} 上是走不通的: 那个库里有既有的
 * 数据, 一个"往 agent_ownership 插三行再数一数"的测试会在一个不属于它的库上
 * 做写操作。
 */
class AgentRegistryTest {

    private static final String HUM = "hum_a";
    private static final String OWNER = "user_1";
    private static final Instant T = Instant.parse("2026-03-01T12:00:00Z");

    private AgentOwnershipRecordRepository ownership;
    private HumanRecordRepository humans;
    private ConversationAccountBindingRecordRepository bindings;
    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        ownership = mock(AgentOwnershipRecordRepository.class);
        humans = mock(HumanRecordRepository.class);
        bindings = mock(ConversationAccountBindingRecordRepository.class);
        when(bindings.findByHumanIdOrderByBoundAtAsc(anyString())).thenReturn(List.of());
        when(humans.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        registry = new AgentRegistry(ownership, humans,
                new AgentProfileProjector(bindings, LiveHumanSource.NONE));
    }

    // ─────────────────── 一、谁应当跑: 口径只有一个 ───────────────────

    @Nested
    @DisplayName("应当跑的那批")
    class 应当跑的那批 {

        /**
         * 认不出来的运行档<b>要跑</b> —— 而这条断言排除了一整种"更自然"的实现。
         *
         * <p>见类注释。四个并列的用例刻意放在同一张表上, 因为真正会出错的不是
         * 单独任何一个, 而是"把它们分开判"这件事本身 —— 一个只写
         * {@code lifecycle="active"} 的测试, 在上面那两种实现下都是绿的。
         */
        @Test
        @DisplayName("认不出来的运行档要跑 —— 也就是不能用 SQL 过滤那一列")
        void 应当跑的那批用枚举判而不是用SQL判() {
            given(
                    row("hum_active", "active", null),
                    row("hum_sleeping", "sleeping", null),
                    row("hum_paused", "paused", null),
                    row("hum_deleted", "active", LocalDateTime.parse("2026-01-01T00:00:00")),
                    row("hum_blank", "", null));

            List<String> runnable = registry.runnable().stream()
                    .map(AgentProfileView::humanId).toList();

            assertEquals(List.of("hum_active", "hum_sleeping", "hum_blank"), runnable,
                    "应当跑的那批只由两件事决定: 她还在(deletedAt 为空), 且运行档不是 paused。"
                            + "认不出来的字(这里 \"sleeping\" 与空串)按 §3.6.5 一律当运行 —— "
                            + "而一个用 findByLifecycleAndDeletedAtIsNull...(\"active\") 的实现会把它们"
                            + "排除掉, 那正是这条测试要挡住的: 她被静默地停了, 而库里没有一列写着 paused");

            assertFalse(runnable.contains("hum_paused"), "写着一字不差的 paused 还不跑, 那这个开关就没用了");
            assertFalse(runnable.contains("hum_deleted"),
                    "被软删的不许被拉起来 —— 注意它的 lifecycle 恰恰是 active, "
                            + "所以这里证明的是'两个问题分开问'真的落到了代码里");
        }

        @Test
        @DisplayName("软删 + 暂停的四种组合, 只有一种跑")
        void 软删与暂停是两列两问() {
            given(
                    row("hum_a", "active", null),
                    row("hum_b", "paused", null),
                    row("hum_c", "active", LocalDateTime.parse("2026-01-01T00:00:00")),
                    row("hum_d", "paused", LocalDateTime.parse("2026-01-01T00:00:00")));

            Map<String, AgentProfileView> byId = registry.roster().stream()
                    .collect(Collectors.toMap(AgentProfileView::humanId, v -> v));

            assertTrue(byId.get("hum_a").alive());
            assertTrue(byId.get("hum_a").running());

            assertTrue(byId.get("hum_b").alive(), "暂停不等于被删 —— 这是 §3.6.6 的整段话");
            assertFalse(byId.get("hum_b").running());

            assertFalse(byId.get("hum_c").alive());
            assertFalse(byId.get("hum_c").running(), "软删的行 lifecycle 还写着 active, 而它不该跑 —— "
                    + "软删它的那条路径没有义务去改运行档, 那正是两列各答各的好处");
            assertFalse(byId.get("hum_c").paused(), "paused 说的是那一列, 而它确实是 active");

            assertFalse(byId.get("hum_d").alive());
            assertFalse(byId.get("hum_d").running());
        }

        @Test
        @DisplayName("逐字统计与'能不能跑'是两个口径, 不该被对齐")
        void 逐字统计与能不能跑是两个口径() {
            given(
                    row("hum_active", "active", null),
                    row("hum_sleeping", "sleeping", null),
                    row("hum_paused", "paused", null));

            Map<String, Long> counts = registry.countsByLiteralLifecycle();

            assertEquals(Map.of("active", 1L, "paused", 1L, "sleeping", 1L), counts,
                    "这一份答的是'库里那一列各写着什么', 所以它必须逐字分组 —— "
                            + "把认不出来的并进 active 会让'有 agent 的运行档我们看不懂'这件事从面板上消失");
            assertEquals(2, registry.runnable().size(),
                    "而这一份答的是'谁在跑' —— 两个数不一样是**对的**: "
                            + "sleeping 那一行在跑(runnable), 而它的字面值不是 active(counts)。"
                            + "运维正是靠这个差看出'有一个 agent 会一直跑, 而它的运行档没人认识'");
        }
    }

    // ─────────────────── 二、运行档的读侧兜底 ───────────────────

    @Nested
    @DisplayName("运行档")
    class 运行档 {

        /**
         * {@code of()} 的兜底方向 —— 这里再钉一次, 但钉的是<b>花名册有没有用它</b>。
         *
         * <p>{@code AgentLifecycle.of} 自己已经有一组测试了。这里问的是另一个问题:
         * 花名册的那几条读路径（{@link AgentRegistry#lifecycleOf}、
         * {@link AgentProfileView#paused()}）是不是真的从它走 —— 还是各自又写了一遍
         * {@code equals("paused")}。两处各写一遍的结果就是两处会漂, 而漂的那一处
         * 决定的是"她还跑不跑"。
         */
        @Test
        @DisplayName("认不出来的值、大小写、空白 —— 全部落到同一个方向")
        void 读侧兜底只有一个方向() {
            given(row("hum_x", "sleeping", null));
            assertEquals(AgentLifecycle.ACTIVE, registry.lifecycleOf("hum_x"),
                    "认不出来的值当运行 —— 这是本设计里唯一一处往危险方向兜的地方(§3.6.5)");

            given(row("hum_x", "PAUSED", null));
            assertEquals(AgentLifecycle.PAUSED, registry.lifecycleOf("hum_x"),
                    "大小写是打字习惯, 不该改变一个 agent 跑不跑");

            given(row("hum_x", "  paused  ", null));
            assertEquals(AgentLifecycle.PAUSED, registry.lifecycleOf("hum_x"));

            given(row("hum_x", null, null));
            assertEquals(AgentLifecycle.ACTIVE, registry.lifecycleOf("hum_x"),
                    "null 的含义是'刚建出来、还没被暂停过', 而不是'该停'");
        }

        @Test
        @DisplayName("查不到归属行时当运行, 而不是当停")
        void 没有归属行也要跑() {
            when(ownership.findByHumanId("hum_ghost")).thenReturn(Optional.empty());

            assertEquals(AgentLifecycle.ACTIVE, registry.lifecycleOf("hum_ghost"),
                    "一个还没有归属行的 id 意味着'这一行还没被 provisioning 出来', "
                            + "而那不是'她该停' —— 往停的方向兜会让一个刚建出来的 agent 直接变哑巴");
            assertFalse(registry.registered("hum_ghost"));
        }
    }

    // ─────────────────── 三、写 ───────────────────

    @Nested
    @DisplayName("设运行档")
    class 设运行档 {

        @Test
        @DisplayName("写的是 wire 形态, 而且同值不写库")
        void 设运行档写wire形态且幂等() {
            AgentOwnershipRecord row = row(HUM, "active", null);
            when(ownership.findByHumanId(HUM)).thenReturn(Optional.of(row));

            assertTrue(registry.setLifecycle(HUM, AgentLifecycle.PAUSED));
            assertEquals("paused", row.getLifecycle(), "库里存的是 wire() 而不是枚举名");
            verify(ownership, times(1)).save(row);

            assertFalse(registry.setLifecycle(HUM, AgentLifecycle.PAUSED),
                    "它本来就是这一档 —— 重复按同一个按钮必须返回 false, "
                            + "这正是 PUT .../lifecycle 这个形状买到的东西: 重复提交天然幂等");
            verify(ownership, times(1)).save(row);
        }

        @Test
        @DisplayName("已经写着 PAUSED 时再设 paused 也算没变")
        void 大写PAUSED也算同一档() {
            AgentOwnershipRecord row = row(HUM, "PAUSED", null);
            when(ownership.findByHumanId(HUM)).thenReturn(Optional.of(row));

            assertFalse(registry.setLifecycle(HUM, AgentLifecycle.PAUSED),
                    "库里写着 PAUSED 而运行时已经在按 PAUSED 办(of 认大小写), "
                            + "所以'她跑不跑'没有变 —— 若这里返回 true, 控制台会显示一次"
                            + "并不存在的状态变更");
            verify(ownership, never()).save(any());
        }

        @Test
        @DisplayName("对不存在的 agent 设运行档: 不写库, 也不抛")
        void 对不存在的agent不写库() {
            when(ownership.findByHumanId("hum_ghost")).thenReturn(Optional.empty());

            assertFalse(registry.setLifecycle("hum_ghost", AgentLifecycle.PAUSED),
                    "查不到就是没变 —— 而'该不该 404'是 HTTP 那一层的判断(它先 requireOwned), "
                            + "不是这里要替它做的");
            verify(ownership, never()).save(any());
        }
    }

    // ─────────────────── 四、投影的边角 ───────────────────

    @Nested
    @DisplayName("投影")
    class 投影 {

        @Test
        @DisplayName("human 行查不到时照样投影, 只是没有名字")
        void 悬空归属行照样投影() {
            // 这里刻意<b>不</b>走 given(): 那个辅助方法会给每个人造一行 human,
            // 于是"悬空"这件事根本不会发生 —— 一个被夹具抹平的用例是看不见的。
            // 断言的严重性正在于此: 悬空在真库上是可以出现的(human_id 上没有外键),
            // 而一个"投影时假定 human 行一定在"的实现会在那里抛 NPE, 于是
            // 整个控制台列表因为一行脏数据而 500。
            AgentOwnershipRecord orphan = row("hum_orphan", "active", null);
            when(ownership.findByHumanId("hum_orphan")).thenReturn(Optional.of(orphan));
            when(humans.findById("hum_orphan")).thenReturn(Optional.empty());

            AgentProfileView v = registry.profile("hum_orphan").orElseThrow();

            assertNull(v.displayName(),
                    "归属行还在、human 行没了 —— 这张表没有外键, 悬空是可能的(见 §7.2)。"
                            + "它必须能显示出来, 而不是变成一个 500: 运维看到的应该是"
                            + "'这个 agent 的身份行不见了', 而不是'控制台坏了'");
            assertEquals("hum_orphan", v.humanId());
            assertEquals(OWNER, v.ownerUserId());
            assertEquals("active", v.lifecycle(),
                    "身份行没了不影响运行档 —— 它是归属行上的列。一个把两者绑在一起的实现"
                            + "会让'human 行缺了'连带变成'她跑不跑也不知道了'");
        }

        @Test
        @DisplayName("查不到归属行 → empty, 而不是一个空壳档案")
        void 查不到就是empty() {
            when(ownership.findByHumanId("hum_ghost")).thenReturn(Optional.empty());

            assertTrue(registry.profile("hum_ghost").isEmpty(),
                    "一个没有归属行的她不是'一份各项为空的档案' —— 后者在界面上会渲染成"
                            + "一个活着的、状态全 0 的 agent");
        }

        @Test
        @DisplayName("N 行归属只查一次 human 表")
        void human表只查一次() {
            given(row("hum_1", "active", null), row("hum_2", "active", null),
                    row("hum_3", "active", null));

            assertEquals(3, registry.roster().size());

            verify(humans, times(1)).findAllByOrderByCreatedAtAsc();
            verify(humans, never()).findById(anyString());
        }

        @Test
        @DisplayName("空表不查 human 表")
        void 空表早退() {
            when(ownership.findAll()).thenReturn(List.of());

            assertEquals(List.of(), registry.roster());
            verify(humans, never()).findAllByOrderByCreatedAtAsc();
        }

        /**
         * {@code createdAt} 在内存构造的行上是 {@code null} —— 而排序不能因此炸。
         *
         * <p>真实场景里那一列由 {@code @CreationTimestamp} 填, 所以永远非空; 而
         * "永远非空"这句话在测试、回填脚本、与将来的导入路径上都不成立。
         * 一个 {@code Comparator.comparing(getCreatedAt)}（没有 nullsLast）会在这里
         * 抛 NPE, 而症状是"某个 agent 一被建出来, 整个控制台列表就 500"。
         */
        @Test
        @DisplayName("createdAt 为空的行排在最后, 不炸")
        void createdAt为空也能排序() {
            given(
                    row("hum_late", "active", null),
                    row("hum_null", "active", null),
                    row("hum_early", "active", null));
            registry.roster();  // 先确认不抛

            // 现在给三个不同的 createdAt, 只有中间那个是 null
            AgentOwnershipRecord early = row("hum_early", "active", null);
            early.setCreatedAt(LocalDateTime.parse("2026-01-01T00:00:00"));
            AgentOwnershipRecord late = row("hum_late", "active", null);
            late.setCreatedAt(LocalDateTime.parse("2026-03-01T00:00:00"));
            AgentOwnershipRecord nullAt = row("hum_null", "active", null);
            when(ownership.findAll()).thenReturn(List.of(late, nullAt, early));

            assertEquals(List.of("hum_early", "hum_late", "hum_null"),
                    registry.roster().stream().map(AgentProfileView::humanId).toList(),
                    "顺序必须稳定(按创建时刻正序), 而 null 排最后 —— "
                            + "倒序或按内容排会让整个列表在每次新建 agent 之后位移, "
                            + "而运维正看着它");
        }
    }

    // ─────────────────── 五、档案本身的语义 ───────────────────

    @Nested
    @DisplayName("档案")
    class 档案 {

        /**
         * {@code lifecycleRaw} 与 {@code lifecycleDrifted()} 是这份视图上唯一一处
         * "看起来冗余"的地方, 而它挡的是一个查不出来的现场。
         *
         * <pre>
         *   库里写着 "pausd"  →  运行时按 ACTIVE 办(兜底)  →  她在跑、账单在涨
         *   而控制台若只显示 lifecycle, 它显示的就是"运行中" —— 运维说
         *   "我明明按了暂停", 代码说"库里不是 paused", 而库里那个值没有任何地方显示
         * </pre>
         */
        @Test
        @DisplayName("认不出来的字被原样留住, 并被标成漂移")
        void 漂移的那一列看得见() {
            given(row("hum_x", "pausd", null));

            AgentProfileView v = registry.profile("hum_x").orElseThrow();

            assertEquals("pausd", v.lifecycleRaw(), "那一列的字面值必须原样给出来 —— 打错也要看得见");
            assertEquals("active", v.lifecycle(), "而运行时照做的这一档是兜底之后的");
            assertTrue(v.lifecycleDrifted(), "两者对不上就是漂移, 而界面上要能说出这句话");
            assertTrue(v.running(), "她在跑 —— 而这一条与上一条同时成立才是完整的现场");
        }

        @Test
        @DisplayName("正常值与空值都不算漂移")
        void 正常值不算漂移() {
            given(row("hum_a", "active", null), row("hum_b", "paused", null),
                    row("hum_c", "PAUSED", null), row("hum_d", "", null), row("hum_e", null, null));

            Map<String, AgentProfileView> byId = registry.roster().stream()
                    .collect(Collectors.toMap(AgentProfileView::humanId, v -> v));

            for (String id : List.of("hum_a", "hum_b", "hum_c", "hum_d", "hum_e")) {
                assertFalse(byId.get(id).lifecycleDrifted(),
                        id + " 被判成了漂移 —— 大小写与空白是打字习惯(不该报), "
                                + "而 null/空串是'刚建出来、还没被暂停过'(那是正常状态)。"
                                + "把正常状态报成漂移的后果是这块提示再也没人看");
            }
            assertEquals("paused", byId.get("hum_c").lifecycle(),
                    "大写 PAUSED 解析出来就是 PAUSED —— of() 忽略大小写, 所以它既不漂移、也不是 active。"
                            + "这一条与上面那条'不算漂移'是同一件事的两半: 前者说'别报警', "
                            + "后者说'但它的意思仍然是停'");
            assertEquals("paused", byId.get("hum_b").lifecycle());
            assertEquals("active", byId.get("hum_d").lifecycle(),
                    "空串认不出来 → 兜底成 active(§3.6.5)");
            assertEquals("active", byId.get("hum_e").lifecycle());
        }

        @Test
        @DisplayName("没有快照 = 她不在这台机器上, 而这不是'她的状态是零'")
        void 没有快照就是不在() {
            given(row("hum_x", "active", null));

            AgentProfileView v = registry.profile("hum_x").orElseThrow();

            assertNull(v.context(), "LiveHumanSource.NONE 说的是实话: 第 5/6 步还没落地, "
                    + "这台机器上确实一个人都没有");
            assertFalse(v.materialized());
        }

        @Test
        @DisplayName("有快照时它就是被投影进来的那一份")
        void 有快照就带上() {
            HumanContext fake = mock(HumanContext.class);
            registry = new AgentRegistry(ownership, humans,
                    new AgentProfileProjector(bindings, humanId -> "hum_x".equals(humanId) ? fake : null));
            given(row("hum_x", "active", null), row("hum_y", "active", null));

            assertTrue(registry.profile("hum_x").orElseThrow().materialized());
            assertEquals(fake, registry.profile("hum_x").orElseThrow().context(),
                    "投影进来的必须是 source 给的那一份 —— 不是一份拷贝、更不是一份默认值");
            assertFalse(registry.profile("hum_y").orElseThrow().materialized(),
                    "同一个 source 对另一个 humanId 说不 —— 于是'在不在'是逐人的, 不是一个全局开关");
        }

        @Test
        @DisplayName("描述里看得出三件事: 归属、运行档、在不在")
        void 描述说得清() {
            given(row("hum_x", "pausd", null));

            String d = registry.profile("hum_x").orElseThrow().describe();

            assertTrue(d.contains("hum_x") && d.contains(OWNER), "描述里要有她与她的主人: " + d);
            assertTrue(d.contains("pausd"), "要显示那一列的字面值, 否则打错的那个字没人看得见: " + d);
            assertTrue(d.contains("active"), "也要显示兜底之后真正生效的那一档: " + d);
            assertTrue(d.contains("不在这台机器上"), "而'在不在'必须说出来: " + d);
        }

        @Test
        @DisplayName("账号绑定按绑定时刻升序进视图")
        void 账号绑定按时刻进视图() {
            given(row("hum_x", "active", null));
            ConversationAccountBindingRecord a = binding("acc_1", "person_1", T);
            ConversationAccountBindingRecord b = binding("acc_2", "person_2", T.plusSeconds(60));
            when(bindings.findByHumanIdOrderByBoundAtAsc("hum_x")).thenReturn(List.of(a, b));

            List<AgentProfileView.AccountRef> accounts =
                    registry.profile("hum_x").orElseThrow().accounts();

            assertEquals(List.of("acc_1", "acc_2"), accounts.stream()
                    .map(AgentProfileView.AccountRef::chatAccountId).toList(),
                    "顺序由 repository 的方法名保证, 而视图必须原样保留 —— "
                            + "一份每次刷新都换顺序的列表在界面上看起来就是'她的账号在跳'");
            assertEquals("person_1", accounts.get(0).personId(),
                    "账号与人**不是**同一个字段: 她认的是人, 不是号(换过号不该变成陌生人)");
            assertEquals(T, accounts.get(0).boundAt(),
                    "绑定时刻是仿真时刻(Instant), 参与回放 —— 与归属行上那两个墙上时钟不是一回事");
        }

        @Test
        @DisplayName("没有账号不是错误")
        void 没有账号不是错误() {
            given(row("hum_x", "active", null));

            assertEquals(List.of(), registry.profile("hum_x").orElseThrow().accounts(),
                    "provisioning 与绑定是两步, 而'她还没有任何聊天账号'是正常状态 —— "
                            + "一个把它当异常的分支会在 onboarding 的中间态上炸");
        }

        @Test
        @DisplayName("两个 source 参数都不许为 null")
        void 不许传null的source() {
            assertThrows(NullPointerException.class,
                    () -> new AgentProfileProjector(bindings, null),
                    "null 会让'投影器忘了配'与'她确实不在这台机器上'变成同一个写法 —— "
                            + "而后者已经有一个有名字的答案: LiveHumanSource.NONE");
            assertThrows(NullPointerException.class,
                    () -> new AgentProfileProjector(null, LiveHumanSource.NONE));
        }
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 把一批归属行装进 mock, 并顺手给每个人一行 human —— 好让投影走完整条路。 */
    private void given(AgentOwnershipRecord... rows) {
        when(ownership.findAll()).thenReturn(List.of(rows));
        when(ownership.count()).thenReturn((long) rows.length);
        when(ownership.findByOwnerUserIdOrderByCreatedAtAsc(OWNER)).thenReturn(List.of(rows));
        List<HumanRecord> hs = new ArrayList<>();
        for (AgentOwnershipRecord r : rows) {
            when(ownership.findByHumanId(r.getHumanId())).thenReturn(Optional.of(r));
            when(ownership.existsByHumanId(r.getHumanId())).thenReturn(true);
            HumanRecord h = new HumanRecord();
            h.setId(r.getHumanId());
            h.setDisplayName("她-" + r.getHumanId());
            hs.add(h);
        }
        when(humans.findAllByOrderByCreatedAtAsc()).thenReturn(hs);
        for (HumanRecord h : hs) {
            when(humans.findById(h.getId())).thenReturn(Optional.of(h));
        }
    }

    private static AgentOwnershipRecord row(String humanId, String lifecycle, LocalDateTime deletedAt) {
        AgentOwnershipRecord r = new AgentOwnershipRecord();
        r.setId("own_" + humanId);
        r.setHumanId(humanId);
        r.setOwnerUserId(OWNER);
        r.setLifecycle(lifecycle);
        r.setDeletedAt(deletedAt);
        return r;
    }

    private static ConversationAccountBindingRecord binding(String accountId, String personId, Instant at) {
        ConversationAccountBindingRecord b = new ConversationAccountBindingRecord();
        b.setId("bind_" + accountId);
        b.setHumanId(HUM);
        b.setChatAccountId(accountId);
        b.setPersonId(personId);
        b.setBindReason("bootstrap");
        b.setBoundAt(at);
        return b;
    }
}
