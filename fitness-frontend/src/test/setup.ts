import '@testing-library/jest-dom/vitest'
import { vi } from 'vitest'

/**
 * 测试环境补齐 jsdom 缺失的浏览器 API
 * <p>
 * 这三项都是 antd / ECharts 在真实浏览器里依赖、而 jsdom 不实现的：
 * 不补的话所有渲染测试都会以「xxx is not a function」失败，与业务代码无关。
 */

// 1) matchMedia：Grid.useBreakpoint（AppLayout 的响应式分支）、Drawer 等都要用。
//    这里让 min-width 查询按阈值判断，使响应式布局走「桌面端」路径（测试更稳定）：
//    桌面分支是固定 Sider，比 Drawer 更容易断言。
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => {
    const minWidth = /min-width:\s*(\d+)/.exec(query)
    return {
      matches: minWidth ? Number(minWidth[1]) <= 1024 : false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }
  },
})

// 2) ResizeObserver：antd 的 Form/Table 等组件用它做尺寸观察
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver

// 3) scrollIntoView：ChatTab 每次回答后滚动到底部（jsdom 未实现）
window.HTMLElement.prototype.scrollIntoView = vi.fn()

/**
 * 把 ECharts 换成空组件
 * <p>
 * ECharts 需要 canvas，jsdom 里没有。趋势页的**渲染**（标题、筛选器、空状态、Skeleton）
 * 与图表无关，因此本文件只测页面骨架；图表本身由 `vite build` 与人工浏览验证。
 */
vi.mock('echarts-for-react', () => ({
  default: () => null,
}))
