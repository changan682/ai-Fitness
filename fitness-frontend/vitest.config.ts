import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

/**
 * Vitest 配置
 * <p>
 * 复用 `vite.config.ts`（`@/` 别名、React 插件），只叠加测试相关配置 ——
 * 否则别名要在两处各写一遍，迟早不一致。
 *
 * 环境用 jsdom：这些测试是**渲染冒烟测试**，目的是证明页面在浏览器环境里
 * 真的能挂载并输出内容（而不是只看类型检查过不过）。
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      // 样式不参与断言，关掉可省大量解析时间
      css: false,
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      // antd 组件在 jsdom 里挂载较慢（页面级用例 3-10s），
      // vitest 默认 5s 超时会把正常用例判成失败，放宽到 30s
      testTimeout: 30_000,
      hookTimeout: 30_000,
      // 渲染失败时报出 antd 的告警，便于定位
      silent: false,
    },
  }),
)
