import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Bot, KeyRound, RefreshCw, Sparkles } from 'lucide-react'
import { listCompanions } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { stageZh, summarizeCompanions, type DashboardSummary } from '@/lib/studio'
import { Button, Empty, ErrorNote, InfoTip, Panel } from '@/components/ui'
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
    <RequireStudio why="总览按你在聊天平台上的数字人归属来统计。">
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
        <h1 className="flex items-center gap-1.5 text-lg font-medium text-ink">
          总览
          <InfoTip label="这一页的数字是怎么来的">
            全部由 <span className="font-mono">GET /api/companions</span> 一次返回的结果算出, 本页只发这一个请求。
            列表按<b>你</b>在聊天平台上的归属过滤 —— 与「数字人」页按接口密钥归属的那一份是两套规则, 两边数量不同是正常的。
          </InfoTip>
        </h1>
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
            <Stat label="我的数字人" value={summary.total} />
            <Stat
              label="已有聊天平台账号"
              value={summary.withHandle}
              hint={summary.total > summary.withHandle
                ? `还有 ${summary.total - summary.withHandle} 个没有 — 见「系统设置」页`
                : '全部都有'}
            />
            <Stat label="近 7 天新建" value={summary.createdLast7d} />
            <Stat
              label="还没编译出人格"
              value={summary.missingPersona}
              hint={summary.missingPersona > 0 ? '建出来了, 但人格还没编译好' : '都有人格了'}
            />
          </div>

          {/*
            停止的那一档单独一行, 而不是挤进上面那四个数里 —— 上面四个回答"建得怎么样",
            这一个回答"现在花不花钱", 是两个问题。agent 是持续运转的: 没人说话时定时任务
            照样在推进它们的一生, 所以"有几个还开着"值得一眼看见。
          */}
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Stat
              label="还在运行"
              value={summary.total - summary.paused}
              hint={summary.total > 0 ? '持续运转, 会调用模型' : '还没有数字人'}
            />
            <Stat
              label="已停止"
              value={summary.paused}
              hint={summary.paused > 0 ? '不推进、不调模型, 记忆都在' : '没有停着的'}
            />
            {summary.paused > 0 && (
              <div className="col-span-2 flex items-center gap-2 rounded-xl border border-line bg-raised px-4 py-3">
                <p className="text-xs leading-relaxed text-ink-soft">
                  到 <Link to="/agents" className="text-accent hover:underline">数字人</Link> 页逐个开关,
                  或用那一页的「全部停止」。
                </p>
                <InfoTip label="停止一个数字人意味着什么">
                  停止<b>不删除任何东西</b> —— 记忆、关系、还没说完的话全部留着,
                  继续之后从停下的那一刻接着走。
                </InfoTip>
              </div>
            )}
          </div>

          <div className="grid gap-5 lg:grid-cols-2">
            <Panel
              title={
                <div className="flex min-w-0 items-center gap-1.5">
                  <h2 className="text-sm font-medium text-ink">各段关系到哪一步了</h2>
                  <InfoTip label="「关系阶段」是什么">
                    平台给每段关系标一个阶段(字段名 <span className="font-mono">relationshipStage</span>)。
                    没有标注的归到「未标注」—— 那是一个真实的类别, 不是缺数据。
                    页面上认不出的阶段名会原样显示成机器取值, 不猜。
                  </InfoTip>
                </div>
              }
            >
              {summary.stages.length === 0
                ? <Empty>还没有数字人。</Empty>
                : <Stages summary={summary} />}
            </Panel>

            <Panel
              title="最近新建"
              action={<Link to="/agents" className="text-xs text-accent hover:underline">全部 ›</Link>}
            >
              {summary.newest.length === 0
                ? <Empty>列表里没有带创建时间的数字人。</Empty>
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

          <Panel title="接下来去哪儿">
            <div className="grid gap-3 sm:grid-cols-3">
              <Shortcut to="/agents" icon={<Bot size={15} />} title="数字人" desc="逐个看她今天在做什么、记得什么、和谁有关系" />
              <Shortcut to="/applications" icon={<Sparkles size={15} />} title="应用能力" desc="平台现在能给她提供哪些能力" />
              <Shortcut to="/access" icon={<KeyRound size={15} />} title="接口密钥" desc="发放客户端钥、查看接入方式" />
            </div>
          </Panel>
        </>
      )}
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: number; hint?: string }) {
  return (
    <div className="rounded-xl border border-line bg-raised px-4 py-3">
      <p className="text-xs uppercase tracking-wider text-ink-faint">{label}</p>
      <p className="mt-1 font-mono text-2xl text-accent tnum">{value}</p>
      {/* 提示原来被 truncate 截掉 —— 一句被截断的话比没有还难读, 所以让它换行 */}
      {hint && <p className="mt-0.5 text-pretty text-xs leading-relaxed text-ink-faint">{hint}</p>}
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
            <span className="truncate text-ink-soft">{stageZh(s.stage)}</span>
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
