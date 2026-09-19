import { useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Pause, Play, RefreshCw, Search, Trash2 } from 'lucide-react'
import {
  ApiError,
  getCompanion,
  listMemories,
  searchMemories,
  setAgentLifecycle,
  updatePersona,
  deleteAgent,
  type Companion,
  type MemoryRow,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { useSessionStore } from '@/stores/session'
import { AGENT_TABS, DEFAULT_TAB, PREFIXED_TABS, distinctTypes, sectionsOf, tabOf } from '@/lib/studio'
import { RecordView } from '@/components/RecordView'
import { Section, describeError } from '@/components/Section'
import { RequireStudio } from '@/components/StudioLogin'
import { Button, Chip, Empty, ErrorNote, Field, InfoTip, Panel, inputClass } from '@/components/ui'
/*
 * 四个"她此刻"的页 + 关系网。`Body` 在这里改名, 因为本文件里已经有一个同名的局部
 * 函数(页面外壳) —— 两个 `Body` 撞在一起时 TypeScript 报的是"属性不存在"这种
 * 看不出根因的错。
 */
import { Body as BodyTab } from './agent/Body'
import { Phone } from './agent/Phone'
import { Plan } from './agent/Plan'
import { Relation } from './agent/Relation'
import { Today } from './agent/Today'

/**
 * agent 详情 —— 八个标签页。**打开一个 agent 就是打开这一页**, 所以它得先回答
 * "她今天怎么样", 才轮到"她有哪些字段"。
 *
 * <h2>八个页分成两半, 前四后四</h2>
 *
 * 前四个(今天 / 计划表 / 手机 / 身体)回答的是同一个问题: **她此刻**。它们是快的、
 * 看得见变化的 —— 今天怎么过、打算做什么、手机响不响、身体怎么样。所以它们连在一起
 * 并排在最前面。
 *
 * 后四个(关系网 / 记忆 / 心智 / 档案)回答的是另一个问题: **她是谁**。它们是慢的、
 * 累积的。把这两半混排, 就会出现"她的态度"和"她的 intimacy 字段"并排摆在一屏里。
 *
 * 原始 JSON 没有消失, 它被**收敛**到最后一个「档案」页里 —— 界面上一旦有两处能看见
 * 原始返回体, 每一页都会退化成 JSON 查看器。这条约束由 `studio.test.ts` 钉着。
 *
 * <h2>两套归属, 一个 id</h2>
 *
 * 这一页读的全部是 **Studio 面**(用户 JWT, server:8091)。页头那份档案来自
 * `GET /api/companions/{id}`, 它按"你是不是这个人"过滤 —— 与 Agents 页右栏那套
 * "你是不是这个 API 客户端"是两条独立的规则。
 *
 * 两条路径里的 id 是**同一个值**(`companions.id`), 所以从哪一栏点进来都落到这里。
 * 而账号ID(`agent_…`)是聊天平台侧的标识, 它只在这一页的页头出现, 且**不可修改**。
 *
 * <h2>为什么选中项在 URL 里</h2>
 *
 * 标签页落在 `?tab=`, 与之前 `?id=` 的理由一样: 刷新、分享链接、从别处返回都还停在
 * 同一处。`tabOf()` 会把拼错的 tab(以及重做之前那六个旧 id)收敛回默认页, 而不是
 * 弹一整页错误。
 */
export function AgentDetail() {
  return (
    <RequireStudio why="这一页读的是她的记忆、关系和日常 —— 属于个人数据, 只对本人开放。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { agentId = '' } = useParams()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tab = tabOf(params.get('tab'))
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)
  // 删掉之后**回列表**, 不留在原地刷新: 那一刷会去打一个刚被软删的 id, 页面要么
  // 报错要么显示一份已经不存在的档案 —— 两种都不如实说"它没了, 我们回列表吧"。
  const backToList = () => navigate('/agents', { replace: true })

  // 页头与「身份」页共用这一条档案 —— 分两次取会在极端情况下让两处显示不同的名字。
  const { data: companion, loading, error, reload } = useAsync(
    () => getCompanion(agentId), [agentId])

  function selectTab(next: string) {
    const p = new URLSearchParams(params)
    // 默认页不留 `?tab=` —— 一条"她的今天"的链接不该带一个多余的参数, 而且默认页
    // 哪天换了, 那些链接会自动跟到新默认页, 而不是钉死在旧的那一页上。
    if (next === DEFAULT_TAB) p.delete('tab')
    else p.set('tab', next)
    setParams(p, { replace: true })
  }

  /*
   * 开关放在**页头**, 不在某一个标签页里: "这个 agent 现在停着" 是看任何一页都该知道的事。
   * 把它塞进「总览」意味着用户在「记忆」页找一条不存在的记忆时, 没有任何线索告诉他
   * 原因是他上周把它关了。
   *
   * 切换之后**重取**而不是就地改: 页头显示的这一份数据同时被「身份」页的 RecordView 用着,
   * 就地改只会改页头那个对象, 两个地方就会各说各的。单条切换的代价只是一次档案请求。
   */
  async function toggleLifecycle() {
    if (!companion) return
    const next = companion.lifecycle === 'paused' ? 'active' : 'paused'
    setSwitching(true)
    setSwitchError(null)
    try {
      await setAgentLifecycle(companion.id, next)
      reload()
    } catch (e) {
      setSwitchError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setSwitching(false)
    }
  }

  const paused = companion?.lifecycle === 'paused'

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Link
          to="/agents"
          className="inline-flex items-center gap-1.5 rounded text-sm text-ink-soft transition-colors hover:text-ink"
        >
          <ArrowLeft size={14} />返回数字人列表
        </Link>
        <span className="flex items-center gap-2">
          {companion && (
            <Button
              variant="ghost"
              disabled={switching}
              title={paused ? '让她重新开始推进' : '停止推进, 不删任何东西'}
              onClick={() => void toggleLifecycle()}
            >
              {paused ? <Play size={13} /> : <Pause size={13} />}
              {paused ? '继续运行' : '停止运行'}
            </Button>
          )}
          <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
        </span>
      </div>

      <header className={`rounded-xl border px-5 py-4 ${
        paused ? 'border-warn/40 bg-raised' : 'border-line bg-raised'
      }`}>
        <ErrorNote error={error ? describeError(error) : null} />
        {switchError && <div className="mb-2"><ErrorNote error={switchError} /></div>}
        {loading && !companion && <Empty>读取中…</Empty>}
        {companion && (
          <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h1 className="text-lg font-medium text-ink">{companion.name || companion.id}</h1>
            <span className="font-mono text-xs text-ink-faint" title="她在本平台的编号, 不会变">
              {companion.id}
            </span>
            {companion.handle
              ? <span className="font-mono text-xs text-accent" title="聊天平台给她的账号ID">{companion.handle}</span>
              : <span className="text-xs text-warn">还没有聊天平台账号</span>}
            {paused && <Chip tone="warn">已停止</Chip>}
            <InfoTip label="上面这两个编号有什么区别" align="center">
              <span className="font-mono">id</span> 是<b>本平台</b>标识这个数字人的值,
              <span className="mx-1 font-mono">handle</span> 是聊天平台侧的账号ID。
              两者永不可互换 —— 前者不可变, 后者由系统分配、她自己也改不了。
            </InfoTip>
          </div>
        )}
        {paused && (
          <p className="mt-2 text-xs leading-relaxed text-ink-soft">
            已停止推进, 也不会再调用模型 —— 记忆、关系、没说完的话都还留着。
          </p>
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
              className={`shrink-0 whitespace-nowrap rounded-t border-b-2 px-3 py-2 text-sm transition-colors
                          focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
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

      {/*
        前四页是「她此刻」, 各由一个专门组件画 —— 它们的形状互不相同(时间轴 / 差异表 /
        推演台 / 仪表), 那正是它们必须排在最前面的原因: 打开一个 agent, 最该先看见的
        是"她今天怎么过", 而不是"这个对象有哪些字段"。
      */}
      {tab === 'today' && <Today agentId={agentId} />}
      {tab === 'plan' && <Plan agentId={agentId} />}
      {tab === 'phone' && <Phone agentId={agentId} />}
      {tab === 'body' && <BodyTab agentId={agentId} />}

      {tab === 'memory' && <MemoryTab agentId={agentId} />}

      {/* 前缀页: 组件先画上面那一块(它需要 userId, 表驱动给不了), 表里的面板照常画在下面。 */}
      {PREFIXED_TABS.includes(tab) && tab === 'relationship' && <Relation agentId={agentId} />}

      {sectionsOf(tab).map((spec) => (
        <Section key={spec.key} spec={spec} agentId={agentId} />
      ))}

      {/*
        档案页额外放一份**编译进她的人格**, 以及开放面操作。
        它只能是档案页: 那两份都是"给机器看的原文", 放在「今天」上会让一条时间轴
        旁边出现一个 JSON 折叠块, 而那正是这次重做要解决的东西。
      */}
      {tab === 'archive' && companion && (
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">她的档案(原文)</h2>
              <InfoTip label="这份原文怎么读">
                这是接口原样返回的完整档案。字段名保持后端的样子不改 ——
                你在日志、接口文档、数据库里看到的是同一批名字, 界面上换个说法会让两边对不上号。
              </InfoTip>
            </div>
          }
        >
          <RecordView value={companion} empty="读不到这个数字人的档案。" />
        </Panel>
      )}

      {tab === 'archive' && companion && <OpenApiActions companion={companion} onDeleted={backToList} />}
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
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">
            她记得的事 <span className="font-normal text-ink-faint tnum">({shown.length})</span>
          </h2>
          <InfoTip label="为什么搜不到某条记忆">
            搜索走服务端, 这里列表也只显示一屏 —— 不在这一屏里的记忆, 前端搜不到,
            但这不等于她忘了。
          </InfoTip>
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
          aria-label="搜索她的记忆"
          className="min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
        />
        {query && (
          <button
            type="button"
            onClick={() => { setInput(''); setQuery('') }}
            className="shrink-0 rounded text-xs text-ink-soft transition-colors hover:text-ink"
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
        <Empty>{query ? `没有匹配「${query}」的记忆。` : '她还没有记忆。'}</Empty>
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
      className={`rounded-md border px-2 py-0.5 font-mono text-xs transition focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
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
      <Panel title="改她的人格, 或删掉她">
        <Empty>
          手里没有客户端钥 —— 这一块用不了。
          <Link to="/access" className="ml-1 text-accent hover:underline">去「接口密钥」页填一把</Link>
          , 就能用它改写这个数字人的人格, 或把她删掉。
        </Empty>
      </Panel>
    )
  }

  async function submit() {
    if (!description.trim()) {
      setErr('先写一段新的描述 —— 平台会把它重编译成人格, 并落一个新版本。')
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
    if (!confirm(`删除「${companion.name || companion.id}」? 认知链不再推进她, 记录仍留在库里。`)) return
    try {
      await deleteAgent(companion.id)
      onDeleted()
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e))
    }
  }

  return (
    <Panel
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">改她的人格, 或删掉她</h2>
          <InfoTip tone="warn" label="这一块用的是哪张面">
            这一块走的是 <span className="font-mono">/api/v1/openapi/**</span>(客户端钥),
            与上面八个标签页走的内省接口是<b>两张不同的面</b> ——
            上面问的是"你是不是这个人", 这里问的是"你是不是这个 API 客户端"。
            同一个数字人, 两条独立的准入规则。
          </InfoTip>
        </div>
      }
      action={<Button variant="danger" onClick={() => void remove()}><Trash2 size={13} />删除</Button>}
    >
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); void submit() }}>
        <Field
          label="把她改成什么样"
          error={err}
          hint={
            <InfoTip label="为什么改人格要写一整段描述">
              每次更新都会落一个<b>人格新版本</b> —— 不改人格、只改关系是做不到的。
              旧版本不删, 可以到「档案」页对照。
            </InfoTip>
          }
        >
          <textarea
            className={`${inputClass} min-h-[88px] resize-y`}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="把这个人改成什么样…"
          />
        </Field>
        <Field
          label="为什么改 (可选)"
          hint={
            <InfoTip label="这一栏会被谁看到" align="center">
              它会跟着这次改动一起记下来, 之后能从人格版本里翻到。
            </InfoTip>
          }
        >
          <input
            className={inputClass}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="例: 用户希望她更健谈一些"
          />
        </Field>
        <div className="flex items-center gap-3">
          <Button type="submit" disabled={busy}>{busy ? '重编译中…' : '更新她的人格'}</Button>
          {done && (
            <span className="text-xs text-ok">
              已提交, 新版本已经落地 —— 切到「档案」页的「人格的每一版」看
            </span>
          )}
        </div>
      </form>
    </Panel>
  )
}
