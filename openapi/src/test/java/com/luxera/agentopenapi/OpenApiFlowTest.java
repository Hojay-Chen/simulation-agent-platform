package com.luxera.agentopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G4 全链 —— 建客户端(发钥匙) → 用钥匙建/读/改/删 agent 的闭环。
 *
 * <p>鉴权矩阵与归属模型一起验:
 * <ol>
 *   <li>管理面: 无 X-Admin-Key 401; 错钥 401; 对钥 201 且明文 key 只出现这一次。</li>
 *   <li>客户端面: 无 Bearer 401; 伪造 sap_ 钥 401; 真钥全通。</li>
 *   <li>归属隔离: 客户端 A 建的 agent, 客户端 B 读不到(404) —— 这是"三方只能动
 *       自己的 agent"的判据, 也是 user_id=clientId 归属模型的存在理由。</li>
 *   <li>吊销: revoke 后同一把 key 立即 401。</li>
 * </ol>
 *
 * <p>profile=test 钉住 LLM Mock 降级与测试库; openapi 不扫认知链包,
 * PersonaCompiler 走 LlmRouter 的 mock 网关 —— 离线全绿。
 */
@ActiveProfiles("test")
@SpringBootTest(classes = AgentOpenApiApplication.class)
@AutoConfigureMockMvc
class OpenApiFlowTest {

    private static final String ADMIN = "test-openapi-admin-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── 鉴权矩阵 ─────────────────────────────────────────────────────────

    @Test
    void adminFaceRequiresAdminKey() throws Exception {
        mockMvc.perform(post("/api/v1/openapi/clients")
                        .contentType("application/json")
                        .content("{\"name\":\"no-key\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/openapi/clients")
                        .header("X-Admin-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentFaceRequiresBearerKey() throws Exception {
        mockMvc.perform(get("/api/v1/openapi/agents"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer sap_forged"))
                .andExpect(status().isUnauthorized());
    }

    // ── 全链闭环 ─────────────────────────────────────────────────────────

    @Test
    void fullLifecycle_createListReadUpdateDelete() throws Exception {
        String clientA = createClient(unique("flow-a"));
        String clientB = createClient(unique("flow-b"));

        // 建: description 走编译链(Mock LLM)
        MvcResult created = mockMvc.perform(post("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"description\":\"一个开朗的测试 agent\",\"relationshipType\":\"friend\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode agent = MAPPER.readTree(created.getResponse().getContentAsString());
        String agentId = agent.get("agentId").asText();
        assertFalse(agentId.isEmpty(), "建 agent 必须返回 id");
        assertNotNull(agent.get("name").asText());

        // 列: A 有, B 空
        MvcResult listA = mockMvc.perform(get("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk()).andReturn();
        assertTrue(MAPPER.readTree(listA.getResponse().getContentAsString()).size() >= 1);
        MvcResult listB = mockMvc.perform(get("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + clientB))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, MAPPER.readTree(listB.getResponse().getContentAsString()).size(),
                "B 不能看到 A 的 agent");

        // 读: A 通, B 404(归属隔离)
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId)
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId)
                        .header("Authorization", "Bearer " + clientB))
                .andExpect(status().isNotFound());

        // 状态读: A 通(可能缺字段 = 认知链没初始化, 正常), B 404
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId + "/state")
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId + "/state")
                        .header("Authorization", "Bearer " + clientB))
                .andExpect(status().isNotFound());

        // 改: 重编译落新版本
        mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/persona")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"description\":\"一个安静了很多的测试 agent\",\"reason\":\"G4 测试更新\"}"))
                .andExpect(status().isOk());

        // 删: A 通, 再读 404
        mockMvc.perform(delete("/api/v1/openapi/agents/" + agentId)
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId)
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedKeyImmediatelyStopsWorking() throws Exception {
        String plaintext = createClient(unique("revoked-client"));
        // 建一个 agent 证明 key 可用
        mockMvc.perform(post("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + plaintext)
                        .contentType("application/json")
                        .content("{\"description\":\"将被孤立的 agent\"}"))
                .andExpect(status().isCreated());
        // 吊销(需要 clientId — 从列表反查)
        MvcResult list = mockMvc.perform(get("/api/v1/openapi/clients")
                        .header("X-Admin-Key", ADMIN)).andReturn();
        String clientId = MAPPER.readTree(list.getResponse().getContentAsString())
                .get(0).get("clientId").asText();
        mockMvc.perform(delete("/api/v1/openapi/clients/" + clientId)
                        .header("X-Admin-Key", ADMIN))
                .andExpect(status().isNoContent());
        // 同一把 key 立即 401
        mockMvc.perform(get("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void docsAndSpecArePublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());

        // 只断言 "/docs 状态码 < 400" 是不够的 —— /docs 是个 302, 302 本来就
        // < 400, 于是"落点吃了 403"这种坏法完全测不出来。G7 部署时就真吃了
        // 这个亏: Swagger UI 从 G4 起就一直打不开(springdoc 的 /docs 302 到
        // /swagger-ui/index.html, 而安全配置只放行了 /docs)。
        // 所以这里必须跟着跳转, 断言**落点**可用。
        MvcResult docs = mockMvc.perform(get("/docs")).andExpect(status().is3xxRedirection()).andReturn();
        String target = docs.getResponse().getRedirectedUrl();
        assertNotNull(target, "/docs 应当 302 到文档 UI 的落点");
        assertTrue(target.contains("swagger-ui"),
                "/docs 的落点变了(实得 " + target + ") —— 安全配置里的放行清单要跟着改");

        // 落点本身必须可用 —— 这条才是真正的断言
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    // ── helper ───────────────────────────────────────────────────────────

    /** 建客户端并返回明文 API Key(仅创建响应里有)。 */
    /** 每个测试独立名字(测试库持久, update 不清数据, 固定名会 409)。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private String unique(String name) {
        return name + "-" + System.nanoTime() % 1000000 + "-" + SEQ.incrementAndGet();
    }

    private String createClient(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/openapi/clients")
                        .header("X-Admin-Key", ADMIN)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("apiKey").asText();
    }
}
