import { LockOutlined, MobileOutlined } from '@ant-design/icons'
import { App, Button, Checkbox, Form, Input } from 'antd'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import userApi from '@/api/userApi'
import { useUserStore } from '@/store'
import { ErrorCode } from '@/types'
import { isRemembered, readLastPhone, saveLastPhone } from '@/utils/tokenStorage'

const PHONE_PATTERN = /^1[3-9]\d{9}$/

interface LoginFormValues {
  phone: string
  password: string
  remember: boolean
}

interface Props {
  /** 注册成功后由父组件切到登录 Tab 并预填手机号 */
  presetPhone?: string
  /** 提示「该手机号未注册」时，点击可切到注册 Tab */
  onGoRegister: () => void
}

/**
 * 登录表单
 *
 * <p>错误处理分工：密码错误（1002）与用户不存在（1003）由**本组件**展示，
 * axios 拦截器对这两个码是静默的（见 `client.ts` 的 SILENT_CODES），
 * 因为只有这里知道该把错误放在密码框下还是提示「去注册」。
 */
export default function LoginForm({ presetPhone, onGoRegister }: Props) {
  const [form] = Form.useForm<LoginFormValues>()
  const [submitting, setSubmitting] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { message } = App.useApp()
  const login = useUserStore((s) => s.login)

  // 上次勾选过「记住我」则自动回填手机号；注册成功后也会带回手机号
  useEffect(() => {
    const phone = presetPhone ?? (isRemembered() ? (readLastPhone() ?? '') : '')
    if (phone) {
      form.setFieldsValue({ phone })
    }
  }, [presetPhone, form])

  const handleSubmit = async (values: LoginFormValues): Promise<void> => {
    setSubmitting(true)
    try {
      const data = await userApi.login({ phone: values.phone, password: values.password })

      if (values.remember) {
        saveLastPhone(values.phone)
      }
      login(data.token, data.user, values.remember)
      message.success(`欢迎回来，${data.user.nickname}`)

      // 被路由守卫踢到登录页时跳回原页面（ProtectedRoute 会把来源路径写进 state.from）
      const from = (location.state as { from?: string } | null)?.from
      void navigate(from ?? '/dashboard', { replace: true })
    } catch (e) {
      if (e instanceof ApiError && e.code === ErrorCode.PASSWORD_ERROR) {
        message.error('密码错误，请重试')
        // 清空密码并聚焦，省得用户手动全选删除
        form.setFieldValue('password', '')
        form.getFieldInstance('password')?.focus?.()
      } else if (e instanceof ApiError && e.code === ErrorCode.USER_NOT_FOUND) {
        message.error('该手机号未注册')
        onGoRegister()
      }
      // 其余错误码已由拦截器统一提示，这里不重复弹
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{ remember: isRemembered() }}
      onFinish={(values) => void handleSubmit(values)}
      disabled={submitting}
      requiredMark={false}
    >
      <Form.Item
        name="phone"
        label="手机号"
        rules={[
          { required: true, message: '请输入手机号' },
          { pattern: PHONE_PATTERN, message: '请输入 11 位有效手机号' },
        ]}
      >
        <Input
          prefix={<MobileOutlined className="text-gray-300" />}
          placeholder="请输入手机号"
          maxLength={11}
          autoComplete="username"
          size="large"
        />
      </Form.Item>

      <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password
          prefix={<LockOutlined className="text-gray-300" />}
          placeholder="请输入密码"
          autoComplete="current-password"
          size="large"
        />
      </Form.Item>

      <Form.Item name="remember" valuePropName="checked" className="mb-3">
        <Checkbox>记住我（7天免登录）</Checkbox>
      </Form.Item>

      <Form.Item className="mb-2">
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          {submitting ? '正在登录...' : '登 录'}
        </Button>
      </Form.Item>

      <div className="text-center text-sm text-gray-500">
        没有账号？
        <Button type="link" size="small" onClick={onGoRegister}>
          立即注册
        </Button>
      </div>
    </Form>
  )
}
