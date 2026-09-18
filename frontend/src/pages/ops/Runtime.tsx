import { useMemo, useState } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import {
  getMetrics,
  getRegisteredAgents,
  getV11,
  listPendingMessages,
  listScheduled,
  listTraces,
  type V11ShadowView,
} from '@/api/client'
import { fmtClock } from '@/lib/plan'
import { useAsync } from '@/lib/useAsync'
import { AgentPickerBar, useAgentChoice } from '@/components/AgentPicker'
import { RequireStudio } from '@/components/StudioLogin'
import { RecordView } from '@/components/RecordView'
import { Button, Chip, Empty, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { GapNote } from '@/components/viz/panels'

/**
 * 「运行时」—— 运维面。这一页回答的是"它现在跑成什么样", 不是"她过得好不好"。
 *
 * <h2>这一页允许丑</h2>
 *
 * 「档案」页里那些原始 JSON 是**收敛**过的, 而这一页是把几个内省端点直接摊开:
 * 认知指标、轨迹、排程、注册的处理器、V11 的影子对比。它们的字段名是给排查用的,
 * 不是给人读的 —— 把它们"美化"成中文标题之后, 用户在日志里看到的名字就和屏幕上
 * 对不上号了。所以这一页用同一个 `RecordView`, 只在**块与块之间**加解释。
 *
 * <h2>唯一一处能看到消息正文的地方</h2>
 *
 * 是下面的「她决定待会儿再看」。这是产品里唯一一个正文出现在"她还没回"的上下文里的
 * 出口, 所以它需要一个明写的理由, 而理由必须写在页面上而不是只写在代码注释里:
 * **那条消息她本来就看过** —— `PendingMessageService` 存的就是她自己决定推迟的那一条。
 * 所以它不是泄漏。
 *
 * 而这也是它只能在这一页的原因: 看她那一侧的每一页都不许渲染这个字段。那条路一旦
 * 打开, "正文只在她主动去看的时候才进入她"这句话就不成立了 —— 而那是整个产品唯一
 * 一条不能破的规则。这一页归运维组, 就是这条边界的**实现方式**。
 *
 * <h2>V11 影子那一块为什么要单独说一句"读到 0 不等于没分歧"</h2>
 *
 * 因为那个端点在没启用影子对比时会返回一组全 0 的数, 并塞一句 `note`。全 0 在界面上
 * 看起来就是"完全一致" —— 一个把"没开"读成"没问题"的运维页, 比没有这个页面更糟。
 * 所以 `shadowVerdict()` 把 `enabled=false` 判成**未知**, 而不是"一致"。
 */
export function Runtime() {
  return (
    <RequireStudio why="运行时数据(认知指标 / 轨迹 / 排程 / 影子对比)只在 server:8091 上 —— 需要先用你的账号登录。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { agents, agentId, setAgentId, loading: agentsLoading, error: agentsError, reload: reloadAgents } =
    useAgentChoice()

  const v11 = useAsync(() => (agentId ? getV11(agentId) : Promise.resolve(null)), [agentId])
  const metrics = useAsync(
    () => (agentId ? getMetrics(agentId) : Promise.resolve<Record<string, unknown>>({})),
    [agentId],
  )
  const traces = useAsync(
    () => (agentId ? listTraces(agentId) : Promise.resolve<Record<string, unknown>[]>([])),
    [agentId],
  )
  const scheduled = useAsync(
    () => (agentId ? listScheduled(agentId) : Promise.resolve([])),
    [agentId],
  )
  const registered = useAsync(
    () => (agentId ? getRegisteredAgents(agentId) : Promise.resolve({ registered: [] })),
    [agentId],
  )

  function reloadAll() {
    v11.reload(); metrics.reload(); traces.reload(); scheduled.reload(); registered.reload()
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-base font-medium text-ink">运行时</h1>
          <p className="mt-1 max-w-2xl text-xs leading-relaxed text-ink-faint">
            认知链的计数、轨迹、排程与降级。这一页是**运维面** —— 字段名照抄后端,
            不做美化, 以便和日志、接口文档、数据库对得上号。
          </p>
        </div>
        <span className="flex items-center gap-2">
          <Button variant="ghost" onClick={reloadAll}><RefreshCw size={13} />刷新全部</Button>
        </span>
      </div>

      <AgentPickerBar
        agents={agents}
        agentId={agentId}
        onChange={setAgentId}
        loading={agentsLoading}
        error={agentsError}
        onReload={reloadAgents}
        right={registered.data && (
          <span className="text-xs text-ink-faint">
            注册的处理器 <span className="font-mono text-ink tnum">{registered.data.registered.length}</span>
          </span>
        )}
      />

      {agentId && (
        <>
          <ShadowPanel state={v11} />

          <div className="grid gap-5 lg:grid-cols-2">
            <Panel
              title="认知指标"
              action={<Button variant="ghost" onClick={metrics.reload}><RefreshCw size={13} />刷新</Button>}
            >
              <p className="mb-4 text-xs leading-relaxed text-ink-faint">
                认知链自己的计数器(轮次、模型调用、命中率、耗时)。它是"这个 agent
                花了多少"的**唯一**来源 —— 别处没有第二个数可以拿来对。
              </p>
              {metrics.error && <p className="text-sm text-danger">{describeError(metrics.error)}</p>}
              {metrics.data && <RecordView value={metrics.data} empty="还没有指标 —— 它要跑过至少一轮认知。" />}
            </Panel>

            <PendingPanel agentId={agentId} />
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <TracesPanel agentId={agentId} state={traces} />

            <Panel
              title="按计划排下的动作"
              action={<Button variant="ghost" onClick={scheduled.reload}><RefreshCw size={13} />刷新</Button>}
            >
              {scheduled.error && <p className="text-sm text-danger">{describeError(scheduled.error)}</p>}
              {scheduled.data && scheduled.data.length === 0 && (
                <Empty>
                  没有待执行的排程。
                  <span className="mt-1 block text-[11px] leading-relaxed">
                    空列表在这里是**正常的**: 排程只会被计划表里"到了点要触发"的事填满。
                  </span>
                </Empty>
              )}
              {scheduled.data && scheduled.data.length > 0 && (
                <ul className="divide-y divide-line">
                  {scheduled.data.map((s, i) => (
                    <li key={`${s.type}-${s.executeAt}-${i}`} className="flex items-baseline gap-3 py-2 text-xs">
                      <span className="w-12 shrink-0 font-mono text-ink tnum">{fmtClock(s.executeAt ?? null)}</span>
                      <span className="min-w-0 flex-1 truncate text-ink-soft">{s.type ?? '(无类型)'}</span>
                      {typeof s.retry === 'number' && s.retry > 0 && (
                        <Chip tone="warn">重试 {s.retry}</Chip>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          <Panel title="认知链上真正在跑的处理器">
            <p className="mb-4 text-xs leading-relaxed text-ink-faint">
              这一组名字是**运行时注册**的, 不是编译期的一张清单 —— 所以它是"这个 agent
              现在装了哪些能力"的权威答案。少一个往往就意味着某条链路整个不工作,
              而它在别处的表现只是一条事件没有反应。
            </p>
            {registered.error && <p className="text-sm text-danger">{describeError(registered.error)}</p>}
            {registered.data && registered.data.registered.length === 0 && (
              <Empty>没有注册任何处理器 —— 这个 agent 的认知链是空的。</Empty>
            )}
            {registered.data && registered.data.registered.length > 0 && (
              <ul className="flex flex-wrap gap-1.5">
                {registered.data.registered.map((n) => (
                  <li
                    key={n}
                    className="rounded-md border border-line bg-sunken px-2 py-0.5 font-mono text-xs text-ink-soft"
                  >
                    {n}
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </>
      )}

      <Panel title="这一页读不到什么">
        <div className="grid gap-3 lg:grid-cols-2">
          <GapNote title="降级没有单独的读数">
            <p>
              V2.2 §8 的 LLM 降级(Mock 回退)目前只能从指标里**间接**看出来 ——
              调用数对不上、耗时异常短, 都是间接证据。
            </p>
            <p>
              缺的端点: <code>GET /api/companions/{'{id}'}/v9/degradation</code>,
              返回"最近 N 次调用里有多少次落到了 Mock、分别是什么原因"。
            </p>
          </GapNote>
          <GapNote title="进程内的账本读不到">
            <p>
              这一页显示的三块(排程 / 待处理消息 / 影子)都是从**持久化**的那一侧读的。
              而 `ContinuousEffectLedger` 与 `RealtimeEventQueue`(`boundary/event/*`
              那两个 fabric)是**进程内**的 —— 重启即重建, 没有任何读取面。
            </p>
            <p>
              所以"她此刻的刺激队列里排着什么"这个问题, 界面上答不了。这在排查
              "她怎么对这个通知没反应"时是最需要的那一条信息。
            </p>
          </GapNote>
        </div>
      </Panel>
    </div>
  )
}

// ── 影子对比 ────────────────────────────────────────────────────────────────

function ShadowPanel({ state }: { state: ReturnType<typeof useAsync<V11ShadowView | null>> }) {
  const d = state.data
  const verdict = useMemo(() => (d ? shadowVerdict(d) : null), [d])

  return (
    <Panel
      title="V11 送达主链的影子对比"
      action={<Button variant="ghost" onClick={state.reload}><RefreshCw size={13} />刷新</Button>}
    >
      <p className="mb-4 text-xs leading-relaxed text-ink-faint">
        新主链在没有正式切流之前, 会**同时**跑一遍旧的判定, 把两次结果的分歧记下来。
        这一块就是那份分歧账 —— 它是"能不能切"的唯一依据。
      </p>

      {state.error && <p className="text-sm text-danger">{describeError(state.error)}</p>}
      {state.loading && !d && <Empty>读取中…</Empty>}

      {d && verdict && (
        <div className="space-y-4">
          <div className={`flex items-start gap-2 rounded-lg border px-3 py-2 ${
            verdict.tone === 'unknown' ? 'border-warn/40 bg-warn/10' : 'border-line bg-sunken/40'
          }`}>
            {verdict.tone === 'unknown' && <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warn" />}
            <p className={`text-xs leading-relaxed ${verdict.tone === 'unknown' ? 'text-warn' : 'text-ink-soft'}`}>
              {verdict.text}
            </p>
          </div>

          {d.overall && (
            <section>
              <p className="mb-1.5 text-xs uppercase tracking-wider text-ink-faint">overall(全平台口径)</p>
              <RecordView value={d.overall} empty="空。" />
            </section>
          )}
          {d.thisCompanion && (
            <section>
              <p className="mb-1.5 text-xs uppercase tracking-wider text-ink-faint">thisCompanion(只算她)</p>
              <RecordView value={d.thisCompanion} empty="空。" />
            </section>
          )}
          {d.recent && d.recent.length > 0 && (
            <section>
              <p className="mb-1.5 text-xs uppercase tracking-wider text-ink-faint">recent({d.recent.length})</p>
              <RecordView value={d.recent} empty="空。" />
            </section>
          )}
          <details>
            <summary className="cursor-pointer text-xs text-ink-faint hover:text-ink-soft">
              其余字段原样展开
            </summary>
            <div className="mt-2">
              <RecordView value={d} empty="空。" />
            </div>
          </details>
        </div>
      )}
    </Panel>
  )
}

// ── 待处理消息 ──────────────────────────────────────────────────────────────

function PendingPanel({ agentId }: { agentId: string }) {
  const pending = useAsync(() => listPendingMessages(agentId), [agentId])
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set())

  return (
    <Panel
      title="她决定待会儿再看"
      action={<Button variant="ghost" onClick={pending.reload}><RefreshCw size={13} />刷新</Button>}
    >
      <div className="mb-4 flex items-start gap-2 rounded-lg border border-warn/40 bg-warn/10 px-3 py-2">
        <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warn" />
        <p className="text-[11px] leading-relaxed text-warn">
          这一块**含消息正文**, 而它是整个控制台里唯一一处。理由是具体的: 存进来的
          就是她自己已经看过、并决定推迟的那一条, 所以它不是泄漏。
          <span className="mt-1 block text-ink-faint">
            它只能出现在运维面。她那一侧的每一页都不许渲染这个字段 ——
            那条路一旦打开, "正文只在她主动去看的时候才进入她"就不成立了。
          </span>
        </p>
      </div>

      {pending.error && <p className="text-sm text-danger">{describeError(pending.error)}</p>}
      {pending.loading && !pending.data && <Empty>读取中…</Empty>}
      {pending.data && pending.data.length === 0 && (
        <Empty>
          没有推后的消息。
          <span className="mt-1 block text-[11px] leading-relaxed">
            注意"推后"和"没理会"是两件事: 前者在这张表里, 后者**不在这里** ——
            她压根没感知到的消息不会产生一条待办。
          </span>
        </Empty>
      )}

      {pending.data && pending.data.length > 0 && (
        <ul className="space-y-2">
          {pending.data.map((m, i) => {
            const key = m.messageId ?? `#${i}`
            const shown = open.has(key)
            return (
              <li key={key} className="rounded-lg border border-line bg-sunken/40 p-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="font-mono text-[11px] text-ink-faint">{m.messageId ?? '(没有 messageId)'}</span>
                  <span className="text-[11px] text-ink-faint">
                    下次再看 {fmtClock(m.nextReviewAt ?? null)}
                  </span>
                </div>
                {m.reason && (
                  <p className="mt-1 text-xs text-ink-soft">她的理由: {m.reason}</p>
                )}
                {m.content !== undefined && (
                  <div className="mt-2">
                    <button
                      type="button"
                      aria-expanded={shown}
                      onClick={() => setOpen((s) => {
                        const n = new Set(s)
                        if (n.has(key)) n.delete(key)
                        else n.add(key)
                        return n
                      })}
                      className="text-[11px] text-accent hover:underline"
                    >
                      {shown ? '收起正文' : '展开正文'}
                    </button>
                    {shown && (
                      <p className="mt-1.5 whitespace-pre-wrap rounded border border-line bg-raised px-3 py-2 text-xs leading-relaxed text-ink">
                        {m.content}
                      </p>
                    )}
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </Panel>
  )
}

// ── 轨迹 ────────────────────────────────────────────────────────────────────

function TracesPanel({
  agentId,
  state,
}: {
  agentId: string
  state: ReturnType<typeof useAsync<Record<string, unknown>[]>>
}) {
  const rows = state.data ?? []
  return (
    <Panel
      title={`认知轨迹 (${rows.length})`}
      action={<Button variant="ghost" onClick={state.reload}><RefreshCw size={13} />刷新</Button>}
    >
      <p className="mb-4 text-xs leading-relaxed text-ink-faint">
        一条轨迹 = 一次认知的完整过程。它是"她为什么这么反应"的**唯一**可复查记录 ——
        上面那些计数说"跑了几轮", 这里说"每一轮想了什么"。
      </p>
      {state.error && <p className="text-sm text-danger">{describeError(state.error)}</p>}
      {state.loading && state.data === null && <Empty>读取中…</Empty>}
      {state.data && rows.length === 0 && (
        <Empty>
          还没有轨迹。
          <span className="mt-1 block text-[11px] leading-relaxed">
            她收到第一条消息、或第一次被定时任务叫醒之后才会出现。
          </span>
        </Empty>
      )}
      {rows.length > 0 && (
        <ul className="max-h-[28rem] space-y-3 overflow-auto pr-1">
          {rows.map((t, i) => (
            <li key={String((t.traceId as string) ?? i)} className="rounded-lg border border-line bg-sunken/40 p-3">
              <RecordView value={t} />
            </li>
          ))}
        </ul>
      )}
      {rows.length > 0 && (
        <p className="mt-2 text-[11px] text-ink-faint">
          这一块是 agent <span className="font-mono">{agentId}</span> 的轨迹 —— 跨 agent
          的轨迹检索需要另一个端点(见本页缺口)。
        </p>
      )}
    </Panel>
  )
}

// ── 纯函数 ──────────────────────────────────────────────────────────────────

export interface ShadowVerdict {
  tone: 'unknown' | 'ok'
  text: string
}

/**
 * 影子对比怎么下结论。
 *
 * <h2>只有服务端**明确说在跑**的时候才说 ok</h2>
 *
 * 没启用时服务端返回一组全 0 的计数, 而全 0 在屏幕上看起来就是"两边完全一致"。
 * 把这个读成"可以切流了"是这块面板唯一一种会造成真实损失的读法 —— 它会把一个
 * "没测"变成一次上线决定。
 *
 * 所以这里的判据取的是**肯定式**: `enabled === true || shadow === true` 才算在跑。
 * 于是"字段缺席"(旧后端)也落到未知那一档, 而不是被当成"在跑且没有分歧"。
 * 一个多显示一次警告的界面是安全的; 一个把未知显示成 0 的界面不是。
 */
export function shadowVerdict(v: V11ShadowView): ShadowVerdict {
  const off = v.enabled === false || v.shadow === false
  const on = v.enabled === true || v.shadow === true

  if (off || !on) {
    return {
      tone: 'unknown',
      text: v.note?.trim()
        ? `影子对比**没有在跑**: ${v.note}`
        : '影子对比没有在跑(或服务端没说它在跑)—— 下面的数全是 0, 但那表示「没测」, 不表示「没有分歧」。',
    }
  }
  if (v.note?.trim()) return { tone: 'unknown', text: v.note }
  return {
    tone: 'ok',
    text: '影子对比在跑。下面的分歧计数是"新主链与旧判定不一致"的实际发生次数 —— 它是能不能切流的唯一依据。',
  }
}
