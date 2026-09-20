import { Card } from 'antd'
import { Navigate } from 'react-router-dom'
import AuthTabs from '@/components/auth/AuthTabs'
import { useUserStore } from '@/store'

/**
 * 登录 / 注册页
 *
 * <p>已登录用户访问会直接跳 Dashboard（规范要求），避免手动敲 /login 后
 * 看到一个已经用不上的表单。
 */
export default function LoginPage() {
  const isAuthenticated = useUserStore((s) => s.isAuthenticated)

  if (isAuthenticated) {
    return <Navigate to="/dashboard" replace />
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-gray-100 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <h1 className="mb-1 text-2xl font-semibold text-gray-800">
            🏋️ AI健身私教 &amp; 体态管家
          </h1>
          <p className="text-sm text-gray-500">你的专属AI私人教练</p>
        </div>

        <Card className="shadow-lg" styles={{ body: { padding: '8px 24px 20px' } }}>
          <AuthTabs />
        </Card>

        <p className="mt-4 text-center text-xs text-gray-400">
          登录后与 Java 后端（/api）通信 · AI 能力由 Java 代理转发
        </p>
      </div>
    </div>
  )
}
