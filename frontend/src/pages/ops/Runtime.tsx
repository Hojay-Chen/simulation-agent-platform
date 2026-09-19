import { useMemo, useState } from 'react'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import {
  getMetrics,
  getRegisteredAgents,
  getV11,
  listPendingMessages,
  listWakeups,
  listTraces,
  type V11ShadowView,
} from '@/api/client'
import { fmtClock } from '@/lib/plan'
import { useAsync } from '@/lib/useAsync'
import { AgentPickerBar, useAgentChoice } from '@/components/AgentPicker'
import { RequireStudio } from '@/components/StudioLogin'
import { RecordView } from '@/components/RecordView'
import { Button, Chip, Empty, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { GapNote } from '@/components/viz/panels'

/**
 * 「运行状态」—— 运维面。这一页回答的是"它现在跑成什么样", 不是"她过得好不好"。
 *
 * <h2>这一页允许丑</h2>
 *
 * 「档案」页里那些原始 JSON 是**收敛**过的, 而这一页是把几个内省端点直接摊开:
 * 认知指标、轨迹、排程、注册的处理器、V11 的影子对比。它们的字段名是给排查用的,
 * 不是给人读的 —— 把它们"美化"成中文标题之后, 用户在日志里看到的名字就和屏幕上
 * 对不上号了。所以这一页用同一个 `RecordView`, 只在**块与块之间**加解释。
 *
 * <p>而"块与块之间的解释"原来是每一块面板标题下的一行灰字, 六块就是六行。现在它们
 * 各自挂在那一块标题的问号上 —— 排查的人手里是数据, 第一次来的人点得到说明书。
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
 * <p>那一行警示条**留在版面上**没有搬进问号: 它讲的是"你接下来会看到正文", 属于
 * 读之前必须知道的事。详细的理由挂在它自己的问号上。
 *
 * <h2>V11 影子那一块为什么要单独说一句"读到 0 不等于没分歧"</h2>
 *
 * 因为那个端点在没启用影子对比时会返回一组全 0 的数, 并塞一句 `note`。全 0 在界面上
 * 看起来就是"完全一致" —— 一个把"没开"读成"没问题"的运维页, 比没有这个页面更糟。
 * 所以 `shadowVerdict()` 把 `enabled=false` 判成**未知**, 而不是"一致"。
 */
export function Runtime() {
  return (
    <RequireStudio why="运行状态是认知链的内部数据(指标、轨迹、排程、降级对比), 属于个人数据。">
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
  const wakeups = useAsync(
    () => (agentId ? listWakeups(agentId) : Promise.resolve([])),
    [agentId],
  )
  const registered = useAsync(
    () => (agentId ? getRegisteredAgents(agentId) : Promise.resolve({ registered: [] })),
    [agentId],
  )

  function reloadAll() {
    v11.reload(); metrics.reload(); traces.reload(); wakeups.reload(); registered.reload()
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex min-w-0 items-center gap-1.5">
            <h1 className="text-base font-medium text-ink">运行状态</h1>
            <InfoTip label="「运行状态」这一页给谁看">
              认知链的计数、轨迹、排程与降级 —— 这一页是<b className="font-medium text-ink">运维面</b>。
              <br />
              <br />
              字段名照抄后端、不做美化: 你在日志、接口文档、数据库里看到的是同一批名字。
              "她过得好不好"不在这里, 在她那一侧的「数字人」里。
            </InfoTip>
          </div>
          <p className="mt-1 max-w-2xl text-pretty text-xs leading-relaxed text-ink-faint">
            认知链跑成什么样: 计数、轨迹、排程、降级。
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
            已注册的处理器 <span className="font-mono text-ink tnum">{registered.data.registered.length}</span>
          </span>
        )}
      />

      {agentId && (
        <>
          <ShadowPanel state={v11} />

          <div className="grid gap-5 lg:grid-cols-2">
            <Panel
              title={
                <div className="flex min-w-0 items-center gap-1.5">
                  <h2 className="text-sm font-medium tracking-wide text-ink">认知链的计数</h2>
                  <InfoTip label="这块数是什么">
                    认知链自己的计数器(轮次、模型调用、命中率、耗时)。它是"这个数字人花了多少"
                    的<b className="font-medium text-ink">唯一</b>来源 —— 别处没有第二个数可以拿来对。
                  </InfoTip>
                </div>
              }
              action={<Button variant="ghost" onClick={metrics.reload}><RefreshCw size={13} />刷新</Button>}
            >
              {metrics.error && <p className="text-sm text-danger">{describeError(metrics.error)}</p>}
              {metrics.data && <RecordView value={metrics.data} empty="还没有指标 —— 它要跑过至少一轮认知。" />}
            </Panel>

            <PendingPanel agentId={agentId} />
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <TracesPanel agentId={agentId} state={traces} />

            <Panel
              title="她排下的闹钟"
              action={<Button variant="ghost" onClick={wakeups.reload}><RefreshCw size={13} />刷新</Button>}
            >
              {wakeups.error && <p className="text-sm text-danger">{describeError(wakeups.error)}</p>}
              {wakeups.data && wakeups.data.length === 0 && (
                <Empty>
                  她没在等任何时刻。
                  <InfoTip label="空列表意味着什么">
                    空列表在这里是<b className="font-medium text-ink">正常的</b>: 闹钟只被她
                    自己排下 —— 一个意图到点、一条未了的事、一段沉默太久、计划表里"到了点要触发"
                    的某件事。她今天可能一件都没有。
                  </InfoTip>
                </Empty>
              )}
              {wakeups.data && wakeups.data.length > 0 && (
                <ul className="divide-y divide-line">
                  {wakeups.data.map((w, i) => (
                    <li key={`${w.source}-${w.wakeAt}-${i}`} className="py-2 text-xs">
                      <div className="flex items-baseline gap-3">
                        <span className="w-12 shrink-0 font-mono text-ink tnum">{fmtClock(w.wakeAt ?? null)}</span>
                        <span className="min-w-0 flex-1 truncate text-ink-soft">
                          {w.reason || w.eventType || '(没有理由)'}
                        </span>
                        {w.source && <Chip>{w.source}</Chip>}
                      </div>
                      {/*
                        理由与事件类型分开显示: 前者是她自己的话(为什么等), 后者是系统
                        的分类(等到了要干什么)。合起来看才读得出"这个闹钟是谁排的"。
                      */}
                      {w.reason && w.eventType && (
                        <span className="mt-0.5 block pl-[3.75rem] font-mono text-[11px] text-ink-faint">
                          {w.eventType}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          <Panel
            title={
              <div className="flex min-w-0 items-center gap-1.5">
                <h2 className="text-sm font-medium tracking-wide text-ink">已注册的处理器</h2>
                <InfoTip label="「处理器」是什么">
                  这一组名字是<b className="font-medium text-ink">运行时注册</b>的, 不是编译期的
                  一张清单 —— 所以它是"这个数字人现在装了哪些能力"的权威答案。
                  <br />
                  <br />
                  少一个往往就意味着某条链路整个不工作, 而它在别处的表现只是"一条事件没有反应"。
                </InfoTip>
              </div>
            }
          >
            {registered.error && <p className="text-sm text-danger">{describeError(registered.error)}</p>}
            {registered.data && registered.data.registered.length === 0 && (
              <Empty>没有注册任何处理器 —— 这个数字人的认知链是空的。</Empty>
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
          <GapNote
            title="降级没有单独的读数"
            tip={
              <>
                V2.2 §8 的 LLM 降级(Mock 回退)目前只能从指标里间接看出来 —— 调用数对不上、
                耗时异常短, 都是间接证据。
                <br />
                <br />
                缺的端点: <span className="font-mono">GET /api/companions/{'{id}'}/v9/degradation</span>,
                返回"最近 N 次调用里有多少次落到了 Mock、分别是什么原因"。
              </>
            }
          />
          <GapNote
            title="进程内的账本读不到"
            tip={
              <>
                这一页显示的三块(排程 / 待处理消息 / 影子)都是从持久化的那一侧读的。而
                <span className="font-mono"> ContinuousEffectLedger </span>与
                <span className="font-mono"> RealtimeEventQueue </span>
                (<span className="font-mono">boundary/event/*</span> 那两个 fabric)是进程内的 ——
                重启即重建, 没有任何读取面。
                <br />
                <br />
                所以"她此刻的刺激队列里排着什么"这个问题, 界面上答不了。这在排查
                "她怎么对这个通知没反应"时是最需要的那一条信息。
              </>
            }
          />
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
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium tracking-wide text-ink">新旧主链有没有分歧</h2>
          <InfoTip label="这块数是什么">
            V11 的新主链在没有正式切流之前, 会<b className="font-medium text-ink">同时</b>跑一遍
            旧的判定, 把两次结果的分歧记下来。这一块就是那份分歧账 —— 它是"能不能切"的唯一依据。
          </InfoTip>
        </div>
      }
      action={<Button variant="ghost" onClick={state.reload}><RefreshCw size={13} />刷新</Button>}
    >
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
              <p className="mb-1.5 text-xs text-ink-faint">
                <span className="font-mono">overall</span> · 全平台口径
              </p>
              <RecordView value={d.overall} empty="空。" />
            </section>
          )}
          {d.thisCompanion && (
            <section>
              <p className="mb-1.5 text-xs text-ink-faint">
                <span className="font-mono">thisCompanion</span> · 只算她
              </p>
              <RecordView value={d.thisCompanion} empty="空。" />
            </section>
          )}
          {d.recent && d.recent.length > 0 && (
            <section>
              <p className="mb-1.5 text-xs text-ink-faint">
                <span className="font-mono">recent</span> · 最近 {d.recent.length} 条
              </p>
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

/**
 * 摩擦类型 → 人话。
 *
 * 这三个值说的不是"她有多忙", 而是**卡在哪一步** —— 处置完全不同:
 * `SEEN_NO_REPLY` 是她看见了但当时不想回(再等等就好); `WANTED_TO_REPLY_FORGOT`
 * 是她想过要回、被别的事打断然后忘了(她的复查窗口本来就拉得更长);
 * `REPLIED_HALFWAY` 是回了半句被打断(接上就行)。
 *
 * 未知值原样显示: 服务端加了第四种而这里没跟上时, 运维该看到那个生词,
 * 而不是被一个"其他"糊过去。
 */
function frictionLabel(t: string): string {
  switch (t) {
    case 'SEEN_NO_REPLY':
      return '看到了没回'
    case 'WANTED_TO_REPLY_FORGOT':
      return '想回、被打断忘了'
    case 'REPLIED_HALFWAY':
      return '回了一半被打断'
    default:
      return t
  }
}

function PendingPanel({ agentId }: { agentId: string }) {
  const pending = useAsync(() => listPendingMessages(agentId), [agentId])
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set())

  return (
    <Panel
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">她推迟回的消息</h2>
          <InfoTip label="「她推迟回的消息」是什么">
            她已经看过、但当时决定"待会儿再回"的那些消息。每一条都带一个
            <b className="font-medium text-ink">摩擦类型</b>: 它说的不是她有多忙, 而是
            <b className="font-medium text-ink">卡在哪一步</b> —— 处置完全不同。
          </InfoTip>
        </div>
      }
      action={<Button variant="ghost" onClick={pending.reload}><RefreshCw size={13} />刷新</Button>}
    >
      <div className="mb-4 flex items-start gap-2 rounded-lg border border-warn/40 bg-warn/10 px-3 py-2">
        <AlertTriangle size={14} className="mt-0.5 shrink-0 text-warn" />
        <p className="flex flex-wrap items-center gap-1.5 text-[11px] leading-relaxed text-warn">
          <span>这一块含消息正文 —— 整个控制台里唯一一处。</span>
          <InfoTip tone="warn" label="为什么这里能显示正文">
            理由是具体的: 存进来的就是她自己已经看过、并决定推迟的那一条, 所以它不是泄漏。
            <br />
            <br />
            它只能出现在运维面。她那一侧的每一页都不许渲染这个字段 —— 那条路一旦打开,
            "正文只在她主动去看的时候才进入她"就不成立了。
          </InfoTip>
        </p>
      </div>

      {pending.error && <p className="text-sm text-danger">{describeError(pending.error)}</p>}
      {pending.loading && !pending.data && <Empty>读取中…</Empty>}
      {pending.data && pending.data.length === 0 && (
        <Empty>
          没有推后的消息。
          <InfoTip label="「没有推后的消息」意味着什么">
            "推后"和"没理会"是两件事: 前者在这张表里, 后者
            <b className="font-medium text-ink">不在这里</b> —— 她压根没感知到的消息不会产生
            一条待办。
            <br />
            <br />
            这条队列<b className="font-medium text-ink">有终点</b>: 复查到上限她还没回, 这一条
            就变成"她忘了"并离开这里。所以"列表变短"既可能是她回了, 也可能是她放下了 ——
            两者在别处(对话与轨迹)分得清。
          </InfoTip>
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
                <div className="mt-1 flex flex-wrap items-baseline gap-x-3 gap-y-1">
                  {/*
                    复查次数不是内部计数器 —— 它回答的是运维真正会问的那句话:
                    "这条她是在想, 还是已经忘了"。到上限的那一条**不在这个列表里**
                    (它已经 EXPIRED), 所以"还剩几次"是这里唯一读得出来的紧迫度。
                  */}
                  {m.reviewCount !== undefined && (
                    <span className="text-[11px] text-ink-faint">
                      复查 {m.reviewCount}/{m.maxReviews ?? '?'} 次
                      {m.maxReviews !== undefined && m.reviewCount >= m.maxReviews - 1 && (
                        <span className="ml-1 text-warn">最后一次</span>
                      )}
                    </span>
                  )}
                  {m.frictionType && (
                    <span className="text-[11px] text-ink-faint">{frictionLabel(m.frictionType)}</span>
                  )}
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
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">认知轨迹 ({rows.length})</h2>
          <InfoTip label="「认知轨迹」是什么">
            一条轨迹 = 一次认知的完整过程。它是"她为什么这么反应"的
            <b className="font-medium text-ink">唯一</b>可复查记录 —— 上面那些计数说
            "跑了几轮", 这里说"每一轮想了什么"。
          </InfoTip>
        </div>
      }
      action={<Button variant="ghost" onClick={state.reload}><RefreshCw size={13} />刷新</Button>}
    >
      {state.error && <p className="text-sm text-danger">{describeError(state.error)}</p>}
      {state.loading && state.data === null && <Empty>读取中…</Empty>}
      {state.data && rows.length === 0 && (
        <Empty>
          还没有轨迹。
          <InfoTip label="轨迹什么时候才会出现">
            她收到第一条消息、或第一次被定时任务叫醒之后才会出现。
          </InfoTip>
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
        <p className="mt-2 text-pretty text-[11px] text-ink-faint">
          只有数字人 <span className="font-mono">{agentId}</span> 的轨迹 —— 跨数字人检索
          还没有端点(见本页缺口)。
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
        ? `影子对比没有在跑: ${v.note}`
        : '影子对比没有在跑(或服务端没说它在跑)—— 下面的数全是 0, 但那表示「没测」, 不表示「没有分歧」。',
    }
  }
  if (v.note?.trim()) return { tone: 'unknown', text: v.note }
  return {
    tone: 'ok',
    text: '影子对比在跑。下面的分歧计数是"新主链与旧判定不一致"的实际发生次数 —— 它是能不能切流的唯一依据。',
  }
}
