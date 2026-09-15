/**
 * faceOf() 的归属判定 —— 控制台唯一一条"用哪把钥匙"的规则。
 *
 * 判错不会报错, 只会静默 401: 该带 X-Admin-Key 的带了 Bearer, 或反过来。
 * 401 的文案是"钥匙无效", 用户会去怀疑钥匙本身, 而不是怀疑路由 —— 这类
 * 故障最难查, 所以规则必须被钉死在这里。
 */
import { describe, expect, it } from 'vitest'
import { ADMIN_FACE_PATH, faceOf } from './client'

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
