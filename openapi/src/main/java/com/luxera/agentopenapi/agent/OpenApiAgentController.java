package com.luxera.agentopenapi.agent;

import com.luxera.agentopenapi.client.OpenApiClientRecord;
import com.luxera.agentopenapi.config.OpenApiAuthFilter;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.persona.Persona;
import com.luxera.companion.persona.PersonaService;
import com.luxera.companion.persona.PersonaVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端面 —— 三方创建、删除、管理、使用自己的仿真 agent。
 *
 * <p><b>归属模型</b>: agent 落 {@code companions} 表时 {@code user_id} = 客户端 id。
 * 仓 1 的 {@code CompanionDirectoryPort.requireOwned(userId, companionId)} 因此
 * 天然工作, server:8091 的认知链照常把它当自己的 agent 推进 —— 跨服务零改动,
 * 这是 G4 选"直接复用 companions 表"而非另开一张 agents 表的全部理由。
 *
 * <p>所有端点先经 {@code OpenApiAuthFilter}(Bearer sap_...) — controller 里
 * 从 request attribute 取已验的客户端, 归属断言走 {@code CompanionService.requireOwned}
 * (clientId 当 userId, 与真人同一条校验路径)。
 *
 * <h2>代建: {@code ownerUserId} 与 {@code chatAccountId}</h2>
 *
 * 这条链路原先只能"客户端给自己建 agent"({@code user_id = clientId})。聊天平台的一键创建
 * 需要另一种形状: **以某个真人的名义**建一个 agent, 并记下它在聊天平台用哪个账号说话。
 * 两个新字段各自对应其中一半 —— 它们只改 {@code create}, 其余端点一个字未动。
 *
 * <p><b>为什么 {@code ownerUserId} 需要一道闸门。</b>不加限制的话, 任何持 {@code sap_} key
 * 的程序都能往任意用户的通讯录里塞一个归他所有、他自己却删不掉的 agent。所以服务端只在
 * 客户端被标记可信({@code openapi_clients.can_act_for_users}, 默认 false)时才认这个字段,
 * 否则 403 —— 闸门在下面的 {@code create} 里是**一行能单独读出来的判断**, 不是藏在某个
 * service 里的隐含行为。
 *
 * <p><b>代建 ≠ 拥有。</b>代建出来的 agent 的 {@code user_id} 是**真人的**, 只有
 * {@code created_by_client_id} 写客户端。于是它在客户端的列表里看得见(它确实是这个客户端
 * 建的), 却过不了 {@code requireOwned} —— 改不动也删不掉。那个 agent 属于那个真人。
 */
@Tag(name = "openapi-agents", description = "仿真 agent 全生命周期(客户端 API Key 面)")
@RestController
@RequestMapping("/api/v1/openapi/agents")
public class OpenApiAgentController {

    private final CompanionService companionService;
    private final PersonaService personaService;
    private final com.luxera.companion.person.PersonService personService;

    public OpenApiAgentController(CompanionService companionService, PersonaService personaService,
                                  com.luxera.companion.person.PersonService personService) {
        this.companionService = companionService;
        this.personaService = personaService;
        this.personService = personService;
    }

    private static OpenApiClientRecord caller(HttpServletRequest request) {
        Object c = request.getAttribute(OpenApiAuthFilter.ATTR_CLIENT);
        if (!(c instanceof OpenApiClientRecord client)) {
            throw new IllegalStateException("过滤器没有放行客户端身份 —— 不该到达这里");
        }
        return client;
    }

    @Operation(summary = "创建 agent",
            description = "二选一: {description} 走平台编译链(自然语言→人格), 或 {persona} 直传已编译人格。"
                    + " 可带 {chatAccountId}(聊天平台的账号 id — 同一账号重复调用返回同一个 agent, 幂等)"
                    + " 与 {ownerUserId}(代建给某个真人 — 需要客户端被标记为可信)。")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                     @RequestBody CreateAgentBody body) {
        OpenApiClientRecord client = caller(request);
        if ((body.description() == null || body.description().isBlank())
                && body.persona() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "description 与 persona 必须给一个"));
        }

        // ── 闸门: 代建需要可信标记 ──────────────────────────────────────────
        // 单独一行、单独一条注释 —— 它是这个端点上唯一的授权判断, 混进下面那段业务逻辑里
        // 就再也读不出来了。默认 false 意味着"新登记的客户端一律没有这个能力"。
        boolean actingForUser = hasText(body.ownerUserId());
        if (actingForUser && !client.canActForUsers()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "这个 API Key 没有代建的权限",
                    "hint", "ownerUserId 只能在被平台标记为可信的客户端上使用"));
        }

        // ── 幂等: 同一个聊天账号 → 同一个 agent ─────────────────────────────
        // 这条判断必须在建之前: "建了但响应丢了"是一键创建最常见的失败, 重试若走到
        // 下面那行 create, 唯一约束会把它拦成一个 500 —— 而正确的答复是"已经建好了,
        // 这就是它"。返回 200(不是 201): 这一次调用没有创建任何东西。
        if (hasText(body.chatAccountId())) {
            Companion existing = companionService.findByChatAccountId(body.chatAccountId()).orElse(null);
            if (existing != null) {
                if (!client.getId().equals(existing.getCreatedByClientId())) {
                    // 不是本客户端建的 —— 连 agentId 都不能回, 那是在告诉 A "B 的 agent 存在"
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "error", "这个聊天账号已经登记过 agent"));
                }
                if (existing.getDeletedAt() != null) {
                    // 绝不悄悄复活一个已经被删掉的 agent。带上 chat_account_id 的唯一约束
                    // 还在那一行上, 所以这里也不能改建一个新的 —— 只能让调用方知道。
                    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                            "error", "这个聊天账号的 agent 已被删除",
                            "hint", "换一个聊天账号, 或先恢复那个 agent"));
                }
                return ResponseEntity.ok(toDto(client, existing));
            }
        }

        Persona persona = body.persona() != null
                ? body.persona()
                : companionService.compile(body.description());
        String relationship = body.relationshipType() == null || body.relationshipType().isBlank()
                ? "friend" : body.relationshipType();
        // 归属: 代建时 owner 是真人的 id(闸门已在上面放行), 否则维持"客户端即所有者"
        String owner = actingForUser ? body.ownerUserId() : client.getId();
        // created_by_client_id 恒写 clientId —— 它是"谁建的", 与"归谁"是两个问题(见 Companion)
        Companion c = companionService.create(owner, persona, relationship,
                body.chatAccountId(), client.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(client, c));
    }

    @Operation(summary = "列自己的 agents",
            description = "含本客户端代建的(那些 agent 归真人所有, 但看得见)。")
    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        OpenApiClientRecord client = caller(request);
        // 判据与 requireOwned 不同, 三个分支的理由见 CompanionRepository#findVisibleToClient
        return companionService.listVisibleToClient(client.getId()).stream()
                .map(c -> toDto(client, c)).toList();
    }

    /**
     * agent 详情(含人格)。
     *
     * <p>读的判据是 {@code listVisibleToClient} 那一套(而不是 {@code requireOwned}),
     * 好让代建出来的 agent 也读得到; 而下面 {@code updatePersona} / {@code delete}
     * 两条写路径**仍然**走 {@code requireOwned}, 于是代建的 agent 看得见、改不动。
     * 这个不对称是刻意的 —— 见类注释最后一段。
     */
    @Operation(summary = "agent 详情(含人格)")
    @GetMapping("/{agentId}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request,
                                                   @PathVariable String agentId) {
        OpenApiClientRecord client = caller(request);
        Companion c = companionService.findVisibleToClient(client.getId(), agentId).orElse(null);
        if (c == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> dto = toDto(client, c);
        dto.put("persona", personaService.getActive(agentId));
        return ResponseEntity.ok(dto);
    }

    @Operation(summary = "更新人格 — {description} 重编译, 落 persona_versions 新版本")
    @PutMapping("/{agentId}/persona")
    public ResponseEntity<Map<String, Object>> updatePersona(HttpServletRequest request,
                                                             @PathVariable String agentId,
                                                             @RequestBody UpdatePersonaBody body) {
        OpenApiClientRecord client = caller(request);
        if (body.description() == null || body.description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "description 不能为空"));
        }
        try {
            PersonaVersion version = companionService.updatePersona(
                    client.getId(), agentId, body.description(),
                    body.reason() == null ? "OpenAPI 更新" : body.reason());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("agentId", agentId);
            resp.put("versionId", version.getId());
            resp.put("persona", personaService.getActive(agentId));
            return ResponseEntity.ok(resp);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 软删 agent —— 只写 {@code deleted_at}。
     *
     * <p>方法论上这里**做不到**更彻底: 完整退役要清 {@code phone} 包与
     * {@code runtime.pipeline} 包的活队列、还要经 {@code ChatWorldPort} 销毁聊天平台的会话,
     * 而这三样在本进程里一律不存在 —— 本服务的扫描白名单刻意只有 persona 闭包的薄件
     * (见 {@code AgentOpenApiApplication} 的注释与 {@code check-agent.sh} 的边界守卫),
     * 完整退役因此住在 server 侧、openapi 扫不到的 {@code AgentRetirementService} 里。
     *
     * <p>所以本接口的语义就是它文档里写的那一个 —— 软删。第三方删掉的 agent 留下的聊天
     * 残骸由 {@code GhostChatSweeper} 兜底(它按 {@code deleted_at is not null} 全表对账,
     * 不区分是谁删的)。
     */
    @Operation(summary = "软删 agent(deleted_at — 与平台真人侧同语义)")
    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String agentId) {
        OpenApiClientRecord client = caller(request);
        try {
            companionService.softDelete(client.getId(), agentId);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── dto ──────────────────────────────────────────────────────────────

    /**
     * agent 的对外形状。
     *
     * <p>{@code agentId} / {@code chatAccountId} / {@code handle} 三个都在, 因为它们是**三件
     * 不同的事**(agent 平台的个体 id / 聊天平台的账号 id / 人念得出来的账号ID), 而调用方
     * 恰恰最容易把它们当成一个。放在一起, 名字又各不相同, 读的人就没有机会混淆。
     *
     * <p>{@code chatAccountId} / {@code handle} 可空: 第三方自助创建的 agent 没有聊天账号,
     * 而 handle 在补号 runner 跑过之前可能还没有。
     */
    private Map<String, Object> toDto(OpenApiClientRecord client, Companion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", c.getId());
        m.put("clientId", client.getId());
        m.put("chatAccountId", c.getChatAccountId());
        m.put("handle", personService.handleOfCompanion(c.getId()));
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        return m;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 请求体 —— 与仓 1 的 {@code AgentRegistrationRequest} 同形。
     *
     * <p>{@code description} / {@code persona} 二选一; {@code chatAccountId} 与
     * {@code ownerUserId} 是给"代建"用的(见类注释), 普通客户端不传它们就是原来的行为。
     */
    public record CreateAgentBody(String description, Persona persona, String relationshipType,
                                  String chatAccountId, String ownerUserId) {}
    public record UpdatePersonaBody(String description, String reason) {}
}
