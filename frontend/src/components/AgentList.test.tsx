/**
 * AgentList 的渲染契约 + 创建表单的校验契约。
 *
 * 跑在 node + renderToStaticMarkup 上(见 vite.config.ts 的 test.environment):
 * 要断言的是"给定这份数据, 渲染出什么", 而不是"点下去发生什么" —— 后者是
 * 浏览器的事, 而这里真正想钉住的是列表对**缺数据**的态度。
 */
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { AgentList } from './AgentList'
import { validateAgentForm } from './AgentCreateForm'
import type { AgentSummary } from '@/api/client'

const agent = (over: Partial<AgentSummary> = {}): AgentSummary => ({
  agentId: 'agt-1',
  clientId: 'oc-1',
  name: '林晚',
  status: 'active',
  createdAt: '2026-09-16T10:30:00',
  ...over,
})

describe('AgentList 渲染', () => {
  it('空列表给出可执行的下一步, 而不是一片空白', () => {
    const html = renderToStaticMarkup(<AgentList agents={[]} />)
    expect(html).toContain('还没有 agent')
    // 空态必须提到"客户端钥"—— 空列表最常见的原因不是真没有 agent,
    // 而是当前客户端钥属于另一个客户端(它只看得到自己名下的)。
    expect(html).toContain('客户端钥')
  })

  it('逐条渲染名称与 agentId', () => {
    const html = renderToStaticMarkup(
      <AgentList agents={[agent(), agent({ agentId: 'agt-2', name: '周野' })]} />,
    )
    expect(html).toContain('林晚')
    expect(html).toContain('agt-1')
    expect(html).toContain('周野')
    expect(html).toContain('agt-2')
  })

  it('名称为空时退回 agentId —— 不渲染出一个空白条目', () => {
    const html = renderToStaticMarkup(<AgentList agents={[agent({ name: '', agentId: 'agt-9' })]} />)
    expect(html).toContain('agt-9')
  })

  it('缺 createdAt 时显示占位符而不是 "null" / "undefined"', () => {
    const html = renderToStaticMarkup(<AgentList agents={[agent({ createdAt: null })]} />)
    expect(html).not.toContain('null')
    expect(html).not.toContain('undefined')
    expect(html).toContain('—')
  })

  it('选中项被标出(便于断言选中态确实跟着 URL 走)', () => {
    const html = renderToStaticMarkup(
      <AgentList agents={[agent(), agent({ agentId: 'agt-2' })]} selectedId="agt-2" />,
    )
    // 断言的是 `bg-accent-soft` 而不是随便一个底色: 未选中行的 hover 也是底色,
    // 若选中态用同一个 token, 两者就分不出来了 —— 而"哪一条被选中了"是这一屏
    // 唯一的交互事实。
    expect(html).toContain('bg-accent-soft')
  })
})

describe('创建表单校验', () => {
  it('描述为空 → 报错', () => {
    expect(validateAgentForm({ description: '   ', relationshipType: 'friend' }).description)
      .toBeTruthy()
  })

  it('描述过短 → 报错(编译不出稳定人格)', () => {
    expect(validateAgentForm({ description: '女孩', relationshipType: 'friend' }).description)
      .toBeTruthy()
  })

  it('一句完整的话 → 通过', () => {
    const errors = validateAgentForm({
      description: '一位在旧书店工作的女孩, 说话慢, 喜欢在雨天聊诗。',
      relationshipType: 'friend',
    })
    expect(errors).toEqual({})
  })

  it('关系为空 → 报错', () => {
    expect(
      validateAgentForm({ description: '一位在旧书店工作的女孩', relationshipType: '' })
        .relationshipType,
    ).toBeTruthy()
  })

  it('首尾空白不计入长度', () => {
    // "        女孩        " 实际只有 2 个字 —— trim 后仍该判短
    expect(
      validateAgentForm({ description: '        女孩        ', relationshipType: 'friend' })
        .description,
    ).toBeTruthy()
  })
})
