/**
 * 记录渲染的纯函数层 —— Studio 那 25 个内省接口的**唯一**呈现规则。
 *
 * <h2>为什么是一个通用渲染器, 而不是八个标签页各写各的</h2>
 *
 * 因为服务端返回的是 {@code Map<String,Object>} / {@code List<Map<String,Object>>}
 * —— 记忆、关系、生活、世界事件、反思……它们在**类型上**长得一模一样, 差别只在
 * 字段名。给每个端点手写一张面板, 得到的是 25 份"字段名写错了就静默显示空白"的
 * 代码; 通用的那一份则保证: 服务端加了字段, 页面第二天就会显示出来。
 *
 * 代价是排版不如定制的精致。这个代价是愿意付的 —— 一个内省控制台里, "字段全在"
 * 比"排版好看"重要得多。
 *
 * <h2>为什么全在这里, 而不是在组件里</h2>
 *
 * 本仓前端测试跑在 node 上(没有 jsdom), 组件渲染不起来 —— 写在 JSX 里的判断一条
 * 都保护不了。所以凡是"看着像格式问题、其实是逻辑"的东西(空值怎么显示、日期怎么裁、
 * 对象怎么拆)全部落在这个文件里, 由 `format.test.ts` 逐条钉住。
 */

/** 标量 = 能在一行里说完的值。对象与数组交给下一层, 不在这里拍平。 */
export function isScalar(v: unknown): v is string | number | boolean | null | undefined {
  return v === null || v === undefined || typeof v !== 'object'
}

/**
 * ISO 时间戳 → `2026-09-18 10:23`。
 *
 * **解析不了就原样返回**, 不返回 '—': 后端偶尔会往时间字段里塞别的东西(比如把
 * "每天凌晨" 这种描述放进 createdAt), 那种时候把原文显示出来是有信息量的,
 * 而 '—' 会让人以为是"没有值"——两者该走完全不同的排查方向。
 */
export function humanTime(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  const raw = String(value)
  // 只接受 ISO 8601 的日期开头。`new Date("2026")` 是合法的, 而 "2026" 显然
  // 不是一个时间戳 —— 交给 Date 自己猜会得到一条看起来很像真的的假时间。
  if (!/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 一个标量怎么显示。空值统一 '—', 布尔用中文 —— 屏幕上不该出现 `true`。 */
export function scalarText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(3)
  if (typeof value === 'string') {
    // 字符串里包着时间戳是常态(后端 JSON 化之后全成了字符串) —— 顺手裁一下,
    // 否则 `2026-09-18T10:23:45.123456Z` 会把整行撑爆。
    if (/^\d{4}-\d{2}-\d{2}T/.test(value)) return humanTime(value)
    return value
  }
  return String(value)
}

/** 长 id 只显示头一段 —— 完整值挂在 title 上, 鼠标停一下就有。 */
export function shortId(id: unknown, keep = 8): string {
  const s = id === null || id === undefined ? '' : String(id)
  if (!s) return '—'
  return s.length <= keep ? s : `${s.slice(0, keep)}…`
}

/**
 * 已知字段的中文名 —— **只收真正会反复出现的那些**。
 *
 * 不收全: 词典越长, 越容易在服务端改名后留下一堆过期映射, 而过期映射的症状是
 * "这一栏的名字和别处对不上", 比英文原名难查得多。查不到的走 {@link humanKey},
 * 英文原文也远比一个猜错的中文好。
 */
const KEY_LABELS: Record<string, string> = {
  id: 'ID',
  name: '名字',
  title: '标题',
  type: '类型',
  content: '内容',
  summary: '摘要',
  status: '状态',
  stage: '阶段',
  note: '备注',
  reason: '原因',
  createdAt: '创建于',
  updatedAt: '更新于',
  lastAccessedAt: '上次访问',
  importance: '重要度',
  score: '分数',
  tags: '标签',
  mood: '情绪',
  emotionalCloseness: '情感亲密度',
  sleepiness: '困倦度',
  companionId: '所属 agent',
  userId: '所属用户',
  version: '版本',
  handle: '账号ID',
}

/** `emotionalCloseness` → `emotional closeness`; `relationship_stage` → `relationship stage`。 */
export function humanKey(key: string): string {
  if (KEY_LABELS[key]) return KEY_LABELS[key]
  return key
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
}

export interface Entry {
  key: string
  label: string
  value: unknown
}

export interface SplitEntries {
  /** 能在一行里说完的 —— 渲染成定义列表。 */
  scalars: Entry[]
  /** 数组与对象 —— 各自展开成一块, 不塞进定义列表里。 */
  containers: Entry[]
}

/**
 * 把一条记录拆成"标量"与"容器"两堆, **保持服务端给的字段顺序**。
 *
 * 顺序刻意不动: 服务端按 `@JsonPropertyOrder` 或字段声明顺序序列化, 那个顺序往往
 * 就是作者心里"这件事该怎么说"的顺序(先身份后状态、先结论后细节)。前端按字母重排
 * 会把那条线索抹掉, 而它恰恰是唯一能让人看懂一坨 JSON 的东西。
 */
export function splitEntries(record: Record<string, unknown> | null | undefined): SplitEntries {
  const scalars: Entry[] = []
  const containers: Entry[] = []
  if (!record || typeof record !== 'object') return { scalars, containers }
  for (const [key, value] of Object.entries(record)) {
    const entry: Entry = { key, label: humanKey(key), value }
    if (isScalar(value)) scalars.push(entry)
    else if (Array.isArray(value) && value.length === 0) {
      // 空数组是标量语义 —— "没有"。当成容器渲染会得到一块只有标题的空板子,
      // 满屏都是"这里曾经可能有点什么"。
      scalars.push({ ...entry, value: '—' })
    } else if (!Array.isArray(value) && Object.keys(value as object).length === 0) {
      scalars.push({ ...entry, value: '—' })
    } else containers.push(entry)
  }
  return { scalars, containers }
}

/** 卡片标题: 记录里最像"这一条是什么"的那个字段。找不到就退回 id 的短形式。 */
export function titleOf(record: Record<string, unknown> | null | undefined): string {
  if (!record) return '—'
  for (const key of ['title', 'name', 'summary', 'content', 'type']) {
    const v = record[key]
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return shortId(record.id ?? record.memoryId ?? record.versionId)
}

/** 截断 —— 列表里的一行不该因为一条长记忆变成一面墙。 */
export function truncate(text: string, max = 140): string {
  const s = text.trim()
  return s.length <= max ? s : `${s.slice(0, max)}…`
}
