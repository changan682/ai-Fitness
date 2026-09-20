import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useUserStore } from '@/store'

/**
 * 路由守卫
 *
 * <p>未登录时把**当前完整路径**塞进 `location.state.from`，
 * 登录成功后由 LoginPage 读回来跳转，这样用户被踢下线后能回到原页面
 * （规范明确要求的交互细节）。
 *
 * <p>登录态由 `useUserStore.isAuthenticated` 判定，而它已在应用启动时
 * 通过 `restoreSession()` 从 storage 恢复，因此刷新页面不会误判成未登录。
 */
export default function ProtectedRoute() {
  const isAuthenticated = useUserStore((s) => s.isAuthenticated)
  const location = useLocation()

  if (!isAuthenticated) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: location.pathname + location.search }}
      />
    )
  }

  return <Outlet />
}
