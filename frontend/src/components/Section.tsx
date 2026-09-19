import { ApiError } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import type { SectionSpec } from '@/lib/studio'
import { RecordView } from './RecordView'
import { Empty, ErrorNote, InfoTip, Panel } from './ui'

/**
 * 一块面板 = 一个只读端点。
 *
 * 八个标签页里有七个是"读 N 个端点, 每个渲染成一块", 这个组件就是那个 N。
 * 它认得三种状态: 读到了(交给 RecordView)、读挂了(把服务端文案原样端上来)、
 * 正在读。**没有第四种** —— 尤其没有"读挂了但显示空"这一种: 那会把"服务没通"
 * 伪装成"这个人没有记忆", 而这两件事的排查方向完全相反。
 *
 * <h2>面板标题下面那行灰字搬到了问号里</h2>
 *
 * `spec.hint` 是写在 `studio.ts` 那张表里的一句话, 解释这块数据**是什么、跟别的有什么
 * 不一样**。它原来的位置在标题正下方, 一行灰色小字。问题是 `studio.ts` 里有些 hint 长
 * 到四行(比如「自我模型」那条要讲清它和「档案」是两样东西), 于是**每一块面板都顶着一
 * 段说明文**, 一页八块就是八段。读者要读的不再是数据, 而是一份说明书。
 *
 * 而说明书写得再好也只读一遍, 数据每看一次都是新的 —— 常驻的说明文是在用最贵的位置
 * 放最便宜的内容。现在它挂在标题右边的圆圈问号上: 第一次来的人点得到, 来过的人面前
 * 只有数据。**一个字都没删。**
 */
export function Section({ spec, agentId }: { spec: SectionSpec; agentId: string }) {
  // deps 用 [agentId, spec]: spec 是 studio.ts 里那张**模块级**表里的一行, 引用稳定。
  // 若哪天有人把表挪进组件里, 这里会退化成无限刷新 —— studio.test.ts 钉住了稳定性。
  const { data, loading, error } = useAsync(() => spec.load(agentId), [agentId, spec])

  return (
    <Panel
      title={
        <div className="flex min-w-0 items-center gap-1.5">
          <h2 className="text-sm font-medium text-ink">{spec.title}</h2>
          {spec.hint && <InfoTip label={`「${spec.title}」这一块是什么`}>{spec.hint}</InfoTip>}
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
