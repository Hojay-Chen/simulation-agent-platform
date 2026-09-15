package com.luxera.agentopenapi.agent;

import com.luxera.agentopenapi.client.OpenApiClientRecord;
import com.luxera.agentopenapi.config.OpenApiAuthFilter;
import com.luxera.companion.persona.Companion;
import com.luxera.companion.persona.CompanionService;
import com.luxera.companion.state.AgentState;
import com.luxera.companion.state.AgentStateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用面(G4 最小闭环) —— 三方读自己 agent 的实时状态。
 *
 * <p>openapi 是纯数据面: 不代发消息、不触发认知(那是 server:8091 的职责,
 * 消息注入通道留给 G7 联调时按 server 实际消费能力定)。三方能拿到的是
 * agent 的当前内在状态 —— 情绪/亲密度/困倦 —— 全部直读表, server 的认知链
 * 在同一张表上持续更新, 两边不争不抢。
 */
@Tag(name = "openapi-agent-state", description = "agent 实时状态(客户端 API Key 面)")
@RestController
@RequestMapping("/api/v1/openapi/agents/{agentId}/state")
public class OpenApiAgentStateController {

    private final CompanionService companionService;
    private final AgentStateRepository agentStates;

    public OpenApiAgentStateController(CompanionService companionService,
                                       AgentStateRepository agentStates) {
        this.companionService = companionService;
        this.agentStates = agentStates;
    }

    @Operation(summary = "读 agent 当前内在状态(情绪/亲密度/困倦 — server 认知链持续更新)")
    @GetMapping
    public ResponseEntity<Map<String, Object>> state(HttpServletRequest request,
                                                    @PathVariable String agentId) {
        Object attr = request.getAttribute(OpenApiAuthFilter.ATTR_CLIENT);
        if (!(attr instanceof OpenApiClientRecord client)) {
            return ResponseEntity.status(401).build();
        }
        try {
            Companion c = companionService.requireOwned(client.getId(), agentId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", c.getId());
            m.put("name", c.getName());
            // 无行 = 认知链尚未初始化(server 起来后第一条消息触发) —— 空状态而非错误
            agentStates.findByCompanionId(agentId).ifPresent(st -> {
                m.put("mood", st.getMood());
                m.put("emotionalCloseness", st.getEmotionalCloseness());
                m.put("sleepiness", st.getSleepiness());
            });
            m.put("note", st(agentId));
            return ResponseEntity.ok(m);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static String st(String agentId) {
        return agentId.isEmpty() ? "" : "state 由 server(8091) 认知链持续更新; 缺字段 = 尚未初始化";
    }
}
