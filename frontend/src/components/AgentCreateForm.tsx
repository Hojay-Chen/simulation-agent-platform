import { useState } from 'react'
import { Sparkles } from 'lucide-react'
import { Button, ErrorNote, Field, inputClass } from './ui'

/**
 * 建 agent 的两种给法 —— 与 openapi 的 CreateAgentBody 一一对应:
 *   description 走平台编译链(自然语言 → 人格)
 *   persona     直传已编译人格
 * 服务端要求二者必给其一, 且 description 优先。前端把这个约束前移,
 * 让"两个都空"在本地就被挡下(而不是白跑一趟 400)。
 */
export interface AgentFormInput {
  description: string
  relationshipType: string
}

export const RELATIONSHIP_TYPES = [
  { value: 'friend', label: '朋友' },
  { value: 'partner', label: '伴侣' },
  { value: 'mentor', label: '导师' },
  { value: 'colleague', label: '同事' },
] as const

export interface AgentFormErrors {
  description?: string
  relationshipType?: string
}

/** 纯函数校验 —— 与渲染解耦, 因此可以被直接断言。 */
export function validateAgentForm(input: AgentFormInput): AgentFormErrors {
  const errors: AgentFormErrors = {}
  if (!input.description.trim()) {
    errors.description = '请用一段自然语言描述这个人 —— 平台会把它编译成人格'
  } else if (input.description.trim().length < 8) {
    // 太短的描述编译不出稳定人格, 服务端会成功但结果随机 —— 与其生成一个
    // 莫名其妙的 agent, 不如在本地要求写够一句话。
    errors.description = '描述太短, 至少写一句完整的话(8 字以上)'
  }
  if (!input.relationshipType) {
    errors.relationshipType = '请选择与使用者的关系'
  }
  return errors
}

export function AgentCreateForm({ onSubmit, submitting, error }: {
  onSubmit: (input: AgentFormInput) => void
  submitting?: boolean
  error?: string | null
}) {
  const [description, setDescription] = useState('')
  const [relationshipType, setRelationshipType] = useState<string>('friend')
  const [errors, setErrors] = useState<AgentFormErrors>({})
  const [touched, setTouched] = useState(false)

  function submit() {
    setTouched(true)
    const input = { description, relationshipType }
    const found = validateAgentForm(input)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    onSubmit(input)
  }

  // 校验发生在提交时, 但一旦提交过就随输入实时更新 —— 让用户看到错误在消失,
  // 而不是改完了还红着。
  const live = touched ? validateAgentForm({ description, relationshipType }) : {}
  const shown: AgentFormErrors = touched ? { ...live, ...pickStillInvalid(errors, live) } : {}

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <Field
        label="人格描述"
        error={shown.description}
        // 例子不该暗示一种默认。原文是「一位在旧书店工作的女孩, 说话慢, 喜欢在
        // 雨天聊诗, 偶尔健忘」—— 那个"女孩"是伴侣时代的默认(agent 恒为女性),
        // 而 agent 现在是按用户需求生成的: 它可以是任何性别、任何年纪。
        // 换成同样有质感、但没有性别标记的一个, 顺手把年龄也拉开一点 ——
        // 例子是用户唯一的参照物, 它长什么样, 用户就照着写什么样。
        hint="例: 一位退休的地理老师, 说话慢, 喜欢在雨天聊诗, 偶尔健忘。"
      >
        <textarea
          className={`${inputClass} min-h-[104px] resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="用一段自然语言描述这个 agent 是谁…"
        />
      </Field>

      <Field label="与使用者的关系" error={shown.relationshipType}>
        <select
          className={inputClass}
          value={relationshipType}
          onChange={(e) => setRelationshipType(e.target.value)}
        >
          {RELATIONSHIP_TYPES.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
      </Field>

      <ErrorNote error={error ?? null} />

      <Button type="submit" disabled={submitting}>
        <Sparkles size={14} />
        {submitting ? '编译人格中…' : '创建 agent'}
      </Button>
    </form>
  )
}

/** 已展示过的错误, 只要仍然不合法就继续展示(避免"改一个字错误就闪没")。 */
function pickStillInvalid(shown: AgentFormErrors, live: AgentFormErrors): AgentFormErrors {
  const kept: AgentFormErrors = {}
  if (shown.description && live.description) kept.description = live.description
  if (shown.relationshipType && live.relationshipType) kept.relationshipType = live.relationshipType
  return kept
}
