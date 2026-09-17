package com.luxera.companion.person;

import com.luxera.companion.auth.User;
import com.luxera.companion.auth.UserRepository;
import com.luxera.companion.common.BusinessException;
import com.luxera.companion.persona.Companion;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * §五 Person 服务: User/Agent 的 Person 身份层。
 * id 与 users.id / companions.id 一致(零迁移), OTHER 人物独立生成。
 *
 * <p>账号ID(handle)的分配、校验与改号配额也在这里 —— 它是 Person 的属性, 而
 * {@code persons} 正是"用户与 Agent 都是人"的那一层。**刻意不拆出第二个 service**:
 * 8092(openapi)的组件扫描白名单里, {@code person} 包只有本类一个名字(见
 * {@code AgentOpenApiApplication} 的 includeFilters), 拆出去的类在那个进程里不会被注册,
 * 而本类若依赖它就会让 8092 起不来。这不是假想的风险 —— 2026-09-17 给
 * {@code CompanionService} 加依赖时正是这么把 8092 弄挂的。
 */
@Service
public class PersonService {

    /** 「每年三次」—— 窗口是滑动的 365 天, 不是自然年, 理由见 {@link PersonHandleChange} */
    public static final int MAX_HANDLE_CHANGES_PER_YEAR = 3;
    private static final int HANDLE_WINDOW_DAYS = 365;

    /** 生成撞车的重试次数。31^10 的空间里撞一次已经罕见, 撞满说明随机源坏了 */
    private static final int MINT_ATTEMPTS = 8;

    private final PersonRepository repo;
    private final UserRepository userRepository;
    private final PersonHandleChangeRepository handleChanges;
    /** 账号ID 的随机源可注入 —— 测试要重放出确定的值 */
    private final Random random = new SecureRandom();

    public PersonService(PersonRepository repo, UserRepository userRepository,
                         PersonHandleChangeRepository handleChanges) {
        this.repo = repo;
        this.userRepository = userRepository;
        this.handleChanges = handleChanges;
    }

    // ── 取/建 ────────────────────────────────────────────────────────────

    /** 取/建用户的 Person(幂等) */
    @Transactional
    public Person getOrCreateUser(String userId) {
        return repo.findByUserId(userId).orElseGet(() -> {
            Person p = new Person();
            p.setId(userId);
            p.setPersonType(Person.TYPE_USER);
            p.setUserId(userId);
            String name = "用户";
            try {
                User u = userRepository.findById(userId).orElse(null);
                if (u != null) {
                    name = u.getNickname() != null && !u.getNickname().isBlank()
                            ? u.getNickname() : u.getUsername();
                }
            } catch (Exception ignored) { }
            p.setName(name);
            p.setHandle(mintUniqueHandle(Handles::generate));
            p.setMetadata(Map.of("source", "user"));
            return repo.save(p);
        });
    }

    /** 取/建伴侣(Agent)的 Person(幂等), id 沿用 companion.id */
    @Transactional
    public Person getOrCreateAgent(Companion companion) {
        return repo.findByCompanionId(companion.getId()).orElseGet(() -> {
            Person p = new Person();
            p.setId(companion.getId());
            p.setPersonType(Person.TYPE_AGENT);
            p.setCompanionId(companion.getId());
            p.setName(companion.getName());
            p.setHandle(mintUniqueHandle(agentHandleGenerator()));
            p.setGender(companion.getGender());
            p.setMetadata(Map.of("source", "companion"));
            return repo.save(p);
        });
    }

    /** 新建 OTHER 人物(数字人的社会关系) —— **不发账号ID**, 理由见 {@link PersonRepository} */
    @Transactional
    public Person createOther(String name, String gender, Map<String, Object> metadata) {
        Person p = new Person();
        p.setPersonType(Person.TYPE_OTHER);
        p.setName(name);
        p.setGender(gender);
        p.setMetadata(metadata);
        return repo.save(p);
    }

    // ── 账号ID ───────────────────────────────────────────────────────────

    /**
     * 给还没有账号ID 的人补一个 —— 幂等。
     *
     * <p>由 {@code PersonHandleBackfill} 在启动时对老数据调用: 这一列是后加的, 而加列时
     * 库里已经有人了(`ddl-auto: update` 加不了 NOT NULL 列, 见 {@link Person#getHandle()})。
     */
    @Transactional
    public Person ensureHandle(Person p) {
        if (p.getHandle() != null && !p.getHandle().isBlank()) {
            return p;
        }
        p.setHandle(mintHandleFor(p));
        return repo.save(p);
    }

    /**
     * 给某个 Person 铸号的**唯一入口** —— 前缀按类型分流。
     *
     * <p>放在一处而不是让每个调用点自己挑前缀: "AGENT 的号必须带 {@code agent_}"是需求,
     * 不是某个调用点的偏好。分散着写, 迟早会有一个新调用点漏掉前缀, 而那正是
     * {@code AgentHandleMigration} 要花力气去修的那种不一致。
     */
    private String mintHandleFor(Person p) {
        return Person.TYPE_AGENT.equals(p.getPersonType())
                ? mintUniqueHandle(agentHandleGenerator())
                : mintUniqueHandle(Handles::generate);
    }

    /** Agent 号的生成器 —— 把"带前缀"这条形状规则留给 {@link Handles} 定义。 */
    private java.util.function.Supplier<String> agentHandleGenerator() {
        return () -> Handles.generateAgentHandle(random);
    }

    /**
     * 改账号ID —— 唯一性 + 每年三次配额。
     *
     * <p>顺序是有讲究的: 先校验形状(用户能立刻改对的东西), 再查占用, **最后**才查配额。
     * 反过来会让"形状写错了"的用户收到"你的配额用完了" —— 一个他既改不动、又和眼前问题
     * 无关的坏消息。
     *
     * <p>改成和当前**完全一样**的值: 不是错误, 也**不消耗配额**。用户在输入框里原样提交
     * 是很常见的事(点开设置随手按了保存), 为这个扣掉一年三次里的一次, 是那种用户会记很久
     * 的不公平。
     */
    @Transactional
    public HandleQuota changeHandle(String personId, String rawHandle) {
        Person p = requireById(personId);
        // 类型闸门放在**形状校验之前**。顺序不是随意的: 一个根本改不动的东西,
        // 先收到"你格式写错了"是一句既无用又误导的坏消息 —— 用户会去改格式,
        // 然后再被同一个 403 挡一次。
        //
        // 为什么 Agent 不能改: 它的账号ID 是**系统分配的标识**, 不是它自己的门牌。
        // 一个 Agent 换号会让三样东西同时失准 —— 别人手里那个旧号指向谁、
        // 消息流水里那条 sender_id 讲的是谁、以及"agent_ 前缀 = 系统发的号"这条
        // 一眼可读的规则。人的号可以改(那是他的门牌, 每年三次), Agent 的不行。
        requireUserChosen(p);
        String wanted = Handles.validate(rawHandle);
        if (wanted.equals(p.getHandle())) {
            return quotaOf(p);
        }
        repo.findByHandle(wanted).ifPresent(occupant -> {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "账号ID「" + wanted + "」已经被占用了",
                    "可以试试 " + Handles.suggestVariant(wanted, random));
        });

        HandleQuota before = quotaOf(p);
        if (before.remaining() <= 0) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "账号ID 每 365 天最多修改 " + MAX_HANDLE_CHANGES_PER_YEAR + " 次，你已经用完了",
                    "下次可改时间：" + before.nextChangeAt().toLocalDate());
        }

        PersonHandleChange rec = new PersonHandleChange();
        rec.setPersonId(personId);
        rec.setOldHandle(p.getHandle());
        rec.setNewHandle(wanted);
        rec.setChangedAt(LocalDateTime.now());
        handleChanges.save(rec);

        p.setHandle(wanted);
        repo.save(p);
        // 重新算一次, 让返回值里 used 已经是改完之后的数 —— 否则前端刷新一下才发现少了一次
        return quotaOf(p);
    }

    /**
     * 本人改自己的账号ID —— 浏览器那条路的入口。
     *
     * <p>与 {@link #changeHandle(String, String)} 的区别只在**怎么找到那个人**: 那个方法收的是
     * personId, 而 HTTP 层手里只有登录用户的 {@code userId}。这里做的
     * {@code getOrCreateUser} 不只是"查一下" —— 它是幂等的取/建, 因为
     * {@code users} 里有相当一部分真人**还没有 Person 行**(补号 runner 只给"有伴侣的 owner"
     * 建过 Person)。对这些人来说, 第一次打开改号页就是他们第一次拥有 Person 行,
     * 不在这里建, 他们就会收到一个 404 而不是一个账号ID。
     */
    @Transactional
    public HandleQuota changeUserHandle(String userId, String rawHandle) {
        return changeHandle(getOrCreateUser(userId).getId(), rawHandle);
    }

    /** 只有 USER 能自选账号ID —— 见 {@code changeHandle} 里的闸门注释。 */
    private void requireUserChosen(Person p) {
        if (!Person.TYPE_USER.equals(p.getPersonType())) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "Agent 的聊天账号ID 由系统分配，不能修改",
                    "它是这个 Agent 的标识，不是可以自己取的名字");
        }
    }

    /** 某个人的账号ID 配额现状。{@code nextChangeAt} 只在额度用尽时非空。 */
    @Transactional(readOnly = true)
    public HandleQuota quotaOf(String personId) {
        return quotaOf(requireById(personId));
    }

    // ── 系统重铸(存量迁移专用) ─────────────────────────────────────────────

    /**
     * 把某个 Agent 的账号ID **换成**一个带 {@code agent_} 前缀的新号。给存量迁移用。
     *
     * <p>与 {@link #changeHandle} 的三点不同, 每一点都是有意的:
     *
     * <ol>
     *   <li><b>不校验形状也不查占用</b> —— 新号是系统铸的, 不是用户填的, 走
     *       {@code mintUniqueHandle} 那条构造性的路。拿 {@code Handles.validate} 去验会
     *       把自己刚铸出来的号判成非法(前缀按设计就过不了人自选那道校验)。</li>
     *   <li><b>不受"Agent 不可改号"约束</b> —— 那条闸门挡的是**用户**对 Agent 发起的改号,
     *       而这里改号的正是系统。区分这两件事的正是"谁在发起", 不是"改的是谁"。</li>
     *   <li><b>不查配额</b> —— 配额是给人的(每年三次)。Agent 那条闸门已经让它永远用不到,
     *       再查一次只会把一个恒为 3 的值搬来搬去。</li>
     * </ol>
     *
     * <p>但仍然写 {@link PersonHandleChange} 流水: 旧号是"报给过别人的地址", 换掉之后
     * 那条线索必须留得住 —— 这正是那张表存在的第一个理由(见它的类注释)。
     */
    @Transactional
    public Person remintAgentHandle(String personId) {
        Person p = requireById(personId);
        if (!Person.TYPE_AGENT.equals(p.getPersonType())) {
            throw new IllegalStateException(
                    "remintAgentHandle 只用于 Agent, 拿到的是 " + p.getPersonType());
        }
        String old = p.getHandle();
        String fresh = mintUniqueHandle(agentHandleGenerator());

        PersonHandleChange rec = new PersonHandleChange();
        rec.setPersonId(personId);
        rec.setOldHandle(old);
        rec.setNewHandle(fresh);
        rec.setChangedAt(LocalDateTime.now());
        handleChanges.save(rec);

        p.setHandle(fresh);
        return repo.save(p);
    }

    private HandleQuota quotaOf(Person p) {
        List<PersonHandleChange> recent = handleChanges
                .findByPersonIdAndChangedAtAfterOrderByChangedAtDesc(
                        p.getId(), LocalDateTime.now().minusDays(HANDLE_WINDOW_DAYS));
        LocalDateTime nextChangeAt = null;
        if (recent.size() >= MAX_HANDLE_CHANGES_PER_YEAR) {
            // 窗口内已经改满。最早能再改的时刻 = 这三次里**最早那次**滑出窗口的时候 ——
            // 那一刻之后窗口内只剩两次, 也就腾出了一次额度。
            // recent 是时间倒序, 所以"这三次里最早那次"是第 MAX 个(下标 MAX-1)。
            nextChangeAt = recent.get(MAX_HANDLE_CHANGES_PER_YEAR - 1).getChangedAt()
                    .plusDays(HANDLE_WINDOW_DAYS);
        }
        return HandleQuota.of(p.getHandle(), recent.size(), MAX_HANDLE_CHANGES_PER_YEAR, nextChangeAt);
    }

    /** 单个 Agent 的账号ID。取不到 / 还没补号时返回 null —— 界面显示空, 不是崩。 */
    @Transactional(readOnly = true)
    public String handleOfCompanion(String companionId) {
        return repo.findByCompanionId(companionId).map(Person::getHandle).orElse(null);
    }

    /**
     * 一批 Agent 的账号ID —— 列表页要显示每一行的账号ID。
     *
     * <p>批次接口不是优化, 是**避免一种必然发生的写法**: 有了单条版本, 列表页就会写成
     * 循环里逐条取, 而列表页恰恰是唯一会长到几十行的地方。一次 {@code IN} 查询取完,
     * 调用方就没有机会写错。
     */
    @Transactional(readOnly = true)
    public Map<String, String> handlesOfCompanions(Collection<String> companionIds) {
        if (companionIds == null || companionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byCompanion = new HashMap<>();
        for (Person p : repo.findByCompanionIdIn(companionIds)) {
            if (p.getHandle() != null && !p.getHandle().isBlank()) {
                byCompanion.put(p.getCompanionId(), p.getHandle());
            }
        }
        return byCompanion;
    }

    // ── 查询 ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Person requireByCompanionId(String companionId) {
        return repo.findByCompanionId(companionId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("伴侣的 Person 不存在"));
    }

    @Transactional(readOnly = true)
    public Person requireByUserId(String userId) {
        return repo.findByUserId(userId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("用户的 Person 不存在"));
    }

    @Transactional(readOnly = true)
    public Person requireById(String personId) {
        return repo.findById(personId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("Person 不存在"));
    }

    /**
     * 造一个当前没被占用的账号ID。
     *
     * <p>生成与查重循环若干次; 循环用尽仍撞车就抛异常而不是返回一个重复值 ——
     * 重复的账号ID 会由数据库唯一约束拦下, 但那时抛出来的是一个约束冲突,
     * 排查的人要绕一圈才知道"随机源坏了"。在这里说清楚。
     */
    private String mintUniqueHandle(java.util.function.Supplier<String> generator) {
        for (int i = 0; i < MINT_ATTEMPTS; i++) {
            String candidate = generator.get();
            if (repo.findByHandle(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "连续 " + MINT_ATTEMPTS + " 次生成的账号ID都已被占用 —— 随机源或数据量异常");
    }
}
