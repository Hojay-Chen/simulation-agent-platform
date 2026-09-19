import { useId, type ReactNode } from 'react'
import { CircleAlert, CircleHelp } from 'lucide-react'

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

export function Button({ children, onClick, type = 'button', variant = 'primary', disabled, title, className = '' }: {
  children: ReactNode
  onClick?: () => void
  type?: 'button' | 'submit'
  variant?: 'primary' | 'ghost' | 'danger'
  disabled?: boolean
  title?: string
  /** 额外类名。目前只有登录页在用(`w-full`) —— 其余地方的按钮都是内容宽 */
  className?: string
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
      className={`${styles} !px-3 !py-1.5 disabled:opacity-40 disabled:cursor-not-allowed ${className}`}
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

/**
 * 圆圈里的 ? / ! —— 说明挂在符号上, 鼠标移上去(或键盘 Tab 到、手指点一下)才出现。
 *
 * <h2>为什么要有它</h2>
 *
 * 原来这些说明是**直接印在页面上**的: 面板标题下面一行灰字、侧栏底部一整段话。
 * 写的时候是对的 —— 那些话确实有用。但每一块都这么写之后, 读者要读的就不再是数据,
 * 而是一份说明书; 而说明书**读第二遍就没有价值了**, 数据每看一次都是新的。于是
 * 观感变成"堆了一大堆介绍文本", 真正要看的被淹掉。
 *
 * 所以说明**不删, 只是从常驻改成按需**: 第一次来的人照样找得到, 来过的人面前只剩数据。
 *
 * <h2>为什么用 opacity 而不是 hidden</h2>
 *
 * `display:none` 和 `visibility:hidden` 会把元素从无障碍树上摘掉, 于是
 * `aria-describedby` 指向的内容对读屏用户等于不存在 —— 而读屏用户**恰恰**是最需要
 * 这段说明的人(他们看不到图标, 只看到一片空白)。`opacity-0` 仍然留在无障碍树上,
 * 遮挡由 `pointer-events-none` 解决。
 *
 * 键盘可达性也不是白来的: 里面那个是真正的 `<button>`, 所以 Tab 能到、能聚焦,
 * `group-focus-within` 负责把气泡显示出来。手指点一下 = 聚焦, 于是触屏同样能用。
 */
export function InfoTip({ children, label = '说明', tone = 'info', align = 'start', side = 'top' }: {
  children: ReactNode
  /** 读屏念的按钮名。只念提示内容会让人不知道这句话是从哪儿冒出来的 */
  label?: string
  /** info = 圆圈问号(这是什么); warn = 圆圈叹号(注意什么) */
  tone?: 'info' | 'warn'
  /** 气泡贴哪一边。贴着页面左缘的图标要用 start, 否则气泡会伸出屏幕 */
  align?: 'start' | 'center' | 'end'
  /** 气泡在符号上方还是下方。页头那排符号必须用 bottom, 否则顶出视口 */
  side?: 'top' | 'bottom'
}) {
  const id = useId()
  const Icon = tone === 'warn' ? CircleAlert : CircleHelp
  const place = {
    start: 'left-0',
    center: 'left-1/2 -translate-x-1/2',
    end: 'right-0',
  }[align]
  const drop = side === 'top' ? 'bottom-full mb-2' : 'top-full mt-2'

  return (
    <span className="group relative inline-flex align-middle">
      <button
        type="button"
        aria-label={label}
        aria-describedby={id}
        className={`grid h-[15px] w-[15px] shrink-0 place-items-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
          tone === 'warn' ? 'text-warn' : 'text-ink-faint hover:text-ink-soft'
        }`}
      >
        <Icon size={13} strokeWidth={1.75} />
      </button>
      <span
        id={id}
        role="tooltip"
        className={`pointer-events-none absolute z-30 w-64 rounded-lg border border-line bg-raised px-3 py-2 text-left text-xs font-normal leading-relaxed text-ink-soft opacity-0 shadow-pop transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100 ${place} ${drop}`}
      >
        {children}
      </span>
    </span>
  )
}
