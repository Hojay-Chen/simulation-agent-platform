# simulation-agent-platform — 仿真 Agent 平台（仓 2）

> 数字人的全部认知 / 生活 / 记忆 / 状态。它消费仓 1（[chat-platform](../chat-platform)）
> 发布的契约 artifact，通过 SPI 端口看聊天世界与应用世界 —— 不认识任何一个的具体实现。
> 由 companion-agent 物理拆分而来（G1 = 仓 1 Gradle 化，G2 = 本仓骨架 + DH 迁入）。

---

## 1. 两个服务 + 一个控制台（用户拍板的形态）

| 服务 | 目录 | 端口 | 职责 |
|---|---|---|---|
| **功能服务** | `server/` | 8091 | 拉起数字人全部认知链（41 个顶层包 + 40+ 定时任务），`SimulationAgentPlatformApplication` |
| **对外 OpenAPI 服务** | `openapi/` | 8092 | 供外部/三方创建、删除、管理、使用自己的仿真 agent。g4 起业务端点全量(API Key + OpenAPI 3.1 + /docs) |
| **管理控制台** | `frontend/` | 5174(dev) | G6 起的 agent 管理前端：agents 列表/详情/创建、API 客户端发放与吊销、agent 实时状态 |

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
frontend/                  G6 管理控制台(Vite + React 19 + TS strict + Tailwind, 产物 dist/)
deploy/nginx/              G7 nginx 配置(版本库是源头, deploy.sh 装到 /etc/nginx/conf.d/)
deploy/systemd/            G7 两个服务的单元文件(版本库是源头, 手动 cp 到 /etc/systemd/system/)
scripts/check-agent.sh     边界守卫(grep 第一道防线)
scripts/check-openapi.sh   G4 验收(鉴权矩阵/CRUD/归属隔离/吊销)
scripts/check-console.sh   G6 验收(产物/代理/两面钥匙端到端)
scripts/deploy.sh          G7 部署(构建→rsync→nginx→健康检查→DNS 体检)
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
~/tools/gradle/gradle-8.14.3/bin/gradle test      # 325 测试(DH 全量 + common/server/openapi)
~/tools/gradle/gradle-8.14.3/bin/gradle :server:bootJar :openapi:bootJar

bash scripts/check-openapi.sh        # G4 验收: 鉴权矩阵/CRUD/归属隔离/吊销
bash scripts/check-console.sh        # G6 验收: 产物/vite 代理/两面钥匙端到端

# 控制台
cd frontend && npm ci
npm run test                         # 28 断言(faceOf 归属判定 + 列表渲染 + 表单校验)
npm run build                        # tsc -b && vite build → dist/
```

> 两个验收脚本都会自起 8092。**跑之前先确认没有别的实例占着 8092** ——
> 它们复用前会先验自己那把 `OPENAPI_ADMIN_KEY`，验不过会明确告诉你
> "8092 已在跑但用的不是本脚本的管理钥"，而不是让你去猜为什么 401。
> 脚本退出时用 `kill_tree` 递归收尾（`npm run dev` 会 fork 出 vite，
> 只杀直接子进程会留下孤儿占端口）。

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
- **G6（2026-09-16 完成）**：仿真 Agent 管理控制台（仓 2 `frontend/`，从零建）。
  - **分流不是"按主机"，是"按面"** —— 这是与仓 1 聊天前端 `route()` 最本质的差别。
    控制台只打**一个**后端 openapi:8092，但手里有**两把互不相通的钥匙**：管理面
    `X-Admin-Key`（发/吊销客户端钥）、客户端面 `Bearer sap_...`。`src/api/client.ts`
    的 `faceOf(url)` 按路径段判定该带哪把 —— 判错不会报错，只会静默 401，所以
    规则被 `client.route.test.ts` 钉死（含 `clients-archive` 这类前缀相似兄弟资源的边界）。
  - **一个被否掉的计划项**：原计划让「实时状态」走 8091（`/server/api` 代理）。
    落地时核对真实端点后否了：mood/emotionalCloseness/sleepiness 三个字段由 8092 的
    `OpenApiAgentStateController` 直读 `agent_state` 表给出，而 8091 的 `StateController`
    走 `CurrentUser.requireUserId()` —— 要用户 JWT，控制台手里只有 `sap_` 与 admin key，
    根本进不去那道门。所以最终是单上游 + 双钥匙面，vite 只配一条 `/api → 8092`。
  - **三页**：Agents（列表/详情/创建，创建走平台编译链；选中项落 URL `?id=`）、
    API 客户端（发放时明文 key 一次性展示 + 一键"用作当前客户端钥"）、
    agent 实时状态（10s 轮询；**认知链未初始化时如实说"尚未初始化"，不编 0**）。
  - **验收**：`check-console.sh` C1-C7，其中 C4（经 vite 无钥必须是 401 而非 404）
    同时证明代理确实落到了 8092；C7 专验两面钥匙互不相通。
- **G7（2026-09-16 完成）**：nginx 分流 + 部署（`agent.luxera.top`，与 companion 并存）。
  - **鉴权分层是本轮的要点**：人（浏览器）→ 控制台静态页 → **Authelia 前门**；
    机器（三方）→ `/api/**` → **不套 Authelia**，由 API Key 自己把关。
    给 `/api/` 套上 Authelia 会把整个对外 OpenAPI 产品打死 —— 三方带 `sap_...` 调过来
    会收到 302 跳登录页而不是 401，机器客户端无法完成浏览器登录。`deploy.sh` 的 D5
    把这条做成了硬断言（无钥必须 401，不是 302）。
  - **部署时抓到两个真缺陷**（单测与 G4 验收都没覆盖到）：
    ① **Swagger UI 从来打不开**。springdoc 的 `/docs` 不是页面而是 302：
    `GET /docs → 302 Location: /swagger-ui/index.html`。G4 的安全配置只放行了
    `/docs`，落点 `/swagger-ui/**` 吃 403。G4 的测试 `assertTrue(status < 400)`
    —— **302 恰好满足**，于是这个洞一路活到部署。（已修：放行 `/swagger-ui/**`，
    测试改为**跟随跳转**并断言落点 200。）
    ② **验收脚本泄漏子进程**。`( cd X && java … & echo $! )` 里的 `&` 绑定的是整个
    `cd && java` 列表，`$!` 拿到的是子 shell 的 pid；cleanup 杀掉子 shell 后 java
    变孤儿继续占着端口，下一个脚本复用到它、撞上另一把管理钥，报出来却是
    "invalid or missing api key"。（已修：`exec` 让子 shell 变成 java + `kill_tree`
    递归收尾 + 复用前先验自己那把管理钥。）
  - **DNS 是唯一待人工的一步**：`luxera.top` **不是泛解析**，每个子域名单独登记。
    `agent.luxera.top` 的公网 A 记录需在 DNS 服务商处添加指向 `124.222.135.75`；
    在那之前站内（`/etc/hosts` 或 `curl --resolve`）可用，外网访问不到。`deploy.sh` D6
    会把这件事喊出来，而不是假装部署成功。
  - **首次真机部署（2026-09-16）又抓出三个只在"两个仓同时真跑"时才现形的问题**，
    全部与"单看一个服务它完全健康"有关，详见 §8.1–8.3：
    ① 三个进程的共享密钥分处两个 env 文件 → 8081 拿的是 yml dev 默认值，
    G3 的 HMAC 与 G5 的共用 JWT 一起静默 403；
    ② 8091 **没有** `/api/health`，探活脚本把它误判为"没起"；
    ③ 3.6 GB 内存装三个默认堆（各 977 MB 上限）的 JVM，OOM killer 会随机挑受害者
    —— 已显式封顶堆 + 加 swap。另修了一个从 G1 就在转的崩溃循环：仓 1 单元的
    `WorkingDirectory` 指向拆分后已不存在的 `backend/`。

## 8. 部署

```bash
# 后端两个服务 —— 单元文件的源头在 deploy/systemd/(版本库)
sudo cp deploy/systemd/luxera-agent-{server,openapi}.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now luxera-agent-server luxera-agent-openapi

# 前端 + nginx（仓 2 scripts/deploy.sh）
bash scripts/deploy.sh                # 构建 → rsync /var/www/agent → nginx reload → 健康检查
bash scripts/deploy.sh --skip-build   # 只同步产物 + nginx
bash scripts/deploy.sh --dry-run      # 只体检，不动手
```

nginx 配置的**源头在 `deploy/nginx/agent.luxera.top.conf`**（版本库里），
`/etc/nginx/conf.d/` 是它的安装位置 —— 改配置请改仓库里那份再跑 deploy.sh，
否则下次部署会被覆盖回去。

### 8.1 密钥：三个进程共用一份（2026-09-16 部署时踩到）

| 文件 | 内容 | 读者 |
|---|---|---|
| `/etc/luxera/shared-secrets.env` | `JWT_SECRET` / `AGENT_PLATFORM_INTERNAL_KEY` / `SIMULATOR_TOKEN_SECRET` | 8081 + 8091 + 8092 |
| `/etc/companion/.env` | `DEEPSEEK_API_KEY` | 8081 |
| `/etc/agent-platform/.env` | `DEEPSEEK_API_KEY` / `OPENAPI_ADMIN_KEY` | 8091 + 8092 |

**为什么集中**：`JWT_SECRET` 要让 8091 验得出 8081 签的 token，
`AGENT_PLATFORM_INTERNAL_KEY` 要让两边的 `/internal` HMAC 对得上。分两处存 = 迟早漂移，
而漂移的症状是"跨服务调用全线 403"——**每个服务单看都健康**，只有把两边进程的实际
环境变量指纹对一遍才看得出来。真实事故：仓 1 的单元当时只读了 `/etc/companion/.env`
（只有 DEEPSEEK），于是 8081 拿的是 yml 里的 dev 默认密钥，8091 拿的是随机共享密钥，
G3 的 HMAC 与 G5 的共用 JWT 一起静默失效。

### 8.2 探活：8091 **没有** `/api/health`

8091（功能服务）不暴露健康端点，`/api/health` 是 404。但 404 恰恰证明它活着——
端口没人监听时 curl 拿不到任何状态码。判据是"有没有 HTTP 响应"，不是"状态码是不是 200"。
仓 1 的 `check-frontend.sh` 原先拿 `/api/health` 探 8091，会得出"8091 没起"的错误结论，
进而去起第二个实例撞端口；仓 1 的 `deploy.sh` 犯过同一个错。

### 8.3 内存：3.6 GB 的机器上跑三个 JVM

`deploy/systemd/` 里的两个单元都**显式封顶堆**（8091 `-Xmx512m`，8092 `-Xmx320m`）
并带 `-XX:+ExitOnOutOfMemoryError`。JVM 的工效学默认值按物理内存算，每个进程都会
认为自己可以要 977 MB；三个 JVM 各自按默认值跑就超过整机内存，结果是内核 OOM killer
随机挑一个受害者（可能是正在正常服务的 8081）。显式封顶把竞争变成各自有界，
真撞上限时自己体面退出、由 `Restart=always` 拉起，而不是拖着整机换页。

另：单元里 `SuccessExitStatus=143` 是必须的 —— 143 = 128+SIGTERM，JVM 走完
shutdown hook 仍以 143 退出，不声明的话一次正常的 `systemctl stop` 会把单元留成
`failed`（红），监控误报。
