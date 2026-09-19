import { InfoTip } from '@/components/ui'
import { GROUP_META, describeReading, type VitalReading } from '@/lib/body'
import { AWARENESS_META, MESSAGE_LADDER, awarenessOf } from '@/lib/events'

/**
 * 一个 0–1 的量怎么画。
 *
 * <h2>为什么是横条而不是圆环/半圆表盘</h2>
 *
 * 十九个量要在一屏里比较。圆环的比较靠**面积**和**弧长**, 人对这两样的判断比对
 * 长度的判断差得多 —— 两个圆环谁大一眼看不出来, 两根横条谁长一眼就看得出来。
 * 而且圆环排成网格之后, 中心那个数字的位置会随着值变化, 扫读的时候眼睛找不到锚点。
 *
 * <h2>为什么刻度上要画一条"中位线"</h2>
 *
 * 因为这些量的默认值各不相同, 而"0.6 算高吗"这个问题没有绝对答案。在 0.5 处画一条
 * 淡线, 至少让"高于/低于中间"这件事不需要读数字就能看出来 —— 这是这一页上唯一一个
 * 不需要解释的参照系。
 *
 * <h2>颜色只表达方向, 不表达程度</h2>
 *
 * 不利于她的量用 danger 色, 有利的用 ok 色, 中间地带**一律用 ink**。不用"越红越糟"
 * 的渐变色: 那样 0.55 和 0.95 是两个不同的红, 而没有人知道分界在哪 —— 会让人以为
 * 存在一条精确的线, 而实际上那条线是前端编的。
 */
/**
 * 一个 0–1 的量。身体、关系维度、注意力权重都用它 —— 同一个量在同一个个产品里
 * 必须是同一种画法, 否则"0.8 是多是少"这个判断会在两页之间失效。
 */
export function Meter({
  label,
  value,
  goodHigh,
  hint,
  note,
  unregistered = false,
}: {
  label: string
  /** 0–1, 已经夹紧。 */
  value: number
  /** 高是好还是坏。决定标不标色, 以及标哪个色。 */
  goodHigh: boolean
  hint?: string
  /** 写在数值旁边的短注(例如"未登记")。 */
  note?: string
  unregistered?: boolean
}) {
  // 方向: 偏离到"值得标色"的程度才标。0.45–0.6 一律中性 —— 见文件头。
  const favorable = goodHigh ? value >= 0.6 : value <= 0.4
  const unfavorable = goodHigh ? value <= 0.35 : value >= 0.65
  const barColor = unfavorable ? 'bg-danger' : favorable ? 'bg-ok' : 'bg-ink-faint'
  const textColor = unfavorable ? 'text-danger' : favorable ? 'text-ok' : 'text-ink-soft'

  return (
    <div className="space-y-1" title={hint}>
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className="flex min-w-0 items-baseline gap-1.5">
          <span className="truncate text-ink-soft">{label}</span>
          {note && (
            <span className="shrink-0 rounded border border-warn/40 px-1 text-[10px] text-warn">
              {note}
            </span>
          )}
        </span>
        <span className={`shrink-0 font-mono text-[11px] tnum ${textColor}`}>
          {value.toFixed(2)}
        </span>
      </div>

      {/* role="meter" 而不是 progressbar: 这个量可以上下, 而且有"好/坏"方向。 */}
      <div
        role="meter"
        aria-label={`${label}: ${value.toFixed(2)}${unregistered ? '(前端没登记这一项)' : ''}`}
        aria-valuenow={Number(value.toFixed(2))}
        aria-valuemin={0}
        aria-valuemax={1}
        className="relative h-2 overflow-hidden rounded-full bg-sunken"
      >
        <div className={`h-full rounded-full ${barColor}`} style={{ width: `${value * 100}%` }} />
        {/* 中位线。压在最上层, 所以它在任何值的条上都看得见。 */}
        <span className="absolute inset-y-0 left-1/2 w-px bg-line-strong" aria-hidden="true" />
      </div>
    </div>
  )
}

export function Gauge({ reading }: { reading: VitalReading }) {
  const { vital, value, unregistered } = reading
  return (
    <Meter
      label={vital.label}
      value={value}
      goodHigh={vital.goodHigh}
      unregistered={unregistered}
      note={unregistered ? '未登记' : undefined}
      hint={unregistered
        ? `后端返回了 ${vital.key}, 但前端没有登记它 —— 不知道它是什么, 也不知道哪边是好。`
        : `${vital.hint}${vital.hint ? ' ' : ''}现在: ${describeReading(reading)}`}
    />
  )
}

/** 一组量。组标题带一个问号 —— 这一组回答什么问题挂在上面, 不占版面。 */
export function GaugeGroup({
  group,
  items,
}: {
  group: keyof typeof GROUP_META
  items: readonly VitalReading[]
}) {
  if (items.length === 0) return null
  return (
    <div className="space-y-3">
      <div className="flex min-w-0 items-center gap-1.5">
        <h3 className="text-xs font-medium text-ink">{GROUP_META[group].label}</h3>
        <InfoTip label={`「${GROUP_META[group].label}」这一组是什么`}>
          {GROUP_META[group].hint}
        </InfoTip>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((r) => <Gauge key={r.vital.key} reading={r} />)}
      </div>
    </div>
  )
}

/**
 * 意识的四级阶梯。
 *
 * <h2>为什么是阶梯不是进度条</h2>
 *
 * 因为"没感知到"**不是**一个进度为 0 的中间状态, 它是一个终态。一件事情可以永远
 * 停在第 1 级 —— 她不是"还没来得及知道", 她是真的不知道。进度条会暗示"还在走",
 * 而阶梯的每一级都可以是终点。
 *
 * <h2>为什么服务端那串阈值原文要一起显示</h2>
 *
 * 因为档位是前端按硬编码的阈值算的(见 `lib/events.ts`)。如果哪天服务端把 0.8 调成
 * 0.7, 前端画的档位会**悄悄**和调度器不一致 —— 而那种不一致没有任何报错。把原文
 * 摆出来, 至少让不一致是**看得见**的。
 */
export function AwarenessLadder({
  score,
  thresholds,
  level,
}: {
  score?: number
  /** 服务端给的阈值原文。不传就不显示那一行。 */
  thresholds?: string
  /** 服务端**自己**判定的级别。与本地算的不一致时两个都显示。 */
  level?: string
}) {
  const local = score !== undefined ? awarenessOf(score) : null
  const disagree = local !== null && level !== undefined && local !== level

  return (
    <div className="space-y-2">
      <ol className="space-y-1.5">
        {(['NONE', 'SUBCONSCIOUS', 'AWARE', 'FOCUSED'] as const).map((lv) => {
          const on = (level ?? local) === lv
          return (
            <li
              key={lv}
              aria-current={on ? 'step' : undefined}
              className={`flex items-baseline gap-2.5 rounded-lg border px-3 py-1.5 text-xs
                          ${on ? 'border-accent/50 bg-accent-soft text-ink' : 'border-line text-ink-faint'}`}
            >
              <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${on ? 'bg-accent' : 'bg-line-strong'}`} />
              <span className="shrink-0 font-medium">{AWARENESS_META[lv].label}</span>
              <span className="min-w-0 leading-relaxed">{AWARENESS_META[lv].hint}</span>
            </li>
          )
        })}
      </ol>

      {score !== undefined && (
        <p className="font-mono text-[11px] text-ink-faint tnum">
          感知分 {score.toFixed(2)}
          {level && <> · 服务端判定 {level}</>}
        </p>
      )}
      {thresholds && (
        <p className="text-[11px] leading-relaxed text-ink-faint">
          服务端阈值: <code className="font-mono">{thresholds}</code>
        </p>
      )}
      {disagree && (
        <p className="rounded-lg border border-warn/40 bg-warn/10 px-3 py-2 text-[11px] leading-relaxed text-warn">
          前端按本地阈值算出的是「{AWARENESS_META[local].label}」, 服务端给的却是「{level}」。
          两边不一致 —— 以服务端为准, 并去核对 `lib/events.ts` 里那份阈值。
        </p>
      )}
    </div>
  )
}

/**
 * 一条消息到她手里的五级台阶。
 *
 * <h2>它为什么不能画成进度条</h2>
 *
 * 因为**第 4 级可以不发生**。已读不回是一个决定, 不是故障 —— 一个人可以永远停在
 * "注意到了"那一级。画成进度条会让人以为 100% 才是正常, 于是把"她没回"读成 bug。
 * 所以这里画成一条可以停在任意一级的阶梯, 并把每一级"可能到此为止"这件事说出来。
 *
 * <h2>每一级上那句"她知道什么"才是这个组件的全部价值</h2>
 *
 * 「手机响」那一级的注解说得很明确: 此时**她不知道是谁、说了什么**。这句话是整个
 * 产品里最容易被实现悄悄破坏的一条规则(只要有人在通知里塞一个发信人名字就够了),
 * 所以它必须写在用户每天都会看见的地方, 而不是只写在设计文档里。
 */
export function MessageLadder({ reached }: { reached?: readonly string[] }) {
  const has = new Set(reached ?? [])
  return (
    <ol className="space-y-1">
      {MESSAGE_LADDER.map((step, i) => {
        const on = has.has(step.type)
        return (
          <li key={step.type} className="flex gap-3">
            <span className="flex flex-col items-center">
              <span
                className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-[10px] tnum
                            ${on ? 'border-accent bg-accent text-accent-ink' : 'border-line text-ink-faint'}`}
              >
                {i + 1}
              </span>
              {i < MESSAGE_LADDER.length - 1 && (
                <span className={`w-px flex-1 ${on ? 'bg-accent/40' : 'bg-line'}`} />
              )}
            </span>
            <span className="min-w-0 pb-2">
              <span className={`block text-xs font-medium ${on ? 'text-ink' : 'text-ink-soft'}`}>
                {step.label}
              </span>
              <span className="mt-0.5 block text-[11px] leading-relaxed text-ink-faint">
                {step.hint}
              </span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}
