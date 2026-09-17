import { ApiError } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import type { SectionSpec } from '@/lib/studio'
import { RecordView } from './RecordView'
import { Empty, ErrorNote, Panel } from './ui'

/**
 * 一块面板 = 一个只读端点。
 *
 * 八个标签页里有七个是"读 N 个端点, 每个渲染成一块", 这个组件就是那个 N。
 * 它认得三种状态: 读到了(交给 RecordView)、读挂了(把服务端文案原样端上来)、
 * 正在读。**没有第四种** —— 尤其没有"读挂了但显示空"这一种: 那会把"服务没通"
 * 伪装成"这个人没有记忆", 而这两件事的排查方向完全相反。
 */
export function Section({ spec, agentId }: { spec: SectionSpec; agentId: string }) {
  // deps 用 [agentId, spec]: spec 是 studio.ts 里那张**模块级**表里的一行, 引用稳定。
  // 若哪天有人把表挪进组件里, 这里会退化成无限刷新 —— studio.test.ts 钉住了稳定性。
  const { data, loading, error } = useAsync(() => spec.load(agentId), [agentId, spec])

  return (
    <Panel
      title={
        <div className="min-w-0">
          <h2 className="text-sm font-medium text-ink">{spec.title}</h2>
          {spec.hint && (
            <p className="mt-0.5 text-xs font-normal leading-relaxed text-ink-faint">{spec.hint}</p>
          )}
        </div>
      }
    >
      <ErrorNote error={error ? describeError(error) : null} />
      {loading && data === null && !error && <Empty>读取中…</Empty>}
      {data !== null && <RecordView value={data} empty={spec.empty ?? '没有数据。'} />}
    </Panel>
  )
}

/**
 * 只读端点的失败文案。
 *
 * `ApiError` 带的是服务端原话(以及 401/403 时 faceOf 那一层写的"登录已失效"), 直接
 * 用它; 其余异常(网络断了、CORS、超时)只有一句 `TypeError: Failed to fetch`, 那
 * 句话对用户毫无意义 —— 换成一句他能拿去做点什么的话。
 */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) return e.message
  return `请求没发出去: ${e instanceof Error ? e.message : String(e)} —— 通常是后端没起或网络断了。`
}
