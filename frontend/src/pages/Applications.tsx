import { RefreshCw } from 'lucide-react'
import { getLapCatalog } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { RecordView } from '@/components/RecordView'
import { describeError } from '@/components/Section'
import { RequireStudio } from '@/components/StudioLogin'
import { Button, Empty, ErrorNote, Panel } from '@/components/ui'

/**
 * Applications —— 应用平台(LAP)目录: 能力 → 应用 → 动作。
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
 * 回答的是"**平台**能让 agent 做什么": 有哪些能力、谁实现了它、每个动作的权限级别
 * 与作者写的策略建议(`agentHint`)。不回答"某个 agent 现在正在玩什么" —— 那要有真实
 * 会话才谈得上, 属于运行时, 不属于目录。
 */
export function Applications() {
  return (
    <RequireStudio why="应用目录经 server:8091 取, 那道门要用户 JWT —— 需要先登录。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { data, loading, error, reload } = useAsync(() => getLapCatalog(), [])
  const apps = (data ?? []).reduce((n, c) => n + (c.applications?.length ?? 0), 0)

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-medium text-ink">Applications</h1>
          <p className="mt-0.5 text-xs text-ink-faint">
            {data ? `${data.length} 个能力 · ${apps} 个应用` : ''}
          </p>
        </div>
        <Button variant="ghost" onClick={reload}><RefreshCw size={13} />刷新</Button>
      </div>

      <ErrorNote error={error ? describeError(error) : null} />
      {loading && !data && <Empty>读取中…</Empty>}

      {data && data.length === 0 && (
        <Panel title="应用平台">
          <Empty>
            应用平台现在没有登记任何能力。
          </Empty>
          <p className="mt-2 text-center text-xs leading-relaxed text-ink-faint">
            这句话在"平台真的没登记"与"聊天平台连不上"两种情况下**字面相同** ——
            本页拿不到连接层的错误, 所以它只说它知道的那件事。
            想确认后端通不通, 到 System 页看探活。
          </p>
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
