import { NavLink, Outlet } from 'react-router-dom'
import {
  Activity,
  Bot,
  Boxes,
  Gauge,
  LayoutDashboard,
  Moon,
  Server,
  Sun,
  KeyRound,
  ShieldCheck,
  ShieldAlert,
  UserCheck,
  UserX,
} from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import { Chip } from './ui'

/**
 * 侧栏的两组 —— 这一页的**唯一**职责是让"这是给谁的页面"在导航上就成立。
 *
 * <h2>为什么分组比排版重要</h2>
 *
 * 上面那两条回答的是"**她**今天怎么样", 下面那几条回答的是"**这套东西**跑得怎么样"。
 * 两组人被混在一条列表里时, 症状不是"页面难看", 而是**两边都点错**: 一个只是想知道
 * 她好不好的人点进「事件流」看到一屏 `USER_MESSAGE_NOTIFIED`; 一个来查线上故障的人
 * 先看到一张"她的一天"的时间轴。
 *
 * 分组标签的字很小 —— 它不是为了好看, 是为了让上面那两条与下面那几条在视觉上
 * 属于两件事。`App.tsx` 的路由表顺序与这里一致。
 */
const NAV_GROUPS = [
  {
    id: 'her',
    label: '看她',
    items: [
      { to: '/', label: '总览', icon: LayoutDashboard, end: true },
      { to: '/agents', label: 'Agents', icon: Bot, end: false },
    ],
  },
  {
    id: 'ops',
    label: '运维 / 开发',
    items: [
      { to: '/events', label: '事件流', icon: Activity, end: false },
      { to: '/runtime', label: '运行时', icon: Gauge, end: false },
      { to: '/applications', label: '应用目录', icon: Boxes, end: false },
      // 路径是 /access 而不是 /api —— `/api/**` 归后端, SPA 占它会让"刷新这一页"变成
      // 拿 document 请求打后端(403)。栏目名照旧叫 API, 那是给人看的。
      { to: '/access', label: 'API', icon: KeyRound, end: false },
      { to: '/system', label: 'System', icon: Server, end: false },
    ],
  },
] as const

/**
 * 控制台外壳: 顶部栏 + 侧边导航。
 *
 * <h2>页头那三盏灯是这个控制台最重要的东西</h2>
 *
 * 它同时握着**四份互不相通的凭据**, 而任何一页出问题的症状都是一句 401 或一个空
 * 列表 —— 那些症状几乎从不指向真正的原因。所以三盏灯(管理钥 / 客户端钥 / 登录)
 * 常驻在每一个页面上: 缺哪一份, 对应的那几张面就是死的, 而这个事实必须随时看得见。
 *
 * (这一条从第一版就留着, 是对的 —— 只是从两盏灯长到了三盏。)
 */
export function Layout() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  const studioToken = useSessionStore((s) => s.studioToken)
  const studioUser = useSessionStore((s) => s.studioUser)
  const { theme, toggle } = useThemeStore()

  return (
    <div className="flex min-h-screen flex-col bg-surface">
      <header className="flex items-center justify-between gap-3 border-b border-line bg-raised px-4 py-3 md:px-6">
        <div className="flex min-w-0 items-center gap-2.5">
          <NavLink to="/" className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-accent text-accent-ink">
            <Bot size={16} />
          </NavLink>
          <span className="truncate text-sm font-medium tracking-wide text-ink">
            Being Studio
            {/* 窄屏放不下 —— 它本来也只是个定语, 去掉不影响这句话的意思 */}
            <span className="ml-2 hidden text-ink-faint sm:inline">仿真 Agent 控制台</span>
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {/* 灯的名字在窄屏缩成图标 —— 但状态灯本身**不**缩掉 */}
          {adminKey
            ? <Chip tone="ok"><ShieldCheck size={12} /><span className="ml-1 hidden lg:inline">管理钥</span></Chip>
            : <Chip tone="warn"><ShieldAlert size={12} /><span className="ml-1 hidden lg:inline">缺管理钥</span></Chip>}
          {clientKey
            ? <Chip tone="ok"><KeyRound size={12} /><span className="ml-1 hidden lg:inline">客户端钥</span></Chip>
            : <Chip tone="warn"><KeyRound size={12} /><span className="ml-1 hidden lg:inline">缺客户端钥</span></Chip>}
          {/* 第三盏: 票是**聊天平台**发的, 所以未登录时给的链接是"用你的账号登录"而不是"注册" */}
          {studioToken
            ? <Chip tone="ok"><UserCheck size={12} /><span className="ml-1 hidden lg:inline">{studioUser || '已登录'}</span></Chip>
            : <NavLink to="/system"><Chip tone="warn"><UserX size={12} /><span className="ml-1 hidden lg:inline">未登录</span></Chip></NavLink>}
          <button
            type="button"
            onClick={toggle}
            title={theme === 'dark' ? '切到亮色' : '切到深色'}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
          >
            {theme === 'dark' ? <Moon size={15} /> : <Sun size={15} />}
          </button>
        </div>
      </header>

      {/*
        导航的两套形态, 理由与仓 1 的 TabBar 一样: 同一个组件两套 class, 不是两个组件。
        窄屏横排成一条, 宽屏才是左侧 208px 的竖栏 —— 原来窄屏也钉着 w-52, 375px 下
        只剩 167px 给内容, 每个字都要换行, 页面等于是坏的。控制台虽然主要在桌面上用,
        但"坏"和"没为此优化"是两回事。
      */}
      <div className="flex flex-1 flex-col md:flex-row">
        <nav className="flex shrink-0 items-center gap-1 overflow-x-auto border-b border-line bg-sunken p-2
                        md:w-52 md:flex-col md:items-stretch md:gap-0 md:overflow-visible md:border-b-0 md:border-r md:p-3">
          {NAV_GROUPS.map((g, gi) => (
            <div key={g.id} className={`flex shrink-0 items-center gap-1 md:flex-col md:items-stretch ${
              gi > 0 ? 'md:mt-4' : ''
            }`}>
              {/* 窄屏上分组标签会挤掉导航项 —— 那里靠一条竖线分隔, 而不是靠一行字 */}
              <p className="hidden px-3 pb-1 text-[10px] uppercase tracking-widest text-ink-faint md:block">
                {g.label}
              </p>
              {gi > 0 && <span className="mx-1 h-4 w-px shrink-0 bg-line md:hidden" aria-hidden="true" />}
              {g.items.map(({ to, label, icon: Icon, end }) => (
                <NavLink
                  key={to}
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    `flex shrink-0 items-center gap-2.5 whitespace-nowrap rounded-lg px-3 py-2 text-sm transition-colors
                     md:mb-1 md:shrink ${
                      isActive
                        ? 'bg-accent-soft text-accent'
                        : 'text-ink-soft hover:bg-raised hover:text-ink'
                    }`
                  }
                >
                  <Icon size={15} />
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
          <p className="mt-6 hidden px-3 text-xs leading-relaxed text-ink-faint md:block">
            「看她」那两条读的是
            <span className="mx-1 font-mono text-ink-soft">server:8091</span>
            的内省接口, 要用户 JWT; 「运维」那几条里 API 页读的是
            <span className="mx-1 font-mono text-ink-soft">openapi:8092</span>
            , 要钥匙。四张面的完整清单在 System。
          </p>
        </nav>

        <main className="min-w-0 flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
