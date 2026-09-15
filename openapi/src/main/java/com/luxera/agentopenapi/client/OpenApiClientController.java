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
            description = "管理密钥面。响应里的 apiKey 不会再次出现 —— 丢失只能吊销重建。")
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
        record.setApiKeyHash(key.hash());
        record.setApiKeyPrefix(key.prefix());
        record.setStatus(OpenApiClientRecord.STATUS_ACTIVE);
        OpenApiClientRecord saved = clients.save(record);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("clientId", saved.getId());
        resp.put("name", saved.getName());
        resp.put("apiKey", key.plaintext());     // 仅此一次
        resp.put("apiKeyPrefix", key.prefix());
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
                    m.put("createdAt", c.getCreatedAt().toString());
                    return m;
                }).toList();
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

    public record CreateClientBody(String name, String ownerUserId) {}
}
