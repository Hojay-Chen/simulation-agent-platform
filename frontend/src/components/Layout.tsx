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
      <header className="flex items-center justify-between gap-4 border-b border-line bg-raised px-6 py-3">
        <div className="flex items-center gap-2.5">
          <span className="grid h-7 w-7 place-items-center rounded-lg bg-accent text-accent-ink">
            <Bot size={16} />
          </span>
          <span className="text-sm font-medium tracking-wide text-ink">
            Luxera Simulation Agent
            <span className="ml-2 text-ink-faint">控制台</span>
          </span>
        </div>
        <div className="flex items-center gap-2">
          {adminKey
            ? <Chip tone="ok"><ShieldCheck size={12} className="mr-1" />管理钥已配</Chip>
            : <Chip tone="warn"><ShieldAlert size={12} className="mr-1" />缺管理钥</Chip>}
          {clientKey
            ? <Chip tone="ok"><KeyRound size={12} className="mr-1" />客户端钥已配</Chip>
            : <Chip tone="warn"><KeyRound size={12} className="mr-1" />缺客户端钥</Chip>}
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

      <div className="flex flex-1">
        <nav className="w-52 shrink-0 border-r border-line bg-sunken p-3">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                `mb-1 flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors ${
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
          <p className="mt-6 px-3 text-xs leading-relaxed text-ink-faint">
            agent 详情页里的「实时状态」由
            <span className="mx-1 font-mono text-ink-soft">server:8091</span>
            的认知链持续写入, 控制台读同一张表。
          </p>
        </nav>

        <main className="min-w-0 flex-1 p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
