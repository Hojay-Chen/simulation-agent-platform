import { useState } from 'react'
import { Play, RefreshCw, Volume2, VolumeX } from 'lucide-react'
import { explainPerception } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { Button, ErrorNote, Field, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { AwarenessLadder, MessageLadder } from '@/components/viz/Gauge'
import { GapNote } from '@/components/viz/panels'

/**
 * 「手机」—— 她的手机会不会吵到她。
 *
 * <h2>这一页为什么是"推演台"而不是"设备面板"</h2>
 *
 * 因为它回答了那个**真正被人问的问题**。"她的手机会响吗"这个问题, 换成设备面板的
 * 说法就是"把电量、音量、应用列表摊开"—— 而摊开之后没人能回答原问题, 因为答案不在
 * 任何一个字段里, 它由四层共同决定:
 *
 * 1. **聊天平台**的会话免打扰(`ConversationNotificationSetting.muted`)—— 决定
 *    **要不要发出**通知信号。这一层在聊天平台上, 不在她这边。
 * 2. **手机**的通知策略(`NotificationPolicy`)—— 决定信号到了**怎么响**。
 * 3. **她的活动** —— 决定她**有没有可能**注意到(睡着了就是不可能)。
 * 4. **她的注意力** —— 决定同样的刺激这次进不进得来(`AttentionService`)。
 *
 * 所以这一页的形态是: 给一个输入(什么样的刺激、多重要), 让服务端把这四层跑一遍,
 * 把每一层的输出**分开显示**。用户可以自己改那个输入来回试 —— 它是只读的仿真,
 * 点多少次都不会真的打扰她。
 *
 * <h2>为什么"免打扰"要分成两层说</h2>
 *
 * 因为这是这个产品里最容易被实现成一件事的地方, 而实现成一件事之后有一个**具体的**
 * 坏结果: 用户在聊天平台上把某个会话设成免打扰, 于是以为"她不会被打扰了";
 * 而手机自己的闹钟、日程提醒照响不误 —— 然后在某天早上被一个"她怎么还是被吵醒了"
 * 的问题问住。两层各有各的所有者, 页面必须把它们摆成两行。
 */
export function Phone({ agentId }: { agentId: string }) {
  const [eventType, setEventType] = useState<string>('CHAT_MESSAGE_DELIVERED')
  const [importance, setImportance] = useState(0.6)
  /** 已提交的输入。与上面两个分开, 是为了"改滑块"不每动一下就发一次请求。 */
  const [probe, setProbe] = useState({ eventType: 'CHAT_MESSAGE_DELIVERED', importance: 0.6 })

  /*
   * `useAsync` 在挂载时就会跑一次, 所以进页面立刻有东西看 —— 不用额外补一次
   * "初始化请求"。页面打开时那一屏就是**此刻的报告**, 而不是一个等着被操作的工具。
   */
  const explain = useAsync(
    () => explainPerception(agentId, probe.eventType, probe.importance),
    [agentId, probe.eventType, probe.importance],
  )

  const d = explain.data

  return (
    <div className="space-y-5">
      <Panel
        title="推演: 这样一条刺激到了她那儿, 会不会被注意到"
        action={
          <Button variant="ghost" onClick={explain.reload}>
            <RefreshCw size={13} />重跑一次
          </Button>
        }
      >
        <p className="mb-4 text-xs leading-relaxed text-ink-faint">
          这是**只读的仿真**: 它把这条刺激在四层(聊天平台免打扰 / 手机策略 / 她的活动 /
          她的注意力)里各跑一遍, 然后原样返回每一层的判定。点多少次都不会真的打扰她,
          也不会在她那边留下任何痕迹。
        </p>

        <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
          <Field label="刺激的类型" hint="后端 `ExternalEventType` 里的取值。">
            <select
              className="input"
              value={eventType}
              onChange={(e) => setEventType(e.target.value)}
            >
              {EVENT_TYPES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
          </Field>

          <Field
            label={`这条刺激有多重要 · ${importance.toFixed(2)}`}
            hint="这是**刺激本身**的属性(salience), 不是她的状态。她的状态由服务端自己取。"
          >
            <div className="flex items-center gap-3 pt-2">
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={importance}
                onChange={(e) => setImportance(Number(e.target.value))}
                className="w-40 accent-accent"
                aria-label="刺激的重要程度"
              />
              <span className="w-10 font-mono text-xs text-ink-faint tnum">{importance.toFixed(2)}</span>
            </div>
          </Field>
        </div>

        <div className="mt-3 flex items-center gap-3">
          <Button
            disabled={explain.loading}
            onClick={() => setProbe({ eventType, importance })}
          >
            <Play size={13} />{explain.loading ? '推演中…' : '推演'}
          </Button>
          {probe.eventType !== eventType || probe.importance !== importance ? (
            <span className="text-xs text-warn">输入改过了 —— 上面的结果是上一次的。</span>
          ) : (
            <span className="text-xs text-ink-faint">
              当前展示: {labelOfEventType(probe.eventType)} · 重要度 {probe.importance.toFixed(2)}
            </span>
          )}
        </div>

        <ErrorNote error={explain.error ? describeError(explain.error) : null} />
      </Panel>

      {d && (
        <>
          <div className="grid gap-5 lg:grid-cols-3">
            {/* ① 感知 */}
            <Panel title="① 她感知到了吗">
              <AwarenessLadder
                score={d.perception.score}
                level={d.perception.level}
                thresholds={d.thresholds}
              />
              <dl className="mt-4 space-y-1.5 text-xs">
                <Row k="strategy" v={d.perception.strategy} />
                <Row k="triggersCognition" v={d.perception.triggersCognition ? '是 —— 会进认知链' : '否 —— 到此为止'} />
              </dl>
              <p className="mt-3 text-[11px] leading-relaxed text-ink-faint">
                {d.perception.triggersCognition
                  ? '这一条会进入认知链, 她会对它做出反应。'
                  : '这一条不会进入认知链 —— 她可能"隐约感到"了一下, 但不会为它停下来。这不是丢弃, 是一条明确的判定。'}
              </p>
            </Panel>

            {/* ② 手机 */}
            <Panel title="② 她的手机处在什么状态">
              <dl className="space-y-1.5 text-xs">
                <Row
                  k="notificationMode"
                  v={notificationModeZh(d.device.notificationMode)}
                  hint="手机这一层的通知模式。它由手机的 NotificationPolicy 决定 —— **和聊天平台的会话免打扰是两件事**。"
                />
                <Row
                  k="doNotDisturb"
                  v={d.device.doNotDisturb ? '开着' : '关着'}
                  hint="手机自己的免打扰。它决定信号到了之后**怎么响**, 不决定要不要发。"
                />
                <Row
                  k="phoneLocation"
                  v={phoneLocationZh(d.device.phoneLocation)}
                  hint="手机在哪儿。放在另一个房间和拿在手里, 对「她能不能听见」是完全不同的两件事。"
                />
              </dl>
              <div className="mt-4 rounded-lg border border-line bg-sunken/40 px-3 py-2">
                <p className="flex items-center gap-1.5 text-xs text-ink-soft">
                  {d.device.doNotDisturb
                    ? <VolumeX size={13} className="text-cat-sensory" />
                    : <Volume2 size={13} className="text-ok" />}
                  这一层现在会{notificationModeZh(d.device.notificationMode)}
                </p>
              </div>
            </Panel>

            {/* ③ 她 */}
            <Panel title="③ 她此刻在做什么、还剩多少余量">
              <dl className="space-y-1.5 text-xs">
                <Row k="life.description" v={d.life.description} />
                <Row k="life.activity" v={d.life.activity} />
                <Row
                  k="life.attentionDemand"
                  v={d.life.attentionDemand}
                  hint="她手上的事要占多少注意力。占得越多, 同样的刺激越进不来。"
                />
                <Row
                  k="life.sleeping"
                  v={d.life.sleeping ? '睡着' : '醒着'}
                  hint="睡着的时候, 视觉通道整个关闭 —— 手机响了也看不见。"
                />
                <Row k="mind.focus" v={num(d.mind.focus)} />
                <Row k="mind.energy" v={num(d.mind.energy)} />
              </dl>
            </Panel>
          </div>

          {/* ④ 决策 */}
          <Panel title="④ 她的决定">
            <div className="space-y-3">
              <p className="text-sm leading-relaxed text-ink">{d.decision.reason}</p>
              <dl className="grid gap-1.5 sm:grid-cols-2">
                <Row k="policy" v={d.decision.policy} />
                <Row k="type" v={d.decision.type} />
              </dl>
              <p className="text-[11px] leading-relaxed text-ink-faint">
                `policy` 是**哪条规则**做出的这个判断, `type` 是决定的种类。两者一起
                回答"为什么是这个反应"—— 它们不是给人读的文案, 是给排查用的定位信息,
                所以原样显示。
              </p>
            </div>
          </Panel>
        </>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <Panel title="两层免打扰, 谁也替不了谁">
          <div className="space-y-3 text-xs leading-relaxed">
            <div className="rounded-lg border border-line bg-sunken/40 px-4 py-3">
              <p className="font-medium text-ink">第一层 · 聊天平台: 要不要**发出**通知</p>
              <p className="mt-1 text-ink-faint">
                一个会话被设成免打扰之后, 聊天平台**根本不发**那条通知信号。
                她那边什么都不会收到 —— 连"隐约感到"都没有。这一层属于聊天平台,
                在 <span className="font-mono">chat.luxera.top</span> 的会话设置里改。
              </p>
            </div>
            <div className="rounded-lg border border-line bg-sunken/40 px-4 py-3">
              <p className="font-medium text-ink">第二层 · 她的手机: 信号到了**怎么响**</p>
              <p className="mt-1 text-ink-faint">
                信号到了之后, 手机按自己的 <span className="font-mono">NotificationPolicy</span>
                (通知音量 / 铃声 / 闹钟 / 媒体四个通道 + 免打扰) 决定响不响、多响。
                这一层属于她, 在「手机」这一页。
              </p>
            </div>
            <p className="text-ink-faint">
              两层都没拦的时候她仍然可能"没听见"—— 那第三种情况来自她的**活动和注意力**
              (上面 ③④ 两栏)。三种"没反应"的原因完全不同, 而界面上必须能分辨:
              把第一层的原因说成第三层, 会让人去调她的专注度, 而真正该做的是改会话设置。
            </p>
          </div>
        </Panel>

        <Panel title="一条消息到她手里的五级台阶">
          <MessageLadder />
          <p className="mt-3 text-[11px] leading-relaxed text-ink-faint">
            第 4 级(她自己去看)是消息正文进入她的**唯一**路径。第 3 级之前,
            她连"是谁发的"都不知道 —— 通知里只有 meta, 没有正文也没有发信人。
          </p>
        </Panel>
      </div>

      <Panel title="这一页读不到什么">
        <div className="grid gap-3 lg:grid-cols-2">
          <GapNote title="四个声道的音量">
            <p>
              上面 ② 那三个字段是 `perception/explain` 顺带给的设备快照。V2.2 §4.2 里
              真正那套 `AudioSystem`(NOTIFICATION / RINGTONE / ALARM / MEDIA 四个 0–1 的
              连续音量)整个 <span className="font-mono">phone/</span> 包(11 个类)
              <b>没有任何 HTTP 面</b>。
            </p>
            <p>
              缺的端点: <span className="font-mono">GET /api/companions/{'{id}'}/world/devices</span>。
            </p>
            <p>
              所以这一页**不能**画那四个滑杆 —— 画出来就只能是一组假数字, 而假数字在
              一个"她会不会被吵到"的页面上是最不该出现的东西。
            </p>
          </GapNote>
          <GapNote title="电量、应用列表、她的通讯录">
            <p>
              §4.3 的 `DeviceApplication`(她在手机上装了哪些应用、每个应用能不能发通知)
              同样没有读取面。§4.4 的 `ChatApplication`(她自己那份聊天软件里的会话、
              免打扰)也没有。
            </p>
            <p>
              这一块的关键在 §4.1 的分界: **数字设备世界**是她可以改的
              (装应用、调音量), **数字世界**不是。前端目前读到的两个世界混在
              `perception/explain` 一个返回体里, 分不出哪个字段归哪一边。
            </p>
          </GapNote>
        </div>
      </Panel>
    </div>
  )
}

function Row({ k, v, hint }: { k: string; v?: string | number | null; hint?: string }) {
  return (
    <div className="flex items-baseline gap-2" title={hint}>
      <dt className="shrink-0 font-mono text-ink-faint">{k}</dt>
      <dd className="min-w-0 text-ink-soft">
        {v === undefined || v === null || v === '' ? '—' : v}
      </dd>
    </div>
  )
}

const num = (v: number | undefined) => (typeof v === 'number' ? v.toFixed(2) : undefined)

/** 后端 `ExternalEventType` 的取值, 加一句人话。 */
const EVENT_TYPES = [
  { value: 'CHAT_MESSAGE_DELIVERED', label: '聊天消息送达(最常推演的一种)' },
  { value: 'DEVICE_NOTIFICATION', label: '设备通知弹出' },
  { value: 'APPLICATION_EVENT', label: '应用事件(游戏/小程序)' },
  { value: 'LIFE_EVENT', label: '生活事件(日程/活动)' },
  { value: 'TIME_EVENT', label: '时间事件(定时器/节律)' },
  { value: 'ENVIRONMENT_EVENT', label: '环境事件(温度/气味)' },
] as const

function labelOfEventType(v: string): string {
  return EVENT_TYPES.find((t) => t.value === v)?.label ?? v
}

/**
 * 通知模式的中文。
 *
 * 词条写成**动词短语**而不是名词 —— 这个值在页面上是被嵌进一句话里用的
 * ("这一层现在会……"), 而"SOUND → 声音"拼出来是"这一层现在会声音"。认不出就
 * 原样返回机器名: 一个编出来的中文词会让排查的人看不到真正的取值。
 */
const NOTIFICATION_MODE_ZH: Record<string, string> = {
  SILENT: '静音不响',
  VIBRATE: '只震动',
  SOUND: '响一声',
  RINGTONE: '按铃声的响度响',
  ALARM: '按闹钟的响度响',
  DND: '被免打扰拦下',
  NORMAL: '正常响',
}

function notificationModeZh(mode: string | undefined): string {
  if (!mode) return '—'
  return NOTIFICATION_MODE_ZH[mode] ?? mode
}

const PHONE_LOCATION_ZH: Record<string, string> = {
  HAND: '拿在手里',
  POCKET: '揣在兜里',
  BAG: '放在包里',
  DESK: '搁在桌上',
  ANOTHER_ROOM: '在另一个房间',
  CHARGING: '正在充电',
  UNKNOWN: '不知道在哪',
}

function phoneLocationZh(loc: string | undefined): string {
  if (!loc) return '—'
  return PHONE_LOCATION_ZH[loc] ?? loc
}
