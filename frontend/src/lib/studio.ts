/**
 * Being Studio 的**目录层** —— 哪个标签页读哪几个接口, 以及首页的那几个数怎么算。
 *
 * <h2>为什么把"读什么"从"怎么画"里拆出来</h2>
 *
 * 因为八个标签页里, 有七个的形状是一模一样的: 读 N 个端点, 每个端点渲染成一块。
 * 把它们写死在各自的 JSX 里, 会得到八份 95% 相同的代码; 而拆成这张表之后,
 * "加一个端点"变成改一行数据, 页面组件从头到尾不必知道存在哪些接口。
 *
 * 这张表也是**可以测的** —— key 重不重复、有没有空标签页、同一个端点会不会被
 * 加载两次, 全是纯函数层面的事实。`studio.test.ts` 逐条钉住。本仓前端没有 jsdom,
 * 写在 JSX 里的结构断言一条也跑不起来。
 *
 * <h2>Skills 与 Applications 的数据从哪来</h2>
 *
 * 不是从本平台的库里 —— 是 server:8091 经 contract 的 `ApplicationRuntimePort`
 * 走 HMAC 去问**聊天平台**要 (LAP: 能力 → 应用 → 动作)。这条读取链早就存在
 * (认知链调用应用走的就是它), 只是从来没有一个只读的出口。控制台这一版把它
 * 接到 `GET /api/lap/catalog` 上 —— 一次调用返回整棵树, 而不是让前端按
 * 能力-应用两层循环去打 N+1 次。
 */

import {
  getAgentStateFull,
  getLapCatalog,
  getLife,
  getMetrics,
  getRelationship,
  getRelationshipNarrative,
  getSelfModel,
  listExperiences,
  listLifeEvents,
  listOpenLoops,
  listPersonaVersions,
  listPromises,
  listReflections,
  listRelationshipEvents,
  listSharedExperiences,
  listTraces,
  listWorldEvents,
  type Companion,
} from '@/api/client'

/** 一个面板 = 一次取数 + 一段说明。`load` 里的 id 是 **agent id**, 不是账号ID。 */
export interface SectionSpec {
  /** 标签页内唯一 —— 同时用作 React key 与出错时的定位信息。 */
  key: string
  title: string
  load: (agentId: string) => Promise<unknown>
  /** 这块在讲什么。服务端的字段名是英文, 中文解释放在这里, 而不是塞进 format 词典。 */
  hint?: string
  /** 这块本来就可能是空的 —— 说清楚是"还没发生"还是"不该有"。 */
  empty?: string
}

export const AGENT_TABS = [
  { id: 'overview', label: '总览' },
  { id: 'identity', label: '身份' },
  { id: 'personality', label: '人格' },
  { id: 'memory', label: '记忆' },
  { id: 'relationship', label: '关系' },
  { id: 'life', label: '生活' },
  { id: 'skills', label: '技能' },
  { id: 'activity', label: '活动' },
] as const

export type AgentTab = (typeof AGENT_TABS)[number]['id']

const TAB_IDS = AGENT_TABS.map((t) => t.id) as readonly string[]

/**
 * URL 里的 `?tab=` 收敛成一个已知值。
 *
 * 这里**必须**兜底而不是报错: 标签页是用户手改 URL 能碰到的东西, 一个拼错的
 * tab 值弹出一整页错误, 会让人以为 agent 读不出来 —— 而实际只是拼错了。回到
 * 总览, 用户自己会发现。
 */
export function tabOf(raw: string | null | undefined): AgentTab {
  return TAB_IDS.includes(raw ?? '') ? (raw as AgentTab) : 'overview'
}

const SECTIONS: Record<AgentTab, SectionSpec[]> = {
  overview: [
    {
      key: 'state',
      title: '实时状态',
      load: getAgentStateFull,
      hint: '由 server:8091 的认知链持续写入。它只在这里显示, 打开本页不会触发任何认知。',
      empty: '认知链还没为这个 agent 建出 state —— 它收到第一条消息后才会出现。',
    },
    {
      key: 'metrics',
      title: '指标',
      load: getMetrics,
      hint: '认知链自己的计数器(轮次、命中率、耗时)。',
    },
  ],
  // 身份页由页面自己提供: 页头(名字/账号ID)读的也是同一条档案, 让这张表再取一次
  // 同一个端点只是白打一次请求。见 AgentDetail 的 identity 分支。
  identity: [],
  personality: [
    {
      key: 'persona-versions',
      title: '人格版本',
      load: listPersonaVersions,
      empty: '还没有人格版本。',
    },
    {
      key: 'self-model',
      title: '自我模型',
      load: getSelfModel,
      hint: '她对自己的描述 —— 由认知链从长期记忆里归纳, 不等同于上面那份被编译出来的人格。',
      empty: '认知链还没归纳出自我模型。',
    },
  ],
  // 记忆页是唯一带输入框的标签页(搜索), 由 MemoryTab 单独实现, 这里空着。
  memory: [],

  relationship: [
    { key: 'relationship', title: '关系', load: getRelationship },
    {
      key: 'narrative',
      title: '关系叙事',
      load: getRelationshipNarrative,
      hint: '把一堆事件讲成一段话 —— 认知链自己写的。',
      empty: '还没有生成叙事。',
    },
    {
      key: 'events',
      title: '关系事件',
      load: listRelationshipEvents,
      empty: '还没有值得记为事件的事。',
    },
    {
      key: 'shared-experiences',
      title: '共同经历',
      load: listSharedExperiences,
      empty: '还没有共同经历。',
    },
    {
      key: 'promises',
      title: '承诺',
      load: listPromises,
      hint: '她答应过的事。未兑现的会一直留在这里。',
      empty: '没有未兑现的承诺。',
    },
  ],
  life: [
    { key: 'life', title: '生活状态', load: getLife, empty: '生活线还没启动。' },
    { key: 'life-events', title: '生活事件', load: listLifeEvents, empty: '还没有生活事件。' },
    {
      key: 'world-events',
      title: '世界事件',
      load: listWorldEvents,
      hint: '世界自己发生的事 —— 不是她做的, 但她会知道。',
      empty: '世界还很安静。',
    },
    {
      key: 'open-loops',
      title: '未完之事',
      load: listOpenLoops,
      hint: '开了口没合上的事(约好的、答应过的、被打断的)。',
      empty: '没有悬着的事。',
    },
  ],
  skills: [
    {
      key: 'lap-catalog',
      title: '应用平台能力',
      load: () => getLapCatalog(),
      hint: '来自**聊天平台**的应用平台(LAP)。这份目录是平台能提供什么, 不等于这个 agent 正在玩什么 —— 后者要等它真的开了一局才有会话。',
      empty: '应用平台现在没有登记任何能力。要么它还没接上来, 要么目录是空的。',
    },
  ],
  activity: [
    { key: 'traces', title: '认知轨迹', load: listTraces, empty: '还没有轨迹。' },
    {
      key: 'reflections',
      title: '反思',
      load: listReflections,
      empty: '她还没反思过什么。',
    },
    { key: 'experiences', title: '经历', load: listExperiences, empty: '还没有积累经历。' },
  ],
}

/**
 * 由**页面**而不是这张表提供的标签页。
 *
 *   - `identity`: 页头(名字 + 账号ID)读的就是这条档案, 让表再取一次同一端点只是白打
 *     一次请求, 还会让页头与档案页在极端情况下显示两个不同的名字。
 *   - `memory`: 唯一带输入框的一页(搜索), 形状与其余七页不同。
 *
 * 列成一个常量是为了让"哪些页是空的需要专门处理"成为可断言的事实 —— 否则某天有人
 * 往表里加了一个空页, 页面上会出现一个点进去什么都没有的标签。
 */
export const COMPONENT_TABS: readonly AgentTab[] = ['identity', 'memory']

/** 一个标签页里的所有面板。空数组 = 该标签页由专门组件实现(见 COMPONENT_TABS)。 */
export function sectionsOf(tab: AgentTab): SectionSpec[] {
  return SECTIONS[tab]
}

/**
 * 记忆行里出现过的类型, 用来生成筛选按钮 —— 顺序按出现次数降序, 同数按名字。
 *
 * 从**数据**里推而不是写死一张表: 类型是认知链自己定的(episodic / semantic / …),
 * 写死的那张表会在认知链加一种新记忆时静默漏掉它。
 */
export function distinctTypes(rows: readonly { type?: string }[]): string[] {
  const bucket = new Map<string, number>()
  for (const r of rows) {
    const t = (r.type ?? '').trim()
    if (t) bucket.set(t, (bucket.get(t) ?? 0) + 1)
  }
  return [...bucket.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([t]) => t)
}

// ── 首页的账 ────────────────────────────────────────────────────────────────

export interface StageBucket {
  stage: string
  count: number
}

export interface DashboardSummary {
  total: number
  withHandle: number
  /** 没有可读人格版本的 —— 建是建出来了, 但还没编译出人格。 */
  missingPersona: number
  createdLast7d: number
  /** 当前停着的 —— "还在花钱的" 就是 `total - paused`。 */
  paused: number
  stages: StageBucket[]
  newest: Companion[]
}

/** 没有关系阶段的行归到这里, 而不是显示成空白 —— "未标注"是一个真实的类别。 */
export const UNKNOWN_STAGE = '未标注'

export function stageOf(c: Companion): string {
  const s = (c.relationshipStage ?? '').trim()
  return s || UNKNOWN_STAGE
}

/**
 * 从 agent 列表里算出首页那几个数。
 *
 * `now` 是**参数**而不是 `Date.now()` —— 否则"近 7 天"这件事没法测: 测试要么
 * 依赖运行时刻(半夜跑会挂), 要么只能断言"大于等于 0"(等于没测)。
 *
 * 这里一个请求都不发: 首页要快, 而列表接口已经带回了算这几个数所需的全部字段。
 * 想加"每人多少条记忆"之类的数, 就得逐 agent 打一次 —— 那种数属于 agent 详情页,
 * 不属于首页。
 */
export function summarizeCompanions(list: readonly Companion[], now: number): DashboardSummary {
  const weekAgo = now - 7 * 24 * 60 * 60 * 1000
  const bucket = new Map<string, number>()
  let withHandle = 0
  let missingPersona = 0
  let createdLast7d = 0
  let paused = 0

  for (const c of list) {
    if ((c.handle ?? '').trim()) withHandle += 1
    if (!c.persona) missingPersona += 1
    // 判据必须是"等于 paused", 不是"不等于 active": 字段缺席(旧后端)与 status 为 null
    // (存量行)都表示**在跑**, 把它们算成停着的会让首页说"全部已停止"而实际上它们在烧钱。
    if (c.lifecycle === 'paused') paused += 1
    const stage = stageOf(c)
    bucket.set(stage, (bucket.get(stage) ?? 0) + 1)
    const t = c.createdAt ? Date.parse(c.createdAt) : NaN
    if (!Number.isNaN(t) && t >= weekAgo && t <= now) createdLast7d += 1
  }

  const stages = [...bucket.entries()]
    .map(([stage, count]) => ({ stage, count }))
    // 多的在前; 同数量按名字排 —— 否则 Map 的插入顺序会让同一个数的两块在不同
    // 刷新里换位置, 看起来像数据在跳。
    .sort((a, b) => b.count - a.count || a.stage.localeCompare(b.stage))

  const newest = [...list]
    .filter((c) => c.createdAt)
    .sort((a, b) => Date.parse(b.createdAt!) - Date.parse(a.createdAt!))
    .slice(0, 5)

  return { total: list.length, withHandle, missingPersona, createdLast7d, paused, stages, newest }
}
