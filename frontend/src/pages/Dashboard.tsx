import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Bot, KeyRound, RefreshCw, Sparkles } from 'lucide-react'
import { listCompanions } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { summarizeCompanions, type DashboardSummary } from '@/lib/studio'
import { Button, Empty, ErrorNote, Panel } from '@/components/ui'
import { RequireStudio } from '@/components/StudioLogin'
import { SummaryLine } from '@/components/RecordView'

/**
 * 首页 —— Being Studio 的账。
 *
 * <h2>它为什么只发一个请求</h2>
 *
 * 所有数字都从**同一个** `/api/companions` 的结果里算出来(summarizeCompanions,
 * 纯函数, 有测试)。想加"每人多少条记忆"之类的数, 就得逐 agent 打一次 —— 那种数
 * 属于 agent 详情页: 首页要回答的是"我一共有多少个、都在什么阶段", 而不是"第三个
 * 人上周三说了什么"。
 *
 * <h2>为什么首页要登录</h2>
 *
 * 因为列表本身就要用户 JWT。这一页没有"未登录的版本"可看 —— 与其编一个空壳假装
 * 有内容, 不如把登录框放在这, 并说清楚登录后会看到什么。
 */
export function Dashboard() {
  return (
    <RequireStudio why="首页的数来自你在聊天平台上的 agent 列表 —— 需要先用你的账号登录。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { data, loading, error, reload } = useAsync(() => listCompanions(), [])
  // 时间基准取一次就固定住: 每渲染一次都调 Date.now() 的话, "近 7 天"这个数会在
  // 一次会话里悄悄漂移, 而后端数据一动不动 —— 那看起来像数据在自己变。
  const summary = data ? summarizeCompanions(data, Date.now()) : null

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-medium text-ink">Dashboard</h1>
        <Button variant="ghost" onClick={reload}>
          <RefreshCw size={13} />
          刷新
        </Button>
      </div>

      <ErrorNote error={error} />
      {loading && !data && <Empty>读取中…</Empty>}

      {summary && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat label="我的 agents" value={summary.total} />
            <Stat
              label="已有账号ID"
              value={summary.withHandle}
              hint={summary.total > summary.withHandle
                ? `${summary.total - summary.withHandle} 个还没有 — 见 System 页`
                : '全部都有'}
            />
            <Stat label="近 7 天新增" value={summary.createdLast7d} />
            <Stat
              label="缺人格版本"
              value={summary.missingPersona}
              hint={summary.missingPersona > 0 ? '建出来了, 但还没编译出人格' : '都有人格'}
            />
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <Panel title="关系阶段分布">
              {summary.stages.length === 0
                ? <Empty>还没有 agent。</Empty>
                : <Stages summary={summary} />}
            </Panel>

            <Panel
              title="最近创建"
              action={<Link to="/agents" className="text-xs text-accent hover:underline">全部 ›</Link>}
            >
              {summary.newest.length === 0
                ? <Empty>列表里没有带创建时间的 agent。</Empty>
                : (
                  <div className="divide-y divide-line">
                    {summary.newest.map((c) => (
                      <Link key={c.id} to={`/agents/${encodeURIComponent(c.id)}`} className="block hover:bg-sunken/60">
                        <SummaryLine
                          text={c.name || c.id}
                          right={c.createdAt?.slice(0, 10) ?? ''}
                        />
                      </Link>
                    ))}
                  </div>
                )}
            </Panel>
          </div>

          <Panel title="下一步">
            <div className="grid gap-3 sm:grid-cols-3">
              <Shortcut to="/agents" icon={<Bot size={15} />} title="Agents" desc="逐个看身份、记忆、关系、生活" />
              <Shortcut to="/applications" icon={<Sparkles size={15} />} title="Applications" desc="应用平台现在提供哪些能力" />
              <Shortcut to="/access" icon={<KeyRound size={15} />} title="API" desc="发放客户端钥、查看接入方式" />
            </div>
          </Panel>

          <p className="text-xs leading-relaxed text-ink-faint">
            以上数字全部由 `GET /api/companions` 一次返回的结果算出(见 `lib/studio.ts`),
            本页只发这一个请求。agent 列表按**你**在聊天平台上的归属过滤 —— 这与
            <Link to="/agents" className="mx-1 text-accent hover:underline">Agents</Link>
            页按 API 客户端归属过滤的那一份是两套规则, 两边数量不同是正常的。
          </p>
        </>
      )}
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: number; hint?: string }) {
  return (
    <div className="rounded-xl border border-line bg-raised px-4 py-3">
      <p className="text-xs uppercase tracking-wider text-ink-faint">{label}</p>
      <p className="mt-1 font-mono text-2xl text-accent">{value}</p>
      {hint && <p className="mt-0.5 truncate text-xs text-ink-faint" title={hint}>{hint}</p>}
    </div>
  )
}

/** 条形分布。用宽度而不是饼图 —— 三五个类别, 比长度比面积准得多。 */
function Stages({ summary }: { summary: DashboardSummary }) {
  const max = Math.max(...summary.stages.map((s) => s.count), 1)
  return (
    <ul className="space-y-2.5">
      {summary.stages.map((s) => (
        <li key={s.stage} className="space-y-1">
          <div className="flex items-baseline justify-between text-xs">
            <span className="truncate text-ink-soft">{s.stage}</span>
            <span className="shrink-0 font-mono text-ink">{s.count}</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-sunken">
            <div
              className="h-full rounded-full bg-accent"
              style={{ width: `${Math.round((s.count / max) * 100)}%` }}
            />
          </div>
        </li>
      ))}
    </ul>
  )
}

function Shortcut({ to, icon, title, desc }: {
  to: string
  icon: ReactNode
  title: string
  desc: string
}) {
  return (
    <Link
      to={to}
      className="rounded-lg border border-line bg-sunken/40 p-3 transition hover:border-accent/40 hover:bg-accent-soft"
    >
      <span className="flex items-center gap-2 text-sm text-ink">{icon}{title}</span>
      <span className="mt-1 block text-xs leading-relaxed text-ink-faint">{desc}</span>
    </Link>
  )
}
