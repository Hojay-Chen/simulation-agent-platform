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

    // ── Agent 开关的客户端面 ──────────────────────────────────────────────

    /**
     * 第三方程序停得下自己的 agent, 而且**停不等于删**。
     *
     * <p>缺这条路由的话, 一个程序想让自己的 agent 歇一会儿, 唯一的手段是 {@code DELETE} ——
     * 而那个手段会连记忆一起作废, 且 {@code chat_account_id} 的唯一约束意味着同一个聊天
     * 账号再也建不出第二个 agent。所以这一条不是"多一个便利方法", 是"停下"这件事在
     * 客户端面上唯一的表达方式。
     */
    @Test
    void aClientCanStopAndRestartItsOwnAgent() throws Exception {
        String clientA = createClient(unique("lifecycle-a"));
        String clientB = createClient(unique("lifecycle-b"));

        MvcResult created = mockMvc.perform(post("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"description\":\"一个会被第三方自己停掉的 agent\"}"))
                .andExpect(status().isCreated()).andReturn();
        String agentId = MAPPER.readTree(created.getResponse().getContentAsString()).get("agentId").asText();

        // 刚建出来是活的 —— 规范化字段永远在, 第三方不必自己去解释 status 的原值
        assertEquals("active", readAgent(clientA, agentId).get("lifecycle").asText());

        // 停
        MvcResult paused = mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/lifecycle")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"lifecycle\":\"paused\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode p = MAPPER.readTree(paused.getResponse().getContentAsString());
        assertEquals("paused", p.get("lifecycle").asText());
        assertTrue(p.get("changed").asBoolean());

        // 幂等: 再停一次, changed=false 而不是报错
        MvcResult again = mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/lifecycle")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"lifecycle\":\"PAUSED\"}"))
                .andExpect(status().isOk()).andReturn();
        assertFalse(MAPPER.readTree(again.getResponse().getContentAsString()).get("changed").asBoolean(),
                "大小写宽松, 而重复的那一次不该报又停了一遍");

        // 停 ≠ 删: 详情、状态、列表全都还在
        assertEquals("paused", readAgent(clientA, agentId).get("lifecycle").asText());
        mockMvc.perform(get("/api/v1/openapi/agents/" + agentId + "/state")
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk());
        MvcResult list = mockMvc.perform(get("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + clientA))
                .andExpect(status().isOk()).andReturn();
        assertTrue(MAPPER.readTree(list.getResponse().getContentAsString()).size() >= 1);

        // 归属: B 停不了 A 的 agent(404), 而不是"停成功了"
        mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/lifecycle")
                        .header("Authorization", "Bearer " + clientB)
                        .contentType("application/json")
                        .content("{\"lifecycle\":\"active\"}"))
                .andExpect(status().isNotFound());
        assertEquals("paused", readAgent(clientA, agentId).get("lifecycle").asText(),
                "B 的那次尝试必须一点作用都没有");

        // 打错的值回 400 而不是被猜成 active —— 猜的话症状是"我明明按了停, 它还在烧 token"
        for (String bad : new String[]{"stop", "", "sleeping"}) {
            mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/lifecycle")
                            .header("Authorization", "Bearer " + clientA)
                            .contentType("application/json")
                            .content("{\"lifecycle\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals("paused", readAgent(clientA, agentId).get("lifecycle").asText(),
                "被拒的请求不该留下任何痕迹");

        // 继续
        MvcResult resumed = mockMvc.perform(put("/api/v1/openapi/agents/" + agentId + "/lifecycle")
                        .header("Authorization", "Bearer " + clientA)
                        .contentType("application/json")
                        .content("{\"lifecycle\":\"active\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(MAPPER.readTree(resumed.getResponse().getContentAsString()).get("changed").asBoolean());
        assertEquals("active", readAgent(clientA, agentId).get("lifecycle").asText());
    }

    // ── Agent 开关的运维面(/admin) ────────────────────────────────────────

    /**
     * {@code /api/v1/openapi/admin/**} 必须吃管理钥, **客户端钥不算**。
     *
     * <p>这条是安全问题而不是路由洁癖: `pause-all` 会让**全平台的 agent 停工** ——
     * 包括别的客户端、别的用户的。判成客户端面的话, 任何持有一把 {@code sap_} 钥匙的
     * 三方程序都能一键把整个平台停掉。而鉴权判错在这套 filter 里的表现不是报错,
     * 是"用错了钥匙却拿到了 200"。
     *
     * <p>第二条断言反过来说同一件事: 那把**真的有效**的客户端钥在这里必须 401 ——
     * 一个只测"没带钥匙 401"的用例, 对"客户端面吃掉了管理面"这种坏法是瞎的。
     */
    @Test
    void theAgentSwitchOpsFaceRequiresTheAdminKeyNotAClientKey() throws Exception {
        String clientKey = createClient(unique("ops-face"));

        mockMvc.perform(post("/api/v1/openapi/admin/agents/pause-all"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/openapi/admin/agents/pause-all")
                        .header("X-Admin-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/openapi/admin/agents/lifecycle"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/openapi/admin/agents/pause-all")
                        .header("Authorization", "Bearer " + clientKey))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/openapi/admin/agents/lifecycle")
                        .header("Authorization", "Bearer " + clientKey))
                .andExpect(status().isUnauthorized());

        // 同一个资源名放在客户端面下, 就不再是管理面 —— 这两条将来若被合并成一个
        // 前缀, 上面那些断言会一起失效, 所以这里顺手把现状钉住
        mockMvc.perform(get("/api/v1/openapi/administrators")
                        .header("Authorization", "Bearer " + clientKey))
                .andExpect(status().isNotFound());
    }

    /**
     * 运维面的开关真的关得掉、也真的开得回来, 而且**关掉不等于删掉**。
     *
     * <p>最后那三条断言是重点: 一个停着的 agent 的详情、状态、记忆都还应当读得到。
     * 若暂停被实现成"列表里看不见了", 用户会以为自己的 agent 被删了 —— 而这个功能
     * 的全部承诺就是"停止不删除任何东西"。
     */
    @Test
    void pauseAllStopsEveryoneAndResumeAllBringsThemBack() throws Exception {
        String key = createClient(unique("switch-ops"));
        MvcResult created = mockMvc.perform(post("/api/v1/openapi/agents")
                        .header("Authorization", "Bearer " + key)
                        .contentType("application/json")
                        .content("{\"description\":\"一个会被暂停的 agent\"}"))
                .andExpect(status().isCreated()).andReturn();
        String agentId = MAPPER.readTree(created.getResponse().getContentAsString()).get("agentId").asText();

        try {
            MvcResult paused = mockMvc.perform(post("/api/v1/openapi/admin/agents/pause-all")
                            .header("X-Admin-Key", ADMIN))
                    .andExpect(status().isOk()).andReturn();
            JsonNode after = MAPPER.readTree(paused.getResponse().getContentAsString());
            assertEquals("paused", after.get("action").asText());
            assertTrue(after.get("changed").asInt() >= 1, "至少要停掉刚建的那一个");
            assertTrue(after.get("platform").get("paused").asInt() >= 1);

            // 再按一次: changed 归零, 而 paused 仍然是那些 —— 幂等操作要能自证
            MvcResult again = mockMvc.perform(post("/api/v1/openapi/admin/agents/pause-all")
                            .header("X-Admin-Key", ADMIN))
                    .andExpect(status().isOk()).andReturn();
            JsonNode second = MAPPER.readTree(again.getResponse().getContentAsString());
            assertEquals(0, second.get("changed").asInt(),
                    "第二次按不该报又停了一遍 —— changed 与 pausedBefore 并置就是为了这一条");
            assertTrue(second.get("pausedBefore").asInt() >= 1);

            // 停止 ≠ 删除: 详情与状态照常读得到, 列表里也还在
            mockMvc.perform(get("/api/v1/openapi/agents/" + agentId)
                            .header("Authorization", "Bearer " + key))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/openapi/agents/" + agentId + "/state")
                            .header("Authorization", "Bearer " + key))
                    .andExpect(status().isOk());
            MvcResult list = mockMvc.perform(get("/api/v1/openapi/agents")
                            .header("Authorization", "Bearer " + key))
                    .andExpect(status().isOk()).andReturn();
            assertTrue(MAPPER.readTree(list.getResponse().getContentAsString()).size() >= 1,
                    "被暂停的 agent 必须还在列表里 —— 消失会被读成「被删了」");
        } finally {
            // 恢复现场: 这是一个改**全平台**状态的测试, 不还原的话后面的测试(以及
            // 本类之外的一切)都会在"全部暂停"下跑, 而那种失败看起来毫无来由。
            mockMvc.perform(post("/api/v1/openapi/admin/agents/resume-all")
                            .header("X-Admin-Key", ADMIN))
                    .andExpect(status().isOk());
        }
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

    /** 读一个 agent 的详情, 走客户端面(带归属校验) —— 断言 lifecycle 就该从这里读。 */
    private JsonNode readAgent(String key, String agentId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/v1/openapi/agents/" + agentId)
                        .header("Authorization", "Bearer " + key))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
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
