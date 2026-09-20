import { RouterProvider } from 'react-router-dom'
import { router } from '@/router'

/**
 * 应用根组件
 *
 * <p>只负责挂载路由。全局的 ConfigProvider / AntdApp / QueryClientProvider
 * 都在 `main.tsx` 里（它们不依赖路由，放在外层可以覆盖到所有路由元素）。
 */
export default function App() {
  return <RouterProvider router={router} />
}
