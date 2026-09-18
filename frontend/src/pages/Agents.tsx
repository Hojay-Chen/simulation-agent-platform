import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Bot, ChevronRight, Pause, Play, RefreshCw, Trash2 } from 'lucide-react'
import {
  ApiError,
  createAgent,
  deleteAgent,
  listAgents,
  listCompanions,
  pauseAllMine,
  resumeAllMine,
  setAgentLifecycle,
  type AgentSummary,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { useSessionStore } from '@/stores/session'
import { AgentCreateForm, type AgentFormInput } from '@/components/AgentCreateForm'
import { Button, Chip, Empty, ErrorNote, Notice, Panel } from '@/components/ui'
import { RequireStudio } from '@/components/StudioLogin'

/**
 * Agents —— 两张名单在一页上, **因为它们本来就是两回事**。
 *
 * <h2>为什么是两栏而不是一栏</h2>
 *
 * 「我的 agents」按**人**归属(`GET /api/companions`, 用户 JWT): 我在聊天平台上认识
 * 的那几个数字人。「API 客户端建的 agents」按**客户端**归属(`GET /api/v1/openapi/agents`,
 * `sap_` 钥匙): 某个程序自己建的程序化 agent。
 *
 * 把它们合成一栏看起来更整齐, 但会立刻带来一个没有答案的问题: 删除一个"我的 agent"
 * 到底该调哪个端点? 两者的鉴权模型完全不同 —— 一个是"你是这个人吗", 一个是"你是这个
 * 客户端吗"。**分栏就是这条界线的样子**, 而它值得被看见。
 *
 * 详情页在右边那一套(`/agents/:id`), 两张名单里点进去是同一个页面: `companions.id`
 * 与 openapi 的 `agentId` 是**同一个值** —— agent 平台标识 agent 个体的那个 id。
 * 账号ID(`agent_…`)是另一回事, 那是聊天平台侧的标识, 见详情页「身份」。
 */
export function Agents() {
  return (
    <div className="space-y-5">
      <h1 className="text-lg font-medium text-ink">Agents</h1>
      <RequireStudio why="「我的 agents」按你在聊天平台上的归属过滤 —— 需要先登录。">
        <MyAgents />
      </RequireStudio>
      <ClientAgents />
    </div>
  )
}

/** 人这一侧 —— 与首页同一个数据源, 所以两边数量永远一致。 */
function MyAgents() {
  const { data, loading, error, reload, setData } = useAsync(() => listCompanions(), [])
  const [busy, setBusy] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)

  const paused = (data ?? []).filter((c) => c.lifecycle === 'paused')

  /*
   * 批量暂停之后**重取**而不是就地改本地数组。
   *
   * 就地改更快, 但它会掩盖一件事: 批量暂停在服务端是逐个 agent 做的, 而"我提交的这批
   * 全都成功了"只是客户端的猜测 —— 若中间有一个失败, 本地数组会显示全部已停,
   * 而服务端不是。重取把界面重新钉在服务端事实上, 代价是一次列表请求。
   */
  async function bulk(action: 'pause' | 'resume') {
    setBusy(true)
    setSwitchError(null)
    try {
      await (action === 'pause' ? pauseAllMine() : resumeAllMine())
      reload()
    } catch (e) {
      setSwitchError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function toggle(id: string, next: 'active' | 'paused') {
    setSwitchError(null)
    try {
      await setAgentLifecycle(id, next)
      // 就地改这一行: 单个开关的响应里带着权威的新状态, 为一个开关重取整张列表
      // 会让整页闪一下, 而用户刚点的是这一行 —— 视觉上应当只有这一行动。
      setData((data ?? []).map((c) => (c.id === id ? { ...c, lifecycle: next } : c)))
    } catch (e) {
      setSwitchError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Panel
      title="我的 agents"
      action={
        <span className="flex items-center gap-2">
          <span className="text-xs text-ink-faint">
            按人在聊天平台上的归属 · {data?.length ?? 0}
            {paused.length > 0 && <span className="ml-1 text-warn">({paused.length} 已停止)</span>}
          </span>
          {(data?.length ?? 0) > 0 && (
            <>
              <Button
                variant="ghost"
                disabled={busy}
                title="停止我名下全部 agent —— 它们不再推进、不再调 LLM"
                onClick={() => void bulk('pause')}
              >
                <Pause size={13} />全部停止
              </Button>
              <Button
                variant="ghost"
                disabled={busy}
                title="恢复我名下全部 agent"
                onClick={() => void bulk('resume')}
              >
                <Play size={13} />全部继续
              </Button>
            </>
          )}
          <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
        </span>
      }
    >
      <ErrorNote error={error} />
      {switchError && <div className="mb-2"><ErrorNote error={switchError} /></div>}
      <p className="mb-3 text-xs leading-relaxed text-ink-faint">
        这些 agent 是<b>持续运转</b>的: 没人说话时, 定时任务也在推进它们的一生, 而每一步
        都可能调用模型。停止<b>不删除任何东西</b> —— 记忆、关系、未说的话全部留着,
        继续之后从停下的那一刻接着走。
      </p>
      {loading && !data && <Empty>读取中…</Empty>}
      {data && data.length === 0 && (
        <Empty>你在聊天平台上还没有 agent。到聊天平台里「一键创建 agent 好友」建一个。</Empty>
      )}
      {data && data.length > 0 && (
        <ul className="divide-y divide-line">
          {data.map((c) => {
            const isPaused = c.lifecycle === 'paused'
            return (
              <li key={c.id} className="flex items-center gap-3 px-1 py-3">
                <Link
                  to={`/agents/${encodeURIComponent(c.id)}`}
                  className="flex min-w-0 flex-1 items-center gap-3 transition hover:opacity-80"
                >
                  <span
                    className={`grid h-9 w-9 shrink-0 place-items-center rounded-lg border bg-raised ${
                      isPaused ? 'border-line text-ink-faint' : 'border-line text-accent'
                    }`}
                  >
                    <Bot size={16} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span className="truncate text-sm text-ink">{c.name || c.id}</span>
                      {isPaused && <Chip tone="warn">已停止</Chip>}
                    </span>
                    <span className="block truncate font-mono text-xs text-ink-faint">
                      {c.handle || '还没有账号ID'}
                    </span>
                  </span>
                  <span className="shrink-0 text-xs text-ink-faint">{c.relationshipStage ?? ''}</span>
                </Link>
                <Button
                  variant="ghost"
                  disabled={busy}
                  title={isPaused ? '继续运行' : '停止运行(不删任何东西)'}
                  onClick={() => void toggle(c.id, isPaused ? 'active' : 'paused')}
                >
                  {isPaused ? <Play size={13} /> : <Pause size={13} />}
                  {isPaused ? '继续' : '停止'}
                </Button>
                <Link to={`/agents/${encodeURIComponent(c.id)}`} className="shrink-0 text-ink-faint">
                  <ChevronRight size={14} />
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </Panel>
  )
}

/** 客户端这一侧 —— 建/列/删都走 `sap_` 钥匙, 与上面那一栏互不相通。 */
function ClientAgents() {
  const clientKey = useSessionStore((s) => s.clientKey)
  const navigate = useNavigate()
  const { data: agents, loading, error, reload, setData } = useAsync(() => listAgents(), [])

  const [busy, setBusy] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)

  async function onCreate(input: AgentFormInput) {
    setBusy(true)
    setCreateError(null)
    try {
      const created = await createAgent({
        description: input.description.trim(),
        relationshipType: input.relationshipType,
      })
      setData([created, ...(agents ?? [])])
      navigate(`/agents/${encodeURIComponent(created.agentId)}`)
    } catch (e) {
      setCreateError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function onDelete(agent: AgentSummary) {
    if (!confirm(`删除「${agent.name || agent.agentId}」? 软删(deleted_at), 认知链不再推进它。`)) return
    try {
      await deleteAgent(agent.agentId)
      setData((agents ?? []).filter((a) => a.agentId !== agent.agentId))
    } catch (e) {
      setCreateError(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <div className="grid gap-5 lg:grid-cols-[1.1fr_1fr]">
      <Panel
        title="API 客户端建的 agents"
        action={
          <span className="flex items-center gap-2">
            <span className="text-xs text-ink-faint">按客户端归属 · {agents?.length ?? 0}</span>
            <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
          </span>
        }
      >
        {!clientKey && (
          <div className="mb-3">
            <Notice>
              还没填客户端钥 —— 这一栏与创建都会失败。到
              <Link className="mx-1 underline" to="/access">API</Link>页发放/填入一把。
            </Notice>
          </div>
        )}
        <ErrorNote error={error} />
        {loading && !agents && <Empty>读取中…</Empty>}
        {agents && agents.length === 0 && <Empty>这个客户端名下还没有 agent。</Empty>}
        {agents && agents.length > 0 && (
          <ul className="divide-y divide-line">
            {agents.map((a) => (
              <li key={a.agentId} className="flex items-center gap-2 px-1 py-2.5">
                <Link
                  to={`/agents/${encodeURIComponent(a.agentId)}`}
                  className="flex min-w-0 flex-1 items-center gap-3 transition hover:text-accent"
                >
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg border border-line bg-raised text-ink-soft">
                    <Bot size={14} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm text-ink">{a.name || a.agentId}</span>
                    <span className="block truncate font-mono text-xs text-ink-faint">{a.agentId}</span>
                  </span>
                </Link>
                <Button
                  variant="ghost"
                  title="软删"
                  onClick={() => void onDelete(a)}
                >
                  <Trash2 size={13} />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="创建 agent">
        <p className="mb-4 text-xs leading-relaxed text-ink-faint">
          这一栏建出来的 agent 属于**上面那把客户端钥**的持有者, 不出现在「我的 agents」里
          —— 两者是两条独立的归属路径(这正是两个平台互相独立、只通过接口往来的一种体现)。
        </p>
        <AgentCreateForm onSubmit={onCreate} submitting={busy} error={createError} />
      </Panel>
    </div>
  )
}
