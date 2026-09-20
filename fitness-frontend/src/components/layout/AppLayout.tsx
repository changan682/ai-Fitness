import {
  DashboardOutlined,
  LineChartOutlined,
  LogoutOutlined,
  MenuOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Avatar, Button, Drawer, Grid, Layout, Menu } from 'antd'
import { useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import ErrorBoundary from '@/components/common/ErrorBoundary'
import { useUserStore } from '@/store'

const { Sider, Content, Header } = Layout

/** 侧边栏菜单项（key 即路由路径，selectedKeys 直接比对 location.pathname 就能高亮） */
const MENU_ITEMS = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '训练看板' },
  { key: '/ai', icon: <RobotOutlined />, label: 'AI助手' },
  { key: '/trends', icon: <LineChartOutlined />, label: '数据趋势' },
  { key: '/profile', icon: <UserOutlined />, label: '个人档案' },
]

/**
 * 受保护页面的共用布局
 *
 * <h3>响应式策略</h3>
 * 桌面端用可折叠的固定 `Sider`；移动端（<768px）改用 `Drawer` + 顶部汉堡按钮 ——
 * 固定侧栏在手机上会挤掉正文宽度，且 antd 的 Sider 折叠态仍占 80px。
 *
 * <h3>为什么把 ErrorBoundary 放在这里</h3>
 * 包在 `<Outlet />` 外层后，单个页面渲染崩溃只会替换内容区，
 * 侧边栏与导航仍然可用（规范要求「单个页面的错误不影响其他页面」）。
 */
export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useUserStore((s) => s.user)
  const logout = useUserStore((s) => s.logout)

  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md

  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  const handleMenuClick = ({ key }: { key: string }): void => {
    void navigate(key)
    setDrawerOpen(false)
  }

  const handleLogout = async (): Promise<void> => {
    // logout() 内部已容错：后端不可达也会清理本地登录态
    await logout()
    void navigate('/login', { replace: true })
  }

  const sidebarContent = (
    <div className="flex h-full flex-col">
      {/* 顶部：头像 + 昵称（数据来自 userStore，登录接口只给了精简信息） */}
      <div className="flex items-center gap-3 px-4 py-4">
        <Avatar icon={<UserOutlined />} className="shrink-0 bg-blue-500" />
        {!(collapsed && !isMobile) && (
          <div className="min-w-0">
            <div className="truncate text-sm font-medium">
              🏋️ {user?.nickname ?? '未登录'}
            </div>
            <div className="truncate text-xs text-gray-400">
              {user?.trainingGoal || 'AI健身私教'}
            </div>
          </div>
        )}
      </div>

      <Menu
        mode="inline"
        selectedKeys={[location.pathname]}
        items={MENU_ITEMS}
        onClick={handleMenuClick}
        className="flex-1 border-e-0"
      />

      {/* 底部：退出登录 */}
      <div className="border-t border-gray-100 p-2">
        <Button
          type="text"
          danger
          block
          icon={<LogoutOutlined />}
          onClick={() => void handleLogout()}
        >
          {!(collapsed && !isMobile) && '退出登录'}
        </Button>
      </div>
    </div>
  )

  return (
    <Layout className="min-h-screen">
      {isMobile ? (
        <Drawer
          placement="left"
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          width={232}
          styles={{ body: { padding: 0 } }}
        >
          {sidebarContent}
        </Drawer>
      ) : (
        <Sider
          theme="light"
          collapsible
          collapsed={collapsed}
          onCollapse={setCollapsed}
          width={232}
          className="border-r border-gray-100"
        >
          {sidebarContent}
        </Sider>
      )}

      <Layout>
        {isMobile && (
          <Header className="flex items-center gap-3 bg-white px-4 shadow-sm" style={{ height: 56, lineHeight: '56px' }}>
            <Button
              type="text"
              icon={<MenuOutlined />}
              onClick={() => setDrawerOpen(true)}
              aria-label="打开菜单"
            />
            <span className="text-base font-medium">AI健身私教 & 体态管家</span>
          </Header>
        )}

        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <ErrorBoundary>
            <Outlet />
          </ErrorBoundary>
        </Content>
      </Layout>
    </Layout>
  )
}
