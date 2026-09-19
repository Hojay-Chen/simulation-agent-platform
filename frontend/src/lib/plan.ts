/**
 * 计划表(PlanBoard)在界面上怎么变成一条时间轴 —— 全是纯函数, 有测试。
 *
 * <h2>为什么时间轴要自己算</h2>
 *
 * V2.2 §3.5 的计划项是**时间段**(`TimeWindow`: start_at / end_at + 期望时长), 不是
 * 时间点。这一点决定了三件事, 三件都不能靠一个 `<ul>` 列表糊过去:
 *
 * 1. **会重叠**。她可以一边洗衣服一边听播客(约束图允许的事项)。重叠必须在视觉上
 *    分开, 否则后画的那条会把前一条盖住 —— 用户看到的是"她只做了听播客"。
 * 2. **有进度与打断状态**。同一段时长里, 已过去的和还没到的是两种东西。
 * 3. **可以被改**。§3.5.6 的重新规划产出的是**新的 Revision**, 所以要能画"改之前"
 *    和"改之后"两份, 并让人一眼看出哪一条动了。
 *
 * <h2>为什么车道数是最少的那个</h2>
 *
 * 贪心首次适配(first-fit)对区间图恰好给出最优解 —— 任意时刻并发的最大条数。这意味着
 * "今天她最多同时做 2 件事"时, 时间轴就正好两行, 不会出现一条空行占着高度却什么都不放。
 * 换成"按顺序往下排"会得到 5 行里有 4 行是空的, 那种页面没人愿意看。
 *
 * <h2>时间基准是参数, 不是 Date.now()</h2>
 *
 * 每个函数都要求显式传入 `now`。理由是同一页要能画出"今天 12:00 那一刻的样子",
 * 而渲染期间调 `Date.now()` 会让"现在"这条线在两次渲染之间移动 —— 一条会自己走动的
 * 红线看起来像加载动画, 而不是一个事实。
 */

/**
 * 计划项的最小形状。
 *
 * 字段名照抄 `GET /api/companions/{id}/life` 的 `todayActivities[]`, 不做改名 ——
 * 中间多加一层映射表, 端点哪天加一个字段就得改两处, 而漏改的表现是"字段不见了"
 * 而不是报错。
 */
export interface PlanActivity {
  title: string
  /** ISO 8601。后端给不出时为空串 —— 那种项排在末尾并明确标注。 */
  plannedStart?: string
  plannedEnd?: string
  type?: string
  status?: string
  attentionDemand?: string
  interruptibility?: string
  phoneAvailability?: string
  moodEffect?: string
  progress?: number
  interrupted?: boolean
  importance?: number
  emotionalSignificance?: number
}

/** 一条已经落到像素坐标上的条。`left`/`width` 是 0–100 的百分比。 */
export interface LaidOut<T> {
  item: T
  /** 第几行(0 起)。同一行的任意两条在时间上不重叠。 */
  lane: number
  left: number
  width: number
  /** 时段缺失时为 true —— 界面上画在"未排期"区, 不能画进轨道。 */
  unscheduled: boolean
}

export interface DayLayout<T> {
  rows: LaidOut<T>[]
  /** 需要几行 —— 也就是"她今天最多同时在做几件事"。 */
  laneCount: number
}

const MIN_WIDTH_PCT = 0.6

function ms(v: string | undefined | null): number | null {
  if (!v) return null
  const t = Date.parse(v)
  return Number.isFinite(t) ? t : null
}

/** 一段区间。两个端点都是毫秒时间戳。 */
export interface Span {
  start: number
  end: number
}

/** 两个区间是否真的重叠。端点相接(一个 12:00 结束、另一个 12:00 开始)**不算**重叠。 */
export function overlaps(a: Span, b: Span): boolean {
  return a.start < b.end && b.start < a.end
}

/**
 * 首次适配的车道分配。
 *
 * 输入的区间下标与输出的车道下标一一对应 —— 调用方靠这个下标把结果配回原来的项。
 * 空输入得到空数组(而不是抛错): "她今天什么都没安排"是一个合法状态, 而且是个
 * 值得看的状态。
 */
export function assignLanes(spans: readonly Span[]): number[] {
  const order = spans
    .map((s, i) => ({ s, i }))
    // 先按开始时间; 同开始的先放**长的** —— 先把长的安置好, 短的更容易插进缝隙,
    // 于是总行数更少。反过来会让一行被一个短条占住开头, 长条被迫另起一行。
    .sort((a, b) => a.s.start - b.s.start || b.s.end - a.s.end || a.i - b.i)

  const lanes: Span[][] = []
  const out = new Array<number>(spans.length).fill(0)

  for (const { s, i } of order) {
    let placed = -1
    for (let l = 0; l < lanes.length; l++) {
      const last = lanes[l][lanes[l].length - 1]
      if (!overlaps(last, s)) {
        lanes[l].push(s)
        placed = l
        break
      }
    }
    if (placed < 0) {
      lanes.push([s])
      placed = lanes.length - 1
    }
    out[i] = placed
  }
  return out
}

/**
 * 一天的横轴该画多宽。
 *
 * 不按 0:00–24:00 铺 —— 一张"只有 9:00–18:00 有内容"的时间轴若按时钟铺满, 中间那些
 * 活动会挤成不可辨认的几个像素, 而上下各留一大片空白。让轨道跟着内容走, 但**左右
 * 两端各留 5%(最少 15 分钟)**: 贴着边画的条看起来像被裁掉了。
 *
 * 一条内容都没有时给以"现在"为中心的 6 小时 —— 那句"今天还没排事"背后仍然有一条
 * 真实的时间轴, 而不是一片空白。
 *
 * 单独导出(而不是只在 `layoutDay` 里算)是因为**轴、刻度、"现在"线、A 类带子**都必须
 * 用同一个窗口。各自算一次的后果是条子和刻度对不上, 而那种错位看起来像数据出了问题。
 */
export function dayWindow(spans: readonly Span[], now?: number): Span {
  const valid = spans.filter((s) => Number.isFinite(s.start) && Number.isFinite(s.end) && s.end > s.start)
  if (valid.length === 0) {
    const anchor = now ?? Date.now()
    return { start: anchor - 3 * 3_600_000, end: anchor + 3 * 3_600_000 }
  }
  const lo = Math.min(...valid.map((s) => s.start))
  const hi = Math.max(...valid.map((s) => s.end))
  const pad = Math.max((hi - lo) * 0.05, 15 * 60_000)
  return { start: lo - pad, end: hi + pad }
}

/**
 * 把一堆计划项摆到一条时间轴上。
 *
 * `window` 缺省时用 `dayWindow()` 从内容推。
 */
export function layoutDay<T>(
  items: readonly T[],
  getWindow: (item: T) => Span | null,
  window?: Span,
  now?: number,
): DayLayout<T> {
  const timed: { item: T; span: Span }[] = []
  const untimed: T[] = []
  for (const item of items) {
    const span = getWindow(item)
    if (span && span.end > span.start) timed.push({ item, span })
    else untimed.push(item)
  }

  const w = window ?? dayWindow(timed.map((t) => t.span), now)

  const span = w.end - w.start
  const lanes = assignLanes(timed.map((t) => t.span))

  const rows: LaidOut<T>[] = timed.map((t, i) => {
    // 夹到窗口内再算百分比: 一条越界的项(比如跨夜的计划)若直接算, 会得到
    // 一个负数宽度或超过 100 的右端, 画出来是一条横穿整页的色块。
    const start = Math.max(t.span.start, w.start)
    const end = Math.min(t.span.end, w.end)
    const left = ((start - w.start) / span) * 100
    const width = Math.max(((end - start) / span) * 100, MIN_WIDTH_PCT)
    return { item: t.item, lane: lanes[i], left, width, unscheduled: false }
  })

  for (const item of untimed) {
    rows.push({ item, lane: 0, left: 0, width: 0, unscheduled: true })
  }

  const laneCount = timed.length === 0 ? 0 : Math.max(...lanes) + 1
  return { rows, laneCount }
}

/** 一个事件流里带时间的那种项。 */
export interface TimedEventLike {
  type: string
  at?: string
  payload?: Record<string, unknown> | null
}

/**
 * A 类(持续影响)在时间轴上画成一条**带**而不是一个点。
 *
 * 难点在于 A 类事件在线上**没有结束时间** —— "外面 16℃"这条影响从它发生那一刻起
 * 一直有效, 直到下一件改变它的事件为止。所以做法是: 按发生时刻排序, 每一条的带子
 * 一直画到**同类型的下一条**, 最后一条画到 `until`。
 *
 * 这正好复现了 §5.3.1 说的 `ContinuousEffectLedger` 是个"集合 + 计算"而不是队列:
 * 后面来一条新温度, 前面那条**就地失效**, 而不是被消费掉。画成带子以后, 这件事在
 * 界面上是看得见的 —— 用户可以指着说"这条影响的寿命就是这一段"。
 */
export function effectBands<T extends TimedEventLike>(
  events: readonly T[],
  typeOf: (e: T) => string,
  keyOf: (e: T) => string,
  until: number,
): { key: string; type: string; start: number; end: number; from: T }[] {
  const byType = new Map<string, { t: number; e: T }[]>()
  for (const e of events) {
    const t = ms(e.at)
    if (t === null) continue
    const type = typeOf(e)
    const arr = byType.get(type)
    if (arr) arr.push({ t, e })
    else byType.set(type, [{ t, e }])
  }

  const out: { key: string; type: string; start: number; end: number; from: T }[] = []
  for (const [type, list] of byType) {
    list.sort((a, b) => a.t - b.t)
    for (let i = 0; i < list.length; i++) {
      const start = list[i].t
      // 下一条同类型的影响把它顶掉。顶掉它的那条若在它之前就已经生效
      // (乱序到达), 这里得到的是一个零长或负长的区间 —— 直接丢掉:
      // 画一条零宽的带子只会让人以为渲染坏了。
      const end = i + 1 < list.length ? list[i + 1].t : until
      if (end <= start) continue
      out.push({ key: keyOf(list[i].e), type, start, end, from: list[i].e })
    }
  }
  return out.sort((a, b) => a.start - b.start)
}

// ── 重新规划: 两份快照的差异 ────────────────────────────────────────────────

/** 计划项在快照里的规范形状。 */
export interface PlanSnapshotItem {
  /** 身份。**用 title**: 线上只给了 title, 没有 itemId(见报告里的接口缺口)。 */
  key: string
  title: string
  start: number | null
  end: number | null
  status: string
  progress: number
}

export type DiffKind = 'added' | 'removed' | 'moved' | 'resized' | 'kept'

export interface DiffRow {
  key: string
  kind: DiffKind
  title: string
  before: PlanSnapshotItem | null
  after: PlanSnapshotItem | null
}

export const DIFF_META: Record<DiffKind, { label: string; hint: string }> = {
  added: { label: '新增', hint: '这一版计划里多出来的。可能是她自己加的, 也可能是插进来的。' },
  removed: { label: '删除', hint: '这一版里没有了。不是"跳过" —— 跳过会留在表上, 删除是它真的不在她今天的打算里了。' },
  moved: { label: '移了时间', hint: '同一件事, 开始时间变了。§3.5.6 里"把作业的开始时间设成穿完衣服的时间"就是这个。' },
  resized: { label: '改了时长', hint: '开始没变, 但占用的时间变长或变短了。' },
  kept: { label: '没动', hint: '两版之间完全一致。' },
}

/**
 * 从 `todayActivities[]` 取一份可比较的快照。
 *
 * 两份快照之间**没有时间戳也没有版本号** —— 后端目前不暴露 Revision(见报告),
 * 所以"改动前 / 改动后"这两份是**界面上自己抓的**: 你在这一页做出打断动作之前,
 * 页面先把当前这份存下来, 动作之后重新拉一次, 两份一比就是这次重新规划的效果。
 *
 * 这当然不如读服务端的 revisionHistory(那个能回溯到任意一版), 但它诚实: 界面上
 * 显示的差异**确实**是这一次动作造成的, 而不是猜的。等 §3.5.5 的
 * `GET /api/companions/{id}/plan/revisions` 落地, 这个函数就该被删掉。
 */
export function snapshot(activities: readonly PlanActivity[]): PlanSnapshotItem[] {
  return activities.map((a) => ({
    key: a.title,
    title: a.title,
    start: ms(a.plannedStart),
    end: ms(a.plannedEnd),
    status: a.status ?? '',
    progress: typeof a.progress === 'number' ? a.progress : 0,
  }))
}

/** 同一时刻两版计划的逐条对照。顺序: 按改动的大小排, "没动"垫底。 */
export function diffPlan(
  before: readonly PlanSnapshotItem[],
  after: readonly PlanSnapshotItem[],
): DiffRow[] {
  const b = new Map(before.map((i) => [i.key, i]))
  const a = new Map(after.map((i) => [i.key, i]))
  const rows: DiffRow[] = []

  for (const item of after) {
    const old = b.get(item.key)
    if (!old) {
      rows.push({ key: item.key, kind: 'added', title: item.title, before: null, after: item })
      continue
    }
    // 先判移动再判时长: 一次"往后推半小时"会同时改掉 start 和 end, 那是**移**,
    // 不是"又移又变长"。把两件事分开报会让一次挪动看起来像两次改动。
    const moved = old.start !== item.start
    const resized = !moved && old.end !== item.end
    rows.push({
      key: item.key,
      kind: moved ? 'moved' : resized ? 'resized' : 'kept',
      title: item.title,
      before: old,
      after: item,
    })
  }
  for (const item of before) {
    if (!a.has(item.key)) {
      rows.push({ key: item.key, kind: 'removed', title: item.title, before: item, after: null })
    }
  }

  const rank: Record<DiffKind, number> = { added: 0, removed: 1, moved: 2, resized: 3, kept: 4 }
  return rows.sort((x, y) => rank[x.kind] - rank[y.kind] || x.key.localeCompare(y.key, 'zh'))
}

/** 这次重新规划到底改了什么 —— 给一句话摘要用。 */
export function diffSummary(rows: readonly DiffRow[]): {
  changed: number
  byKind: Record<DiffKind, number>
  headline: string
} {
  const byKind: Record<DiffKind, number> = { added: 0, removed: 0, moved: 0, resized: 0, kept: 0 }
  for (const r of rows) byKind[r.kind]++
  const changed = rows.length - byKind.kept

  let headline: string
  if (rows.length === 0) headline = '没有拿到可比较的计划项。'
  else if (changed === 0) headline = '计划表没有变化 —— 她看了这一版, 决定照原样过。'
  else {
    const parts: string[] = []
    if (byKind.added) parts.push(`加了 ${byKind.added} 项`)
    if (byKind.removed) parts.push(`删了 ${byKind.removed} 项`)
    if (byKind.moved) parts.push(`挪了 ${byKind.moved} 项`)
    if (byKind.resized) parts.push(`改了 ${byKind.resized} 项的时长`)
    headline = `她重排了计划: ${parts.join(', ')}。`
  }
  return { changed, byKind, headline }
}

// ── 展示用的小件 ────────────────────────────────────────────────────────────

/** `HH:MM`, 本地时区。时间轴上的刻度用它 —— 带日期会挤成一团。 */
export function fmtClock(msOrIso: number | string | null | undefined): string {
  const t = typeof msOrIso === 'number' ? msOrIso : ms(msOrIso)
  if (t === null) return '--:--'
  const d = new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** 时长, 说人话。"1 小时 30 分" 而不是 "90min" 或 "PT1H30M"。 */
export function fmtDuration(a: number, b: number): string {
  const mins = Math.max(0, Math.round((b - a) / 60_000))
  if (mins < 60) return `${mins} 分钟`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m === 0 ? `${h} 小时` : `${h} 小时 ${m} 分`
}

/**
 * 计划状态 → 视觉。未知状态归 `planned`。
 *
 * **不把 `interrupted` 折进来** —— 它是一条独立的布尔, 可以和一个 `done` 同时为真
 * ("做完了, 但中途被打断过")。合进状态枚举会把那种情况丢掉一半信息。
 */
export type PlanTone = 'planned' | 'active' | 'done' | 'cancelled'

const TONE_OF_STATUS: Record<string, PlanTone> = {
  pending: 'planned',
  planned: 'planned',
  scheduled: 'planned',
  active: 'active',
  in_progress: 'active',
  running: 'active',
  done: 'done',
  completed: 'done',
  finished: 'done',
  cancelled: 'cancelled',
  canceled: 'cancelled',
  skipped: 'cancelled',
  superseded: 'cancelled',
}

export function toneOfStatus(status: string | undefined | null): PlanTone {
  return TONE_OF_STATUS[(status ?? '').trim().toLowerCase()] ?? 'planned'
}

export const TONE_META: Record<PlanTone, { label: string; bar: string; text: string }> = {
  planned: { label: '打算做', bar: 'bg-accent/25 border-accent/50', text: 'text-accent' },
  active: { label: '正在做', bar: 'bg-accent/70 border-accent', text: 'text-accent-ink' },
  done: { label: '做完了', bar: 'bg-ok/50 border-ok', text: 'text-ok' },
  cancelled: { label: '没做/被顶掉', bar: 'bg-sunken border-line-strong', text: 'text-ink-faint' },
}
