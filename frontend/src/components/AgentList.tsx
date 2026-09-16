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
    <ul className="divide-y divide-line">
      {agents.map((a) => {
        const selected = a.agentId === selectedId
        return (
          <li key={a.agentId}>
            <button
              type="button"
              onClick={() => onSelect?.(a.agentId)}
              className={`flex w-full items-center gap-3 px-4 py-3 text-left transition ${
                selected ? 'bg-accent-soft' : 'hover:bg-sunken'
              }`}
            >
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg border border-line bg-raised text-accent">
                <Bot size={16} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm text-ink">{a.name || a.agentId}</span>
                <span className="block truncate font-mono text-xs text-ink-faint">{a.agentId}</span>
              </span>
              <span className="shrink-0 text-xs text-ink-faint">
                {a.createdAt ? a.createdAt.slice(0, 10) : '—'}
              </span>
              <ChevronRight size={14} className="shrink-0 text-ink-faint" />
            </button>
          </li>
        )
      })}
    </ul>
  )
}
