import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  resolve: {
    // 与 tsconfig.json 的 paths 必须一致：全部用 @/xxx 绝对导入，
    // 避免 ../../../ 这种层级耦合，页面搬家时不用改一堆 import
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  server: {
    port: 5173,
    // 规范要求：Vite proxy 把 /api 转发到 Java 后端（8080）。
    // 前端只认自己的同源 /api，完全不知道 Java 在 8080、也不知道 Python/Milvus 的存在
    //（Java 是 BFF，前端只跟它交互），因此这里不做任何路径重写。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },

  build: {
    outDir: 'dist',
    sourcemap: false,
    // antd + echarts 体积偏大，放宽单 chunk 告警阈值
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 手动分包：把「很少变动的大依赖」与业务代码分开。
        // 好处有两点：① 业务代码改动时用户的这些大包仍然命中浏览器缓存；
        // ② 首屏不必为一个 1MB 的图表库让路（Trends 页本身也做了懒加载）。
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd', '@ant-design/icons'],
          'chart-vendor': ['echarts', 'echarts-for-react'],
          'query-vendor': ['@tanstack/react-query', 'axios', 'zustand'],
          'markdown-vendor': ['react-markdown', 'remark-gfm'],
        },
      },
    },
  },
})
