import { Navigate, Route, Routes, useParams } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { Dashboard } from '@/pages/Dashboard'
import { Agents } from '@/pages/Agents'
import { AgentDetail } from '@/pages/AgentDetail'
import { Applications } from '@/pages/Applications'
import { ApiPortal } from '@/pages/ApiPortal'
import { System } from '@/pages/System'
import { Events } from '@/pages/ops/Events'
import { Runtime } from '@/pages/ops/Runtime'
import { SPA_PATHS } from '@/lib/routes'

/**
 * 路由表 —— 两拨人共用的一台控制台。
 *
 * <h2>两拨人, 不在一页里</h2>
 *
 * `/` 与 `/agents/**` 给**看她的人**: 她今天在做什么、她的打算被改动过没有、她的手机
 * 会不会吵到她、她身体怎么样。`/events`、`/runtime`、`/applications`、`/access`、
 * `/system` 给**运维与开发**: 线上发生了几件事、认知链花了多少、平台能提供什么、
 * 凭据在哪填。
 *
 * 这两拨人问的问题不一样, 而混在一页里的代价是**双向**的 —— 看她的人被一屏机器名
 * 劝退, 运维的人在时间轴上找不到"哪一条事件没登记"。所以它们不只是分组, 是两张面。
 *
 * <h2>这一页的路径叫 /access, 而不是 /api</h2>
 *
 * 因为 `/api/**` 是**后端的**命名空间: nginx 把它整段交给 8091/8092, vite 的 proxy
 * 也一样。SPA 一旦占了这个前缀下的地址, 直接访问或**刷新**那个页面就再也不是"加载
 * 前端", 而是"拿一个 document 请求去打后端" —— 现象是 403(后端答的), 而页面代码
 * 一行都没跑。它只在"从别的页面点链接过来"时看起来正常, 所以极易漏掉。
 *
 * 这条约束比它省下的那点命名美感重要: **SPA 的路由不许落在后端拥有的前缀之下**。
 * 栏目名仍然是「API」, 那是给人看的; 路径是给机器分的。
 *
 * <h2>旧地址一律 302 到新家, 不返回空白页</h2>
 *
 * `/clients` 与 `/connect` 现在分别是 `/access?tab=…` 的一个标签页; `/agents/:id/state`
 * 是详情页的「总览」。这些地址曾经真实存在过(在截图里、在别人的书签里), 让它们
 * 变成"什么都不显示"比留一条转发更糟 —— 用户会以为是站点坏了。
 */
export default function App() {
  // 凭据的装载**不在这里** —— 它在 main.tsx 里、render 之前。
  // 放在组件的 effect 里会晚于子组件的 effect(React 自下而上), 于是"挂载即取数"的
  // 页面会拿着空凭据发请求。详见 main.tsx 那段注释。

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path={SPA_PATHS.dashboard} element={<Dashboard />} />
        <Route path={SPA_PATHS.agents} element={<Agents />} />
        <Route path={`${SPA_PATHS.agents}/:agentId`} element={<AgentDetail />} />
        <Route path={SPA_PATHS.events} element={<Events />} />
        <Route path={SPA_PATHS.runtime} element={<Runtime />} />
        <Route path={SPA_PATHS.applications} element={<Applications />} />
        <Route path={SPA_PATHS.access} element={<ApiPortal />} />
        <Route path={SPA_PATHS.system} element={<System />} />

        {/* 旧地址 */}
        <Route path="/clients" element={<Navigate to="/access?tab=clients" replace />} />
        <Route path="/connect" element={<Navigate to="/access?tab=connect" replace />} />
        <Route path="/agents/:agentId/state" element={<StateRedirect />} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

/**
 * `/agents/:id/state` 当年是个独立页, 现在是详情页的第一个标签。
 *
 * 路径写全而不是用 `..`: 相对跳转的基准是"当前路径"还是"当前路由", 在嵌套路由下
 * 是两个不同的答案, 而这种地方出错的表现是"跳到了一个看起来对但其实是别的页"。
 */
function StateRedirect() {
  const { agentId = '' } = useParams()
  return <Navigate to={`/agents/${encodeURIComponent(agentId)}`} replace />
}
