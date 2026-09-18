/**
 * 运维两页里那几个纯函数。
 *
 * <h2>为什么测的是这几个, 而不是页面渲染</h2>
 *
 * 本仓前端没有 jsdom(vitest 的 environment 是 node), 写在 JSX 里的结构断言一条也跑不
 * 起来。而这两页真正的风险点**恰好都不在 JSX 里**:
 *
 * - 「事件流」的分组与筛选: 一条事件可以同时属于两类, 交集 / 并集的差别不会报错,
 *   只会让"我只想看感官事件"顺手丢掉一批 —— 而用户不会知道。
 * - 「运行时」的影子结论: 没启用时返回的全 0 看起来就是"完全一致", 把它读成
 *   "可以切流"是这块面板唯一一种会造成真实损失的读法。
 *
 * 这两件事都是纯函数, 所以它们能被钉住。
 */
import { describe, expect, it } from 'vitest'
import type { WorldEventRow } from '@/api/client'
import { filterByClass, toRows, toggle } from './Events'
import { shadowVerdict } from './Runtime'
import type { EventClass } from '@/lib/events'

const row = (over: Partial<WorldEventRow> & { type: string }): WorldEventRow => ({ ...over })

describe('toggle', () => {
  it('点一下加进去, 再点一下拿出来 —— 返回的是新集合', () => {
    const a = new Set<EventClass>()
    const b = toggle(a, 'sensory')
    expect([...b]).toEqual(['sensory'])
    // 就地改 Set 不会触发重渲染 —— 那是最难查的一类"点了没反应"。
    expect([...a]).toEqual([])
    expect([...toggle(b, 'sensory')]).toEqual([])
  })
})

describe('toRows', () => {
  it('新的在上', () => {
    const rows = toRows([
      row({ type: 'A', at: '2026-09-18T10:00:00Z' }),
      row({ type: 'B', at: '2026-09-18T12:00:00Z' }),
    ])
    expect(rows.map((r) => r.type)).toEqual(['B', 'A'])
  })

  it('没有时间的排在最后 —— 不是最前', () => {
    // 放在最前会让"最近发生了什么"那一屏被一批没有时刻的记录占满, 而那些记录
    // 恰恰是信息量最低的。
    const rows = toRows([
      row({ type: 'NO_TIME' }),
      row({ type: 'EARLY', at: '2026-09-18T08:00:00Z' }),
      row({ type: 'LATE', at: '2026-09-18T20:00:00Z' }),
    ])
    expect(rows.map((r) => r.type)).toEqual(['LATE', 'EARLY', 'NO_TIME'])
  })

  it('时刻认不出来的一律沉底, 而不是抛错', () => {
    const rows = toRows([
      row({ type: 'JUNK', at: '不是时间' }),
      row({ type: 'OK', at: '2026-09-18T08:00:00Z' }),
    ])
    expect(rows.map((r) => r.type)).toEqual(['OK', 'JUNK'])
  })

  it('同一毫秒里的两条不会撞 key —— 撞了 React 会少画一条', () => {
    const rows = toRows([
      row({ type: 'X', at: '2026-09-18T10:00:00Z' }),
      row({ type: 'X', at: '2026-09-18T10:00:00Z' }),
    ])
    expect(new Set(rows.map((r) => r.key)).size).toBe(2)
  })

  it('类别来自类型表; payload 里的 category 一旦出现就优先采信', () => {
    const [byType] = toRows([row({ type: 'USER_MESSAGE_NOTIFIED' })])
    expect(byType.classes).toEqual(['sensory'])

    // 服务端把同一个类型重新分类 —— 这时**不能**再查前端那张表。
    const [byServer] = toRows([
      row({ type: 'USER_MESSAGE_NOTIFIED', payload: { category: 'STATE_EFFECT,SENSORY' } }),
    ])
    expect(byServer.classes).toEqual(['effect', 'sensory'])
  })

  it('payload 摘要不显示 category —— 那是事件流自己用来分类的字段', () => {
    const [r] = toRows([
      row({ type: 'X', payload: { category: 'SENSORY', city: '上海' } }),
    ])
    expect(r.summary).toContain('city=上海')
    expect(r.summary).not.toContain('category')
  })

  it('跨 agent 的运维页要能把来源带出来', () => {
    const [r] = toRows([row({ type: 'X' })], 'agent_abc')
    expect(r.source).toBe('agent_abc')
  })
})

describe('filterByClass —— 取交集, 不是并集', () => {
  const rows = toRows([
    row({ type: 'USER_MESSAGE_NOTIFIED' }),                          // 只属于 sensory
    row({ type: 'ENVIRONMENT_CHANGED', payload: { category: 'STATE_EFFECT,SENSORY' } }),
    row({ type: 'WORLD_EVENT_OCCURRED' }),                           // 只属于 fact
  ])

  it('没筛任何一类时原样返回', () => {
    expect(filterByClass(rows, new Set()).length).toBe(3)
  })

  it('筛掉感官之后, "同时也是感官"的那条**还在** —— 因为它还有别的类别', () => {
    // 用并集(筛掉任一类就隐藏)会让"我只想看计划类"顺手丢掉这条 ——
    // 它确实还在持续影响她, 只是顺带也被听见过一次。
    const out = filterByClass(rows, new Set<EventClass>(['sensory']))
    expect(out.map((r) => r.type)).toEqual(['ENVIRONMENT_CHANGED', 'WORLD_EVENT_OCCURRED'])
  })

  it('全部筛掉时一条不剩, 而不是回退成"全显示"', () => {
    const out = filterByClass(rows, new Set<EventClass>(['sensory', 'effect', 'schedule', 'fact']))
    expect(out).toEqual([])
  })

  it('不改传入的数组', () => {
    const before = rows.map((r) => r.key)
    filterByClass(rows, new Set<EventClass>(['sensory']))
    expect(rows.map((r) => r.key)).toEqual(before)
  })
})

describe('shadowVerdict —— "没启用"不是"没问题"', () => {
  it('enabled=false 判成未知, 并把服务端那句话摆在最前面', () => {
    const v = shadowVerdict({ enabled: false, note: '影子对比未开启' })
    expect(v.tone).toBe('unknown')
    expect(v.text).toContain('影子对比未开启')
  })

  it('没给 note 也要说清楚全 0 表示"没测"而不是"没有分歧"', () => {
    const v = shadowVerdict({ enabled: false })
    expect(v.tone).toBe('unknown')
    expect(v.text).toContain('没有分歧')
  })

  it('shadow=false 与 enabled=false 同一种读法', () => {
    expect(shadowVerdict({ shadow: false }).tone).toBe('unknown')
  })

  it('在跑、且没有 note 时才算 ok', () => {
    const v = shadowVerdict({ enabled: true, overall: { diverged: 3 } })
    expect(v.tone).toBe('ok')
  })

  it('在跑、但服务端给了 note —— 仍然按未知处理, 原话优先', () => {
    const v = shadowVerdict({ enabled: true, note: '只有 2 条样本, 不足以判断' })
    expect(v.tone).toBe('unknown')
    expect(v.text).toContain('不足以判断')
  })

  it('字段全缺(旧后端)一律按未知 —— 判据是肯定式的', () => {
    // 这里的关键不是"缺字段时显示什么", 而是**判据的方向**: 只有服务端明确说在跑
    // 才说 ok。反过来写(缺字段当成 ok)会把一次"没测"变成一次上线决定。
    expect(shadowVerdict({}).tone).toBe('unknown')
    expect(shadowVerdict({ enabled: undefined }).tone).toBe('unknown')
    expect(shadowVerdict({ enabled: undefined }).text).toContain('没测')
  })
})
