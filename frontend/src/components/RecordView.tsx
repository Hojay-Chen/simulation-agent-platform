import { Empty } from './ui'
import { isScalar, scalarText, shortId, splitEntries, titleOf, truncate } from '@/lib/format'

/**
 * 一坨 `Map<String,Object>` 怎么变成一屏能读的东西。
 *
 * <h2>为什么是通用件</h2>
 *
 * Studio 的二十几个内省接口返回的都是同一种形状(对象或对象数组), 差别只在字段名。
 * 给每个端点写一张定制面板, 得到的是二十几份"字段改名后静默显示空白"的代码; 通用
 * 的这一份保证服务端加了字段, 页面第二天就显示出来。代价是排版不如定制的精致 ——
 * 在一个内省控制台里, "字段全在"比"排版好看"重要。
 *
 * <h2>深度是封顶的</h2>
 *
 * 到第三层就不再往下拆, 直接把 JSON 打出来。理由是**屏幕比数据浅**: 记忆里嵌套的
 * 结构化字段(比如一条 metadata 里套着一层 tags 里再套一层)往下列三层之后, 缩进已经
 * 吃掉半个屏幕, 而人眼在那个深度上读的是缩进不是内容。到那一步, 原始 JSON 反而更
 * 容易看 —— 至少它不假装自己是排版。
 */
const MAX_DEPTH = 3

export function RecordView({ value, empty = '没有数据。' }: {
  value: unknown
  empty?: string
}) {
  return <Node value={value} depth={0} empty={empty} />
}

function Node({ value, depth, empty }: { value: unknown; depth: number; empty: string }) {
  if (value === null || value === undefined) return <Empty>{empty}</Empty>

  if (Array.isArray(value)) {
    if (value.length === 0) return <Empty>{empty}</Empty>
    // 纯标量数组(标签、能力 id 之类)排成一行 chip —— 竖着列 12 个字符串是浪费。
    if (value.every(isScalar)) {
      return (
        <div className="flex flex-wrap gap-1.5">
          {value.map((v, i) => (
            <span key={i} className="rounded-md border border-line bg-sunken px-2 py-0.5 font-mono text-xs text-ink-soft">
              {scalarText(v)}
            </span>
          ))}
        </div>
      )
    }
    return (
      <ul className="space-y-3">
        {value.map((v, i) => (
          <li key={i} className="rounded-lg border border-line bg-sunken/40 p-3">
            {isScalar(v)
              ? <span className="text-sm text-ink">{scalarText(v)}</span>
              : (
                <>
                  <p className="mb-2 truncate text-xs font-medium text-ink-soft">
                    {titleOf(v as Record<string, unknown>)}
                  </p>
                  <Node value={v} depth={depth + 1} empty={empty} />
                </>
              )}
          </li>
        ))}
      </ul>
    )
  }

  if (typeof value === 'object') {
    const { scalars, containers } = splitEntries(value as Record<string, unknown>)
    if (scalars.length === 0 && containers.length === 0) return <Empty>{empty}</Empty>
    return (
      <div className="space-y-4">
        {scalars.length > 0 && (
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            {scalars.map((e) => (
              <div key={e.key} className="flex min-w-0 items-baseline gap-2">
                <dt className="shrink-0 text-xs tracking-wide text-ink-faint">{e.label}</dt>
                <dd
                  className="min-w-0 flex-1 truncate text-sm text-ink"
                  title={String(e.value ?? '')}
                >
                  {scalarText(e.value)}
                </dd>
              </div>
            ))}
          </dl>
        )}
        {containers.map((e) => (
          <section key={e.key}>
            <p className="mb-1.5 text-xs tracking-wide text-ink-faint">
              {e.label}
              {Array.isArray(e.value) && <span className="ml-1.5">({e.value.length})</span>}
            </p>
            {depth >= MAX_DEPTH
              ? (
                <pre className="max-h-48 overflow-auto rounded-lg border border-line bg-sunken/60 p-2.5 font-mono text-[11px] leading-relaxed text-ink-soft">
                  {JSON.stringify(e.value, null, 2)}
                </pre>
              )
              : <Node value={e.value} depth={depth + 1} empty={empty} />}
          </section>
        ))}
      </div>
    )
  }

  // 裸标量 —— 面板直接读了一个返回字符串/数字的端点时走这里, 不常见但合法。
  return <p className="text-sm text-ink">{scalarText(value)}</p>
}

/**
 * 身份证似的一块: 主标题 + 副标题(通常是 id) + 内容。
 *
 * 与 {@link RecordView} 分开, 是因为"一条记录长什么样"和"这一块该叫什么名字"是两件事:
 * 前者由数据决定, 后者由页面决定。
 */
export function RecordCard({ title, subtitle, value, empty }: {
  title: string
  subtitle?: string
  value: unknown
  empty?: string
}) {
  return (
    <div className="rounded-xl border border-line bg-raised p-4">
      <div className="mb-3 flex min-w-0 items-baseline gap-2">
        <h3 className="truncate text-sm font-medium text-ink">{title}</h3>
        {subtitle && (
          <span className="truncate font-mono text-xs text-ink-faint" title={subtitle}>
            {shortId(subtitle, 12)}
          </span>
        )}
      </div>
      <RecordView value={value} empty={empty} />
    </div>
  )
}

/** 一行文本摘要 —— 首页的"最近创建"之类的短列表用, 比一整张卡片轻。 */
export function SummaryLine({ text, right }: { text: string; right?: string }) {
  return (
    <div className="flex min-w-0 items-baseline justify-between gap-3 py-1.5">
      <span className="truncate text-sm text-ink" title={text}>{truncate(text, 60)}</span>
      {right && <span className="shrink-0 text-xs text-ink-faint">{right}</span>}
    </div>
  )
}
