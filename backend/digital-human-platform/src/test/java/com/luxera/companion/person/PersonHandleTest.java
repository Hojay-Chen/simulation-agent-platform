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

    /**
     * 建一个真人并返回它的 Person。
     *
     * <p>配额与改号的测试全部走这里, 而不是 {@link #newAgent} —— 因为**只有人能改号**
     * (Agent 的账号ID 由系统分配)。换句话说这不是"换个夹具", 是这批测试的被测对象
     * 从"任意 Person"收窄成了"USER Person", 而那正是需求本身。
     */
    private Person newUser() {
        return personService.getOrCreateUser(UUID.randomUUID().toString());
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
        assertTrue(Handles.isAgentHandle(a.getHandle()),
                "Agent 的号必须带 agent_ 前缀 —— 那正是「这个号不能改」的一眼可读标识: " + a.getHandle());
        // 用 validateMinted 而不是 validate: 后者按设计**就拒** agent_ 前缀(防冒充),
        // 拿它验系统自己铸出来的号, 会把合法产物判成非法。
        assertEquals(a.getHandle(), Handles.validateMinted(a.getHandle()),
                "系统分配的号必须自己过得了铸号侧的校验");
    }

    @Test
    void newUsersGetAHandleWithoutTheAgentPrefix() {
        Person me = newUser();

        assertNotNull(me.getHandle());
        assertFalse(Handles.isAgentHandle(me.getHandle()),
                "人的号不带前缀 —— 前缀是「不能改」的标识, 而人的号是可以改的");
        assertEquals(me.getHandle(), Handles.validate(me.getHandle()));
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
        Person a = newUser();
        Person b = newUser();

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
        Person victim = newUser();
        Person attacker = newUser();
        String victimsHandle = victim.getHandle();

        assertThrows(BusinessException.class,
                () -> personService.changeHandle(attacker.getId(), victimsHandle));

        assertEquals(victimsHandle, personService.requireById(victim.getId()).getHandle(),
                "抢号失败不能把原主人的号弄丢");
    }

    // ── Agent 不可改 ─────────────────────────────────────────────────────

    /**
     * ★ 需求「Agent 的聊天账号ID 不能被修改」—— 这是它的正面断言。
     */
    @Test
    void anAgentMayNotChangeItsHandle() {
        Person agent = newAgent("小满");
        String before = agent.getHandle();

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(agent.getId(), h("newname")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus(),
                "改不动的东西是 403(禁止), 不是 400(你写错了) —— 后者会诱使用户去改格式");
        assertEquals(before, personService.requireById(agent.getId()).getHandle(),
                "被拒之后号必须原封不动");
    }

    /**
     * 闸门必须在**形状校验之前**。顺序反了, 用户先收到"你格式写错了"、改对格式之后再被
     * 同一个 403 挡一次 —— 一次改不动却要试两遍的交互。
     */
    @Test
    void theAgentGateFiresBeforeShapeValidation() {
        Person agent = newAgent("小满");

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(agent.getId(), "小满"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus(),
                "形状也错时应当报 403 —— 格式对不对根本不影响结论, 不该让用户白改一遍");
    }

    /** OTHER(数字人社交圈里的虚构人物)同样不是能自选账号的东西。 */
    @Test
    void anImaginaryPersonMayNotChooseAHandleEither() {
        Person imaginary = personService.createOther("邻居", "male", Map.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> personService.changeHandle(imaginary.getId(), h("neighbor")));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    /** 浏览器那条路: 手里只有登录用户的 userId, 没有 personId。 */
    @Test
    void aUserCanChangeTheirOwnHandleByUserId() {
        String uid = UUID.randomUUID().toString();

        HandleQuota q = personService.changeUserHandle(uid, h("myname"));

        assertEquals(h("myname"), q.handle());
        assertEquals(1, q.used());
    }

    /**
     * {@code changeUserHandle} 对**还没有 Person 行**的真人也要能用。
     *
     * <p>这不是边界情况: 补号 runner 只给"有伴侣的 owner"建过 Person, 所以 {@code users} 里
     * 相当一部分真人没有 Person 行 —— 对他们来说, 第一次打开改号页就是他们第一次拥有 Person。
     * 直接 {@code requireByUserId} 会让这些人收到 404 而不是一个账号ID。
     */
    @Test
    void aUserWithNoPersonRowYetGetsOneOnFirstUse() {
        String uid = UUID.randomUUID().toString();

        HandleQuota first = personService.changeUserHandle(uid, h("newcomer"));

        assertNotNull(first.handle());
        assertEquals(1, first.used());
        assertNotNull(personService.requireByUserId(uid), "第一次改号应当顺手把 Person 行建出来");
    }

    // ── 配额 ──────────────────────────────────────────────────────────────

    @Test
    void changingToTheSameValueIsNotAChangeAndCostsNothing() {
        Person a = newUser();
        String handle = a.getHandle();

        HandleQuota q = personService.changeHandle(a.getId(), handle);

        assertEquals(3, q.remaining(), "原样提交不该扣掉一年三次里的一次");
        assertEquals(0, q.used());
        assertEquals(handle, q.handle());
    }

    @Test
    void theShapeIsNormalizedRatherThanRejected() {
        Person a = newUser();
        String wanted = h("xiaoman");

        HandleQuota q = personService.changeHandle(a.getId(), "  " + wanted.toUpperCase() + "  ");

        assertEquals(wanted, q.handle(), "大写和空白应当被归一, 而不是报错");
    }

    @Test
    void threeChangesAreAllowedAndTheFourthIsRefusedWith429() {
        Person a = newUser();
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
        Person a = newUser();
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
        Person a = newUser();
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
        Person a = newUser();
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
        Person a = newUser();
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

    // ── 系统重铸(存量迁移) ───────────────────────────────────────────────

    /**
     * 重铸是**系统**改号, 所以它不受"Agent 不可改号"那道闸门约束 —— 那道闸门挡的是
     * 用户发起的改号。区分这两件事的是"谁在发起", 不是"改的是谁"。
     */
    @Test
    void remintingGivesAnAgentALegacyHandleThePrefixAndKeepsTheOldOnRecord() {
        Person agent = newAgent("小满");
        // 模拟存量: 手工把号改成旧形状(无前缀的 10 位随机)
        String legacy = Handles.generate(new java.util.Random(42L));
        Person stale = personService.requireById(agent.getId());
        stale.setHandle(legacy);
        persons.save(stale);

        Person reminted = personService.remintAgentHandle(agent.getId());

        assertTrue(Handles.isAgentHandle(reminted.getHandle()), "重铸后必须带前缀");
        assertNotEquals(legacy, reminted.getHandle(), "必须真的换了一个号");
        assertEquals(reminted.getHandle(), personService.requireById(agent.getId()).getHandle(),
                "落库的必须是新号");

        // 旧号必须留在流水里 —— 那是"别人手里那个旧地址"的唯一线索
        boolean recorded = changes.findAll().stream().anyMatch(c ->
                agent.getId().equals(c.getPersonId())
                        && legacy.equals(c.getOldHandle())
                        && reminted.getHandle().equals(c.getNewHandle()));
        assertTrue(recorded, "重铸必须留下 old → new 的流水");
    }

    @Test
    void remintingIsRefusedForNonAgents() {
        Person me = newUser();

        assertThrows(IllegalStateException.class,
                () -> personService.remintAgentHandle(me.getId()),
                "这条路径只给存量 Agent 用, 用在人身上应当立刻炸而不是静默改号");
    }
}
