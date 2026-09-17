import { create } from 'zustand'
import { initCredentials, setCredentials, whoami, type Credentials } from '@/api/client'

/**
 * 三份凭据的会话状态。
 *
 * <h2>为什么不合并成"一个 token"</h2>
 *
 * 因为它们属于**三个不同的委托方**:
 *
 *   - 管理钥是平台管理员发给自己的(能发任意客户端钥);
 *   - 客户端钥是某个第三方客户端的身份;
 *   - Studio 的票是**某个真人的**身份, 而且是**聊天平台**发的 —— 本平台只是认它
 *     (§22: `users` 由 Chat 写, Agent 只读; 所以这里不复制用户表, 也不自己发明登录)。
 *
 * 三者同时在手是常态而非例外: 运维一边给自己发钥匙, 一边看某个 agent 的记忆。
 * 合成一个字段会强迫它们在"同一个身份"上, 而那正是 §22 禁止的事。
 *
 * <h2>票存在 sessionStorage 里, 这是一个被权衡过的选择</h2>
 *
 * sessionStorage 不是 XSS 安全的地方(脚本能读到它), 而 localStorage 更糟 ——
 * 关掉标签页之后它还在。这里的判断是: 控制台已经要在同一处放两把**权限更高的**
 * 钥匙(管理钥能凭空发客户端钥), 而票至少会随标签页消失。真正的解法是 httpOnly
 * cookie + 服务端会话, 那需要本平台拥有会话 —— 又撞回 §22。
 */
interface SessionState extends Credentials {
  /** 是否已从 sessionStorage 恢复过 —— 避免首屏把"恢复了空值"误判成"用户没填"。 */
  hydrated: boolean
  /** 票是谁的 —— 登录后填上, 用于页头打招呼与"票还在不在"的确认。 */
  studioUser: string
  hydrate: () => void
  setAdminKey: (key: string) => void
  setClientKey: (key: string) => void
  /** 登录成功后写入票。`who` 是可选的展示名。 */
  setStudioToken: (token: string, who?: string) => void
  /** 登出: 只清票, **不动**两把钥匙 —— 它们是另一个委托方的东西。 */
  signOut: () => void
  clear: () => void
}

export const useSessionStore = create<SessionState>((set, get) => ({
  adminKey: '',
  clientKey: '',
  studioToken: '',
  studioUser: '',
  hydrated: false,

  hydrate: () => {
    const creds = initCredentials()
    set({ ...creds, hydrated: true })
    // 票是从 sessionStorage 恢复的, 可能早就过期了。这里**不**阻塞首屏去验它 ——
    // 各页自己发请求时自然会撞上 403, 而那时给出的文案是"登录已失效", 比一个
    // 卡在半路的启动流程清楚。只有拿到了才顺手问一句"你是谁", 用来显示在页头。
    if (creds.studioToken) {
      whoami()
        .then((me) => set({ studioUser: me.nickname || me.username || '' }))
        // 票真的失效了才走到这里。必须用 signOut() 而不是手写 set():
        // 后者只清 store, **不清 client 里那份模块级的票** —— 于是页面显示"未登录",
        // 而请求仍然带着那张废票出去, 下一个 403 会出现在一个看起来毫无关系的地方。
        .catch(() => get().signOut())
    }
  },

  setAdminKey: (key) => {
    // 用**当前完整凭据**做底再改一个字段。手写字段列表的写法(adminKey, clientKey)
    // 会在加第三个凭据时静默丢掉它 —— 症状是"填完管理钥, 登录状态没了"。
    const next = { ...get(), adminKey: key }
    setCredentials(credsOf(next))
    set({ adminKey: key })
  },

  setClientKey: (key) => {
    const next = { ...get(), clientKey: key }
    setCredentials(credsOf(next))
    set({ clientKey: key })
  },

  setStudioToken: (token, who = '') => {
    const next = { ...get(), studioToken: token }
    setCredentials(credsOf(next))
    set({ studioToken: token, studioUser: who })
  },

  signOut: () => {
    const next = { ...get(), studioToken: '', studioUser: '' }
    setCredentials(credsOf(next))
    set({ studioToken: '', studioUser: '' })
  },

  clear: () => {
    setCredentials({ adminKey: '', clientKey: '', studioToken: '' })
    set({ adminKey: '', clientKey: '', studioToken: '', studioUser: '' })
  },
}))

/** 从 store 状态里摘出凭据那三个字段 —— `setCredentials` 不该看见 studioUser/hydrated。 */
function credsOf(s: Credentials): Credentials {
  return { adminKey: s.adminKey, clientKey: s.clientKey, studioToken: s.studioToken }
}
