import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'

/** 404 页面 */
export default function NotFoundPage() {
  const navigate = useNavigate()

  return (
    <div className="flex min-h-screen items-center justify-center">
      <Result
        status="404"
        title="页面未找到"
        subTitle="您访问的页面不存在或已被移除"
        extra={
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            返回首页
          </Button>
        }
      />
    </div>
  )
}
