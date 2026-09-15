import { create } from 'zustand'
import { initCredentials, setCredentials, type Credentials } from '@/api/client'

/**
 * 两把钥匙的会话状态。
 *
 * 为什么不合并成"一个 token": 它们属于**两个不同的委托方** —— 管理钥是平台
 * 管理员发给自己的(能发任意客户端钥), 客户端钥是某个第三方客户端的身份。
 * 控制台里两者同时在手, 切换查看"A 客户端的 agent"和"B 客户端的 agent"只需
 * 换 clientKey, 管理面不受影响。
 */
interface SessionState extends Credentials {
  /** 是否已从 sessionStorage 恢复过 —— 避免首屏把"恢复了空值"误判成"用户没填"。 */
  hydrated: boolean
  hydrate: () => void
  setAdminKey: (key: string) => void
  setClientKey: (key: string) => void
  clear: () => void
}

export const useSessionStore = create<SessionState>((set, get) => ({
  adminKey: '',
  clientKey: '',
  hydrated: false,

  hydrate: () => {
    const creds = initCredentials()
    set({ ...creds, hydrated: true })
  },

  setAdminKey: (key) => {
    const next = { adminKey: key, clientKey: get().clientKey }
    setCredentials(next)
    set({ adminKey: key })
  },

  setClientKey: (key) => {
    const next = { adminKey: get().adminKey, clientKey: key }
    setCredentials(next)
    set({ clientKey: key })
  },

  clear: () => {
    setCredentials({ adminKey: '', clientKey: '' })
    set({ adminKey: '', clientKey: '' })
  },
}))
