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
 */
@Tag(name = "openapi-agents", description = "仿真 agent 全生命周期(客户端 API Key 面)")
@RestController
@RequestMapping("/api/v1/openapi/agents")
public class OpenApiAgentController {

    private final CompanionService companionService;
    private final PersonaService personaService;

    public OpenApiAgentController(CompanionService companionService, PersonaService personaService) {
        this.companionService = companionService;
        this.personaService = personaService;
    }

    private static OpenApiClientRecord caller(HttpServletRequest request) {
        Object c = request.getAttribute(OpenApiAuthFilter.ATTR_CLIENT);
        if (!(c instanceof OpenApiClientRecord client)) {
            throw new IllegalStateException("过滤器没有放行客户端身份 —— 不该到达这里");
        }
        return client;
    }

    @Operation(summary = "创建 agent",
            description = "二选一: {description} 走平台编译链(自然语言→人格), 或 {persona} 直传已编译人格。")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                     @RequestBody CreateAgentBody body) {
        OpenApiClientRecord client = caller(request);
        if ((body.description() == null || body.description().isBlank())
                && body.persona() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "description 与 persona 必须给一个"));
        }
        Persona persona = body.persona() != null
                ? body.persona()
                : companionService.compile(body.description());
        String relationship = body.relationshipType() == null || body.relationshipType().isBlank()
                ? "friend" : body.relationshipType();
        // user_id = clientId —— 归属模型见类注释
        Companion c = companionService.create(client.getId(), persona, relationship);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(client, c));
    }

    @Operation(summary = "列自己的 agents")
    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        OpenApiClientRecord client = caller(request);
        return companionService.list(client.getId()).stream()
                .map(c -> toDto(client, c)).toList();
    }

    @Operation(summary = "agent 详情(含人格)")
    @GetMapping("/{agentId}")
    public ResponseEntity<Map<String, Object>> get(HttpServletRequest request,
                                                   @PathVariable String agentId) {
        OpenApiClientRecord client = caller(request);
        try {
            Companion c = companionService.requireOwned(client.getId(), agentId);
            Map<String, Object> dto = toDto(client, c);
            dto.put("persona", personaService.getActive(agentId));
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
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

    private static Map<String, Object> toDto(OpenApiClientRecord client, Companion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", c.getId());
        m.put("clientId", client.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        return m;
    }

    public record CreateAgentBody(String description, Persona persona, String relationshipType) {}
    public record UpdatePersonaBody(String description, String reason) {}
}
