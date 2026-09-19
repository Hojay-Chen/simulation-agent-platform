/**
 * 控制台的 API 客户端。
 *
 * ## 分流不是"按主机", 而是"按面"
 *
 * 仓 1 聊天前端的 route() 解决的是**两个后端**(8081 聊天 / 8091 仿真)的归属问题。
 * 这里只有一个后端 openapi:8092, 但有两把**互不相通的钥匙**:
 *
 *   - 管理面(`/api/v1/openapi/clients`): `X-Admin-Key`, 全局一把, 发/吊销客户端钥;
 *   - 客户端面(`/api/v1/openapi/agents` 及其余): `Authorization: Bearer sap_...`,
 *     每客户端一把, 只能碰自己名下的 agent。
 *
 * 两把钥匙由 OpenApiAuthFilter 在服务端分开校验, 带错了就是 401。所以前端必须
 * 知道"这个请求该用哪把钥匙"—— 这就是 faceOf() 的全部职责。判定落在 url 上
 * (而不是在每个调用点手写 key), 与仓 1 同一个哲学: **规则一处, 调用点零感知**。
 *
 * ## 第三张面: Studio(JWT)
 *
 * Being Studio(§19)要展示的是 Memory / Relationship / Life / Cognition —— 那些
 * 数据只在 server:8091 上, 而 8091 的 73 个端点**全部要用户 JWT**; 它的两个钥匙面
 * 一个都进不去(见 openapi/V9 的边界: 8092 的包白名单被 check-agent.sh 静态钉死,
 * 认知链包永不进 8092, 所以 Memory/Life 不可能从 openapi 面出来)。
 *
 * 于是控制台长出第三张面: `Authorization: Bearer <JWT>` 打 8091。而 JWT 从哪来 ——
 * **不是**本平台发的。`users` 表由聊天平台拥有(§22: "users 由 Chat 写, Agent 只读"),
 * 所以控制台把用户凭据交给**聊天平台的登录端点**换一张票, 两仓共享 `app.jwt.secret`,
 * 8091 的 JwtAuthenticationFilter 认这张票。这不是绕过边界, 这**就是**边界:
 * 本平台不复制一份用户表, 也不自己发明一套登录。
 *
 * ## 同源
 *
 * 所有请求都打同源相对路径, 由反向代理决定落到哪个上游 —— dev 是 vite 的
 * `/api` 代理, prod 是 nginx(G7)的同一条规则。页面代码在两种环境下完全一致。
 */

/** 管理面的路径前缀 —— faceOf() 的唯一判据。 */
export const ADMIN_FACE_PATH = '/api/v1/openapi/clients'

/**
 * 开放面的前缀。它必须排在 studio 之前判定 —— 两个面都在 `/api/` 之下,
 * 而顺序搞反的后果是"用一个 sap_ 客户端钥去打管理端点", 报 401 却指向钥匙本身。
 */
export const OPENAPI_FACE_PATH = '/api/v1/openapi'

/**
 * 登录面 —— 不归本平台。这里**只列一个前缀**, 且它是唯一一处前端会主动把用户
 * 密码发出去的地方, 所以它值得一条自己的规则, 而不是混在 studio 里。
 */
export const AUTH_FACE_PATH = '/api/auth'

/**
 * 换凭据的那几个端点 —— 只有它们必须**空手**去打。
 *
 * 写成一张显式的名单而不是"整个 /api/auth 前缀都不带凭据": 后者的规则比它的理由
 * (见 request() 里那段)宽得多, 而宽出来的部分会把 `/api/auth/me` 一起吃掉 ——
 * 那个端点恰恰是**靠票**才能回答的。
 */
const CREDENTIAL_EXCHANGE_PATHS: readonly string[] = ['/api/auth/login']

/** 这个路径是不是"去换凭据的那一次"。query/fragment 不影响归属。 */
export function isCredentialExchange(url: string): boolean {
  return CREDENTIAL_EXCHANGE_PATHS.includes(stripQueryAndFragment(url))
}

export type Face = 'admin' | 'client' | 'studio' | 'auth'

export interface Credentials {
  /** 管理密钥(X-Admin-Key)。空 = 管理面不可用, 但客户端面照常。 */
  adminKey: string
  /** 客户端 API Key(sap_...)。空 = 客户端面不可用。 */
  clientKey: string
  /**
   * Studio 面的用户票(JWT)。空 = Studio 各页显示"请先登录", 而 API 面照常 ——
   * 两张面互不依赖, 这一点是有意的: 一个只想发 API Key 的运维不该被迫先登录。
   */
  studioToken: string
}

export const EMPTY_CREDENTIALS: Credentials = { adminKey: '', clientKey: '', studioToken: '' }

/**
 * 判定一个请求属于哪个面。
 *
 * 只认路径, 不认 query/fragment —— `?x=1` 不该改变归属(G5 在 route() 上踩过
 * 这个坑: 先 startsWith 再剥 query 会把 `/clients?status=active` 判错)。
 *
 * 前缀比较用 `===` 或 `prefix + '/'`, 不能用裸 startsWith —— 否则
 * `/api/v1/openapi/clients-archive` 会被误判成管理面, 而它其实是客户端面的资源。
 *
 * <h2>判定顺序是这个函数唯一的难点</h2>
 *
 * 管理面在开放面**之内**(`/api/v1/openapi/clients` 是 `/api/v1/openapi` 的子路径),
 * 所以管理面必须先判 —— 反过来写的话, 管理端点会被开放面吃掉, 拿着 sap_ 钥匙去打
 * 管理端点, 服务端回 401, 而 401 的文案是"钥匙无效", 用户会去怀疑钥匙本身。
 */
export function faceOf(url: string): Face {
  const pathOnly = stripQueryAndFragment(url)

  if (pathOnly === ADMIN_FACE_PATH || pathOnly.startsWith(ADMIN_FACE_PATH + '/')) {
    return 'admin'
  }
  // `/api/health` 是探活: 它**不**归 studio —— 一个探活请求不该带任何凭据,
  // 而给它挂上 Bearer 的后果是把"服务活着吗"变成"我的票过期了吗"。
  if (pathOnly === '/api/health') {
    return 'client'
  }
  if (pathOnly === AUTH_FACE_PATH || pathOnly.startsWith(AUTH_FACE_PATH + '/')) {
    return 'auth'
  }
  if (pathOnly === OPENAPI_FACE_PATH || pathOnly.startsWith(OPENAPI_FACE_PATH + '/')) {
    return 'client'
  }
  // 其余 `/api/**` 全部归 studio。写成前缀匹配而不是逐个列举, 是因为 8091 上有
  // 73 个端点、且它们分散在十几个控制器里 —— 列举法会随 8091 长出新端点而静默失效,
  // 而失效的样子是"新页面全部 401"。
  if (pathOnly === '/api' || pathOnly.startsWith('/api/')) {
    return 'studio'
  }
  // 文档(/docs、/v3/api-docs)既不要钥匙也不归 studio: 它们由 springdoc 提供,
  // 而 springdoc 在 8092 上。
  return 'client'
}

function stripQueryAndFragment(url: string): string {
  const q = url.indexOf('?')
  const h = url.indexOf('#')
  let cut = -1
  if (q !== -1 && h !== -1) cut = Math.min(q, h)
  else if (q !== -1) cut = q
  else if (h !== -1) cut = h
  return cut === -1 ? url : url.slice(0, cut)
}

// ── 凭据持有 ────────────────────────────────────────────────────────────────
//
// 模块级持有 + setter, 由 stores/session.ts 在启动时与变更时写入。这样 api
// 函数签名里没有 key 参数, 调用点不会因为漏传某一把而静默打到错的面。
// 测试可以直接 setCredentials() 注入, 不必拉起整个 store。
//
// 落在 **sessionStorage** 而非 localStorage: 这两把钥匙都是"特权凭据"
// (管理钥能发任意客户端钥), 不该在关掉标签页后仍留在机器上。

const STORAGE_KEY = 'luxera.sim-agent-console.credentials'

let credentials: Credentials = EMPTY_CREDENTIALS

/** 读回上次填的钥匙。模块加载时不自动执行 —— 单测环境没有 sessionStorage。 */
export function initCredentials(): Credentials {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<Credentials>
      credentials = {
        adminKey: typeof parsed.adminKey === 'string' ? parsed.adminKey : '',
        clientKey: typeof parsed.clientKey === 'string' ? parsed.clientKey : '',
        studioToken: typeof parsed.studioToken === 'string' ? parsed.studioToken : '',
      }
    }
  } catch {
    // sessionStorage 不可用(隐私模式/单测 node 环境) 或存的是坏 JSON ——
    // 退回空凭据: 用户在「填入密钥」那一栏重填即可, 不该因此白屏。
    credentials = EMPTY_CREDENTIALS
  }
  return credentials
}

export function setCredentials(next: Credentials): void {
  credentials = next
  try {
    // 三把全空 = 没什么可留的。少判一把的后果是"登出之后 sessionStorage 里还留着
    // 上一张票", 而那正是登出想清掉的东西。
    if (!next.adminKey && !next.clientKey && !next.studioToken) {
      sessionStorage.removeItem(STORAGE_KEY)
    } else {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
    }
  } catch {
    /* 存不下就算了 —— 本次会话内存里仍然有效 */
  }
}

export function getCredentials(): Credentials {
  return credentials
}

// ── 请求 ────────────────────────────────────────────────────────────────────

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 服务端在错误体里放的是 `{error: "..."}`, 取不到就退回状态码文案。
 *
 * <h2>403 在这一层有两种意思, 而它们要分开说</h2>
 *
 * 两个后端的未认证语义**不一样**, 这不是笔误: 8092 的 OpenApiAuthFilter 回 401,
 * 而 8091 的 ServerSecurityConfig 没配 authenticationEntryPoint, 于是拿不到身份时
 * 走 Spring 默认的 Http403ForbiddenEntryPoint —— **回 403**。
 *
 * 所以"403"在 studio 面上等于"没登录/票过期了", 而不是"你没这个权限"(8091 上根本
 * 没有角色体系, 每个登录用户都是 ROLE_USER)。照抄 401 的文案会把人引向"去查权限",
 * 而正确答案是"重新登录"。
 */
async function errorMessage(res: Response, face: Face): Promise<string> {
  try {
    const body = await res.json()
    if (body && typeof body.error === 'string' && body.error) return body.error
  } catch {
    /* 非 JSON 错误体(网关页/空体) —— 走下面的兜底 */
  }
  if (face === 'studio' && (res.status === 401 || res.status === 403)) {
    return '登录已失效 —— 重新登录即可(聊天平台的账号)'
  }
  if (res.status === 401) return '钥匙无效或缺失'
  if (res.status === 404) return '不存在或不属于当前客户端'
  if (res.status === 503) return '该面未在服务端配置(缺少管理密钥)'
  return `请求失败 (HTTP ${res.status})`
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const face = faceOf(url)
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')

  if (face === 'auth') {
    /*
     * 登录面里其实有**两种**请求, 而它们对凭据的要求是相反的 —— 这是这一面唯一的坑。
     *
     *   /api/auth/login  去换票的那一次, 必须空手: 带着一张过期/错误的票去打它,
     *                    服务端的 JwtAuthenticationFilter 会先看到那张票, 于是
     *                    "密码是对的却登不进去", 而屏幕上没有任何东西提示问题出在旧票上。
     *   /api/auth/me     问"这张票是谁的", 必须带票 —— 空手去一定是 403, 因为它
     *                    要回答的正是"票的主人是谁"。
     *
     * 早先这里一刀切成"登录面一律不带凭据", 于是 whoami() 永远 403, 而 hydrate()
     * 把 403 读成"票失效了"并清掉了票 —— 症状是**每次刷新都退回登录页**, 看起来
     * 像是登录本身没成功, 而不是这一条判定写宽了。
     */
    if (!isCredentialExchange(url) && credentials.studioToken) {
      headers.set('Authorization', `Bearer ${credentials.studioToken}`)
    }
  } else if (face === 'admin') {
    // 管理面: 带空 X-Admin-Key 没有意义 —— 服务端 adminKey 未配时回 503,
    // 配了但值不对回 401, 两种都让用户看不懂。这里提前拦下, 说清是哪一把缺了。
    if (!credentials.adminKey) {
      // 那一栏现在叫「填入密钥」(`ApiPortal.tsx` 的标签表)。**名字必须跟标签一致** ——
      // 「接入」是它当年作为独立页 `/connect` 时的旧名, 现在页面上找不到这三个字, 于是
      // 这句本来最好懂的提示变成了一句指不到地方的指路(已实测: 同一屏的横幅说「填入密钥」,
      // 面板里的报错说「接入」, 两者指的是同一个标签页)。
      throw new ApiError(0, '未配置管理密钥 —— 先在「填入密钥」那一栏填入 X-Admin-Key')
    }
    headers.set('X-Admin-Key', credentials.adminKey)
  } else if (face === 'studio') {
    if (!credentials.studioToken) {
      throw new ApiError(0, '尚未登录 —— Studio 各页需要聊天平台的账号')
    }
    headers.set('Authorization', `Bearer ${credentials.studioToken}`)
  } else {
    if (!credentials.clientKey) {
      throw new ApiError(0, '未配置客户端 API Key —— 先在「填入密钥」那一栏填入 sap_... 钥匙')
    }
    headers.set('Authorization', `Bearer ${credentials.clientKey}`)
  }

  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(url, { ...init, headers })

  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res, face))
  }
  // 204(吊销 / 软删成功)没有响应体 —— res.json() 会抛, 这里显式短路。
  if (res.status === 204) {
    return undefined as T
  }
  return (await res.json()) as T
}

// ── 资源 ────────────────────────────────────────────────────────────────────
//
// 每个函数都硬写完整路径(不经由变量拼接) —— 路径就是面判定的输入, 拼出来的
// 字符串会让 faceOf() 的规则失效于阅读者眼下。

export interface ClientSummary {
  clientId: string
  name: string
  apiKeyPrefix: string
  ownerUserId: string | null
  createdAt: string
}

export interface ClientCreated {
  clientId: string
  name: string
  /** 明文钥匙 —— 服务端只在此刻给出这一次, 之后库里只有 sha256。 */
  apiKey: string
  apiKeyPrefix: string
  notice: string
}

export function listClients(): Promise<ClientSummary[]> {
  return request<ClientSummary[]>('/api/v1/openapi/clients')
}

export function createClient(name: string, ownerUserId?: string): Promise<ClientCreated> {
  return request<ClientCreated>('/api/v1/openapi/clients', {
    method: 'POST',
    body: JSON.stringify({ name, ownerUserId: ownerUserId || null }),
  })
}

export function revokeClient(clientId: string): Promise<void> {
  return request<void>(`/api/v1/openapi/clients/${encodeURIComponent(clientId)}`, {
    method: 'DELETE',
  })
}

export interface AgentSummary {
  agentId: string
  clientId: string
  name: string
  status: string
  createdAt: string | null
  persona?: Persona | null
}

export interface Persona {
  name?: string
  [key: string]: unknown
}

export interface AgentState {
  agentId: string
  name: string
  /** 以下三项在认知链尚未初始化时缺省 —— 空状态而非错误。 */
  mood?: string
  emotionalCloseness?: number
  sleepiness?: number
  note?: string
}

export function listAgents(): Promise<AgentSummary[]> {
  return request<AgentSummary[]>('/api/v1/openapi/agents')
}

/**
 * 建 agent —— 二选一: description 走平台编译链(自然语言→人格), 或 persona 直传。
 * relationshipType 缺省时服务端按 "friend" 落。
 */
export function createAgent(input: {
  description?: string
  persona?: Persona
  relationshipType?: string
}): Promise<AgentSummary> {
  return request<AgentSummary>('/api/v1/openapi/agents', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function getAgent(agentId: string): Promise<AgentSummary> {
  return request<AgentSummary>(`/api/v1/openapi/agents/${encodeURIComponent(agentId)}`)
}

export function updatePersona(
  agentId: string,
  description: string,
  reason?: string,
): Promise<{ agentId: string; versionId: string; persona: Persona }> {
  return request(`/api/v1/openapi/agents/${encodeURIComponent(agentId)}/persona`, {
    method: 'PUT',
    body: JSON.stringify({ description, reason }),
  })
}

export function deleteAgent(agentId: string): Promise<void> {
  return request<void>(`/api/v1/openapi/agents/${encodeURIComponent(agentId)}`, {
    method: 'DELETE',
  })
}

export function getAgentState(agentId: string): Promise<AgentState> {
  return request<AgentState>(`/api/v1/openapi/agents/${encodeURIComponent(agentId)}/state`)
}

// ── Studio 面(JWT) ──────────────────────────────────────────────────────────
//
// 这一半打的是 server:8091, 走用户 JWT。它读的是**同一个人**在聊天平台上拥有的
// agent —— 与上面那一半(按 API client 归属)是两套完全不同的可见性规则, 所以这里
// 的每个函数路径都刻意不叫 openapi: 两个面混起来的后果是"用客户端钥去读别人的
// agent", 而那正是两套规则存在的理由。

export interface LoginResult {
  token: string
  user?: { id?: string; nickname?: string; username?: string }
}

/**
 * 换一张票 —— 把用户凭据交给**聊天平台**(`users` 表的拥有者), 拿回 JWT。
 *
 * 字段名是 `username` 而不是 `email`: 聊天平台的 LoginRequest 按 username 取,
 * 而它接受邮箱作为用户名(测试账号就是这么登的)。写成 `email` 会得到一个
 * 「Cannot invoke String.trim() because getUsername() is null」的 500 ——
 * 一个把"字段名写错"报成"服务端空指针"的错误, 归因成本极高。
 */
export function login(username: string, password: string): Promise<LoginResult> {
  return request<LoginResult>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}

/** 当前这张票是谁的 —— 登录后确认用, 也让"票还在不在"有一个便宜的探针。 */
export function whoami(): Promise<{ id?: string; nickname?: string; username?: string }> {
  return request('/api/auth/me')
}

export interface Companion {
  id: string
  name: string
  /** 账号ID(`agent_` 前缀)。它与 `id` 是**两个不同的东西** —— 见 §身份。 */
  handle?: string
  gender?: string
  age?: number
  relationshipType?: string
  relationshipStage?: string
  greeting?: string
  createdAt?: string
  persona?: Record<string, unknown> | null
  /**
   * 运转状态: `'active'` | `'paused'`。见 Agent 开关。
   *
   * 可选(而不是必填): 后端是老版本时这个字段不在响应里, 而"字段缺席"与"她是活的"
   * 在界面上必须表现成同一件事 —— 一个把缺席当成 `paused` 的前端会在后端还没升级时
   * 把满屏 agent 显示成"已停止", 那比不显示更糟。
   */
  lifecycle?: string
}

export function listCompanions(): Promise<Companion[]> {
  return request<Companion[]>('/api/companions')
}

// ── Agent 开关 ─────────────────────────────────────────────
//
// 这个平台的 agent 是**持续运转**的: 定时任务在推进它的一生, 每一步都可能调 LLM。
// 于是"先停下来"必须是一个能点的动作, 而不是"把它删了"(那是另一件事, 且不可逆)。
//
// 这一组端点在 8091 的用户面上, 而不是开放面上: 暂停是**所有者对自己 agent** 的操作,
// 它需要知道"我是谁"。平台级的"全部关掉"在管理密钥面(见 scripts/agents-off.sh)。

export interface AgentLifecycleRow {
  agentId: string
  name: string
  lifecycle: string
  paused: boolean
}

export interface AgentLifecycleOverview {
  agents: AgentLifecycleRow[]
  mineActive: number
  minePaused: number
  /** 全平台口径 —— 它回答"我关了的是不是全部" */
  platform: {
    active: number
    paused: number
    runtimeEnabled: boolean
    /** 本次进程启动以来被硬闸拦下的 LLM 调用次数 —— 开关生效的证据 */
    blockedCalls: number
    blockedByTask: Record<string, number>
  }
}

export function getAgentLifecycle(): Promise<AgentLifecycleOverview> {
  return request<AgentLifecycleOverview>('/api/agents/lifecycle')
}

/** 设置一个 agent 的运转状态。**幂等** —— 重复设成同一个值不算错。 */
export function setAgentLifecycle(agentId: string, lifecycle: 'active' | 'paused') {
  return request<{ agentId: string; lifecycle: string; changed: boolean }>(
    `/api/agents/${encodeURIComponent(agentId)}/lifecycle`,
    { method: 'PUT', body: JSON.stringify({ lifecycle }) },
  )
}

export function pauseAllMine(): Promise<{ paused: number; total: number }> {
  return request('/api/agents/lifecycle/pause-all', { method: 'POST' })
}

export function resumeAllMine(): Promise<{ resumed: number; total: number }> {
  return request('/api/agents/lifecycle/resume-all', { method: 'POST' })
}

export function getCompanion(id: string): Promise<Companion> {
  return request<Companion>(`/api/companions/${encodeURIComponent(id)}`)
}

export function listPersonaVersions(id: string): Promise<PersonaVersion[]> {
  return request<PersonaVersion[]>(
    `/api/companions/${encodeURIComponent(id)}/persona/versions`,
  )
}

export interface PersonaVersion {
  id?: string
  versionId?: string
  reason?: string
  createdAt?: string
  persona?: Record<string, unknown>
}

export function getAgentStateFull(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/api/companions/${encodeURIComponent(id)}/state`)
}

export function listMemories(id: string, type?: string): Promise<MemoryRow[]> {
  const q = type ? `?type=${encodeURIComponent(type)}` : ''
  return request<MemoryRow[]>(`/api/companions/${encodeURIComponent(id)}/memories${q}`)
}

export function searchMemories(id: string, query: string): Promise<MemoryRow[]> {
  return request<MemoryRow[]>(
    `/api/companions/${encodeURIComponent(id)}/memories/search?q=${encodeURIComponent(query)}`,
  )
}

export interface MemoryRow {
  id?: string
  memoryId?: string
  type?: string
  content?: string
  importance?: number
  createdAt?: string
  lastAccessedAt?: string
  [k: string]: unknown
}

export function getRelationship(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/api/companions/${encodeURIComponent(id)}/relationship`)
}

export function listRelationshipEvents(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/relationship/events`,
  )
}

export function listSharedExperiences(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/relationship/shared-experiences`,
  )
}

export function getRelationshipNarrative(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(
    `/api/companions/${encodeURIComponent(id)}/relationship/narrative`,
  )
}

export function listPromises(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/relationship/promises`,
  )
}

/**
 * 一项日程。
 *
 * 字段与 `life` 返回体里的 `todayActivities[]` **逐字相同**。这张类型定义是「今天」
 * 与「计划表」两页的全部输入 —— 那两页都不再自己从 `unknown` 上取字段。
 */
export interface PlanActivityView {
  title: string
  plannedStart?: string
  plannedEnd?: string
  type?: string
  status?: string
  /** 这件事要占她多少注意力。它和 `interruptibility` 一起决定她能不能被叫走。 */
  attentionDemand?: string
  /** 可打断程度 —— 「计划表」那一页会把它摆出来, 因为它决定"打断她"合不合适。 */
  interruptibility?: string
  /** 这段时间她能不能看手机。**不是**手机的免打扰设置, 是她的活动的属性。 */
  phoneAvailability?: string
  moodEffect?: string
  /** 0–1。缺省时界面不画进度条, 而不是画一条 0% 的。 */
  progress?: number
  interrupted?: boolean
  importance?: number
  emotionalSignificance?: number
}

export interface LifeView {
  /** 她的一天的一句话摘要。原样显示 —— 它是认知链写的, 不是前端拼的。 */
  todaySummary?: string
  currentActivity?: string
  /** 处在一天的哪一段(早晨/上午/午后/傍晚/夜里)。 */
  dayPhase?: string
  todayActivities?: PlanActivityView[]
}

/**
 * 她的一天。
 *
 * ⚠️ 返回体里 `todayActivities` 的每一项**没有 id** —— 只有 `title`。身份判定因此
 * 只能靠标题, 而"两件同名的事"会被当成一件(见 `lib/plan.ts` 的 `snapshot`)。这是
 * 一个接口缺口, 不是前端将就: 见报告里对 `PlanItem.itemId` 的说明。
 */
export function getLife(id: string): Promise<LifeView> {
  return request<LifeView>(`/api/companions/${encodeURIComponent(id)}/life`)
}

/** 一条世界事件。`type` 是 `WorldEventType` 里的机器名, 见 `lib/events.ts` 的译表。 */
export interface WorldEventRow {
  type: string
  at?: string
  payload?: Record<string, unknown> | null
}

export function listLifeEvents(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/life-events`,
  )
}

export function listWorldEvents(id: string): Promise<WorldEventRow[]> {
  return request<WorldEventRow[]>(
    `/api/companions/${encodeURIComponent(id)}/v5/world-events`,
  )
}

export function listOpenLoops(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/open-loops`,
  )
}

export function getMetrics(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(
    `/api/companions/${encodeURIComponent(id)}/v9/metrics`,
  )
}

export function listTraces(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(`/api/companions/${encodeURIComponent(id)}/v5/traces`)
}

export function listReflections(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/reflections`,
  )
}

export function listExperiences(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/experiences`,
  )
}

export function getSelfModel(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/api/companions/${encodeURIComponent(id)}/self`)
}

/** 我自己的账号ID 与改号配额 —— 它属于**人**, 不属于 agent, 所以没有 companionId。 */
export interface HandleView {
  handle: string
  used: number
  limit: number
  remaining: number
  nextChangeAt?: string | null
}

export function getMyHandle(): Promise<HandleView> {
  return request<HandleView>('/api/persons/me/handle')
}

/** 一个动作 —— 应用能做的**一件具体的事**。`agentHint` 是应用作者写给 agent 的策略建议。 */
export interface LapAction {
  actionId?: string
  applicationId?: string
  capabilityId?: string
  description?: string
  permissionLevel?: string
  riskLevel?: string
  agentHint?: string
  inputSchema?: unknown
  [k: string]: unknown
}

export interface LapApplication {
  applicationId?: string
  version?: string
  name?: string
  description?: string
  category?: string
  capabilities?: string[]
  actions?: LapAction[]
  [k: string]: unknown
}

export interface LapCapability {
  capabilityId?: string
  title?: string
  description?: string
  category?: string
  applications?: LapApplication[]
  [k: string]: unknown
}

/**
 * 应用平台目录 —— 能力 → 应用 → 动作, 一次取全。
 *
 * 它是一条**新的读取出口**(server:8091 的 `LapCatalogController`): 认知链一直在
 * 用这三个只读方法问聊天平台"有什么能做的", 但从来没有控制器把它们暴露出来过。
 * 数据一直存在, 缺的只是这个出口。
 */
export function getLapCatalog(): Promise<LapCapability[]> {
  return request<LapCapability[]>('/api/lap/catalog')
}

// ── 词汇表 ─────────────────────────────────────────────────────────────────

/** 一类机制。`category` 与 `world_event.category` 那一列**同词**(`STATE_EFFECT` / `SENSORY` / `SCHEDULED`)。 */
export interface EventCategoryEntry {
  category: string
  /** 中文名 —— 服务端给, 前端**不该**再抄一份。 */
  label: string
  /** 这一类在机制上是什么意思(由哪个数据结构接住)。 */
  note: string
  count: number
}

/** 一条事件类型。 */
export interface EventTypeEntry {
  /** `namespace.name.vN` —— 完整标识, 能 `parse` 回三段。 */
  type: string
  /** `namespace.name` —— 不含版本, 按类型订阅 handler 时用它。 */
  subscriptionKey: string
  namespace: string
  name: string
  version: number
  category: string
  categoryLabel: string
  /** A 类作用在哪个通道上; 否则 null。 */
  channel?: string | null
  /** B 类走哪个感官; 否则 null。 */
  modality?: string | null
  payload?: string
  semantics?: string
  producer?: string
  consumer?: string
  /** 有没有消费者。为假不一定是错 —— 但一定是需要人看一眼的事。 */
  claimed: boolean
}

export interface EventNamespaceEntry {
  namespace: string
  count: number
  types: EventTypeEntry[]
}

/** `GET /api/meta/event-types` 的返回体 —— **目录本身**, 不是它的一个投影。 */
export interface EventTypeCatalog {
  count: number
  categories: EventCategoryEntry[]
  namespaces: EventNamespaceEntry[]
  /** 标准持续影响通道(`body.warmth` 等)。 */
  channels: string[]
  /** 五种感官通道 —— 封闭集合。 */
  modalities: string[]
  /** 没有消费者的类型标识 —— **健康状态是空**。 */
  unclaimed: string[]
  types: EventTypeEntry[]
}

/**
 * 这套仿真的**词汇表**: 世界能发出哪些事件、影响作用在哪些通道上、人靠哪几种感官接收。
 *
 * <h2>它替掉的是什么</h2>
 *
 * 在它之前, 前端只能显示"线上真的出现过的类型" —— 因为 `CoreEventCatalog` 是服务端的
 * 常量, 没有出口。于是"哪几类事件从来没发生过"这个问题答不了, 而它恰恰是运维最需要的
 * 那一个: 一个从来没出现过的类型, 要么是那条链还没接通, 要么是它的订阅键写错了。
 *
 * <h2>它与事件流是**两份**数据, 不要在服务端合成一份</h2>
 *
 * 这一份随代码变(加了新事件就多一条), 事件流那一份随数据变(今天发生过什么)。
 * 前端的用法是各取一份、在内存里对一次 —— 那个对账的结果才是答案。
 */
export function getEventTypeCatalog(): Promise<EventTypeCatalog> {
  return request<EventTypeCatalog>('/api/meta/event-types')
}

// ── V2.2 观测面 ─────────────────────────────────────────────────────────────
//
// 下面这些是"她的一天 / 计划表 / 手机 / 关系网 / 事件流"五页**实际依赖**的端点。
// 每一个都在 8091 上真实存在(逐个对着控制器核过), 不是照着设计文档猜的路径。
//
// 与之相对, §V2.2 里还有一批控制器**没有**任何 HTTP 面: PlanRevision、
// ContinuousEffectLedger、整个 phone/ 包(11 个类)、boundary/event/* 的 fabric、
// Environment。那部分不在这里编一个函数出来假装能调 —— 编出来的函数会在页面上
// 变成一条没有解释的 404。它们出现在各页的「缺口」区块里, 并写清楚缺的是哪个端点。
//
// `CoreEventCatalog` 曾经也在上面那一串里, 现在不在了: `MetaCatalogController` 给了
// 它一条出口(见下面的 `getEventTypeCatalog`)。**但它给的不是"事件流的分类"** ——
// 目录用的是 `namespace.name.vN` 这套词汇, 而线上事件流里跑的还是 `WorldEventType`
// 那 15 个大写字符串。两套词汇并存这件事没有被这个端点解决, 它只是让"目录里有什么"
// 第一次可读。见 `lib/events.ts` 的类注释。

/** `/api/v10/perception/explain` 的返回体。 */
export interface PerceptionExplain {
  eventType: string
  perception: {
    level: string
    score: number
    strategy: string
    triggersCognition: boolean
  }
  decision: { policy: string; type: string; reason: string }
  life: { activity: string; attentionDemand: string; sleeping: boolean; description: string }
  /** 手机那一侧的状态。**这是目前唯一能读到设备状态的端点** —— 见「手机」页。 */
  device: { notificationMode: string; doNotDisturb: boolean; phoneLocation: string }
  mind: { focus: number; energy: number }
  importance: number
  /** 服务端给的阈值原文。界面**原样显示它**, 而不是只显示自己算的那一档。 */
  thresholds: string
}

/**
 * 推演: 这样一条事件到了她那儿, 会不会被注意到。
 *
 * 它是**只读的仿真** —— 不写任何状态, 随便点。这一点很重要: 「手机」页那个
 * 推演台的全部价值就在于用户可以乱试, 而不用怕打扰到她。
 *
 * `importance` 是事件的属性(salience), 她此刻的状态是另一个输入 —— 后者由服务端
 * 自己从她的 life/device/mind 里取, 前端不需要也不应该自己拼。
 */
export function explainPerception(
  companionId: string,
  eventType = 'DEVICE_NOTIFICATION',
  importance = 0.5,
): Promise<PerceptionExplain> {
  // eventType 与 importance 都走 query —— 这是 GET, 不是 POST。
  // 路径里没有 companionId(它是参数不是路径段), 所以必须自己 encode。
  const q = new URLSearchParams({
    companionId,
    eventType,
    importance: String(importance),
  })
  return request<PerceptionExplain>(`/api/v10/perception/explain?${q}`)
}

export interface InterruptResult {
  interrupted: boolean
  title?: string
  /** 打断之后她的自述。后端在目标不存在时给的是 `reason` 而不是 `explain`。 */
  explain?: string
  reason?: string
}

/**
 * 手动打断一个进行中的计划 —— **唯一一条能演示 §3.5.6 的写操作**。
 *
 * 它是 `/api/admin/**`, 所以 faceOf() 把它判成 studio 面(JWT)。这不是"管理面"
 * 那把 X-Admin-Key —— 两回事, 别混。它要的是**登录用户的票**, 而服务端会校验
 * 这个 companion 属于这个用户。
 *
 * `title` 是**模糊匹配**(`contains`), 所以传空串会打断列表里的第一条。
 * 这个语义有点危险, 前端因此必须总是显式传一个 title —— 见「计划表」页。
 *
 * 返回里的 `explain` 是这件事在界面上最值钱的部分: 它是她**自己**对"为什么现在
 * 不写作业了"的解释, 而不是界面上编的一句话。
 */
export function interruptPlan(
  companionId: string,
  title: string,
  reason?: string,
): Promise<InterruptResult> {
  const q = new URLSearchParams({ title })
  if (reason) q.set('reason', reason)
  return request<InterruptResult>(
    `/api/admin/plan/interrupt/${encodeURIComponent(companionId)}?${q}`,
    { method: 'POST' },
  )
}

/** `/api/v10/relationship/projection` 的返回体 —— 从 Reality Ledger 投影出来的互动事实。 */
export interface RelationshipProjection {
  companionId: string
  summary: {
    totalEvents: number
    messagesSentByPerson: number
    messagesRead: number
    messagesDeferred: number
    messagesIgnored: number
    activitiesEnded: number
    replyRate: number
    lastInteractionAt: string | null
  }
  reconciled: boolean
  principle: string
}

/**
 * 关系事实层。`userId` 是**必填**参数(控制器上没写 required = false, 缺了直接 400),
 * 所以调用方必须先从 `whoami()` 拿到自己的 id。
 *
 * 为什么这个投影值得单独一页: 它把"她读了几条、推了几条、理了几条"算成事实,
 * 而**不**问她的记忆怎么说(`principle` 那句话就是这条规则)。所以它是「关系网」
 * 页上唯一一组可以拿去争论的数 —— 记忆可以记错, 账本不会。
 */
export function getRelationshipProjection(
  companionId: string,
  userId: string,
): Promise<RelationshipProjection> {
  const q = new URLSearchParams({ companionId, userId })
  return request<RelationshipProjection>(`/api/v10/relationship/projection?${q}`)
}

/**
 * 她排下的一个闹钟 —— "她下一次什么时候醒, 因为什么"。
 *
 * <p>这是 `agent_schedule` 的唯一读出口(§18.1)。它取代了曾经那个 `ScheduledAction`:
 * 旧的那个读的是一张全仓零 handler 注册的表, 每条记录都必然变成 FAILED,
 * 于是端点显示的只是一串失败。
 *
 * <p>`source` 是"同一件事"的身份(见 `AgentWakeupService` 的 SRC_* 常量): 她本来说
 * "一小时后", 又说"算了三小时后", 那是同一个闹钟被推后了 —— 所以这里**不会有**
 * 两行同 source 的记录, 而"她改过主意"这件事在数据上就看不见了(它在轨迹里)。
 */
export interface AgentWakeup {
  /** 到点时刻。 */
  wakeAt?: string
  /** 醒来要处理的事件类型(`AgentEventType`)。 */
  eventType?: string
  /** 来源键 —— 排期去重的身份。 */
  source?: string
  /** 为什么排它(她自己的理由, 截到 160 字符)。 */
  reason?: string
}

/** 她还没醒的那些闹钟, 按时刻升序。已响/已取消的不在这里。 */
export function listWakeups(id: string): Promise<AgentWakeup[]> {
  return request<AgentWakeup[]>(`/api/companions/${encodeURIComponent(id)}/v5/wakeups`)
}

/**
 * 一条"她决定待会儿再看"的消息。
 *
 * <p>(这里曾经还有一个 {@code ScheduledAction} / {@code listScheduled} 对着
 * {@code /v5/scheduled}。那个端点读的是一张全仓零 handler 注册的表 —— 每条记录都
 * 必然变成 FAILED, 于是那个读面唯一能显示的就是一串失败。表与端点都已删除。)
 */
export interface PendingMessage {
  messageId?: string
  /**
   * 消息正文。
   *
   * ⚠️ 这是**全文**, 不是摘要 —— 它是这个返回体里唯一一处正文出现在"她还没回"的
   * 上下文里。原因是 `PendingMessageService` 存的就是她自己决定推迟时已经看过的那条,
   * 所以它不算泄漏。
   *
   * 但它**只能出现在运维面**。看她那一侧的页面永远不许渲染这个字段: 那条路径一旦
   * 打开, "正文只在她主动去看的时候才进入她"这句话就不成立了 —— 而那是整个产品
   * 唯一一条不能破的规则。见 `src/lib/events.ts` 里那五级台阶。
   */
  content?: string
  nextReviewAt?: string
  reason?: string
  /**
   * 已经复查过几次 / 一共允许几次。
   *
   * 两个数一起看才读得出运维真正会问的那句话: "这条她是在想, 还是已经忘了"。
   * 到 `maxReviews` 的那一条**不在这个列表里**(它已经 EXPIRED), 所以
   * `reviewCount` 逼近 `maxReviews` 就是"最后一次机会"。
   */
  reviewCount?: number
  maxReviews?: number
  /** 为什么没回: `SEEN_NO_REPLY` / `WANTED_TO_REPLY_FORGOT` / `REPLIED_HALFWAY`。 */
  frictionType?: string
}

export function listPendingMessages(id: string): Promise<PendingMessage[]> {
  return request<PendingMessage[]>(`/api/companions/${encodeURIComponent(id)}/v5/pending-messages`)
}

/** V11 送达主链的 shadow 对比。 */
export interface V11ShadowView {
  enabled?: boolean
  shadow?: boolean
  overall?: Record<string, unknown>
  thisCompanion?: Record<string, unknown>
  recent?: unknown[]
  /** 未启用时服务端会塞一句话进来 —— 读到全 0 不等于"没有分歧"。 */
  note?: string
  turns?: Record<string, unknown>
  cognition?: Record<string, unknown>
}

export function getV11(id: string): Promise<V11ShadowView> {
  return request<V11ShadowView>(`/api/companions/${encodeURIComponent(id)}/v5/v11`)
}

/** 注册进这个 agent 的名字 —— 认知链上真正在跑的处理器。 */
export function getRegisteredAgents(id: string): Promise<{ registered: string[] }> {
  return request<{ registered: string[] }>(
    `/api/companions/${encodeURIComponent(id)}/v5/agents`,
  )
}
