import { Tabs } from 'antd'
import { useState } from 'react'
import LoginForm from './LoginForm'
import RegisterForm from './RegisterForm'

type TabKey = 'login' | 'register'

/**
 * 登录 / 注册 Tab 容器
 *
 * <p>两个表单之间需要互相联动：
 * - 注册成功 → 切到登录 Tab 并预填手机号（规范要求的交互细节）
 * - 「该手机号未注册 / 已注册」→ 切到另一个 Tab
 * 因此 activeKey 与预填手机号状态都由这里持有。
 */
export default function AuthTabs() {
  const [activeKey, setActiveKey] = useState<TabKey>('login')
  const [presetPhone, setPresetPhone] = useState<string | undefined>(undefined)

  return (
    <Tabs
      activeKey={activeKey}
      onChange={(key) => setActiveKey(key as TabKey)}
      centered
      items={[
        {
          key: 'login',
          label: '登录',
          children: (
            <LoginForm
              presetPhone={presetPhone}
              onGoRegister={() => setActiveKey('register')}
            />
          ),
        },
        {
          key: 'register',
          label: '注册',
          children: (
            <RegisterForm
              onRegistered={(phone) => {
                setPresetPhone(phone)
                setActiveKey('login')
              }}
              onGoLogin={() => setActiveKey('login')}
            />
          ),
        },
      ]}
    />
  )
}
