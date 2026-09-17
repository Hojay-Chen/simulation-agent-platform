import { useState, type ReactNode } from 'react'
import { LogIn, LogOut, ShieldCheck } from 'lucide-react'
import { ApiError, login } from '@/api/client'
import { useSessionStore } from '@/stores/session'
import { Button, Chip, ErrorNote, Field, Panel, inputClass } from './ui'

/**
 * Studio 的登录 / 登出 —— 控制台那张**用户 JWT** 从哪来。
 *
 * <h2>为什么这个登录框打的是聊天平台</h2>
 *
 * 因为本平台**没有账号**。§22 把 `users` 表划给了聊天平台, 本平台只读它。所以这里
 * 输入的邮箱与密码被送到 `chat.luxera.top/api/auth/login`, 换回一张用两仓共享密钥
 * 签发的 JWT; server:8091 的 `JwtAuthenticationFilter` 认这张票, 于是那一半内省
 * 接口(记忆/关系/生活/活动)才开得了门。
 *
 * 一句话: **这不是"登录本控制台", 是"用你在聊天平台的账号证明你是谁"。** 控制台
 * 自己没有第二套身份, 也不该有 —— 有了就意味着同一个人的两套密码。
 *
 * <h2>票只存 sessionStorage</h2>
 *
 * 理由写在 `stores/session.ts` 的文件头: 关掉标签页就没了, 而两把 API 钥匙本来就在
 * 同一个地方。真正的解法是 httpOnly cookie + 服务端会话, 那要求本平台拥有会话 ——
 * 又撞回 §22。
 */
export function StudioLogin() {
  const studioToken = useSessionStore((s) => s.studioToken)
  const studioUser = useSessionStore((s) => s.studioUser)
  const setStudioToken = useSessionStore((s) => s.setStudioToken)
  const signOut = useSessionStore((s) => s.signOut)

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (studioToken) {
    return (
      <div className="flex items-center gap-2">
        <Chip tone="ok">
          <ShieldCheck size={12} />
          <span className="ml-1">{studioUser || '已登录'}</span>
        </Chip>
        <Button variant="ghost" onClick={signOut} title="只清这张票, 不动两把 API 钥匙">
          <LogOut size={13} />
          退出
        </Button>
      </div>
    )
  }

  async function submit() {
    if (!username.trim() || !password) {
      setError('邮箱与密码都要填 —— 用的是聊天平台的账号。')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const r = await login(username.trim(), password)
      // 票落进 store 的同时也落进 sessionStorage(见 setStudioToken) —— 刷新后还在。
      setStudioToken(r.token, r.user?.nickname || r.user?.username || username.trim())
      setPassword('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Panel title="登录 Being Studio">
      <form
        className="space-y-4"
        onSubmit={(e) => { e.preventDefault(); void submit() }}
      >
        <p className="text-xs leading-relaxed text-ink-faint">
          用你在
          <span className="mx-1 font-mono text-ink-soft">chat.luxera.top</span>
          的账号登录。本平台没有账号系统 —— 身份由聊天平台签发, 这里只是认它。
        </p>
        <Field label="邮箱 / 用户名">
          <input
            className={inputClass}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
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
        <Button type="submit" disabled={busy}>
          <LogIn size={13} />
          {busy ? '登录中…' : '登录'}
        </Button>
      </form>
    </Panel>
  )
}

/**
 * 把一页圈在登录之后。
 *
 * **不用重定向**: 重定向会让"我没登录"和"这个页面没了"在屏幕上长得一样。这里直接
 * 把登录框放在原地, 并说清楚这一页为什么需要它 —— 用户看完就知道下一步做什么。
 */
export function RequireStudio({ children, why }: { children: ReactNode; why: string }) {
  const studioToken = useSessionStore((s) => s.studioToken)
  if (studioToken) return <>{children}</>

  return (
    <div className="mx-auto max-w-md space-y-4">
      <p className="text-sm leading-relaxed text-ink-soft">{why}</p>
      <StudioLogin />
    </div>
  )
}
