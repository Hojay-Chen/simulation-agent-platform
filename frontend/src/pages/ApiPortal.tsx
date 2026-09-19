import { useSearchParams } from 'react-router-dom'
import { BookOpen, KeyRound, Users } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { Clients } from './Clients'
import { Connect } from './Connect'
import { Docs } from './Docs'
import { InfoTip } from '@/components/ui'

/**
 * 「接口密钥」—— 平台对外的接口面, 三个标签页: 客户端 / 填入密钥 / 接入说明。
 *
 * <h2>为什么合成一页</h2>
 *
 * 这三件事是一条链上的前后脚: 用管理钥发一个客户端 → 把拿到的 `sap_` 填进密钥栏 →
 * 照着说明调。原来它们是导航里两个独立入口, 结果是"发完钥匙不知道该填到哪",
 * 而那个问题的答案就在隔壁那一页。
 *
 * 标签页落在 `?tab=`, 所以「到填入密钥那一页去填」这种跨页提示可以是一个**能直接
 * 点过去的链接** —— 而不是让用户自己回到导航里找。
 *
 * <h2>原来底下那一整块说明面板没了, 内容挂在标题的问号上</h2>
 *
 * 这一页原来自带一块「这一页管的是哪张面」—— 两段话讲开放面、两把钥匙、以及控制台
 * 自己另外用的两张面。它是这个控制台"堆了一堆介绍文本"最典型的一处: 用户是来发钥匙
 * 的, 却要在三个标签页下面先读两段架构说明。现在那两段话挂在标题的问号上,
 * 并且**只指向别处已有的解释**(页头那盏灯的问号、系统设置页), 不再重讲一遍。
 */
const TABS = [
  { id: 'clients', label: '客户端', icon: Users },
  { id: 'connect', label: '填入密钥', icon: KeyRound },
  { id: 'docs', label: '接入说明', icon: BookOpen },
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
        <div className="flex min-w-0 items-center gap-1.5">
          <h1 className="text-lg font-medium text-ink">接口密钥</h1>
          <InfoTip label="「接口密钥」这一页管的是哪张面">
            <b className="font-medium text-ink">平台开放面</b>(server:8092,
            <span className="font-mono">/api/v1/openapi/**</span>)—— 给任意第三方程序用的那张面。
            <br />
            它有两把互不相通的钥匙: 管理钥发客户端, 客户端钥建 / 管 / 用数字人。
            <br />
            <br />
            页头那盏钥匙灯的问号里写着这两把钥匙分别是谁的; 四份凭据的完整清单在「系统设置」页。
            控制台自己另外用的两张面(账号票、登录)只服务于这个界面, 不在对外承诺里。
          </InfoTip>
        </div>
        <p className="text-xs text-ink-faint">
          管理钥{adminKey ? '已填' : '未填'} · 客户端钥{clientKey ? '已填' : '未填'}
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
              className={`flex shrink-0 items-center gap-1.5 rounded-t-md border-b-2 px-3 py-2 text-sm transition-colors
                          focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
                active ? 'border-accent text-accent' : 'border-transparent text-ink-soft hover:bg-sunken hover:text-ink'
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
    </div>
  )
}
