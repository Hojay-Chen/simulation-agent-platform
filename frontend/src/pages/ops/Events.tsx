import { useMemo, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { getEventTypeCatalog, listWorldEvents, type WorldEventRow } from '@/api/client'
import {
  CLASS_OF_SERVER_CATEGORY,
  EVENT_CLASS_META,
  eventClassesOf,
  eventLabelZh,
  reconcileCatalog,
  summarizePayload,
  type EventClass,
} from '@/lib/events'
import { useAsync } from '@/lib/useAsync'
import { AgentPickerBar, useAgentChoice } from '@/components/AgentPicker'
import { RequireStudio } from '@/components/StudioLogin'
import { Button, Empty, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { CategoryLegend, ClassTally, EventRow, GapNote, tally, type EventRowData } from '@/components/viz/panels'

/**
 * 「事件流」—— 运维面。世界往她那儿递了什么。
 *
 * <h2>这一页属于谁</h2>
 *
 * 属于**运维/开发**, 不属于"看她的人"。同一个问题在两张面上长得完全不一样:
 * 看她的人问的是"她今天过得怎么样"(答案在「今天」页的时间轴上), 而这一页回答的是
 * "线上到底发生了几件事、有没有没登记的类型、有哪一条卡在台阶上没往下走"。
 *
 * 两张面混在一页的代价在 §2.3 里写得很清楚: 一个只是好奇她今天干了什么的人, 会看到
 * 一屏 `USER_MESSAGE_NOTIFIED` 这样的机器名, 于是要么看不懂、要么学会忽略它。
 * 所以这一页刻意从侧栏的「运维」那一组进来, 和看她那几页分开。
 *
 * <h2>这一页的核心动作: 把扁平的一条变成有类别的一条</h2>
 *
 * 线上的返回体是一个扁平的 `{type, at, payload}` 列表 —— 送达、手机响、她注意到、
 * 她读了、她推后了, 五件事长得一模一样。而它们分属**三种完全不同的机制**:
 * 一个是世界的事实(不需要她做什么)、一个是必须立刻处理的实时刺激、一个是计划表上
 * 的时间窗。这一页把它们按类别分开数、标上形状、并把"哪一条其实是刺激"标出来。
 *
 * 形状(带/针/条)在 `CategoryLegend` 里和颜色一起给出 —— 色觉差异的用户看不出琥珀和
 * 玫红, 但看得出"一片底色"和"一根针"。
 *
 * <h2>规则: 这一页永远不显示消息正文</h2>
 *
 * 因为正文进入她只有一条路径 —— **她自己做出「看一眼手机」这个动作**。
 * 这个流里的 `USER_MESSAGE_READ` 正是那件事发生的**记录**; 运维看的是"她读过了",
 * 不是"她读到了什么"。所以这里渲染的是 payload 的字段摘要(`summarizePayload`),
 * 而不是任何消息内容。运维要看正文有一个专门的出口(`/v5/pending-messages`,
 * 见「运行状态」页), 那个出口的存在恰恰是为了让这一页可以干净。
 */
export function Events() {
  return (
    <RequireStudio why="事件流是她的运行时记录, 属于个人数据。">
      <Body />
    </RequireStudio>
  )
}

function Body() {
  const { agents, agentId, setAgentId, loading: agentsLoading, error: agentsError, reload: reloadAgents } =
    useAgentChoice()

  const world = useAsync(
    () => (agentId ? listWorldEvents(agentId) : Promise.resolve([] as WorldEventRow[])),
    [agentId],
  )

  // 词汇表 —— 平台级的, 与 agentId 无关, 所以它不吃 agentId 这个依赖。
  const catalog = useAsync(() => getEventTypeCatalog(), [])

  const [active, setActive] = useState<ReadonlySet<EventClass>>(() => new Set())

  const allRows = useMemo(() => toRows(world.data ?? [], agentId), [world.data, agentId])
  const rows = useMemo(() => filterByClass(allRows, active), [allRows, active])
  const counts = useMemo(() => tally(allRows), [allRows])

  const total = allRows.length

  /**
   * 这个窗口里的**观察到的类型**, 与目录里**登记过的类型**各是什么。
   *
   * 这是这一页上唯一一个真正的"对账": 一边随数据变, 一边随代码变, 而两者的差
   * 是可以算出来的。`matched` 那个数是它的关键 —— 当它是 0, 说明这个流里的名字
   * 与目录里的名字**根本不是同一套词汇**, 于是"哪几类从没发生过"这个问题今天
   * 没有意义(全部 47 条都没发生过)。那一天它不再是 0 的时候, 这一块会自己换一个
   * 说法, 不需要改代码。
   */
  const ledger = useMemo(
    () => reconcileCatalog(catalog.data, allRows.map((r) => r.type)),
    [catalog.data, allRows],
  )

  /**
   * 服务端给的中文名, 按**界面类别**索引。
   *
   * 界面用的是四个桶(`fact` 是"还没进她感知的世界事实", 不是目录里的一类), 目录里
   * 是三类。所以这张表只填能对上的三个 —— `fact` 没有对应项, 于是它回落到前端那份。
   * 这不是将就: `fact` 本来就不属于那三类机制, 它是它们的**上游**。
   */
  const categoryText = useMemo(() => {
    const m = new Map<EventClass, { label: string; note: string; count: number }>()
    for (const c of catalog.data?.categories ?? []) {
      const bucket = CLASS_OF_SERVER_CATEGORY[c.category]
      if (bucket) m.set(bucket, { label: c.label, note: c.note, count: c.count })
    }
    return m
  }, [catalog.data])

  /** 类别的中文名: **服务端有的就用服务端的**。两者曾经漂过(`感官实时` vs `实时感官`)。 */
  const serverLabel = (c: EventClass, fallback: string): string =>
    categoryText.get(c)?.label ?? fallback

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex min-w-0 items-center gap-1.5">
            <h1 className="text-base font-medium text-ink">事件流</h1>
            <InfoTip label="「事件流」这一页回答什么">
              这一页是<b className="font-medium text-ink">运维面</b>: 线上到底发生了几件事、
              有没有没登记的类型、有哪一条卡在台阶上没往下走。
              <br />
              <br />
              想知道她今天过得怎么样, 不在这一页 —— 那在「总览」和她那一侧的「数字人」里。
              两张面刻意分开: 一个只是好奇她今天干了什么的人, 在这页会看到一屏
              <span className="font-mono"> USER_MESSAGE_NOTIFIED </span>
              这样的机器名, 于是要么看不懂、要么学会忽略它。
            </InfoTip>
          </div>
          <p className="mt-1 max-w-2xl text-pretty text-xs leading-relaxed text-ink-faint">
            世界往她那儿递的每一件事, 按机制分成三类。
          </p>
        </div>
        <Button variant="ghost" onClick={() => { world.reload(); reloadAgents() }}>
          <RefreshCw size={13} />刷新
        </Button>
      </div>

      <AgentPickerBar
        agents={agents}
        agentId={agentId}
        onChange={setAgentId}
        loading={agentsLoading}
        error={agentsError}
        onReload={reloadAgents}
        right={
          <span className="text-xs text-ink-faint">
            共 <span className="font-mono text-ink tnum">{total}</span> 条
          </span>
        }
      />

      {world.error && <p className="text-sm text-danger">{describeError(world.error)}</p>}
      {world.loading && !world.data && <Empty>读取中…</Empty>}

      {world.data && total === 0 && agentId && (
        <Panel title="这个流是空的">
          <Empty>
            没有读到任何事件。
            <InfoTip label="空列表意味着什么">
              它在今天<b className="font-medium text-ink">多半是正常的</b>: 这个端点是一个
              滚动窗口, 而且今天线上只会往里写
              <span className="font-mono"> WORLD_EVENT_OCCURRED </span>
              一种类型。空 ≠ 她没有在活动, 只表示这个出口暂时没有东西可给。
              哪些东西读不到, 见页面底部的缺口说明。
            </InfoTip>
          </Empty>
        </Panel>
      )}

      {total > 0 && (
        <>
          <Panel
            title={
              <div className="flex min-w-0 items-center gap-1.5">
                <h2 className="text-sm font-medium tracking-wide text-ink">三类机制各有多少条</h2>
                <InfoTip label="为什么四个数加起来大于总数">
                  一条事件可以同时属于两类(§5.2: 一个事件可以实现多个能力接口 ——
                  手机响了既要立刻被听见, 又会在戴耳机时留下一个短时的听阈偏移)。
                  所以下面四个数加起来<b className="font-medium text-ink">可以大于</b>总数。
                </InfoTip>
              </div>
            }
          >
            <ClassTally counts={counts} />
          </Panel>

          <div className="grid gap-5 lg:grid-cols-[1fr_auto]">
            <Panel
              title="三类机制"
              action={<span className="text-xs text-ink-faint">点一下筛掉这一类</span>}
            >
              <ul className="space-y-1">
                {(['effect', 'sensory', 'schedule', 'fact'] as const).map((c) => {
                  const m = EVENT_CLASS_META[c]
                  const off = active.has(c)
                  return (
                    <li key={c}>
                      <button
                        type="button"
                        aria-pressed={off}
                        onClick={() => setActive(toggle(active, c))}
                        className={`flex w-full items-start gap-2.5 rounded-lg border px-3 py-1.5 text-left transition
                                    focus:outline-none focus-visible:ring-2 focus-visible:ring-accent/40
                                    ${off
                                      ? 'border-line-strong bg-sunken opacity-50'
                                      : `border-line ${m.bg} hover:border-line-strong`}`}
                      >
                        <span className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${m.dot}`} aria-hidden="true" />
                        <span className="min-w-0">
                          <span className="flex items-baseline gap-2 text-xs">
                            <span className={`font-medium ${m.text}`}>{serverLabel(c, m.label)}</span>
                            <span className="font-mono text-[10px] text-ink-faint tnum">{counts[c]}</span>
                            {off && <span className="text-[10px] text-ink-faint">已筛掉</span>}
                          </span>
                          <span className="mt-0.5 block text-pretty text-[11px] leading-relaxed text-ink-faint">
                            {m.hint}
                          </span>
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
              {active.size > 0 && (
                <Button variant="ghost" onClick={() => setActive(new Set())}>全部显示</Button>
              )}
            </Panel>

            <Panel
              className="lg:w-72"
              title={
                <div className="flex min-w-0 items-center gap-1.5">
                  <h2 className="text-sm font-medium tracking-wide text-ink">时间轴上的形状</h2>
                  <InfoTip label="图例里为什么画形状">
                    三类事件在时间轴上是<b className="font-medium text-ink">三种形状</b>:
                    A 类是一片一直延伸的带子(A 类没有结束时间), B 类是一根不占时间的针,
                    C 类就是那些条子本身。
                    <br />
                    <br />
                    图例里画出形状, 是为了让"哪个是哪一类"不必靠颜色去记 —— 色觉差异的
                    用户看不出琥珀和玫红, 但看得出"一片底色"和"一根针"。
                  </InfoTip>
                </div>
              }
            >
              <CategoryLegend classes={['effect', 'sensory', 'schedule']} showHint={false} />
            </Panel>
          </div>

          <Panel
            title={
              <div className="flex min-w-0 items-center gap-1.5">
                <h2 className="text-sm font-medium tracking-wide text-ink">
                  事件记录 ({rows.length}{rows.length !== total ? ` / ${total}` : ''})
                </h2>
                <InfoTip label="一条消息到她手里要经过哪五步">
                  一条消息到她手里要经过五个
                  <b className="font-medium text-ink">各自独立</b>的动作,
                  它们在流里是五条不同的记录:
                  <br />
                  <br />
                  <span className="block space-y-1">
                    <span className="block">1. <span className="font-mono">USER_MESSAGE_RECEIVED</span> — 聊天平台落库了。这是世界的事实, 她还不知道。</span>
                    <span className="block">2. <span className="font-mono">USER_MESSAGE_NOTIFIED</span> — 通知信号到了手机, 手机响了。这是实时感官刺激。</span>
                    <span className="block">3. <span className="font-mono">USER_MESSAGE_NOTICED</span> — 她感知到了声音。此时她仍然不知道是谁、说了什么。</span>
                    <span className="block">4. <span className="font-mono">USER_MESSAGE_READ</span> — 她自己做出了「看一眼手机」的动作。正文到此才第一次进入她。</span>
                    <span className="block">5. <span className="font-mono">USER_MESSAGE_DEFERRED</span> — 她决定先不处理。已读不回是一个决定, 不是故障。</span>
                  </span>
                  <br />
                  第 2 条与第 1 条不是同一件事 —— 这正是 §2.3 要修的那个技术债: 把"送达"和
                  "她注意到了"画成同一个状态, 于是永远分不清"平台没送到"和"她没看见"。
                  在这一页上它们是两条可以分别计数的记录。
                </InfoTip>
              </div>
            }
            action={
              <span className="flex items-center gap-1 text-xs text-ink-faint">
                新的在上
                <InfoTip label="每行下面那串字是什么" align="end">
                  每行下面的那串 <span className="font-mono">字段=值</span> 是 payload 的
                  <b className="font-medium text-ink">字段摘要</b>, 不是消息内容。
                  <br />
                  <br />
                  这条规则是硬的: 正文进入她只有一条路径 —— 她自己去看。要看正文有另一个
                  出口(见下方面板)。
                </InfoTip>
              </span>
            }
          >
            {rows.length === 0 ? (
              <Empty>当前筛选下一条都不剩。</Empty>
            ) : (
              <ul className="-mx-1">
                {rows.map((r) => <EventRow key={r.key} row={r} />)}
              </ul>
            )}
          </Panel>
        </>
      )}

      <Panel title="这一页读不到什么">
        <div className="grid gap-3 lg:grid-cols-3">
          <GapNote
            title="没有跨数字人的事件流"
            tip={
              <>
                这一页每次只能看<b className="font-medium text-ink">一个</b>数字人 ——
                因为线上的每一个事件端点都挂在
                <span className="font-mono"> /api/companions/{'{id}'}/ </span>
                下面。运维真正想问的那个问题(「刚刚全平台发生了什么」)因此答不了。
                <br />
                <br />
                缺的端点: <span className="font-mono">GET /api/v10/events?since=&amp;type=&amp;limit=</span>,
                按时间倒序、跨数字人、带 <span className="font-mono">agentId</span> 字段。
              </>
            }
          />
          <GapNote
            title="返回体里还没有类别字段"
            tip={
              <>
                §7.2 的 <span className="font-mono">world_event</span> 表有一个
                <span className="font-mono"> category </span>列
                (<span className="font-mono">STATE_EFFECT / SENSORY / SCHEDULED</span>,
                逗号分隔可多值), 而线上的返回体里<b className="font-medium text-ink">只有 type 字符串</b>。
                <br />
                <br />
                所以这一页的分类暂时靠前端一张译表(<span className="font-mono">lib/events.ts</span>
                的 <span className="font-mono">CLASS_OF_TYPE</span>)。那份译表在服务端把
                <span className="font-mono"> category </span>发出来之后就该删掉 —— 它现在的价值是
                "让三类机制在界面上先成立", 风险是"它会和调度器漂"。
                <span className="font-mono"> eventClassesOf() </span>已经写成"服务端给了就采信",
                所以那一天不需要改页面。
              </>
            }
          />
          <GapNote
            title="窗口只有 50 条, 且今天只有一种类型"
            tip={
              <>
                这个端点是<b className="font-medium text-ink">滚动 50 条</b>的, 所以"事件流"
                在这里其实是"最近 50 条"。而今天线上往它里面写的只有
                <span className="font-mono"> WORLD_EVENT_OCCURRED </span>—— 上面那些台阶记录
                目前是从别处(认知链的日志)产生的, 还没汇进这一条流。
                <br />
                <br />
                因此现在打开这一页多半会看到一片同一种类型。这不是页面坏了, 是数据源还很窄:
                缺的是一条把 EventFabric(<span className="font-mono">boundary/event/*</span>)
                的写入镜像一份到可查询存储的通道。
              </>
            }
          />
        </div>
      </Panel>

      <Panel
        title={
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-sm font-medium tracking-wide text-ink">词汇表: 这个世界能发生哪些事</h2>
            <InfoTip label="这份清单是从哪来的">
              来自 <span className="font-mono">GET /api/meta/event-types</span>, 是
              <b className="font-medium text-ink">运行时从服务端读来的</b>, 不是前端抄下来的一份 ——
              第三方应用随时可以往注册表里加自己的类型。
              <br />
              <br />
              所以它读不到时这一块是空的, 而事件流本身照常显示: 两者没有依赖关系。
            </InfoTip>
          </div>
        }
      >
        {catalog.loading && !catalog.data && <Empty>读取中…</Empty>}

        {catalog.error && (
          <p className="text-pretty text-sm text-danger">
            读不到词汇表: {describeError(catalog.error)}
            <span className="mt-1 block text-[11px] leading-relaxed text-ink-faint">
              目录读不到不影响下面这条流 —— 事件照常显示。
            </span>
          </p>
        )}

        {catalog.data && (
          <div className="space-y-4">
            <p className="text-xs leading-relaxed text-ink-soft">
              共 <span className="font-mono tnum">{catalog.data.count}</span> 条事件类型, 分布在{' '}
              <span className="font-mono tnum">{catalog.data.namespaces.length}</span> 个命名空间里,
              按机制分成三类。
            </p>

            <div className="grid gap-3 sm:grid-cols-3">
              {catalog.data.categories.map((c) => (
                <div key={c.category} className="rounded-lg border border-line px-3 py-2">
                  <div className="flex items-baseline gap-2">
                    <span className="text-xs font-medium text-ink">{c.label}</span>
                    <span className="font-mono text-[10px] text-ink-faint">{c.category}</span>
                    <span className="ml-auto font-mono text-sm text-ink tnum">{c.count}</span>
                  </div>
                  <p className="mt-1 text-pretty text-[11px] leading-relaxed text-ink-faint">{c.note}</p>
                </div>
              ))}
            </div>

            {/*
              没被认领的 —— 这是这份返回体里唯一一个"非空就说明有事"的字段,
              所以它单独一块, 不和上面那三个统计数字混在一起。
            */}
            <div
              className={`rounded-lg border px-3 py-2 ${
                catalog.data.unclaimed.length > 0 ? 'border-cat-effect/40 bg-cat-effect/10' : 'border-line'
              }`}
            >
              <p className="flex items-center gap-1.5 text-xs font-medium text-ink">
                <span>
                  没有消费者的类型:{' '}
                  {catalog.data.unclaimed.length === 0 ? (
                    <span className="font-normal text-ink-faint">没有 —— 每一条都有人接着</span>
                  ) : (
                    <span className="font-mono tnum">{catalog.data.unclaimed.length}</span>
                  )}
                </span>
                <InfoTip label="「没有消费者」是什么意思">
                  一条登记了却没有消费者的事件, 要么是留给将来的, 要么是某个 handler 的
                  订阅键写错了。这两种情况在别处长得一模一样, 所以它由服务端算出来 ——
                  四十几条逐条比对不该是人干的活。
                  <br />
                  <br />
                  「一条都没有」是<b className="font-medium text-ink">健康状态</b>:
                  目录里每一条都有明确的消费者。
                </InfoTip>
              </p>
              {catalog.data.unclaimed.length > 0 && (
                <ul className="mt-1.5 space-y-0.5">
                  {catalog.data.unclaimed.map((t) => (
                    <li key={t} className="font-mono text-[11px] text-ink-soft">{t}</li>
                  ))}
                </ul>
              )}
            </div>

            {/*
              对账。这一段是这一页唯一"用两份数据算出第三个事实"的地方 ——
              它的说法随 `matchedCount` 变, 而那个数随数据变。
            */}
            <div className="rounded-lg border border-line px-3 py-2">
              <p className="flex items-center gap-1.5 text-xs font-medium text-ink">
                <span>目录与实际的对账</span>
                <InfoTip label="这两个数为什么在这里对">
                  这一段的一个数随代码变、一个随数据变, 所以它们是
                  <b className="font-medium text-ink">各取一份、在这里对一次</b>的, 而不是服务端
                  合成的一份 —— 那样会让"这个世界能发生什么"与"今天发生了什么"互相污染。
                </InfoTip>
              </p>
              {ledger.matchedCount === 0 ? (
                <p className="mt-1 text-pretty text-[11px] leading-relaxed text-ink-soft">
                  这个窗口里有 <span className="font-mono tnum">{ledger.observedCount}</span> 种事件名,
                  而它们<b className="font-medium text-ink">没有一条</b>属于上面这份目录 ——
                  两套词汇现在是分开的: 目录用
                  <span className="font-mono"> namespace.name.vN </span>(新的事件结构),
                  而这个流里跑的是 <span className="font-mono">WorldEventType</span> 那几个大写名字
                  (旧的认知链)。
                </p>
              ) : (
                <>
                  <p className="mt-1 text-pretty text-[11px] leading-relaxed text-ink-soft">
                    目录 {catalog.data.count} 条里, 这个窗口出现过{' '}
                    <span className="font-mono tnum">{ledger.matchedCount}</span> 条;
                    从没出现过的 <span className="font-mono tnum">{ledger.neverSeen.length}</span> 条。
                  </p>
                  <ul className="mt-1.5 space-y-0.5">
                    {ledger.neverSeen.slice(0, 12).map((t) => (
                      <li key={t.type} className="text-[11px] text-ink-faint">
                        <span className="font-mono">{t.type}</span>
                        {t.semantics ? <span className="ml-2">{t.semantics}</span> : null}
                      </li>
                    ))}
                  </ul>
                  {ledger.neverSeen.length > 12 && (
                    <p className="mt-1 text-[11px] text-ink-faint">
                      还有 {ledger.neverSeen.length - 12} 条没列出来。
                    </p>
                  )}
                </>
              )}
              {ledger.outside.length > 0 && (
                <p className="mt-2 text-pretty text-[11px] leading-relaxed text-ink-faint">
                  反过来, 这个流里有{' '}
                  <span className="font-mono tnum">{ledger.outside.length}</span> 个名字不在目录里
                  {ledger.matchedCount === 0 ? '(就是上面那些全部)' : ''} —— 它们不是"未登记的错误",
                  只是另一套词汇。
                </p>
              )}
            </div>

            <details className="rounded-lg border border-line px-3 py-2">
              <summary className="cursor-pointer text-xs font-medium text-ink hover:text-accent">
                按命名空间看那 {catalog.data.namespaces.length} 族
              </summary>
              <div className="mt-2 space-y-2">
                {catalog.data.namespaces.map((ns) => (
                  <div key={ns.namespace}>
                    <p className="font-mono text-[11px] text-ink-soft">
                      {ns.namespace}.* <span className="text-ink-faint">({ns.count})</span>
                    </p>
                    <ul className="mt-0.5 space-y-0.5">
                      {ns.types.map((t) => (
                        <li key={t.type} className="text-[11px] leading-relaxed text-ink-faint">
                          <span className="font-mono text-ink-soft">{t.name}</span>
                          <span className="ml-2">{t.categoryLabel}</span>
                          {t.channel ? <span className="ml-2">通道 {t.channel}</span> : null}
                          {t.modality ? <span className="ml-2">感官 {t.modality}</span> : null}
                          {!t.claimed ? <span className="ml-2 text-cat-effect">未认领</span> : null}
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </details>
          </div>
        )}
      </Panel>
    </div>
  )
}

// ── 纯函数 ──────────────────────────────────────────────────────────────────

/** 筛掉某一类。返回新集合 —— 就地改 Set 不会触发重渲染, 那是最难查的一类"点了没反应"。 */
export function toggle(active: ReadonlySet<EventClass>, c: EventClass): Set<EventClass> {
  const next = new Set(active)
  if (next.has(c)) next.delete(c)
  else next.add(c)
  return next
}

/**
 * 事件行 → 界面行。
 *
 * <h2>key 为什么要带上序号</h2>
 *
 * 同一毫秒里可以有两条 `WORLD_EVENT_OCCURRED`(批量写入时真的会发生)。
 * 只按 `type@at` 拼 key 会让 React 收到重复 key —— 表现为其中一条**不渲染**,
 * 而屏幕上少一条记录是最容易被忽略的错。
 */
export function toRows(events: readonly WorldEventRow[], source?: string): EventRowData[] {
  return events
    .map((e, i) => ({
      key: `${e.type}@${e.at ?? '?'}#${i}`,
      type: e.type,
      at: e.at ?? '',
      classes: eventClassesOf(e),
      summary: summarizePayload(e.payload),
      source,
    }))
    // 新的在上。没有时间的排在最后 —— 它们放哪儿都不对, 那就不占最上面那一屏。
    .sort((a, b) => timeOf(b.at) - timeOf(a.at))
}

/** 一条记录的排序时刻。解析不出来给 -Infinity, 于是它沉底而不是跑到最上面。 */
function timeOf(at: string): number {
  const t = Date.parse(at)
  return Number.isFinite(t) ? t : Number.NEGATIVE_INFINITY
}

/**
 * 按"还剩下哪几类"过滤。
 *
 * 语义取的是**交集**: 一条事件同时是 sensory + effect 时, 只要这两类里还有一类没被
 * 筛掉, 它就留下。用并集(筛掉任一类就隐藏)会让"我只想看感官事件"这个动作顺手把
 * 那些"同时也是感官"的事件一起丢掉 —— 而用户不会知道。
 */
export function filterByClass(
  rows: readonly EventRowData[],
  active: ReadonlySet<EventClass>,
): EventRowData[] {
  if (active.size === 0) return [...rows]
  return rows.filter((r) => r.classes.some((c) => !active.has(c)))
}

/** 一条事件的中文名 —— 页面里凡是显示类型的地方都走它, 免得两处译法不一致。 */
export const labelOf = eventLabelZh
