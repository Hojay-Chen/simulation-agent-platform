import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Activity, RefreshCw, Trash2 } from 'lucide-react'
import {
  ApiError,
  createAgent,
  deleteAgent,
  getAgent,
  listAgents,
  updatePersona,
  type AgentSummary,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { useSessionStore } from '@/stores/session'
import { AgentCreateForm, type AgentFormInput } from '@/components/AgentCreateForm'
import { AgentList } from '@/components/AgentList'
import { Button, Empty, ErrorNote, Field, Notice, Panel, inputClass } from '@/components/ui'

/**
 * Agents —— 列表 + 详情 + 创建, 一页三态。
 *
 * 选中项落在 URL query(`?id=`)而不是组件状态: 刷新/分享链接后还停在同一个
 * agent 上, 从状态页返回(带 ?id=)也直接落回原处。
 */
export function Agents() {
  const clientKey = useSessionStore((s) => s.clientKey)
  const [params, setParams] = useSearchParams()
  const selectedId = params.get('id')

  const { data: agents, loading, error, reload, setData } = useAsync(() => listAgents(), [])

  const [createBusy, setCreateBusy] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [detail, setDetail] = useState<AgentSummary | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)

  // 选中变化 → 拉详情。详情页含 persona, 列表接口不带。
  useEffect(() => {
    if (!selectedId) {
      setDetail(null)
      setDetailError(null)
      return
    }
    let alive = true
    setDetailError(null)
    getAgent(selectedId)
      .then((d) => { if (alive) setDetail(d) })
      .catch((e: unknown) => {
        if (alive) {
          setDetail(null)
          setDetailError(e instanceof ApiError ? e.message : String(e))
        }
      })
    return () => { alive = false }
  }, [selectedId])

  function select(id: string | null) {
    const next = new URLSearchParams(params)
    if (id) next.set('id', id)
    else next.delete('id')
    setParams(next, { replace: true })
  }

  async function onCreate(input: AgentFormInput) {
    setCreateBusy(true)
    setCreateError(null)
    try {
      const created = await createAgent({
        description: input.description.trim(),
        relationshipType: input.relationshipType,
      })
      // 本地先插进列表 —— 不等 reload 的往返, 创建完立刻能选中看详情。
      setData([created, ...(agents ?? [])])
      select(created.agentId)
    } catch (e) {
      setCreateError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setCreateBusy(false)
    }
  }

  async function onDelete(agent: AgentSummary) {
    if (!confirm(`删除「${agent.name || agent.agentId}」? 软删(deleted_at), 认知链不再推进它。`)) return
    try {
      await deleteAgent(agent.agentId)
      setData((agents ?? []).filter((a) => a.agentId !== agent.agentId))
      if (selectedId === agent.agentId) select(null)
    } catch (e) {
      setDetailError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-medium text-ink">Agents</h1>
        <Button variant="ghost" onClick={reload}>
          <RefreshCw size={13} />
          刷新
        </Button>
      </div>

      {!clientKey && (
        <Notice>
          还没填客户端 API Key —— 列表与创建都会失败。到
          <Link className="mx-1 underline" to="/connect">接入</Link>页填入, 或先在
          <Link className="mx-1 underline" to="/clients">API 客户端</Link>页发放一把。
        </Notice>
      )}

      <div className="grid gap-5 lg:grid-cols-[1.1fr_1.4fr]">
        <Panel title={`我的 agents (${agents?.length ?? 0})`}>
          <ErrorNote error={error} />
          {loading && !agents && <Empty>读取中…</Empty>}
          {agents && (
            <AgentList agents={agents} selectedId={selectedId} onSelect={(id) => select(id)} />
          )}
        </Panel>

        <div className="space-y-5">
          {selectedId ? (
            <AgentDetail
              agent={detail}
              error={detailError}
              onDeleted={onDelete}
              onPersonaUpdated={(p) => setDetail((d) => (d ? { ...d, persona: p } : d))}
            />
          ) : (
            <Panel title="创建 agent">
              <AgentCreateForm onSubmit={onCreate} submitting={createBusy} error={createError} />
            </Panel>
          )}
        </div>
      </div>
    </div>
  )
}

/** 详情 —— 读 persona / 重编译 persona / 软删 / 跳状态页。 */
function AgentDetail({ agent, error, onDeleted, onPersonaUpdated }: {
  agent: AgentSummary | null
  error: string | null
  onDeleted: (agent: AgentSummary) => void
  onPersonaUpdated: (persona: AgentSummary['persona']) => void
}) {
  const [description, setDescription] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)

  useEffect(() => { setErr(null); setFieldError(null) }, [agent?.agentId])

  if (error) {
    return (
      <Panel title="详情">
        <ErrorNote error={error} />
      </Panel>
    )
  }
  if (!agent) {
    return (
      <Panel title="详情">
        <Empty>读取中…</Empty>
      </Panel>
    )
  }

  async function submitPersona() {
    if (!description.trim()) {
      setFieldError('写一段新的描述 —— 平台会重编译成人格并落一个新版本')
      return
    }
    setFieldError(null)
    setBusy(true)
    setErr(null)
    try {
      const r = await updatePersona(agent!.agentId, description.trim(), reason.trim() || undefined)
      onPersonaUpdated(r.persona)
      setDescription('')
      setReason('')
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Panel
        title={
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-medium text-ink">{agent.name || agent.agentId}</h2>
            <span className="font-mono text-xs text-ink-faint">{agent.agentId}</span>
          </div>
        }
        action={
          <div className="flex items-center gap-2">
            <Link to={`/agents/${encodeURIComponent(agent.agentId)}/state`}>
              <Button variant="ghost"><Activity size={13} />实时状态</Button>
            </Link>
            <Button variant="danger" onClick={() => onDeleted(agent)}>
              <Trash2 size={13} />删除
            </Button>
          </div>
        }
      >
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt className="text-xs uppercase tracking-wider text-ink-faint">状态</dt>
            <dd className="text-ink">{agent.status}</dd>
          </div>
          <div>
            <dt className="text-xs uppercase tracking-wider text-ink-faint">创建于</dt>
            <dd className="text-ink">{agent.createdAt?.slice(0, 19).replace('T', ' ') ?? '—'}</dd>
          </div>
          <div className="col-span-2">
            <dt className="text-xs uppercase tracking-wider text-ink-faint">归属客户端</dt>
            <dd className="font-mono text-xs text-ink-soft">{agent.clientId}</dd>
          </div>
        </dl>

        <div className="mt-5">
          <p className="mb-1.5 text-xs uppercase tracking-wider text-ink-faint">当前人格</p>
          {agent.persona
            ? (
              <pre className="max-h-64 overflow-auto rounded-lg border border-line bg-raised p-3 font-mono text-xs leading-relaxed text-ink-soft">
                {JSON.stringify(agent.persona, null, 2)}
              </pre>
            )
            : <Empty>这个 agent 没有可读的人格版本</Empty>}
        </div>
      </Panel>

      <Panel title="重编译人格">
        <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); void submitPersona() }}>
          <Field label="新的描述" error={fieldError} hint="不改人格就只改关系/状态是做不到的 —— 每次更新都落一个 persona 新版本。">
            <textarea
              className={`${inputClass} min-h-[88px] resize-y`}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="把这个人改成什么样…"
            />
          </Field>
          <Field label="变更原因 (可选)" hint="写进版本记录, 便于日后回看人格是怎么演化的。">
            <input
              className={inputClass}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="例: 用户希望她更健谈一些"
            />
          </Field>
          <ErrorNote error={err} />
          <Button type="submit" disabled={busy}>{busy ? '重编译中…' : '更新人格'}</Button>
        </form>
      </Panel>
    </>
  )
}
