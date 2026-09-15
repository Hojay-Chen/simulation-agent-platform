import type { ReactNode } from 'react'

/** 控制台的原子件 —— 保持极小, 不引组件库: 这个前端只有四个页面。 */

export function Panel({ title, action, children, className = '' }: {
  title?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`rounded-xl border border-cocoa-700 bg-cocoa-850 shadow-panel ${className}`}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-3 border-b border-cocoa-700 px-5 py-3">
          {typeof title === 'string'
            ? <h2 className="text-sm font-medium tracking-wide text-cocoa-200">{title}</h2>
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
  const base =
    'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition disabled:opacity-40 disabled:cursor-not-allowed'
  const styles = {
    primary: 'bg-ember text-cocoa-950 hover:bg-ember-soft',
    ghost: 'border border-cocoa-600 text-cocoa-200 hover:border-cocoa-500 hover:text-cocoa-100',
    danger: 'border border-rosewood/60 text-rosewood-soft hover:bg-rosewood/15',
  }[variant]
  return (
    <button type={type} onClick={onClick} disabled={disabled} title={title} className={`${base} ${styles}`}>
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
      <span className="block text-xs font-medium uppercase tracking-wider text-cocoa-400">{label}</span>
      {children}
      {error
        ? <span className="block text-xs text-rosewood-soft">{error}</span>
        : hint && <span className="block text-xs text-cocoa-500">{hint}</span>}
    </label>
  )
}

export const inputClass =
  'w-full rounded-lg border border-cocoa-600 bg-cocoa-900 px-3 py-2 text-sm text-cocoa-100 ' +
  'placeholder:text-cocoa-500 outline-none focus:border-ember/70 focus:ring-1 focus:ring-ember/30'

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-8 text-center text-sm text-cocoa-500">{children}</p>
}

/** 错误条 —— 服务端的 {error} 文案原样呈现, 不吞不换。 */
export function ErrorNote({ error }: { error: string | null }) {
  if (!error) return null
  return (
    <p className="rounded-lg border border-rosewood/40 bg-rosewood/10 px-3 py-2 text-sm text-rosewood-soft">
      {error}
    </p>
  )
}

export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-lg border border-ember/40 bg-ember/10 px-3 py-2 text-sm text-ember-soft">
      {children}
    </p>
  )
}

export function Chip({ children, tone = 'neutral' }: {
  children: ReactNode
  tone?: 'neutral' | 'ok' | 'warn'
}) {
  const toneClass = {
    neutral: 'border-cocoa-600 text-cocoa-300',
    ok: 'border-jade/50 text-jade-soft',
    warn: 'border-ember/50 text-ember-soft',
  }[tone]
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs ${toneClass}`}>
      {children}
    </span>
  )
}
