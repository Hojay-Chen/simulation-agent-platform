/**
 * 前端自己的路径表, 以及它与后端命名空间之间那条**不许越过**的界线。
 *
 * <h2>为什么这件事值得一个模块</h2>
 *
 * 控制台与后端 API **同源**(同一个 nginx server 块, 同一个 vite proxy)。于是有一个
 * 前缀是后端独占的: `/api/**`。SPA 只要在它下面挂一个页面, 那个页面就只在"从别的
 * 页面点链接过去"时正常 —— 因为那是客户端跳转, 一个网络请求都不发; 而**直接访问或
 * 刷新**它, 浏览器发的是 document 请求, nginx/vite 按前缀把它交给后端, 后端回 403,
 * 页面代码一行都没跑。
 *
 * 这个症状非常有迷惑性: 走一遍导航, 每一页都好。所以它得有个机器检查, 而不是靠
 * 下一个人记得。`routes.test.ts` 就是那个检查。
 */
import { ADMIN_FACE_PATH, AUTH_FACE_PATH, OPENAPI_FACE_PATH } from '@/api/client'

/**
 * 后端独占、SPA 不得占用的前缀。
 *
 * 前三条直接取自 `faceOf()` 的判定常量 —— 它们是"谁的面"的唯一来源, 抄一份常量值
 * 到这里, 那边改了这边不会跟着改, 测试就会开始说谎。最后一条 `/api` 是总管:
 * 三条面路径本来就都在它下面, 把它也列出来是因为**真正做分流的是它**(nginx 的
 * `location /api/` 与 vite 的 `'/api'` proxy 都只认这个前缀, 不认更细的面)。
 */
export const BACKEND_OWNED_PREFIXES = [
  ADMIN_FACE_PATH,
  OPENAPI_FACE_PATH,
  AUTH_FACE_PATH,
  '/api',
] as const

/**
 * SPA 的路径表。
 *
 * 只列**静态**的那几条(动态段如 `/agents/:agentId` 与旧地址转发不在内) —— 它们是
 * "用户可能直接敲进地址栏或收藏"的那些, 也就是会撞上前缀规则的那些。
 *
 * <h2>表里的顺序就是侧栏的顺序, 也是那张两栏分组的顺序</h2>
 *
 * 前两条(`/`、`/agents`)是**看她的人**用的, 其余是运维/开发用的。这个顺序在这个
 * 文件里只是排版, 在 `Layout.tsx` 的 `NAV_GROUPS` 里才是那件事本身 —— 但两边必须
 * 一致, 否则"侧栏看上去是两拨, 地址栏读上去是一串"。
 */
export const SPA_PATHS = {
  dashboard: '/',
  /**
   * 登录页 —— **不在控制台外壳里**。它是唯一一条不被 `<Layout />` 包住的路由。
   *
   * 放进这张表是因为它必须和别的路径一起被 `collidesWithBackend` 检查: 它也是用户会
   * 直接敲进地址栏的地址。它同时是 `RequireStudio` 的跳转目标, 所以两处引用同一个常量。
   */
  login: '/login',
  agents: '/agents',
  /** 运维面: 世界往她那儿递了什么。 */
  events: '/events',
  /** 运维面: 认知链的计数、轨迹、排程与降级。 */
  runtime: '/runtime',
  applications: '/applications',
  /** 栏目名是「接口密钥」, 路径必须是 /access —— 见文件头。 */
  access: '/access',
  system: '/system',
} as const

/**
 * 聊天平台的地址 —— 账号的**来源**, 所以登录页必须能指过去。
 *
 * 它**不进** `SPA_PATHS`: 那张表是"本 SPA 的路由", 而这是一个别人的站点。混进去有
 * 两个后果 —— `collidesWithBackend` 会拿它去比后端前缀(它压根不属于本 SPA), 而遍历
 * `SPA_PATHS` 的地方会试图把它渲染成一条内部导航。
 *
 * 登录页原来那条「先不登录, 回总览」是个**环**: 总览自己就要登录, 点过去被
 * `RequireStudio` 原样弹回, 只在 URL 上多留一句 why —— 用户"去"了一趟, 回到原地,
 * 还多挨一句像责备的话。那一页真正缺的出口不是"回总览"(回不去), 是"我没有账号,
 * 去哪儿弄一个"。
 */
export const CHAT_PLATFORM_URL = 'https://chat.luxera.top'

/**
 * 这个 SPA 路径是不是落在后端独占的前缀之下。
 *
 * 前缀比较用 `===` 或 `prefix + '/'`, 不用裸 `startsWith` —— 否则 `/apiary` 会被
 * 判成撞上 `/api`。这与 `faceOf()` 里那条规则是同一条, 不是巧合: 两处都在回答
 * "这个字符串属于谁"。
 */
export function collidesWithBackend(path: string): boolean {
  return BACKEND_OWNED_PREFIXES.some((p) => path === p || path.startsWith(p + '/'))
}
