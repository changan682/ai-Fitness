/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 与 App.tsx 的 ConfigProvider colorPrimary 保持一致，避免两套主题色
        brand: '#1677ff',
      },
    },
  },
  plugins: [],
  corePlugins: {
    // 关闭 preflight：它会重置 button/input 等元素的基础样式，
    // 与 Ant Design 5 的组件样式冲突（按钮高度、边框、表头字重都会错乱）。
    // 本项目 Tailwind 只承担布局辅助类，组件外观统一交给 antd。
    preflight: false,
  },
}
