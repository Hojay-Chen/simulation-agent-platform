import { describe, expect, it } from 'vitest'
import { BACKEND_OWNED_PREFIXES, SPA_PATHS, collidesWithBackend } from './routes'

describe('SPA 路径不许落在后端独占的前缀之下', () => {
  it('每一条 SPA 路径都是干净的', () => {
    // 这一条失败时, 症状不是"页面报错", 而是"点链接进去好好的, 刷新一下 403" ——
    // 一个只在别人书签里复现的故障。所以它由测试守, 不由人守。
    for (const [name, path] of Object.entries(SPA_PATHS)) {
      expect(collidesWithBackend(path), `${name} (${path}) 撞上了后端前缀`).toBe(false)
    }
  })

  it('/api 这一条记得住 —— 它正是被改掉的那个路径', () => {
    // 曾经的路径就是 /api, 而 /api/** 整段归后端(nignx 与 vite proxy 都是前缀分流)。
    // 留着这条断言是因为"把栏目叫 API, 路径也顺手写成 /api"是极其自然的下一步。
    expect(collidesWithBackend('/api')).toBe(true)
    expect(collidesWithBackend('/api/agents')).toBe(true)
    expect(SPA_PATHS.access).toBe('/access')
  })
})

describe('collidesWithBackend', () => {
  it('前三条前缀直接来自 faceOf 的常量 —— 那边改了这边要跟着', () => {
    expect(BACKEND_OWNED_PREFIXES).toContain('/api/auth')
    expect(BACKEND_OWNED_PREFIXES).toContain('/api/v1/openapi')
    expect(BACKEND_OWNED_PREFIXES).toContain('/api/v1/openapi/clients')
  })

  it('前缀相同但不是子路径的兄弟不算撞 —— 否则 /apiary 会被冤枉', () => {
    // 这与 faceOf 里"裸 startsWith 会把 clients-archive 判成管理面"是同一类错。
    expect(collidesWithBackend('/apiary')).toBe(false)
    expect(collidesWithBackend('/systems')).toBe(false)
  })

  it('三条面路径都在 /api 之下 —— 所以真正需要守住的是 /api 这一条', () => {
    for (const p of ['/api/auth', '/api/v1/openapi', '/api/v1/openapi/clients']) {
      expect(collidesWithBackend(p)).toBe(true)
    }
  })
})
