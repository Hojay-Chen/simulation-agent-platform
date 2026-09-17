import { useState } from 'react'
import { Link } from 'react-router-dom'
import { KeyRound, ShieldCheck } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { Button, Field, Notice, Panel, inputClass } from '@/components/ui'

/**
 * 接入页 —— 两把钥匙的入口。
 *
 * 这不是"登录": 控制台本身没有会话, 它只是替你把两个凭据带进请求头。
 * 所以这里既不校验也不跳转, 存进 sessionStorage 即生效; 对不对由下一个
 * 真实请求回答(401 会带着服务端文案回到对应页面)。
 */
export function Connect() {
  const adminKey = useSessionStore((s) => s.adminKey)
  const clientKey = useSessionStore((s) => s.clientKey)
  const setAdminKey = useSessionStore((s) => s.setAdminKey)
  const setClientKey = useSessionStore((s) => s.setClientKey)
  const clear = useSessionStore((s) => s.clear)

  const [admin, setAdmin] = useState(adminKey)
  const [client, setClient] = useState(clientKey)
  const [saved, setSaved] = useState(false)

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <Panel title="接入凭据">
        <div className="space-y-5">
          <Notice>
            两把钥匙对应两个互不相通的面, 分别由服务端 <code>OpenApiAuthFilter</code> 校验。
            这里只把它们放进请求头 —— 校验与否由服务端说了算。
          </Notice>

          <Field
            label="管理密钥 · X-Admin-Key"
            hint="平台管理员持有。用于发放/吊销 API 客户端(即 API 钥匙)。服务端未配置时该面回 503。"
          >
            <input
              className={inputClass}
              type="password"
              autoComplete="off"
              value={admin}
              onChange={(e) => { setAdmin(e.target.value); setSaved(false) }}
              placeholder="OPENAPI_ADMIN_KEY 的值"
            />
          </Field>

          <Field
            label="客户端 API Key · Bearer sap_…"
            hint="某个 API 客户端的身份。控制台用它查看/创建该客户端名下的 agent。"
          >
            <input
              className={`${inputClass} font-mono`}
              type="password"
              autoComplete="off"
              value={client}
              onChange={(e) => { setClient(e.target.value); setSaved(false) }}
              placeholder="sap_…"
            />
          </Field>

          <div className="flex items-center gap-2">
            <Button
              onClick={() => {
                setAdminKey(admin.trim())
                setClientKey(client.trim())
                setSaved(true)
              }}
            >
              <ShieldCheck size={14} />
              保存到本次会话
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                clear()
                setAdmin('')
                setClient('')
                setSaved(false)
              }}
            >
              清除
            </Button>
            {saved && <span className="text-xs text-ok">已保存</span>}
          </div>

          <p className="text-xs leading-relaxed text-ink-faint">
            存在 <code>sessionStorage</code>(不在 localStorage): 管理钥能换出任意客户端钥,
            不该在关掉标签页后继续留在这台机器上。谁要看 agent 实时状态, 用哪把钥匙就填哪把 ——
            缺的那把只会让对应的页面报错, 不影响另一面。
          </p>
        </div>
      </Panel>

      <Panel title="钥匙从哪来">
        <ol className="list-decimal space-y-2 pl-5 text-sm text-ink-soft">
          <li>
            服务端启动时注入 <code className="text-accent">OPENAPI_ADMIN_KEY</code> ——
            这就是管理密钥, 由运维持有。
          </li>
          <li>
            在「API 客户端」页用它<Link className="mx-1 text-accent" to="/access?tab=clients">创建一个客户端</Link> ——
            明文 <code>sap_…</code> 只在创建响应里出现一次。
          </li>
          <li>
            把刚拿到的 <code>sap_…</code> 填进上面的客户端钥 —— 之后就能建/列/改 agent 了。
          </li>
        </ol>
        <p className="mt-3 flex items-start gap-2 text-xs text-ink-faint">
          <KeyRound size={13} className="mt-0.5 shrink-0" />
          丢失的钥匙没有任何途径取回 —— 库里只有 sha256。吊销客户端重建即可(其名下 agent 保留)。
        </p>
      </Panel>
    </div>
  )
}
