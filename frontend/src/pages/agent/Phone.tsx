import { useState } from 'react'
import { Play, RefreshCw, Volume2, VolumeX } from 'lucide-react'
import { explainPerception } from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { Button, ErrorNote, Field, InfoTip, Panel } from '@/components/ui'
import { describeError } from '@/components/Section'
import { AwarenessLadder, MessageLadder } from '@/components/viz/Gauge'

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
        title={
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-sm font-medium text-ink">推演: 她会注意到吗</h2>
            <InfoTip label="这个推演是真的在打扰她吗">
              不是 —— 这是<b>只读的仿真</b>: 它把这条刺激在四层(聊天平台免打扰 / 手机策略 / 她的活动 /
              她的注意力)里各跑一遍, 然后原样返回每一层的判定。点多少次都不会真的打扰她,
              也不会在她那边留下任何痕迹。
            </InfoTip>
          </div>
        }
        action={
          <Button variant="ghost" onClick={explain.reload}>
            <RefreshCw size={13} />重跑一次
          </Button>
        }
      >
        <div className="grid gap-4 sm:grid-cols-[1fr_auto]">
          <Field
            label="刺激的类型"
            hint={<InfoTip label="这里为什么用机器取值">列表是后端支持的全部刺激类型, 原样列出, 方便和日志里的取值对上。</InfoTip>}
          >
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
            hint={
              <InfoTip label="这个数字是谁的" align="end">
                这是<b>刺激本身</b>的属性(有多显眼), 不是她的状态。她此刻的状态由服务端自己去取,
                所以拖这个滑块只改"来的这条消息", 不改她。
              </InfoTip>
            }
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
                <Row
                  k="strategy"
                  label="判定用的策略"
                  v={d.perception.strategy}
                  hint="感知用哪条策略判的 —— 机器取值, 排查时用得上。"
                />
                <Row
                  k="triggersCognition"
                  label="会不会进认知链"
                  v={d.perception.triggersCognition ? '是 —— 会进认知链' : '否 —— 到此为止'}
                  hint="进了认知链, 她才会对它做出反应。"
                />
              </dl>
              <p className="mt-3 text-pretty text-[11px] leading-relaxed text-ink-soft">
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
                  label="通知模式"
                  v={notificationModeZh(d.device.notificationMode)}
                  hint="手机这一层的通知模式, 由手机自己的通知策略决定 —— 和聊天平台的会话免打扰是两件事。"
                />
                <Row
                  k="doNotDisturb"
                  label="手机免打扰"
                  v={d.device.doNotDisturb ? '开着' : '关着'}
                  hint="手机自己的免打扰。它决定信号到了之后怎么响, 不决定要不要发。"
                />
                <Row
                  k="phoneLocation"
                  label="手机在哪"
                  v={phoneLocationZh(d.device.phoneLocation)}
                  hint="放在另一个房间和拿在手里, 对「她能不能听见」是完全不同的两件事。"
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
                <Row k="life.description" label="她此刻" v={d.life.description} />
                <Row k="life.activity" label="手上的活动" v={d.life.activity} />
                <Row
                  k="life.attentionDemand"
                  label="要占多少注意力"
                  v={d.life.attentionDemand}
                  hint="她手上的事要占多少注意力。占得越多, 同样的刺激越进不来。"
                />
                <Row
                  k="life.sleeping"
                  label="睡着了吗"
                  v={d.life.sleeping ? '睡着' : '醒着'}
                  hint="睡着的时候, 视觉通道整个关闭 —— 手机响了也看不见。"
                />
                <Row
                  k="mind.focus"
                  label="专注"
                  v={num(d.mind.focus)}
                  hint="注意力的实际输入。它高的时候, 一条普通消息连「隐约感到」都到不了。"
                />
                <Row
                  k="mind.energy"
                  label="精力"
                  v={num(d.mind.energy)}
                  hint="她还能撑多久。"
                />
              </dl>
            </Panel>
          </div>

          {/* ④ 决策 */}
          <Panel
            title={
              <div className="flex min-w-0 items-center gap-1.5">
                <h2 className="text-sm font-medium text-ink">④ 她的决定</h2>
                <InfoTip label="下面这两行是给谁看的">
                  <span className="font-mono">policy</span> 是哪条规则做出的这个判断,
                  <span className="mx-1 font-mono">type</span> 是决定的种类。两者一起回答"为什么是这个反应" ——
                  它们不是给人读的文案, 是排查用的定位信息, 所以原样显示。
                </InfoTip>
              </div>
            }
          >
            <div className="space-y-3">
              <p className="text-pretty text-sm leading-relaxed text-ink">{d.decision.reason}</p>
              <dl className="grid gap-1.5 sm:grid-cols-2">
                <Row k="policy" label="依据的规则" v={d.decision.policy} />
                <Row k="type" label="决定的种类" v={d.decision.type} />
              </dl>
            </div>
          </Panel>
        </>
      )}

      <div className="grid gap-5 lg:grid-cols-2">
        <div className="flex items-start gap-2 rounded-xl border border-line bg-raised px-4 py-3">
          <InfoTip tone="warn" label="两层免打扰分别是谁管的">
            <div className="space-y-3">
              <div>
                <p className="font-medium text-ink">第一层 · 聊天平台: 要不要<b>发出</b>通知</p>
                <p className="mt-1">
                  一个会话被设成免打扰之后, 聊天平台<b>根本不发</b>那条通知信号。
                  她那边什么都不会收到 —— 连"隐约感到"都没有。这一层属于聊天平台,
                  在 <span className="font-mono">chat.luxera.top</span> 的会话设置里改。
                </p>
              </div>
              <div>
                <p className="font-medium text-ink">第二层 · 她的手机: 信号到了<b>怎么响</b></p>
                <p className="mt-1">
                  信号到了之后, 手机按自己的通知策略(通知音量 / 铃声 / 闹钟 / 媒体四个通道 + 免打扰)
                  决定响不响、多响。这一层属于她, 就画在这一页上面那两栏里。
                </p>
              </div>
              <div>
                <p className="font-medium text-ink">第三层 · 她自己的活动与注意力</p>
                <p className="mt-1">
                  两层都没拦的时候她仍然可能"没听见" —— 那来自她手上在做的事和还剩多少余量(上面 ③④ 两栏)。
                  三种"没反应"的原因完全不同: 把第一层的原因说成第三层, 会让人去调她的专注度,
                  而真正该做的是去改会话设置。
                </p>
              </div>
            </div>
          </InfoTip>
          <p className="text-xs leading-relaxed text-ink-soft">
            免打扰有<b>两层</b>: 聊天平台决定要不要发出通知, 她的手机决定到了怎么响。
            再加上她自己的活动与注意力 —— 三种"没反应"的原因完全不同。
          </p>
        </div>

        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">一条消息到她手里的五步</h2>
              <InfoTip label="为什么要分五步">
                第 4 步(她自己去看)是消息正文进入她的<b>唯一</b>路径。第 3 步之前,
                她连"是谁发的"都不知道 —— 通知里只有 meta, 没有正文也没有发信人。
              </InfoTip>
            </div>
          }
        >
          <MessageLadder />
        </Panel>
      </div>

      <div className="flex items-start gap-2 rounded-xl border border-line bg-raised px-4 py-3">
        <InfoTip tone="warn" label="这一页读不到的两样东西">
          <div className="space-y-3">
            <div>
              <p className="font-medium text-ink">四个声道的音量</p>
              <p className="mt-1">
                上面 ② 那几个字段是这次推演顺带给的设备快照。真正那套音量系统
                (通知 / 铃声 / 闹钟 / 媒体四个 0–1 的连续音量)整个
                <span className="font-mono"> phone/ </span>包<b>没有任何 HTTP 面</b>。
              </p>
              <p className="mt-1">
                缺的端点: <span className="font-mono">GET /api/companions/{'{id}'}/world/devices</span>。
                所以这一页不能画那四个滑杆 —— 画出来只能是一组假数字, 而假数字放在一个
                "她会不会被吵到"的页面上是最不该出现的东西。
              </p>
            </div>
            <div>
              <p className="font-medium text-ink">电量、应用列表、她的通讯录</p>
              <p className="mt-1">
                她在手机上装了哪些应用、每个应用能不能发通知, 以及她自己那份聊天软件里的会话 ——
                同样都没有读取面。
              </p>
              <p className="mt-1">
                关键的分界是: <b>数字设备世界</b>是她可以改的(装应用、调音量),
                <b>数字世界</b>不是。目前读到的两个世界混在一次返回里, 分不出哪个字段归哪一边。
              </p>
            </div>
          </div>
        </InfoTip>
        <p className="text-xs leading-relaxed text-ink-soft">
          这一页看到的是<b>设备快照</b>, 不是设备面板 —— 四个声道的音量、装了什么应用都还读不到。
        </p>
      </div>
    </div>
  )
}

/**
 * 一行"名字 · 值"。
 *
 * <h2>为什么显示中文名、却还留着 `k`</h2>
 *
 * `k` 是后端字段名 —— 直接摆在屏幕上, 一个不写代码的人看不出"notificationMode"是什么。
 * 所以给人看的是 `label`; `k` 只留在问号的读屏名里, 让排查的人仍然能把这一行和
 * 接口返回体对起来。说明也从原生 `title` 换成了问号: 原生 tooltip 在触屏上根本出不来。
 */
function Row({ k, label, v, hint }: {
  k: string
  label: string
  v?: string | number | null
  hint?: string
}) {
  return (
    <div className="flex items-baseline gap-2">
      <dt className="shrink-0 text-ink-faint">{label}</dt>
      <dd className="min-w-0 text-ink-soft">
        {v === undefined || v === null || v === '' ? '—' : v}
      </dd>
      {hint && (
        <InfoTip label={`${label}(字段 ${k})是什么意思`}>{hint}</InfoTip>
      )}
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
