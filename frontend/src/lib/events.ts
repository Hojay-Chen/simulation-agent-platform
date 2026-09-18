/**
 * 事件的三类、意识的四级、以及她与一条消息之间的五级台阶。
 *
 * <h2>为什么这一层必须存在</h2>
 *
 * 设计文档 §2.3 把三件事分开: **消息已被送达**(世界的事实)、**手机响了**(设备的物理
 * 行为)、**她注意到了**(她自己的认知状态)。后端把这三件事分别落在了 World 和 Human
 * 两侧, 中间由 EventFabric 连接(V2.2 §5)。但那条边界在线上的**表现**是一个扁平的
 * `{type, at, payload}` 列表 —— 三件事长得一模一样。这一层就是把那条边界翻译回界面上
 * 看得见的东西: 一条事件属于哪一类、走到台阶的第几级、还需不需要立刻管。
 *
 * <h2>为什么是一张表而不是 switch</h2>
 *
 * V2.2 §5.4 的事件目录(`CoreEventCatalog`)是**运行时注册的数据**, 不是编译期 enum,
 * 第三方可以随时注册新类型。前端不可能穷举, 所以这张表只负责把**它认识的**那些标上
 * 类别, 认不出的一律落到 `fact` 并在界面上显式写成「未分类」—— 而不是猜一个类别。
 * 猜错的代价是一条其实要立刻处理的事件被画成"不用管"。
 *
 * <h2>这张表的有效期</h2>
 *
 * §7.2 的 `world_event` 表里有一个 `category` 列(`STATE_EFFECT` / `SENSORY` /
 * `SCHEDULED`, 逗号分隔可多值)。一旦那个字段出现在返回体里, **这张表就该被删掉**,
 * 改成直接读服务端的分类 —— 因为那时候再保留一份前端副本, 两者会漂, 而漂的表现是
 * "界面说这是感官事件、调度器说不是", 没人会报告, 但每个人都觉得哪里不对。
 * 判据: 返回体里出现 `category` 字段。见 `classifyFromServer()`。
 */

/**
 * 一条事件在「World → Human」这条路上属于哪一类。
 *
 * - `fact`     世界里的既成事实, **尚未**也**无需**进入她的感知。§2.3 的第一行。
 * - `effect`   A 类, 持续影响。它不会"被消费掉" —— 外面 16℃ 会一直生效到环境变暖
 *              或她穿上衣服(§5.3.1)。
 * - `sensory`  B 类, 实时感官。需要她**立刻**处理, 走优先队列, 按 urgency 排序。
 * - `schedule` C 类, 与时间轴有关。进的是版本化时间轴, 不是队列(§5.3.3)。
 */
export type EventClass = 'fact' | 'effect' | 'sensory' | 'schedule'

/** 界面上的四类怎么画。颜色来自 `index.css` 的 `--cat-*` 语义变量。 */
export interface EventClassMeta {
  label: string
  /** 一句话说清这一类"要不要立刻管"。 */
  hint: string
  /** Tailwind 类名片段 —— 写成完整字面量, 否则 Tailwind 的扫描器看不见它们。 */
  text: string
  border: string
  bg: string
  dot: string
}

export const EVENT_CLASS_META: Record<EventClass, EventClassMeta> = {
  fact: {
    label: '世界事实',
    hint: '世界里发生了, 但她还不知道 —— 送达不等于她知道。',
    text: 'text-ink-soft',
    border: 'border-line',
    bg: 'bg-sunken',
    dot: 'bg-ink-faint',
  },
  effect: {
    label: '持续影响',
    hint: '从现在起一直生效, 不会因为处理过一次就消失。降温、饥饿、疲劳。',
    text: 'text-cat-effect',
    border: 'border-cat-effect/40',
    bg: 'bg-cat-effect/10',
    dot: 'bg-cat-effect',
  },
  sensory: {
    label: '实时感官',
    hint: '需要她立刻感知并决定怎么反应。手机响、臭味、疼。',
    text: 'text-cat-sensory',
    border: 'border-cat-sensory/40',
    bg: 'bg-cat-sensory/10',
    dot: 'bg-cat-sensory',
  },
  schedule: {
    label: '计划表',
    hint: '时间段型, 可以被插入 / 删除 / 移动 / 改时长。',
    text: 'text-cat-schedule',
    border: 'border-cat-schedule/40',
    bg: 'bg-cat-schedule/10',
    dot: 'bg-cat-schedule',
  },
}

/**
 * 类别 → 排序权重。
 *
 * 这是一个**展示用的**启发式, 不是后端的 urgency 字段 —— 后者是 §5.3.2 里
 * `RealtimeEventQueue` 的排序键, 只对 B 类事件存在。事件流要一条稳定的顺序,
 * 而"疼 > 手机响 > 计划推进 > 纯事实"这条直觉在界面上是成立的。
 *
 * 数字不写进 UI, 只用来排序 —— 一个没有出处的数字标在屏幕上就是在假装精确。
 */
const CLASS_WEIGHT: Record<EventClass, number> = {
  sensory: 3,
  schedule: 2,
  effect: 1,
  fact: 0,
}

/** 一条事件的排序权重: 它属于的所有类别里最急的那一档。 */
export function weightOf(classes: readonly EventClass[]): number {
  return classes.reduce((max, c) => Math.max(max, CLASS_WEIGHT[c]), 0)
}

/**
 * 已知事件类型 → 类别。
 *
 * 键取自后端 `runtime/WorldEventType.java`(15 个值)。这是**线上的实际取值**, 不是
 * 设计文档 §5.4 那份 47 条的目录 —— 后者要等 `CoreEventCatalog` 长出 HTTP 面
 * (`GET /api/meta/event-types`, 目前没有)。两者不是一回事: 这一张是"今天真的会
 * 出现在列表里的字符串", 那一张是"这个世界里能发生哪些事"。
 */
const CLASS_OF_TYPE: Record<string, readonly EventClass[]> = {
  // ── 一条消息到她手里的五级台阶(V11 §6.3 的意识阶梯, V2.2 §2.3 的两端) ──
  // 台阶本身不是"事件类别": RECEIVED 是世界的既成事实, NOTIFIED 才是感官刺激。
  // 把这两级画成同一种颜色, 正是现有实现最根本的那个技术债(§2.3 的反例)。
  USER_MESSAGE_RECEIVED: ['fact'],
  USER_MESSAGE_NOTIFIED: ['sensory'],
  USER_MESSAGE_NOTICED: ['sensory'],
  USER_MESSAGE_READ: ['fact'],
  USER_MESSAGE_DEFERRED: ['schedule'],

  // ── 生活 / 计划(ScheduledEvent) ──
  ACTIVITY_STARTED: ['schedule'],
  ACTIVITY_ENDED: ['schedule'],
  // 进度不是"一件事发生了", 而是"某件事正在持续" —— 与温度同一种形状。
  ACTIVITY_PROGRESS: ['effect'],
  SCHEDULED_WAKEUP: ['schedule'],

  // ── 世界 / 身体(StateEffectEvent) ──
  ENVIRONMENT_CHANGED: ['effect'],
  EMOTION_CHANGED: ['effect'],
  RELATIONSHIP_CHANGED: ['effect'],

  // ── 纯事实 ──
  WORLD_EVENT_OCCURRED: ['fact'],
  THOUGHT_FORMED: ['fact'],
}

/** 中文短名。后端给的是 SCREAMING_SNAKE 的机器名, 这里只做展示层的翻译。 */
const LABEL_OF_TYPE: Record<string, string> = {
  USER_MESSAGE_RECEIVED: '消息已送达',
  USER_MESSAGE_NOTIFIED: '手机响了',
  USER_MESSAGE_NOTICED: '她注意到了',
  USER_MESSAGE_READ: '她看了',
  USER_MESSAGE_DEFERRED: '她决定待会儿看',
  ACTIVITY_STARTED: '开始一项活动',
  ACTIVITY_ENDED: '结束一项活动',
  ACTIVITY_PROGRESS: '活动进行中',
  SCHEDULED_WAKEUP: '计划唤醒',
  ENVIRONMENT_CHANGED: '环境变化',
  EMOTION_CHANGED: '情绪变化',
  RELATIONSHIP_CHANGED: '关系变化',
  WORLD_EVENT_OCCURRED: '世界事件',
  THOUGHT_FORMED: '形成一个念头',
}

/** 已知类型的中文名。认不出时**原样返回机器名** —— 那比一个编出来的中文名有用。 */
export function eventLabelZh(type: string): string {
  return LABEL_OF_TYPE[type] ?? type
}

/**
 * 一个事件类型属于哪几类。
 *
 * 认不出 → `['fact']`。**不猜**: 一条未知事件被画成"不用立刻管"是安全的(最坏是漏看
 * 一条), 被画成"立刻要管"是危险的(会训练用户忽略这个界面)。而漏看的那条在界面上
 * 会带着它的原始机器名出现, 排查者一眼能看出这是没登记过的类型。
 */
export function classifyEvent(type: string): readonly EventClass[] {
  return CLASS_OF_TYPE[type] ?? ['fact']
}

/** 这个类型登记过吗。运维页用它标出"没登记的第三方事件" —— 见 §9 验收标准 B。 */
export function isKnownEventType(type: string): boolean {
  return type in CLASS_OF_TYPE
}

/**
 * 服务端已经把类别给了我们 —— 这时**不要**再查上面那张表。
 *
 * `world_event.category` 是逗号分隔的多值字段(§7.2), 一个事件可以同时是
 * `STATE_EFFECT,SENSORY`(手机响了就是: 要立刻听见, 且戴耳机时会有暂时性听阈偏移)。
 * 这是那条边界第一次以数据的形式出现, 值得优先采信。
 */
export function classifyFromServer(category: string | null | undefined): readonly EventClass[] | null {
  if (!category) return null
  const wanted = new Set(category.split(',').map((s) => s.trim().toUpperCase()))
  const out: EventClass[] = []
  if (wanted.has('STATE_EFFECT')) out.push('effect')
  if (wanted.has('SENSORY')) out.push('sensory')
  if (wanted.has('SCHEDULED')) out.push('schedule')
  // 认得出至少一个才算数 —— 否则回落到类型表, 而不是回一个空数组
  // (空数组在界面上会画成一条"没有类别"的事件, 那是个不存在的状态)。
  return out.length > 0 ? out : null
}

// ── 意识阶梯 ────────────────────────────────────────────────────────────────

/**
 * Attention 的四级 —— V11 §6.3 的 `NOT_AWARE / SUBCONSCIOUS / AWARE / FOCUSED`。
 *
 * 它是"她没注意到"这个状态的**唯一合法来源**(V2.2 §3.4.4)。界面上把它单独画一条
 * 阶梯, 而不是折进事件流里 —— 因为这一页回答的问题不一样: 事件流问"世界发生了什么",
 * 阶梯问"她知道了多少"。
 */
export const AWARENESS_LEVELS = ['NONE', 'SUBCONSCIOUS', 'AWARE', 'FOCUSED'] as const
export type Awareness = (typeof AWARENESS_LEVELS)[number]

export const AWARENESS_META: Record<Awareness, { label: string; hint: string }> = {
  NONE: { label: '没感知到', hint: '这件事根本没进入她。她不是不理你, 是不知道。' },
  SUBCONSCIOUS: { label: '隐约感到', hint: '背景里的一个动静, 不足以打断她手上的事。' },
  AWARE: { label: '意识到了', hint: '她知道发生了什么, 但不一定要停下来处理。' },
  FOCUSED: { label: '专注在此', hint: '这件事成了她此刻的焦点。' },
}

/**
 * 分值 → 级别。
 *
 * 阈值与后端 `PerceptionExplain` 给的那串文案是同一组
 * (`NONE<0.2 / 0.2<=SUBCONSCIOUS<0.5 / 0.5<=AWARE<0.8 / FOCUSED>=0.8`)。
 * **但界面同时把服务端那串原文显示出来** —— 阈值哪天调了, 用户看到的是两个数不一致,
 * 而不是一个悄悄画错的阶梯。硬编码一份阈值是有风险的, 藏起来风险更大。
 */
export function awarenessOf(score: number): Awareness {
  if (!Number.isFinite(score)) return 'NONE'
  if (score >= 0.8) return 'FOCUSED'
  if (score >= 0.5) return 'AWARE'
  if (score >= 0.2) return 'SUBCONSCIOUS'
  return 'NONE'
}

/**
 * 一次消息到达要走的五级台阶(V11 §6.3)。
 *
 * 它**不是**一条流水线 —— 第 4 级可以不发生(已读不回是一个决定, 不是 bug, §2.2 第 ⑫ 步)。
 * 所以界面上它画成一条可以停在任意一级的阶梯, 而不是一个进度条。
 */
export const MESSAGE_LADDER = [
  { type: 'USER_MESSAGE_RECEIVED', label: '送达', hint: '聊天平台落库了。她还不知道。' },
  { type: 'USER_MESSAGE_NOTIFIED', label: '手机响', hint: '通知信号到了她的手机, 手机按自己的策略响了。' },
  { type: 'USER_MESSAGE_NOTICED', label: '注意到', hint: '她感知到了这个声音 —— 此时她仍然不知道是谁、说了什么。' },
  { type: 'USER_MESSAGE_READ', label: '去看', hint: '她自己做出了「看一眼手机」的动作, 正文到此才第一次进入她。' },
  { type: 'USER_MESSAGE_DEFERRED', label: '待会儿看', hint: '她决定先不处理。已读不回是一个决定, 不是故障。' },
] as const

/** 一个类型在台阶上的位置; 不在台阶上返回 -1。 */
export function ladderIndexOf(type: string): number {
  return MESSAGE_LADDER.findIndex((s) => s.type === type)
}

// ── 从线上的事件流里切出三类 ────────────────────────────────────────────────

/** 事件流上一条事件的最小形状 —— 与 `GET /v5/world-events` 的返回体一致。 */
export interface WorldEventLike {
  type: string
  at?: string
  payload?: Record<string, unknown> | null
}

/**
 * 一条事件的类别: **服务端给了 `category` 就采信它**, 否则查上面那张表。
 *
 * 顺序不能反。反过来的话, 服务端把一个事件重新分类(§7.2 的 `category` 列就是为了
 * 这件事存在的)之后, 界面会继续按前端那份旧表画 —— 而两者不一致时**不会有任何
 * 报错**, 只有一条画错了颜色的记录。
 */
export function eventClassesOf(e: WorldEventLike): readonly EventClass[] {
  const raw = e.payload && typeof e.payload === 'object' ? e.payload.category : undefined
  if (typeof raw !== 'string') return classifyEvent(e.type)
  // `classifyFromServer` 每次都要 split + 建 Set, 而事件流上同一个 category 字符串
  // 会重复出现几百次。这层缓存是纯函数层面的, 没有生命周期问题 —— category 的取值
  // 只有 2³ 种组合, 所以这个 Map 的大小有天然上界。
  let hit = SERVER_CLASS_CACHE.get(raw)
  if (hit === undefined) {
    hit = classifyFromServer(raw)
    SERVER_CLASS_CACHE.set(raw, hit)
  }
  return hit ?? classifyEvent(e.type)
}

const SERVER_CLASS_CACHE = new Map<string, readonly EventClass[] | null>()

export interface SplitEvents<T> {
  /** A 类 —— 走 `effectBands()` 画成带子, 不能画成点。 */
  effect: T[]
  /** B 类 —— 画成针, 而且要单独一条轨道(它们不占时间)。 */
  sensory: T[]
  /** C 类 —— 就是时间轴上那些条本身(她今天的日程)。 */
  schedule: T[]
  /** 没进她感知的纯事实。它们进事件流的表, 但不进时间轴。 */
  facts: T[]
}

/**
 * 按类别切开一条事件流。
 *
 * <h2>一条事件可以同时进两个桶</h2>
 *
 * 这不是 bug, 是 §5.2 的核心: 一个事件可以实现多个能力接口(手机响了既需要立刻被
 * 听见, 又会在戴耳机时留下一个短时的听阈偏移)。所以这里返回的是四个**可以重叠**的
 * 桶, 而不是一次四选一的分组。调用方自己决定在哪一层画它。
 *
 * <h2>没有时间的条目被丢掉</h2>
 *
 * 时间是一条记录出现在时间轴上的前提。一条没有 `at` 的事件画上去, 位置就是编的。
 * 它会被 `facts` 那一桶接住(那个桶不看时间), 所以不会静默消失。
 */
export function splitWorldEvents<T extends WorldEventLike>(events: readonly T[]): SplitEvents<T> {
  const out: SplitEvents<T> = { effect: [], sensory: [], schedule: [], facts: [] }
  for (const e of events) {
    const cls = eventClassesOf(e)
    const timed = typeof e.at === 'string' && Number.isFinite(Date.parse(e.at))
    if (!timed) {
      // 没有时间的条目只能是"事实" —— 另外三类在时间轴上没有位置可言。
      // `fact` 是它们唯一的去处, 于是它们不会在界面上消失。
      out.facts.push(e)
      continue
    }
    if (cls.includes('effect')) out.effect.push(e)
    else if (cls.includes('sensory')) out.sensory.push(e)
    else if (cls.includes('schedule')) out.schedule.push(e)
    else out.facts.push(e)
  }
  return out
}

/** 事件流一行里那句 payload 摘要。截断在 160 字 —— 事件流是用来扫的, 不是用来读的。 */
export function summarizePayload(payload: unknown): string | null {
  if (payload === null || payload === undefined) return null
  if (typeof payload !== 'object') return String(payload).slice(0, 160)
  const entries = Object.entries(payload as Record<string, unknown>)
    // category 是事件流**自己**用来分类的字段, 把它显示出来只会让人以为那是一条数据。
    .filter(([k]) => k !== 'category')
    .map(([k, v]) => `${k}=${typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)}`)
  return entries.length === 0 ? null : entries.join(' ').slice(0, 160)
}
