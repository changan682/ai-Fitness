import { LockOutlined, MobileOutlined, UserOutlined } from '@ant-design/icons'
import { App, Button, Form, Input, Progress, Select } from 'antd'
import { useMemo, useState } from 'react'
import { ApiError } from '@/api/client'
import userApi from '@/api/userApi'
import { ErrorCode, Gender, toOptions, TrainingGoal, TrainingLevel } from '@/types'
import type { RegisterRequest } from '@/types'

const PHONE_PATTERN = /^1[3-9]\d{9}$/
/** 与后端 `ValidationPatterns.PASSWORD` 完全一致：8-32 位且含大小写字母与数字 */
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)\S{8,32}$/

interface RegisterFormValues {
  nickname: string
  phone: string
  password: string
  confirmPassword: string
  trainingGoal: TrainingGoal
  trainingLevel: TrainingLevel
}

interface Props {
  /** 注册成功：父组件切到登录 Tab 并预填手机号 */
  onRegistered: (phone: string) => void
  /** 手机号已注册：切到登录 Tab */
  onGoLogin: () => void
}

/**
 * 密码强度评估（规范：含大小写字母 + 数字 + 长度 ≥8）
 * <p>
 * 返回值直接喂给 antd `Progress`：<30% 红、30-70% 橙、>70% 绿。
 */
function evaluateStrength(password: string): { percent: number; strokeColor: string; label: string } {
  if (!password) return { percent: 0, strokeColor: '#d9d9d9', label: '' }

  let score = 0
  // 长度分（8 位满分）
  score += Math.min(34, Math.round((password.length / 8) * 34))
  // 同时含大小写
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 33
  // 含数字
  if (/\d/.test(password)) score += 33

  const percent = Math.min(100, score)
  if (percent < 30) return { percent, strokeColor: '#ff4d4f', label: '弱' }
  if (percent <= 70) return { percent, strokeColor: '#faad14', label: '中' }
  return { percent, strokeColor: '#52c41a', label: '强' }
}

/** 注册表单 */
export default function RegisterForm({ onRegistered, onGoLogin }: Props) {
  const [form] = Form.useForm<RegisterFormValues>()
  const [submitting, setSubmitting] = useState(false)
  const { message } = App.useApp()
  const password = Form.useWatch('password', form) ?? ''
  const strength = useMemo(() => evaluateStrength(password), [password])

  const handleSubmit = async (values: RegisterFormValues): Promise<void> => {
    setSubmitting(true)
    try {
      const payload: RegisterRequest = {
        nickname: values.nickname.trim(),
        phone: values.phone,
        password: values.password,
        gender: Gender.UNSET,
        trainingGoal: values.trainingGoal,
        trainingLevel: values.trainingLevel,
      }
      await userApi.register(payload)
      message.success('注册成功！欢迎加入')
      onRegistered(values.phone)
      form.resetFields()
    } catch (e) {
      if (e instanceof ApiError && e.code === ErrorCode.PHONE_REGISTERED) {
        message.warning('该手机号已注册，请直接登录')
        onGoLogin()
      }
      // 其余错误码由拦截器统一提示（含 9003 参数校验的 warning）
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={{ trainingGoal: TrainingGoal.MAINTAIN, trainingLevel: TrainingLevel.BEGINNER }}
      onFinish={(values) => void handleSubmit(values)}
      disabled={submitting}
      requiredMark={false}
    >
      <Form.Item
        name="nickname"
        label="昵称"
        rules={[
          { required: true, message: '请输入昵称' },
          { min: 2, max: 20, message: '昵称长度需 2-20 字符' },
        ]}
      >
        <Input
          prefix={<UserOutlined className="text-gray-300" />}
          placeholder="2-20 字符"
          size="large"
          maxLength={20}
        />
      </Form.Item>

      <Form.Item
        name="phone"
        label="手机号"
        rules={[
          { required: true, message: '请输入手机号' },
          { pattern: PHONE_PATTERN, message: '请输入 11 位有效手机号' },
        ]}
        extra="提交时会校验是否已被注册（后端没有独立的「手机号查重」接口）"
      >
        <Input
          prefix={<MobileOutlined className="text-gray-300" />}
          placeholder="请输入手机号"
          maxLength={11}
          size="large"
        />
      </Form.Item>

      <Form.Item
        name="password"
        label="密码"
        rules={[
          { required: true, message: '请输入密码' },
          {
            pattern: PASSWORD_PATTERN,
            message: '密码需 8-32 位且同时包含大小写字母和数字',
          },
        ]}
      >
        <Input.Password
          prefix={<LockOutlined className="text-gray-300" />}
          placeholder="8-32 位，含大小写字母和数字"
          autoComplete="new-password"
          size="large"
        />
      </Form.Item>

      {password && (
        <div className="-mt-3 mb-3">
          <Progress
            percent={strength.percent}
            strokeColor={strength.strokeColor}
            showInfo={false}
            size="small"
          />
          <span className="text-xs text-gray-500">密码强度：{strength.label}</span>
        </div>
      )}

      <Form.Item
        name="confirmPassword"
        label="确认密码"
        dependencies={['password']}
        rules={[
          { required: true, message: '请再次输入密码' },
          ({ getFieldValue }) => ({
            validator(_rule, value: string) {
              if (!value || getFieldValue('password') === value) {
                return Promise.resolve()
              }
              return Promise.reject(new Error('两次输入的密码不一致'))
            },
          }),
        ]}
      >
        <Input.Password
          prefix={<LockOutlined className="text-gray-300" />}
          placeholder="请再次输入密码"
          autoComplete="new-password"
          size="large"
        />
      </Form.Item>

      <div className="flex gap-3">
        <Form.Item name="trainingGoal" label="训练目标" className="flex-1">
          <Select options={toOptions(TrainingGoal)} size="large" />
        </Form.Item>
        <Form.Item name="trainingLevel" label="训练水平" className="flex-1">
          <Select options={toOptions(TrainingLevel)} size="large" />
        </Form.Item>
      </div>

      <Form.Item className="mb-2">
        <Button type="primary" htmlType="submit" block size="large" loading={submitting}>
          {submitting ? '正在注册...' : '注 册'}
        </Button>
      </Form.Item>

      <div className="text-center text-sm text-gray-500">
        已有账号？
        <Button type="link" size="small" onClick={onGoLogin}>
          去登录
        </Button>
      </div>
    </Form>
  )
}
