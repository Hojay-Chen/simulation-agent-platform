package com.luxera.companion.persona;

import com.luxera.companion.common.BusinessException;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipRepository;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class CompanionService {

    private final CompanionRepository companions;
    private final PersonaService personaService;
    private final PersonaCompiler compiler;
    private final LifeEventRepository lifeEvents;
    private final RelationshipRepository relationships;
    private final AgentStateRepository agentStates;
    private final com.luxera.companion.person.PersonService personService;
    private final com.luxera.companion.relationship.RelationshipService relationshipService;

    public CompanionService(CompanionRepository companions, PersonaService personaService,
                            PersonaCompiler compiler, LifeEventRepository lifeEvents,
                            RelationshipRepository relationships, AgentStateRepository agentStates,
                            com.luxera.companion.person.PersonService personService,
                            com.luxera.companion.relationship.RelationshipService relationshipService) {
        this.companions = companions;
        this.personaService = personaService;
        this.compiler = compiler;
        this.lifeEvents = lifeEvents;
        this.relationships = relationships;
        this.agentStates = agentStates;
        this.personService = personService;
        this.relationshipService = relationshipService;
    }

    public Persona compile(String description) {
        return compiler.compile(description);
    }

    public String preview(Persona persona, String scenario) {
        return compiler.preview(persona, scenario);
    }

    @Transactional
    public Companion create(String userId, Persona persona) {
        return create(userId, persona, null);
    }

    /** 创建伴侣 —— 同时建立 Person 身份层 + 按关系类型初始化真实关系状态 */
    @Transactional
    public Companion create(String userId, Persona persona, String relationshipType) {
        return create(userId, persona, relationshipType, null, null);
    }

    /**
     * 创建伴侣, 并且把"它在聊天平台用哪个账号"与"是哪个客户端建的"一起记下来。
     *
     * <p>多出来的两个参数都是**可空的跨平台痕迹**, 不加它们也能建出 agent —— 但那样
     * 聊天平台一键创建的 agent 就永远是"哑的": 它在聊天平台有自己的账号, 而本仓不知道
     * 那个账号是哪个, 于是既不能把消息记到正确的 sender 上, 也没法按聊天账号反查。
     *
     * <ul>
     *   <li>{@code chatAccountId} —— 聊天平台的 {@code users.id}。唯一约束在数据库上,
     *       幂等性由此而来(见 {@code Companion#chatAccountId})。</li>
     *   <li>{@code createdByClientId} —— 哪个 API 客户端建的。真人经聊天平台创建时为空。</li>
     * </ul>
     *
     * <p>{@code userId} 仍然是"归谁所有"的唯一答案 —— 代建时它是**真人**, 不是客户端。
     */
    @Transactional
    public Companion create(String userId, Persona persona, String relationshipType,
                            String chatAccountId, String createdByClientId) {
        compiler.fillDefaults(persona);
        Persona p = persona;
        Companion c = new Companion();
        applyIdentity(c, p);
        c.setUserId(userId);
        c.setChatAccountId(chatAccountId);
        c.setCreatedByClientId(createdByClientId);
        companions.save(c);

        personaService.saveInitial(c.getId(), p);
        seedLifeEvents(c.getId(), p);

        // §四/§七: Person 身份层(用户与 Agent 都是 Person)
        personService.getOrCreateUser(userId);
        personService.getOrCreateAgent(c);

        // §五/§六: 关系 —— 用户选择的关系类型是 Agent 世界的真实状态
        Relationship rel = new Relationship();
        rel.setUserId(userId);
        rel.setCompanionId(c.getId());
        rel.setUserPersonId(userId);
        rel.setAgentPersonId(c.getId());
        String type = relationshipType != null && relationshipType.isBlank() ? null : relationshipType;
        if (type == null) {
            type = p.getRelationship() != null && StringUtils.hasText(p.getRelationship().getType())
                    ? p.getRelationship().getType() : "friend";
        }
        relationshipService.initByType(rel, type);
        relationships.save(rel);

        AgentState st = new AgentState();
        st.setCompanionId(c.getId());
        agentStates.save(st);
        return c;
    }

    @Transactional
    public PersonaVersion updatePersona(String userId, String companionId, String description, String reason) {
        Companion c = requireOwned(userId, companionId);
        Persona p = compiler.compile(description);
        applyIdentity(c, p);
        companions.save(c);
        lifeEvents.deleteByCompanionId(companionId);
        seedLifeEvents(companionId, p);
        return personaService.update(companionId, p, StringUtils.hasText(reason) ? reason : "用户重新描述", "user");
    }

    @Transactional(readOnly = true)
    public Companion requireOwned(String userId, String companionId) {
        Companion c = companions.findById(companionId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("伴侣不存在"));
        if (!c.getUserId().equals(userId) || c.getDeletedAt() != null) {
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND, "伴侣不存在", null);
        }
        return c;
    }

    @Transactional(readOnly = true)
    public List<Companion> list(String userId) {
        return companions.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(userId);
    }

    /**
     * 一个 API 客户端看得见的 agent —— **只给 8092 用**。
     *
     * <p>与 {@link #list(String)} 分开而不是让 8092 也调那一个: 那一个的判据是
     * {@code user_id = 自己}, 而代建出来的 agent 的 {@code user_id} 是**真人的**,
     * 于是聊天平台建的 agent 会在建它的那个客户端的列表里凭空消失 —— 建完就"查无此 agent",
     * 而这个失败只在真的走一遍一键创建时才看得见。判据的三个分支见
     * {@code CompanionRepository#findVisibleToClient}。
     *
     * <p>它**只用于读**: 写路径仍走 {@link #requireOwned}, 所以代建的 agent 看得见、
     * 改不动也删不掉 —— 代建不等于拥有。
     */
    @Transactional(readOnly = true)
    public List<Companion> listVisibleToClient(String clientId) {
        return companions.findVisibleToClient(clientId);
    }

    /** 可见集里的单条 —— 与 {@link #listVisibleToClient(String)} 同一个判据(见仓储里的常量)。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Companion> findVisibleToClient(String clientId, String agentId) {
        return companions.findVisibleToClientById(clientId, agentId);
    }

    /**
     * 按聊天账号找 agent —— 一键创建的**幂等键**。
     *
     * <p>故意把已删除的也带回来(而不是过滤掉): 调用方需要区分"这个账号还没建过"与
     * "建过但后来被删了", 而这两种情况要给的答复完全不同 —— 后者绝不能悄悄复活一个
     * 用户已经删掉的 agent。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Companion> findByChatAccountId(String chatAccountId) {
        return companions.findByChatAccountId(chatAccountId);
    }

    /**
     * 全部活着的 Agent —— 对账 runner 的输入。
     *
     * <p>与 {@link #list(String)} 的区别是判据: 那一个按 {@code user_id}(谁的好友),
     * 这一个不按任何人 —— 补铸和对账是**平台级**的活, 它们要问的是"这个平台上现在有哪些
     * Agent", 而不是"某个人有哪些 Agent"。
     *
     * <p>已删除的不在里面: 一个用户删掉的 Agent 不该被补一个聊天账号, 它该做的是让
     * 聊天平台那边把残留清干净(那是 {@code AgentRetirementService} 与
     * {@code GhostChatSweeper} 的活)。
     */
    @Transactional(readOnly = true)
    public List<Companion> listLive() {
        return companions.findByDeletedAtIsNullOrderByCreatedAtAsc();
    }

    /**
     * 还没有聊天账号的活 Agent —— 补铸 runner 的输入, 见
     * {@code CompanionRepository#findByDeletedAtIsNullAndChatAccountIdIsNullOrderByCreatedAtAsc}。
     */
    @Transactional(readOnly = true)
    public List<Companion> listMissingChatAccount() {
        return companions.findByDeletedAtIsNullAndChatAccountIdIsNullOrderByCreatedAtAsc();
    }

    /**
     * 把一个**已经存在的**聊天账号登记给一个**已经存在的** agent —— 补铸走的那条路。
     *
     * <h2>它不是 {@link #create} 的一个分支, 因为顺序恰好相反</h2>
     *
     * {@code create} 是"先有 agent, 后有账号"(第三方自助创建时根本没有账号)。
     * 这里是"先有账号, 后有 agent"的**补录**: 聊天平台为一批早就存在的 agent 铸了账号,
     * 现在回来把对应关系记上。它不碰人格、不碰关系、不碰任何其他列 —— 只写一列。
     *
     * <h2>三道闸, 每一道都在挡一种"两份记录对不上"</h2>
     *
     * <ol>
     *   <li><b>agent 必须存在且活着</b> —— 已删除的不补。给一个用户已经删掉的 agent 补账号,
     *       等于让它在聊天平台上重新有身份; 而 {@code simulate_devices} 那边也会跟着复活。</li>
     *   <li><b>已经是同一个值 → 原样返回</b>。补铸 runner 是可重跑的, 第二次跑必然撞上
     *       这一条 —— 它必须成功, 而不是 409。幂等在这里不是礼貌, 是"重跑安全"的全部内容。</li>
     *   <li><b>已经是**别的**值 → 抛错</b>。一个 agent 只通过一个聊天账号说话, 这不是
     *       "后写的赢"那种可以覆盖的字段: 改掉它, 那个 agent 之前发出去的消息就归到了
     *       另一个身份名下, 而那些消息已经在库里了。换账号只能换一个 agent。</li>
     * </ol>
     *
     * <p>第四种情况 —— 这个聊天账号已经挂在**另一个** agent 上 —— 由
     * {@code companions.chat_account_id} 的唯一约束兜底(它连已删除的行一起管),
     * 所以这里先查一次是为了给出一句说得清的话, 而不是为了让数据库不报错。
     *
     * @throws javax.persistence.EntityNotFoundException agent 不存在或已删除
     * @throws BusinessException 409 —— 这个 agent 已经有别的账号, 或这个账号已经归了别人
     */
    @Transactional
    public Companion attachChatAccount(String companionId, String chatAccountId) {
        if (chatAccountId == null || chatAccountId.isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "chatAccountId 不能为空", null);
        }
        Companion c = companions.findById(companionId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("伴侣不存在"));
        if (c.getDeletedAt() != null) {
            // 与 requireOwned 同样报 404: 在 HTTP 边界上不区分"不存在"与"已删除"
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "伴侣不存在", null);
        }

        if (chatAccountId.equals(c.getChatAccountId())) {
            return c;   // 重跑命中: 已经是这个值了, 成功
        }
        if (c.getChatAccountId() != null) {
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                    "这个 Agent 已经有聊天账号了",
                    "一个 Agent 只通过一个聊天账号说话; 换账号要换一个 Agent");
        }

        java.util.Optional<Companion> taken = companions.findByChatAccountId(chatAccountId);
        if (taken.isPresent() && !taken.get().getId().equals(companionId)) {
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                    "这个聊天账号已经登记给别的 Agent 了",
                    "agent " + taken.get().getId());
        }

        c.setChatAccountId(chatAccountId);
        companions.save(c);
        return c;
    }

    /**
     * 「这个 Agent 是不是我的, 而且已经删了」—— 给删除接口的**重试**用。
     *
     * <p>{@link #requireOwned} 把"不存在"、"不是我的"、"已删除"三种情况统一报成 404, 这对
     * 正常的读取路径是对的(不泄露"存在但不是你的")。但删除路径需要把第三种单独认出来:
     * 上次删到一半(本仓删成了、聊天平台没清干净), 用户再点一次删除时, 我们要能接着做收尾,
     * 而不是回他一个 404 把他卡死在半个状态上。
     *
     * <p>归属照样查 —— 这个判断不能变成"谁都能触发销毁"。
     */
    @Transactional(readOnly = true)
    public boolean ownsDeleted(String userId, String companionId) {
        return companions.findById(companionId)
                .map(c -> c.getUserId().equals(userId) && c.getDeletedAt() != null)
                .orElse(false);
    }

    /**
     * 软删除 —— 只把 {@code deleted_at} 写上, <b>本仓其他表和聊天平台一概不动</b>。
     *
     * <p>名字里的 soft 是认真的: 它**不是**"删除这个 Agent"的完整含义。完整的退役是
     * {@code AgentRetirementService#retire}, 那里才会清活队列、才会把聊天平台的会话连消息
     * 一起销毁。
     *
     * <p>这个方法之所以单独留着、而且必须保持这么薄, 是因为 8092(openapi)也调它 ——
     * 那个进程的扫描白名单刻意只有 persona 闭包的薄件, 连 {@code phone} 包的仓储都不注册
     * (见 {@code AgentOpenApiApplication} 的注释与 {@code check-agent.sh} 的边界守卫)。
     * 任何往这里加的依赖都会把认知链拖进 8092, 或者直接把 8092 启动搞崩。第三方 API 的
     * 语义本来就是"软删"(见 {@code OpenApiAgentController} 的接口说明), 所以那边到此为止
     * 是对的; 漏下的残骸由 {@code GhostChatSweeper} 兜底。
     *
     * <p>界线的另一半: {@link #ownsDeleted} + {@code AgentRetirementService} 才是平台
     * 真人侧的删除入口。
     */
    @Transactional
    public void softDelete(String userId, String companionId) {
        Companion c = requireOwned(userId, companionId);
        c.setDeletedAt(LocalDateTime.now());
        companions.save(c);
    }

    public Persona getPersona(String companionId) {
        return personaService.getActive(companionId);
    }

    public List<PersonaVersion> listPersonaVersions(String companionId) {
        return personaService.history(companionId);
    }

    /** 用户分享的真实生活事件(设计文档 §33: USER_SHARED_EVENT 来源) */
    @Transactional
    public void recordUserSharedLifeEvent(String companionId, String title, String description) {
        if (title == null || title.isBlank()) return;
        LifeEvent ev = new LifeEvent();
        ev.setCompanionId(companionId);
        ev.setType("user_shared");
        ev.setTitle(title);
        ev.setDescription(description);
        ev.setStartTime(LocalDate.now());
        ev.setImportance(0.55);
        ev.setEmotionalSignificance(0.5);
        ev.setSource("USER_SHARED_EVENT");
        lifeEvents.save(ev);
    }

    public List<LifeEvent> listLifeEvents(String companionId) {
        return lifeEvents.findByCompanionIdOrderByStartTimeAsc(companionId);
    }

    // ── 内部 ─────────────────────────────────

    private void applyIdentity(Companion c, Persona p) {
        Persona.Identity idn = p.getIdentity();
        c.setName(idn.getName());
        c.setGender(idn.getGender());
        if (StringUtils.hasText(idn.getBirthDate())) {
            c.setBirthDate(LocalDate.parse(idn.getBirthDate()));
        }
        c.setBirthPlace(idn.getBirthPlace());
        c.setNationality(idn.getNationality());
        c.setTimezone(idn.getTimezone());
        c.setGreeting("你好呀,我是" + idn.getName() + "。很高兴认识你,往后的日子我都在。");
    }

    private void seedLifeEvents(String companionId, Persona p) {
        List<Persona.LifeEventDto> events = p.getLife() != null ? p.getLife().getEvents() : null;
        if (events == null || events.isEmpty()) {
            events = defaultLifeEvents(p.getIdentity().getBirthDate());
        }
        for (Persona.LifeEventDto e : events) {
            if (e.getTitle() == null || e.getTitle().isBlank()) continue;
            LifeEvent ev = new LifeEvent();
            ev.setCompanionId(companionId);
            ev.setType(e.getType() != null ? e.getType() : "experience");
            ev.setSubtype(e.getSubtype());
            ev.setTitle(e.getTitle());
            ev.setDescription(e.getDescription());
            ev.setStartTime(parseDate(e.getStartTime()));
            ev.setEndTime(parseDate(e.getEndTime()));
            ev.setImportance(e.getImportance() != null ? e.getImportance() : 0.5);
            ev.setEmotionalSignificance(e.getEmotionalSignificance() != null ? e.getEmotionalSignificance() : 0.5);
            ev.setSource("persona");
            lifeEvents.save(ev);
        }
    }

    /** 当编译器没给出人生事件时,按年龄生成基础时间线 */
    private List<Persona.LifeEventDto> defaultLifeEvents(String birthDateStr) {
        List<Persona.LifeEventDto> result = new ArrayList<>();
        LocalDate birth;
        try {
            birth = LocalDate.parse(birthDateStr);
        } catch (Exception e) {
            return result;
        }
        int age = LocalDate.now().getYear() - birth.getYear();
        addEvent(result, "education", "primary", "上小学", birth.plusYears(6), birth.plusYears(12), 0.6, 0.4);
        addEvent(result, "education", "secondary", "上中学", birth.plusYears(12), birth.plusYears(18), 0.65, 0.5);
        if (age >= 18) {
            addEvent(result, "education", "university", "进入大学", birth.plusYears(18), birth.plusYears(22), 0.8, 0.7);
        }
        if (age >= 22) {
            addEvent(result, "career", "first_job", "第一份工作", birth.plusYears(22), null, 0.72, 0.6);
        }
        return result;
    }

    private void addEvent(List<Persona.LifeEventDto> list, String type, String subtype, String title,
                          LocalDate start, LocalDate end, double importance, double emotional) {
        if (start.isAfter(LocalDate.now())) return;
        Persona.LifeEventDto e = new Persona.LifeEventDto();
        e.setType(type);
        e.setSubtype(subtype);
        e.setTitle(title);
        e.setStartTime(start.toString());
        e.setEndTime(end == null ? null : end.toString());
        e.setImportance(importance);
        e.setEmotionalSignificance(emotional);
        list.add(e);
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s) || "null".equalsIgnoreCase(s)) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
