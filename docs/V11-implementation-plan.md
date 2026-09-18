# V11 实施计划（把设计文档落到类与测试）

> 设计文档：`docs/V11-persistent-world-driven-runtime.md`（用户提供，不可改动语义）。
> 本文件是**执行**文档：每个 Phase 对应哪些类、哪些测试、如何在不打断线上 53 个 agent 的
> 前提下切换。

---

## 0. 底盘勘察结论（决定了本次怎么下手）

设计文档把当前状态描述得比实际更"白"。实地读代码 + 查库后的真实底盘：

| 设计文档假设 | 实际情况 |
|---|---|
| 需要新建 WorldEvent | **已经有三套**：`runtime/WorldEvent`(record)+`world_events` 表(4478 行)、`world/WorldEvent`(实体)+`digital_world_events`(21854 行)、`digitalhuman/event/ExternalEvent`(外部事件链)。**要的是统一，不是新建** |
| 需要新建 mailbox | `digitalhuman/actor/PersonActor` 已经是 per-agent 阻塞队列 + 单消费线程 + 30s 空闲回收；`PersonActorRegistry.tell()` 是全系统唯一写入口。缺的是**类型化**与**持久化** |
| 需要新建 OpenLoop / Intention | **两张表与 Service 都在**（`open_loops` / `intentions`）。`open_loops` 是 **0 行** —— 有 job 无产出，问题是没接上，不是没写 |
| 需要新建 Decision | `digitalhuman/decision/PersonDecision` 已有 Ignore/InspectDevice/Reply/DelayReply/ChangeActivity `DecisionPolicyEngine` 已在跑 |
| 需要新建 Awareness | `runtime/WorldEventType` 已定义完整阶梯：`RECEIVED→NOTIFIED→NOTICED→READ→DEFERRED`；`phone/PhoneNotification` 表已有 111 行 |
| Shadow Mode 要新建 | `digitalhuman/hotpath/V10HotpathGateway` + `ShadowDecisionRecorder` **已在跑**（`shadow=true, enabled=false` 默认） |
| 消息内容不直接读 | **契约已声明、代码没遵守**：`ChatMessageDeliveredPayload` 的 javadoc 写着"BODY intentionally NOT included"，而 `AgentRuntime.onChatMessageDelivered` 的 javadoc 写着"消息内容始终通过 Simulator 读取" —— 方法体却直接 `chatWorld.messages(conversationId)` 并读 `getContent()` |

**结论：V11 的主体工作是"接线与统一"，不是"从零造"。** 这决定了本次不采用重写，
而是沿用 V10 已经验证过的 Strangler 套路（Adapter → Shadow → Dual Run → Cutover → Cleanup）。

### 两个必须尊重的现实约束

1. **`simulator_devices` 只有 1 个 ACTIVE，51 个是 PAIRING。**
   → Phase 2 把"读消息"改成显式 Action 时，**不能**把传输层绑死在模拟器 WS 上，
   否则 52 个 agent 立刻变哑巴。设计见 §2.2：能力抽象 + 可用传输择优 + 记录用了哪条。
2. **`ChatSimulatorClient` 今天零调用者。** 它是死代码 —— 但它也是 V10 为这件事
   已经写好的实现。Phase 2 要把它接上，而不是另写一个。

---

## 1. 总体切换策略

新增一个与 V10 同构的开关，**默认 shadow**：

```yaml
app:
  v11:
    runtime:
      enabled: false   # 新运行时是否驱动真实行为
      shadow: true     # 是否并行跑新链路并记录决策差异
```

三层含义与 V10 一致：

| enabled | shadow | 行为 |
|---|---|---|
| false | false | 完全走旧链（V11 不参与） |
| false | true  | **默认**。旧链驱动；新链并行算一遍，只记 diff |
| true  | true  | 新链驱动；旧链保留为对照 |

切流判据（§30 验收指标）全部有测试与打点之后才动 `enabled`。

**开关本身也受 agent 开关管辖** —— 被暂停的 agent 在 mailbox 任务体第一行就被拦下，
V11 的新链路不许绕过那道闸（否则暂停功能会失效，而它是用户明确要求的省钱手段）。

---

## 2. Phase 分解

### Phase 1：Runtime 基础（纯新增，零行为变更）

| 交付 | 类 | 说明 |
|---|---|---|
| 事件信封 | `world/EventEnvelope` | V11 §5.2 的不可变信箱条目：`eventId / agentId / occurredAt / type / source / priority / references`。**禁止携带聊天正文**（同 `ChatMessageDeliveredPayload` 的既有约定） |
| 事件词汇统一 | `world/AgentEventType`、`world/EventSource`、`world/EventPriority` | 把现有三套词汇映射到一套。**只做映射表，不删旧常量** |
| 持久信箱 | `mailbox/AgentInboxEntry` + `AgentInboxRepository` | 事件落库（表 `agent_inbox`），`eventId` 唯一索引 = 幂等键 |
| 信箱 | `mailbox/AgentMailbox` | 类型化门面：去重 → 落库 → 投递到 `PersonActor`。**复用既有串行化，不重造** |
| 快照 | `runtime/AgentSnapshot`、`AgentSnapshotService` | 单 agent 状态的可序列化切面 |
| 恢复 | `runtime/AgentRecoveryService` | 启动时重放未消费 inbox 条目 |
| 门面 | `runtime/PersistentAgentRuntime` | V11 §4.1 的接口：`accept / wake / snapshot / recover` |

**验收测试**：同 eventId 投两次只处理一次；重启后未消费事件被重放；
同一 agent 串行、不同 agent 并行；暂停中的 agent 一条都不处理。

### Phase 2：消息世界化（本阶段开始改行为，但默认仍在 shadow 后）

| 交付 | 类 | 说明 |
|---|---|---|
| 手机能力 | `phone/PhoneCapability`（接口） | V11 §7.2：`notifications()` / `readMessages()` |
| 默认实现 | `phone/DefaultPhoneCapability` | **择优传输**：设备连着 → 走 `ChatSimulatorClient`（DHCP `chat.readMessages`）；否则回退 `ChatWorldPort`。两条都记录用了哪条 |
| 读动作 | `action/ReadMessagesAction` | 正文**只能**经此进入认知 |
| 通知 | 复用 `phone/PhoneNotificationService` | 投递只产生通知，不产生正文 |
| 阶梯 | `phone/AwarenessLadder` | RECEIVED→NOTIFIED→NOTICED→READ。用 `world/AgentEventType`（Phase 1 造的词汇归一），**不是**本表原先写的 `WorldEventType` —— 后者是 14 个字符串常量且只有 1 个有生产者，`AgentEventType` 才是信封真正用的类型 |
| 读取策略 | `phone/ReadPolicy` + `phone/MessageBatch` | 按 id 读 / 读最近 N；**没有"读整个会话"这个策略** |
| 显著性 | `attention/DeliverySalience`（纯函数）+ `attention/DeliverySignals` | **不读正文**算出的消息显著性 |
| 开关 | `runtime/v11/V11RuntimeSwitch` | `app.v11.runtime.enabled=false` / `.shadow=true` |
| 主链 | `runtime/v11/V11DeliveryPath` | `assess`（无副作用）/ `deliver`（有副作用） |
| 对比 | `runtime/v11/V11DeliveryShadow` | 定长计数器 + 有界样本环 |
| 读出口 | `DiagnosticController` 的 `GET /v1/.../v5/{id}/v11` | 让对比数据**真的能被读到** |
| 改主链 | `AgentRuntime.onChatMessageDelivered` | shadow 下并跑判定并记录；老链那三行一字未动 |

**这是本次唯一会让 53 个 agent 行为真的变化的改动**，所以它必须先能在 shadow 下对比。

#### 开工后才发现的四件事（原计划没写）

1. **本阶段的真正阻塞点是架构性的，不是机械性的。**
   今天"她注意到了没有"是这么算的（`MessagePipeline` 第 2 步）：

   ```java
   emotion = emotionAgent.execute(..., decisionText, ...)          // 先读完正文 + 情绪评估
   double salience = 0.3 + emotion.delta().warmth()*0.3 + ...      // 再用情绪算显著性
   attentionService.compute(..., salience)                          // 才问"注意到没有"
   ```

   即**显著性来自对正文的情绪评估** —— 于是"没注意到"永远不可能是真的：
   内容已经在她手里了，后面所有的"在忙 / 没看见 / 已读不回"都只是知道内容之后找的说法。
   所以 Phase 2 的核心不是"把读消息包成一个 Action"，而是**造一个不读正文的显著性模型**
   （`DeliverySalience`：连发条数 / 关系数值 / 时间戳）。

2. **丢掉了"追问词"检测，这是明知代价的取舍。**
   老链靠读正文里的"你怎么不回""在吗在吗"判断被催问。新模型做不到，代价是她会对
   明显在催她的人无动于衷。**但正确的修法不是"再读一点正文"** —— "这条消息在催我"
   是聊天平台知道、agent 不该靠读正文去猜的事实。正确做法是送达时给一个显式的紧急度
   字段（契约变更）。`DeliverySalience.Signals` 已经留好 `urgencyHint` 与
   `minutesSinceReply` 两个位置，今天恒填默认值，补契约时只改一行接线。

3. **salience 不许再打折"她的处境"。**
   第一版让 `DeliverySalience` 对"睡着""勿扰"打折，写完 `DeliverySignals` 才发现
   `AttentionService` 已经用 `taskAttention`(SLEEP 0.95) 和 `phoneNotificationFactor`(dnd 0.0)
   表达了同一件事 —— 两边都打折就是**双罚**：一个深夜的勿扰消息被罚两次，
   于是"她没看见"既无法解释也无法调参。边界定死：**salience 是消息的属性，
   处境是她的属性**。

4. **V10 的 shadow 是纯成本，本阶段不许重演。**
   `ShadowDecisionRecorder.stats()` / `recent()` / `perAgentStats()` 在整仓里
   **一个调用者都没有** —— 没有端点，没有测试。它一直记录、从没被读过，
   而且 `buffer.put(id + "-" + System.nanoTime(), ...)` 从不淘汰，是条只涨不跌的内存曲线。
   所以 V11 的对比数据有明确的读出口（诊断端点 `/v11`）和读出口的测试。
   **一个读不到的对比不是对比。**

5. **切流前必须有人"听得懂"阶梯事件，今天一个都没有。**
   `AgentEventType.USER_MESSAGE_RECEIVED/NOTIFIED/NOTICED/READ` 写进 `agent_inbox`，
   但全仓<b>没有任何 `AgentInboxConsumer` 实现</b>（只有接口），所以 `consumerFor` 返回 null，
   条目按设计<b>留在 PENDING</b> —— `AgentMailbox` 的注释说得很准：
   "没有消费者不是处理失败，是还没有人听得懂"。

   这不阻塞 Phase 2（`enabled=false`，一条都不会写），但**它阻塞切流**：
   打开 `enabled` 之后，每条送达会写 3–4 行永远不会被消费的信箱条目。
   不担心它涨到天上（`dropExpired` 有 24h 保留期，已核实），
   但"阶梯是事实的记录"这件事要真的成立，得有 Phase 3/4 的消费者来读它。
   **切流的前置条件之一就是先有消费者。**

#### 测试覆盖与一处刻意留白

Phase 2 的用例（86 条，全部绿）：`DeliverySalienceTest`（显著性逐条断言）、
`V11DeliveryPathTest`（**assess 不许有副作用** / 阶梯顺序 / 回退）、
`V11DeliveryShadowTest`（有界性 / 可读性 / 键名契约）、
`DefaultPhoneCapabilityTest`（择优传输 / 回退 / 反模式：不许把整个会话拉下来筛）、
`ReadMessagesActionTest`（先读后记）、`AwarenessLadderTest`（幂等键 / 信封无正文）。

**刻意留白一处**：没有"`enabled=true` 端到端走一遍"的集成测试。
理由不是偷懒 —— 那个测试今天会断言一条**下游不存在的**行为：
阶梯事件写进信箱后没有任何消费者（见上文第 5 条），
所以"端到端"在这里只到"信进了信箱"为止，而这正是单元测试已经覆盖的部分。
真正的端到端测试属于切流那一步，和消费者一起来。

#### 顺带修掉的启动故障

`DefaultPhoneCapability` 直接注入 `ChatSimulatorClient`，而后者是
`@ConditionalOnProperty(app.simulator.backend=websocket)` —— 默认配置下**这个 bean 不存在**。
于是 `DefaultPhoneCapability → ReadMessagesAction → V11DeliveryPath → AgentRuntime`
一路把"设备通道是可选的"变成了一条硬依赖，**整个应用起不来**（`UnsatisfiedDependencyException`）。
改用 `ObjectProvider`：设备通道缺席时安静地走平台直读，而不是让整条认知链陪葬。
这不是理论问题，是第一次跑全量测试时红出来的。

### Phase 3：连续意识

`mind/MindState`（+ `WorkingThread`、`FocusState`）+ 持久化；
`ConversationTurnAggregator`（OPEN→QUIET_WAIT→SEALED→PROCESSING→COMPLETED）+ 定时 seal；
把既有 `openloop/`、`intention/` 接进 MindState（**`open_loops` 表 0 行是接线问题**）。

**验收**：m1/m2/m3 在一个 quiet window 内 → **1 个** Cognitive Turn，不是 3 个。

### Phase 4：认知重构

`cognition/DecisionType` + `CognitiveDecision`（扩既有 `PersonDecision`，不替换）；
`advanceMind()` 取代 `processUserMessage()` 的调用位置；
`ResponseContinuityGuard` 去重表达；LLM 降为 reasoning tool。

**验收**：busy 时收到消息 → `DEFER`/`IGNORE` 且**不回复**；到点唤醒 → READ → REPLY。

### Phase 5：主动行为

`wakeup/AgentWakeup` + `agent_schedule`（下次唤醒时刻）；
Life / OpenLoop / Relationship 三类触发器产出**事件**（不是直接聊天）。

### Phase 6：旧链清理

旧 `MessagePipeline` 无核心职责后再删。保留 adapter 的地方写清为什么保留。

---

## 3. 全程不变量（每步之后都必须成立）

1. 与 chat-platform 的契约**一个字节都不改**（契约 1.0.1 已符合 V11 语义）。
2. `scripts/check-agent.sh` 边界守卫通过。
3. `gradle test` 全绿；新增测试只增不减。
4. agent 开关三层闸门 + LLM 硬闸仍然有效。
5. 每次提交后立即 push。

---

## 4. 进度

- [x] Phase 1 —— Runtime 基础(纯新增, 零行为变更)。四条验收标准各有测试:
      幂等(含四线程并发)、重启重放、同 agent 串行/不同 agent 并行、暂停中一条不处理。
      顺带修掉一个会静默丢信的坑: `EventEnvelope.withDeterministicId` 原先<b>接受 agentId
      却不用它</b>, 于是两个 agent 撞上同一个来源键(广播事件、给所有人的定时唤醒)时,
      后一个的信会被当成"已收过"直接丢掉 —— 没有异常、没有日志。
- [x] Phase 2 —— 消息世界化。交付 11 个类 + 86 条用例；默认
      `app.v11.runtime.enabled=false / shadow=true`，即**并跑判定并记录分歧，不改行为**。
      老链那三行一字未动。本阶段真正的产出不是"把读消息包成 Action"，
      而是**造出了一个不读正文的显著性模型**（`DeliverySalience`），
      因为老链的显著性来自对正文的情绪评估 —— 顺序错了，"没注意到"就不可能是真的。
      五条开工后才发现的事记在上面（架构阻塞点、追问词取舍、salience 不许双罚处境、
      V10 shadow 是纯成本、切流前必须先有消费者）。
      **切流前置条件（未做）**：① 阶梯事件要有消费者；② 真实流量下 shadow 分歧率可读
      （`GET /api/companions/{id}/v5/v11`）；③ `enabled=true` 的端到端测试。
- [ ] Phase 3
- [ ] Phase 4
- [ ] Phase 5
- [ ] Phase 6
- [ ] 全量测试 + 两仓部署 + 截图 + 全部 agent 关闭
