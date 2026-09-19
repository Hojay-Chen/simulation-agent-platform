import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Copy, Plus, Trash2 } from 'lucide-react'
import {
  ApiError,
  createClient,
  listClients,
  revokeClient,
  type ClientCreated,
} from '@/api/client'
import { useAsync } from '@/lib/useAsync'
import { useSessionStore } from '@/stores/session'
import { Button, Empty, ErrorNote, Field, InfoTip, Notice, Panel, inputClass } from '@/components/ui'

/**
 * 客户端 —— 管理钥那一面(X-Admin-Key)。
 *
 * 「发钥匙 / 吊销钥匙」是本页的全部动作。明文钥匙只在创建响应里出现一次,
 * 所以创建成功后不关弹层、不自动刷新列表: 钥匙还在屏幕上, 用户得先抄走。
 */
export function Clients() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const setClientKey = useSessionStore((s) => s.setClientKey)
  const { data, loading, error, reload } = useAsync(() => listClients(), [])

  const [name, setName] = useState('')
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [issued, setIssued] = useState<ClientCreated | null>(null)
  const [nameError, setNameError] = useState<string | null>(null)
  const [revoking, setRevoking] = useState<string | null>(null)

  async function submit() {
    if (!name.trim()) {
      setNameError('给这个客户端起个名字 —— 它是将来你能认出的唯一线索')
      return
    }
    setNameError(null)
    setCreating(true)
    setCreateError(null)
    try {
      const created = await createClient(name.trim())
      setIssued(created)
      setName('')
      reload()
    } catch (e) {
      setCreateError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setCreating(false)
    }
  }

  async function revoke(clientId: string, clientName: string) {
    if (!confirm(`吊销「${clientName}」? 它的密钥立即失效, 名下数字人保留但不可再操作。`)) {
      return
    }
    setRevoking(clientId)
    try {
      await revokeClient(clientId)
      reload()
    } catch (e) {
      setCreateError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setRevoking(null)
    }
  }

  return (
    <div className="space-y-5">
      {!adminKey && (
        <Notice>
          还没填管理钥 —— 本页的请求都会失败。到<Link className="mx-1 underline" to="/access?tab=connect">「填入密钥」</Link>那一栏填入
          {/* `ml-1` 不是 `mx-1`: 右边紧跟着一个全角句号, 给它留外边距会让句号飘在
              半空("X-Admin-Key 。")。左边那个空格要留 —— 它隔开中文与拉丁字母。 */}
          <code className="ml-1">X-Admin-Key</code>。
        </Notice>
      )}

      {issued && <IssuedKey issued={issued} onUse={setClientKey} onDismiss={() => setIssued(null)} />}

      <div className="grid gap-5 lg:grid-cols-[1.6fr_1fr]">
        <Panel
          title={`客户端 (${data?.length ?? 0})`}
          action={<Button variant="ghost" onClick={reload}>刷新</Button>}
        >
          <ErrorNote error={error} />
          {loading && !data && <Empty>读取中…</Empty>}
          {data && data.length === 0 && <Empty>还没有客户端 —— 右侧发一个。</Empty>}
          {data && data.length > 0 && (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs tracking-wide text-ink-faint">
                  <th scope="col" className="pb-2 font-medium">名称</th>
                  <th scope="col" className="pb-2 font-medium">密钥前缀</th>
                  <th scope="col" className="pb-2 font-medium">创建时间</th>
                  <th scope="col" className="pb-2"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((c) => (
                  <tr key={c.clientId}>
                    <td className="py-2.5 text-ink">
                      {c.name}
                      <span className="ml-2 font-mono text-xs text-ink-faint">{c.clientId}</span>
                    </td>
                    <td className="py-2.5 font-mono text-xs text-ink-soft">{c.apiKeyPrefix}…</td>
                    <td className="py-2.5 text-xs text-ink-faint">{c.createdAt.slice(0, 10)}</td>
                    <td className="py-2.5 text-right">
                      <Button
                        variant="danger"
                        disabled={revoking === c.clientId}
                        onClick={() => revoke(c.clientId, c.name)}
                      >
                        <Trash2 size={13} />
                        吊销
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>

        <Panel
          title={
            <div className="flex min-w-0 items-center gap-1.5">
              <h2 className="text-sm font-medium text-ink">新建客户端 · 发一把新钥匙</h2>
              <InfoTip tone="warn" label="发放之前要知道什么">
                明文密钥<b className="font-medium text-ink">只在发放这一刻出现一次</b> ——
                服务端只存 sha256, 刷新页面就等于永久丢失。看到之后先抄走。
                <br />
                <br />
                名字是日后吊销时认出它的唯一线索, 用机构名或用途。
              </InfoTip>
            </div>
          }
        >
          <form
            className="space-y-4"
            onSubmit={(e) => { e.preventDefault(); void submit() }}
          >
            <Field label="客户端名称" error={nameError} hint="同一个名字只能有一个。">
              <input
                className={inputClass}
                value={name}
                onChange={(e) => { setName(e.target.value); setNameError(null) }}
                placeholder="例: acme-prod"
              />
            </Field>
            <ErrorNote error={createError} />
            <Button type="submit" disabled={creating}>
              <Plus size={14} />
              {creating ? '发放中…' : '发放密钥'}
            </Button>
          </form>
        </Panel>
      </div>
    </div>
  )
}

/**
 * 明文钥匙的一次性展示。
 *
 * 这是整个控制台唯一一次能看到钥匙原文的地方 —— 服务端只存 sha256, 刷新
 * 页面就等于永久丢失。所以: 不自动消失, 提供一键复制, 并顺手提供"直接用作
 * 客户端钥"的按钮(免去用户手工复制粘贴的第二遍出错机会)。
 */
function IssuedKey({ issued, onUse, onDismiss }: {
  issued: ClientCreated
  onUse: (key: string) => void
  onDismiss: () => void
}) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(issued.apiKey)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // 非 https / 无剪贴板权限 —— 用户仍可手动选中复制, 不该报错打断。
      setCopied(false)
    }
  }

  return (
    <Panel title={`「${issued.name}」的客户端钥`} className="border-ok/40">
      <div className="space-y-3">
        <Notice>{issued.notice}</Notice>
        <code className="block overflow-x-auto rounded-lg border border-line bg-raised px-3 py-2.5 font-mono text-sm text-ok">
          {issued.apiKey}
        </code>
        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={() => void copy()}>
            <Copy size={14} />
            {copied ? '已复制' : '复制'}
          </Button>
          <Button
            variant="ghost"
            onClick={() => { onUse(issued.apiKey); onDismiss() }}
            title="把它填进客户端钥, 立刻用它管理该客户端的数字人"
          >
            用作当前客户端钥
          </Button>
          <Button variant="ghost" onClick={onDismiss}>我已保存</Button>
        </div>
      </div>
    </Panel>
  )
}
