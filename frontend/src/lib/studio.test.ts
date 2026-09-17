/**
 * Studio 目录层的测试 —— 标签页表与首页那几个数。
 *
 * 表错了的症状是**静默**的: 一个标签页读错端点, 页面照常渲染, 只是内容不对;
 * 一个 key 撞了, React 会警告但页面仍然出来。所以这里断言的是结构性质
 * (唯一性/非空/可兜底), 而不是某一行文案。
 */
import { describe, expect, it } from 'vitest'
import type { Companion } from '@/api/client'
import {
  AGENT_TABS,
  COMPONENT_TABS,
  UNKNOWN_STAGE,
  distinctTypes,
  sectionsOf,
  stageOf,
  summarizeCompanions,
  tabOf,
} from './studio'

describe('tabOf', () => {
  it('认识的标签原样返回', () => {
    for (const t of AGENT_TABS) expect(tabOf(t.id)).toBe(t.id)
  })

  it('不认识的、缺的、空的全部回总览 —— 手改 URL 不该得到一整页错误', () => {
    expect(tabOf('nope')).toBe('overview')
    expect(tabOf(null)).toBe('overview')
    expect(tabOf(undefined)).toBe('overview')
    expect(tabOf('')).toBe('overview')
    expect(tabOf('Overview')).toBe('overview')
  })
})

describe('标签页与面板表', () => {
  it('标签 id 不重复', () => {
    const ids = AGENT_TABS.map((t) => t.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('空标签页必须是**登记过的**那几个 —— 否则会出现点进去什么都没有的一页', () => {
    for (const t of AGENT_TABS) {
      if (COMPONENT_TABS.includes(t.id)) {
        expect(sectionsOf(t.id), `${t.id} 既在 COMPONENT_TABS 里又有面板`).toEqual([])
        continue
      }
      expect(sectionsOf(t.id).length, `${t.id} 是空的却没登记`).toBeGreaterThan(0)
    }
  })

  it('COMPONENT_TABS 里的每一个都真的是标签页', () => {
    for (const id of COMPONENT_TABS) {
      expect(AGENT_TABS.some((t) => t.id === id), `${id} 不是标签页`).toBe(true)
    }
  })

  it('每个标签页要么在表里有面板, 要么在 COMPONENT_TABS 里 —— 两者必居其一', () => {
    for (const t of AGENT_TABS) {
      const inTable = sectionsOf(t.id).length > 0
      const byComponent = COMPONENT_TABS.includes(t.id)
      expect(inTable || byComponent, `${t.id} 没人认领`).toBe(true)
    }
  })

  it('同一个标签页里面板 key 不重复', () => {
    for (const t of AGENT_TABS) {
      const keys = sectionsOf(t.id).map((s) => s.key)
      expect(new Set(keys).size, `${t.id} 有重复 key`).toBe(keys.length)
    }
  })

  it('每块都有标题与取数函数', () => {
    for (const t of AGENT_TABS) {
      for (const s of sectionsOf(t.id)) {
        expect(s.title.trim(), `${t.id}/${s.key} 缺标题`).not.toBe('')
        expect(typeof s.load, `${t.id}/${s.key} 缺 load`).toBe('function')
      }
    }
  })

  it('sectionsOf 每次返回的是同一份引用 —— 页面按引用做依赖比较', () => {
    // 若这里每次新建数组, useAsync 的 deps 会永远不等, 页面变成无限刷新。
    // 这是"数据表 + 依赖数组"组合最容易踩的一脚。
    expect(sectionsOf('relationship')).toBe(sectionsOf('relationship'))
  })
})

describe('distinctTypes', () => {
  it('按出现次数降序, 同数按名字', () => {
    expect(distinctTypes([
      { type: 'episodic' }, { type: 'semantic' }, { type: 'episodic' },
      { type: 'affective' }, { type: 'affective' },
    ])).toEqual(['affective', 'episodic', 'semantic'])
  })

  it('空、缺失、纯空白的类型不进候选 —— 它们筛出来是"看不见的一类"', () => {
    expect(distinctTypes([{ type: '' }, { type: '   ' }, {}, { type: 'episodic' }]))
      .toEqual(['episodic'])
  })

  it('空输入得到空数组', () => {
    expect(distinctTypes([])).toEqual([])
  })
})

describe('stageOf', () => {
  it('有阶段就用它', () => {
    expect(stageOf({ id: 'a', name: 'a', relationshipStage: 'close' } as Companion)).toBe('close')
  })

  it('空的、纯空白的归「未标注」', () => {
    expect(stageOf({ id: 'a', name: 'a' } as Companion)).toBe(UNKNOWN_STAGE)
    expect(stageOf({ id: 'a', name: 'a', relationshipStage: '   ' } as Companion)).toBe(UNKNOWN_STAGE)
  })
})

const NOW = Date.parse('2026-09-18T12:00:00Z')

function c(over: Partial<Companion>): Companion {
  return { id: 'x', name: 'x', ...over } as Companion
}

describe('summarizeCompanions', () => {
  it('空列表得到全零, 而不是 NaN', () => {
    const s = summarizeCompanions([], NOW)
    expect(s).toMatchObject({ total: 0, withHandle: 0, missingPersona: 0, createdLast7d: 0 })
    expect(s.stages).toEqual([])
    expect(s.newest).toEqual([])
  })

  it('账号ID 与人格缺失各算各的', () => {
    const s = summarizeCompanions([
      c({ handle: 'agent_abc', persona: { name: 'a' } }),
      c({ handle: '   ', persona: { name: 'b' } }),
      c({}),
    ], NOW)
    expect(s.total).toBe(3)
    expect(s.withHandle).toBe(1)
    expect(s.missingPersona).toBe(1)
  })

  it('近 7 天按传入的 now 算 —— 不依赖运行时刻', () => {
    const s = summarizeCompanions([
      c({ createdAt: '2026-09-17T00:00:00Z' }), // 1 天前
      c({ createdAt: '2026-09-11T00:00:00Z' }), // 7 天零 12 小时前 —— 刚好出窗口
      c({ createdAt: '2026-09-12T00:00:00Z' }), // 6.5 天前
      c({ createdAt: '2027-01-01T00:00:00Z' }), // 未来 —— 不算
      c({ createdAt: '不是时间' }),
      c({}),
    ], NOW)
    expect(s.createdLast7d).toBe(2)
  })

  it('阶段桶按数量降序, 同数量按名字 —— 否则每刷新一次两块就换位置', () => {
    const s = summarizeCompanions([
      c({ relationshipStage: 'close' }),
      c({ relationshipStage: 'close' }),
      c({ relationshipStage: 'friend' }),
      c({ relationshipStage: 'stranger' }),
    ], NOW)
    expect(s.stages).toEqual([
      { stage: 'close', count: 2 },
      // 同数量(1)的两块按名字排: friend < stranger
      { stage: 'friend', count: 1 },
      { stage: 'stranger', count: 1 },
    ])
  })

  it('没有阶段的并进「未标注」', () => {
    const s = summarizeCompanions([c({}), c({}), c({ relationshipStage: 'close' })], NOW)
    expect(s.stages[0]).toEqual({ stage: UNKNOWN_STAGE, count: 2 })
  })

  it('最新的五个按创建时间倒序, 没时间的排不上', () => {
    const list = Array.from({ length: 7 }, (_, i) =>
      c({ id: `a${i}`, createdAt: `2026-09-1${i}T00:00:00Z` }))
    list.push(c({ id: 'no-time' }))
    const s = summarizeCompanions(list, NOW)
    expect(s.newest.map((x) => x.id)).toEqual(['a6', 'a5', 'a4', 'a3', 'a2'])
  })

  it('不修改传入的列表', () => {
    const list = [c({ id: 'a', createdAt: '2026-09-01T00:00:00Z' }),
      c({ id: 'b', createdAt: '2026-09-17T00:00:00Z' })]
    summarizeCompanions(list, NOW)
    expect(list.map((x) => x.id)).toEqual(['a', 'b'])
  })
})
