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
  getLapCatalog,
  getLife,
  getMetrics,
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

/**
 * 她这一侧的八个标签页。
 *
 * <h2>顺序不是随手排的, 它是一条问问题的顺序</h2>
 *
 * 前四个是"**她此刻**"——今天怎么过、打算做什么、手机上有没有人找她、身体怎么样。
 * 这四个回答的是同一种问题, 所以它们必须连在一起、并且排在最前面: 一个想看她的人
 * 打开这一页, 想知道的是"她好吗", 而不是"她的 relationship 表的第 7 列是什么"。
 *
 * 后四个是"**她是谁**"——她的通讯录、她记得的事、她在想什么、以及一份原始档案。
 * 这一半是慢的、累积的。把它和前四个混在一起, 就会出现"她的态度"和"她的 intimacy
 * 字段"并排放在一屏里的情况, 而那是两个完全不同层次的东西。
 *
 * <h2>`archive` 是刻意留下的那个"丑"页</h2>
 *
 * 原始 JSON 没有消失 —— 它被**收敛**到最后一个标签页里。这不是偷懒: 界面上一旦
 * 有一个地方能看见原始返回体, 排查"这个字段到底有没有值"就不需要开命令行。但
 * 它只能是**一个**地方, 而且必须是最后一个 —— 否则每一页都会退化成 JSON 查看器,
 * 那正是这次重做要解决的问题。
 */
export const AGENT_TABS = [
  { id: 'today', label: '今天' },
  { id: 'plan', label: '计划表' },
  { id: 'phone', label: '手机' },
  { id: 'body', label: '身体' },
  { id: 'relationship', label: '关系网' },
  { id: 'memory', label: '记忆' },
  { id: 'mind', label: '心智' },
  { id: 'archive', label: '档案' },
] as const

export type AgentTab = (typeof AGENT_TABS)[number]['id']

const TAB_IDS = AGENT_TABS.map((t) => t.id) as readonly string[]

/**
 * 没有指定标签页时落到哪里。
 *
 * 是 `today` 而不是某个"总览": 打开一个 agent 时最该看见的东西是**她今天在做什么**,
 * 那是一句人话能回答的问题。原来的 `overview` 是一屏 JSON, 它回答的是"这个对象有哪些
 * 字段" —— 那是给调试用的问题, 不是给看她用的。
 */
export const DEFAULT_TAB: AgentTab = 'today'

/**
 * URL 里的 `?tab=` 收敛成一个已知值。
 *
 * 这里**必须**兜底而不是报错: 标签页是用户手改 URL 能碰到的东西, 一个拼错的
 * tab 值弹出一整页错误, 会让人以为 agent 读不出来 —— 而实际只是拼错了。回到
 * 默认页, 用户自己会发现。
 */
export function tabOf(raw: string | null | undefined): AgentTab {
  return TAB_IDS.includes(raw ?? '') ? (raw as AgentTab) : DEFAULT_TAB
}

const SECTIONS: Record<AgentTab, SectionSpec[]> = {
  // ── 她此刻: 全部由专门组件实现(时间轴 / 差异表 / 推演台 / 仪表) ──
  today: [],
  plan: [],
  phone: [],
  body: [],

  // 关系网是"前缀 + 表"的混血: 上面那两块是从 Reality Ledger 投影出来的事实摘要
  // 与维度条(前者需要 `userId`, 表驱动那套签名给不了), 下面四块是原来的内省接口。
  // 见 PREFIXED_TABS。
  //
  // 「关系维度」那块**没有**在这里: 它现在由 `Relation.tsx` 用 `Meter` 画成十条量
  // (与身体那页同一种画法), 而不是一张原始 JSON。留在这里会得到同一份数据画两遍,
  // 而两遍里更丑的那一遍恰好排在更显眼的位置。
  relationship: [
    {
      key: 'narrative',
      title: '她对这段关系的说法',
      load: getRelationshipNarrative,
      hint: '把发生过的事串成一段话 —— 认知链自己写的, 不是模板填出来的。',
      empty: '她还没为这段关系写过什么。',
    },
    {
      key: 'events',
      title: '发生过的事',
      load: listRelationshipEvents,
      hint: '被她的关系图记为「事件」的那些事 —— 不是每一句话都算, 只有改变了什么的才算。',
      empty: '还没有值得记为事件的事。',
    },
    {
      key: 'shared-experiences',
      title: '共同经历',
      load: listSharedExperiences,
      hint: '她记下的"你和她一起做过的事"。',
      empty: '还没有共同经历。',
    },
    {
      key: 'promises',
      title: '她答应过的事',
      load: listPromises,
      hint: '未兑现的会一直留在这里 —— 所以这一块越短越好。',
      empty: '她还没答应过什么。',
    },
  ],

  // 记忆页是唯一带输入框的标签页(搜索), 由 MemoryTab 单独实现。
  memory: [],

  // ── 她是谁: 纯表驱动, 形状与原来一致 ──
  mind: [
    {
      key: 'self-model',
      title: '她怎么看自己',
      load: getSelfModel,
      hint: '认知链从长期记忆里归纳出的、她自己写的自我描述。它与「档案」里那份被编译出来的人格是两样东西: 一个是她自己写的, 一个是给她的。',
      empty: '认知链还没归纳出她怎么看自己。',
    },
    {
      key: 'metrics',
      title: '她花了多少(认知链的计数器)',
      load: getMetrics,
      hint: '认知链自己的计数器(轮次、模型调用、命中率、耗时)。它是"这个数字人花了多少"的唯一来源。',
      empty: '认知链还没写过计数器。',
    },
    {
      key: 'traces',
      title: '她每一步的判断',
      load: listTraces,
      hint: '认知链每一步留下的记录 —— 想知道"她当时为什么这么做"就从这里翻。',
      empty: '还没有留下判断记录。',
    },
    {
      key: 'reflections',
      title: '她的反思',
      load: listReflections,
      hint: '她回过头看自己做过的事之后写下的东西。',
      empty: '她还没反思过什么。',
    },
    {
      key: 'experiences',
      title: '她的经历',
      load: listExperiences,
      hint: '她攒下来的经历 —— 与她认识谁无关, 是她自己身上发生过的事。',
      empty: '还没有积累经历。',
    },
  ],

  archive: [
    {
      key: 'persona-versions',
      title: '人格的每一版',
      load: listPersonaVersions,
      hint: '每次改人格都会落一个新版本, 旧的不删 —— 所以能看见她是怎么被改过来的。',
      empty: '还没有人格版本。',
    },
    { key: 'life-events', title: '她生活里的事', load: listLifeEvents, empty: '还没有生活事件。' },
    {
      key: 'world-events',
      title: '世界事件(原文)',
      load: listWorldEvents,
      hint: '滚动 50 条的原始列表, 未加工。它的分类画在「今天」那一页的时间轴上, 这里是原文。',
      empty: '世界还很安静。',
    },
    {
      key: 'open-loops',
      title: '未完之事',
      load: listOpenLoops,
      hint: '开了口没合上的事(约好的、答应过的、被打断的)。',
      empty: '没有悬着的事。',
    },
    {
      key: 'life',
      title: '生活状态(原文)',
      load: getLife,
      hint: '生活线接口的完整返回体, 未加工。「今天」那一页把它画成了时间轴, 这里是原文。',
      empty: '生活线还没启动。',
    },
    {
      key: 'lap-catalog',
      title: '应用能力清单(原文)',
      load: () => getLapCatalog(),
      hint: '来自聊天平台的应用能力目录。这份清单说的是平台能提供什么, 不等于这个数字人正在玩什么 —— 后者要等它真的开了一局才有会话。',
      empty: '应用平台现在没有登记任何能力。要么它还没接上来, 要么目录是空的。',
    },
  ],
}

/**
 * 完全由**页面**实现的标签页 —— 表里没有面板。
 *
 * 列成一个常量是为了让"哪些页是空的"成为可断言的事实(见 `studio.test.ts`)。
 * 否则某天有人往表里加了一个空页, 页面上会出现一个点进去什么都没有的标签。
 */
export const COMPONENT_TABS: readonly AgentTab[] = ['today', 'plan', 'phone', 'body', 'memory']

/**
 * 前面一块由组件画、后面几块仍然表驱动的标签页。
 *
 * 这一类是这次重做**必须**长出来的: 关系网的核心数据(Reality Ledger 投影)需要
 * `userId`, 而 `SectionSpec.load` 的签名只有 `agentId` —— 硬塞进去只能靠在模块级
 * 缓存一个全局的当前用户, 那是一种把依赖藏起来、出问题时最难查的写法。所以这一类
 * 页面的做法是: 组件画上面, `<Section>` 照常画下面。
 */
export const PREFIXED_TABS: readonly AgentTab[] = ['relationship']

/** 一个标签页里表驱动的那些面板。空数组 = 没有表驱动部分。 */
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
 * 关系阶段的机器名 → 中文 —— **只用于显示**。
 *
 * `stageOf()` 返回的原值一个字都不能改: 分组、统计和测试都按原值走, 这里换的只是
 * 屏幕上那两个字。认不出的阶段原样返回(与 `typeZh` 同一个做法): 编一个中文词出来,
 * 会让排查的人看不到真正的取值。
 */
const STAGE_ZH: Record<string, string> = {
  stranger: '陌生',
  friend: '朋友',
  close: '亲近',
  deeply_connected: '深度联结',
}

export function stageZh(stage: string): string {
  return STAGE_ZH[stage] ?? stage
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
