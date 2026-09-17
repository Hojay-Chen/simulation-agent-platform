import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { useSessionStore } from '@/stores/session'
import './index.css'

/*
 * 凭据必须在**第一次渲染之前**装好, 这是一条顺序约束, 不是风格问题。
 *
 * 曾经它在 App 的 useEffect 里(那是"组件挂载后")。React 的 effect 是**自下而上**跑的:
 * 子组件的 effect 先于父组件。于是任何"挂载时就发请求"的页面(API 页那一栏最典型)
 * 会在 hydrate 之前就发出去, 而请求层给未配置的钥匙的答复是一句
 * "未配置管理密钥 —— 先在「接入」页填入 X-Admin-Key" —— **明明已经配好了**。
 *
 * 症状是"刷新一下页面, 钥匙就丢了; 点一下刷新按钮, 它又回来了", 而人很难从这个现象
 * 反推到 effect 顺序。装在 render 之前, 这个竞态就不存在了。
 */
useSessionStore.getState().hydrate()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
