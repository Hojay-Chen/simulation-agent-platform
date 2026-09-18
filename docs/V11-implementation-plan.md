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
| 需要新建 OpenLoop / Intention | **两张表与 Service 都在**（`open_loops` / `intentions`）。`intentions` 24 行、`thoughts` 52 行, 而 `open_loops` 是 **0 行** |
| 需要新建 Decision | `digitalhuman/decision/PersonDecision` 已有 Ignore/InspectDevice/Reply/DelayReply/ChangeActivity `DecisionPolicyEngine` 已在跑 |
| 需要新建 Awareness | `runtime/WorldEventType` 已定义完整阶梯：`RECEIVED→NOTIFIED→NOTICED→READ→DEFERRED`；`phone/PhoneNotification` 表已有 111 行 |
| Shadow Mode 要新建 | `digitalhuman/hotpath/V10HotpathGateway` + `ShadowDecisionRecorder` **已在跑**（`shadow=true, enabled=false` 默认） |
| 消息内容不直接读 | **契约已声明、代码没遵守**：`ChatMessageDeliveredPayload` 的 javadoc 写着"BODY intentionally NOT included"，而 `AgentRuntime.onChatMessageDelivered` 的 javadoc 写着"消息内容始终通过 Simulator 读取" —— 方法体却直接 `chatWorld.messages(conversationId)` 并读 `getContent()` |

**结论：V11 的主体工作是"接线与统一"，不是"从零造"。** 这决定了本次不采用重写，
而是沿用 V10 已经验证过的 Strangler 套路（Adapter → Shadow → Dual Run → Cutover → Cleanup）。

### 0.1 一条被推翻的底盘结论（`open_loops` 0 行）

上面这张表原先写着"`open_loops` 是 0 行 —— **有 job 无产出，问题是没接上，不是没写**"。
Phase 3 开工时逐行核对，**结论是反的**：

- 线是通的：`AgentPostProcessor.afterExchange`（`@Async`，挂在回复路径上）→
  `ThoughtEngine.maybeFromConversation` → `OpenLoopService.create`，一路都有调用者。
- 0 行的真实原因是 **触发条件饿死**：`ThoughtEngine.RESOLUTION_PATTERN` 要求
  `(明天|后天|下周|今晚|过几天)…(面试|考试|开会|…)` 或 `等(消息|结果|通知)` 或
  `(面试|考试)(结果|出来|怎么样)` 这类显式的"待办 + 时间"句式。
- 证据：拿库里 145 条真实用户消息逐条跑那个正则，**0 条命中**。

所以"Phase 3 要把 `openloop/` 接进 MindState"这句话的准确含义是：**不是接线，是消费**。
`MindStateService.snapshot(...)` 通过 `OpenLoopService` / `IntentionService`（与
`AgentSnapshotService` 同一对 Service）把这两张表读进她的心智切面 —— 一份数据两个读口，
数字不会分叉。**放宽正则不是解法**：把"她记不记得住一件事"交给一个更宽松的正则，
只会把噪声写进 `open_loops`。真正的解法是 Phase 5 的主动行为里用 LLM 抽取（那时它
有一个不循环的位置），本文档不在 Phase 3 做这件事，也不假装它已经做了。

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

**交付**（`mind/` 6 个类 + `runtime/v11/` 3 个类）：

| 类 | 职责 |
|---|---|
| `mind/TurnWindow` | 静默窗口 / 硬上限 / 条数上限。构造器校验，配错在**启动时**炸 |
| `mind/ConversationTurnAggregator` | 纯状态机（无依赖、无 sleep、无时钟）。`OPEN→QUIET_WAIT→SEALED`，后两态刻意不表达 |
| `mind/WorkingThread` / `FocusState` / `MindSnapshot` | 工作台、关注点、心智切面（三个 record，无行为） |
| `mind/MindState`（表 `agent_mind_states`）+ `Repository` + `Service` | 持久化；**写由一处上闸**，读不上闸 |
| `runtime/v11/V11TurnsSwitch` | `app.v11.turns.*`——与 `app.v11.runtime.*` 刻意不合并（见下） |
| `runtime/v11/V11TurnPath` | 回合这条链上唯一知道"封口之后发生什么"的地方 |
| `runtime/v11/V11TurnSealJob` | 每秒一次：到期 → 入队。**调度线程上不跑认知** |

**验收**：m1/m2/m3 在一个 quiet window 内 → **1 个** Cognitive Turn，不是 3 个。
`ConversationTurnAggregatorTest$Acceptance` 直接断言这一点（1 个回合、3 条消息、
`messagesPerTurn == 3.0`），并配一条反向用例：三句隔十分钟 → 3 个回合、合并率回到 1.0。

**开工后才发现、并因此改了设计的事：**

1. **调度线程只有一根。** Spring 默认 `ThreadPoolTaskScheduler` 池大小是 1（本工程既没配
   `spring.task.scheduling.pool.size`，也没有自定义 `TaskScheduler` bean），全平台 ~19 个
   `@Scheduled` 共用它，含 5 秒一次的 `outbox-relay`。所以"封口即处理"会让一次 LLM 把
   全平台的定时任务一起按住。**解法**：job 只做"查开关 + 入队"，认知由
   `PersonActorRegistry.tell` 送回 agent 自己的消费线程（也是它今天本来就在跑的线程）。
   `V11TurnSealJobTest` 用**真实的** `PersonActorRegistry` 断言认知线程名是
   `person-actor-<agentId>` —— 用一个假的入队测不出这件事。
2. **回合 id 必须可重算**，否则日志、`agent_mind_states.last_turn_id`、阶梯事件对不上号。
   用 `会话#第一条消息`（确定性），不是随机 UUID。上限 160 字符。
3. **`@Transactional` 在自调用上是装饰。** `getOrCreate` 由此改成 `private`——
   它的真正价值不是事务，而是让"建行"这件事只有一个只能在闸门之后到达的入口。
4. **shadow 必须走自己的入口。** `V11TurnPath.accept` 会写心智，`observe` 不会；闸门在
   `MindStateService` 里（`turns.isEnabled()`）。测试里若用 `accept` 喂 shadow，
   mock 上没有闸门，用例会看见一个生产上不存在的交互，把"shadow 不写心智"测成假的。
5. **序列化失败要"不写"，不是"写空"。** `writeThreads` 失败返回 `null` 并让调用方
   `return`（保留库里原来的工作台），而不是返回 `[]` —— 后者是把一次技术故障写成她的记忆。
   同样地，读坏 JSON 按"没有线"处理：反向选择会让一条写坏的记录从此让这个 agent 的
   每次读写都失败。
6. **`last_cognitive_at` 只在认知真跑过时才推。** 被暂停丢掉、读不到正文、认知抛异常
   的回合都记 `cognized=false`：账目要记，但"她上一次真正想过事"不能是假话。
7. **`@Scheduled` 的默认值里不能带引号。** YAML 里 `turn-seal-cron: '*/1 * * * * *'` 的
   引号是给 YAML 的（`*` 开头会被当成别名），而 `${key:default}` 的 default 是**逐字**
   取的 —— 把 YAML 那行原样粘进注解，得到的是一个以单引号开头的非法 cron，表现为
   **所有 Spring 上下文测试一起红**（`IllegalStateException: invalid @Scheduled method`，
   而不是一条 CronExpression 解析失败）。这正是全量测试存在的意义：本类的单元测试
   （纯 Mockito，不起 Spring）全绿，只有起上下文的用例才看得见它。

**对设计文档 §9.2 的一处有意偏离**：文档的 `MindState` 列了 `attention` / `emotion` /
`social` / `life` 四个字段。本实现**不把它们存进 `agent_mind_states`** —— 它们各自已经有家
（`agent_states`、通知表、关系表、life 内核），再存一份就是同一件事的两个值：一个由本表
写入者更新、一个由原子系统更新。要"全"的那个东西是 `MindSnapshot`（读取时向同一对
Service 要，与 `AgentSnapshotService` 同源），不是这条记录。理由是"两个答案"比"字段少"
糟得多：发现它们不一致需要有人同时读两处。这段话写在 `MindState` 的类注释里。

**四个"知道但今天拿不到"的判据**（§8.3 列了六个，只实现了两个）：契约里没有"对方正在
输入"这个信号；"语义是否完整"要用一个 LLM 回合去决定要不要开一个 LLM 回合（循环论证，
正确位置是 Phase 4 的 `WAIT` 决策）；"她自己的状态 / 是不是正在做别的事"要有 Phase 4 的
决策输入。全部写在 `TurnWindow` 的类注释里，而不是假装它们已经被考虑过。

**已知限制（Phase 5 负责）**：in-flight 的消息 id **刻意不持久化**（避免第二个真相源与
会话状态分叉）。后果是重启会丢掉正在静默窗口里的那个回合 —— 一次重启期间对方说的话，
会等下一次送达或 Phase 5 的主动唤醒才被处理。

### Phase 4：认知重构

`cognition/DecisionType` + `CognitiveDecision`（扩既有 `PersonDecision`，不替换）；
`advanceMind()` 取代 `processUserMessage()` 的调用位置；
`ResponseContinuityGuard` 去重表达；LLM 降为 reasoning tool。

**验收**：busy 时收到消息 → `DEFER`/`IGNORE` 且**不回复**；到点唤醒 → READ → REPLY。

#### 开工后确定下来的三处语义（都由测试逼出来，不是设计时想到的）

1. **"LLM 降为 reasoning tool" 的落点**：回不回由**纯规则**决定（`MindDecisionPlanner`
   无 I/O、无时钟、输入全是流水线已算出的事实），LLM 只负责**怎么说**。这与 V10 的顺序相反
   —— 以前是先问模型"要不要回"，再解释它的答案；现在是先有"她决定做什么"这个值，
   模型只在已经决定要说之后才被调用。所以本阶段的验收可以用穷举式用例证明，
   而不是靠"跑几次看看它回不回"。
2. **busy 是 DEFER 的*必要不充分*条件**。V10 §12 已写死"Busy ≠ 不回复"，两者不冲突是靠
   这一条对齐的：忙 <b>且</b> 没被催问 <b>且</b> 情绪信号不强 → DEFER；被催问 / 情绪强烈时
   忙也要回。`RESTING`（休息）**刻意不算忙** —— 休息中的她恰恰有空回消息。
3. **"她睡着了"取作息与可用性的并集**。这两个事实来自不同地方（睡眠模型 / `AgentState`），
   会不同步；只看其中一个会出现"可用性说她睡着、作息说她醒着" → 落到"忙"分支 →
   **押后一小时**，而对一个睡着的人来说那是最差的答案（既没等她醒，也没解释清楚）。
   任一个说她睡着就按睡着处理。

### Phase 5：主动行为

`wakeup/AgentWakeup` + `agent_schedule`（下次唤醒时刻）；
Life / OpenLoop / Relationship 三类触发器产出**事件**（不是直接聊天）。

#### 交付的形状

八件东西，一条自持的闭环：

| 类 | 角色 |
|---|---|
| `wakeup/AgentWakeup`（表 `agent_schedule`）+ `AgentWakeupService` | 她下次醒来的时刻，一行一个 |
| `wakeup/AgentWakeupJob`（10s） | 到点的闹钟 → 信箱。**只投信，不跑认知** |
| `wakeup/WakeupRearmJob`（5min） | 每个活着的 agent 手上必须一直有一个时刻 |
| `digitalhuman/life/LifeScheduleJob` | 生活事件：世界那一半同步，她那一半投信 |
| `openloop/OpenLoopDueJob`（3min） | 悬着的事到点了 → 一封信 |
| `proactive/ProactiveActionConsumer` | 仓库里**第一个真的消费者**：拆信 → 问行为引擎 |
| `runtime/v11/ProactiveActionRecorder` | 主动行为的对照账本（与认知那本分开） |
| `runtime/AgentRuntime.scheduleWakeup` | 把 DEFER 的 `nextWakeupAt` 变成下一行闹钟 |

闭环：`WakeupRearmJob` 保证每人一个闹钟 → `AgentWakeupJob` 到点投信 →
`ProactiveActionConsumer` 拆信问引擎 → 引擎可能押后 → `AgentRuntime` 排下一个闹钟。
**她不再依赖任何全局 cron 被"推进"** —— "她是活的"从一句声明变成了一条能自持的电路。

#### 三处由测试逼出来的语义

1. **幂等键在两个方向上正好相反。** 闹钟那一路是 `行id@这次响的时刻`：只用 `sourceKey`
   会在她改主意推后时被当成重复，只用行 id 会在复活同一行之后让第二次以后的响全部静默丢失
   —— 两种写法都表现为"她第一次没回，之后那个闹钟就再也没响过"，没有异常、没有日志。
   生活事件那一路**故意**只用 `scheduleId`：一个排程项对应一次生活变化，`markDone` 写库失败
   时下一轮重走，第二封**必须**被认出来，否则她经历两次"活动结束了"。
2. **世界那一半不许变成信。** 活动结束/计划激活照旧同步执行：它是事实，会被作息、可用性、
   注意力读到；改成投信之后她一旦在睡觉，活动就永远不会结束。分裂点是"事实"与"她的反应"，
   不是"重要的"与"不重要的"。
3. **`enabled=true` 时老链的 tick 必须停。** 不停的话她的一次主动行为会被两条链各执行一次
   ——重复发言。而那在外部看起来与"她心情很好"一模一样，不会被当成故障报上来。
   所以 `BehaviorTickJob` 的分叉不是效率优化，是切流的正确性条件（Phase 6 连同本类一起删）。

#### 一处与本文档原设计的偏离（有意，记录在案）

原文写的是"Life / OpenLoop / **Relationship** 三类触发器产出事件"。实际交付的是
Life / OpenLoop / **Wakeup** 三类，**没有为 Relationship 单独做 emitter**。理由：

`connectionPressure` 在两次交互之间是**单调不减**的（由 `lastInteractionAt` 与"沉默了多久"
算出）。"压力高就投一封信"这把触发器于是会在每个扫描周期都成立 —— 一个压力驱动的 emitter
会变成一台复读机，而它的症状只是"她最近特别黏人"，不会被当成故障。

关系压力今天仍然到达她的决策，路径是：`WakeupRearmJob` 对每个 runnable agent 调
`BehaviorEngine.prepare`（§三十九 的关系维护推进，原来寄生在老链 tick 里 —— 切流后那一步
必须有人接住，漏掉它的症状是"她再也不主动找人"）→ `BehaviorEngine.select` 读
`connectionPressure` 作为一项权重。**节奏旋钮是 `app.v11.proactive.rearm-minutes`（默认 30
分钟）**，不是一个新的触发器。`RELATIONSHIP_CHANGED` 事件类型与消费者支持都留着，
等有了真正的"关系突变"生产者在别处接上。

#### 本阶段未做（如实记录，不留含糊）

- **用 LLM 抽取 `open_loops`**（§0.1 把这件事指到了 Phase 5）**没有落地**。
  `OpenLoopDueJob` 只消费既有 `open_loops` 行，不问"这条对话里有没有一件悬着的事"。
- **`INTENTION_ACTIVATED` 有类型、消费者也认它，但没有生产者。** 今天投出去的事件只有
  Life / OpenLoop / Wakeup 三族。
- **重启丢掉的在途消息**（Phase 3 的"刻意不持久化"）只是被闹钟**间接**兜住 ——
  她会在下一次醒来时重新面对那条会话，而不是有一个显式的"读未回复消息"动作。
- **老链那条 `pipelineResult.isDeferred()` 分支仍然不排闹钟**（`advanceMind` 第 562 行）。
  它今天不需要排：`enabled=true` 时 V11 决策接管"回不回"，那个分支根本不执行。
  也就是说这个缺口**在切流那一刻自动闭合**，而 `enabled=false` 时它是老链原来的行为、
  不该被 Phase 5 改动。写在这里是因为"DEFER 没有执行者"这句话在 Phase 4 的注释里
  仍然字面成立 —— 它成立的范围是"shadow 期的老链"，不是 V11 的决策。

### Phase 6：旧链清理

原计划只有一句：旧 `MessagePipeline` 无核心职责后再删。保留 adapter 的地方写清为什么保留。

#### 先说结论：切流还没发生，所以"删 MessagePipeline"今天做不到，也不该做

§25.1 的顺序是 Adapter → Shadow → Dual Run → **Cutover** → Cleanup，而 Cutover 的前置是
真实流量下的 shadow 分歧率可读（Phase 2 的前置条件 ②）。四个开关今天全部是
`enabled=false / shadow=true`（`backend/server/src/main/resources/application.yml`）。
在这个状态下老链不是"可以删的遗留物"，它是**唯一真的在回话的那条路**：

| 老链部件 | 今天为什么还不能删 |
|---|---|
| `MessagePipeline.process` | `AgentRuntime.advanceMind`(:501) 读它的 `attention()` 与 `brainDecision()` 去构造回复输入 |
| `AgentRuntime.process()`（委托适配器） | `V11TurnSealJob`(:129) 走的就是它 —— 删它得先把回合封口改接到 `advanceMind` |
| `BehaviorTickJob` | shadow 期她**唯一**会主动开口的地方：Phase 5 的消费者只记账不动手，而这里 `:51` 判的是 `isEffective()` |
| `PersonDecision` 五策略 + `V10HotpathGateway.shadowEvaluate` | shadow 期每批消息都跑；结果只写进 `ShadowDecisionRecorder` 的内存缓冲，**而那个缓冲没有任何 src/main 读者** |

最后一行是 Phase 2 已经记过的"V10 shadow 是纯成本"，这里补一个更刺眼的说法：
`app.v10.hotpath.*` 在生产 yml 里**根本没写**，于是跑的是默认值
`enabled=false, shadow=true` —— 每个聊天批次都算一遍 V10 的感知与决策，算完丢掉。
它不会报错，也不会被任何断言发现，它只是没人读。

所以本阶段交付四件能做的事，而不是一句"删不掉"。

#### 交付 1：切流演练（`V11CutoverTest`，5 条用例，真库 + 真 Spring 上下文）

前五个阶段的用例全在**开关关着**的默认态下跑。于是有一条风险一直没被覆盖，而它恰好最难事后发现：
**切流那天是这些接线第一次被执行**。属性名写错一个字母（`@Value` 的默认值会安静地接手）、
某个消费者没被注册进容器、某个事务边界只在真库上才暴露 —— 单测一个都看不见，
因为单测里的开关是手工构造的。演练换的是**配置**，所以它检查的是"配置文件到行为"这条线本身。

三条断言值得点名：

1. **四条链都真的切过去了。** 看着像废话，挡的是"四条链里切了三条"那种无声失败。
2. **`enabled=true` 时老 tick 必须停。** 不停的话她的一次主动行为被两条链各执行一次，
   而"她一次说了两条"在外观上与"她心情很好"没有区别。
3. **阶梯事件在真库上仍然没人拆。** `accept` 返回 `true` 只说"信是新收下的"，
   与"有人拆"无关（`AgentMailbox:115`）—— 所以这条用例断的是"它留在 PENDING"，
   而不是"投递被拒"。重放也救不了：重放遇到没有消费者的信会原样留在 PENDING。
   这就是 Phase 2 那条"切流前置条件 ①"在真库上的可见形态。

#### 交付 2：演练当场逮到的一个真缺陷 —— 测试脚手架漏进了每一个 Spring 上下文

演练第一次跑就红了，但它红得有价值：它在容器里逮到的不是生产代码，而是
`AgentMailboxTest.RecordingConsumer` 这个**测试脚手架**（它 `supports` 一切），
一个只该活在 `AgentMailboxTest` 自己上下文里的东西。

病根不在那个测试。`DigitalHumanTestApplication` 直接写了一个 `@ComponentScan`，
而**直接注解会压过元注解** —— `@SpringBootApplication` 元注解上带的
`TypeExcludeFilter` 就此静默消失。少了它，任何一个测试类里嵌套的 `@TestConfiguration`
都会被组件扫描收成 bean，进入**所有** 31 个 `@SpringBootTest` 的上下文。

修法是把它显式写回来（连带 `AutoConfigurationExcludeFilter`），安全性是有证据的：
全仓只有一个 `@TestConfiguration`，而它自己的测试用 `@Import` 显式引入 ——
`@Import` 不经过组件扫描，所以原本的用法一字未改。

这件事值得单独记一笔，因为它说明了一类失败：**泄漏本身不报错**。
今天容器里漏进来的只有一个记录器，那是运气；31 个上下文各自带着别人的协作者，
而"这条用例到底验证了哪条路径"会变成一个需要推理的问题。

#### 交付 3：删掉真正没人调用的东西

`CognitiveDecision` 里的四个单向适配器 —— `from(Outcome, reason)` /
`fromBrain(action, reason)` / `from(PersonDecision, now)` / `from(PersonDecision)` ——
在 `src/main` 里**一个调用者都没有**，从头到尾只有它们自己的测试在用。删了。

删掉的理由不是"暂时用不上"，而是它们注释里声称的那件事是**错的**：注释写着
"shadow 期的差异率靠它把老链的 Outcome 读成新词表算出来"，而真正的差异率在
`CognitionDecisionRecorder.classify` —— 它比的是**"会不会写一条消息出去"**
(`DecisionType.producesOutboundMessage`) 与老链这一次到底有没有发消息，**刻意不比决策名字**。
这是两种对照里更结实的那一种：决策名是两套词表各自的方言，拿方言对齐只会得到一张
永远需要维护的映射表，而"她会不会开口"不需要翻译。

留着它们的代价很具体：后来的人读到一个"并跑期观察设施"的注释，会去找它在哪被用，
找不到，只能怀疑自己看漏了。真要按决策名对照时从 git 历史取回即可 ——
理由与删法写在了 `CognitiveDecision` 类文件的末尾，`AgentRuntime.scheduleWakeup`
的 javadoc 里那句指向已删方法的解释也一并改掉了。

**保留了一个同样零调用者的类**：`digitalhuman/attention/AttentionPolicies`。
它没有任何地方调用，但**另一个仓的契约把它写成了自己的一部分** ——
`chat-platform/contract/.../AttentionPolicy.java` 逐字写着这个映射
"lives on the DH side (`AttentionPolicies.toPerceptionLevel`)"，并给了理由：
反向依赖会让 `contracts` 依赖 DH。删它要跨仓改那段文档，收益是三十行代码，不划算。这条记在这里，是为了让它下次被人发现时
不必重新推一遍。

#### 交付 4：删除清单（每一项都带解锁条件）

不是"以后再说"，而是每一项写清**什么条件下它才可以删**：

| 遗物 | 今天的状态 | 解锁条件 |
|---|---|---|
| `AgentRuntime.process()`（委托适配器） | V11 的回合封口走的就是它 | `V11TurnSealJob` 改接 `advanceMind` 之后 |
| `MessagePipeline.process` | `advanceMind`(:501) 读它的 attention / brainDecision | 回复输入改从 V11 自己的送达评估取之后（`V11DeliveryPath.assess` 已经算出同一套 attention） |
| `pipelineResult.isDeferred()` 分支 | `enabled=true` 下不可达 | 切流即闭合（Phase 5 已记） |
| `PersonDecision` 五策略 / `DecisionPolicyEngine` / `PerceptionDecisionOrchestrator` | shadow 期真的在跑，结果无人读 | 与 `V10HotpathGateway` 一起删。它自己藏在 `app.v10.hotpath.enabled=false` 后面 |
| `ShadowDecisionRecorder` | 只写不读 | 同上。**或者**决定留一条 V10 决策的观测，那就给它接一个读者 —— 今天它是一台对着空房间的录像机 |
| `PendingMessageReevaluationJob`（每分钟） | **活的**：会真的重跑 Brain、重排 action | 切流前必须回答"DEFER 之后的重新评估谁做"。V11 的闹钟只覆盖了**唤醒**侧，Brain 重评没有移植 |
| `ScheduledActionJob` + `ScheduledActionDispatcher`（每 20 秒） | 活的循环，但 `ScheduledActionDispatcher.register` **全仓零调用点** → 每个到点的 action 都落到 `handler == null` → `markFailed` | 这份表是老链 DEFER 的产物，随 `MessagePipeline` 一起删。**在那之前它是纯噪声**：它把"她答应过要做的事"逐条记成失败 |
| `OpenLoopJob` / `LifeTickJob` / `UnfinishedThoughtActivationJob` | 三个老定时器，生产 yml 里 cron **都有值**，所以都在跑 | **未验证** —— 需要逐个确认 V11 的对应物（`OpenLoopDueJob` / `LifeScheduleJob` / `INTENTION_ACTIVATED` 消费者）是否覆盖了它的全部作用。这是全清单里唯一一段"连对应关系都还没核"的，如实记下 |

#### 切流 runbook（真切的那天照这个走）

**前置**（缺一条就别开始）：

1. `GET /api/companions/{id}/v5/v11` 的分歧率已经在真实流量上读过一轮，**且你认得出那个数字**。
   先看 `errors` 再看分歧率：一个坏掉的 shadow 会给出 `divergenceRate = 0%`（"可以切流了"）。
2. 阶梯事件有结论（Phase 2 前置条件 ①）—— 它今天仍然零消费者，`V11CutoverTest` 里那条断言就是这道题。
3. `V11CutoverTest` 全绿。它回答的是"如果明天就切会不会当场炸"，与"该不该切"是两件事。

**顺序**：一次翻一组，翻完看一轮再翻下一组 —— 合成一个开关的后果不是省事，是切流那天无法归因。

1. `app.v11.runtime.enabled=true`（`shadow` 同时置 false）：送达主链换手。看 `turns` 段是否开始有数。
2. `app.v11.turns.enabled=true`：三句话并成一个回合。
3. `app.v11.cognition.enabled=true`：**从这里开始"她回不回"由新决策决定**。盯 `wouldSilence` ——
   切流后它就是"她安静了多少"，而这个数大到某个程度不是"更真实"，是"她坏了"。
4. `app.v11.proactive.enabled=true`：最后翻它。前三个只改"她怎么回"，这一个改"她会不会先开口"。

**回滚**：把翻过的开关翻回 `enabled=false` 再部署。**不要**用"只把 `shadow` 改回 true"当回滚 ——
那会同时留下两个消费者（新链投信 + 老链 tick），症状是她一次说两条。

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
- [x] Phase 3 —— 连续意识。交付 9 个类 + 58 条用例; 默认
      `app.v11.turns.enabled=false / shadow=true`（跑聚合状态机、记合并率, 认知仍是一次
      送达一次）。三句话并成一个回合的验收有正反两条用例。**Phase 2 → Phase 3 的开关
      依赖方向写进了代码**: `runtime.enabled=false` 时 `turns.enabled=true` 不会有任何效果
      （回合合并长在 V11 送达主链上），启动打 WARN、诊断端点的 `turns` 段直说。
      `GET /api/companions/{id}/v5/v11` 新增 `turns` 段：内存计数器（本次启动）与
      落库累计值（跨重启）并列 —— 只给一个的话，一次部署会让合并率看起来突然变 0 或翻倍。
- [x] Phase 4 —— 认知重构。交付 6 个类 + 78 条用例；默认
      `app.v11.cognition.enabled=false / shadow=true`（算决策、记与老链的差异，回复行为一字未变）。
      `advanceMind()` 成为认知入口的真实实现，`process()` 降为**委托适配器**（Phase 6 删）。
      原流程一行未删，插入的是两件事：把"她决定做什么"从**流程走向**变成一个值
      （`CognitiveDecision`，10 个动作、7 个老链 return 点的语义区分）；`enabled=true` 时
      该值**接管**"回不回" —— 非回复类决策当场了结，老链那三个判断不再参与，
      否则"接管"会退化成"多一道闸"。
      **接管之后仍然有效的闸门**（写进了 `advanceMind` 的 javadoc）：没写出来 / Reality 冲突 /
      状态版本冲突 / 输出验证不通过 —— 它们是**执行失败**而不是决策，V11 今天还没把它们
      建模成动作（§24.6 Action 化是后面的事）。
      与既有文档对齐的三处语义见上面 §Phase 4 的"开工后确定下来的三处语义"。
      切流判据是 `wouldSilence`（老链会回、新决策不回），与 `wouldSpeak`、`errors`
      **分开计数** —— 一个坏掉的 shadow 会表现为"差异率 0%，可以切流了"。
      **未做**：`enabled=true` 的端到端测试（与 Phase 2 的第 ③ 条前置条件同一条）。
- [x] Phase 5 —— 主动行为。交付 8 个类 + 80 条用例；默认
      `app.v11.proactive.enabled=false / shadow=true`（新链投信、消费者只记账不动手，
      老链 tick 照跑并记进对照账本 —— shadow 期**老链必须继续跑**，否则"对照"没有第二边，
      那会是一次开关看起来还关着的静默切流）。
      三条触发器（Life / OpenLoop / Wakeup）全部产出**事件**而不是直接聊天，
      仓库里第一次有了真的 `AgentInboxConsumer`：在那之前投出去的信没有收信人。
      确认了 `ProactiveActionConsumer` **刻意不碰阶梯事件**（`USER_MESSAGE_*` 仍然零消费者）
      —— 加一个上去会让同一条消息被处理两次，而"她偶尔回你两条"没人会当故障报上来；
      有一条用例遍历 `AgentEventType.values()` 把这个留白钉住。
      **偏离**：没有单独的 Relationship emitter，理由与替代路径见上面 §Phase 5。
      **未做**：LLM 抽取 `open_loops`、`INTENTION_ACTIVATED` 的生产者、
      `enabled=true` 的端到端测试（与 Phase 2 的第 ③ 条前置条件同一条）。
- [x] Phase 6 —— 旧链清理。**本阶段的结论是"切流之前不删老链"**, 理由逐条写死了:
      老链今天是唯一真的在回话的那条路(`MessagePipeline` / `AgentRuntime.process()` /
      `BehaviorTickJob` / 五个 `PersonDecision` 策略, 四处具体依赖见上面那张表)。
      所以交付的是四件能做的事, 而不是一次大删除 ——
      ① **切流演练** `V11CutoverTest`(5 条用例, 真库 + 真上下文, 换的是**配置**不是对象,
      所以它检查的是"配置文件到行为"那条线, 而那条线上写错一个字母不会有任何报错);
      ② 演练当场逮到的一个真缺陷: `DigitalHumanTestApplication` 手写 `@ComponentScan`
      压掉了元注解上的 `TypeExcludeFilter`, 于是别的测试里嵌套的 `@TestConfiguration`
      漏进了**所有** 31 个 `@SpringBootTest` 的上下文 —— 修法已写明, 安全性有证据
      (全仓只有一个 `@TestConfiguration`, 且它自己的测试用 `@Import` 显式引入);
      ③ 删掉四个零调用者的适配器(`CognitiveDecision.from*`) —— 它们的注释声称自己是
      shadow 差异率的来源, 而那是**错的**: 真正的对照比的是"会不会写出一条消息出去",
      不比决策名字; 同时**保留**同样零调用者但被另一个仓的契约 javadoc 点名的
      `AttentionPolicies`;
      ④ 删除清单(每一项带解锁条件) + 切流 runbook(含回滚), 其中三个老定时器的
      对应关系**未核实**, 如实标出。
      另: `V11RuntimeSwitch` 补了 `isEffective()` —— 四个开关横着读的地方不该有
      "这一个是 isEnabled, 那三个是 isEffective" 这种记忆负担。
      ⑤ **真实流量上顺手撞见的一件事(与 V11 无关, 但是 V11 的账本把它显形了)**:
      **同一条用户消息会被老链完整地算两遍**, 双份 LLM 调用(情绪评估/记忆召回/大脑决策/
      表达生成)、双份记忆抽取、双份台阶计数 —— 用户看不到("她回两条"没有发生,
      会话里始终只有一条回复), 账单看得到。证据链(2026-09-18 21:25, 小满):
      21:25:28 仓 1 `MessageCoreService` 落库后 `afterCommit` 走 live 通知,
      **同一个事务里**又往 `outbox_event` 入了一行兜底(`dedupKey` 与 live 的 eventId 同一个);
      21:25:29–21:25:44 认知链第一遍跑在**她自己的 actor 线程**上, 21:25:44 回复落库;
      21:25:44.539–21:25:59 第二遍**完整**跑在 `scheduling-1` 上, 跑完 outbox 才
      `markPublished`(21:25:59); `processed_event` 只有一行 `routed`, 时间戳 21:25:44.502689。
      根因在读代码时是确定的: `RoutingHandler.handle` **先** `eventRouter.route(event)`
      **再**写 `processed_event`, 而 `route` 是同步穿过整条认知链的 —— 于是"这条已处理过"
      这条记录 <b>要等认知跑完才存在</b>, 而 `OutboxRelayJob` 默认每 5 秒就来问一次
      "这行还没发出去吧"。**任何一次处理时长超过一个 tick 的送达都会被算两遍**,
      这解释了那 15 秒的间隔, 不是巧合。
      两处附带观察, 一并记在这里: (a) 第二遍把整条认知(十几秒)跑在
      **池大小为 1 的调度线程**上 —— 这与 `V11TurnSealJob` 用 javadoc 立下的
      "定时线程上不许跑认知" 是同一条纪律, 老链的 outbox 中继今天正违反它;
      (b) 第二遍**没有**产出第二条消息, 幂等的接点在回复落库那一侧
      (最可能是仓 1 `addMessage(..., clientMessageId)` 的同键幂等, **未逐行追证**)。
      **本期不修**, 理由是纪律而不是难度: 把 `processed_event` 提到 `route` 之前是一个
      "先声明再执行"的改动, 一旦认知抛异常, 那封信就永远失去了 outbox 的兜底重投
      —— 正确的修法是 claim/lease(`status=PROCESSING` + 心跳)而不是调换两行,
      那是 V10 §21.2/21.3 去重层自己的设计任务。而它改的是**老链**的行为, 而老链今天
      仍是唯一在回话的那条路 —— 在切流之前动它, 正是本阶段反复拒绝的那件事。
      切流之后这件事自然消失: V11 的信箱路径一次投递只有一封信, 一个消费者。
- [x] 全量测试 + 两仓部署 + 截图 + 全部 agent 关闭
      —— 全量 774 条(729/37/8)全绿; 仓 2 已部署(两个 systemd 服务重启, 四条 `[V11]` shadow
      启动行都在, `check-openapi.sh` / `check-console.sh` 全过); 仓 1 未被 V11 改动, 无需重发;
      真机演示: 一条真实消息走 8081→8091, 影子账本记下判定, 她回了话(截图见交付说明);
      54 个 companion 全部 `paused`(0 active)。
      唯一不是"绿"的一格, 如实写在第 ⑤ 条里: 那次演示同时暴露了老链的重复处理。
