import type { ReactNode } from 'react'
import {
  TONE_META,
  fmtClock,
  fmtDuration,
  type DayLayout,
  type PlanTone,
  type Span,
} from '@/lib/plan'
import { EVENT_CLASS_META, type EventClass } from '@/lib/events'

/**
 * 她的一天 —— 一条真正的时间轴, 不是一列卡片。
 *
 * <h2>为什么这里必须是坐标, 不能是列表</h2>
 *
 * 计划项是**时间段**(`TimeWindow`), 而一个 `<ul>` 无论如何排版都表达不出一件事:
 * 两件事有没有同时发生、中间空了几个小时、她"现在"在整天的哪个位置。这三件事恰恰
 * 是"她的一天"这一页唯一要回答的问题。所以这里用真实坐标: 横轴是时间, 一条就是
 * 一段时间, 重叠的自动分行。
 *
 * <h2>三类事件在同一个坐标系里怎么区分</h2>
 *
 * 这正是这一页最值得看的那个设计决定 —— 三类事件**画成三种不同的形状**, 而不是
 * 三种颜色的小圆点:
 *
 * - **C 计划表**(时间段) → **条**。有左端有右端, 占据轨道。
 * - **A 持续影响**(一直在生效) → **带**。铺在轨道底下的半透明色块, 从它发生那一刻
 *   一直画到被下一条同类影响顶掉为止。它没有"结束"这个动作, 所以画成没有边框的
 *   一片底色 —— 你没法"处理掉"外面的 16℃。
 * - **B 实时感官**(必须立刻反应) → **针**。钉在轨道下面的窄条上, 一个一个的点。
 *   它们不占时间, 而且**必须现在管**, 所以它们在轨道之外, 单独一条。
 *
 * 形状的差别比颜色的差别更难忽略 —— 色盲的用户看不出琥珀和玫红, 但看得出
 * "一片底色"和"一根针"。这是这一页对可访问性的主要回答。
 *
 * <h2>键盘</h2>
 *
 * 每一条计划都是一个真的 `<button>`, 所以 Tab 能走到、回车能选。`aria-label`
 * 里带完整信息(标题 / 起止 / 状态 / 时长), 读屏用户因此不需要"看"这条时间轴。
 * 绝对定位不影响 Tab 顺序 —— 顺序跟着 DOM 走, 而 DOM 是按开始时间排的。
 */

export interface TimelineMarker {
  /** 毫秒时间戳。 */
  at: number
  cls: EventClass
  label: string
  /** 悬停/读屏用的完整说明。 */
  detail?: string
}

export interface TimelineBand {
  key: string
  start: number
  end: number
  label: string
}

export interface DayTimelineProps<T> {
  layout: DayLayout<T>
  window: Span
  now?: number
  keyOf: (item: T) => string
  labelOf: (item: T) => string
  toneOf: (item: T) => PlanTone
  /**
   * 起止时刻, 毫秒。
   *
   * 单独一个函数而不是让组件自己去 `item.plannedStart` 上取: 组件对 `T` 一无所知,
   * 想直接取就只能做类型断言, 而断言在字段改名时**不会**报错 —— 它会静默地让所有
   * 时间显示变成 `--:--`。让调用方显式给出, 改名时编译器就会拦住。
   */
  timeOf: (item: T) => { start: number | null; end: number | null }
  /** 副标题: 时长、类型、进度。悬停提示与 aria-label 共用。 */
  metaOf?: (item: T) => string | null
  markers?: readonly TimelineMarker[]
  bands?: readonly TimelineBand[]
  selectedKey?: string | null
  onSelect?: (item: T) => void
  /** 轨道下面的说明; 没有排期的项落在这里。 */
  footer?: ReactNode
}

/** 一条轨道的高度(px)。写在常量里, 因为它同时决定容器总高与每条的 top。 */
const LANE_H = 38
const BAR_H = 30
const MARKER_H = 26

export function DayTimeline<T>({
  layout,
  window: win,
  now,
  keyOf,
  labelOf,
  toneOf,
  timeOf,
  metaOf,
  markers = [],
  bands = [],
  selectedKey,
  onSelect,
  footer,
}: DayTimelineProps<T>) {
  const span = Math.max(win.end - win.start, 1)
  const pct = (t: number) => ((t - win.start) / span) * 100

  const lanes = Math.max(layout.laneCount, 1)
  const tracksH = lanes * LANE_H
  const inWindow = markers.filter((m) => m.at >= win.start && m.at <= win.end)

  return (
    <div className="space-y-3">
      <Axis win={win} now={now} />

      <div
        className="relative rounded-lg border border-line bg-sunken/40"
        style={{ height: tracksH + MARKER_H + 8 }}
      >
        {/* ① 小时的格线 —— 最底层, 只做背景, 不抢内容 */}
        <Grid win={win} height={tracksH + MARKER_H} pct={pct} />

        {/* ② A 类持续影响: 铺在轨道底下的带子 */}
        {bands.map((b) => {
          const left = Math.max(0, pct(Math.max(b.start, win.start)))
          const right = Math.min(100, pct(Math.min(b.end, win.end)))
          if (right <= left) return null
          return (
            <div
              key={b.key}
              title={`${b.label} · ${fmtClock(b.start)} 起持续生效`}
              className="pointer-events-none absolute rounded border border-cat-effect/30 bg-cat-effect/10"
              style={{ left: `${left}%`, width: `${right - left}%`, top: 4, height: tracksH }}
            >
              <span className="sr-only">{b.label}</span>
            </div>
          )
        })}

        {/* ③ C 类计划表: 条 */}
        {layout.rows.filter((r) => !r.unscheduled).map((r) => {
          const key = keyOf(r.item)
          const tone = toneOf(r.item)
          const meta = metaOf?.(r.item) ?? null
          const { start: s, end: e } = timeOf(r.item)
          const hasTime = s !== null && e !== null
          const timeText = hasTime ? `${fmtClock(s)} 到 ${fmtClock(e)}` : ''
          const durText = hasTime ? fmtDuration(s, e) : ''
          const selected = selectedKey === key
          const label = [
            labelOf(r.item),
            timeText,
            durText,
            TONE_META[tone].label,
            meta ?? '',
          ].filter(Boolean).join(', ')

          return (
            <button
              key={key}
              type="button"
              onClick={() => onSelect?.(r.item)}
              aria-label={label}
              aria-pressed={selected}
              title={label}
              className={`absolute overflow-hidden rounded-md border px-2 text-left text-xs leading-none transition
                          ${TONE_META[tone].bar}
                          ${selected ? 'ring-2 ring-accent ring-offset-1 ring-offset-sunken' : ''}
                          ${onSelect ? 'cursor-pointer hover:brightness-105' : 'cursor-default'}
                          focus:outline-none focus-visible:ring-2 focus-visible:ring-accent`}
              style={{
                left: `${r.left}%`,
                width: `${r.width}%`,
                top: 4 + r.lane * LANE_H + (LANE_H - BAR_H) / 2,
                height: BAR_H,
              }}
            >
              <span className="flex h-full flex-col justify-center gap-0.5">
                <span className={`truncate font-medium ${TONE_META[tone].text}`}>{labelOf(r.item)}</span>
                {hasTime && (
                  <span className="truncate text-[10px] text-ink-faint tnum">
                    {fmtClock(s)}–{fmtClock(e)}
                  </span>
                )}
              </span>
            </button>
          )
        })}

        {/* ④ B 类实时感官: 针。钉在轨道**下面**单独一条 —— 它们不占时间, 而且
            和上面的条不是同一个问题(上面是"她打算做什么", 这里是"什么打断了她")。 */}
        <div className="absolute left-0 right-0" style={{ top: tracksH + 2, height: MARKER_H }}>
          {inWindow.map((m, i) => {
            const meta = EVENT_CLASS_META[m.cls]
            return (
              <span
                key={`${m.cls}-${m.at}-${i}`}
                title={m.detail ?? `${m.label} · ${fmtClock(m.at)}`}
                className="absolute flex -translate-x-1/2 flex-col items-center"
                style={{ left: `${pct(m.at)}%`, top: 0 }}
              >
                <span className={`h-2.5 w-0.5 rounded-full ${meta.dot}`} />
                <span className={`mt-0.5 whitespace-nowrap text-[10px] tnum ${meta.text}`}>
                  {fmtClock(m.at)}
                </span>
              </span>
            )
          })}
        </div>

        {/* ⑤ 「现在」—— 最后一层, 压在一切之上。它是一条会随着时间右移的线,
            所以它必须和内容在视觉上分得开。 */}
        {now !== undefined && now >= win.start && now <= win.end && (
          <div
            className="pointer-events-none absolute bottom-0 top-0 w-px bg-accent"
            style={{ left: `${pct(now)}%` }}
            aria-hidden="true"
          >
            <span className="absolute -top-0.5 left-1/2 h-1.5 w-1.5 -translate-x-1/2 rounded-full bg-accent" />
          </div>
        )}
      </div>

      {footer}
    </div>
  )
}

/** 横轴刻度。标签按可用宽度抽稀 —— 一小时一个标签在窄屏上会糊成一条黑边。 */
function Axis({ win, now }: { win: Span; now?: number }) {
  const span = Math.max(win.end - win.start, 1)
  const startHour = new Date(win.start)
  startHour.setMinutes(0, 0, 0)
  if (startHour.getTime() < win.start) startHour.setHours(startHour.getHours() + 1)

  const hours = Math.ceil(span / 3_600_000)
  // 每小时一个标签最多 24 个。超过 12 格就每 2 小时标一个, 超过 24 格每 4 小时。
  const step = hours <= 12 ? 1 : hours <= 24 ? 2 : 4

  const ticks: number[] = []
  for (let t = startHour.getTime(), i = 0; t < win.end; t += 3_600_000, i++) {
    if (i % step === 0) ticks.push(t)
  }

  return (
    <div className="flex items-baseline justify-between gap-3">
      <div className="relative h-4 flex-1">
        {ticks.map((t) => (
          <span
            key={t}
            className="absolute -translate-x-1/2 text-[10px] tnum text-ink-faint"
            style={{ left: `${((t - win.start) / span) * 100}%` }}
          >
            {fmtClock(t)}
          </span>
        ))}
      </div>
      {now !== undefined && (
        <span className="shrink-0 text-[10px] tnum text-accent">
          现在 {fmtClock(now)}
        </span>
      )}
    </div>
  )
}

function Grid({ win, height, pct }: { win: Span; height: number; pct: (t: number) => number }) {
  const lines: number[] = []
  const cursor = new Date(win.start)
  cursor.setMinutes(0, 0, 0)
  if (cursor.getTime() < win.start) cursor.setHours(cursor.getHours() + 1)
  for (let t = cursor.getTime(); t < win.end; t += 3_600_000) lines.push(t)

  return (
    <div className="pointer-events-none absolute inset-x-0 top-0" style={{ height }} aria-hidden="true">
      {lines.map((t) => (
        <span
          key={t}
          className="absolute bottom-0 top-0 w-px bg-line"
          style={{ left: `${pct(t)}%` }}
        />
      ))}
    </div>
  )
}

/**
 * 没排上时间的项。
 *
 * 它们**不画进轨道** —— 一个没有起止时间的东西放上时间轴, 位置就是编的。
 * 后端给不出时间要么是它还没被排期, 要么是数据有问题; 两种都要被看见, 但都不该
 * 被伪装成"她 3 点做这个"。
 */
export function UnscheduledRows<T>({
  items,
  keyOf,
  labelOf,
  render,
}: {
  items: readonly T[]
  keyOf: (item: T) => string
  labelOf: (item: T) => string
  render?: (item: T) => ReactNode
}) {
  if (items.length === 0) return null
  return (
    <div className="rounded-lg border border-line bg-sunken/40 p-3">
      <p className="mb-2 text-xs text-ink-faint">
        下面这些没有开始/结束时间, 排不到轨道上 —— 它们没有"第几行第几列"可言。
      </p>
      <ul className="space-y-1">
        {items.map((it) => (
          <li key={keyOf(it)} className="flex items-center justify-between gap-3 text-xs">
            <span className="truncate text-ink-soft">{labelOf(it)}</span>
            {render?.(it)}
          </li>
        ))}
      </ul>
    </div>
  )
}
