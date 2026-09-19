import { RefreshCw } from 'lucide-react'
import { getLapCatalog } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { RecordView } from '@/components/RecordView'
import { describeError } from '@/components/Section'
import { RequireStudio } from '@/components/StudioLogin'
import { Button, Empty, ErrorNote, InfoTip, Panel } from '@/components/ui'

/**
 * 「应用能力」—— 应用平台(LAP)目录: 能力 → 应用 → 动作。
 *
 * <h2>这些数据一直存在, 缺的只是一个出口</h2>
 *
 * 认知链一直在问聊天平台"有什么能力、哪个应用能做、它有哪些动作"(contract 的
 * `ApplicationRuntimePort`, 走 HMAC)。那三个方法从来没有控制器暴露过它们, 于是
 * 控制台上这一整块在数据上一直都在, 只是没人能看见 —— 本页 + server:8091 的
 * `LapCatalogController` 就是把那条出口接上。
 *
 * <h2>它回答什么, 不回答什么</h2>
 *
 * 回答的是"**平台**能让数字人做什么": 有哪些能力、谁实现了它、每个动作的权限级别
 * 与作者写的策略建议(`agentHint`)。不回答"某个数字人现在正在玩什么" —— 那要有真实
 * 会话才谈得上, 属于运行状态, 不属于目录。
 */
export function Applications() {
  return (
    <RequireStudio why="这一页列的是平台能提供的能力, 数据由数字人平台持有、按账号授权。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { data, loading, error, reload } = useAsync(() => getLapCatalog(), [])
  const apps = (data ?? []).reduce((n, c) => n + (c.applications?.length ?? 0), 0)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex min-w-0 items-center gap-1.5">
            <h1 className="text-lg font-medium text-ink">应用能力</h1>
            <InfoTip label="「应用能力」这一页回答什么">
              平台<b className="font-medium text-ink">能</b>让数字人做什么 —— 一条能力下面
              挂着实现它的应用, 每个应用下面挂着它能做的动作(动作上还带着权限级别与作者写的
              策略建议)。
              <br />
              <br />
              它是一份目录, 不是实况: "某个数字人此刻正在玩什么"要有真实会话才谈得上,
              在「运行状态」页看。
            </InfoTip>
          </div>
          <p className="mt-0.5 text-xs text-ink-faint">
            {data ? `${data.length} 个能力 · ${apps} 个应用` : ''}
          </p>
        </div>
        <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
      </div>

      <ErrorNote error={error ? describeError(error) : null} />
      {loading && !data && <Empty>读取中…</Empty>}

      {data && data.length === 0 && (
        <Panel title="平台能力">
          <Empty>
            平台现在没有登记任何能力。
            <span className="mt-1 block text-[11px] leading-relaxed">
              这句空话在「真的没登记」与「连不上聊天平台」两种情况下长得一模一样 ——
              想确认后端通不通, 到「系统设置」页看那几行检查结果。
            </span>
          </Empty>
        </Panel>
      )}

      {data && data.map((cap, i) => (
        <Panel
          key={cap.capabilityId ?? i}
          title={
            <div className="min-w-0">
              <h2 className="text-sm font-medium text-ink">
                {cap.title || cap.capabilityId}
                {cap.category && (
                  <span className="ml-2 rounded-md border border-line px-1.5 py-0.5 font-mono text-[11px] font-normal text-ink-faint">
                    {cap.category}
                  </span>
                )}
              </h2>
              {cap.description && (
                <p className="mt-0.5 text-xs font-normal leading-relaxed text-ink-faint">
                  {cap.description}
                </p>
              )}
            </div>
          }
        >
          {(cap.applications?.length ?? 0) === 0
            ? <Empty>这个能力还没有应用实现它。</Empty>
            : <RecordView value={cap.applications} />}
        </Panel>
      ))}
    </div>
  )
}
