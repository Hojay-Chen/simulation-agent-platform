package com.luxera.companion.persona;

import com.luxera.companion.common.BusinessException;
import com.luxera.companion.config.CurrentUser;
import com.luxera.companion.maintenance.AgentRetirementService;
import com.luxera.companion.relationship.Relationship;
import com.luxera.companion.relationship.RelationshipService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/companions")
public class CompanionController {

    private final CompanionService companionService;
    private final RelationshipService relationshipService;
    private final CurrentUser currentUser;
    /**
     * 账号ID 住在 {@code persons}(用户与 Agent 都是 Person), 所以由它来读写。
     *
     * <p>注入点选在**控制器**而不是 {@code CompanionService}, 是因为控制器只被 server(8091)
     * 注册 —— 8092(openapi)的扫描白名单里没有任何 {@code *Controller}。而
     * {@code CompanionService} 是两个进程共用的, 往它身上挂依赖要连带确认 8092 装得下
     * (2026-09-17 那次 {@code NoSuchBeanDefinitionException} 就是这么来的)。
     */
    private final com.luxera.companion.person.PersonService personService;
    /**
     * 完整的删除。刻意不是 {@code CompanionService#delete} —— 后者只是软删除, 因为
     * 8092(openapi)也调它而那个进程刻意没有认知链的依赖。见 {@code AgentRetirementService}。
     * 本控制器只被 server(8091)注册, 所以这里能拿到完整的那一个。
     */
    private final AgentRetirementService retirement;

    public CompanionController(CompanionService companionService,
                               RelationshipService relationshipService,
                               CurrentUser currentUser,
                               AgentRetirementService retirement,
                               com.luxera.companion.person.PersonService personService) {
        this.companionService = companionService;
        this.relationshipService = relationshipService;
        this.currentUser = currentUser;
        this.retirement = retirement;
        this.personService = personService;
    }

    /** 自然语言 → 编译人格 + 默认场景预览 */
    @PostMapping("/compile")
    public CompanionDtos.CompileResponse compile(@Valid @RequestBody CompanionDtos.CompileRequest req) {
        Persona persona = companionService.compile(req.getDescription());
        String preview = companionService.preview(persona, null);
        CompanionDtos.CompileResponse resp = new CompanionDtos.CompileResponse();
        resp.setPersona(persona);
        resp.setPreview(preview);
        return resp;
    }

    /** 任意场景预览 */
    @PostMapping("/preview")
    public CompanionDtos.PreviewResponse preview(@Valid @RequestBody CompanionDtos.PreviewRequest req) {
        CompanionDtos.PreviewResponse resp = new CompanionDtos.PreviewResponse();
        resp.setResponse(companionService.preview(req.getPersona(), req.getScenario()));
        return resp;
    }

    @GetMapping
    public List<CompanionDtos.CompanionDto> list() {
        String userId = currentUser.requireUserId();
        List<Companion> all = companionService.list(userId);
        // 账号ID 一次取完, 不是"每行再查一次" —— 通讯录正是唯一会长到几十行的那一屏,
        // 而 N+1 的写法在这里看起来和正确写法一模一样(见 PersonService#handlesOfCompanions)
        Map<String, String> handles = personService.handlesOfCompanions(
                all.stream().map(Companion::getId).toList());
        return all.stream().map(c -> toDto(userId, c, handles.get(c.getId()))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanionDtos.CompanionDto create(@Valid @RequestBody CompanionDtos.CreateRequest req) {
        if (req.getPersona() == null) {
            throw new IllegalArgumentException("persona 不能为空");
        }
        String userId = currentUser.requireUserId();
        Companion c = companionService.create(userId, req.getPersona(), req.getRelationshipType());
        return toDto(userId, c);
    }

    @GetMapping("/{id}")
    public CompanionDtos.CompanionDto get(@PathVariable String id) {
        String userId = currentUser.requireUserId();
        return toDto(userId, companionService.requireOwned(userId, id));
    }

    /**
     * 删除 Agent —— 两步, 顺序是本方法唯一重要的事。
     *
     * <p>第一步 {@code retirement.retire} 落定"它不再存在"（软删除 + 清本仓两条活队列,
     * 一个事务; 它**提交**了才轮到下一步）。第二步才把聊天平台的会话连消息一起销毁 ——
     * 那一下不可逆。两步为什么必须分开, 理由整段写在 {@code AgentRetirementService} 的
     * 类注释上; 一句话: <b>绝不为一个可能还活着的东西销毁历史</b>。
     *
     * <p>为什么这里要自己写这两步、而不是 {@code CompanionService.delete} 里一把做完:
     * 那个方法 8092(openapi)也在调, 而那个进程刻意没有认知链的依赖, 装不下完整退役。
     * 见 {@code CompanionService#softDelete}。
     *
     * <p>第二步失败时**不能**把整体报成成功。本仓这边删除已经生效（用户的意图达成了）, 但
     * 聊天列表里那个窗口还在 —— 客户端必须知道这件事没做完, 否则它和用户都以为干净了,
     * 而这正是这个功能原本的 bug。所以抛出去, 让人看见。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        String userId = currentUser.requireUserId();
        try {
            retirement.retire(userId, id);
        } catch (BusinessException e) {
            // 已经删过(且还是我的)? 那就**只补做**后面那两步 —— 这样"再点一次删除"是一条
            // 真的能把上次没做完的收尾做完的路, 而不是撞回一个 404 然后永远卡在半个状态上。
            // 归属仍然要查: 别人的 Agent 不能靠重放 DELETE 来触发销毁。
            // requireOwned 把"不存在"和"不是我的"统一报成 404, 所以这里必须显式再问一次。
            if (!companionService.ownsDeleted(userId, id)) throw e;
        }
        try {
            retirement.clearActiveQueues(id);
            retirement.purgeChatWorld(id);
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Agent 已删除, 但它的聊天记录未能清理干净", "再次删除同一个 Agent 即可继续清理");
        }
    }

    /** 重新描述 → 编译为新版本人格 */
    @PutMapping("/{id}/persona")
    public PersonaVersion updatePersona(@PathVariable String id,
                                        @RequestBody CompanionDtos.UpdatePersonaRequest req) {
        return companionService.updatePersona(currentUser.requireUserId(), id,
                req.getDescription(), req.getReason());
    }

    @GetMapping("/{id}/life-events")
    public List<CompanionDtos.LifeEventDto> lifeEvents(@PathVariable String id) {
        companionService.requireOwned(currentUser.requireUserId(), id);
        return companionService.listLifeEvents(id).stream()
                .map(e -> {
                    CompanionDtos.LifeEventDto dto = new CompanionDtos.LifeEventDto();
                    dto.setId(e.getId());
                    dto.setType(e.getType());
                    dto.setSubtype(e.getSubtype());
                    dto.setTitle(e.getTitle());
                    dto.setDescription(e.getDescription());
                    dto.setStartTime(e.getStartTime());
                    dto.setEndTime(e.getEndTime());
                    dto.setImportance(e.getImportance());
                    dto.setEmotionalSignificance(e.getEmotionalSignificance());
                    return dto;
                }).toList();
    }

    /** 人格版本历史(含演化记录) */
    @GetMapping("/{id}/persona/versions")
    public List<PersonaVersion> personaVersions(@PathVariable String id) {
        companionService.requireOwned(currentUser.requireUserId(), id);
        return companionService.listPersonaVersions(id);
    }

    /*
     * 这里**没有** `GET/PUT /{id}/handle` —— 它们随"Agent 的聊天账号ID 不可修改"一起删掉了。
     *
     * 删而不是留一个恒 403 的路由: 一个存在但永远拒绝的端点会诱使后来者把它重新打开
     * (它是这条规则最像"合理的实现位置"的地方), 而删掉之后语义是明确的 ——
     * **这个操作不存在**。真正的保证仍在 PersonService.changeHandle 的类型闸门里,
     * 端点不存在只是第二道防线(否则任何一个新加的调用点都能绕过它)。
     *
     * 读账号ID 的那条也一并删了, 但不是因为不可读 —— 而是因为它本来就多余:
     * `CompanionDto.handle` 已经带着账号ID(见下面的 toDto), 而它是通讯录每一行都在传的
     * 东西。设置页原先多打的那一次请求, 换来的只是同一个值。
     *
     * 真人改自己的账号ID 走 8091 的 `/api/persons/me/handle` —— 见 PersonController,
     * 那里解释了为什么主语必须是"me"而不是一个 id。
     */

    private CompanionDtos.CompanionDto toDto(String userId, Companion c) {
        return toDto(userId, c, personService.handleOfCompanion(c.getId()));
    }

    private CompanionDtos.CompanionDto toDto(String userId, Companion c, String handle) {
        CompanionDtos.CompanionDto dto = new CompanionDtos.CompanionDto();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setHandle(handle);
        dto.setGender(c.getGender());
        dto.setAge(c.getBirthDate() != null ? c.age() : null);
        dto.setBirthDate(c.getBirthDate());
        dto.setNextBirthday(c.getBirthDate() != null ? nextBirthday(c.getBirthDate()) : null);
        dto.setBirthPlace(c.getBirthPlace());
        dto.setNationality(c.getNationality());
        dto.setTimezone(c.getTimezone());
        dto.setGreeting(c.getGreeting());
        dto.setPersona(companionService.getPersona(c.getId()));
        dto.setCreatedAt(c.getCreatedAt());
        // 从实体上直接读, 不查库也不问 AgentSwitchService —— 状态就在 companions 行上,
        // 而这行刚刚才被查出来
        dto.setLifecycle(AgentLifecycle.of(c.getStatus()).wire());
        Relationship rel = relationshipService.find(userId, c.getId());
        if (rel != null) {
            dto.setRelationshipType(rel.getRelationshipType());
            dto.setRelationshipStage(rel.getRelationshipStage());
        }
        return dto;
    }

    private static LocalDate nextBirthday(LocalDate birth) {
        LocalDate today = LocalDate.now();
        LocalDate next = birth.withYear(today.getYear());
        if (!next.isAfter(today)) {
            next = next.plusYears(1);
        }
        return next;
    }
}
