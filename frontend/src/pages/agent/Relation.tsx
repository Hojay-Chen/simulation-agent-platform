import { useMemo } from 'react'
import { RefreshCw, Users } from 'lucide-react'
import {
  getRelationship,
  getRelationshipProjection,
  whoami,
  type RelationshipProjection,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { Button, Empty, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { Meter } from '@/components/viz/Gauge'
import { GapNote } from '@/components/viz/panels'

/**
 * 「关系网」—— 她的通讯录。
 *
 * <h2>这一页最要紧的一句话: 她的通讯录是她自己长出来的</h2>
 *
 * 它**不是**从平台同步下去的。平台不告诉她的手机"你有这些人"; 是她自己在一句一句
 * 聊的过程中, 把一个人变成"认识的人", 再变成"会主动找的人"。所以这一页上所有
 * 的东西 —— 熟悉度、信任、共同经历、承诺 —— 都是**她那边长出来的结果**, 不是配置。
 * 页面顶部那段话必须把这件事说出来, 否则整页看起来像一个"关系设置面板"。
 *
 * <h2>两份数, 别混</h2>
 *
 * 这一页上有两组来源完全不同的数, 页面把它们**分开摆**:
 *
 * 1. **关系维度**(熟悉度 / 信任 / 亲密 …)—— 来自认知链的关系图, 是她的**内部状态**。
 *    它可以和事实不一致(记忆可以记错)。
 * 2. **互动投影**(发了几条 / 读了几条 / 推了几条 / 回得怎么样)—— 来自 Reality Ledger,
 *    是**账本**。服务端在那份返回体里自己写了一句话:
 *    「关系事实层从 Reality Ledger 投影, 不手工维护(Memory 不能覆盖 Reality)」。
 *
 * 两组的差别不是精度, 是**权威性**: 要争论"她是不是在冷落我"时该看第二组;
 * 要知道"她觉得自己和这个人多熟"时看第一组。混成一屏, 这个区别就没了。
 *
 * <h2>为什么投影那一块可能是空的</h2>
 *
 * 因为那个端点要 `userId`(必填)。拿不到当前用户就整块不显示, 并说明原因 ——
 * 而不是发一个必定 400 的请求, 让页面上出现一条来路不明的红色错误。
 */
export function Relation({ agentId }: { agentId: string }) {
  const me = useAsync(() => whoami(), [])
  const userId = me.data?.id ?? null

  const projection = useAsync(
    () => (userId ? getRelationshipProjection(agentId, userId) : Promise.resolve(null)),
    [agentId, userId],
  )

  return (
    <div className="space-y-5">
      <Panel
        title="她的通讯录是她自己长出来的"
        action={<Button variant="ghost" onClick={() => { me.reload(); projection.reload() }}>
          <RefreshCw size={13} />刷新
        </Button>}
      >
        <div className="flex items-start gap-3">
          <Users size={16} className="mt-0.5 shrink-0 text-accent" />
          <div className="space-y-2 text-xs leading-relaxed text-ink-soft">
            <p>
              平台**不同步**通讯录给她。她知道谁, 是她在聊天的过程里一点点建立起来的:
              第一次说话、第一次被记住、第一次主动找对方。所以这里所有的数都是**结果**,
              没有任何一项是配置出来的。
            </p>
            <p className="text-ink-faint">
              由此推出两件在界面上必须成立的事: 一段关系可以**变陌生**(长期不说话就会),
              而"她记得你"和"你确实和她说过话"是两回事 —— 前者是她的记忆, 后者是账本。
              下面两块就是这两样东西。
            </p>
          </div>
        </div>
      </Panel>

      <ProjectionBlock projection={projection} hasUser={userId !== null} meError={me.error} />

      <DimensionBlock agentId={agentId} />

      <Panel title="这一页读不到什么">
        <div className="grid gap-3 lg:grid-cols-2">
          <GapNote title="通讯录本身没有面">
            <p>
              上面那些是**一个人的关系**(她与当前用户)。而她的通讯录里可能不止一个人 ——
              §3.4 的 `RelationshipGraph` 是把她与全部 `PersonObject` 连起来的一张图。
            </p>
            <p>
              缺的端点: <code>GET /api/companions/{'{id}'}/mind/relationship/graph</code>
              —— 返回她认识的所有人以及每个人与她的维度值。有了它, 这一页才能从
              "一条关系"变成"一张网"。
            </p>
          </GapNote>
          <GapNote title="账号绑定没有面">
            <p>
              §6.5 里把聊天平台的 `accountId` 绑到她的 `PersonObject` 上。这条绑定是她
              "认识某个人"的技术前提, 也是排查"她怎么不认得我了"时第一个要看的东西。
            </p>
            <p>
              目前只有投影返回体里那个 `reconciled` 布尔在暗示这件事发生过, 没有任何
              端点能列出绑定关系。
            </p>
          </GapNote>
        </div>
      </Panel>
    </div>
  )
}

// ── 账本投影 ────────────────────────────────────────────────────────────────

function ProjectionBlock({
  projection,
  hasUser,
  meError,
}: {
  projection: ReturnType<typeof useAsync<RelationshipProjection | null>>
  hasUser: boolean
  meError: string | null
}) {
  const d = projection.data

  return (
    <Panel title="事实层: 从账本投影出来的互动">
      {!hasUser && (
        <p className="text-xs leading-relaxed text-ink-faint">
          读不到当前用户的 id{meError ? ` (${describeError(meError)})` : ''} —— 这个端点
          要 `userId` 才能回答"是不是你", 所以这一块暂时是空的。
        </p>
      )}

      {hasUser && projection.error && (
        <p className="text-xs text-danger">{describeError(projection.error)}</p>
      )}
      {hasUser && projection.loading && !d && <Empty>读取中…</Empty>}

      {d && (
        <div className="space-y-4">
          <p className="text-xs leading-relaxed text-ink-soft">
            这一组数**不问她的记忆**。服务端在那份返回体里自己写了这条规则:
            <span className="ml-1 text-ink-faint">「{d.principle}」</span>
          </p>

          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Fact label="她收到的" value={d.summary.messagesSentByPerson} hint="你发给她的条数。" />
            <Fact label="她读的" value={d.summary.messagesRead} hint="她做出「看一眼」这个动作之后, 正文才进入她。" />
            <Fact label="她推后的" value={d.summary.messagesDeferred} hint="她决定待会儿再看。这是一个决定, 不是没看见。" />
            <Fact label="她没理会的" value={d.summary.messagesIgnored} hint="她压根没感知到 —— 通知就被拦下或她睡着了。" />
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium text-ink-soft">她收到的消息最后都去了哪</p>
            <AwarenessOutcome summary={d.summary} />
            <p className="text-[11px] leading-relaxed text-ink-faint">
              三段加起来 = {d.summary.messagesSentByPerson}(
              她一共收到的条数)。"推后"和"没理会"的**区别**才是这一页最值钱的信息:
              一个说明她看见了但选择晚点处理, 另一个说明她根本不知道 ——
              而这两种情况需要的行动完全相反。
            </p>
          </div>

          <dl className="grid gap-1.5 text-xs sm:grid-cols-2">
            <Row k="totalEvents" v={d.summary.totalEvents} hint="账本里关于这段关系的全部事件条数。" />
            <Row k="activitiesEnded" v={d.summary.activitiesEnded} hint="她因为你而结束了手上的活动 —— 也就是「她真的为你停下来过」的次数。" />
            <Row
              k="replyRate"
              v={`${(d.summary.replyRate * 100).toFixed(0)}%`}
              hint="回得怎么样。它不等于「在意」, 只是一个比率。"
            />
            <Row k="lastInteractionAt" v={fmtMoment(d.summary.lastInteractionAt)} hint="最后一次互动。" />
            <Row
              k="reconciled"
              v={d.reconciled ? '账本与关系图已对齐' : '两边有出入'}
              hint="服务端做的一个一致性检查: 账本里的事实和她的关系图对不对得上。"
            />
          </dl>
        </div>
      )}
    </Panel>
  )
}

/**
 * 三段的占比条。
 *
 * 用**一条**堆叠条而不是三个饼/三个进度条: 这三段加起来是一个整体(她收到的全部),
 * 而"三段构成一个整体"这件事本身就是这里要说的内容。三个分开的条会让人以为
 * 它们是三个独立的指标。
 */
function AwarenessOutcome({ summary }: {
  summary: RelationshipProjection['summary']
}) {
  const total = Math.max(summary.messagesSentByPerson, 1)
  const segments = [
    { key: 'read', label: '她看了', n: summary.messagesRead, cls: 'bg-ok' },
    { key: 'deferred', label: '她推后了', n: summary.messagesDeferred, cls: 'bg-cat-schedule' },
    { key: 'ignored', label: '她没理会', n: summary.messagesIgnored, cls: 'bg-ink-faint' },
  ]
  return (
    <div className="space-y-2">
      <div className="flex h-2.5 overflow-hidden rounded-full bg-sunken">
        {segments.map((s) => (
          <span
            key={s.key}
            className={s.cls}
            style={{ width: `${(s.n / total) * 100}%` }}
            title={`${s.label}: ${s.n}`}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-[11px]">
        {segments.map((s) => (
          <span key={s.key} className="flex items-center gap-1.5">
            <span className={`h-1.5 w-1.5 rounded-full ${s.cls}`} aria-hidden="true" />
            <span className="text-ink-faint">{s.label}</span>
            <span className="font-mono text-ink tnum">{s.n}</span>
          </span>
        ))}
      </div>
    </div>
  )
}

// ── 关系维度 ────────────────────────────────────────────────────────────────

/**
 * 关系维度表。
 *
 * 与 `lib/body.ts` 同一个做法: **从数据里取全部数值字段**, 认识的给中文名与方向,
 * 不认识的照样显示但标"未登记"。写死一张十项的清单会在认知链加一个维度时静默
 * 漏掉它 —— 而漏掉的那一项在界面上是"不存在", 不是"没值"。
 */
const DIMENSIONS: Record<string, { label: string; goodHigh: boolean; hint: string }> = {
  familiarity: { label: '熟悉度', goodHigh: true, hint: '她知道关于你的事有多少。它涨得慢, 掉得也慢。' },
  trust: { label: '信任', goodHigh: true, hint: '她愿不愿意把不确定的事交给你。' },
  intimacy: { label: '亲密', goodHigh: true, hint: '多深的话题她愿意开。' },
  affection: { label: '好感', goodHigh: true, hint: '她此刻对你的整体倾向。' },
  tension: { label: '紧张', goodHigh: false, hint: '没解开的摩擦。它和"受伤"不同: 紧张是关系里的, 受伤是她的。' },
  reciprocity: { label: '互惠', goodHigh: true, hint: '一来一往的平衡。长期单向会把它压低。' },
  respect: { label: '尊重', goodHigh: true, hint: '她怎么看待你的判断。' },
  dependence: { label: '依赖', goodHigh: true, hint: '她有多少事会先想到你。' },
  connectionPressure: { label: '联结压力', goodHigh: false, hint: '关系本身在推着她做点什么的力度。太高她会喘不过气。' },
}

function DimensionBlock({ agentId }: { agentId: string }) {
  const rel = useAsync(() => getRelationship(agentId), [agentId])
  const items = useMemo(() => readDimensions(rel.data), [rel.data])

  return (
    <Panel
      title="她的内部状态: 她把这段关系记成什么样"
      action={<Button variant="ghost" onClick={rel.reload}><RefreshCw size={13} />刷新</Button>}
    >
      {rel.error && <p className="text-xs text-danger">{describeError(rel.error)}</p>}
      {rel.loading && !rel.data && <Empty>读取中…</Empty>}
      {rel.data && items.length === 0 && (
        <Empty>返回体里没有任何数值维度 —— 这段关系可能还没被建过。</Empty>
      )}
      {items.length > 0 && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((it) => (
              <Meter
                key={it.key}
                label={it.label}
                value={it.value}
                goodHigh={it.goodHigh}
                note={it.unregistered ? '未登记' : undefined}
                unregistered={it.unregistered}
                hint={it.hint}
              />
            ))}
          </div>
          {items.some((i) => i.unregistered) && (
            <p className="mt-3 text-[11px] leading-relaxed text-warn">
              有维度前端不认识。它们照常显示, 但**方向不明** —— 颜色只反映数值高低,
              不代表好坏。要修的是 `Relation.tsx` 里那张表。
            </p>
          )}
          <p className="mt-4 text-[11px] leading-relaxed text-ink-faint">
            这一段是**她的内部状态**, 不是账本。它可以和上面那段事实不一致 ——
            记忆可以记错, 而账本不会。两者不一致时, 上面那段才是"发生了什么"。
          </p>
        </>
      )}
    </Panel>
  )
}

interface DimReading {
  key: string
  label: string
  value: number
  goodHigh: boolean
  hint: string
  unregistered: boolean
}

/** 从 `getRelationship` 的返回体里挑出数值维度。导出是为了它能被测。 */
export function readDimensions(data: Record<string, unknown> | null): DimReading[] {
  if (!data) return []
  // 关系维度可能嵌在 `relationship` 里, 也可能就在顶层 —— 两种都认。
  const inner = data.relationship
  const src = inner && typeof inner === 'object' && !Array.isArray(inner)
    ? (inner as Record<string, unknown>)
    : data

  const out: DimReading[] = []
  for (const [key, raw] of Object.entries(src)) {
    if (typeof raw !== 'number' || !Number.isFinite(raw)) continue
    const known = DIMENSIONS[key]
    out.push({
      key,
      label: known?.label ?? key,
      value: Math.min(1, Math.max(0, raw)),
      goodHigh: known?.goodHigh ?? true,
      hint: known?.hint ?? '',
      unregistered: !known,
    })
  }
  return out.sort((a, b) => a.key.localeCompare(b.key))
}

// ── 小件 ────────────────────────────────────────────────────────────────────

function Fact({ label, value, hint }: { label: string; value: number; hint: string }) {
  return (
    <div className="rounded-lg border border-line bg-sunken/40 px-3 py-2" title={hint}>
      <p className="text-[11px] text-ink-faint">{label}</p>
      <p className="mt-0.5 font-mono text-xl text-ink tnum">{value}</p>
    </div>
  )
}

function Row({ k, v, hint }: { k: string; v: string | number; hint?: string }) {
  return (
    <div className="flex items-baseline gap-2" title={hint}>
      <dt className="shrink-0 font-mono text-ink-faint">{k}</dt>
      <dd className="min-w-0 text-ink-soft">{v}</dd>
    </div>
  )
}

function fmtMoment(iso: string | null): string {
  if (!iso) return '还没有互动过'
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return iso
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
