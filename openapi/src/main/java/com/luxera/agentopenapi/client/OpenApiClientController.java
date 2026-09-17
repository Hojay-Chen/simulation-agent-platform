package com.luxera.agentopenapi.client;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面 —— 建客户端(发 API Key)。
 *
 * <p>{@code X-Admin-Key} 全局管理密钥({@code OPENAPI_ADMIN_KEY} env)。这是
 * "发钥匙"的动作: 一把管理钥换任意把客户端钥。明文 key 只在创建响应里出现一次,
 * 之后再没有任何途径取回(库里只有 sha256)。
 */
@Tag(name = "openapi-clients", description = "机器客户端管理(管理密钥面)")
@RestController
@RequestMapping("/api/v1/openapi/clients")
public class OpenApiClientController {

    private final OpenApiClientRepository clients;

    public OpenApiClientController(OpenApiClientRepository clients) {
        this.clients = clients;
    }

    @Operation(summary = "建客户端, 返回明文 API Key(仅此一次)",
            description = "管理密钥面。响应里的 apiKey 不会再次出现 —— 丢失只能吊销重建。"
                    + " canActForUsers=true 表示允许这个客户端代建(创建 agent 时指定 ownerUserId), 默认 false。")
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateClientBody body) {
        if (body.name() == null || body.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 不能为空"));
        }
        if (clients.existsByName(body.name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "客户端名已存在: " + body.name()));
        }
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();
        OpenApiClientRecord record = new OpenApiClientRecord();
        record.setName(body.name());
        record.setOwnerUserId(body.ownerUserId());
        // 只认显式的 true —— 不传(以及传 null)都是不可代建。默认关闭是这一列的语义, 而
        // "没写 = 开着"是权限设计里最常见的错误
        record.setCanActForUsers(Boolean.TRUE.equals(body.canActForUsers()));
        record.setApiKeyHash(key.hash());
        record.setApiKeyPrefix(key.prefix());
        record.setStatus(OpenApiClientRecord.STATUS_ACTIVE);
        OpenApiClientRecord saved = clients.save(record);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("clientId", saved.getId());
        resp.put("name", saved.getName());
        resp.put("apiKey", key.plaintext());     // 仅此一次
        resp.put("apiKeyPrefix", key.prefix());
        resp.put("canActForUsers", saved.canActForUsers());
        resp.put("notice", "apiKey 只在本次响应出现, 请妥善保存; 丢失请吊销重建");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(summary = "列客户端(不含密钥)")
    @GetMapping
    public List<Map<String, Object>> list() {
        return clients.findAllByStatusOrderByCreatedAtDesc(OpenApiClientRecord.STATUS_ACTIVE)
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clientId", c.getId());
                    m.put("name", c.getName());
                    m.put("apiKeyPrefix", c.getApiKeyPrefix());
                    m.put("ownerUserId", c.getOwnerUserId());
                    m.put("canActForUsers", c.canActForUsers());
                    m.put("createdAt", c.getCreatedAt().toString());
                    return m;
                }).toList();
    }

    /**
     * 开/关一个客户端的代建权限 —— **不需要换钥**。
     *
     * <p>单独一个端点而不是"改不了, 只能吊销重建": 代建权限是会被收回的(那把 key 泄漏了、
     * 或者某个集成方不再需要它)。若收回只能靠吊销, 调用方会为了避免换钥而干脆一直开着它 ——
     * 一个只能开不能关的开关, 实际上等于永远是开的。
     *
     * <p>它只影响**此后**的创建请求: 已经代建出来的 agent 归属不变(那些 agent 属于真人,
     * 归属本来就不由这个开关决定)。
     */
    @Operation(summary = "开/关代建权限 — 立即生效, 不必换钥")
    @PutMapping("/{clientId}/can-act-for-users")
    public ResponseEntity<Map<String, Object>> setCanActForUsers(
            @PathVariable String clientId, @RequestBody CanActBody body) {
        return clients.findById(clientId).map(c -> {
            c.setCanActForUsers(Boolean.TRUE.equals(body.canActForUsers()));
            clients.save(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", c.getId());
            m.put("canActForUsers", c.canActForUsers());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "吊销客户端 — 其 API Key 立即失效, 其名下 agents 保留但不可再操作")
    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> revoke(@PathVariable String clientId) {
        return clients.findById(clientId).map(c -> {
            c.setStatus(OpenApiClientRecord.STATUS_REVOKED);
            clients.save(c);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    public record CreateClientBody(String name, String ownerUserId, Boolean canActForUsers) {}
    public record CanActBody(Boolean canActForUsers) {}
}
