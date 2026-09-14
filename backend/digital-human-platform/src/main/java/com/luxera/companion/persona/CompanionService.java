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
        compiler.fillDefaults(persona);
        Persona p = persona;
        Companion c = new Companion();
        applyIdentity(c, p);
        c.setUserId(userId);
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

    @Transactional
    public void delete(String userId, String companionId) {
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
