/**
 * 记录渲染层的测试。
 *
 * 这些断言看着琐碎(一个空值显示成什么、camelCase 怎么断), 但它们是 Studio 那 25 个
 * 接口在屏幕上的**全部**形状 —— 而这一层错了不会报错, 只会让人看见一片 '—',
 * 然后去怀疑后端没返回数据。
 */
import { describe, expect, it } from 'vitest'
import {
  humanKey,
  humanTime,
  isScalar,
  scalarText,
  shortId,
  splitEntries,
  titleOf,
  truncate,
} from './format'

describe('isScalar', () => {
  it.each([1, 'x', true, false, null, undefined])('%s 是标量', (v) => {
    expect(isScalar(v)).toBe(true)
  })

  it.each([[{}], [[]], [{ a: 1 }]])('%o 不是标量', (v) => {
    expect(isScalar(v)).toBe(false)
  })
})

describe('humanTime', () => {
  it('ISO 时间戳裁到分钟', () => {
    // 用本地时间构造再断言本地时间 —— 直接写字符串会因时区而挂。
    expect(humanTime('2026-09-18T10:23:45')).toBe('2026-09-18 10:23')
  })

  it('缺值 → —', () => {
    expect(humanTime(null)).toBe('—')
    expect(humanTime(undefined)).toBe('—')
    expect(humanTime('')).toBe('—')
  })

  it('不是时间的字符串**原样返回**, 不返回 —', () => {
    // '—' 说的是"没有值", 而"每天凌晨"是一个真实的值 —— 两者的排查方向完全不同。
    expect(humanTime('每天凌晨')).toBe('每天凌晨')
  })

  it('只有年份的字符串不被当成时间戳', () => {
    // new Date('2026') 是合法的(2026-01-01T00:00:00Z), 交给 Date 猜会得到一条假时间。
    expect(humanTime('2026')).toBe('2026')
  })

  it('日期残缺但月份日合法时仍按原文', () => {
    expect(humanTime('2026-09')).toBe('2026-09')
  })

  it('形状对但日期非法 → 原文', () => {
    expect(humanTime('2026-13-45T99:99')).toBe('2026-13-45T99:99')
  })
})

describe('scalarText', () => {
  it('空值统一 —', () => {
    expect(scalarText(null)).toBe('—')
    expect(scalarText(undefined)).toBe('—')
    expect(scalarText('')).toBe('—')
  })

  it('布尔说中文', () => {
    expect(scalarText(true)).toBe('是')
    expect(scalarText(false)).toBe('否')
  })

  it('整数不带小数点, 小数裁到三位', () => {
    expect(scalarText(3)).toBe('3')
    expect(scalarText(0.7333333)).toBe('0.733')
  })

  it('字符串里的时间戳顺手裁开', () => {
    // 后端 JSON 化之后时间也是字符串 —— 不裁的话整行会被 2026-09-18T10:23:45.123Z 撑爆。
    // 断言"不再含 T"而不是某个具体钟点: 带 Z 的按 UTC 解析后要换算到**用户本地时区**,
    // 钉死钟点等于让这条测试依赖跑测试那台机器的时区。
    const out = scalarText('2026-09-18T10:23:45.123456Z')
    expect(out).not.toContain('T')
    expect(out).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  it('0 与 false 不被当成空', () => {
    expect(scalarText(0)).toBe('0')
    expect(scalarText(false)).toBe('否')
  })
})

describe('shortId', () => {
  it('长的截断并留省略号', () => {
    expect(shortId('0123456789abcdef', 8)).toBe('01234567…')
  })

  it('短的原样', () => {
    expect(shortId('abc', 8)).toBe('abc')
  })

  it('缺值 → —', () => {
    expect(shortId(null)).toBe('—')
    expect(shortId('')).toBe('—')
  })
})

describe('humanKey', () => {
  it('认识的字段给中文', () => {
    expect(humanKey('emotionalCloseness')).toBe('情感亲密度')
    expect(humanKey('createdAt')).toBe('创建于')
  })

  it('不认识的拆词并小写', () => {
    expect(humanKey('lastSeenMood')).toBe('last seen mood')
    expect(humanKey('relationship_stage')).toBe('relationship stage')
  })

  it('单段小写原样', () => {
    expect(humanKey('foo')).toBe('foo')
  })
})

describe('splitEntries', () => {
  it('保持服务端给的字段顺序', () => {
    const { scalars } = splitEntries({ b: 1, a: 2, c: 3 })
    expect(scalars.map((e) => e.key)).toEqual(['b', 'a', 'c'])
  })

  it('数组与对象进容器堆', () => {
    const { scalars, containers } = splitEntries({ id: 'x', tags: [1, 2], meta: { a: 1 } })
    expect(scalars.map((e) => e.key)).toEqual(['id'])
    expect(containers.map((e) => e.key)).toEqual(['tags', 'meta'])
  })

  it('空数组与空对象当标量 —— "没有"而不是"这里曾经可能有点什么"', () => {
    const { scalars, containers } = splitEntries({ tags: [], meta: {} })
    expect(containers).toEqual([])
    expect(scalars.map((e) => [e.key, e.value])).toEqual([['tags', '—'], ['meta', '—']])
  })

  it('label 跟着 key 一起算好', () => {
    const { scalars } = splitEntries({ createdAt: '2026-09-18T10:00:00' })
    expect(scalars[0].label).toBe('创建于')
  })

  it('null / undefined 记录不炸', () => {
    expect(splitEntries(null)).toEqual({ scalars: [], containers: [] })
    expect(splitEntries(undefined).containers).toEqual([])
  })
})

describe('titleOf', () => {
  it('优先 title > name > summary > content > type', () => {
    expect(titleOf({ content: 'c', name: 'n', title: 't' })).toBe('t')
    expect(titleOf({ content: 'c', name: 'n' })).toBe('n')
    expect(titleOf({ content: 'c' })).toBe('c')
  })

  it('空白字符串不算数', () => {
    expect(titleOf({ title: '   ', name: 'n' })).toBe('n')
  })

  it('都没有就退回 id 的短形式', () => {
    expect(titleOf({ id: '0123456789abcdef' })).toBe('01234567…')
    expect(titleOf({ memoryId: 'abcdefghijklmnop' })).toBe('abcdefgh…')
  })

  it('空记录不炸', () => {
    expect(titleOf(null)).toBe('—')
    expect(titleOf({})).toBe('—')
  })
})

describe('truncate', () => {
  it('短的原样', () => {
    expect(truncate('好', 10)).toBe('好')
  })

  it('长的截断', () => {
    expect(truncate('a'.repeat(20), 10)).toBe(`${'a'.repeat(10)}…`)
  })

  it('先去空白再判断长度 —— 否则一排空格也会被当成"有内容"', () => {
    expect(truncate('  短  ', 10)).toBe('短')
  })
})
