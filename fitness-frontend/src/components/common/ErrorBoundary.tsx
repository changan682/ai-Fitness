import { Button, Result } from 'antd'
import { Component, type ErrorInfo, type ReactNode } from 'react'
import { navigateTo } from '@/utils/bridge'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * 全局错误边界 —— 捕获子组件**渲染期**的 JS 异常，避免整站白屏
 *
 * <p>注意事项：
 * - 只能捕获渲染/生命周期/构造函数里的错误，**捕获不到事件处理器与异步错误**
 *   （那些由 axios 拦截器和各页面的 catch 负责）
 * - 需要包在路由 `Outlet` 外层，这样单个页面崩溃不会带走侧边栏与其他页面
 */
export default class ErrorBoundary extends Component<Props, State> {
  override state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    // 控制台留全量堆栈；生产环境可在这里接入前端监控上报
    console.error('[ErrorBoundary] 页面渲染异常:', error, info.componentStack)
  }

  private readonly handleReload = (): void => {
    window.location.reload()
  }

  private readonly handleGoHome = (): void => {
    this.setState({ hasError: false, error: null })
    navigateTo('/dashboard')
  }

  override render(): ReactNode {
    const { hasError, error } = this.state
    if (!hasError) {
      return this.props.children
    }

    return (
      <Result
        status="error"
        title="页面出错了"
        subTitle="请尝试刷新页面或返回首页"
        extra={[
          <Button key="reload" onClick={this.handleReload}>
            刷新页面
          </Button>,
          <Button key="home" type="primary" onClick={this.handleGoHome}>
            返回首页
          </Button>,
        ]}
      >
        {import.meta.env.DEV && error && (
          <pre className="max-h-80 overflow-auto rounded bg-gray-50 p-3 text-left text-xs text-red-600">
            {error.stack ?? error.message}
          </pre>
        )}
      </Result>
    )
  }
}
