import { useEffect } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { Agents } from '@/pages/Agents'
import { AgentState } from '@/pages/AgentState'
import { Clients } from '@/pages/Clients'
import { Connect } from '@/pages/Connect'
import { useSessionStore } from '@/stores/session'

export default function App() {
  const hydrate = useSessionStore((s) => s.hydrate)

  // 启动时把 sessionStorage 里的钥匙装回 api client。放在 App 而不是模块顶层:
  // 单测导入 client.ts 时不该碰 sessionStorage。
  useEffect(() => {
    hydrate()
  }, [hydrate])

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Agents />} />
        <Route path="/agents/:agentId/state" element={<AgentState />} />
        <Route path="/clients" element={<Clients />} />
        <Route path="/connect" element={<Connect />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
