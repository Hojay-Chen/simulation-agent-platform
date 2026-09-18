import type { ReactNode } from 'react'
import { EVENT_CLASS_META, eventLabelZh, isKnownEventType, weightOf, type EventClass } from '@/lib/events'
import { DIFF_META, fmtClock, fmtDuration, TONE_META, type DiffRow, type PlanTone } from '@/lib/plan'

/**
 * 图例、缺口说明、事件行、计划差异表 —— 跨页共用的小件。
 *
 * 放在一个文件里而不是四个, 是因为它们共同承担同一件事: **把后端数据结构与界面
 * 之间那层翻译显式化**。图例说的是"颜色什么意思", 缺口说明说的是"哪一块读不到",
 * 事件行说的是"这条从哪来", 差异表说的是"这一版和上一版差在哪"。四件事都是
 * "别让用户猜", 拆开反而看不出它们是一件事。
 */

/**
 * 事件三类的图例。
 *
 * <h2>为什么图例里要写形状</h2>
 *
 * 因为时间轴上三类事件是**三种形状**(带 / 针 / 条, 见 Timeline.tsx)。图例只给颜色
 * 的话, 用户得自己去发现"原来底下那片半透明是 A 类"; 把形状写进图例, 这件事一眼就
 * 成立。而形状是色盲用户唯一能依赖的东西, 所以它必须出现在图例里, 而不是只在实现里。
 */
export function CategoryLegend({ classes }: { classes?: readonly EventClass[] }) {
  const list = classes ?? (['effect', 'sensory', 'schedule', 'fact'] as const)
  return (
    <ul className="space-y-1.5">
      {list.map((c) => {
        const m = EVENT_CLASS_META[c]
        return (
          <li key={c} className="flex gap-2.5 text-xs">
            <Shape c={c} />
            <span className="min-w-0">
              <span className={`font-medium ${m.text}`}>{m.label}</span>
              <span className="ml-2 leading-relaxed text-ink-faint">{m.hint}</span>
            </span>
          </li>
        )
      })}
    </ul>
  )
}

/** 三类各自的形状。与 Timeline 上的画法保持一致 —— 不一致的图例比没有图例更糟。 */
function Shape({ c }: { c: EventClass }) {
  const m = EVENT_CLASS_META[c]
  if (c === 'effect') {
    // A: 一根横带
    return <span className={`mt-1 h-2.5 w-6 shrink-0 rounded-sm border ${m.border} ${m.bg}`} />
  }
  if (c === 'sensory') {
    // B: 一根针
    return <span className={`mt-0.5 h-3.5 w-0.5 shrink-0 rounded-full ${m.dot}`} />
  }
  if (c === 'schedule') {
    // C: 一条轨
    return <span className={`mt-1 h-2.5 w-6 shrink-0 rounded-[3px] border ${m.border} ${m.bg}`} />
  }
  // fact: 一个小点
  return <span className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${m.dot}`} />
}

/**
 * 缺口说明 —— 后端还没有这个面。
 *
 * <h2>为什么它必须是一个可见的组件, 而不是一句注释</h2>
 *
 * 因为"这一页少了一块"和"这一页本来就是这样的"在用户眼里长得一模一样。一块空白的
 * 区域会被读成"她没有数据", 而实际上数据在服务端存在、只是没有出口 —— 这两种情况
 * 需要完全不同的行动(一个去查数据, 一个去催接口)。所以缺口的写法必须包含三件事:
 * **缺什么、本该由哪个端点提供、现在能看到的是哪一部分**。
 */
export function GapNote({
  title = '这块还读不到',
  children,
}: {
  title?: string
  children: ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed border-line-strong bg-sunken/50 px-4 py-3">
      <p className="text-xs font-medium text-ink-soft">{title}</p>
      <div className="mt-1 space-y-1 text-[11px] leading-relaxed text-ink-faint">{children}</div>
    </div>
  )
}

/** 事件流里的一行。 */
export interface EventRowData {
  key: string
  type: string
  at: string
  classes: readonly EventClass[]
  /** 已经展开成可读文本的 payload 摘要。 */
  summary?: string | null
  /** 这一条来自哪个 agent —— 跨 agent 的运维页要显示。 */
  source?: string
}

export function EventRow({ row }: { row: EventRowData }) {
  const known = isKnownEventType(row.type)
  const primary = row.classes[0] ?? 'fact'
  const m = EVENT_CLASS_META[primary]

  return (
    <li className="flex gap-3 border-b border-line px-1 py-2 last:border-0">
      <span className="mt-1 flex w-14 shrink-0 flex-col items-end gap-1">
        <span className="font-mono text-[11px] text-ink tnum">{fmtClock(row.at)}</span>
        <span className={`h-0.5 w-6 rounded-full ${m.dot}`} aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <span className={`text-xs font-medium ${m.text}`}>{eventLabelZh(row.type)}</span>
          {row.classes.length > 1 && (
            <span className="text-[10px] text-ink-faint">
              同时是 {row.classes.map((c) => EVENT_CLASS_META[c].label).join(' + ')}
            </span>
          )}
          {/* 没登记过的类型原样把机器名摆出来, 并标一下 —— 这条信息运维需要,
              而"编一个中文名"会把一个第三方扩展事件伪装成内建事件。 */}
          {!known && (
            <span className="rounded border border-warn/40 px-1 text-[10px] text-warn">
              未登记 · {row.type}
            </span>
          )}
          {row.source && (
            <span className="font-mono text-[10px] text-ink-faint">{row.source}</span>
          )}
        </span>
        {row.summary && (
          <span className="mt-0.5 block break-all font-mono text-[11px] leading-relaxed text-ink-faint">
            {row.summary}
          </span>
        )}
      </span>
    </li>
  )
}

/** 按类别分组计数的小结 —— 事件流顶部那一条。 */
export function ClassTally({ counts }: { counts: Record<EventClass, number> }) {
  const order: EventClass[] = ['sensory', 'schedule', 'effect', 'fact']
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
      {order.map((c) => (
        <span key={c} className="flex items-baseline gap-1.5">
          <span className={`h-1.5 w-1.5 rounded-full ${EVENT_CLASS_META[c].dot}`} aria-hidden="true" />
          <span className="text-ink-faint">{EVENT_CLASS_META[c].label}</span>
          <span className={`font-mono tnum ${counts[c] > 0 ? 'text-ink' : 'text-ink-faint'}`}>
            {counts[c]}
          </span>
        </span>
      ))}
    </div>
  )
}

/** 数一组事件各自的类别。`over` 允许调用方覆盖某一类的判定(服务端给了 category 时)。 */
export function tally(
  rows: readonly { classes: readonly EventClass[] }[],
): Record<EventClass, number> {
  const out: Record<EventClass, number> = { fact: 0, effect: 0, sensory: 0, schedule: 0 }
  for (const r of rows) for (const c of r.classes) out[c]++
  return out
}

/**
 * 计划的两版对照。
 *
 * <h2>为什么"没动"也要列出来</h2>
 *
 * 因为一份只列改动的差异表会让人以为**整张计划表都被重排了**。把"没动"的那几条
 * 也列上, 用户一眼能看出改动是局部的 —— 这个差别决定了"她刚才是不是整个人乱了"。
 * 所以排序上改动大的在前, 但"没动"不折叠。
 *
 * <h2>"删除"与"跳过"必须分开说</h2>
 *
 * 这是 §3.5.6 里最容易被实现糊掉的一处: 一件被删掉的作业和一件被推迟的作业, 在
 * "今天做了没有"这个问题上答案一样, 但在"她明天还做不做"上答案完全相反。表头那句
 * 注释就是为此存在的。
 */
export function PlanDiffTable({ rows }: { rows: readonly DiffRow[] }) {
  if (rows.length === 0) {
    return <p className="py-6 text-center text-sm text-ink-faint">没有可对照的两版计划。</p>
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <caption className="sr-only">重新规划前后的逐项对照</caption>
        <thead>
          <tr className="border-b border-line text-left text-ink-faint">
            <th scope="col" className="py-2 pr-3 font-normal">改动</th>
            <th scope="col" className="py-2 pr-3 font-normal">事项</th>
            <th scope="col" className="py-2 pr-3 font-normal">之前</th>
            <th scope="col" className="py-2 font-normal">之后</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} className="border-b border-line last:border-0 align-top">
              <td className="py-2 pr-3">
                <span className={chipOf(r.kind)} title={DIFF_META[r.kind].hint}>
                  {DIFF_META[r.kind].label}
                </span>
              </td>
              <td className="py-2 pr-3 text-ink">{r.title}</td>
              <td className="py-2 pr-3 font-mono text-ink-faint tnum">{sideText(r.before)}</td>
              <td className="py-2 font-mono text-ink-soft tnum">{sideText(r.after)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const chipOf = (kind: DiffRow['kind']) => ({
  added: 'chip border border-ok/40 text-ok',
  removed: 'chip border border-danger/40 text-danger',
  moved: 'chip border border-cat-schedule/40 text-cat-schedule',
  resized: 'chip border border-cat-effect/40 text-cat-effect',
  kept: 'chip border border-line text-ink-faint',
}[kind])

function sideText(side: DiffRow['before']): string {
  if (!side) return '—'
  if (side.start === null || side.end === null) return '未排期'
  return `${fmtClock(side.start)}–${fmtClock(side.end)} (${fmtDuration(side.start, side.end)})`
}

/**
 * 一条计划项的细节卡 —— 点开时间轴上某一条之后显示。
 *
 * 字段名全部照抄后端, 不做美化: 用户在别处(日志、接口文档、数据库)看到的是同一批
 * 名字, 界面上换个说法会让两边对不上号。
 */
export function PlanItemDetail({
  title,
  tone,
  fields,
}: {
  title: string
  tone: PlanTone
  fields: { label: string; value: ReactNode; hint?: string }[]
}) {
  return (
    <div className="space-y-2">
      <div className="flex items-baseline gap-2">
        <h3 className="text-sm font-medium text-ink">{title}</h3>
        <span className={`chip border ${TONE_META[tone].bar} ${TONE_META[tone].text}`}>
          {TONE_META[tone].label}
        </span>
      </div>
      <dl className="grid gap-x-4 gap-y-1.5 sm:grid-cols-2">
        {fields.map((f) => (
          <div key={f.label} className="flex items-baseline gap-2 text-xs" title={f.hint}>
            <dt className="shrink-0 font-mono text-ink-faint">{f.label}</dt>
            <dd className="min-w-0 text-ink-soft">{f.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/** 事件按紧急度排序用的权重 —— 导出一次, 免得页面各自 import 两个东西。 */
export const eventWeight = (classes: readonly EventClass[]) => weightOf(classes)
