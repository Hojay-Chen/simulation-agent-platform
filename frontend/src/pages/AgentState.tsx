import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, RefreshCw } from 'lucide-react'
import { getAgentState, type AgentState as AgentStateData } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { Button, Empty, ErrorNote, Panel } from '@/components/ui'

/** 每 10s 自动重取 —— 状态由 server:8091 的认知链持续写入, 页面只是窗口。 */
const POLL_MS = 10_000

/**
 * agent 实时状态 —— mood / emotionalCloseness / sleepiness。
 *
 * 三个字段由 openapi:8092 直读 `agent_state` 表给出; 写它的是 server:8091 的
 * 认知链(同一个 PG 库, 两边不争不抢)。**缺字段不是错误**: 认知链要在 agent
 * 收到第一条消息后才会建出这一行, 在那之前返回的是"空状态"—— 页面如实说
 * "尚未初始化", 而不是编一组 0 出来。
 */
export function AgentState() {
  const { agentId = '' } = useParams()
  const { data, loading, error, reload } = useAsync(() => getAgentState(agentId), [agentId])
  const [tick, setTick] = useState(0)

  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), POLL_MS)
    return () => clearInterval(t)
  }, [])

  useEffect(() => {
    if (tick > 0) reload()
    // reload 是稳定引用(useCallback []), 不该进依赖 —— 否则每次 tick 重建定时器
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick])

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <div className="flex items-center justify-between">
        <Link to="/" className="inline-flex items-center gap-1.5 text-sm text-ink-soft hover:text-ink">
          <ArrowLeft size={14} />返回 Agents
        </Link>
        <Button variant="ghost" onClick={reload}>
          <RefreshCw size={13} />刷新
        </Button>
      </div>

      <Panel
        title={
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-medium text-ink">{data?.name ?? '实时状态'}</h2>
            <span className="font-mono text-xs text-ink-faint">{agentId}</span>
          </div>
        }
      >
        <ErrorNote error={error} />
        {loading && !data && <Empty>读取中…</Empty>}

        {data && <StateBody state={data} />}

        <p className="mt-5 text-xs leading-relaxed text-ink-faint">
          每 {POLL_MS / 1000} 秒自动重取一次。这里是纯数据面 —— 打开本页不会触发任何认知;
          字段的推进只发生在 agent 收到消息之后。
        </p>
      </Panel>
    </div>
  )
}

function StateBody({ state }: { state: AgentStateData }) {
  const initialized =
    state.mood !== undefined ||
    state.emotionalCloseness !== undefined ||
    state.sleepiness !== undefined

  if (!initialized) {
    return (
      <div className="space-y-3">
        <Empty>尚未初始化</Empty>
        <p className="text-center text-xs text-ink-faint">
          认知链还没为这个 agent 建出 state 行 —— 它收到第一条消息后才会出现。
          {state.note && <span className="mt-1 block">{state.note}</span>}
        </p>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <Metric label="情绪" value={state.mood ?? '—'} />
      <Metric
        label="情感亲密度"
        value={state.emotionalCloseness !== undefined ? state.emotionalCloseness.toFixed(3) : '—'}
        hint="越高越亲近"
      />
      <Metric
        label="困倦度"
        value={state.sleepiness !== undefined ? state.sleepiness.toFixed(3) : '—'}
        hint="越高越想睡"
      />
    </div>
  )
}

function Metric({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-line bg-raised px-4 py-3">
      <p className="text-xs uppercase tracking-wider text-ink-faint">{label}</p>
      <p className="mt-1 truncate font-mono text-lg text-accent" title={value}>{value}</p>
      {hint && <p className="mt-0.5 text-xs text-ink-faint">{hint}</p>}
    </div>
  )
}
