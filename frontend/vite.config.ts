/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    // import.meta.dirname 而非 __dirname —— vite 8 的 configLoader 正转向原生
    // ESM 加载, __dirname 会被警告(仓 1 那份配置还带着这条警告)。
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
  server: {
    host: '0.0.0.0',
    port: 5174,
    proxy: {
      // G6 —— 控制台只打一个后端: openapi:8092。
      //
      // 曾计划按 G5 那样双目标分流(state 走 8091 /server/api), 落地时核对
      // 真实端点后否掉了: mood/emotionalCloseness/sleepiness 三个字段由
      // openapi 的 OpenApiAgentStateController 直读同一张 agent_state 表给出,
      // 而 8091 的 StateController 走 CurrentUser.requireUserId() —— 要用户
      // JWT, 控制台手里只有 sap_ 客户端钥与 X-Admin-Key, 根本进不去 8091 那道门。
      // 所以这里的分流不是"按主机", 而是"按面"(见 src/api/client.ts 的 faceOf)。
      //
      // 与生产 nginx(G7)同一条规则: 前端只认同源 /api/v1/openapi/**, 由
      // 反向代理决定落到哪个上游。dev/prod 一致, 页面代码零感知。
      '/api': {
        target: 'http://127.0.0.1:8092',
        changeOrigin: true,
      },
    },
  },
})
