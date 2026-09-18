/**
 * 事件分类层的测试。
 *
 * 这一层错了的症状是**误导**, 不是崩溃: 一条感官事件被画成"不用立刻管",
 * 页面照常渲染, 颜色也对, 只是它告诉人的结论反了。所以断言的重点是
 * **边界与兜底**, 不是某几条已知类型的映射。
 */
import { describe, expect, it } from 'vitest'
import {
  AWARENESS_LEVELS,
  EVENT_CLASS_META,
  MESSAGE_LADDER,
  awarenessOf,
  classifyEvent,
  classifyFromServer,
  eventLabelZh,
  isKnownEventType,
  ladderIndexOf,
  weightOf,
  type EventClass,
} from './events'

describe('classifyEvent', () => {
  it('送达是事实, 不是感官 —— §2.3 的核心区分', () => {
    expect(classifyEvent('USER_MESSAGE_RECEIVED')).toEqual(['fact'])
    expect(classifyEvent('USER_MESSAGE_NOTIFIED')).toEqual(['sensory'])
    // 这两条**必须**不同。它们一旦被归成一类, 界面上就再也看不出
    // "消息到了"和"她听见了"之间的那条边界 —— 而那条边界是整个 V2.2 的中心。
    expect(classifyEvent('USER_MESSAGE_RECEIVED')).not.toEqual(classifyEvent('USER_MESSAGE_NOTIFIED'))
  })

  it('环境与情绪是持续影响 —— 它们不会因为被处理过一次就消失', () => {
    expect(classifyEvent('ENVIRONMENT_CHANGED')).toEqual(['effect'])
    expect(classifyEvent('ACTIVITY_PROGRESS')).toEqual(['effect'])
  })

  it('认不出的类型落到 fact, 而不是猜一类', () => {
    expect(classifyEvent('THIRD_PARTY_THING_V9')).toEqual(['fact'])
    expect(classifyEvent('')).toEqual(['fact'])
    // 最要紧的一条: 未知类型**绝不能**被判成 sensory。
    // 判错成感官 = 教育用户去忽略这个界面上最刺眼的那个颜色。
    expect(classifyEvent('THIRD_PARTY_THING_V9')).not.toContain('sensory')
  })

  it('大小写敏感 —— 线上的取值是 SCREAMING_SNAKE, 手改 URL 不该命中', () => {
    expect(isKnownEventType('user_message_received')).toBe(false)
    expect(classifyEvent('user_message_received')).toEqual(['fact'])
  })
})

describe('classifyFromServer', () => {
  it('服务端给了分类就采信, 且支持多值', () => {
    expect(classifyFromServer('SENSORY')).toEqual(['sensory'])
    expect(classifyFromServer('STATE_EFFECT,SENSORY')).toEqual(['effect', 'sensory'])
  })

  it('忽略空白与大小写', () => {
    expect(classifyFromServer(' sensory , scheduled ')).toEqual(['sensory', 'schedule'])
  })

  it('认不出/为空时返回 null —— 调用方据此回落到类型表', () => {
    expect(classifyFromServer(null)).toBeNull()
    expect(classifyFromServer(undefined)).toBeNull()
    expect(classifyFromServer('')).toBeNull()
    // 关键: **不能**返回空数组。空数组在界面上会画成一条"没有类别"的事件,
    // 那是个不存在的状态, 而且会让调用方误以为"服务端说了它什么都不是"。
    expect(classifyFromServer('WEIRD_NEW_THING')).toBeNull()
  })
})

describe('weightOf', () => {
  it('取最急的那一档', () => {
    expect(weightOf(['fact', 'sensory'])).toBe(weightOf(['sensory']))
    expect(weightOf(['fact', 'sensory'])).toBeGreaterThan(weightOf(['effect']))
  })

  it('空数组不炸', () => {
    expect(weightOf([])).toBe(0)
  })
})

describe('awarenessOf', () => {
  it('阈值与后端文档一致', () => {
    expect(awarenessOf(0)).toBe('NONE')
    expect(awarenessOf(0.19)).toBe('NONE')
    expect(awarenessOf(0.2)).toBe('SUBCONSCIOUS')
    expect(awarenessOf(0.49)).toBe('SUBCONSCIOUS')
    expect(awarenessOf(0.5)).toBe('AWARE')
    expect(awarenessOf(0.79)).toBe('AWARE')
    expect(awarenessOf(0.8)).toBe('FOCUSED')
    expect(awarenessOf(1)).toBe('FOCUSED')
  })

  it('NaN / Infinity / 越界不产生一个不存在的级别', () => {
    for (const bad of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      expect(AWARENESS_LEVELS).toContain(awarenessOf(bad))
    }
    expect(awarenessOf(-1)).toBe('NONE')
    expect(awarenessOf(5)).toBe('FOCUSED')
  })
})

describe('消息台阶', () => {
  it('五级, 顺序是 送达 → 响 → 注意 → 看 → 推迟', () => {
    expect(MESSAGE_LADDER.map((s) => s.type)).toEqual([
      'USER_MESSAGE_RECEIVED',
      'USER_MESSAGE_NOTIFIED',
      'USER_MESSAGE_NOTICED',
      'USER_MESSAGE_READ',
      'USER_MESSAGE_DEFERRED',
    ])
  })

  it('ladderIndexOf 认出台阶上的类型, 其余返回 -1', () => {
    expect(ladderIndexOf('USER_MESSAGE_NOTICED')).toBe(2)
    expect(ladderIndexOf('ACTIVITY_STARTED')).toBe(-1)
    expect(ladderIndexOf('')).toBe(-1)
  })

  it('台阶上每一级都是被登记过的类型 —— 否则事件流里同一个类型会有两种画法', () => {
    for (const step of MESSAGE_LADDER) {
      expect(isKnownEventType(step.type), `${step.type} 在台阶上但没登记`).toBe(true)
    }
  })
})

describe('展示表', () => {
  it('每一类都有中文名、说明和四种视觉类名', () => {
    const classes: EventClass[] = ['fact', 'effect', 'sensory', 'schedule']
    for (const c of classes) {
      const m = EVENT_CLASS_META[c]
      expect(m.label.trim()).not.toBe('')
      expect(m.hint.trim()).not.toBe('')
      // 类名必须写成完整字面量, 否则 Tailwind 的扫描器看不见它们 —— 结果是
      // 一个在生产环境里没有颜色的图例, 而 dev 下看着是对的。
      expect(m.bg).toMatch(/^bg-/)
      expect(m.text).toMatch(/^text-/)
      expect(m.dot).toMatch(/^bg-/)
    }
  })

  it('四类的标签互不相同', () => {
    const labels = Object.values(EVENT_CLASS_META).map((m) => m.label)
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('认不出的类型原样返回机器名 —— 编一个中文名会让人以为是登记过的', () => {
    expect(eventLabelZh('USER_MESSAGE_NOTIFIED')).toBe('手机响了')
    expect(eventLabelZh('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
