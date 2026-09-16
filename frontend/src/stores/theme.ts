import { create } from 'zustand'

export type Theme = 'light' | 'dark'

/** 与 `index.html` 里那段内联脚本读的是同一个键 —— 改这里必须同时改那里。 */
export const THEME_KEY = 'luxera.theme'

/**
 * 主题的**判定**与主题的**应用**分开写。
 *
 * 判定是纯函数, 能在 `environment: 'node'` 下直接测; 应用要碰 `document`/`localStorage`,
 * 那两个东西在 node 下不存在。把它们揉在一起, 这条逻辑就永远测不到 ——
 * 而"刷新后主题对不对"恰恰是用户唯一能感知的那部分。
 *
 * 与仓 1 的 `stores/theme.ts` 是同一份逻辑, **连 localStorage 的键都一样**:
 * 两个前端同域不同路径(`chat.luxera.top` / `being.luxera.top` 是两个域名, 但本地
 * 开发时是 localhost 的两个端口), 用户在一处选了深色, 到另一处不必再选一次。
 */
export function pickTheme(stored: string | null, prefersDark: boolean): Theme {
  // 存过就以存的为准 —— 用户手动选过就不该被系统设置再改回去
  if (stored === 'dark' || stored === 'light') return stored
  return prefersDark ? 'dark' : 'light'
}

function storage(): Storage | null {
  return typeof localStorage === 'undefined' ? null : localStorage
}

function prefersDark(): boolean {
  return typeof matchMedia === 'undefined' ? false : matchMedia('(prefers-color-scheme: dark)').matches
}

/** 把主题写到 `<html>` 上。`index.css` 的 `.dark` 块靠这个 class 生效。 */
export function applyTheme(theme: Theme): void {
  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
  toggle: () => void
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: pickTheme(storage()?.getItem(THEME_KEY) ?? null, prefersDark()),

  setTheme: (theme) => {
    applyTheme(theme)
    // 只有**用户主动选**才写 localStorage。首屏那段内联脚本刻意不写回 ——
    // 否则"跟随系统"的用户在第一次打开时就被钉死成了当时的系统值。
    storage()?.setItem(THEME_KEY, theme)
    set({ theme })
  },

  toggle: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
}))
