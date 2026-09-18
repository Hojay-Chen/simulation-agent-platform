/**
 * 身体: 把 `/state` 里那 19 个 0–1 的数字变成一页能看懂的东西。
 *
 * <h2>为什么不能直接把 field: value 列出来</h2>
 *
 * 十九行 `energy: 0.62` 是**数据库的样子**, 不是她现在的样子。这十九个量分成三组,
 * 三组回答三个完全不同的问题:
 *
 * - **身体稳态**(energy / warmth / sleepiness / hunger / physicalDiscomfort / stress)
 *   —— "她还能撑多久"。这一组决定她能不能被打断: 累到 0.9 的人对同一句话的反应是
 *   不一样的, 这不是情绪, 是物理。
 * - **情绪**(joy / sadness / anger / anxiety / hurt / loneliness / affection /
 *   emotionalCloseness) —— "她此刻怎么想这件事"。
 * - **心智余量**(focus / curiosity / socialEnergy) —— "她现在接得住多少新东西"。
 *   这三个是注意力(§3.4.4)和决策的实际输入: 专注 0.9 的时候一条普通消息进不去。
 *
 * <h2>为什么每个量要标"哪边是好"</h2>
 *
 * 因为 `hunger: 0.8` 和 `energy: 0.8` 在屏幕上是同一个数字, 含义正好相反。不标方向
 * 的仪表盘会让运维把"她很饿"读成"她很精神" —— 这类错误没有任何报错, 只会让人对
 * 界面失去信任, 然后不再看第二眼。方向是这一层最要紧的信息, 所以它和数据放在一起。
 *
 * <h2>为什么没有"综合评分"</h2>
 *
 * 因为我们不知道权重, 而 `0.4×energy + 0.3×mood - …` 这种公式在文档里没有出处。
 * 编一个出来, 屏幕上就会出现一个看起来精确、实际上没人能解释的数。宁可给十九个
 * 分开的量, 也不给一个假的。
 */

/** 三组。顺序就是页面上的顺序 —— 物理在先, 心智在后。 */
export type VitalGroup = 'body' | 'emotion' | 'mind'

export const GROUP_META: Record<VitalGroup, { label: string; hint: string }> = {
  body: {
    label: '身体稳态',
    hint: '这一组决定她"现在能不能被打断"。累了的人对同一句话的反应不一样。',
  },
  emotion: {
    label: '情绪',
    hint: '这一组回答"她此刻怎么想这件事", 是上一句消息留下的痕迹。',
  },
  mind: {
    label: '心智余量',
    hint: '专注、好奇、社交电量 —— 注意力和决策的直接输入。专注高的时候一条普通消息进不去。',
  },
}

export interface Vital {
  /** 与 `/state` 返回体里的字段名**逐字相同**, 不做驼峰转换。 */
  key: string
  label: string
  group: VitalGroup
  /** 高是好还是坏。界面靠它决定"要不要标红"。 */
  goodHigh: boolean
  /** 这个量从哪来、影响什么 —— 一句话。 */
  hint: string
}

/**
 * 这 19 项就是 `/api/companions/{id}/state` 当前返回的全部字段。
 *
 * 表里没有而返回体里有的字段, 会被归到 `mind` 并标成"未登记"—— 见 `summarizeVitals`。
 * 这是刻意的: 后端加一个新量的时候不该静默地从页面上消失, 也不该让页面报错。
 */
export const VITALS: readonly Vital[] = [
  // ── 身体稳态 ──
  { key: 'energy', label: '精力', group: 'body', goodHigh: true, hint: '低于 0.3 时她会开始想休息, 也会更容易把事情推迟。' },
  { key: 'warmth', label: '温暖', group: 'body', goodHigh: true, hint: '环境降温会持续拉低它 —— 这是 A 类事件最典型的受害者。' },
  { key: 'sleepiness', label: '困倦', group: 'body', goodHigh: false, hint: '越高越容易把消息推到"明天再说"。' },
  { key: 'hunger', label: '饥饿', group: 'body', goodHigh: false, hint: '饿了会打断她手上的事去吃 —— 这是她自己的重规划, 不是外部插入。' },
  { key: 'physicalDiscomfort', label: '身体不适', group: 'body', goodHigh: false, hint: '疼、冷、坐太久。会直接抬升打断的成功率。' },
  { key: 'stress', label: '压力', group: 'body', goodHigh: false, hint: '压力高的时段, 她的决定更容易偏向回避。' },

  // ── 情绪 ──
  { key: 'joy', label: '愉快', group: 'emotion', goodHigh: true, hint: '' },
  { key: 'sadness', label: '难过', group: 'emotion', goodHigh: false, hint: '' },
  { key: 'anger', label: '生气', group: 'emotion', goodHigh: false, hint: '' },
  { key: 'anxiety', label: '焦虑', group: 'emotion', goodHigh: false, hint: '' },
  { key: 'hurt', label: '受伤', group: 'emotion', goodHigh: false, hint: '被冒犯之后留下的那一块。它比 anger 退得慢得多。' },
  { key: 'loneliness', label: '孤独', group: 'emotion', goodHigh: false, hint: '' },
  { key: 'affection', label: '亲近感', group: 'emotion', goodHigh: true, hint: '' },
  { key: 'emotionalCloseness', label: '情感距离', group: 'emotion', goodHigh: true, hint: '与关系网里的亲密维度是两份数, 一个是此刻, 一个是长期。' },
  { key: 'mood', label: '整体心情', group: 'emotion', goodHigh: true, hint: '上面那些情绪的合成底色。' },

  // ── 心智余量 ──
  { key: 'focus', label: '专注', group: 'mind', goodHigh: true, hint: '**注意力的实际输入**。它高的时候, 一条普通消息连"隐约感到"都到不了。' },
  { key: 'curiosity', label: '好奇', group: 'mind', goodHigh: true, hint: '影响她会不会主动发起一件事(而不是只被动回应)。' },
  { key: 'socialEnergy', label: '社交电量', group: 'mind', goodHigh: true, hint: '低的时候她会选独处 —— 表现为消息被推迟。' },
]

const BY_KEY = new Map(VITALS.map((v) => [v.key, v]))

/** 一条已经准备好画的量。 */
export interface VitalReading {
  vital: Vital
  /** 0–1 之间, 已夹紧。后端偶尔会给出界值(>1 或 <0), 夹紧比画到框外好。 */
  value: number
  /** 不认识这个量 —— 界面上仍然显示, 但明确标注。 */
  unregistered: boolean
}

/**
 * 只挑出**数值**字段。
 *
 * `updatedAt` 不在表里, 也不会进来 —— 它是个字符串, 被 `Number.isFinite` 挡掉了。
 * 用类型判断而不是靠"记得排除 updatedAt": 后端将来加别的时间戳字段时, 这里不用改。
 */
export function summarizeVitals(state: Record<string, unknown> | null | undefined): VitalReading[] {
  if (!state) return []
  const out: VitalReading[] = []
  for (const [key, raw] of Object.entries(state)) {
    const v = typeof raw === 'number' ? raw : Number.NaN
    if (!Number.isFinite(v)) continue
    const known = BY_KEY.get(key)
    out.push({
      vital: known ?? {
        key,
        label: key,
        group: 'mind',
        goodHigh: true,
        hint: '这个量在前端的表里没有登记 —— 后端加了它但界面还不知道它是什么。',
      },
      value: Math.min(1, Math.max(0, v)),
      unregistered: !known,
    })
  }
  // 组间按 GROUP_META 的顺序, 组内按 VITALS 的顺序 —— 每次都一样, 不会因为
  // Object.keys 的顺序换一次而后端觉得"页面怎么在抖"。
  const groupRank: Record<VitalGroup, number> = { body: 0, emotion: 1, mind: 2 }
  const keyRank = new Map(VITALS.map((v, i) => [v.key, i]))
  return out.sort((a, b) =>
    groupRank[a.vital.group] - groupRank[b.vital.group] ||
    (keyRank.get(a.vital.key) ?? 999) - (keyRank.get(b.vital.key) ?? 999) ||
    a.vital.key.localeCompare(b.vital.key))
}

export function groupOf(readings: readonly VitalReading[]): { group: VitalGroup; items: VitalReading[] }[] {
  const out: { group: VitalGroup; items: VitalReading[] }[] = []
  for (const r of readings) {
    const last = out[out.length - 1]
    if (last && last.group === r.vital.group) last.items.push(r)
    else out.push({ group: r.vital.group, items: [r] })
  }
  return out
}

/**
 * 她此刻最突出的那一个量 —— 首页那一句"她现在怎么样"。
 *
 * 判据是**偏离中位数的程度**, 而不是绝对值: 精力 0.9 和饥饿 0.9 都是"突出"的,
 * 而 mood 0.5 不突出。取偏离最大的那个, 并把方向说清楚。
 *
 * 全都贴着 0.5 时返回 null —— 那种情况下**没有**一句话可说, 编一句出来
 * ("她状态平稳")是在给一个不存在的观察加上权威。
 */
export function standout(readings: readonly VitalReading[]): VitalReading | null {
  let best: VitalReading | null = null
  let bestScore = 0
  for (const r of readings) {
    if (r.unregistered) continue
    // 0.5 是"没什么可说"的位置。离它越远越值得说。
    const score = Math.abs(r.value - 0.5)
    if (score > bestScore) {
      bestScore = score
      best = r
    }
  }
  // 偏离不到 0.12 就不值得单说 —— 那是噪声。
  return bestScore >= 0.12 ? best : null
}

/** 把它说成人话。`goodHigh` 在这里才真正用上。 */
export function describeReading(r: VitalReading): string {
  const level = r.value >= 0.75 ? '很高' : r.value >= 0.55 ? '偏高' : r.value > 0.45 ? '中等' : r.value > 0.25 ? '偏低' : '很低'
  const bad = r.value >= 0.55 ? !r.vital.goodHigh : r.vital.goodHigh && r.value <= 0.25
  // 只有"偏离到需要在意"的时候才带那句判断。数值在中间的时候只报数,
  // 不评价 —— 一个永远在评价的界面等于没有评价。
  const verdict = r.value >= 0.75 || r.value <= 0.25 ? (bad ? ' —— 这一项现在不利于她' : ' —— 这一项现在对她有利') : ''
  return `${r.vital.label}${level}${verdict}`
}

/**
 * 五感通道(V2.2 §3.1 Body)。
 *
 * **这一组目前没有后端读取面** —— `World` 的感官通道是内建的, 但没有一个端点
 * 告诉你"她的听觉现在多灵敏"。所以这里只作为**说明**呈现: 让用户知道她的感知
 * 是从哪五个通道进来的, 以及每条通道的门限里有一个 `salience` 折扣。
 *
 * 一旦 `GET /api/companions/{id}/body/senses` 落地, 这张表就该换成读回来的数。
 */
export const SENSE_CHANNELS = [
  { key: 'hearing', label: '听觉', via: '手机通知音量 / 铃声 / 闹钟', hint: 'B 类事件最主要的入口。' },
  { key: 'vision', label: '视觉', via: '她看向屏幕或窗外', hint: '只有她**主动去看**的时候才有输入 —— 这正是消息正文进入她的唯一路径。' },
  { key: 'smell', label: '嗅觉', via: '环境事件', hint: '坏气味是 B 类: 需要立刻反应, 且没法"待会儿再闻"。' },
  { key: 'taste', label: '味觉', via: '进食', hint: '与饥饿、进食活动耦合。' },
  { key: 'touch', label: '触觉', via: '温度 / 衣物 / 身体不适', hint: 'A 类持续影响的主要落点 —— 冷会一直拉着 warmth 往下走。' },
] as const
