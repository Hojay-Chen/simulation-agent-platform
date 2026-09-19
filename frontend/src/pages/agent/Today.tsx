import { useMemo, useState, type ReactNode } from 'react'
import { RefreshCw } from 'lucide-react'
import { getLife, listWorldEvents, type PlanActivityView } from '@/api/client'
import {
  eventLabelZh,
  splitWorldEvents,
  summarizePayload,
  type EventClass,
} from '@/lib/events'
import {
  dayWindow,
  effectBands,
  fmtClock,
  fmtDuration,
  layoutDay,
  toneOfStatus,
  type Span,
} from '@/lib/plan'
import { useAsync, useNow } from '@/lib/useAsync'
import { Button, Empty, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { CategoryLegend, GapNote, PlanItemDetail } from '@/components/viz/panels'
import { DayTimeline, UnscheduledRows, type TimelineBand, type TimelineMarker } from '@/components/viz/Timeline'

/**
 * 「今天」—— 她的一天。
 *
 * <h2>这一页回答的唯一一个问题</h2>
 *
 * **她现在在做什么, 今天打算怎么过。** 不是"她有哪些字段"。所以页面的主体是一条
 * 时间轴, 而不是一叠卡片 —— 只有坐标能同时表达"什么时候""多久""两件事是不是同时"
 * 这三件事, 而这三件事就是"她的一天"的全部内容。
 *
 * <h2>三类事件为什么画在同一个坐标系里</h2>
 *
 * 因为它们是**同一段时间轴上的三种东西**。分成三块就没法看出它们之间的关系:
 * "手机响的那一刻她正在写作业" —— 这句话只有在同一个坐标里才成立, 而它恰恰是这一页
 * 最有价值的信息。一次打断落在一件 `interruptibility: low` 的事中间, 和落在一段
 * 空白里, 是两件完全不同的事。
 *
 * 三者的画法(带 / 针 / 条)见 `components/viz/Timeline.tsx` 的文件头。
 *
 * <h2>这一页读不到的部分</h2>
 *
 * A 类(持续影响)目前**唯一**的来源是 `world-events` 里的 `ENVIRONMENT_CHANGED`。
 * `ContinuousEffectLedger` 本身是进程内的, 没有任何读取面 —— 所以"她此刻觉不觉得冷"
 * 这个问题的权威答案在服务端存在, 而界面上只能从温度事件**推**。页面底部那块缺口
 * 说明把这件事讲清楚了, 而不是让底下那片带子看起来像权威数据。
 */
export function Today({ agentId }: { agentId: string }) {
  const now = useNow()
  const life = useAsync(() => getLife(agentId), [agentId])
  const world = useAsync(() => listWorldEvents(agentId), [agentId])
  const [selected, setSelected] = useState<string | null>(null)

  const activities = useMemo(() => life.data?.todayActivities ?? [], [life.data])
  const events = useMemo(() => world.data ?? [], [world.data])

  /*
   * 窗口只算一次, 并且由这一页同时交给时间轴与刻度。
   * 各自算一次的话, "现在"线会和条子对不上 —— 那种错位看起来像数据出了问题。
   *
   * `now` 刻意**不进依赖**: 它每 60 秒变一次, 而窗口跟着它变会让整条轴每 60 秒
   * 缩放一下。它只在"今天一条日程都没有"时充当锚点。
   */
  const win: Span = useMemo(
    () => dayWindow(spansOf(activities), Date.now()),
    [activities],
  )

  const layout = useMemo(() => layoutDay(activities, spanOf, win), [activities, win])

  const { bands, markers, facts } = useMemo(
    () => buildOverlays(events, win, now),
    [events, win, now],
  )

  const untimed = layout.rows.filter((r) => r.unscheduled).map((r) => r.item)
  const pick = activities.find((a) => a.title === selected) ?? null

  return (
    <div className="space-y-5">
      <Headline
        summary={life.data?.todaySummary}
        current={life.data?.currentActivity}
        phase={life.data?.dayPhase}
        count={activities.length}
        onRefresh={() => { life.reload(); world.reload() }}
      />

      {life.error && <p className="text-sm text-danger">{describeError(life.error)}</p>}

      {activities.length === 0 && !life.loading && (
        <Panel title="今天">
          <Empty>她今天还没有排任何事。</Empty>
        </Panel>
      )}

      {activities.length > 0 && (
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">她的一天</h2>
              <InfoTip label="这条时间轴怎么读">
                横轴是时间, 一行 = 一件可以同时进行的事。三类事件画在<b>同一个坐标系</b>里 ——
                「手机响的那一刻她正在写作业」这句话只有在同一根轴上才成立, 而那正是这一页最有价值的信息。
              </InfoTip>
            </div>
          }
        >
          <DayTimeline
            layout={layout}
            window={win}
            now={now}
            keyOf={(a) => a.title}
            labelOf={(a) => a.title}
            toneOf={(a) => toneOfStatus(a.status)}
            timeOf={timeOf}
            metaOf={(a) => (a.type ? typeZh(a.type) : null)}
            markers={markers}
            bands={bands}
            selectedKey={selected}
            onSelect={(a) => setSelected(a.title === selected ? null : a.title)}
            footer={
              <div className="mt-3 space-y-3">
                <UnscheduledRows items={untimed} keyOf={(a) => a.title} labelOf={(a) => a.title} />
                <p className="text-[11px] leading-relaxed text-ink-faint">
                  {activities.length} 项日程 · {markers.length} 次实时感官刺激 ·{' '}
                  {bands.length} 段持续影响
                  {facts.length > 0 && <> · {facts.length} 条世界事实</>}。点任意一条看细节。
                </p>
              </div>
            }
          />
        </Panel>
      )}

      {pick && (
        <Panel
          title="这一条的细节"
          action={<Button variant="ghost" onClick={() => setSelected(null)}>收起</Button>}
        >
          <PlanItemDetail title={pick.title} tone={toneOfStatus(pick.status)} fields={fieldsOf(pick)} />
          {pick.interrupted && (
            <p className="mt-3 flex items-start gap-1.5 text-xs leading-relaxed text-ink-soft">
              这一条被打断过 —— 她之后重排了整张计划表。
              <InfoTip label="被打断到底发生了什么">
                打断不是"暂停再继续", 是<b>重排她整张计划表</b>: 她拿到新情况, 重新想一遍, 产出一版新的计划。
                改了什么到「计划表」页看。
              </InfoTip>
            </p>
          )}
        </Panel>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">时间轴上的三类事件</h2>
              <InfoTip label="这一页为什么先讲形状">
                <b className="text-ink-soft">形状</b>比颜色更重要 —— 色觉差异的用户看不出琥珀和玫红,
                但看得出"一片底色"和"一根针"。所以图例里画的是形状。
              </InfoTip>
            </div>
          }
        >
          <CategoryLegend classes={['effect', 'sensory', 'schedule']} />
        </Panel>

        <div className="flex items-start gap-2 rounded-xl border border-line bg-raised px-4 py-3">
          <InfoTip tone="warn" label="这一页读不到的两样东西">
            <div className="space-y-3">
              <GapNote title="持续影响没有权威读取面">
                <p>
                  那些带子是从 <span className="font-mono">world-events</span> 里的
                  <span className="font-mono"> ENVIRONMENT_CHANGED </span>
                  <b>推</b>出来的。真正的账本(<span className="font-mono">ContinuousEffectLedger</span>)是进程内的,
                  没有任何 HTTP 面。
                </p>
                <p>
                  缺的端点: <span className="font-mono">GET /api/companions/{'{id}'}/world/effects</span>。
                  它落地之前, "她现在到底觉不觉得冷"在界面上没有确定答案。
                </p>
              </GapNote>
              <GapNote title="计划项没有 id">
                <p>
                  <span className="font-mono">todayActivities</span> 里每一项只有
                  <span className="font-mono"> title</span>。所以两件同名的事在界面上会被当成一件,
                  而"改之前 / 改之后"的对照也只能按标题配。
                </p>
                <p>缺的字段: <span className="font-mono">PlanItem.itemId</span>。</p>
              </GapNote>
            </div>
          </InfoTip>
          <p className="text-xs leading-relaxed text-ink-soft">
            底下那几条琥珀色带子是<b>推</b>出来的, 不是读回来的 —— 别当成权威数据。
          </p>
        </div>
      </div>
    </div>
  )
}

// ── 头部 ────────────────────────────────────────────────────────────────────

function Headline({ summary, current, phase, count, onRefresh }: {
  summary?: string
  current?: string
  phase?: string
  count: number
  onRefresh: () => void
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <p className="text-sm text-ink">
          {summary?.trim() || (count > 0 ? '今天有安排。' : '今天还没有安排。')}
        </p>
        <p className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-0.5 text-xs text-ink-faint">
          {current && (
            <span>此刻: <b className="font-medium text-ink-soft">{typeZh(current)}</b></span>
          )}
          {phase && <span className="font-mono">{phase}</span>}
        </p>
      </div>
      <Button variant="ghost" onClick={onRefresh}><RefreshCw size={13} />刷新</Button>
    </div>
  )
}

function Progress({ value }: { value: number }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100)
  return (
    <span className="inline-flex items-center gap-2">
      <span className="h-1.5 w-16 overflow-hidden rounded-full bg-sunken align-middle">
        <span className="block h-full rounded-full bg-accent" style={{ width: `${pct}%` }} />
      </span>
      <span className="font-mono tnum">{pct}%</span>
    </span>
  )
}

/**
 * 一条日程的细节字段。
 *
 * 名字给中文, 后端字段名只留在提示里 —— `plannedStart` 摆在屏幕上, 不写代码的人
 * 不知道那是什么; 但它对排查的人有用, 所以没删, 只是退到第二行。
 */
export function fieldsOf(a: PlanActivityView) {
  const f = (label: string, value: ReactNode, k: string, hint?: string) => ({
    label,
    value,
    hint: hint ? `${hint}(字段 ${k})` : `后端字段 ${k}。`,
  })
  return [
    f('计划开始', fmtClock(a.plannedStart ?? null), 'plannedStart'),
    f('计划结束', fmtClock(a.plannedEnd ?? null), 'plannedEnd'),
    f('时长', durationOf(a), 'plannedStart / plannedEnd', '从计划开始到结束有多久。'),
    f('状态', a.status ?? '—', 'status', '她此刻与这件事的关系: 正在做 / 做完了 / 还没到点。'),
    f('类型', a.type ? `${typeZh(a.type)} (${a.type})` : '—', 'type'),
    f(
      '要占的注意力',
      a.attentionDemand ?? '—',
      'attentionDemand',
      '这件事要占她多少注意力。它和可打断程度一起决定"现在找她合不合适"。',
    ),
    f(
      '可打断程度',
      a.interruptibility ?? '—',
      'interruptibility',
      '低的时候插进来的事会被她推迟, 而不是立刻处理。',
    ),
    f(
      '能不能看手机',
      a.phoneAvailability ?? '—',
      'phoneAvailability',
      '这段时间她的「活动」允不允许她看手机。注意这和手机自己的免打扰是两件事。',
    ),
    f('对心情的影响', a.moodEffect ?? '—', 'moodEffect', '这件事做完之后会把她的心情推向哪边。'),
    f('进度', typeof a.progress === 'number' ? <Progress value={a.progress} /> : '—', 'progress'),
    f(
      '被打断过吗',
      a.interrupted === undefined ? '—' : a.interrupted ? '被打断过' : '没有被打断',
      'interrupted',
      '它是一个独立的事实, 不是状态的一种 —— 一件事可以"做完了, 但中途被打断过"。',
    ),
    f('重要度', numOr(a.importance), 'importance', '这件事在她自己眼里有多重要。'),
    f('对她意味着多少', numOr(a.emotionalSignificance), 'emotionalSignificance', '这件事牵动她情绪的程度。'),
  ]
}

// ── 纯函数(能测的都放这儿) ─────────────────────────────────────────────────

/** 一项日程的时间区间。解析不出来的返回 null, 由 `layoutDay` 归到"未排期"。 */
export function spanOf(a: PlanActivityView): Span | null {
  const s = a.plannedStart ? Date.parse(a.plannedStart) : Number.NaN
  const e = a.plannedEnd ? Date.parse(a.plannedEnd) : Number.NaN
  return Number.isFinite(s) && Number.isFinite(e) ? { start: s, end: e } : null
}

function spansOf(list: readonly PlanActivityView[]): Span[] {
  return list.map(spanOf).filter((s): s is Span => s !== null)
}

function timeOf(a: PlanActivityView): { start: number | null; end: number | null } {
  const s = a.plannedStart ? Date.parse(a.plannedStart) : Number.NaN
  const e = a.plannedEnd ? Date.parse(a.plannedEnd) : Number.NaN
  return { start: Number.isFinite(s) ? s : null, end: Number.isFinite(e) ? e : null }
}

function durationOf(a: PlanActivityView): string {
  const s = a.plannedStart ? Date.parse(a.plannedStart) : Number.NaN
  const e = a.plannedEnd ? Date.parse(a.plannedEnd) : Number.NaN
  return Number.isFinite(s) && Number.isFinite(e) ? fmtDuration(s, e) : '—'
}

const numOr = (v: number | undefined) => (typeof v === 'number' ? v.toFixed(2) : '—')

/**
 * 把 `world-events` 变成时间轴上的两层覆盖: A 类的带、B 类的针。
 *
 * <h2>为什么 A 类要走一遍 `effectBands`</h2>
 *
 * 因为 A 类**没有结束时间**。一条 `ENVIRONMENT_CHANGED` 只是"从这一刻起温度是 16℃",
 * 它一直有效到下一件改变它的事件为止。把它当成一个时间点画一个圆点, 就完全丢掉了
 * "它一直在生效"这件事 —— 而那正是这类事件区别于另外两类的地方, 也是
 * `ContinuousEffectLedger` 之所以是"集合 + 计算"而不是队列的原因(§5.3.1)。
 *
 * <h2>为什么 B 类的针可以超出日程范围</h2>
 *
 * 因为它们**不占时间**。一次手机响是瞬时的事, 它可能落在两件日程之间的空白里 ——
 * 而那正是最值得看见的一种情况: 她闲着, 却还是没看手机。
 */
export function buildOverlays(
  events: readonly { type: string; at?: string; payload?: Record<string, unknown> | null }[],
  win: Span,
  now: number,
): { bands: TimelineBand[]; markers: TimelineMarker[]; facts: typeof events } {
  const split = splitWorldEvents(events)

  const bands: TimelineBand[] = effectBands(
    split.effect,
    (e) => e.type,
    // 每次影响是一条独立的带子 —— 用 type@at 当 key, 因为同一个类型会反复出现,
    // 而它们必须画成前后相接的几段。合成一段就等于说"温度一直没变过"。
    (e) => `${e.type}@${e.at}`,
    now,
  ).map((b) => ({
    key: b.key,
    start: b.start,
    end: b.end,
    label: `${eventLabelZh(b.type)} 持续生效中`,
  }))

  const markers: TimelineMarker[] = split.sensory
    .filter((e) => {
      const t = Date.parse(e.at ?? '')
      return Number.isFinite(t) && t >= win.start && t <= win.end
    })
    .map((e) => {
      const t = Date.parse(e.at!)
      const detail = summarizePayload(e.payload)
      return {
        at: t,
        cls: 'sensory' as EventClass,
        label: eventLabelZh(e.type),
        detail: `${eventLabelZh(e.type)} · ${fmtClock(t)}${detail ? ` · ${detail}` : ''}`,
      }
    })

  return { bands, markers, facts: split.facts }
}

/**
 * 活动类型的机器名 → 中文。
 *
 * 与 `eventLabelZh` 同一个道理: 认不出就原样返回。加一层"猜"只会让一个没人见过的
 * 活动类型在界面上变成一个编出来的中文词, 而排查的人看不到真正的取值。
 */
const TYPE_ZH: Record<string, string> = {
  sleep: '睡觉',
  wake_up: '起床',
  breakfast: '吃早饭',
  lunch: '吃午饭',
  dinner: '吃晚饭',
  meal: '吃饭',
  work: '工作',
  study: '学习',
  homework: '做作业',
  exercise: '锻炼',
  commute: '通勤',
  rest: '休息',
  leisure: '休闲',
  social: '社交',
  reading: '阅读',
  entertainment: '娱乐',
  chores: '家务',
  free: '空闲',
  idle: '闲着',
}

export function typeZh(type: string): string {
  return TYPE_ZH[type] ?? type
}
