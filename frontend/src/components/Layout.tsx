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
} from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import { Chip } from './ui'
import { ApiKeyTip, StudioAccountChip } from './StudioLogin'

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
 *
 * <h2>栏目名是中文, 而且说的是"用户能拿它干什么"</h2>
 *
 * 这一版把栏目名整个改过一遍。旧名是混语言的(`Agents` / `API` / `System` 夹在
 * 「总览」「事件流」之间), 而且有几条说的是**实现**而不是**用处**: 「运行时」听起来像
 * 一个进程, 用户想知道的是"它现在跑得好不好"; 「应用目录」听起来像文件系统, 那一页
 * 真正的意思是"平台能给她提供哪些能力"。
 *
 * **路径一律没动**(`/agents`、`/runtime`、`/access` …) —— 改的只是给人看的字。
 * 地址栏、书签、`data-testid` 都还是原来那些, 所以这是一次纯文案改动。
 */
const NAV_GROUPS = [
  {
    id: 'her',
    label: '她的近况',
    items: [
      { to: '/', label: '总览', icon: LayoutDashboard, end: true },
      // 面向用户的词是「数字人」而不是「Agent」: 这一栏下面是**人**, 不是进程。
      { to: '/agents', label: '数字人', icon: Bot, end: false },
    ],
  },
  {
    id: 'ops',
    label: '平台运维',
    items: [
      { to: '/events', label: '事件流', icon: Activity, end: false },
      // 「运行时」→「运行状态」: 前者是名词(一个东西), 后者是问题(它怎么样了)
      { to: '/runtime', label: '运行状态', icon: Gauge, end: false },
      // 「应用目录」→「应用能力」: 这一页列的是平台**能提供什么**, 不是一份文件清单
      { to: '/applications', label: '应用能力', icon: Boxes, end: false },
      // 路径是 /access 而不是 /api —— `/api/**` 归后端, SPA 占它会让"刷新这一页"变成
      // 拿 document 请求打后端(403)。栏目名说的是用户来这儿干什么: 管钥匙。
      { to: '/access', label: '接口密钥', icon: KeyRound, end: false },
      { to: '/system', label: '系统设置', icon: Server, end: false },
    ],
  },
] as const

/**
 * 控制台外壳: 顶部栏 + 侧边导航。
 *
 * <h2>页头那三盏灯是这个控制台最重要的东西</h2>
 *
 * 它同时握着**四份互不相通的凭据**, 而任何一页出问题的症状都是一句 401 或一个空
 * 列表 —— 那些症状几乎从不指向真正的原因。所以三盏灯(管理钥 / 客户端钥 / 账号)
 * 常驻在每一个页面上: 缺哪一份, 对应的那几张面就是死的, 而这个事实必须随时看得见。
 *
 * (这一条从第一版就留着, 是对的 —— 只是从两盏灯长到了三盏。)
 *
 * <h2>侧栏底部那段话被拿掉了, 内容没丢</h2>
 *
 * 那段话讲的是"哪一栏读哪个端口、要哪种凭据" —— 对来查故障的人有用, 对其他人是噪音,
 * 而它占了侧栏底部整整六行。现在这段解释挂在那三盏灯的问号上(`ApiKeyTip` /
 * `StudioAccountChip` 里的 InfoTip): **需要的人问得到, 不需要的人面前只有导航**。
 * 四张面的完整清单仍然在「系统设置」页里。
 */
export function Layout() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  // 账号那一盏(以及退出登录)整个搬进了 `StudioAccountChip` —— 它自己读 store。
  // 这里不再多读一遍: 同一个值在两层各读一次, 只会让"谁负责渲染它"变得含糊。
  const { theme, toggle } = useThemeStore()

  return (
    <div className="flex min-h-screen flex-col bg-surface">
      {/*
        跳转链接 —— 键盘用户按第一下 Tab 就会遇到它。
        页头有三盏状态灯、一个主题开关, 侧栏有七条导航, 加起来十几次 Tab 才能到正文。
        它平时是 `sr-only`(只有读屏和键盘看得见), 获得焦点时才现形。
        **这一条不占任何视觉空间**, 但没有它, 键盘用户的每一次翻页都要先穿过整个外壳。
      */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-lg focus:border focus:border-line focus:bg-raised focus:px-3 focus:py-2 focus:text-sm focus:text-ink focus:shadow-pop"
      >
        跳到正文
      </a>
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
          {/* 两把钥匙的说明挂在这个问号上 —— 侧栏底部那六行话搬到了这里 */}
          <ApiKeyTip />
          {/* 第三盏: 账号票是**聊天平台**发的, 所以未登录时给的是"登录", 不是"注册" */}
          <StudioAccountChip />
          <button
            type="button"
            onClick={toggle}
            aria-label={theme === 'dark' ? '切到亮色' : '切到深色'}
            title={theme === 'dark' ? '切到亮色' : '切到深色'}
            className="grid h-7 w-7 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
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
              {/*
                `uppercase` / `tracking-widest` 去掉了: 它们是给拉丁字母排小 caps 用的,
                中文既不会被 uppercase 影响, tracking-widest 还会把四个字拉得七零八落。
                字号从 10px 提到 11px、颜色从 ink-faint 提到 ink-soft 是为了对比度 ——
                分组标签是**导航的一部分**, 不是装饰。
              */}
              <p className="hidden px-3 pb-1 text-[11px] font-medium tracking-wide text-ink-soft md:block">
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
          {/*
            这里原来有六行解释"哪一栏读哪个端口、要哪种凭据"。它讲的是实现, 而侧栏是
            用来**跳转**的 —— 一段读一次就够的说明挂在每次都要看的导航下面, 是拿最贵的
            位置放最便宜的内容。同一条信息现在挂在页头那三盏灯的问号上, 而"四张面的
            完整清单"本来就在「系统设置」页里。
          */}
        </nav>

        {/*
          正文加了宽度上限。以前没有 —— 在 2560px 的显示器上, 内容会从屏幕最左一直
          铺到最右, 一行文字长到眼睛找不到下一行的开头, 表格的列被拉成两条相距半米的
          竖线。上限给得很宽(1600px), 因为这里的页面确实有宽表 —— 它不是要收窄内容,
          只是**不让内容无限摊开**。窄屏下 `w-full` 让这条限制完全不起作用。
        */}
        <main id="main" className="min-w-0 flex-1 p-4 md:p-6">
          <div className="mx-auto w-full max-w-[1600px]">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
