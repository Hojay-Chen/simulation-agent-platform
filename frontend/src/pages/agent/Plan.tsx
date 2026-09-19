import { useMemo, useState } from 'react'
import { AlertTriangle, RefreshCw, Scissors } from 'lucide-react'
import {
  ApiError,
  getLife,
  interruptPlan,
  listWakeups,
  type InterruptResult,
  type PlanActivityView,
} from '@/api/client'
import {
  diffPlan,
  diffSummary,
  fmtClock,
  fmtDuration,
  snapshot,
  toneOfStatus,
  TONE_META,
  type PlanSnapshotItem,
} from '@/lib/plan'
import { useAsync, useNow } from '@/lib/useAsync'
import { Button, Empty, ErrorNote, Field, InfoTip, Panel, inputClass } from '@/components/ui'
import { describeError } from '@/components/Section'
import { GapNote, PlanDiffTable } from '@/components/viz/panels'
import { typeZh } from './Today'

/**
 * 「计划表」—— 她的打算, 以及它被改动的那一刻。
 *
 * <h2>这一页存在的理由: 打断不是暂停</h2>
 *
 * 这是整个产品里最容易做错、也最容易被做"看起来对"的一处。直觉的做法是:
 * 记下还剩多久 → 把新事件插到最前面 → 原事件按剩余时间接着走。设计文档 §3.5.6
 * **明确否掉了它**, 理由有两条, 两条都是硬的:
 *
 * 1. **两个真相源**。"还剩 40 分钟"和"计划表上说 12:00–13:00"会开始各说各的,
 *    而重放一遍历史时无法判断哪一个是当初的意图。
 * 2. **它假设了作业一定会继续**。真实的人被打断之后可能就把作业从今天的表上划掉了,
 *    改成去锻炼 —— 而"剩余 40 分钟"这个数字里根本没有"取消"这个选项。
 *
 * 所以打断在这里是一个**重排动作**: 她拿到新情况, 重新想一遍, 产出一版新的计划表。
 * 这一页要把这件事**画出来** —— 改之前一份、改之后一份、逐条对照。用户看到"穿衣服
 * 挪到了第一项、作业的开始时间变成了穿完衣服的时间", 才算真的理解了这个机制。
 *
 * <h2>为什么"改之前"是前端自己抓的</h2>
 *
 * 因为 Revision 链没有 HTTP 面(见底部缺口)。所以这一页的做法是: 在发出打断请求
 * **之前**先把当前这份快照存下来, 请求回来重取之后再和新的比。这样得到的差异
 * **确实**是这一次动作造成的, 而不是猜的。
 *
 * 它不如读服务端的 `revisionHistory`(那个能回溯到任意一版, 而且能看见
 * `previousRevisionId` 那条链)。但它是诚实的: 它没有假装自己知道更多。
 */
export function Plan({ agentId }: { agentId: string }) {
  const now = useNow()
  const life = useAsync(() => getLife(agentId), [agentId])
  const wakeups = useAsync(() => listWakeups(agentId), [agentId])

  const activities = useMemo(() => life.data?.todayActivities ?? [], [life.data])
  const after = useMemo(() => snapshot(activities), [activities])

  /** 打断**之前**那一份。null = 这次会话里还没打断过。 */
  const [before, setBefore] = useState<PlanSnapshotItem[] | null>(null)
  const [target, setTarget] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [result, setResult] = useState<InterruptResult | null>(null)

  // 换了一个 agent 就把上一份对照清掉 —— 否则会把"她在 A 上的改动"显示成 B 的。
  const [boundTo, setBoundTo] = useState(agentId)
  if (boundTo !== agentId) {
    setBoundTo(agentId)
    setBefore(null)
    setResult(null)
    setTarget(null)
    setErr(null)
  }

  const rows = useMemo(() => (before ? diffPlan(before, after) : null), [before, after])
  const summary = useMemo(() => (rows ? diffSummary(rows) : null), [rows])

  const sorted = useMemo(() => sortByStart(activities), [activities])
  const current = useMemo(() => activities.find((a) => isNow(a, now)) ?? null, [activities, now])

  async function runInterrupt(title: string) {
    setBusy(true)
    setErr(null)
    setResult(null)
    // 先抓"改之前", 再发请求。顺序反过来会漏掉服务端已经改完的那一刻 ——
    // 而那一漏就是整页对照失效, 且表现为"她什么都没改"。
    setBefore(snapshot(activities))
    try {
      const r = await interruptPlan(agentId, title, reason.trim() || undefined)
      setResult(r)
      life.reload()
      wakeups.reload()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e))
      // 请求失败就把那份"改之前"丢掉 —— 留着它会和没变过的现况比出一张全 "没动" 的表,
      // 而那看起来像"打断成功了但什么都没发生"。
      setBefore(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5">
      {/* ── 此刻 ─────────────────────────────────────────────── */}
      <Panel
        title={
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-sm font-medium text-ink">她今天的计划表</h2>
            <InfoTip label="表里这几列是什么意思">
              <b>可打断</b> —— 这件事有多容易被插进来的事推迟。数值越低, 越可能被推迟到之后,
              而不是立刻处理。<br />
              <b>进度</b> —— 她做到哪了。<br />
              <b>状态</b> —— 她此刻与这件事的关系(正在做 / 做完了 / 还没到点)。
            </InfoTip>
          </div>
        }
        action={<Button variant="ghost" onClick={() => { life.reload(); wakeups.reload() }}>
          <RefreshCw size={13} />刷新
        </Button>}
      >
        <ErrorNote error={life.error ? describeError(life.error) : null} />
        {life.loading && !life.data && <Empty>读取中…</Empty>}

        {current && (
          <p className="mb-4 flex flex-wrap items-center gap-x-1.5 text-xs leading-relaxed text-ink-soft">
            <span>
              按计划, 她此刻应该在
              <b className="mx-1 text-ink">{current.title}</b>
              ({fmtClock(current.plannedStart ?? null)}–{fmtClock(current.plannedEnd ?? null)})。
            </span>
            {current.interruptibility && (
              <>
                <span>
                  这件事的可打断程度是 <b className="font-mono">{current.interruptibility}</b>。
                </span>
                <InfoTip label="可打断程度是什么意思">
                  数值越低, 插进去的事越可能被推迟到之后, 而不是立刻处理。
                </InfoTip>
              </>
            )}
          </p>
        )}

        {sorted.length === 0 && !life.loading && <Empty>今天没有排任何事 —— 没有可以被改动的计划表。</Empty>}

        {sorted.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <caption className="sr-only">她今天的计划表</caption>
              <thead>
                <tr className="border-b border-line text-left text-ink-faint">
                  <th scope="col" className="py-2 pr-3 font-normal">时间</th>
                  <th scope="col" className="py-2 pr-3 font-normal">事项</th>
                  <th scope="col" className="py-2 pr-3 font-normal">状态</th>
                  <th scope="col" className="py-2 pr-3 font-normal">可打断</th>
                  <th scope="col" className="py-2 pr-3 font-normal">进度</th>
                  <th scope="col" className="py-2 font-normal">
                    <span className="sr-only">操作</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((a) => {
                  const tone = toneOfStatus(a.status)
                  const active = isNow(a, now)
                  return (
                    <tr
                      key={a.title}
                      className={`border-b border-line last:border-0 ${active ? 'bg-accent-soft/40' : ''}`}
                    >
                      <td className="py-2 pr-3 whitespace-nowrap font-mono text-ink-faint tnum">
                        {fmtClock(a.plannedStart ?? null)}–{fmtClock(a.plannedEnd ?? null)}
                        <span className="ml-1 text-[10px]">{durationOf(a)}</span>
                      </td>
                      <td className="py-2 pr-3">
                        <span className="text-ink">{a.title}</span>
                        {a.type && <span className="ml-1.5 text-[10px] text-ink-faint">{typeZh(a.type)}</span>}
                        {a.interrupted && (
                          <span className="ml-1.5 rounded border border-warn/40 px-1 text-[10px] text-warn">
                            被打断过
                          </span>
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        <span className={`chip border ${TONE_META[tone].bar} ${TONE_META[tone].text}`}>
                          {TONE_META[tone].label}
                        </span>
                      </td>
                      <td className="py-2 pr-3 font-mono text-ink-faint">{a.interruptibility ?? '—'}</td>
                      <td className="py-2 pr-3 font-mono text-ink-faint tnum">
                        {typeof a.progress === 'number' ? `${Math.round(a.progress * 100)}%` : '—'}
                      </td>
                      <td className="py-2 text-right">
                        <Button
                          variant="ghost"
                          disabled={busy}
                          title="把她从这件事上叫走, 看她怎么重排"
                          onClick={() => { setTarget(a.title); void runInterrupt(a.title) }}
                        >
                          <Scissors size={12} />打断
                        </Button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {/* ── 打断 ─────────────────────────────────────────────── */}
      <Panel
        title={
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-sm font-medium text-ink">叫走她, 看她怎么重排</h2>
            <InfoTip tone="warn" label="打断她之后会发生什么">
              打断<b>不是</b>"记下剩余时长 → 插入新事件 → 按剩余时长接着走"。那样会留下两个真相源,
              而且它假设了她一定会回来继续做 —— 但真实的人被打断之后, 可能直接把这件事从今天的表上划掉。
              <br /><br />
              这里做的是: 把新情况告诉她, 让她<b>重新想一遍</b>, 产出一版新的计划表。改了什么会在下面逐条列出来。
            </InfoTip>
          </div>
        }
      >
        <div className="space-y-4">
          <Field
            label="为什么打扰她"
            hint={
              <>
                会写进她的因果链 —— 她之后能解释这件事。留空会用默认那句「临时有事, 计划先放一放」。
              </>
            }
          >
            <input
              className={inputClass}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例: 快递到了, 得下去拿一下"
              disabled={busy}
            />
          </Field>

          {sorted.length > 0 ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-ink-faint">把她从哪件事上叫走:</span>
              {sorted.map((a) => (
                <Button
                  key={a.title}
                  variant="ghost"
                  disabled={busy}
                  onClick={() => { setTarget(a.title); void runInterrupt(a.title) }}
                >
                  <Scissors size={12} />{a.title}
                </Button>
              ))}
            </div>
          ) : (
            <p className="text-xs text-ink-faint">她的计划表是空的 —— 没有可以被打断的事。</p>
          )}

          {busy && <p className="text-xs text-ink-faint">正在打断「{target}」…</p>}
          <ErrorNote error={err} />

          {result && <InterruptOutcome result={result} />}
        </div>
      </Panel>

      {/* ── 前后对照 ─────────────────────────────────────────── */}
      {rows && summary && (
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">这次打断改了什么</h2>
              <InfoTip label="这份对照是怎么来的">
                两端都是<b>本页自己抓的</b>: 打断请求发出前后各取一次
                <span className="font-mono"> GET /life</span>。所以它只覆盖这一次动作,
                而且它没有假装自己知道更多 —— 想回溯到任意一版计划, 需要服务端的版本链(见这一页底下那块说明)。
              </InfoTip>
            </div>
          }
          action={<Button variant="ghost" onClick={() => { setBefore(null); setResult(null) }}>清掉对照</Button>}
        >
          <p className="mb-3 text-pretty text-sm text-ink">{summary.headline}</p>
          <PlanDiffTable rows={rows} />
        </Panel>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        {/* ── 闹钟 ───────────────────────────────────────────── */}
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">她定下的闹钟</h2>
              <InfoTip label="闹钟是怎么来的">
                闹钟只会被计划表里那些"到了点要触发"的事, 以及她自己排下的意图与未了的事填满 ——
                所以她今天一件都没有也<b>很正常</b>。
              </InfoTip>
            </div>
          }
        >
          <ErrorNote error={wakeups.error ? describeError(wakeups.error) : null} />
          {wakeups.loading && !wakeups.data && <Empty>读取中…</Empty>}
          {wakeups.data && wakeups.data.length === 0 && (
            <Empty>她此刻没在等任何时刻。</Empty>
          )}
          {wakeups.data && wakeups.data.length > 0 && (
            <ul className="divide-y divide-line">
              {wakeups.data.map((w, i) => (
                <li key={`${w.source}-${w.wakeAt}-${i}`} className="flex items-baseline gap-3 py-2 text-xs">
                  <span className="w-12 shrink-0 font-mono text-ink tnum">{fmtClock(w.wakeAt ?? null)}</span>
                  <span className="min-w-0 flex-1 truncate text-ink-soft">
                    {w.reason || w.eventType || '(没有理由)'}
                  </span>
                  {w.source && <span className="shrink-0 font-mono text-[11px] text-ink-faint">{w.source}</span>}
                </li>
              ))}
            </ul>
          )}
        </Panel>

        {/* ── 缺口 ───────────────────────────────────────────── */}
        <div className="flex items-start gap-2 rounded-xl border border-line bg-raised px-4 py-3">
          <InfoTip tone="warn" label="这一页读不到的两样东西">
            <div className="space-y-3">
              <GapNote title="版本链没有读取面">
                <p>
                  §3.5.5 的 <span className="font-mono">PlanBoard.revisionHistory</span> 是这次重做的核心承诺 ——
                  <b>每一版都不删除</b>, 所以可以回溯、可以重放、可以拿去做行为分析。
                  但它在 8091 上没有任何 HTTP 面。
                </p>
                <p>
                  缺的端点:
                  <code className="ml-1">GET /api/companions/{'{id}'}/plan/revisions</code>
                  与 <code>GET /api/companions/{'{id}'}/plan/revisions/{'{n}'}</code>。
                </p>
                <p>
                  它们落地之前, 这一页只能对照"刚才那一次", 看不到 <span className="font-mono">previousRevisionId</span>
                  串起来的那条链, 也看不到每次操作的 <span className="font-mono">PlanMutation</span> 类型。
                </p>
              </GapNote>
              <GapNote title="打断的入参太薄">
                <p>
                  现在只有事项名(还是模糊匹配)和原因。真正需要的是"<b>把某件事插到最前面</b>"和
                  "<b>把某件事的开始时间设成另一件事的结束时间</b>" —— 这两种意图是目前这个接口表达不了的。
                </p>
                <p>
                  前端因此做了两件自保的事: 永远显式传事项名(空串会打断列表里第一条),
                  并且永远在发出请求<b>之前</b>抓一份快照。
                </p>
              </GapNote>
            </div>
          </InfoTip>
          <p className="text-xs leading-relaxed text-ink-soft">
            这一页只能对照"刚才那一次" —— 计划的历史版本还读不到。
          </p>
        </div>
      </div>
    </div>
  )
}

/** 打断的结果 —— 她自己的说法, 原样呈现。 */
function InterruptOutcome({ result }: { result: InterruptResult }) {
  if (!result.interrupted) {
    return (
      <div className="flex items-start gap-2 rounded-lg border border-warn/40 bg-warn/10 px-3 py-2">
        <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warn" />
        <p className="text-xs leading-relaxed text-warn">
          没打断成: {result.reason ?? '没有匹配的进行中计划。'}
          <span className="mt-1 flex items-start gap-1.5 text-ink-soft">
            事项名是模糊匹配, 而且只匹配<b>进行中</b>的计划 —— 已经结束或还没开始的都不在候选里。
          </span>
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-2 rounded-lg border border-cat-schedule/40 bg-cat-schedule/10 px-4 py-3">
      <p className="flex items-center gap-1.5 text-xs font-medium text-cat-schedule">
        打断了「{result.title}」—— 下面是<b>她自己</b>对这件事的说法
        <InfoTip label="这段话是哪来的">
          它不是界面上拼的 —— 来自 <span className="font-mono">POST /api/admin/plan/interrupt</span>
          返回体里的 <span className="font-mono">explain</span>, 可以沿着她的因果链追到具体的决定。
          下面那份对照表是它的<b>证据</b>。
        </InfoTip>
      </p>
      {result.explain
        ? <p className="text-pretty whitespace-pre-wrap text-sm leading-relaxed text-ink">{result.explain}</p>
        : <p className="text-xs text-ink-faint">服务端没有给出解释文本。</p>}
    </div>
  )
}

// ── 纯函数 ──────────────────────────────────────────────────────────────────

function durationOf(a: PlanActivityView): string {
  const s = a.plannedStart ? Date.parse(a.plannedStart) : Number.NaN
  const e = a.plannedEnd ? Date.parse(a.plannedEnd) : Number.NaN
  return Number.isFinite(s) && Number.isFinite(e) ? fmtDuration(s, e) : ''
}

/** 按开始时间排。没有时间的排在最后 —— 它们放哪儿都不对, 那就不放前面。 */
export function sortByStart(list: readonly PlanActivityView[]): PlanActivityView[] {
  return [...list].sort((a, b) => {
    const sa = a.plannedStart ? Date.parse(a.plannedStart) : Number.POSITIVE_INFINITY
    const sb = b.plannedStart ? Date.parse(b.plannedStart) : Number.POSITIVE_INFINITY
    return (Number.isFinite(sa) ? sa : Number.POSITIVE_INFINITY)
      - (Number.isFinite(sb) ? sb : Number.POSITIVE_INFINITY)
  })
}

/** 这一刻她是不是正在做这件事。端点相接不算 —— 与 `overlaps()` 同一条规则。 */
export function isNow(a: PlanActivityView, now: number): boolean {
  const s = a.plannedStart ? Date.parse(a.plannedStart) : Number.NaN
  const e = a.plannedEnd ? Date.parse(a.plannedEnd) : Number.NaN
  if (!Number.isFinite(s) || !Number.isFinite(e)) return false
  return now >= s && now < e
}
