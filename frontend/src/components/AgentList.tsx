import { Bot, ChevronRight } from 'lucide-react'
import type { AgentSummary } from '@/api/client'
import { Empty } from './ui'

/**
 * agent 列表 —— 纯展示件: 数据与动作全靠 props。
 *
 * 保持无状态是刻意的: 它要能在 node 环境下被 renderToStaticMarkup 直接渲染,
 * 从而让"列表在各种数据下渲染成什么"成为一条可断言的纯函数式事实, 而不必
 * 拉 jsdom 去模拟点击。取数/副作用留在 pages/Agents.tsx。
 */
export function AgentList({ agents, selectedId, onSelect }: {
  agents: AgentSummary[]
  selectedId?: string | null
  onSelect?: (agentId: string) => void
}) {
  if (agents.length === 0) {
    return <Empty>还没有 agent —— 用右侧「创建」建一个, 或先确认客户端钥属于哪个客户端。</Empty>
  }

  return (
    <ul className="divide-y divide-cocoa-700/70">
      {agents.map((a) => {
        const selected = a.agentId === selectedId
        return (
          <li key={a.agentId}>
            <button
              type="button"
              onClick={() => onSelect?.(a.agentId)}
              className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${
                selected ? 'bg-cocoa-800' : 'hover:bg-cocoa-800/60'
              }`}
            >
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-cocoa-600 bg-cocoa-900 text-ember">
                <Bot size={16} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-cocoa-100">{a.name || a.agentId}</span>
                <span className="block truncate font-mono text-xs text-cocoa-500">{a.agentId}</span>
              </span>
              <span className="shrink-0 text-xs text-cocoa-500">
                {a.createdAt ? a.createdAt.slice(0, 10) : '—'}
              </span>
              <ChevronRight size={14} className="shrink-0 text-cocoa-600" />
            </button>
          </li>
        )
      })}
    </ul>
  )
}
