/**
 * 时间轴与计划差异的测试。
 *
 * 这两个函数决定了页面上**位置**, 所以错了不会被任何断言抓住 —— 只会画歪。
 * 因此这里的断言是算术性质(不重叠、占比之和、区间夹紧), 而不是像素快照。
 */
import { describe, expect, it } from 'vitest'
import {
  assignLanes,
  diffPlan,
  diffSummary,
  effectBands,
  fmtClock,
  fmtDuration,
  layoutDay,
  overlaps,
  snapshot,
  toneOfStatus,
  type PlanActivity,
  type PlanSnapshotItem,
} from './plan'

const T = (hhmm: string) => Date.parse(`2026-09-19T${hhmm}:00+08:00`)
const act = (title: string, s: string, e: string, over: Partial<PlanActivity> = {}): PlanActivity =>
  ({ title, plannedStart: `2026-09-19T${s}:00+08:00`, plannedEnd: `2026-09-19T${e}:00+08:00`, ...over })

const win = (a: PlanActivity) => {
  const s = Date.parse(a.plannedStart!)
  const e = Date.parse(a.plannedEnd!)
  return Number.isFinite(s) && Number.isFinite(e) ? { start: s, end: e } : null
}

describe('overlaps', () => {
  it('端点相接不算重叠', () => {
    expect(overlaps({ start: 0, end: 10 }, { start: 10, end: 20 })).toBe(false)
    expect(overlaps({ start: 10, end: 20 }, { start: 0, end: 10 })).toBe(false)
  })

  it('真的交叠才算', () => {
    expect(overlaps({ start: 0, end: 11 }, { start: 10, end: 20 })).toBe(true)
    expect(overlaps({ start: 5, end: 6 }, { start: 0, end: 100 })).toBe(true)
  })

  it('自己和自己重合', () => {
    const s = { start: 0, end: 10 }
    expect(overlaps(s, s)).toBe(true)
  })
})

describe('assignLanes', () => {
  it('互不重叠的全在第 0 行', () => {
    expect(assignLanes([
      { start: 0, end: 10 }, { start: 10, end: 20 }, { start: 30, end: 40 },
    ])).toEqual([0, 0, 0])
  })

  it('两块重叠分两行', () => {
    const lanes = assignLanes([{ start: 0, end: 20 }, { start: 5, end: 25 }])
    expect(new Set(lanes).size).toBe(2)
  })

  it('行数就是"最多同时几件事" —— 多了会留下空行', () => {
    // 三个区间两两重叠 → 3 行
    expect(assignLanes([
      { start: 0, end: 30 }, { start: 5, end: 35 }, { start: 10, end: 40 },
    ])).toEqual([0, 1, 2])
    // 后两块互相不重叠 → 复用第 1 行, 不许开出第 3 行
    expect(assignLanes([
      { start: 0, end: 30 }, { start: 5, end: 10 }, { start: 15, end: 20 },
    ])).toEqual([0, 1, 1])
  })

  it('输出的下标与输入一一对应 —— 乱序输入不许错位', () => {
    const spans = [{ start: 20, end: 30 }, { start: 0, end: 10 }, { start: 5, end: 25 }]
    const lanes = assignLanes(spans)
    expect(lanes).toHaveLength(3)
    // 0 号(20–30) 与 2 号(5–25) 重叠 → 必须不同行
    expect(lanes[0]).not.toBe(lanes[2])
    // 1 号(0–10) 与 2 号重叠 → 不同行
    expect(lanes[1]).not.toBe(lanes[2])
  })

  it('空输入得到空数组, 不抛错', () => {
    expect(assignLanes([])).toEqual([])
  })

  it('同一行里的任意两条真的不重叠 —— 这是这个函数唯一的正确性要求', () => {
    const spans = [
      { start: 0, end: 10 }, { start: 5, end: 15 }, { start: 12, end: 20 },
      { start: 3, end: 8 }, { start: 18, end: 25 }, { start: 6, end: 22 },
    ]
    const lanes = assignLanes(spans)
    const byLane = new Map<number, typeof spans>()
    lanes.forEach((l, i) => byLane.set(l, [...(byLane.get(l) ?? []), spans[i]]))
    for (const [lane, list] of byLane) {
      for (let i = 0; i < list.length; i++) {
        for (let j = i + 1; j < list.length; j++) {
          expect(overlaps(list[i], list[j]), `第 ${lane} 行里两条重叠了`).toBe(false)
        }
      }
    }
  })
})

describe('layoutDay', () => {
  it('百分比位置正确', () => {
    const { rows } = layoutDay(
      [act('作业', '12:00', '13:00')],
      win,
      { start: T('12:00'), end: T('14:00') },
    )
    expect(rows).toHaveLength(1)
    expect(rows[0].left).toBeCloseTo(0, 5)
    expect(rows[0].width).toBeCloseTo(50, 5)
  })

  it('越界的项被夹进窗口 —— 不能出现负宽度或超过 100 的右端', () => {
    const { rows } = layoutDay(
      [act('跨夜', '00:00', '23:59'), act('刚好一半', '13:00', '15:00')],
      win,
      { start: T('12:00'), end: T('14:00') },
    )
    for (const r of rows) {
      expect(r.left).toBeGreaterThanOrEqual(0)
      expect(r.left + r.width).toBeLessThanOrEqual(100.0001)
    }
  })

  it('没有时段的活动进 unscheduled, 且不占车道', () => {
    const { rows, laneCount } = layoutDay(
      [act('作业', '12:00', '13:00'), { title: '没写时间的事' }],
      win,
      { start: T('12:00'), end: T('14:00') },
    )
    const untimed = rows.filter((r) => r.unscheduled)
    expect(untimed).toHaveLength(1)
    expect(untimed[0].item.title).toBe('没写时间的事')
    expect(laneCount).toBe(1)
  })

  it('end <= start 的项按"没有时段"处理, 不画成一条零宽的带子', () => {
    const { rows } = layoutDay(
      [act('坏的', '12:00', '12:00')],
      win,
      { start: T('12:00'), end: T('14:00') },
    )
    expect(rows[0].unscheduled).toBe(true)
  })

  it('不传窗口时按内容自适应 —— 并且两端不会贴边', () => {
    const { rows } = layoutDay([act('a', '09:00', '10:00'), act('b', '17:00', '18:00')], win)
    expect(rows[0].left).toBeGreaterThan(0)
    expect(rows[1].left + rows[1].width).toBeLessThan(100)
  })

  it('完全没有项时给一段以 now 为中心的窗口, 而不是一片空白', () => {
    const { rows, laneCount } = layoutDay<PlanActivity>([], win, undefined, T('12:00'))
    expect(rows).toEqual([])
    expect(laneCount).toBe(0)
  })

  it('laneCount 等于最大车道号 + 1', () => {
    const { rows, laneCount } = layoutDay(
      [act('a', '12:00', '14:00'), act('b', '12:30', '13:00'), act('c', '15:00', '16:00')],
      win,
      { start: T('12:00'), end: T('16:00') },
    )
    expect(laneCount).toBe(Math.max(...rows.map((r) => r.lane)) + 1)
  })
})

describe('effectBands', () => {
  const ev = (at: string, payload: Record<string, unknown>) => ({ type: 'ENVIRONMENT_CHANGED', at, payload })

  it('同类型的下一条把上一条顶掉 —— 这就是 ContinuousEffectLedger 的语义', () => {
    const bands = effectBands(
      [ev('2026-09-19T10:00:00+08:00', {}), ev('2026-09-19T12:00:00+08:00', {})],
      (e) => e.type + ':' + String(e.payload.temperature),
      (e) => e.at!,
      T('14:00'),
    )
    expect(bands).toHaveLength(2)
    expect(bands[0].end).toBe(bands[1].start)
    // 最后一条一直画到 until —— A 类事件没有结束时间, 它到"现在"为止都还生效。
    expect(bands[1].end).toBe(T('14:00'))
  })

  it('不同键的带子各算各的', () => {
    const bands = effectBands(
      [
        { type: 'ENVIRONMENT_CHANGED', at: '2026-09-19T10:00:00+08:00', payload: { k: 'temp' } },
        { type: 'ENVIRONMENT_CHANGED', at: '2026-09-19T11:00:00+08:00', payload: { k: 'smell' } },
      ],
      (e) => String(e.payload.k),
      (e) => e.at!,
      T('14:00'),
    )
    // typeOf 的返回值落在 `type` 上, keyOf 的落在 `key` 上 —— 前者是"哪一条影响的
    // 种类", 决定带子的颜色; 后者是"具体哪一次", 决定图层的 key。
    expect(bands.map((b) => b.type).sort()).toEqual(['smell', 'temp'])
    expect(bands.map((b) => b.key).sort()).toEqual([
      '2026-09-19T10:00:00+08:00', '2026-09-19T11:00:00+08:00',
    ])
  })

  it('没有时间的条目被丢掉', () => {
    expect(effectBands([{ type: 'X', at: undefined, payload: {} }], (e) => e.type, (e) => e.type, 0))
      .toEqual([])
  })

  it('乱序到达不会产出零长或负长的带子', () => {
    // at 是乱序的: 12:00 先到, 10:00 后到。按类型分组后 10:00 的下一条是 12:00,
    // 而 12:00 的下一条是 until —— 两条都活着, 没有负区间。
    const bands = effectBands(
      [ev('2026-09-19T12:00:00+08:00', {}), ev('2026-09-19T10:00:00+08:00', {})],
      (e) => e.at!,
      (e) => e.at!,
      T('14:00'),
    )
    for (const b of bands) expect(b.end).toBeGreaterThan(b.start)
  })

  it('结果按开始时间排 —— 图层顺序稳定', () => {
    const bands = effectBands(
      [
        { type: 'A', at: '2026-09-19T13:00:00+08:00', payload: {} },
        { type: 'B', at: '2026-09-19T10:00:00+08:00', payload: {} },
      ],
      (e) => e.type, (e) => e.type, T('14:00'),
    )
    expect(bands[0].start).toBeLessThan(bands[1].start)
  })
})

const snap = (title: string, s: string | null, e: string | null): PlanSnapshotItem =>
  ({ key: title, title, start: s ? T(s) : null, end: e ? T(e) : null, status: 'pending', progress: 0 })

describe('snapshot 与 diffPlan', () => {
  it('snapshot 用 title 作身份, 时间解析成毫秒', () => {
    const s = snapshot([act('作业', '12:00', '13:00')])
    expect(s[0].key).toBe('作业')
    expect(s[0].start).toBe(T('12:00'))
  })

  it('snapshot 对没有时间的项给 null, 而不是 NaN', () => {
    const s = snapshot([{ title: '没排期' }])
    expect(s[0].start).toBeNull()
    expect(s[0].end).toBeNull()
  })

  it('新增与删除分得清', () => {
    const rows = diffPlan([snap('作业', '12:00', '13:00')], [snap('锻炼', '12:00', '13:00')])
    expect(rows.map((r) => r.kind).sort()).toEqual(['added', 'removed'])
  })

  it('挪时间报 moved, 不报 resized —— 一次挪动不该看起来像两次改动', () => {
    const rows = diffPlan(
      [snap('作业', '12:00', '13:00')],
      [snap('作业', '13:00', '14:00')],
    )
    expect(rows[0].kind).toBe('moved')
  })

  it('只改时长才是 resized', () => {
    const rows = diffPlan(
      [snap('作业', '12:00', '13:00')],
      [snap('作业', '12:00', '14:00')],
    )
    expect(rows[0].kind).toBe('resized')
  })

  it('一模一样就是 kept', () => {
    const rows = diffPlan([snap('作业', '12:00', '13:00')], [snap('作业', '12:00', '13:00')])
    expect(rows[0].kind).toBe('kept')
  })

  it('两版都空不炸', () => {
    expect(diffPlan([], [])).toEqual([])
  })

  it('排序: 改动大的在前, "没动"垫底', () => {
    const rows = diffPlan(
      [snap('没动', '09:00', '10:00'), snap('挪了', '12:00', '13:00')],
      [snap('没动', '09:00', '10:00'), snap('挪了', '13:00', '14:00'), snap('新加', '15:00', '16:00')],
    )
    expect(rows.map((r) => r.kind)).toEqual(['added', 'moved', 'kept'])
  })
})

describe('diffSummary', () => {
  it('没有变化时说清楚"她决定照原样过" —— 静默通过是合法的结果', () => {
    const rows = diffPlan(
      [snap('作业', '12:00', '13:00')],
      [snap('作业', '12:00', '13:00')],
    )
    const s = diffSummary(rows)
    expect(s.changed).toBe(0)
    expect(s.headline).toContain('没有变化')
  })

  it('数得清每一种改动, 并且不把 kept 算进 changed', () => {
    const rows = diffPlan(
      [snap('没动', '09:00', '10:00'), snap('删了', '11:00', '12:00')],
      [snap('没动', '09:00', '10:00'), snap('新加', '15:00', '16:00')],
    )
    const s = diffSummary(rows)
    expect(s.byKind).toEqual({ added: 1, removed: 1, moved: 0, resized: 0, kept: 1 })
    expect(s.changed).toBe(2)
  })

  it('空输入不产出"她重排了计划"这种空话', () => {
    expect(diffSummary([]).headline).toContain('没有拿到')
  })
})

describe('格式化', () => {
  it('fmtClock 补零', () => {
    expect(fmtClock(T('09:05'))).toBe('09:05')
    expect(fmtClock('2026-09-19T23:00:00+08:00')).toBe('23:00')
  })

  it('fmtClock 对空与坏值给占位符, 不抛错也不给 NaN', () => {
    expect(fmtClock(null)).toBe('--:--')
    expect(fmtClock(undefined)).toBe('--:--')
    expect(fmtClock('不是时间')).toBe('--:--')
  })

  it('fmtDuration 说人话', () => {
    expect(fmtDuration(T('12:00'), T('12:45'))).toBe('45 分钟')
    expect(fmtDuration(T('12:00'), T('13:00'))).toBe('1 小时')
    expect(fmtDuration(T('12:00'), T('13:30'))).toBe('1 小时 30 分')
  })

  it('fmtDuration 反过来的区间给 0 而不是负数', () => {
    expect(fmtDuration(T('13:00'), T('12:00'))).toBe('0 分钟')
  })
})

describe('toneOfStatus', () => {
  it('认识的映射到四档', () => {
    expect(toneOfStatus('pending')).toBe('planned')
    expect(toneOfStatus('in_progress')).toBe('active')
    expect(toneOfStatus('done')).toBe('done')
    expect(toneOfStatus('superseded')).toBe('cancelled')
  })

  it('大小写与空白不算差异', () => {
    expect(toneOfStatus(' DONE ')).toBe('done')
    expect(toneOfStatus('Active')).toBe('active')
  })

  it('认不出的落到 planned —— 一个不认识的状态不该被画成"做完了"', () => {
    expect(toneOfStatus('weird')).toBe('planned')
    expect(toneOfStatus(undefined)).toBe('planned')
    expect(toneOfStatus('')).toBe('planned')
  })
})
