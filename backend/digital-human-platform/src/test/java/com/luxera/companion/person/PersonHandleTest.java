package com.luxera.companion.person;

import com.luxera.companion.common.BusinessException;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号ID 的**行为** —— 自动分配、唯一、改号配额。
 *
 * <h2>这一组要钉住的那个症状</h2>
 *
 * 用户 2026-09-17 的聊天列表里有 7 行都叫「小满」。那 7 行是 7 个不同的、活着的 Agent ——
 * 名字是 LLM 从描述里生成的, 相近的描述反复收敛到同一个名字, 所以重名不是 bug 而是这个名字
 * 的固有性质。真正缺的是"哪个是哪个"的答案, 也就是账号ID。
 *
 * <p>因此这里最要紧的一条是 {@link #agentsWithTheSameNameStillGetDifferentHandles}:
 * 它断言的不是"有个字段", 而是"同名的人**拿到的号不一样**"。这一条挂了, 那 7 行在界面上
 * 就还是没法区分, 功能等于没做。
 *
 * <p>走真 Spring 上下文(与 {@code RelationshipGraphTest} 同风格)。建档走
 * {@code getOrCreateAgent} 而不是 {@code CompanionService#create} —— 后者内部就是调它
 * (见 {@code CompanionService.create}), 而绕过 {@code create} 也顺手避开了编译人格所需的
 * LLM 依赖。
 */
@ActiveProfiles("test")
@SpringBootTest
class PersonHandleTest {

    @Autowired
    PersonService personService;
    @Autowired
    PersonRepository persons;
    @Autowired
    PersonHandleChangeRepository changes;
    @Autowired
    CompanionRepository companionRepository;

    private final String userId = UUID.randomUUID().toString();

    /**
     * 每次运行一个随机后缀, 挂在所有**手写**的账号ID 后面。
     *
     * <p>必须这样: 测试库是跨运行共享的(与 {@code RelationshipGraphTest} 同一张库), 而
     * 账号ID 一旦写进去就**永久占着**(见 {@code Person#getHandle} —— 连软删的 Agent 都不
     * 释放)。写死 {@code xiaoman01} 会让第二次运行撞上第一次运行留下的行, 报"已被占用",
     * 而那是测试自己的残渣, 不是被测逻辑的问题。
     *
     * <p>自动分配的那些号不需要后缀 —— 它们本来就是随机且唯一的。
     */
    private final String tag = Handles.generate(new java.util.Random()).substring(0, 5);

    /** 手写账号ID 的统一构造: 保证每次运行都不同, 且形状合法(字母开头, 长度够) */
    private String h(String base) {
        return base + tag;
    }

    /** 建一个 Agent 并返回它的 Person —— 报号的路径与真实创建完全一致 */
    private Person newAgent(String name) {
        Companion c = new Companion();
        c.setId(UUID.randomUUID().toString());
        c.setUserId(userId);
        c.setName(name);
        c.setGender("female");
        companionRepository.save(c);
        return personService.getOrCreateAgent(c);
    }

    private void recordChange(String personId, String from, String to, LocalDateTime when) {
        PersonHandleChange r = new PersonHandleChange();
        r.setPersonId(personId);
        r.setOldHandle(from);
        r.setNewHandle(to);
        r.setChangedAt(when);
        changes.save(r);
    }

    // ── 自动分配 ──────────────────────────────────────────────────────────

    @Test
    void newAgentsGetAHandleThatSatisfiesTheShapeRules() {
        Person a = newAgent("小满");

        assertNotNull(a.getHandle(), "新建的 Agent 必须立刻有账号ID —— 不能等下次启动补");
        assertEquals(a.getHandle(), Handles.validate(a.getHandle()),
                "系统分配的号必须自己过得了校验, 否则用户改回去还会被拒");
    }

    /** ★ 用户看到的那个症状: 7 行「小满」分不出谁是谁。账号ID 就是来回答这个的。 */
    @Test
    void agentsWithTheSameNameStillGetDifferentHandles() {
        Person a = newAgent("小满");
        Person b = newAgent("小满");
        Person c = newAgent("小满");

        assertEquals(a.getName(), b.getName(), "前提: 名字确实一样(LLM 会收敛到同一个)");
        assertNotEquals(a.getHandle(), b.getHandle(), "同名也必须能被区分开");
        assertNotEquals(b.getHandle(), c.getHandle());
        assertNotEquals(a.getHandle(), c.getHandle());
    }

    @Test
    void creatingTheSameAgentTwiceDoesNotRerollItsHandle() {
        Person first = newAgent("阿澈");
        String handle = first.getHandle();

        Person again = personService.getOrCreateAgent(
                companionRepository.findById(first.getCompanionId()).orElseThrow());

        assertEquals(handle, again.getHandle(), "幂等的取/建不能把已经报出去的号换掉");
    }

    @Test
    void usersGetAHandleTooButImaginaryPersonsDoNot() {
        Person me = personService.getOrCreateUser(userId);
        assertNotNull(me.getHandle(), "用户也是账号, 也该有号");

        Person imaginary = personService.createOther("她的大学室友", "female", Map.of());
        assertNull(imaginary.getHandle(),
                "OTHER 是数字人社交圈里的虚构人物, 不是账号 —— 发号只会造出无人认领的号码");
    }

    // ── 唯一性 ────────────────────────────────────────────────────────────

    @Test
    void takingAnAlreadyUsedHandleIsRefusedWith409() {
        Person a = newAgent("小满");
        Person b = newAgent("小满");

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(b.getId(), a.getHandle()));

        assertEquals(HttpStatus.CONFLICT, e.getStatus(),
                "被占用是 409 不是 400 —— 前端要据此提示「换一个」而不是「格式不对」");
        assertNotNull(e.getHint(), "要给出可执行的下一步");
        assertFalse(e.getHint().isBlank());
        assertEquals(b.getHandle(), personService.requireById(b.getId()).getHandle(),
                "失败之后 b 的号必须原封不动");
    }

    @Test
    void aTakenHandleIsNotTheVictimsProblem() {
        Person victim = newAgent("林晓");
        Person attacker = newAgent("林晓");
        String victimsHandle = victim.getHandle();

        assertThrows(BusinessException.class,
                () -> personService.changeHandle(attacker.getId(), victimsHandle));

        assertEquals(victimsHandle, personService.requireById(victim.getId()).getHandle(),
                "抢号失败不能把原主人的号弄丢");
    }

    // ── 配额 ──────────────────────────────────────────────────────────────

    @Test
    void changingToTheSameValueIsNotAChangeAndCostsNothing() {
        Person a = newAgent("小满");
        String handle = a.getHandle();

        HandleQuota q = personService.changeHandle(a.getId(), handle);

        assertEquals(3, q.remaining(), "原样提交不该扣掉一年三次里的一次");
        assertEquals(0, q.used());
        assertEquals(handle, q.handle());
    }

    @Test
    void theShapeIsNormalizedRatherThanRejected() {
        Person a = newAgent("小满");
        String wanted = h("xiaoman");

        HandleQuota q = personService.changeHandle(a.getId(), "  " + wanted.toUpperCase() + "  ");

        assertEquals(wanted, q.handle(), "大写和空白应当被归一, 而不是报错");
    }

    @Test
    void threeChangesAreAllowedAndTheFourthIsRefusedWith429() {
        Person a = newAgent("小满");
        String third = h("xiaoman3");

        assertEquals(2, personService.changeHandle(a.getId(), h("xiaoman1")).remaining());
        assertEquals(1, personService.changeHandle(a.getId(), h("xiaoman2")).remaining());
        assertEquals(0, personService.changeHandle(a.getId(), third).remaining());

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(a.getId(), h("xiaoman4")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertTrue(e.getHint().contains("下次可改时间"), "要告诉用户什么时候能再改, 而不是只说不许: " + e.getHint());
        assertEquals(third, personService.requireById(a.getId()).getHandle(),
                "被拒的那次不能生效");
    }

    /** 配额是**滑动 365 天**, 不是自然年 —— 老的那次滑出窗口, 额度自动回来。 */
    @Test
    void quotaComesBackWhenTheOldestChangeSlidesOutOfTheWindow() {
        Person a = newAgent("小满");
        String id = a.getId();

        recordChange(id, "old1", "old2", LocalDateTime.now().minusDays(400));
        recordChange(id, "old2", "old3", LocalDateTime.now().minusDays(370));
        recordChange(id, "old3", "old4", LocalDateTime.now().minusDays(10));
        recordChange(id, "old4", "old5", LocalDateTime.now().minusDays(5));

        // 400 天和 370 天前那两次已经在窗口外, 窗口内只剩两次 —— 还改得动
        HandleQuota q = personService.quotaOf(id);
        assertEquals(2, q.used(), "只有落在最近 365 天内的才算数");
        assertEquals(1, q.remaining());
        assertNull(q.nextChangeAt(), "还有额度时不该报「下次可改时间」");

        assertEquals(0, personService.changeHandle(id, h("brandnew")).remaining());
    }

    @Test
    void nextChangeAtIsReportedOnlyWhenTheQuotaIsExhausted() {
        Person a = newAgent("小满");
        String id = a.getId();

        LocalDateTime oldest = LocalDateTime.now().minusDays(300);
        recordChange(id, "h0", "h1", oldest);
        recordChange(id, "h1", "h2", LocalDateTime.now().minusDays(200));
        recordChange(id, "h2", "h3", LocalDateTime.now().minusDays(100));

        HandleQuota q = personService.quotaOf(id);

        assertEquals(0, q.remaining());
        assertNotNull(q.nextChangeAt());
        // 容差 1 毫秒, 而不是精确相等: {@code timestamp without time zone} 只存到微秒,
        // 而 Java 的 LocalDateTime.now() 带纳秒, 落库时 Postgres **四舍五入**。精确比较
        // 测的是这个类型转换, 不是被测逻辑。业务上这个值最终以"哪一天"呈现, 亚毫秒无意义 ——
        // 真正要钉住的是"它等于最早那次改号 + 365 天", 容差 1 毫秒足以体现这件事。
        long deltaMs = Math.abs(java.time.Duration.between(
                oldest.plusDays(365), q.nextChangeAt()).toMillis());
        assertTrue(deltaMs <= 1,
                "能再改的时刻 = 三次里最早那次滑出窗口的时候, 实得偏差 " + deltaMs + "ms");
        assertTrue(q.nextChangeAt().isAfter(LocalDateTime.now()), "报出来的必须是未来的时间");
    }

    /** 窗口边界: 恰好 365 天前的那次已经不算数了(否则窗口永远关不上)。 */
    @Test
    void aChangeExactlyAtTheWindowEdgeNoLongerCounts() {
        Person a = newAgent("小满");
        recordChange(a.getId(), "h0", "h1", LocalDateTime.now().minusDays(366));

        assertEquals(0, personService.quotaOf(a.getId()).used());
        assertEquals(3, personService.quotaOf(a.getId()).remaining());
    }

    // ── 校验先于配额 ──────────────────────────────────────────────────────

    /**
     * 顺序很重要: 形状错的人应当收到"格式不对", 而不是"你的配额用完了" ——
     * 后者是一个他既改不动、又和眼前问题无关的坏消息。
     */
    @Test
    void aMalformedHandleIsRejectedOnShapeNotOnQuota() {
        Person a = newAgent("小满");
        String id = a.getId();
        recordChange(id, "h0", "h1", LocalDateTime.now().minusDays(1));
        recordChange(id, "h1", "h2", LocalDateTime.now().minusDays(1));
        recordChange(id, "h2", "h3", LocalDateTime.now().minusDays(1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(id, "小满"));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(),
                "配额已满 + 形状也错时, 要报形状 —— 那是用户此刻能改的东西");
    }

    // ── 批量读取 ──────────────────────────────────────────────────────────

    @Test
    void handlesOfCompanionsReturnsEveryAgentInOneGo() {
        Person a = newAgent("小满");
        Person b = newAgent("小满");
        Person c = newAgent("阿澈");

        Map<String, String> got = personService.handlesOfCompanions(
                List.of(a.getCompanionId(), b.getCompanionId(), c.getCompanionId()));

        assertEquals(3, got.size());
        assertEquals(a.getHandle(), got.get(a.getCompanionId()));
        assertEquals(c.getHandle(), got.get(c.getCompanionId()));
        assertNotEquals(got.get(a.getCompanionId()), got.get(b.getCompanionId()));
        assertEquals(Map.of(), personService.handlesOfCompanions(List.of()),
                "空输入不该去打一次库");
    }

    // ── 补号 ──────────────────────────────────────────────────────────────

    /**
     * 老数据: 加列之前建的 Person 没有账号ID —— 这正是用户那 9 个 Agent 的状态。
     * 不补号, 界面上不会有任何变化, 功能等于没做。
     */
    @Test
    void backfillGivesLegacyRowsAHandleAndLeavesImaginaryPersonsAlone() {
        Person legacyAgent = newAgent("小满");
        legacyAgent.setHandle(null);
        persons.save(legacyAgent);

        Person legacyUser = personService.getOrCreateUser(UUID.randomUUID().toString());
        legacyUser.setHandle(null);
        persons.save(legacyUser);

        Person imaginary = personService.createOther("邻居", "male", Map.of());

        List<Person> missing = persons.findByHandleIsNullAndPersonTypeIn(
                List.of(Person.TYPE_USER, Person.TYPE_AGENT));
        assertTrue(missing.stream().anyMatch(p -> p.getId().equals(legacyAgent.getId())));
        assertFalse(missing.stream().anyMatch(p -> p.getId().equals(imaginary.getId())),
                "OTHER 不在补号范围内");

        for (Person p : missing) {
            personService.ensureHandle(p);
        }

        assertNotNull(personService.requireById(legacyAgent.getId()).getHandle());
        assertNotNull(personService.requireById(legacyUser.getId()).getHandle());
        assertNull(personService.requireById(imaginary.getId()).getHandle(),
                "虚构人物永远不该拿到账号");
    }

    @Test
    void ensureHandleIsIdempotentAndNeverRerolls() {
        Person a = newAgent("小满");
        String handle = a.getHandle();

        assertEquals(handle, personService.ensureHandle(a).getHandle());
        assertEquals(handle, personService.ensureHandle(personService.requireById(a.getId())).getHandle(),
                "已经报出去的号不能被补号流程换掉");
    }
}
