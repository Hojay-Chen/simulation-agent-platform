import { useState } from 'react'
import { Link } from 'react-router-dom'
import { KeyRound, ShieldCheck } from 'lucide-react'
import { useSessionStore } from '@/stores/session'
import { Button, Field, InfoTip, Panel, inputClass } from '@/components/ui'

/**
 * 「填入密钥」—— 两把钥匙的入口。
 *
 * 这不是"登录": 控制台本身没有会话, 它只是替你把两个凭据带进请求头。
 * 所以这里既不校验也不跳转, 存进 sessionStorage 即生效; 对不对由下一个
 * 真实请求回答(401 会带着服务端文案回到对应页面)。
 *
 * <h2>这一页的说明去哪了</h2>
 *
 * 原来面板顶上有一条常驻提示条讲 OpenApiAuthFilter、底下一段讲为什么存
 * sessionStorage 而不存 localStorage。两段都是"解释它是什么、为什么这样设计",
 * 现在分别挂在面板标题和两个输入框的问号上 —— 填表的人面前只剩两个框和一个按钮。
 *
 * 「钥匙从哪来」那块**留在正文里**没有搬走: 它是一串要照着做的步骤, 里面还有一个
 * 跳去发钥匙的链接 —— 而 InfoTip 的气泡是 `pointer-events-none`, 搬进去的链接
 * 会变成一个点不动的死链。
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
      <Panel
        title={
          <div className="flex min-w-0 items-center gap-1.5">
            <h2 className="text-sm font-medium text-ink">填入密钥</h2>
            <InfoTip label="这两栏是干什么的">
              两把钥匙对应两个互不相通的面, 分别由服务端
              <span className="font-mono"> OpenApiAuthFilter </span>
              校验。这里只把它们放进请求头 —— 校验与否由服务端说了算, 填错不会立刻报错,
              下一个真实请求才会回 401。
              <br />
              <br />
              服务端没配 <span className="font-mono">OPENAPI_ADMIN_KEY</span> 时, 管理钥
              那一面整体回 <span className="font-mono">503</span>(不是 401)—— 那说明平台
              没发这把钥匙, 而不是你填错了。
              <br />
              <br />
              存在 <span className="font-mono">sessionStorage</span>(不在 localStorage):
              管理钥能换出任意客户端钥, 不该在关掉标签页后继续留在这台机器上。
              <br />
              谁要看数字人的实时状态, 用哪把钥匙就填哪把 —— 缺的那把只会让对应的页面报错,
              不影响另一面(哪一栏读哪把, 见「系统设置」页)。
            </InfoTip>
          </div>
        }
      >
        <div className="space-y-5">
          <Field
            label="管理钥 · X-Admin-Key"
            hint="平台管理员持有, 用来发放 / 吊销客户端钥。"
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
            label="客户端钥 · Bearer sap_…"
            hint="某个客户端的身份。控制台用它查看 / 创建该客户端名下的数字人。"
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

          <div className="flex flex-wrap items-center gap-2">
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
        </div>
      </Panel>

      <Panel title="钥匙从哪来">
        <ol className="list-decimal space-y-2 pl-5 text-sm text-ink-soft">
          <li>
            服务端启动时注入 <code className="text-accent">OPENAPI_ADMIN_KEY</code> ——
            这就是管理钥, 由运维持有。
          </li>
          <li>
            在<Link className="mx-1 text-accent hover:underline" to="/access?tab=clients">「客户端」那一栏</Link>用它发一个客户端 ——
            明文 <code>sap_…</code> 只在创建响应里出现一次。
          </li>
          <li>
            把刚拿到的 <code>sap_…</code> 填进上面的客户端钥 —— 之后就能建 / 列 / 改数字人了。
          </li>
        </ol>
        <p className="mt-3 flex items-start gap-2 text-xs leading-relaxed text-ink-faint">
          <KeyRound size={13} className="mt-0.5 shrink-0" />
          <span className="text-pretty">
            丢失的钥匙没有任何途径取回 —— 库里只有 sha256。吊销那个客户端重建一个即可
            (它名下的数字人保留)。
          </span>
        </p>
      </Panel>
    </div>
  )
}
