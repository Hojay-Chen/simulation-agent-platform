import type { ReactNode } from 'react'
import { Navigate, NavLink, useLocation } from 'react-router-dom'
import { KeyRound, LogOut, UserCheck } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { Chip, InfoTip } from './ui'
import { SPA_PATHS } from '@/lib/routes'

/**
 * "这一页要用你的账号" —— 没登录就把人**送到登录页去**, 而不是在原地渲染一个表单。
 *
 * <h2>为什么从"就地渲染"改成了"送过去"</h2>
 *
 * 原来这里是直接把登录框画在正文中央的, 理由是"重定向会让'我没登录'和'这个页面没了'
 * 长得一样"。**那个理由是对的**, 但它证明的是"重定向不该静默", 不是"不该重定向"。
 *
 * 就地渲染的真实代价在别处: 那个表单被包在控制台外壳里 —— 页头挂着三盏灯、侧栏能点、
 * 导航能跳。于是屏幕上同时存在两句话, 一句说"请登录", 另一句说"你已经在里面了"。
 * 一个自相矛盾的界面比一个说明不清的界面更难用。
 *
 * 所以那个理由在这里被**接住**而不是被丢掉: `why` 和当前路径一起作为查询参数传给
 * `/login`, 登录页负责把"你为什么会被带到这儿"原样说给用户听。用户体验到的是同一句
 * 解释, 只是它现在出现在一扇真正的门后面。
 *
 * <h2>why 应该写"原因", 不写"你需要登录"</h2>
 *
 * 登录页本身已经把"要登录"说得很清楚了, 再重复一遍是噪音。所以这里传的是**为什么这
 * 一页需要身份**(数据在哪儿、那道门是谁的), 那才是用户看完能拿去判断的信息。
 */
export function RequireStudio({ children, why }: { children: ReactNode; why: string }) {
  const studioToken = useSessionStore((s) => s.studioToken)
  const location = useLocation()

  if (studioToken) return <>{children}</>

  // `next` 带上**当前完整地址**(含查询串), 登录完原样跳回去 —— 否则用户在 Runtime 页
  // 选了某个标签、被弹去登录, 回来会掉回第一个标签, 而他并没有做错什么。
  const query = new URLSearchParams({
    why,
    next: location.pathname + location.search,
  })
  return <Navigate to={`${SPA_PATHS.login}?${query}`} replace />
}

/**
 * 页头第三盏灯 —— 账号那一盏。
 *
 * <h2>为什么它和另外两盏长在一起</h2>
 *
 * 页头那三盏灯回答的是同一个问题: **"我现在手上缺哪一份凭据"**。缺哪一份, 对应的那几张
 * 面就是死的。账号这一盏要是挪到别处, 这个问题就得在两个地方各看一半。
 *
 * 三盏灯的差别只在文案, 形状一致 —— 状态灯不是装饰, 一致性就是它的全部价值。
 */
export function StudioAccountChip() {
  const studioToken = useSessionStore((s) => s.studioToken)
  const studioUser = useSessionStore((s) => s.studioUser)
  const signOut = useSessionStore((s) => s.signOut)

  if (!studioToken) {
    return (
      <NavLink to={SPA_PATHS.login} title="用你在聊天平台的账号登录">
        <Chip tone="warn">
          <UserCheck size={12} />
          <span className="ml-1 hidden lg:inline">未登录</span>
        </Chip>
      </NavLink>
    )
  }

  return (
    <span className="flex items-center gap-0.5">
      <Chip tone="ok">
        <UserCheck size={12} />
        <span className="ml-1 hidden lg:inline">{studioUser || '已登录'}</span>
      </Chip>
      <button
        type="button"
        onClick={signOut}
        aria-label="退出登录"
        title="退出登录 —— 只清这张身份票, 两把 API 钥匙不动"
        className="grid h-6 w-6 shrink-0 place-items-center rounded-md text-ink-faint transition-colors hover:bg-sunken hover:text-ink-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
      >
        <LogOut size={13} />
      </button>
      <InfoTip label="这一盏灯是什么" align="end" side="bottom">
        <b className="font-medium text-ink">账号身份票</b>
        <br />
        由聊天平台签发, 用来读她那一侧的数据(记忆、关系、生活、事件)。
        它和左边的两把 API 钥匙是三份<b className="font-medium text-ink">互不相通</b>的凭据 —— 退出登录只清这一张票。
      </InfoTip>
    </span>
  )
}

/**
 * 前两盏灯(管理钥 / 客户端钥)的解释。
 *
 * 它们和账号灯一样常驻页头, 却只有缩写名字, 而"管理钥""客户端钥"这两个词本身不说明
 * 任何事 —— 谁发的、能开哪扇门、缺了会怎样, 全得靠猜。原来这些是靠侧栏底部一整段
 * 话解释的, 现在挂在问号上。
 */
export function ApiKeyTip() {
  return (
    <InfoTip label="管理钥与客户端钥是什么" align="end" side="bottom">
      <b className="font-medium text-ink">两把 API 钥匙, 两个委托方</b>
      <br />
      <span className="font-mono text-[11px]">管理钥</span> 是平台管理员发给自己的, 能签发任意客户端钥;
      <br />
      <span className="font-mono text-[11px]">客户端钥</span> 是某个第三方客户端的身份。
      <br />
      缺管理钥 = 发不出新钥匙; 缺客户端钥 = 调不动开放面。在「系统设置」里填。
      <span className="mt-1.5 flex items-center gap-1 text-ink-faint">
        <KeyRound size={11} />
        两者与账号票互不相通
      </span>
    </InfoTip>
  )
}
