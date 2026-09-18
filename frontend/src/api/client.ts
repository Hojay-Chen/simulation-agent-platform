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
    // 退回空凭据: 用户在「接入」页重填即可, 不该因此白屏。
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
      throw new ApiError(0, '未配置管理密钥 —— 先在「接入」页填入 X-Admin-Key')
    }
    headers.set('X-Admin-Key', credentials.adminKey)
  } else if (face === 'studio') {
    if (!credentials.studioToken) {
      throw new ApiError(0, '尚未登录 —— Studio 各页需要聊天平台的账号')
    }
    headers.set('Authorization', `Bearer ${credentials.studioToken}`)
  } else {
    if (!credentials.clientKey) {
      throw new ApiError(0, '未配置客户端 API Key —— 先在「接入」页填入 sap_... 钥匙')
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

export function getLife(id: string): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(`/api/companions/${encodeURIComponent(id)}/life`)
}

export function listLifeEvents(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
    `/api/companions/${encodeURIComponent(id)}/life-events`,
  )
}

export function listWorldEvents(id: string): Promise<Record<string, unknown>[]> {
  return request<Record<string, unknown>[]>(
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
