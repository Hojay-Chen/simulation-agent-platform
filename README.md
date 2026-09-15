# simulation-agent-platform — 仿真 Agent 平台（仓 2）

> 数字人的全部认知 / 生活 / 记忆 / 状态。它消费仓 1（[chat-platform](../chat-platform)）
> 发布的契约 artifact，通过 SPI 端口看聊天世界与应用世界 —— 不认识任何一个的具体实现。
> 由 companion-agent 物理拆分而来（G1 = 仓 1 Gradle 化，G2 = 本仓骨架 + DH 迁入）。

---

## 1. 两个服务（用户拍板的形态）

| 服务 | 目录 | 端口 | 职责 |
|---|---|---|---|
| **功能服务** | `server/` | 8091 | 拉起数字人全部认知链（41 个顶层包 + 40+ 定时任务），`SimulationAgentPlatformApplication` |
| **对外 OpenAPI 服务** | `openapi/` | 8092 | 供外部/三方创建、删除、管理、使用自己的仿真 agent。g4 起业务端点全量(API Key + OpenAPI 3.1 + /docs) |

两个启动类在各自项目目录下，不共用 —— 这是拆分的显式表达。

## 2. Gradle 多项目布局

```
settings.gradle            rootProject + include
build.gradle               版本口径: Spring Boot 2.7.18 / JDK 17 / mavenLocal + 阿里云
common/                    仓 2 公共件(kernel 类随迁, 见 §4)
backend/digital-human-platform/   DH 主体 —— 41 顶层包原样迁入
                           (目录名不改是 git log --follow 穿历史的硬前提)
server/                    功能服务(bootJar: simulation-agent-platform-1.0.0.jar)
openapi/                   对外 API 服务(bootJar: simulation-agent-openapi-1.0.0.jar)
scripts/check-agent.sh     边界守卫(grep 第一道防线)
```

Java 包名保持 `com.luxera.companion.*`（与仓 1 同一哲学：改包名零收益纯风险，拆分在 Gradle 项目边界与仓库边界上表达）。

## 3. 对外认知只有两条

- **契约 artifact**：`com.luxera:contract:1.0.0`（仓 1 执行 `gradle :contract:publishToMavenLocal` 发布）。`ChatWorldPort` / `ApplicationRuntimePort` / `SimulatorAccessPort` / `CompanionDirectoryPort` 等 SPI 端口与 `MessageView` 等 DTO 全部在那里 —— 本仓对 chat 世界与 LAP 的全部认知止于此。
- **本仓 common**：User/UserRepository（只读昵称）/AppProperties/CurrentUser/BusinessException/JsonCodec/3 个 Converter/Outbox 三件（消费侧）+ **G3 补入的 JwtUtil/JwtAuthenticationFilter**（chat 签发的 JWT 在 8091 同源验签——G2 遗漏，check-split 抓出）。

G3 起三个跨服务端口（ChatWorldPort/ApplicationRuntimePort/SimulatorAccessPort）由
`server/.../http/` 的 HTTP 客户端适配器承担（`HttpAdapterConfiguration` 装配），对
chat 平台(8081)的 `/internal/**` 面 HMAC 签名调用。占位哲学原样保留在适配器的缺席
语义里：读返回空、写诚实抛错、fire-and-forget 静默——chat 缺席时认知链照常运转。

## 4. 与仓 1 的关系

| 维度 | 约定 |
|---|---|
| 契约 | 仓 1 `contract` 项目 publishToMavenLocal → 本仓当普通外部依赖 |
| 数据库 | 同一个 PG `companion` 库；表不重叠、跨仓无 FK；`users` 表仓 1 写 / 本仓只读；`outbox_event` 仓 1 写 / 本仓 `OutboxRelayJob` 消费（跨进程可靠通道） |
| 服务发现 | 功能服务 8091（仓 1 yml `app.agent-platform.base-url` 默认值）；openapi 8092 |
| 服务间密钥 | `AGENT_PLATFORM_INTERNAL_KEY` 两仓注入同一值；HMAC-SHA256（`X-Lap-Timestamp`+`X-Lap-Signature` 签 `timestamp.body`），未配 → 双方 /internal 503 死端点 |
| 实时通道 | DHCP v1 WebSocket：本仓 `ChatSimulatorConnector`(客户端) → 仓 1 `/ws/simulator`(服务端)，`app.simulator.chat-ws-url` 指向 8081 |
| git 历史 | ours-merge 嫁接：本仓保有 companion-agent → chat-platform 全部提交链，`git log --follow` 可穿到单体时代（DH 路径名不变是硬前提） |

## 5. 构建与验证

```bash
# 前置(仓 1): cd chat-platform && gradle :contract:publishToMavenLocal
bash scripts/check-agent.sh          # 边界守卫
~/tools/gradle/gradle-8.14.3/bin/gradle test      # 309 测试(DH 全量 + common/server/openapi)
~/tools/gradle/gradle-8.14.3/bin/gradle :server:bootJar :openapi:bootJar
java -jar server/build/libs/simulation-agent-platform-1.0.0.jar    # 8091
java -jar openapi/build/libs/simulation-agent-openapi-1.0.0.jar    # 8092
```

测试用隔离的 `companion_test` 库（`application-test.yml` 随迁），与仓 1 测试库共用 —— 两仓测试串行跑。

## 6. 边界守卫（check-agent.sh）

1. **包归属互斥**：四个 Gradle 项目拥有的顶层包两两不相交（Java split package 会静默合并）；
2. **DH 不引用仓外世界**：DH 源码里的 `com.luxera.companion.*` import 只允许 `contracts` 与本仓四项目拥有的包（白名单从源码树推导，不写死）；
3. **common 不认识 DH**：底座不引用使用者的包；
4. **Gradle 依赖图**：server→{common,digital-human}、digital-human→common、common 零仓内依赖（只依赖 contract artifact）、openapi→{common,digital-human}（G4 起编译依赖 DH，但启动类 includeFilters 白名单只扫 persona 闭包薄件，认知链包不进 8092）。

## 7. 路线图（用户拍板的分轮）

**G1 仓 1 Gradle 化 → G2 仓 2 骨架+DH 迁移 ✅ → G3 跨服务 HTTP 化 ✅ → G4 OpenAPI 服务 ✅ → G5 聊天前端 ✅ → G6 Agent 管理前端 → G7 联调部署。**

- **G3（2026-09-15 完成）**：本仓 `ChatPlatformIntegration` 占位删除、三个端口
  （ChatWorldPort/ApplicationRuntimePort/SimulatorAccessPort）落 HTTP 客户端适配器
  （`server/.../http/` 包：`HttpChatWorldAdapter`/`HttpApplicationRuntimeAdapter`/
  `HttpSimulatorAccessAdapter`，读缺席→空、写缺席→抛、fire-and-forget 吞、token 缺→不连）；
  `InternalCompanionDirectoryController` 暴露 `/internal/directory/**` 给仓 1；
  HMAC 签名鉴权（`InternalSignature` + `InternalAuthFilter`，密钥
  `AGENT_PLATFORM_INTERNAL_KEY` 与仓 1 同值）。**check-split.sh 抓出两个 G2 隐性缺口并修复**：
  ① 仓 2 common 缺 `JwtUtil`/`JwtAuthenticationFilter`（"JWT 同源"只是设计意图，8091
  从没解析过 token——已补，同 io.jsonwebtoken 栈）；
  ② `processed_event.event_id` varchar(96) 装不下 143 字符的确定性认知事件 id
  （单进程时代这事件不落库——已扩到 255）。验收 `check-split.sh` S1-S8 全绿
  （仓 1 scripts/ 下，双服务同起、跨服务闭环、缺席韧性）。
- **G4（2026-09-15 完成）**：openapi:8092 业务端点全量落地。
  - **身份**：`openapi_clients` 表（机器客户端，不复用 users）；API Key 格式 `sap_<64 hex>`，
    只在创建时明文返回一次，库存 sha256。管理面 `X-Admin-Key`（`OPENAPI_ADMIN_KEY` env，
    未配 → 503 死端点）+ 客户端面 `Authorization: Bearer sap_...`，`OpenApiAuthFilter`
    在 Spring Security 链内先验两把钥匙。
  - **Agent 域**：复用 `companions` 表，user_id = clientId —— 仓 1
    `CompanionDirectoryPort.requireOwned` 天然工作，认知链照常推进，跨服务零改动。
  - **端点**：`POST /api/v1/openapi/clients`（发钥匙）、`POST/GET/PUT/DELETE
    /api/v1/openapi/agents...`（建/列/读/改 persona/软删）、`GET
    /api/v1/openapi/agents/{id}/state`（状态直读，纯数据面不触发认知）。
  - **扫描白名单**：`useDefaultFilters=false` + `includeFilters` 精确点名 persona 闭包
    薄件（CompanionService/PersonaService/PersonaCompiler/LlmRouter/RelationshipService/
    PersonService/AgentStateService/EmotionReducer）。为让它自足，把 `EmotionReducer`/
    `EmotionDelta`/`StateReducer` 三个纯函数类从 `runtime` 包迁进 `state` 包
    （它们本来就只服务 state 域，runtime 是历史错置）——否则 AgentStateService 会
    把认知链重服务连带拉起。check-agent.sh 静态断言白名单不含任何认知链包。
  - **文档**：springdoc OpenAPI 3.1 spec（`/v3/api-docs`）+ Swagger UI（`/docs`），公开面。
  - **验收**：`scripts/check-openapi.sh` O1-O7 全绿；`OpenApiFlowTest` 5 断言钉死
    鉴权矩阵 + CRUD 闭环 + 归属隔离 + 吊销立即失效。
- **G5/G6**：两套全新前端（聊天 = 现代 IM 风 + 应用 + agent 好友；仿真 = agent 创建/管理/使用控制台）。
- **G7**：nginx 分流 + 联调部署。
