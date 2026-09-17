import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, RefreshCw, Search, Trash2 } from 'lucide-react'
import {
  ApiError,
  getCompanion,
  listMemories,
  searchMemories,
  updatePersona,
  deleteAgent,
  type Companion,
  type MemoryRow,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { useSessionStore } from '@/stores/session'
import { AGENT_TABS, COMPONENT_TABS, distinctTypes, sectionsOf, tabOf } from '@/lib/studio'
import { RecordView } from '@/components/RecordView'
import { Section, describeError } from '@/components/Section'
import { RequireStudio } from '@/components/StudioLogin'
import { Button, Empty, ErrorNote, Field, Panel, inputClass } from '@/components/ui'

/**
 * agent 详情 —— §19.2 的八个标签页。
 *
 * <h2>两套归属, 一个 id</h2>
 *
 * 这一页读的全部是 **Studio 面**(用户 JWT, server:8091)。页头那份档案来自
 * `GET /api/companions/{id}`, 它按"你是不是这个人"过滤 —— 与 Agents 页右栏那套
 * "你是不是这个 API 客户端"是两条独立的规则。
 *
 * 两条路径里的 id 是**同一个值**(`companions.id`), 所以从哪一栏点进来都落到这里。
 * 而账号ID(`agent_…`)是聊天平台侧的标识, 它只在这一页的「身份」里出现, 且**不可修改**。
 *
 * <h2>为什么选中项在 URL 里</h2>
 *
 * 标签页落在 `?tab=`, 与之前 `?id=` 的理由一样: 刷新、分享链接、从别处返回都还停在
 * 同一处。`tabOf()` 会把拼错的 tab 收敛回总览, 而不是弹一整页错误。
 */
export function AgentDetail() {
  return (
    <RequireStudio why="agent 详情读的是记忆、关系、生活这些只在 server:8091 上的数据 —— 需要先用你的账号登录。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { agentId = '' } = useParams()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tab = tabOf(params.get('tab'))
  // 删掉之后**回列表**, 不留在原地刷新: 那一刷会去打一个刚被软删的 id, 页面要么
  // 报错要么显示一份已经不存在的档案 —— 两种都不如实说"它没了, 我们回列表吧"。
  const backToList = () => navigate('/agents', { replace: true })

  // 页头与「身份」页共用这一条档案 —— 分两次取会在极端情况下让两处显示不同的名字。
  const { data: companion, loading, error, reload } = useAsync(
    () => getCompanion(agentId), [agentId])

  function selectTab(next: string) {
    const p = new URLSearchParams(params)
    if (next === 'overview') p.delete('tab')
    else p.set('tab', next)
    setParams(p, { replace: true })
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Link
          to="/agents"
          className="inline-flex items-center gap-1.5 text-sm text-ink-soft hover:text-ink"
        >
          <ArrowLeft size={14} />返回 Agents
        </Link>
        <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
      </div>

      <header className="rounded-xl border border-line bg-raised px-5 py-4">
        <ErrorNote error={error ? describeError(error) : null} />
        {loading && !companion && <Empty>读取中…</Empty>}
        {companion && (
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h1 className="text-lg font-medium text-ink">{companion.name || companion.id}</h1>
            <span className="font-mono text-xs text-ink-faint" title="agent id(本平台标识这个 agent 个体的值)">
              {companion.id}
            </span>
            {companion.handle
              ? <span className="font-mono text-xs text-accent" title="账号ID(聊天平台侧的标识)">{companion.handle}</span>
              : <span className="text-xs text-warn">还没有账号ID</span>}
          </div>
        )}
      </header>

      <nav className="flex gap-1 overflow-x-auto border-b border-line">
        {AGENT_TABS.map((t) => {
          const active = t.id === tab
          return (
            <button
              key={t.id}
              type="button"
              onClick={() => selectTab(t.id)}
              aria-current={active ? 'page' : undefined}
              className={`shrink-0 whitespace-nowrap border-b-2 px-3 py-2 text-sm transition-colors ${
                active
                  ? 'border-accent text-accent'
                  : 'border-transparent text-ink-soft hover:text-ink'
              }`}
            >
              {t.label}
            </button>
          )
        })}
      </nav>

      {tab === 'identity' && (
        <Panel
          title="档案"
          action={companion && (
            <span className="flex items-center gap-2 text-xs text-ink-faint">
              {companion.relationshipType && <span>{companion.relationshipType}</span>}
              {companion.relationshipStage && <span>· {companion.relationshipStage}</span>}
            </span>
          )}
        >
          <p className="mb-4 text-xs leading-relaxed text-ink-faint">
            <span className="font-mono text-ink-soft">id</span> 是**本平台**标识这个 agent 个体的值,
            <span className="mx-1 font-mono text-ink-soft">handle</span>是聊天平台侧的账号ID。
            两者永不可互换 —— 前者不可变, 后者由系统分配、agent 自己改不了。
          </p>
          <RecordView value={companion} empty="读不到这个 agent 的档案。" />
        </Panel>
      )}

      {tab === 'memory' && <MemoryTab agentId={agentId} />}

      {!COMPONENT_TABS.includes(tab) && sectionsOf(tab).map((spec) => (
        <Section key={spec.key} spec={spec} agentId={agentId} />
      ))}

      {companion && <OpenApiActions companion={companion} onDeleted={backToList} />}
    </div>
  )
}

/**
 * 「记忆」—— 八个标签页里唯一有输入框的那个。
 *
 * 搜索走的是 `GET /memories/search?q=`, **不是**在前端过滤已加载的那一批: 记忆是按
 * 重要度/时间分页取的, 前端拿到的是"最近的一屏", 在里面搜等于把"没搜到"和"排在后面"
 * 混成同一件事。这一点值得写在页面上 —— 用户看不见分页, 只看得见结果少。
 */
function MemoryTab({ agentId }: { agentId: string }) {
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const [type, setType] = useState<string | null>(null)

  // 类型筛选**在本地做**, 所以列表总是取全量(`?type=` 那个服务端参数留在这里不用)。
  // 让服务端筛的话, 返回的那一屏里就只剩选中的那一种类型 —— 于是类型候选塌成一项,
  // 用户再也切不回去, 只能清空重来。
  const { data, loading, error, reload } = useAsync(
    () => (query ? searchMemories(agentId, query) : listMemories(agentId)),
    [agentId, query],
  )

  const types = distinctTypes(data ?? [])
  const shown = filterByType(data ?? [], type)

  return (
    <Panel
      title={
        <div className="min-w-0">
          <h2 className="text-sm font-medium text-ink">
            记忆 <span className="font-normal text-ink-faint">({shown.length})</span>
          </h2>
          <p className="mt-0.5 text-xs font-normal leading-relaxed text-ink-faint">
            搜索走服务端 —— 这里看到的只是一屏, 不在这一屏里的记忆前端搜不到。
          </p>
        </div>
      }
      action={<Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>}
    >
      <form
        className="mb-4 flex items-center gap-2 rounded-lg border border-line bg-sunken/40 px-3 py-2"
        onSubmit={(e) => {
          e.preventDefault()
          // 搜索与类型筛选是两种"窄化", 同时开着的后果是"搜到了但看不见" ——
          // 而筛选条那时已经藏起来了, 用户没有任何线索能想到它还在生效。
          setType(null)
          setQuery(input.trim())
        }}
      >
        <Search size={14} className="shrink-0 text-ink-faint" />
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="搜一条记忆… (回车)"
          className="min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
        />
        {query && (
          <button
            type="button"
            onClick={() => { setInput(''); setQuery('') }}
            className="shrink-0 text-xs text-ink-soft hover:text-ink"
          >
            清空
          </button>
        )}
      </form>

      {types.length > 1 && !query && (
        <div className="mb-4 flex flex-wrap gap-1.5">
          <TypeChip active={type === null} onClick={() => setType(null)}>全部</TypeChip>
          {types.map((t) => (
            <TypeChip key={t} active={type === t} onClick={() => setType(t)}>{t}</TypeChip>
          ))}
        </div>
      )}

      <ErrorNote error={error ? describeError(error) : null} />
      {loading && !data && <Empty>读取中…</Empty>}
      {data && shown.length === 0 && (
        <Empty>{query ? `没有匹配「${query}」的记忆。` : '这个 agent 还没有记忆。'}</Empty>
      )}
      {shown.length > 0 && (
        <ul className="space-y-3">
          {shown.map((m, i) => (
            <li key={m.id ?? m.memoryId ?? i} className="rounded-lg border border-line bg-sunken/40 p-3">
              <RecordView value={m} />
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

/** 类型筛选 —— 放成纯函数是为了它能被测: 筛选判空的写法最容易把 '' 与 undefined 搞混。 */
function filterByType(rows: MemoryRow[], type: string | null): MemoryRow[] {
  if (!type) return rows
  return rows.filter((r) => (r.type ?? '').trim() === type)
}

function TypeChip({ active, onClick, children }: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-md border px-2 py-0.5 font-mono text-xs transition ${
        active ? 'border-accent text-accent' : 'border-line text-ink-soft hover:text-ink'
      }`}
    >
      {children}
    </button>
  )
}

/**
 * 开放面操作 —— 页面底部那一块, **只在手里有客户端钥时才出现**。
 *
 * 它属于另一张面: 上面八个标签页读的是"你是这个人吗"(用户 JWT), 而删除与重编译人格
 * 走的是"你是这个 API 客户端吗"(`sap_` 钥匙)。放在一起不是偷懒, 是因为它们作用于
 * **同一个 agent** —— 分成两个页面才真的会让人找不到。
 *
 * 但那道界线必须写在屏幕上: 否则用户会以为自己刚刚是在以自己的身份删掉的。
 */
function OpenApiActions({ companion, onDeleted }: { companion: Companion; onDeleted: () => void }) {
  const clientKey = useSessionStore((s) => s.clientKey)
  const [description, setDescription] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  // 换了一个 agent 就把上一份输入清掉 —— 否则很容易把给 A 写的人格描述提交到 B 上。
  useEffect(() => { setDescription(''); setReason(''); setErr(null); setDone(false) }, [companion.id])

  if (!clientKey) {
    return (
      <Panel title="开放面操作">
        <Empty>
          手里没有客户端钥 —— 这一块是空的。
          <Link to="/access" className="ml-1 text-accent hover:underline">去 API 页填一把</Link>
          , 或用它创建一个属于该客户端的 agent。
        </Empty>
      </Panel>
    )
  }

  async function submit() {
    if (!description.trim()) {
      setErr('写一段新的描述 —— 平台会重编译成人格并落一个新版本。')
      return
    }
    setBusy(true)
    setErr(null)
    setDone(false)
    try {
      await updatePersona(companion.id, description.trim(), reason.trim() || undefined)
      setDescription('')
      setReason('')
      setDone(true)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!confirm(`删除「${companion.name || companion.id}」? 软删(deleted_at), 认知链不再推进它。`)) return
    try {
      await deleteAgent(companion.id)
      onDeleted()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Panel
      title="开放面操作"
      action={<Button variant="danger" onClick={() => void remove()}><Trash2 size={13} />删除</Button>}
    >
      <p className="mb-4 text-xs leading-relaxed text-ink-faint">
        这一块走的是 <span className="font-mono text-ink-soft">/api/v1/openapi/**</span>(客户端钥),
        与上面八页走的内省接口是**两张不同的面** —— 上面问的是"你是不是这个人", 这里问的是
        "你是不是这个 API 客户端"。同一个 agent, 两条独立的准入规则。
      </p>
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <Field
          label="新的描述"
          error={err}
          hint="每次更新都会落一个 persona 新版本 —— 不改人格只改关系是做不到的。"
        >
          <textarea
            className={`${inputClass} min-h-[88px] resize-y`}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="把这个人改成什么样…"
          />
        </Field>
        <Field label="变更原因 (可选)">
          <input
            className={inputClass}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="例: 用户希望她更健谈一些"
          />
        </Field>
        <div className="flex items-center gap-3">
          <Button type="submit" disabled={busy}>{busy ? '重编译中…' : '更新人格'}</Button>
          {done && <span className="text-xs text-ok">已提交, 拉到新版本了 —— 切到「人格」页看</span>}
        </div>
      </form>
    </Panel>
  )
}
