import { NavLink, Outlet } from 'react-router-dom'
import { Bot, KeyRound, Moon, Sun, Users, ShieldCheck, ShieldAlert } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import { Chip } from './ui'

const NAV = [
  { to: '/', label: 'Agents', icon: Bot, end: true },
  { to: '/clients', label: 'API 客户端', icon: Users, end: false },
  { to: '/connect', label: '接入', icon: KeyRound, end: false },
] as const

/**
 * 控制台外壳: 顶部栏 + 侧边导航。
 *
 * 与仓 1 聊天前端的 IM 布局(会话侧栏)刻意不同 —— 这里是管理工具, 用户
 * 关心的第一件事是"我手里两把钥匙到位了没有", 所以顶部常驻两枚状态灯。
 * 缺哪把, 对应的那个面就是死的, 而这个事实必须在每一个页面都看得见。
 * (这一条是原设计里唯一被完整保留下来的东西 —— 它是对的, 只是换了一套视觉语言。)
 */
export function Layout() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  const { theme, toggle } = useThemeStore()

  return (
    <div className="flex min-h-screen flex-col bg-surface">
      <header className="flex items-center justify-between gap-3 border-b border-line bg-raised px-4 py-3 md:px-6">
        <div className="flex min-w-0 items-center gap-2.5">
          <span className="grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-accent text-accent-ink">
            <Bot size={16} />
          </span>
          <span className="truncate text-sm font-medium tracking-wide text-ink">
            Luxera Simulation Agent
            {/* 窄屏放不下 —— 它本来也只是个定语, 去掉不影响这句话的意思 */}
            <span className="ml-2 hidden text-ink-faint sm:inline">控制台</span>
          </span>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {/* 钥匙的名字在窄屏缩成图标 —— 但状态灯本身**不**缩掉, 见文件头 */}
          {adminKey
            ? <Chip tone="ok"><ShieldCheck size={12} /><span className="ml-1 hidden sm:inline">管理钥已配</span></Chip>
            : <Chip tone="warn"><ShieldAlert size={12} /><span className="ml-1 hidden sm:inline">缺管理钥</span></Chip>}
          {clientKey
            ? <Chip tone="ok"><KeyRound size={12} /><span className="ml-1 hidden sm:inline">客户端钥已配</span></Chip>
            : <Chip tone="warn"><KeyRound size={12} /><span className="ml-1 hidden sm:inline">缺客户端钥</span></Chip>}
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
        <nav className="flex shrink-0 gap-1 overflow-x-auto border-b border-line bg-sunken p-2
                        md:w-52 md:flex-col md:gap-0 md:overflow-visible md:border-b-0 md:border-r md:p-3">
          {NAV.map(({ to, label, icon: Icon, end }) => (
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
          <p className="mt-6 hidden px-3 text-xs leading-relaxed text-ink-faint md:block">
            agent 详情页里的「实时状态」由
            <span className="mx-1 font-mono text-ink-soft">server:8091</span>
            的认知链持续写入, 控制台读同一张表。
          </p>
        </nav>

        <main className="min-w-0 flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
