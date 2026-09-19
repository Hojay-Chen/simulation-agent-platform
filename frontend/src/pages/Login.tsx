import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Bot, LogIn } from 'lucide-react'
import { ApiError, login } from '@/api/client'
import { useSessionStore } from '@/stores/session'
import { Button, ErrorNote, Field, InfoTip, inputClass } from '@/components/ui'
import { CHAT_PLATFORM_URL, SPA_PATHS } from '@/lib/routes'

/**
 * 登录页 —— **独立的一页**, 不在控制台外壳里。
 *
 * <h2>为什么它必须在外壳之外</h2>
 *
 * 原来登录框是**嵌在页面里**的: 没登录时, 首页/详情页/事件流各自在正文中央渲染一个
 * 登录表单, 而它周围仍然包着页头、侧栏、三盏凭据灯。那套外壳的每一件东西都在说
 * "你已经进来了" —— 侧栏可点、导航可跳、页头挂着"已登录"以外的状态灯。于是用户看到的
 * 是一个**自相矛盾的界面**: 一个说"请登录", 另一个说"你已经在里面了"。
 *
 * 更实际的问题: 外壳里的登录框没有"这一页是入口"的意味, 看起来像是某个页面加载失败
 * 之后剩下的半截。而登录本来就是这个站点唯一一扇门, 它应该在门外。
 *
 * <h2>为什么是重定向, 而不是继续在原地渲染</h2>
 *
 * 原来那段注释给的理由是对的: **重定向会让"我没登录"和"这个页面没了"长得一样** ——
 * 用户分不清自己是该去登录, 还是这个地址本来就无效了。
 *
 * 所以这里没有丢掉那个理由, 而是把它接住了: `RequireStudio` 跳过来时会把**为什么**
 * (`why`) 和**原本想去哪** (`next`) 一起带上, 这一页负责把这两件事原样说给用户听。
 * 于是用户看到的仍然是一句"你需要登录才能看这一页: <原因>", 只是这句话现在出现在
 * 一扇真正的门后面, 而不是一页废墟中央。
 */
export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const studioToken = useSessionStore((s) => s.studioToken)
  const setStudioToken = useSessionStore((s) => s.setStudioToken)

  // `why` 是"这一页为什么要登录", `next` 是"登录完回哪去"。两者都由 RequireStudio 写入。
  // 直接敲 /login 进来时两个都没有 —— 那就是一次普通登录, 不该显示任何解释。
  const why = params.get('why')
  const next = params.get('next') || SPA_PATHS.dashboard

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 已经登录的人不该停在登录页 —— 刷新、回退、或者直接敲 /login 都会到这里。
  useEffect(() => {
    if (studioToken) navigate(next, { replace: true })
  }, [studioToken, next, navigate])

  async function submit() {
    if (!username.trim() || !password) {
      setError('邮箱与密码都要填。')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const r = await login(username.trim(), password)
      setPassword('')
      // 票落进 store 的同时也落进 sessionStorage(见 setStudioToken) —— 刷新后还在。
      // 跳转由上面那个 effect 做: 写 store 与读 store 是两件事, 只在一处处理更不容易漏。
      setStudioToken(r.token, r.user?.nickname || r.user?.username || username.trim())
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-dvh place-items-center bg-surface px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent text-accent-ink">
            <Bot size={20} />
          </span>
          <h1 className="text-lg font-medium tracking-tight text-ink">Being Studio</h1>
          <p className="text-xs text-ink-faint">仿真 Agent 控制台</p>
        </div>

        <div className="rounded-xl border border-line bg-raised p-5">
          {/* 从 RequireStudio 跳过来时才有 why —— 它替用户回答了"我为什么会在这儿" */}
          {why && (
            <p className="mb-4 rounded-lg border border-line bg-sunken px-3 py-2 text-xs leading-relaxed text-ink-soft">
              {why}
            </p>
          )}

          <form
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault()
              void submit()
            }}
          >
            <Field label="邮箱 / 用户名">
              <input
                className={inputClass}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                autoFocus
                placeholder="you@example.com"
              />
            </Field>
            <Field label="密码">
              <input
                className={inputClass}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </Field>
            <ErrorNote error={error} />
            <Button type="submit" disabled={busy} className="w-full justify-center">
              <LogIn size={13} />
              {busy ? '登录中…' : '登录'}
            </Button>
          </form>
        </div>

        {/*
          这一段原来印在表单上方, 占三行。它解释的是**这个平台为什么没有自己的账号** ——
          第一次来的人需要知道(否则会去找"注册"), 来过的人不需要。所以它挂在问号上。
        */}
        <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-ink-faint">
          账号来自聊天平台
          <InfoTip label="为什么用聊天平台的账号" align="center">
            本平台没有账号系统。这里输入的邮箱与密码会送到聊天平台换取一张身份票,
            控制台只是认这张票 —— 所以你在两个站点用的是同一个账号, 而不是两套密码。
          </InfoTip>
        </p>

        {/*
          这里原来是一条「先不登录, 回总览」, 只在被深层页面弹过来时出现。它是个**环**:
          总览自己就要登录, 点过去被 `RequireStudio` 原样弹回登录页, 只在 URL 上多留一句
          why —— 用户"去"了一趟, 回到原地, 还多挨一句像责备的话(已实测)。
          这一页真正缺的出口不是"回总览", 而是"我没有账号, 去哪儿弄一个": 上一行刚说了
          账号来自聊天平台, 那就把路指到底。**外链所以用 `<a>` 而不是 `<Link>`** —— 那是
          另一个站点, 不该走本 SPA 的路由。
        */}
        <p className="mt-3 text-center text-xs text-ink-soft">
          还没有账号?
          <a
            href={CHAT_PLATFORM_URL}
            target="_blank"
            rel="noreferrer"
            className="ml-1 text-accent underline-offset-2 hover:underline"
          >
            去聊天平台
          </a>
        </p>
      </div>
    </div>
  )
}
