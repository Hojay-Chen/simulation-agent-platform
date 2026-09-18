/**
 * faceOf() 的归属判定 —— 控制台唯一一条"用哪把钥匙"的规则。
 *
 * 判错不会报错, 只会静默 401: 该带 X-Admin-Key 的带了 Bearer, 或反过来。
 * 401 的文案是"钥匙无效", 用户会去怀疑钥匙本身, 而不是怀疑路由 —— 这类
 * 故障最难查, 所以规则必须被钉死在这里。
 */
import { describe, expect, it } from 'vitest'
import { ADMIN_FACE_PATH, faceOf, isCredentialExchange } from './client'

describe('isCredentialExchange —— "必须空手去打"的那一条规则', () => {
  it('只有换票本身空手, 其余登录面端点带着票去', () => {
    // 这条规则曾经是"整个 /api/auth 前缀都不带凭据", 于是 /api/auth/me 空手去,
    // 永远 403 —— 而 hydrate() 把那个 403 读成"票失效了"并清掉票。
    // 症状是**每次刷新都退回登录页**, 看起来像登录没成功。
    expect(isCredentialExchange('/api/auth/login')).toBe(true)
    expect(isCredentialExchange('/api/auth/me')).toBe(false)
  })

  it('query 与 fragment 不改变归属', () => {
    expect(isCredentialExchange('/api/auth/login?next=/agents')).toBe(true)
    expect(isCredentialExchange('/api/auth/login#x')).toBe(true)
  })

  it('前缀相似的兄弟路径不被误判 —— 否则它们会空手打一个要票的端点', () => {
    expect(isCredentialExchange('/api/auth/login-history')).toBe(false)
    expect(isCredentialExchange('/api/auth/logins')).toBe(false)
  })
})

describe('faceOf — 管理面 (/api/v1/openapi/clients)', () => {
  it.each([
    ADMIN_FACE_PATH,
    `${ADMIN_FACE_PATH}/`,
    `${ADMIN_FACE_PATH}/oc-123`,
    `${ADMIN_FACE_PATH}/oc-123/rotate`,
    `${ADMIN_FACE_PATH}?status=active`,
    `${ADMIN_FACE_PATH}/oc-123?verbose=1`,
    `${ADMIN_FACE_PATH}#top`,
  ])('%s → admin', (url) => {
    expect(faceOf(url)).toBe('admin')
  })
})

describe('faceOf — 客户端面 (/api/v1/openapi/agents 及其余)', () => {
  it.each([
    '/api/v1/openapi/agents',
    '/api/v1/openapi/agents/',
    '/api/v1/openapi/agents/agent-1',
    '/api/v1/openapi/agents/agent-1/persona',
    '/api/v1/openapi/agents/agent-1/state',
    '/api/v1/openapi/agents?limit=10',
    '/api/v1/openapi/agents/agent-1/state?fresh=1#mood',
  ])('%s → client', (url) => {
    expect(faceOf(url)).toBe('client')
  })
})

describe('faceOf — Studio 面(8091, 用户 JWT)', () => {
  it.each([
    '/api/companions',
    '/api/companions/',
    '/api/companions/c-1',
    '/api/companions/c-1/memories',
    '/api/companions/c-1/memories/search?q=雨',
    '/api/companions/c-1/relationship/narrative',
    '/api/companions/c-1/life',
    '/api/companions/c-1/v9/metrics',
    '/api/companions/c-1/v5/traces',
    '/api/persons/me/handle',
    '/api/v10/conversation/cache-stats',
    '/api/admin/cognitive/tick',
  ])('%s → studio', (url) => {
    expect(faceOf(url)).toBe('studio')
  })

  it('agent 开关归 Studio 面 —— 它走"我是不是这个人", 不是"我是不是这个客户端"', () => {
    // 这两条路由与 /api/v1/openapi/agents/{id} 只差一个前缀, 判错的方向是把用户 JWT
    // 当成 sap_ 客户端钥发出去 —— 症状是"我按了停止, 控制台说钥匙无效", 而用户会去
    // 怀疑那把钥匙, 不会怀疑路由。
    expect(faceOf('/api/agents/lifecycle')).toBe('studio')
    expect(faceOf('/api/agents/lifecycle/pause-all')).toBe('studio')
    expect(faceOf('/api/agents/lifecycle/resume-all')).toBe('studio')
    expect(faceOf('/api/agents/c-1/lifecycle')).toBe('studio')
  })

  it('开放面与 Studio 面不会互相吃掉', () => {
    // 两个面都在 /api/ 之下, 而 studio 的规则是"其余全部 /api/**"——
    // 顺序写反的话, 开放面会被 studio 吃掉, 于是拿着 sap_ 客户端钥的开发者面
    // 全部改带 JWT, 症状是"钥匙好好的却全部 401"。
    expect(faceOf('/api/v1/openapi/agents')).toBe('client')
    expect(faceOf('/api/v1/openapi/agents/a-1/state')).toBe('client')
    expect(faceOf(ADMIN_FACE_PATH)).toBe('admin')
  })

  it('登录面自成一类 —— 但它内部的两种请求对凭据的要求相反', () => {
    // 面只是"打哪个后端"的归属, **不等于**"带哪把凭据": 同一个面上的两个端点可以
    // 要求相反的凭据, 于是"哪种请求必须空手"需要一条自己的规则。
    expect(faceOf('/api/auth/login')).toBe('auth')
    expect(faceOf('/api/auth/me')).toBe('auth')
    expect(faceOf('/api/auth/refresh')).toBe('auth')
  })

  it('前缀相同但不是子路径的兄弟路径不归登录面', () => {
    expect(faceOf('/api/authority')).toBe('studio')
    expect(faceOf('/api/authentication')).toBe('studio')
  })

  it('探活不归 studio —— 一个探活请求不该带凭据', () => {
    // 归 studio 的后果是把"服务活着吗"变成"我的票过期了吗": 探活开始依赖登录状态,
    // 而它不是。
    expect(faceOf('/api/health')).toBe('client')
  })
})

describe('faceOf — 边界', () => {
  it('前缀相同但不是子路径的兄弟资源归客户端面', () => {
    // 裸 startsWith 会把这两个判成管理面 —— 而服务端根本没有这两个资源,
    // 判错的结果是"带管理钥去打一个不存在的客户端面端点", 401 变成 404,
    // 排查方向整个跑偏。
    expect(faceOf('/api/v1/openapi/clients-archive')).toBe('client')
    expect(faceOf('/api/v1/openapi/clientsX')).toBe('client')
  })

  it('query 与 fragment 不改变归属', () => {
    expect(faceOf(`${ADMIN_FACE_PATH}?a=1#b`)).toBe('admin')
    expect(faceOf('/api/v1/openapi/agents?a=1#b')).toBe('client')
  })

  it('前导 query 早于 fragment 时按更早的切', () => {
    // ?x=1#/clients 这类: fragment 里出现管理面字样不该翻转归属
    expect(faceOf('/api/v1/openapi/agents?next=/api/v1/openapi/clients')).toBe('client')
  })

  it('文档与健康检查落在客户端面(它们本就不需要钥匙)', () => {
    expect(faceOf('/api/health')).toBe('client')
    expect(faceOf('/v3/api-docs')).toBe('client')
    expect(faceOf('/docs')).toBe('client')
  })
})
