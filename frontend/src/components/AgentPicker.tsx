import { useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'
import { listCompanions, type Companion } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { Empty, ErrorNote } from './ui'
import { describeError } from './Section'

/**
 * 运维页顶部的"看哪一个 agent"。
 *
 * <h2>为什么运维页需要它, 而看她那一侧不需要</h2>
 *
 * 「今天 / 计划表 / 手机 / 身体」这四页的入口是**一个 agent 的详情页**, 所以 id 在
 * 路由段里, 不需要选。而运维页问的是跨 agent 的问题 —— "刚刚发生了什么""谁的认知链
 * 在报错" —— 它从侧栏直接进来, 手上没有 id。
 *
 * <h2>为什么选择落在 URL 上</h2>
 *
 * 与 agent 详情页的 `?tab=` 同一条理由: 一条"我刚才看到的那串报错"要能发给别人。
 * 状态藏在组件里的话, 收链接的人打开看到的是**另一个** agent 的日志, 而他不会知道。
 *
 * <h2>为什么默认落在第一个 agent 而不是空</h2>
 *
 * 因为空选择会让整页变成"请先选一个" —— 而用户来这一页是为了看点什么。默认选第一个
 * 至少让他立刻看到真实数据(哪怕不是他想要的那个), 然后自己换。这与"报错但不显示"
 * 的区别是: 前者一秒钟就能修, 后者要用户先猜到该做什么。
 */
export function useAgentChoice(): {
  agents: Companion[]
  agentId: string
  setAgentId: (id: string) => void
  loading: boolean
  error: string | null
  reload: () => void
} {
  const [params, setParams] = useSearchParams()
  const { data, loading, error, reload } = useAsync(() => listCompanions(), [])

  const agents = useMemo(() => data ?? [], [data])
  const wanted = params.get('agent') ?? ''

  /*
   * URL 里那个 id 可能已经不存在了(agent 被删、或者链接是从别处抄来的)。
   * 这时**回落到列表里的第一个**, 而不是把那个 id 原样传下去 —— 传下去的结果是
   * 每一个面板各报一次 404, 而真正的原因(这个 agent 没了)在四条例外里看不出来。
   */
  const agentId = agents.some((a) => a.id === wanted) ? wanted : (agents[0]?.id ?? '')

  // 把回落的那个值写回 URL —— 否则"当前看的是谁"和地址栏显示的是两个不同的答案,
  // 而复制出去的是地址栏那一个。
  useEffect(() => {
    if (agents.length > 0 && wanted !== agentId) {
      const p = new URLSearchParams(params)
      p.set('agent', agentId)
      setParams(p, { replace: true })
    }
    // params/setParams 每次渲染都是新引用, 进依赖会自激; 这里只在 id 变了时动手。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, wanted, agents.length])

  function setAgentId(id: string) {
    const p = new URLSearchParams(params)
    p.set('agent', id)
    setParams(p, { replace: true })
  }

  return { agents, agentId, setAgentId, loading, error, reload }
}

/** 选择器本体。列表为空时**不画一个空的下拉框** —— 那看起来像坏了。 */
export function AgentPicker({
  agents,
  agentId,
  onChange,
  loading,
}: {
  agents: readonly Companion[]
  agentId: string
  onChange: (id: string) => void
  loading: boolean
}) {
  if (loading && agents.length === 0) {
    return <span className="text-xs text-ink-faint">读取 agent 列表…</span>
  }
  if (agents.length === 0) {
    return <Empty>你名下还没有 agent —— 运维页看的是它们的运行时。</Empty>
  }
  return (
    <label className="flex items-center gap-2 text-xs">
      <span className="shrink-0 text-ink-faint">看哪一个</span>
      <select
        className="input !w-auto !py-1"
        value={agentId}
        onChange={(e) => onChange(e.target.value)}
      >
        {agents.map((a) => (
          <option key={a.id} value={a.id}>
            {a.name || a.id}
            {a.handle ? ` · ${a.handle}` : ''}
          </option>
        ))}
      </select>
    </label>
  )
}

/** 运维页顶部的那一条: 选择器 + 取列表失败时的原话。 */
export function AgentPickerBar({
  agents,
  agentId,
  onChange,
  loading,
  error,
  onReload,
  right,
}: {
  agents: readonly Companion[]
  agentId: string
  onChange: (id: string) => void
  loading: boolean
  error: string | null
  onReload: () => void
  right?: React.ReactNode
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <AgentPicker agents={agents} agentId={agentId} onChange={onChange} loading={loading} />
        <div className="flex items-center gap-2">{right}</div>
      </div>
      <ErrorNote error={error ? describeError(error) : null} />
      {error && (
        <button
          type="button"
          onClick={onReload}
          className="text-xs text-accent hover:underline"
        >
          重新读一次
        </button>
      )}
    </div>
  )
}
