/**
 * 设计系统 —— 与仓 1 聊天前端**同构**的一套语义 token。
 *
 * 刻意不用"抄一份"的方式保持一致, 而是**同一个定义**: 两份 config 的 `colors` 块
 * 逐字相同, `src/index.css` 的 `:root` / `.dark` 也逐字相同。两个前端属于同一个产品,
 * 而"同步"这件事只要靠人去记, 就一定会漂 —— 漂的表现是某个按钮在两个站点里
 * 是两种蓝, 那种错误没人会报告, 但所有人都会觉得哪里不对。
 *
 * **核心决策: 语义 CSS 变量 + `darkMode: 'class'`, 组件只用语义名, 全项目零 `dark:` 前缀。**
 * 于是双主题的成本是每个概念**一个类名**, 而不是每个类名两遍。
 *
 * 旧的暖棕调色板(cocoa/ember/rosewood/jade)与 `shadow-glow` / `shadow-panel` 已整体删除。
 * `shadow-glow` 里那个暖金 `rgba(233,180,103,.18)` 正是"暧昧"的签名 —— 它不是一个
 * 阴影参数, 它是一个态度, 而这个控制台不需要那个态度。
 */
module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // ── 面 ──────────────────────────────
        /** 页面底 */
        surface: 'rgb(var(--surface) / <alpha-value>)',
        /** 卡片/面板/浮层。亮色下与 surface 同色, 靠边框区分 —— 高级感来自边界, 不来自色块 */
        raised: 'rgb(var(--raised) / <alpha-value>)',
        /** 输入框/搜索框/代码块 —— 视觉上"凹进去"的地方 */
        sunken: 'rgb(var(--sunken) / <alpha-value>)',
        /** 遮罩 */
        scrim: 'rgb(var(--scrim) / <alpha-value>)',

        // ── 线 ──────────────────────────────
        /** 发丝分隔线 */
        line: 'rgb(var(--line) / <alpha-value>)',
        /** 需要被看见的边框(聚焦、选中) */
        'line-strong': 'rgb(var(--line-strong) / <alpha-value>)',

        // ── 字 ──────────────────────────────
        ink: 'rgb(var(--ink) / <alpha-value>)',
        'ink-soft': 'rgb(var(--ink-soft) / <alpha-value>)',
        'ink-faint': 'rgb(var(--ink-faint) / <alpha-value>)',

        // ── 强调 ────────────────────────────
        /** 电光蓝。全局唯一的强调色 */
        accent: 'rgb(var(--accent) / <alpha-value>)',
        /** 淡蓝底(选中行、标签) */
        'accent-soft': 'rgb(var(--accent-soft) / <alpha-value>)',
        /** 蓝底上的字 */
        'accent-ink': 'rgb(var(--accent-ink) / <alpha-value>)',

        ok: 'rgb(var(--ok) / <alpha-value>)',
        warn: 'rgb(var(--warn) / <alpha-value>)',
        danger: 'rgb(var(--danger) / <alpha-value>)',

        // ── 事件的三种类别 ──────────────────
        // V2.2 §5.2 的三个能力接口。**它们不是三种事件**, 是同一个事件可以同时
        // 属于的三种处理方式。界面上只承担一件事: 让人一眼看出这条要不要马上管。
        /** A 持续影响 —— 慢, 但一直在(降温、饥饿) */
        'cat-effect': 'rgb(var(--cat-effect) / <alpha-value>)',
        /** B 实时感官 —— 必须现在处理(手机响、疼) */
        'cat-sensory': 'rgb(var(--cat-sensory) / <alpha-value>)',
        /** C 计划表 —— 时间段型, 属于时间轴 */
        'cat-schedule': 'rgb(var(--cat-schedule) / <alpha-value>)',

        // ── IM 气泡 ─────────────────────────
        // 这个控制台不画气泡, 但 token 留着 —— 两个前端的变量定义要能逐字对照,
        // 少三项的后果是下次同步时没人知道该不该补。
        'bubble-in': 'rgb(var(--bubble-in) / <alpha-value>)',
        'bubble-out': 'rgb(var(--bubble-out) / <alpha-value>)',
        'bubble-out-ink': 'rgb(var(--bubble-out-ink) / <alpha-value>)',
      },
      fontFamily: {
        sans: ['Inter', 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', '-apple-system', 'sans-serif'],
        /** 技术标识专用: API key / session id / agent id。科技感来自"确定性", 等宽是它最省的载体 */
        mono: ['"JetBrains Mono"', '"SF Mono"', 'Menlo', 'Consolas', 'monospace'],
      },
      boxShadow: {
        /** 全局唯一的阴影。只给浮层用 —— 页面上的卡片一律靠边框, 不靠投影 */
        pop: 'var(--shadow-pop)',
      },
      transitionDuration: { DEFAULT: '150ms' },
      keyframes: {
        fadeUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: { '0%': { opacity: '0' }, '100%': { opacity: '1' } },
      },
      animation: {
        fadeUp: 'fadeUp .35s ease-out both',
        fadeIn: 'fadeIn .25s ease-out both',
      },
    },
  },
  plugins: [],
}
