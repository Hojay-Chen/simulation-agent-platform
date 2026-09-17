import { useSearchParams } from 'react-router-dom'
import { BookOpen, KeyRound, Users } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { Clients } from './Clients'
import { Connect } from './Connect'
import { Docs } from './Docs'
import { Panel } from '@/components/ui'

/**
 * API —— 平台对外的接口面, 三个标签页: 客户端 / 凭据 / 接口文档。
 *
 * <h2>为什么合成一页</h2>
 *
 * 这三件事是一条链上的前后脚: 用管理钥发一个客户端 → 把拿到的 `sap_` 填进凭据 →
 * 照着文档调。原来它们是导航里两个独立入口, 结果是"发完钥匙不知道该填到哪",
 * 而那个问题的答案就在隔壁那一页。
 *
 * 标签页落在 `?tab=`, 所以「到凭据页去填」这种跨页提示可以是一个**能直接点过去的
 * 链接** —— 而不是让用户自己回到导航里找。
 */
const TABS = [
  { id: 'clients', label: '客户端', icon: Users },
  { id: 'connect', label: '凭据', icon: KeyRound },
  { id: 'docs', label: '接口文档', icon: BookOpen },
] as const

const TAB_IDS = TABS.map((t) => t.id) as readonly string[]

function tabOf(raw: string | null): (typeof TABS)[number]['id'] {
  return TAB_IDS.includes(raw ?? '') ? (raw as (typeof TABS)[number]['id']) : 'clients'
}

export function ApiPortal() {
  const [params, setParams] = useSearchParams()
  const tab = tabOf(params.get('tab'))
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)

  function select(id: string) {
    const p = new URLSearchParams(params)
    if (id === 'clients') p.delete('tab')
    else p.set('tab', id)
    setParams(p, { replace: true })
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-lg font-medium text-ink">API</h1>
        <p className="text-xs text-ink-faint">
          管理钥 {adminKey ? '已配' : '未配'} · 客户端钥 {clientKey ? '已配' : '未配'}
        </p>
      </div>

      <nav className="flex gap-1 overflow-x-auto border-b border-line">
        {TABS.map(({ id, label, icon: Icon }) => {
          const active = id === tab
          return (
            <button
              key={id}
              type="button"
              onClick={() => select(id)}
              aria-current={active ? 'page' : undefined}
              className={`flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm transition-colors ${
                active ? 'border-accent text-accent' : 'border-transparent text-ink-soft hover:text-ink'
              }`}
            >
              <Icon size={14} />
              {label}
            </button>
          )
        })}
      </nav>

      {tab === 'clients' && <Clients />}
      {tab === 'connect' && <Connect />}
      {tab === 'docs' && <Docs />}

      <Panel title="这一页管的是哪张面">
        <p className="text-xs leading-relaxed text-ink-soft">
          本平台对外的接口是
          <span className="mx-1 font-mono text-ink">/api/v1/openapi/**</span>
          (server:8092), 两张钥匙面: 管理钥(<span className="font-mono">X-Admin-Key</span>)发客户端,
          客户端钥(<span className="font-mono">Bearer sap_…</span>)建/管/用 agent。
          开放面是**给任意第三方程序**的 —— 一个程序拿一把钥匙就能自助建 agent、写人格、读状态,
          不需要登录、不需要人在场。
        </p>
        <p className="mt-3 text-xs leading-relaxed text-ink-faint">
          控制台自己另外还有两张面(用户 JWT 的内省面、聊天平台的登录面), 它们只服务于这个界面,
          不在对外承诺里。四张面的完整清单见 System 页。
        </p>
      </Panel>
    </div>
  )
}
