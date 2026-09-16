import type { ReactNode } from 'react'

/**
 * 控制台的原子件 —— 保持极小, 不引组件库: 这个前端只有四个页面。
 *
 * 这一个文件是**全仓最高杠杆的单点**: 四个页面里的每一块面板、每一个按钮、每一个输入框
 * 都从这 100 行里出去。改这里, 四页同时变 —— 这也是为什么重做视觉时不先动页面。
 *
 * 全文件只用语义 token(`bg-raised` / `text-ink-soft` / `border-line` …), 一个 `dark:`
 * 前缀都没有: 亮暗两套由 `index.css` 的 `:root` / `.dark` 提供, 组件层不需要知道
 * 主题这回事。原来那层投影去掉了 —— 页面上的卡片靠 1px 边框区分, 不靠投影。
 */

export function Panel({ title, action, children, className = '' }: {
  title?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`rounded-xl border border-line bg-raised ${className}`}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-3 border-b border-line px-5 py-3">
          {typeof title === 'string'
            ? <h2 className="text-sm font-medium tracking-wide text-ink">{title}</h2>
            : title}
          {action}
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  )
}

export function Button({ children, onClick, type = 'button', variant = 'primary', disabled, title }: {
  children: ReactNode
  onClick?: () => void
  type?: 'button' | 'submit'
  variant?: 'primary' | 'ghost' | 'danger'
  disabled?: boolean
  title?: string
}) {
  // 复用 `index.css` 里的 `.btn-*` —— 与仓 1 聊天前端是同一套定义, 于是同一个
  // "确认"按钮在两个站点里是同一个高度、同一个圆角、同一种蓝。
  const styles = { primary: 'btn-primary', ghost: 'btn-outline', danger: 'btn-danger' }[variant]
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={`${styles} !px-3 !py-1.5 disabled:opacity-40 disabled:cursor-not-allowed`}
    >
      {children}
    </button>
  )
}

export function Field({ label, hint, error, children }: {
  label: string
  hint?: ReactNode
  error?: string | null
  children: ReactNode
}) {
  return (
    <label className="block space-y-1.5">
      <span className="block text-xs font-medium uppercase tracking-wider text-ink-soft">{label}</span>
      {children}
      {error
        ? <span className="block text-xs text-danger">{error}</span>
        : hint && <span className="block text-xs leading-relaxed text-ink-faint">{hint}</span>}
    </label>
  )
}

/**
 * 被 5 处 `<input>` / `<textarea>` / `<select>` 共用的类名。
 *
 * 写成常量而不是一个 `<Input>` 组件, 是因为那 5 处的元素类型不同(输入框、多行、下拉),
 * 包一层只会让每处多一个 `as` 或多一个 prop。但**类名必须只有一份** ——
 * 抄 5 遍的后果是某一天有人改了 4 处, 而第 5 处看起来"就是有点不一样"。
 */
export const inputClass = 'input'

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-sm text-ink-faint">{children}</p>
}

/** 错误条 —— 服务端的 {error} 文案原样呈现, 不吞不换。 */
export function ErrorNote({ error }: { error: string | null }) {
  if (!error) return null
  return (
    <p className="rounded-lg border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
      {error}
    </p>
  )
}

/** 提示条 —— 与错误条同形不同色。用 accent 而不是 warn: 这里说的是"你要知道这件事", 不是"出事了" */
export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-accent/30 bg-accent-soft px-3 py-2 text-sm text-accent">
      {children}
    </p>
  )
}

export function Chip({ children, tone = 'neutral' }: {
  children: ReactNode
  tone?: 'neutral' | 'ok' | 'warn'
}) {
  const toneClass = {
    neutral: 'border-line text-ink-soft',
    ok: 'border-ok/40 text-ok',
    warn: 'border-warn/40 text-warn',
  }[tone]
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs ${toneClass}`}>
      {children}
    </span>
  )
}
