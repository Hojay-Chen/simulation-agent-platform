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
 * ## 同源
 *
 * 所有请求都打同源相对路径, 由反向代理决定落到哪个上游 —— dev 是 vite 的
 * `/api` 代理, prod 是 nginx(G7)的同一条规则。页面代码在两种环境下完全一致。
 */

/** 管理面的路径前缀 —— faceOf() 的唯一判据。 */
export const ADMIN_FACE_PATH = '/api/v1/openapi/clients'

export type Face = 'admin' | 'client'

export interface Credentials {
  /** 管理密钥(X-Admin-Key)。空 = 管理面不可用, 但客户端面照常。 */
  adminKey: string
  /** 客户端 API Key(sap_...)。空 = 客户端面不可用。 */
  clientKey: string
}

export const EMPTY_CREDENTIALS: Credentials = { adminKey: '', clientKey: '' }

/**
 * 判定一个请求属于哪个面。
 *
 * 只认路径, 不认 query/fragment —— `?x=1` 不该改变归属(G5 在 route() 上踩过
 * 这个坑: 先 startsWith 再剥 query 会把 `/clients?status=active` 判错)。
 *
 * 前缀比较用 `===` 或 `prefix + '/'`, 不能用裸 startsWith —— 否则
 * `/api/v1/openapi/clients-archive` 会被误判成管理面, 而它其实是客户端面的资源。
 */
export function faceOf(url: string): Face {
  const pathOnly = stripQueryAndFragment(url)
  if (pathOnly === ADMIN_FACE_PATH || pathOnly.startsWith(ADMIN_FACE_PATH + '/')) {
    return 'admin'
  }
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
    if (!next.adminKey && !next.clientKey) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
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

/** 服务端在错误体里放的是 `{error: "..."}`, 取不到就退回状态码文案。 */
async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json()
    if (body && typeof body.error === 'string' && body.error) return body.error
  } catch {
    /* 非 JSON 错误体(网关页/空体) —— 走下面的兜底 */
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

  const credential = face === 'admin' ? credentials.adminKey : credentials.clientKey

  if (face === 'admin') {
    // 管理面: 带空 X-Admin-Key 没有意义 —— 服务端 adminKey 未配时回 503,
    // 配了但值不对回 401, 两种都让用户看不懂。这里提前拦下, 说清是哪一把缺了。
    if (!credential) {
      throw new ApiError(0, '未配置管理密钥 —— 先在「接入」页填入 X-Admin-Key')
    }
    headers.set('X-Admin-Key', credential)
  } else {
    if (!credential) {
      throw new ApiError(0, '未配置客户端 API Key —— 先在「接入」页填入 sap_... 钥匙')
    }
    headers.set('Authorization', `Bearer ${credential}`)
  }

  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(url, { ...init, headers })

  if (!res.ok) {
    throw new ApiError(res.status, await errorMessage(res))
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
