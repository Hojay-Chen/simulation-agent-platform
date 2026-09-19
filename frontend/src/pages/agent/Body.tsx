import { useMemo } from 'react'
import { RefreshCw } from 'lucide-react'
import { getAgentStateFull } from '@/api/client'
import {
  SENSE_CHANNELS,
  describeReading,
  groupOf,
  standout,
  summarizeVitals,
} from '@/lib/body'
import { useAsync } from '@/lib/useAsync'
import { Button, Empty, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { GaugeGroup } from '@/components/viz/Gauge'
import { GapNote } from '@/components/viz/panels'

/**
 * 「身体」—— 她此刻的状态。
 *
 * <h2>为什么这一页是仪表而不是一段 JSON</h2>
 *
 * `/state` 返回 19 个 0–1 的数。直接列出来, 使用者要做的第一件事是在脑子里把它们
 * 和"正常值"比一遍 —— 而"正常值"不在屏幕上。所以这一页做三件事: **分组**
 * (身体稳态 / 情绪 / 心智余量, 三组回答三个不同的问题)、**标方向**(哪个高是好)、
 * **给参照系**(每根条上一条 0.5 的中位线)。
 *
 * 三件事里只有第一件是"排版", 后两件是**信息**。方向标错的代价在 `lib/body.ts`
 * 的文件头里写了: 一个 0.8 被读反, 界面不会报错, 只会让人不再看第二眼。
 *
 * <h2>为什么头部那句"她现在怎么样"是可以缺失的</h2>
 *
 * `standout()` 在所有量都贴着 0.5 的时候返回 null, 页面就不说这句话。编一句
 * "她状态平稳"出来, 是在给一个不存在的观察加上权威 —— 而这一页的每一条判断都
 * 应该是能从下面的条子上验证的。
 */
export function Body({ agentId }: { agentId: string }) {
  const state = useAsync(() => getAgentStateFull(agentId), [agentId])

  const readings = useMemo(() => summarizeVitals(state.data), [state.data])
  const groups = useMemo(() => groupOf(readings), [readings])
  const top = useMemo(() => standout(readings), [readings])

  const updatedAt = readAt(state.data)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          {top ? (
            <p className="flex items-center gap-1.5 text-sm text-ink">
              {describeReading(top)}
              <InfoTip label="这一页为什么只挑一项说">
                这一句挑的是<b>偏离中间位置最多</b>的那一项, 不是综合评分 ——
                十九个量的权重文档里没有出处, 编一个公式出来只会得到一个没人能解释的数。
                全都在中间时这里就不说话, 因为那时确实没有可说的。
              </InfoTip>
            </p>
          ) : readings.length > 0 ? (
            <p className="text-sm text-ink-soft">
              所有量都在中间位置 —— 没有哪一项明显偏离, 所以这里不总结。
            </p>
          ) : (
            <p className="text-sm text-ink-faint">还没读到她的状态。</p>
          )}
          {updatedAt && (
            <p className="mt-1 text-xs text-ink-faint">
              认知链最后写入于 <span className="font-mono tnum">{updatedAt}</span>
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <InfoTip label="看这一页会不会打扰她">
            不会 —— 打开这一页<b>不触发任何认知</b>, 它只是读一份只读的快照。
            这些数字由认知链在她处理每一件事的时候持续写入。
          </InfoTip>
          <Button variant="ghost" onClick={state.reload}><RefreshCw size={13} />刷新</Button>
        </div>
      </div>

      {state.error && <p className="text-sm text-danger">{describeError(state.error)}</p>}
      {state.loading && !state.data && <Empty>读取中…</Empty>}
      {state.data && readings.length === 0 && (
        <Empty>
          返回体里没有任何数值字段。
          <span className="mt-1 block text-[11px]">
            正常情况下这是"认知链还没为她建出 state"—— 它收到第一条消息之后才会出现。
          </span>
        </Empty>
      )}

      {groups.map((g) => <GaugeGroup key={g.group} group={g.group} items={g.items} />)}

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">她的五感从哪进来</h2>
              <InfoTip label="这张表为什么是说明而不是数据">
                它告诉你看她的感知从哪五条通道进来, 但读不到"她的听觉现在多灵敏" ——
                感官通道的灵敏度还没有读取面。
                <br /><br />
                嗅觉和触觉是<b>持续影响</b>的主要落点: 坏气味要立刻反应,
                而温度会一直拉着温暖值往下走, 直到环境变了或者她穿上衣服。
              </InfoTip>
            </div>
          }
        >
          <ul className="space-y-2.5">
            {SENSE_CHANNELS.map((s) => (
              <li key={s.key} className="text-xs">
                <span className="flex items-baseline gap-2">
                  <span className="font-medium text-ink">{s.label}</span>
                  <span className="font-mono text-[11px] text-accent">{s.via}</span>
                  <InfoTip label={`${s.label}这条通道`}>{s.hint}</InfoTip>
                </span>
              </li>
            ))}
          </ul>
        </Panel>

        <div className="flex items-start gap-2 rounded-xl border border-line bg-raised px-4 py-3">
          <InfoTip tone="warn" label="这一页读不到的三样东西">
            <div className="space-y-3">
              <GapNote title="感官通道的灵敏度没有面">
                <p>
                  上面那张表是<b>说明</b>, 不是数据 —— 读不到"她的听觉现在多灵敏"。
                </p>
                <p>
                  缺的端点: <code>GET /api/companions/{'{id}'}/body/senses</code>。
                  落地之后上面那份通道说明就该换成读回来的门限值。
                </p>
              </GapNote>
              <GapNote title="衣物不是一个对象">
                <p>
                  "穿衣服"之所以能把温度的影响顶掉, 是因为<b>衣服是对象</b> ——
                  它有保暖值, 她穿上它是往自己的对象集合里加了一项。而目前对象集合
                  (她拥有什么、身上穿着什么)没有任何读取面。
                </p>
                <p>
                  所以界面上能看见"温暖值很低", 看不见"因为她没穿外套"。后者才是可行动的那一半。
                </p>
              </GapNote>
              <GapNote title="身体 ≠ 心智">
                <p>
                  这一页只显示<b>身体与情绪的当前值</b>。她"此刻在想什么"不在这一页;
                  她"今天在做什么"在「今天」页。三页分开是三件不同的事,
                  混在一页会让"她饿了"和"她在想晚饭"看起来像同一类信息。
                </p>
              </GapNote>
            </div>
          </InfoTip>
          <p className="text-xs leading-relaxed text-ink-soft">
            能看见"温暖值很低", 看不见"因为她没穿外套" —— 她身上穿着什么、她的感官现在多灵敏, 都还读不到。
          </p>
        </div>
      </div>
    </div>
  )
}

/**
 * `updatedAt` 的展示形态。
 *
 * 只在能解析出时间时格式化; 解析不出就**原样返回服务端的字符串** —— 而不是显示
 * "未知"。一个格式没见过的合法时间戳, 原样显示至少让人看出它是个时间戳。
 */
function readAt(state: Record<string, unknown> | null): string | null {
  const raw = state?.updatedAt
  if (typeof raw !== 'string' || !raw.trim()) return null
  const t = Date.parse(raw)
  if (!Number.isFinite(t)) return raw
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
