package com.luxera.agentopenapi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * G2 骨架健康端点 —— 服务存活探针。
 * 对外的仿真 Agent OpenAPI 面(Rest + API Key + OpenAPI 3.1 spec)由 G4 落地。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "simulation-agent-openapi");
        return body;
    }
}
