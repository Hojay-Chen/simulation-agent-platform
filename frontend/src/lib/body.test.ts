/**
 * 生理量分组的测试。
 *
 * 这一层错的症状是**方向反了**: 屏幕上一个 0.8, 本该读成"她很饿", 却读成
 * "她很精神"。没有报错, 没有崩溃, 只有一个人对界面失去信任。所以断言的重点
 * 是每个量的 `goodHigh` 与"哪一组", 以及所有兜底路径。
 */
import { describe, expect, it } from 'vitest'
import {
  GROUP_META,
  SENSE_CHANNELS,
  VITALS,
  describeReading,
  groupOf,
  standout,
  summarizeVitals,
} from './body'

/** `/api/companions/{id}/state` 的真实形状。 */
const STATE = {
  mood: 0.62, energy: 0.71, stress: 0.18, socialEnergy: 0.55,
  curiosity: 0.66, emotionalCloseness: 0.7, hurt: 0.02, anger: 0.01,
  sadness: 0.08, anxiety: 0.15, warmth: 0.6, sleepiness: 0.3,
  hunger: 0.42, physicalDiscomfort: 0.05, focus: 0.58,
  loneliness: 0.22, joy: 0.5, affection: 0.64,
  updatedAt: '2026-09-19T12:00:00Z',
}

describe('分组表本身', () => {
  it('key 不重复 —— 重复的 key 会让后一个量再也画不出来', () => {
    const keys = VITALS.map((v) => v.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('三组都有中文名与说明', () => {
    for (const g of ['body', 'emotion', 'mind'] as const) {
      expect(GROUP_META[g].label.trim()).not.toBe('')
      expect(GROUP_META[g].hint.trim()).not.toBe('')
    }
  })

  it('每一组都至少有一个量 —— 一个空组的标题挂在页面上是纯噪声', () => {
    for (const g of ['body', 'emotion', 'mind'] as const) {
      expect(VITALS.filter((v) => v.group === g).length, `${g} 组是空的`).toBeGreaterThan(0)
    }
  })

  it('方向标错的代价最大, 所以逐条钉住那几个反直觉的', () => {
    // 这三个越高越糟。它们和 energy 在屏幕上长得一模一样。
    for (const k of ['hunger', 'sleepiness', 'physicalDiscomfort', 'stress']) {
      expect(VITALS.find((v) => v.key === k)!.goodHigh, `${k} 的方向反了`).toBe(false)
    }
    for (const k of ['energy', 'warmth', 'focus', 'mood', 'joy']) {
      expect(VITALS.find((v) => v.key === k)!.goodHigh, `${k} 的方向反了`).toBe(true)
    }
  })

  it('focus 属于心智组 —— 它是注意力的实际输入, 不是情绪', () => {
    expect(VITALS.find((v) => v.key === 'focus')!.group).toBe('mind')
  })

  it('表里登记的量都在真实的 /state 返回体里存在 —— 否则页面上会少一块', () => {
    for (const v of VITALS) {
      expect(Object.keys(STATE), `${v.key} 在 /state 里不存在`).toContain(v.key)
    }
  })
})

describe('summarizeVitals', () => {
  it('只挑数值字段, 时间戳被挡掉', () => {
    const r = summarizeVitals(STATE)
    expect(r.map((x) => x.vital.key)).not.toContain('updatedAt')
    expect(r).toHaveLength(18)
  })

  it('null / undefined / 空对象都得到空数组, 不抛错', () => {
    expect(summarizeVitals(null)).toEqual([])
    expect(summarizeVitals(undefined)).toEqual([])
    expect(summarizeVitals({})).toEqual([])
  })

  it('字符串、"NaN"、null 值一律不进列表 —— 它们画出来是个假刻度', () => {
    const r = summarizeVitals({ energy: '0.5', mood: null, joy: Number.NaN, affection: 0.9 })
    expect(r.map((x) => x.vital.key)).toEqual(['affection'])
  })

  it('越界的值被夹进 0–1, 而不是画到框外', () => {
    const r = summarizeVitals({ energy: 3, hunger: -2 })
    expect(r.find((x) => x.vital.key === 'energy')!.value).toBe(1)
    expect(r.find((x) => x.vital.key === 'hunger')!.value).toBe(0)
  })

  it('未登记的字段仍然出现, 但带标记 —— 后端加了量不该让它静默消失', () => {
    const r = summarizeVitals({ ...STATE, mysteriousNewThing: 0.4 })
    const m = r.find((x) => x.vital.key === 'mysteriousNewThing')!
    expect(m.unregistered).toBe(true)
    expect(m.vital.label).toBe('mysteriousNewThing')
    expect(r.find((x) => x.vital.key === 'energy')!.unregistered).toBe(false)
  })

  it('顺序稳定: 身体 → 情绪 → 心智', () => {
    const groups = summarizeVitals(STATE).map((x) => x.vital.group)
    const first = groups.indexOf('emotion')
    const lastBody = groups.lastIndexOf('body')
    expect(lastBody).toBeLessThan(first)
    expect(groups.lastIndexOf('emotion')).toBeLessThan(groups.indexOf('mind'))
  })

  it('同一份输入调两次得到同样的顺序 —— 否则页面每刷新一次就重排', () => {
    expect(summarizeVitals(STATE).map((x) => x.vital.key))
      .toEqual(summarizeVitals(STATE).map((x) => x.vital.key))
  })
})

describe('groupOf', () => {
  it('切出三段连续的分组', () => {
    const g = groupOf(summarizeVitals(STATE))
    expect(g.map((x) => x.group)).toEqual(['body', 'emotion', 'mind'])
    expect(g.reduce((n, x) => n + x.items.length, 0)).toBe(18)
  })

  it('空输入得到空数组', () => {
    expect(groupOf([])).toEqual([])
  })
})

describe('standout', () => {
  it('挑偏离 0.5 最远的那个', () => {
    const r = summarizeVitals({ energy: 0.5, hunger: 0.95, joy: 0.5 })
    expect(standout(r)!.vital.key).toBe('hunger')
  })

  it('全都贴着 0.5 时返回 null —— 没有可说的就不要说', () => {
    expect(standout(summarizeVitals({ energy: 0.5, hunger: 0.52, joy: 0.48 }))).toBeNull()
  })

  it('不拿未登记的量去当"最突出的那一个"', () => {
    const r = summarizeVitals({ energy: 0.5, unknown: 1 })
    expect(standout(r)).toBeNull()
  })

  it('空输入返回 null', () => {
    expect(standout([])).toBeNull()
  })
})

describe('describeReading', () => {
  const reading = (key: string, value: number) =>
    summarizeVitals({ [key]: value })[0]

  it('中等不评价, 只报数', () => {
    expect(describeReading(reading('energy', 0.5))).toBe('精力中等')
  })

  it('高且好 → 有利', () => {
    expect(describeReading(reading('energy', 0.9))).toContain('有利')
  })

  it('高且坏 → 不利', () => {
    expect(describeReading(reading('hunger', 0.9))).toContain('不利')
  })

  it('低且坏 → 不利', () => {
    expect(describeReading(reading('energy', 0.1))).toContain('不利')
  })

  it('低且好 → 有利', () => {
    expect(describeReading(reading('hunger', 0.1))).toContain('有利')
  })
})

describe('五感通道', () => {
  it('五条, key 不重复', () => {
    expect(SENSE_CHANNELS).toHaveLength(5)
    const keys = SENSE_CHANNELS.map((s) => s.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('每一条都写清楚它从哪进来 —— 只说"视觉"等于没说', () => {
    for (const s of SENSE_CHANNELS) {
      expect(s.label.trim()).not.toBe('')
      expect(s.via.trim()).not.toBe('')
      expect(s.hint.trim()).not.toBe('')
    }
  })

  it('视觉的说明里必须点破"只有她主动去看才有输入" —— 这是整个产品最要紧的一句话', () => {
    const vision = SENSE_CHANNELS.find((s) => s.key === 'vision')!
    expect(vision.hint).toContain('主动')
  })
})
