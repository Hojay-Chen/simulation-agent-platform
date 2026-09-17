import { useEffect, useRef, useState } from 'react'
import { CheckCircle2, RefreshCw, XCircle } from 'lucide-react'
import {
  getMyHandle,
  listAgents,
  listClients,
  listCompanions,
  whoami,
  type HandleView,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'
import { Button, Chip, Empty, ErrorNote, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'

/**
 * System —— 控制台的四张面, 各自现在通不通。
 *
 * <h2>为什么这一页值得存在</h2>
 *
 * 这个控制台同时握着**四份互不相通的凭据**, 打**三个不同的后端**。任何一页出问题时,
 * 用户看到的都是一句 401 或一句空列表 —— 而那些症状几乎从不指向真正的原因:
 * 钥匙填错面、票过期了、8091 没起、聊天平台连不上。这四件事在这一页各占一行,
 * 一眼可以排除掉三个。
 *
 * 每一行都**真的发一次请求**, 不是读本地状态 —— "填了钥匙"与"钥匙有效"是两件事,
 * 而后者才是用户真正关心的。请求是幂等的只读调用。
 */
export function System() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  const studioToken = useSessionStore((s) => s.studioToken)
  const studioUser = useSessionStore((s) => s.studioUser)
  const [nonce, setNonce] = useState(0)

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-medium text-ink">System</h1>
          <p className="mt-0.5 text-xs text-ink-faint">四张面各发一次只读请求, 探的是"钥匙有没有用"</p>
        </div>
        <Button variant="ghost" onClick={() => setNonce((n) => n + 1)}>
          <RefreshCw size={13} />全部重探
        </Button>
      </div>

      <Panel title="连通性">
        <div className="divide-y divide-line">
          <Probe
            key={`probe-health-${nonce}`}
            label="探活"
            upstream="openapi:8092 · GET /api/health"
            note="不带任何凭据 —— 探活不该依赖钥匙是否有效"
            run={async () => {
              const r = await fetch('/api/health', { headers: { Accept: 'application/json' } })
              if (!r.ok) throw new Error(`HTTP ${r.status}`)
              return '在'
            }}
          />
          <Probe
            key={`probe-admin-${nonce}`}
            label="管理面"
            upstream="openapi:8092 · X-Admin-Key"
            note={adminKey ? '已填管理钥' : '未填管理钥 —— 到 API 页填入'}
            run={async () => `${(await listClients()).length} 个客户端`}
          />
          <Probe
            key={`probe-client-${nonce}`}
            label="客户端面"
            upstream="openapi:8092 · Bearer sap_…"
            note={clientKey ? '已填客户端钥' : '未填客户端钥 —— 到 API 页发放并填入'}
            run={async () => `${(await listAgents()).length} 个 agent`}
          />
          <Probe
            key={`probe-studio-${nonce}`}
            label="内省面"
            upstream="server:8091 · Bearer <用户 JWT>"
            note={studioToken
              ? `已登录${studioUser ? ` (${studioUser})` : ''} —— 票由**聊天平台**签发`
              : '未登录 —— 首页 / agent 详情 / 应用目录都要它'}
            run={async () => `${(await listCompanions()).length} 个 agent`}
          />
          <Probe
            key={`probe-auth-${nonce}`}
            label="登录面"
            upstream="chat-platform:8081 · /api/auth"
            note={studioToken ? '正在用这张票问"我是谁"' : '未登录 —— 没有票可验'}
            run={async () => {
              const me = await whoami()
              return me.nickname || me.username || me.id || '有效'
            }}
          />
        </div>
      </Panel>

      <MyHandle />

      <Panel title="为什么是四张面">
        <ul className="space-y-3 text-sm leading-relaxed text-ink-soft">
          <li>
            <strong className="text-ink">管理面 / 客户端面</strong>是平台对外的开放面
            (server:8092)。一个程序拿一把 <span className="font-mono">sap_</span> 钥匙就能
            自助建 agent —— 这两张面**不认人**, 只认钥匙。
          </li>
          <li>
            <strong className="text-ink">内省面</strong>打的是 server:8091, 要用户 JWT。
            记忆、关系、生活、认知链全都只在这张面上 —— 8092 的包白名单是冻结的,
            认知链的包永远不会进去(那是 8091 的职责)。
          </li>
          <li>
            <strong className="text-ink">登录面</strong>不在本平台, 在**聊天平台**:
            本平台没有账号系统, <span className="font-mono">users</span> 表由聊天平台拥有,
            本平台只读。两仓共享同一个 JWT 密钥, 所以那边签的票这边认 —— 这就是
            "两个平台完全独立、只通过接口往来"在身份上的样子。
          </li>
        </ul>
        <p className="mt-4 text-xs leading-relaxed text-ink-faint">
          四张面同时在手是常态而不是例外: 运维一边给自己发钥匙, 一边用本人的身份看某个
          agent 的记忆。把它们合并成一个"登录", 就等于强迫三份不同的委托关系共用一个身份 ——
          而那正是这套分层要避免的事。
        </p>
      </Panel>
    </div>
  )
}

interface ProbeState {
  status: 'running' | 'ok' | 'fail'
  detail: string
  ms: number
}

/** 一次只读探测。**失败也要说清楚失败在哪** —— 状态灯旁边没有字就只是个装饰。 */
function Probe({ label, upstream, note, run }: {
  label: string
  upstream: string
  note: string
  run: () => Promise<string>
}) {
  const [state, setState] = useState<ProbeState>({ status: 'running', detail: '', ms: 0 })
  // `run` 是**每次渲染都新建**的箭头函数。把它放进依赖数组会得到一个死循环:
  // 探测写完 state → 重渲染 → run 变了 → 再探一次 → ……。而"重探"这件事本来就
  // 由外层换 key(每次点刷新换一个)完成 —— 那才是重新挂载, 这里只需探一次。
  const runRef = useRef(run)
  runRef.current = run

  useEffect(() => {
    let alive = true
    const t0 = performance.now()
    setState({ status: 'running', detail: '', ms: 0 })
    runRef.current()
      .then((detail) => {
        if (alive) setState({ status: 'ok', detail, ms: Math.round(performance.now() - t0) })
      })
      .catch((e: unknown) => {
        if (alive) setState({ status: 'fail', detail: describeError(e), ms: Math.round(performance.now() - t0) })
      })
    return () => { alive = false }
  }, [])

  return (
    <div className="flex flex-wrap items-start gap-3 py-3">
      <span className="mt-0.5 shrink-0">
        {state.status === 'ok' && <CheckCircle2 size={16} className="text-ok" />}
        {state.status === 'fail' && <XCircle size={16} className="text-danger" />}
        {state.status === 'running' && <span className="block h-4 w-4 animate-pulse rounded-full bg-sunken" />}
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-2 text-sm text-ink">
          {label}
          <span className="font-mono text-xs text-ink-faint">{upstream}</span>
          {state.status !== 'running' && (
            <span className="text-xs text-ink-faint">{state.ms}ms</span>
          )}
        </p>
        <p className="mt-0.5 text-xs leading-relaxed text-ink-faint">{note}</p>
        {state.status === 'ok' && (
          <p className="mt-1 text-xs text-ok">{state.detail}</p>
        )}
        {state.status === 'fail' && (
          <p className="mt-1 text-xs leading-relaxed text-danger">{state.detail}</p>
        )}
      </div>
    </div>
  )
}

/**
 * 我自己的账号ID 与改号配额 —— **只读展示**。
 *
 * 改号本身在聊天平台那一侧(它是账号的拥有者), 控制台这里只把"还剩几次"摆出来:
 * 配额是 3 次/年, 而"还剩几次"这件事在别处看不到, 等发现的时候通常已经改完了。
 */
function MyHandle() {
  const studioToken = useSessionStore((s) => s.studioToken)
  const [view, setView] = useState<HandleView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!studioToken) return
    let alive = true
    getMyHandle()
      .then((v) => { if (alive) { setView(v); setError(null) } })
      .catch((e: unknown) => { if (alive) setError(describeError(e)) })
    return () => { alive = false }
  }, [studioToken])

  return (
    <Panel title="我的账号ID">
      <ErrorNote error={error} />
      {!studioToken && <Empty>登录后可见 —— 它属于你这个人, 不属于任何一个 agent。</Empty>}
      {studioToken && view && (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <code className="rounded-lg border border-line bg-sunken px-3 py-1.5 font-mono text-sm text-ink">
              {view.handle}
            </code>
            <Chip tone={view.remaining > 0 ? 'neutral' : 'warn'}>
              今年还可改 {view.remaining} 次 (共 {view.limit})
            </Chip>
          </div>
          <p className="mt-3 text-xs leading-relaxed text-ink-faint">
            这是**你**的账号ID, 由聊天平台发放、你自己可以改(每年 {view.limit} 次)。
            agent 的账号ID 是另一回事: 它以 <span className="font-mono">agent_</span> 开头、
            由系统分配、**永远不可修改** —— 改号入口在聊天平台, 不在这里。
            {view.nextChangeAt && (
              <span className="mt-1 block">下次可改时间: {view.nextChangeAt}</span>
            )}
          </p>
        </>
      )}
      {studioToken && !view && !error && <Empty>读取中…</Empty>}
    </Panel>
  )
}
