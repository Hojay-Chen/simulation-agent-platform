/**
 * 「她」那几页里的纯函数。
 *
 * <h2>为什么这几条值得单测</h2>
 *
 * 它们各自守着一个**只会在界面上表现出来**的判断:
 *
 * - `sortByStart` / `isNow`: 计划表那一页的"此刻"高亮与排序。两端相接不算重叠 ——
 *   这一条错了的表现是"两件事同时被标成正在做"。
 * - `buildOverlays`: A 类的带子怎么从一条条 `ENVIRONMENT_CHANGED` 拼出来。拼错了
 *   不会报错, 只会让"她此刻觉不觉得冷"看起来像有权威数据。
 * - `readDimensions`: 关系维度从返回体里挑数值字段。挑漏了的维度在界面上是
 *   "不存在", 不是"没值" —— 所以它必须认识嵌套, 也必须照收不认识的键。
 */
import { describe, expect, it } from 'vitest'
import type { PlanActivityView } from '@/api/client'
import { buildOverlays } from './Today'
import { isNow, sortByStart } from './Plan'
import { readDimensions } from './Relation'

const act = (over: Partial<PlanActivityView> & { title: string }): PlanActivityView => ({ ...over })

describe('sortByStart', () => {
  it('按开始时间排', () => {
    const out = sortByStart([
      act({ title: 'C', plannedStart: '2026-09-19T14:00:00Z' }),
      act({ title: 'A', plannedStart: '2026-09-19T10:00:00Z' }),
      act({ title: 'B', plannedStart: '2026-09-19T12:00:00Z' }),
    ])
    expect(out.map((a) => a.title)).toEqual(['A', 'B', 'C'])
  })

  it('没有开始时间的排在最后 —— 它们放哪儿都不对, 那就不占最上面', () => {
    const out = sortByStart([
      act({ title: '无时间' }),
      act({ title: '有时间', plannedStart: '2026-09-19T10:00:00Z' }),
      act({ title: '乱码时间', plannedStart: '不是时间' }),
    ])
    expect(out.map((a) => a.title)).toEqual(['有时间', '无时间', '乱码时间'])
  })

  it('不改传入的数组', () => {
    const list = [
      act({ title: 'B', plannedStart: '2026-09-19T12:00:00Z' }),
      act({ title: 'A', plannedStart: '2026-09-19T10:00:00Z' }),
    ]
    sortByStart(list)
    expect(list.map((a) => a.title)).toEqual(['B', 'A'])
  })
})

describe('isNow', () => {
  const a = act({
    title: '作业',
    plannedStart: '2026-09-19T12:00:00Z',
    plannedEnd: '2026-09-19T13:00:00Z',
  })

  it('区间内是"正在做"', () => {
    expect(isNow(a, Date.parse('2026-09-19T12:30:00Z'))).toBe(true)
  })

  it('两端相接不算 —— 与 overlaps() 同一条规则', () => {
    // 相接的那一毫秒若算进去, 两件前后紧挨的事会同时被标成"正在做",
    // 而"她此刻在做哪一件"就只能有一个答案。
    expect(isNow(a, Date.parse('2026-09-19T12:00:00Z'))).toBe(true)
    expect(isNow(a, Date.parse('2026-09-19T13:00:00Z'))).toBe(false)
  })

  it('区间外不是', () => {
    expect(isNow(a, Date.parse('2026-09-19T11:59:59Z'))).toBe(false)
  })

  it('缺时间的一律不是"正在做" —— 而不是当作 0 到无穷', () => {
    expect(isNow(act({ title: 'x' }), Date.now())).toBe(false)
    expect(isNow(act({ title: 'x', plannedStart: '不是时间' }), Date.now())).toBe(false)
  })
})

describe('buildOverlays', () => {
  const win = { start: Date.parse('2026-09-19T00:00:00Z'), end: Date.parse('2026-09-19T12:00:00Z') }
  const now = Date.parse('2026-09-19T10:00:00Z')

  it('A 类拼成一条从事件到"现在"的带子 —— 它没有结束时间', () => {
    const { bands } = buildOverlays(
      [{ type: 'ENVIRONMENT_CHANGED', at: '2026-09-19T08:00:00Z', payload: { temperature: 16 } }],
      win, now,
    )
    expect(bands).toHaveLength(1)
    expect(bands[0].start).toBe(Date.parse('2026-09-19T08:00:00Z'))
    // 最后一条带子一直延伸到 now —— 它表示"从那时候起一直生效到现在"。
    expect(bands[0].end).toBe(now)
  })

  it('同一个类型的两次变化画成前后相接的两段, 而不是合成一段', () => {
    // 合成一段等于说"温度一直没变过" —— 而它变了, 那正是要看见的事。
    const { bands } = buildOverlays(
      [
        { type: 'ENVIRONMENT_CHANGED', at: '2026-09-19T07:00:00Z' },
        { type: 'ENVIRONMENT_CHANGED', at: '2026-09-19T09:00:00Z' },
      ],
      win, now,
    )
    expect(bands).toHaveLength(2)
    expect(bands[0].end).toBe(bands[1].start)
  })

  it('B 类变成针 —— 它们不占时间, 落在哪儿都不会被裁掉', () => {
    const { markers, bands } = buildOverlays(
      [{ type: 'USER_MESSAGE_NOTIFIED', at: '2026-09-19T09:30:00Z' }],
      win, now,
    )
    expect(markers).toHaveLength(1)
    expect(markers[0].cls).toBe('sensory')
    expect(markers[0].at).toBe(Date.parse('2026-09-19T09:30:00Z'))
    expect(bands).toHaveLength(0)
  })

  it('窗口之外的针不画 —— 画了那条轴的范围就是假的', () => {
    const { markers } = buildOverlays(
      [{ type: 'USER_MESSAGE_NOTIFIED', at: '2026-09-18T09:30:00Z' }],
      win, now,
    )
    expect(markers).toEqual([])
  })

  it('没有时间的条目落进 facts, 不会静默消失', () => {
    const { bands, markers, facts } = buildOverlays(
      [{ type: 'WORLD_EVENT_OCCURRED' }],
      win, now,
    )
    expect(facts).toHaveLength(1)
    expect(bands).toEqual([])
    expect(markers).toEqual([])
  })

  it('纯事实既不进带子也不进针', () => {
    const { bands, markers, facts } = buildOverlays(
      [{ type: 'WORLD_EVENT_OCCURRED', at: '2026-09-19T09:00:00Z' }],
      win, now,
    )
    expect(bands).toEqual([])
    expect(markers).toEqual([])
    expect(facts).toHaveLength(1)
  })

  it('针上的 detail 带中文名与时刻', () => {
    const { markers } = buildOverlays(
      [{ type: 'USER_MESSAGE_NOTIFIED', at: '2026-09-19T09:30:00Z', payload: { from: 'agent_x' } }],
      win, now,
    )
    expect(markers[0].label).toBe('手机响了')
    expect(markers[0].detail).toContain('手机响了')
    expect(markers[0].detail).toContain('from=agent_x')
  })

  it('认不出的类型**不会**被画成一根针 —— 未知一律落到"事实"', () => {
    // 这是这一层最重要的一条安全规则: 一条没人见过的事件被画成"要立刻管"是危险的
    // (会训练用户忽略这个界面), 被画成"不用管"只是可能漏看一条。所以未知类型
    // 走 fact, 而不是猜成 sensory。
    const { markers, facts } = buildOverlays(
      [{ type: 'SOMETHING_NEW', at: '2026-09-19T09:45:00Z' }],
      win, now,
    )
    expect(markers).toEqual([])
    expect(facts).toHaveLength(1)
  })
})

describe('readDimensions', () => {
  it('顶层数值字段照收', () => {
    expect(readDimensions({ familiarity: 0.4, trust: 0.8 }).map((d) => d.key))
      .toEqual(['familiarity', 'trust'])
  })

  it('嵌在 relationship 里也认', () => {
    // 两种形状都认, 是因为"关系维度挂在哪一层"这件事在返回体里没有约定 ——
    // 只认一种的话, 另一种的表现是这一整块**静默空白**。
    expect(readDimensions({ relationship: { intimacy: 1 } }).map((d) => d.key)).toEqual(['intimacy'])
  })

  it('嵌套时以 relationship 为准, 不把外层的数值也算进来', () => {
    const out = readDimensions({ version: 3, relationship: { trust: 0.5 } })
    expect(out.map((d) => d.key)).toEqual(['trust'])
  })

  it('认识的给中文名与方向', () => {
    const [f] = readDimensions({ familiarity: 0.4 })
    expect(f.label).toBe('熟悉度')
    expect(f.goodHigh).toBe(true)
    expect(f.unregistered).toBe(false)
  })

  it('紧张是"低才好" —— 方向标反了不会报错, 只会让人不再看第二眼', () => {
    expect(readDimensions({ tension: 0.9 })[0].goodHigh).toBe(false)
    expect(readDimensions({ connectionPressure: 0.9 })[0].goodHigh).toBe(false)
  })

  it('不认识的键照收并标未登记 —— 漏掉的那一项在界面上是"不存在"', () => {
    const out = readDimensions({ newDimension: 0.3 })
    expect(out).toHaveLength(1)
    expect(out[0].unregistered).toBe(true)
    expect(out[0].label).toBe('newDimension')
  })

  it('非数值字段一律不进 —— 它们画不成一根 0–1 的条', () => {
    expect(readDimensions({ stage: 'close', since: null, tags: ['a'], trust: 0.5 }).map((d) => d.key))
      .toEqual(['trust'])
  })

  it('NaN / Infinity 不进', () => {
    expect(readDimensions({ a: Number.NaN, b: Number.POSITIVE_INFINITY })).toEqual([])
  })

  it('越界的值夹到 0–1 —— 一条 1.4 的条会画到框外面去', () => {
    expect(readDimensions({ a: 1.4 })[0].value).toBe(1)
    expect(readDimensions({ b: -0.2 })[0].value).toBe(0)
  })

  it('null 与空对象得到空数组', () => {
    expect(readDimensions(null)).toEqual([])
    expect(readDimensions({})).toEqual([])
  })

  it('按 key 排序 —— 否则每一刷新的顺序会跟着后端返回顺序变', () => {
    expect(readDimensions({ trust: 0.1, familiarity: 0.2, intimacy: 0.3 }).map((d) => d.key))
      .toEqual(['familiarity', 'intimacy', 'trust'])
  })
})
