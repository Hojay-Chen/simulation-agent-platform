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
import { Button, Chip, Empty, ErrorNote, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'

/**
 * 「系统设置」—— 控制台的四份凭据, 各自现在能不能用。
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
 *
 * <h2>页面上只剩数据, 说明书挂在问号上</h2>
 *
 * 原来这一页底部有一整块「为什么是四张面」的面板 —— 三段话讲四份凭据的分工。
 * 那段内容一个字没删, 它现在挂在这一页标题的问号上: 第一次来的人点得到, 查故障的人
 * 面前只有五行实测结果。同理, 每一行的解释也从常驻的一行灰字改成了它自己的问号。
 */
export function System() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  const studioToken = useSessionStore((s) => s.studioToken)
  const studioUser = useSessionStore((s) => s.studioUser)
  const [nonce, setNonce] = useState(0)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex min-w-0 items-center gap-1.5">
            <h1 className="text-lg font-medium text-ink">系统设置</h1>
            <InfoTip label="这一页在检查什么" align="start">
              <CredentialExplainer />
            </InfoTip>
          </div>
          <p className="mt-0.5 text-xs text-ink-faint">
            每行各发一次只读请求 —— 填了钥匙, 不等于钥匙有效。
          </p>
        </div>
        <Button variant="ghost" onClick={() => setNonce((n) => n + 1)}>
          <RefreshCw size={13} />全部重探
        </Button>
      </div>

      <Panel title="每份凭据现在能不能用">
        <div className="divide-y divide-line">
          <Probe
            key={`probe-health-${nonce}`}
            label="服务在线吗"
            upstream="openapi:8092 · GET /api/health"
            note="不需要凭据"
            tip="探活故意不带任何钥匙 —— 否则「服务活着吗」会变成「我的钥匙还有效吗」, 两件事就分不开了。"
            run={async () => {
              const r = await fetch('/api/health', { headers: { Accept: 'application/json' } })
              if (!r.ok) throw new Error(`HTTP ${r.status}`)
              return '在'
            }}
          />
          <Probe
            key={`probe-admin-${nonce}`}
            label="能发新钥匙吗"
            upstream="openapi:8092 · X-Admin-Key"
            note={adminKey ? '已填管理钥' : '未填管理钥 —— 到「接口密钥」页填入'}
            tip="管理钥用于发放和吊销客户端钥。缺它不会让别的行变红, 只是这一行发不出新钥匙。"
            run={async () => `${(await listClients()).length} 个客户端`}
          />
          <Probe
            key={`probe-client-${nonce}`}
            label="能建数字人吗"
            upstream="openapi:8092 · Bearer sap_…"
            note={clientKey ? '已填客户端钥' : '未填客户端钥 —— 到「接口密钥」页发放并填入'}
            tip="客户端钥是某个第三方客户端的身份。平台开放面上建 / 管 / 用数字人都靠它。"
            run={async () => `${(await listAgents()).length} 个数字人`}
          />
          <Probe
            key={`probe-studio-${nonce}`}
            label="能读她那一侧吗"
            upstream="server:8091 · Bearer <用户 JWT>"
            note={studioToken
              ? `已登录${studioUser ? ` (${studioUser})` : ''}`
              : '未登录 —— 总览 / 数字人详情 / 应用能力都要它'}
            tip="记忆、关系、生活、认知链只在她那一侧(server:8091), 要账号票。票由聊天平台签发。"
            run={async () => `${(await listCompanions()).length} 个数字人`}
          />
          <Probe
            key={`probe-auth-${nonce}`}
            label="账号票还有效吗"
            upstream="chat-platform:8081 · /api/auth"
            note={studioToken ? '正在用这张票问"我是谁"' : '未登录 —— 没有票可验'}
            tip="拿账号票去聊天平台问一次「我是谁」。票没过期、聊天平台在, 这行才绿。"
            run={async () => {
              const me = await whoami()
              return me.nickname || me.username || me.id || '有效'
            }}
          />
        </div>
      </Panel>

      <MyHandle />
    </div>
  )
}

/**
 * 四份凭据的分工。
 *
 * 原来它是页面底部一整块面板(`为什么是四张面`), 现在挂在标题的问号上 ——
 * 文字一个字没删, 只是从常驻改成按需。内容偏技术是有意的: 会来问这个问题的人,
 * 要的正是"哪把钥匙开哪张门"这句话本身。
 */
function CredentialExplainer() {
  return (
    <>
      <b className="font-medium text-ink">平台开放面(server:8092)—— 两把钥匙</b>
      <br />
      管理钥签发客户端钥; 客户端钥建 / 管 / 用数字人。这两张面不认人, 只认钥匙 ——
      一个程序拿着它就能自助建数字人, 不需要登录。
      <br />
      <br />
      <b className="font-medium text-ink">她那一侧(server:8091)—— 账号票</b>
      <br />
      记忆、关系、生活、认知链全都只在这张面上。开放面的白名单是冻结的, 那些数据
      永远不会从 8092 出去。
      <br />
      <br />
      <b className="font-medium text-ink">登录(聊天平台)</b>
      <br />
      本平台没有账号系统, users 表由聊天平台拥有、本平台只读; 两仓共享同一个 JWT 密钥,
      所以那边签的票这边认。
      <br />
      <br />
      四份凭据同时在手是常态而不是例外: 运维一边给自己发钥匙, 一边用本人的身份看某个
      数字人的记忆。把它们合并成一个"登录", 就等于强迫三份不同的委托关系共用一个身份。
    </>
  )
}

interface ProbeState {
  status: 'running' | 'ok' | 'fail'
  detail: string
  ms: number
}

/** 一次只读探测。**失败也要说清楚失败在哪** —— 状态灯旁边没有字就只是个装饰。 */
function Probe({ label, upstream, note, tip, run }: {
  label: string
  upstream: string
  note: string
  /** 这一行探的是什么、缺了它会怎样 —— 挂在标签的问号上, 不占常驻版面。 */
  tip: string
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
        <p className="flex flex-wrap items-center gap-2 text-sm text-ink">
          <span>{label}</span>
          <InfoTip label={`「${label}」这一行在探什么`}>{tip}</InfoTip>
          <span className="font-mono text-xs text-ink-faint">{upstream}</span>
          {state.status !== 'running' && (
            <span className="font-mono text-xs text-ink-faint tnum">{state.ms}ms</span>
          )}
        </p>
        <p className="mt-0.5 text-pretty text-xs leading-relaxed text-ink-faint">{note}</p>
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
    <Panel
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">我的账号ID</h2>
          <InfoTip label="「我的账号ID」是什么">
            这是<b className="font-medium text-ink">你</b>的账号ID, 由聊天平台发放、
            你自己可以改(每年有次数上限, 见右边的剩余次数)。改号入口在聊天平台, 不在这里。
            <br />
            <br />
            数字人的账号ID 是另一回事: 它以 <span className="font-mono">agent_</span> 开头、
            由系统分配、<b className="font-medium text-ink">永远不可修改</b>。
          </InfoTip>
        </div>
      }
    >
      <ErrorNote error={error} />
      {!studioToken && <Empty>登录后可见 —— 它属于你这个人, 不属于任何一个数字人。</Empty>}
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
          {view.nextChangeAt && (
            <p className="mt-3 text-xs text-ink-faint">
              下次可改时间: <span className="font-mono tnum">{view.nextChangeAt}</span>
            </p>
          )}
        </>
      )}
      {studioToken && !view && !error && <Empty>读取中…</Empty>}
    </Panel>
  )
}
