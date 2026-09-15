// 主题沿用仓 1 聊天前端的暖棕(cocoa/ember) —— 两个前端属于同一个产品,
// 但布局语言不同: 聊天是 IM(会话侧栏), 这里是管理控制台(顶部栏 + 侧边导航)。
module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        cocoa: {
          950: '#14100e',
          900: '#1a1512',
          850: '#201a16',
          800: '#272019',
          700: '#332a21',
          600: '#473a2d',
          500: '#6b5844',
          400: '#9a8168',
          300: '#c3ac90',
          200: '#dfd0ba',
          100: '#efe5d6',
          50: '#f9f4ec',
        },
        ember: {
          DEFAULT: '#d97757',
          soft: '#e8b467',
          deep: '#b45a3f',
          pale: '#f4d7c4',
        },
        rosewood: {
          DEFAULT: '#a85d6f',
          soft: '#c98a97',
        },
        jade: {
          DEFAULT: '#5f9e7d',
          soft: '#8ec2a6',
        },
      },
      fontFamily: {
        sans: ['Inter', 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', '-apple-system', 'sans-serif'],
        mono: ['"JetBrains Mono"', '"SF Mono"', 'Menlo', 'Consolas', 'monospace'],
      },
      boxShadow: {
        glow: '0 0 0 1px rgba(233,180,103,0.18), 0 8px 40px -12px rgba(0,0,0,0.55)',
        panel: '0 1px 0 rgba(255,255,255,0.03) inset, 0 12px 40px -16px rgba(0,0,0,0.6)',
      },
      keyframes: {
        fadeUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
      },
      animation: {
        fadeUp: 'fadeUp .35s ease-out both',
        fadeIn: 'fadeIn .25s ease-out both',
      },
    },
  },
  plugins: [],
}
