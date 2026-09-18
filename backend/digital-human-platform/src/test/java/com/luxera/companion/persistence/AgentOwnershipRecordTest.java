package com.luxera.companion.persistence;

import com.luxera.companion.persistence.entity.AgentOwnershipRecord;
import com.luxera.companion.persistence.repository.AgentOwnershipRecordRepository;
import com.luxera.companion.runtime.AgentLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.2 §3.6 / §7.2 —— {@code agent_ownership}: <b>"她归谁"与"她现在跑不跑"</b>。
 *
 * <h2>这个文件要钉的不是一张表的列, 而是三条判断</h2>
 * 这张表只有八列, 而其中三处是<b>做过的判断</b> —— 判断本身会被人改回"看起来更整齐"
 * 的那一版, 而改回去的症状全都不在这张表上:
 * <table border="1">
 *   <tr><th>判断</th><th>改回去之后会怎样</th></tr>
 *   <tr>
 *     <td>两个问题两个列（{@link AgentOwnershipRecord#alive()} /
 *         {@link AgentOwnershipRecord#paused()} 不合并）</td>
 *     <td>"她被删了"与"她被暂停了"在调用处再也分不开 ——
 *         而这两件事对世界的影响是相反的: 暂停不删任何东西, 删除要走清理逻辑</td>
 *   </tr>
 *   <tr>
 *     <td>{@code lifecycle} 的兜底方向是"<b>照常运行</b>"</td>
 *     <td>一个拼错的 / 将来新增的取值会让 agent <b>变成哑巴</b> ——
 *         症状是"她不回我了", 没有报错、没有日志、没有任何指向这一列的线索</td>
 *   </tr>
 *   <tr>
 *     <td>实体上零外键、零 {@code @Version}</td>
 *     <td>{@code human} 行的存亡会绑住平台侧归属行的存亡 ——
 *         正是本表存在要拆开的那件事</td>
 *   </tr>
 * </table>
 *
 * <h2>为什么这张表的实体值一个测试文件</h2>
 * 因为它<b>没有 store</b>: 其它持久化对象都有一个把领域对象 ⇄ 行搬来搬去的类,
 * 而这一张表上的访问路径就是 {@link AgentOwnershipRecordRepository} 的几个派生查询
 * —— 它的字段与判断就是全部行为。给一个有行为的 store 写测试, 那些行为会顺带被覆盖;
 * 这里没有那层保护, 所以每一条判断都必须被直接钉住。
 *
 * <h2>为什么这里可以断言"读侧兜底与 {@code AgentLifecycle.of} 同向"</h2>
 * 实体的 javadoc 说了一句"两处不一致时哪边算错: {@code AgentLifecycle.of} 那边",
 * 而那样一句话如果没有测试, 它会随着时间演变成"两个地方都觉得自己对"。
 * 实体自己<b>不该</b> import 运行时（依赖方向是运行时读实体）——
 * 但一个<b>测试</b>同时看两边正是把"两边必须同向"变成代码的正确位置:
 * 它不引入任何生产依赖, 却能在有人改动其中一侧时立刻亮红。
 * 这条断言刻意只覆盖"同一个输入两边给出同一个答案", 不覆盖实现方式。
 */
class AgentOwnershipRecordTest {

    private static final String HID = "hum_00000000-0000-0000-0000-000000000001";
    private static final String OWNER = "user_00000000-0000-0000-0000-000000000002";
    private static final String CLIENT = "client_00000000-0000-0000-00000000";

    // ═════════════════ ① 两个问题, 两个列 ═════════════════

    @Test
    @DisplayName("两个问题各问各的: 活着吗走 deleted_at, 跑不跑走 lifecycle")
    void 两个问题两个列() {
        AgentOwnershipRecord row = row("active", null);
        assertTrue(row.alive());
        assertFalse(row.paused());

        AgentOwnershipRecord paused = row("paused", null);
        assertTrue(paused.alive(), "暂停不是删除 —— 记忆/关系/队列全都原样保留");
        assertTrue(paused.paused());

        AgentOwnershipRecord deleted = row("active",
                LocalDateTime.of(2026, 9, 19, 9, 0));
        assertFalse(deleted.alive());
        assertFalse(deleted.paused(),
                "这一行<b>可以</b>同时是 active 与已删除 —— 它不是矛盾, 因为两个列回答两个问题。"
                        + "四值枚举（ACTIVE/PAUSED/RETIRED/ARCHIVED）在这里会逼出一个选择: "
                        + "要么写 RETIRED 而丢掉'它被删之前是跑着的还是停着的', "
                        + "要么允许 lifecycle=ACTIVE 而 deleted_at!=null, 于是'她已经删了'在库里有两个副本");
    }

    @Test
    @DisplayName("跑不跑是调用方自己写的合取 —— 本类刻意不提供 running()")
    void 跑不跑是调用方自己写的合取() {
        List<AgentOwnershipRecord> rows = List.of(
                row("active", null),
                row("paused", null),
                row("active", LocalDateTime.of(2026, 9, 19, 9, 0)),
                row("paused", LocalDateTime.of(2026, 9, 19, 9, 0)));

        // 这四个组合覆盖了"两个列"的完整笛卡尔积, 而它们的合取取值各不相同:
        assertEquals(List.of(true, false, false, false),
                rows.stream().map(r -> r.alive() && !r.paused()).toList(),
                "只有'活着且没被暂停'才跑。把这一步包成 running() 看起来贴心, "
                        + "但那会把两个问题又合并成一个答案 —— 于是某个分支里"
                        + "'暂停不影响世界、删除才影响'这条语义就会丢掉");

        assertEquals(List.of(true, true, false, false),
                rows.stream().map(AgentOwnershipRecord::alive).toList());
        assertEquals(List.of(false, true, false, true),
                rows.stream().map(AgentOwnershipRecord::paused).toList());
    }

    @Test
    @DisplayName("被软删的行不会因为它是 active 就继续跑 —— 这正是两个列不合并的意义")
    void 软删的行不因为_lifecycle_是_active_就继续跑() {
        AgentOwnershipRecord deleted = row("active", LocalDateTime.of(2026, 9, 19, 9, 0));

        assertFalse(deleted.alive() && !deleted.paused(),
                "一个只看 lifecycle 的调度器会继续给她发 tick —— 而她已经删了");
        assertTrue(deleted.describe().contains("软删"), deleted.describe());
    }

    // ═════════════════ ② 兜底方向: 认不出来一律当"跑" ═════════════════

    @Test
    @DisplayName("只有字面写着 paused 才算停 —— 其余一切取值都当'照常运行'")
    void 只有字面写着_paused_才算停() {
        // ── 认不出来的那些: 一个都不许判成"停"
        for (String raw : List.of("active", "ACTIVE", "sleeping", "pausd", "pauseddd",
                "stopped", "0", "-", "暂停")) {
            assertFalse(row(raw, null).paused(),
                    "lifecycle=\"" + raw + "\" 不是 paused 的字面写法 —— 判成停会让 agent 变成哑巴, "
                            + "而症状里没有任何线索指向这一列");
        }

        // ── null 与空白: 一行还没被暂停过的记录是 ACTIVE
        assertFalse(row(null, null).paused(), "null 的含义是'刚建出来、还没被暂停过'");
        assertFalse(row("", null).paused());
        assertFalse(row("   ", null).paused());

        // ── 而真的写着 paused 的（含大小写与首尾空白）必须判成停
        for (String raw : List.of("paused", "PAUSED", "Paused", "paused ", " PAUSED\t")) {
            assertTrue(row(raw, null).paused(),
                    "lifecycle=\"" + raw + "\" 是 paused 的合法写法（trim + 忽略大小写）");
        }
    }

    @Test
    @DisplayName("兜底方向与 AgentLifecycle.of 一致 —— 两个地方不许各答各的")
    void 兜底方向与运行时枚举一致() {
        List<String> values = List.of("active", "ACTIVE", "paused", "PAUSED", " Paused ",
                "sleeping", "pausd", "", "   ", "暂停");
        AgentOwnershipRecord row = new AgentOwnershipRecord();

        for (String raw : values) {
            row.setLifecycle(raw);
            assertEquals(AgentLifecycle.of(raw).isPaused(), row.paused(),
                    "输入 \"" + raw + "\" 上两处给出了不同答案 —— "
                            + "实体的 javadoc 写了'不一致时以运行为准', 那么这一处要改成去调 of()");
        }

        // null 是唯一不能两边都喂的输入: AgentLifecycle.of(null) 有显式的空值分支,
        // 而实体上是 paused() 的 null 判断 —— 结果必须都是"跑"
        assertFalse(AgentLifecycle.of(null).isPaused());
        assertFalse(row(null, null).paused());
    }

    // ═════════════════ ③ 三个 ID 与自建标记 ═════════════════

    @Test
    @DisplayName("platformCreated 认的是'没有客户端 id', 不是某个哨兵字符串")
    void platformCreated_认的是空值而不是哨兵() {
        assertTrue(row("active", null).platformCreated(), "空 = 平台自己的 provisioning 流程建的");
        assertTrue(row("active", null, "   ").platformCreated(), "空白同样算没有");

        AgentOwnershipRecord thirdParty = row("active", null, CLIENT);
        assertFalse(thirdParty.platformCreated());
        assertTrue(thirdParty.describe().contains(CLIENT), thirdParty.describe());

        // ── 没有"PLATFORM"之类的哨兵: 一个真的叫这个名字的客户端 id 会与它撞车,
        //    而 NULL 不会。所以这里断的是"任何非空取值都不算自建"
        assertFalse(row("active", null, "PLATFORM").platformCreated(),
                "字符串 PLATFORM 是本表**不认识**的一个普通客户端 id");
    }

    @Test
    @DisplayName("三个 ID 各自原样保留 —— 本类不做任何换算或前缀校验")
    void 三个_id_原样保留() {
        AgentOwnershipRecord row = new AgentOwnershipRecord();
        row.setHumanId(HID);
        row.setOwnerUserId(OWNER);
        row.setCreatedByClientId(CLIENT);

        assertEquals(HID, row.getHumanId());
        assertEquals(OWNER, row.getOwnerUserId());
        assertEquals(CLIENT, row.getCreatedByClientId());
        assertNotEquals(row.getId(), row.getHumanId(),
                "行的 id 与 human_id 是两个不同的值: 用 human_id 当主键会让'归属行的身份'"
                        + "与'仿真身份'成为同一个值, 于是改归属又要引用仿真侧的标识");
    }

    @Test
    @DisplayName("describe 把这一行的四个事实都说出来")
    void describe说清了这一行是什么() {
        String described = row("active", null).describe();
        assertTrue(described.contains(HID), described);
        assertTrue(described.contains(OWNER), described);
        assertTrue(described.contains("active"), described);
        assertTrue(described.contains("平台自建"), described);
        assertFalse(described.contains("软删"), described);

        AgentOwnershipRecord pausedThirdParty = row("paused", null, CLIENT);
        String other = pausedThirdParty.describe();
        assertTrue(other.contains("paused"), other);
        assertTrue(other.contains(CLIENT), other);
        assertFalse(other.contains("平台自建"), other);
    }

    // ═════════════════ ④ 装配前的那一半: assignId ═════════════════

    @Test
    @DisplayName("assignId 只在没有 id 时发一个, 再调一次不会换掉它")
    void assignId_是幂等的() throws Exception {
        AgentOwnershipRecord row = new AgentOwnershipRecord();
        assertNull(row.getId(), "新对象手上是空的");

        invokeAssignId(row);
        String first = row.getId();
        assertNotNull(first);
        assertEquals(36, first.length(), "列宽 36 —— 一个 UUID 的形状");

        invokeAssignId(row);
        assertEquals(first, row.getId(),
                "@PrePersist 会在每次 save 时跑: 一个每次都换 id 的实现"
                        + "会让'同一行'在库里变成两行, 而错误看起来像重复写入");

        AgentOwnershipRecord preset = new AgentOwnershipRecord();
        preset.setId("fixed-id");
        invokeAssignId(preset);
        assertEquals("fixed-id", preset.getId(), "已经有 id 的原样保留 —— 回填/导入要靠这一条");
    }

    // ═════════════════ ⑤ 表结构反射 ═════════════════

    /**
     * §7.2 写下来的那一组列 —— 名字、宽度、可空性。
     *
     * <p>为什么要用反射钉一遍这些<b>声明</b>, 而不是靠"跑一次真库看看"：
     * {@code ddl-auto: update} <b>只会建表, 不会改已有的列</b>。一个把
     * {@code length = 36} 改成 64 的人, 在本地（空库）看到的是新宽度,
     * 而在真机（已有数据的库）上那一列仍然是 36 —— 于是"改窄了"这类错误
     * 只在生产上以"写不进去"的形式出现, 没有测试会拦它。
     * 反射钉住的是注解本身, 而与数据库无关。
     */
    @Test
    @DisplayName("① 表名/唯一约束/索引都按 §7.2 声明")
    void 表名与约束就绪() {
        Table table = AgentOwnershipRecord.class.getAnnotation(Table.class);
        assertNotNull(table, "没有 @Table 的表名会由 Hibernate 从类名推 —— 那是 agent_ownership_record");
        assertEquals("agent_ownership", table.name());

        UniqueConstraint[] uniques = table.uniqueConstraints();
        assertEquals(1, uniques.length, "只有一个唯一约束");
        assertEquals("uk_agent_ownership_human", uniques[0].name());
        assertEquals(List.of("human_id"), List.of(uniques[0].columnNames()),
                "'一个 human 只被一个用户拥有'唯一的保证在库里, 不在调用方的约定里 —— "
                        + "少了它, 一次 provisioning 重试会造出两个主人, 而 findByHumanId 变成'取第一行'");

        Index[] indexes = table.indexes();
        assertEquals(1, indexes.length);
        assertEquals("idx_agent_ownership_owner", indexes[0].name());
        assertEquals("owner_user_id", indexes[0].columnList(),
                "服务的是'这个用户名下有几个 agent'（控制台列表 + 配额计数）");
        assertNotEquals("human_id", indexes[0].columnList(),
                "这两条索引不能互相替代 —— 它们查询的第一个条件不同");
    }

    @Test
    @DisplayName("② 每一列的宽度与可空性都是想过的")
    void 列声明就绪() throws Exception {
        for (String field : List.of("id", "human_id", "owner_user_id", "created_by_client_id",
                "lifecycle", "deleted_at", "created_at", "updated_at")) {
            assertNotNull(columnOf(fieldOf(field)), "§7.2 要求有这一列: " + field);
        }

        assertEquals(36, columnOf(fieldOf("id")).length());
        assertEquals(36, columnOf(fieldOf("human_id")).length());
        assertEquals(36, columnOf(fieldOf("owner_user_id")).length());
        assertEquals(36, columnOf(fieldOf("created_by_client_id")).length(),
                "三方客户端 id 由平台发放, 与 owner_user_id 同一宽度");

        Column lifecycle = columnOf(fieldOf("lifecycle"));
        assertEquals(32, lifecycle.length(),
                "按含义定宽, 不按今天的取值定（'active'/'paused' 只要 6）—— 见 WorldEventRecord 的同一句话");
        assertFalse(lifecycle.nullable(), "lifecycle 不能为 NULL: '她没有档位'不是一个可表达的处境");

        assertFalse(columnOf(fieldOf("human_id")).nullable());
        assertFalse(columnOf(fieldOf("owner_user_id")).nullable());
        assertTrue(columnOf(fieldOf("created_by_client_id")).nullable(),
                "NULL = 平台自建, 这是一个**取值**而不是'不知道'");
        assertTrue(columnOf(fieldOf("deleted_at")).nullable(),
                "NULL = 她还活着 —— 这一列的可空性就是它的语义");

        // created_at 是审计列: 一次转让不该改写"这一行什么时候被建出来"
        assertFalse(columnOf(fieldOf("created_at")).nullable());
        assertFalse(columnOf(fieldOf("created_at")).updatable());
        assertNotNull(columnOf(fieldOf("updated_at")));
    }

    @Test
    @DisplayName("③ 这张表上没有仿真时刻列: 归属与生命周期是平台概念")
    void 这张表上没有仿真时刻列() throws Exception {
        for (Field field : AgentOwnershipRecord.class.getDeclaredFields()) {
            assertNotEquals(Instant.class, field.getType(),
                    field.getName() + " 是 Instant —— 本表一个仿真时刻列都不该有。"
                            + "给这一行加一个 Instant 会暗示'这件事在仿真世界里发生过', 而它没有: "
                            + "仿真加速 60 倍不该改变'她被暂停于哪一刻'");
        }
        // 反过来, 墙上时钟列必须有: 本表的三列都是平台侧时间
        for (String field : List.of("deleted_at", "created_at", "updated_at")) {
            assertEquals(LocalDateTime.class, fieldOf(field).getType(), field);
        }
    }

    @Test
    @DisplayName("④ 零外键 / 零乐观锁 —— 本仓的既有约定, 而这张表上各有具体理由")
    void 零外键零乐观锁() {
        for (Field field : AgentOwnershipRecord.class.getDeclaredFields()) {
            assertNull(field.getAnnotation(ManyToOne.class),
                    field.getName() + " 上有 @ManyToOne —— human 行的删除会去检查/级联这一行, "
                            + "于是平台侧的归属行的存亡被仿真侧身份行的存亡绑住了");
            assertNull(field.getAnnotation(JoinColumn.class));
            assertNull(field.getAnnotation(OneToMany.class));
            assertNull(field.getAnnotation(Version.class),
                    field.getName() + " 上有 @Version —— 本仓零乐观锁（并发由调度层的单线程 tick 保证）; "
                            + "两次转让在两个语义上都可接受（后写的赢）, 需要'让第二个人知道'的调用方"
                            + "要的是读一次再判断, 而不是一列版本号");
        }
        assertNotNull(AgentOwnershipRecord.class.getDeclaredFields()[0].getAnnotation(Id.class),
                "字段顺序的第一列是主键 —— id 刻意不是 human_id");
    }

    @Test
    @DisplayName("⑤ 实体与仓库的契约: 派生查询的<b>方法名</b>就是查询本身")
    void 仓库的派生查询就绪() throws Exception {
        // Spring Data 的查询是**从方法名推出来的** —— 改名的后果不是编译错,
        // 而是查询条件静默变了（例如漏掉 DeletedAtIsNull 就会把已软删的也捞出来）
        assertQuery("findByHumanId", Optional.class, String.class);
        assertQuery("existsByHumanId", boolean.class, String.class);
        assertQuery("findByOwnerUserIdOrderByCreatedAtAsc", List.class, String.class);
        assertQuery("countByOwnerUserId", long.class, String.class);
        assertQuery("findByLifecycleOrderByCreatedAtAsc", List.class, String.class);
        assertQuery("findByLifecycleAndDeletedAtIsNullOrderByCreatedAtAsc", List.class, String.class);
        assertQuery("countByLifecycle", long.class, String.class);
        assertQuery("findByCreatedByClientIdOrderByCreatedAtAsc", List.class, String.class);
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static AgentOwnershipRecord row(String lifecycle, LocalDateTime deletedAt) {
        return row(lifecycle, deletedAt, null);
    }

    private static AgentOwnershipRecord row(String lifecycle, LocalDateTime deletedAt,
                                            String createdByClientId) {
        AgentOwnershipRecord row = new AgentOwnershipRecord();
        row.setId("own-" + lifecycle + "-" + createdByClientId);
        row.setHumanId(HID);
        row.setOwnerUserId(OWNER);
        row.setCreatedByClientId(createdByClientId);
        row.setLifecycle(lifecycle);
        row.setDeletedAt(deletedAt);
        return row;
    }

    private static void invokeAssignId(AgentOwnershipRecord row) throws Exception {
        // assignId 是包内可见的（@PrePersist 的回调不需要公开 API）,
        // 而本测试在上一层包 —— 反射是这里唯一的路, 也是对的:
        // 把它改成 public 只为让测试够得着, 是把可见性交给测试驱动的反面
        Method assignId = AgentOwnershipRecord.class.getDeclaredMethod("assignId");
        assignId.setAccessible(true);
        assignId.invoke(row);
    }

    private static Field fieldOf(String columnName) throws Exception {
        for (Field field : AgentOwnershipRecord.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && columnName.equals(column.name())) {
                return field;
            }
        }
        throw new AssertionError("没有声明列 " + columnName);
    }

    private static Column columnOf(Field field) {
        return field.getAnnotation(Column.class);
    }

    private static void assertQuery(String name, Class<?> returnType, Class<?>... params) {
        Method method;
        try {
            method = AgentOwnershipRecordRepository.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("仓库上少了派生查询 " + name, e);
        }
        assertEquals(returnType, method.getReturnType(), name);
        assertFalse(method.isAnnotationPresent(org.springframework.data.jpa.repository.Query.class),
                name + " 上写着 @Query —— 本仓的查询全部由方法名派生（没有 JPQL 字符串）。"
                        + "一条 JPQL 与它的方法名可以给出不同的结果, 而只有方法名是调用处看得见的那一份");
    }
}
