import { EditOutlined, UserOutlined } from '@ant-design/icons'
import {
  App,
  Avatar,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Radio,
  Result,
  Row,
  Select,
  Skeleton,
  Space,
  Tooltip,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import bodyMetricApi from '@/api/bodyMetricApi'
import userApi from '@/api/userApi'
import { useUserStore } from '@/store'
import { ErrorCode, Gender, toOptions, TrainingGoal, TrainingLevel } from '@/types'
import type { BodyMetricRequest, UpdateProfileRequest, UserProfile } from '@/types'

const DATE_FMT = 'YYYY-MM-DD'
const PROFILE_KEY = ['user', 'profile'] as const
/** 前缀与 Trends 页共用：体测录入后失效 ['bodyMetric'] 即可同时刷新最新体重与趋势图 */
const LATEST_METRIC_KEY = ['bodyMetric', 'latest'] as const

/** 档案表单值：出生日期用 Dayjs（DatePicker 的受控值），提交时才格式化成字符串 */
interface ProfileFormValues {
  nickname: string
  gender: Gender
  birthDate: Dayjs | null
  height: number | null
  weight: number | null
  trainingGoal: TrainingGoal
  trainingLevel: TrainingLevel
  injuryRecord: string[]
}

/** 体测表单值：体重必填，其余可选（后端 BodyMetricRequest 里也只有 weightKg 是必填） */
interface MetricFormValues {
  weightKg: number
  waistCm?: number
  armCm?: number
  legCm?: number
  bodyFatPct?: number
}

/** 档案 → 表单值 */
function toFormValues(profile: UserProfile): ProfileFormValues {
  return {
    nickname: profile.nickname,
    // gender 在后端可能为 null（历史数据没填），按规范映射到「未设置 0」
    gender: (profile.gender ?? Gender.UNSET) as Gender,
    birthDate: profile.birthDate ? dayjs(profile.birthDate) : null,
    height: profile.height,
    weight: profile.weight,
    // trainingGoal/Level 在后端是自由字符串，这里收敛到枚举；若是枚举外的历史值，
    // Select 会原样显示该字符串（不匹配任何 option），不会丢数据
    trainingGoal: (profile.trainingGoal ?? TrainingGoal.MAINTAIN) as TrainingGoal,
    trainingLevel: (profile.trainingLevel ?? TrainingLevel.BEGINNER) as TrainingLevel,
    injuryRecord: profile.injuryRecord ?? [],
  }
}

/** 体重展示：null 一律 `--`（显示 0 会被误读成真实体重） */
function showWeight(value: number | null | undefined): string {
  return value === null || value === undefined ? '--' : `${value} kg`
}

/**
 * 档案表单骨架
 * <p>规范要求「表单所有字段显示 Skeleton.Input」，因此先出骨架而不是空表单，
 * 避免用户看到一堆空输入框误以为数据丢了。
 */
function ProfileFormSkeleton() {
  return (
    <Space direction="vertical" size="large" className="w-full">
      {Array.from({ length: 6 }).map((_item, index) => (
        <div key={index} className="flex items-center gap-4">
          <Skeleton.Input active size="small" style={{ width: 88 }} />
          <Skeleton.Input active block size="default" />
        </div>
      ))}
    </Space>
  )
}

/**
 * 个人档案（/profile）
 *
 * <h3>两个独立表单，别混用</h3>
 * - 「个人档案」表单写 `t_user`（昵称/性别/身高/体重/目标/年限/伤病）
 * - 「身体数据录入（体测）」表单写 `t_body_metric`（每天的体重与围度）
 *
 * <h3>体重口径（规范明确要求，页面必须让用户也看得懂）</h3>
 * `t_user.weight` 是**注册时填的初始体重，只在手动改档案时更新**；
 * `t_body_metric.weight_kg` 才是**日常跟踪体重**。Dashboard/Trends 的「最新体重」一律取体测表，
 * 两者独立维护、**不自动同步**。因此左栏把体测体重明确标注为「最新体测体重」，
 * 避免用户把档案里的「体重」当成当前体重。
 */
export default function ProfilePage() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()

  const [profileForm] = Form.useForm<ProfileFormValues>()
  const [metricForm] = Form.useForm<MetricFormValues>()
  /** 编辑模式：false 时档案表单整体 disabled（规范要求） */
  const [editing, setEditing] = useState(false)

  // ==================== 查询 ====================

  const profileQuery = useQuery({
    queryKey: PROFILE_KEY,
    queryFn: () => userApi.getProfile(),
  })

  const latestMetricQuery = useQuery({
    queryKey: LATEST_METRIC_KEY,
    queryFn: () => bodyMetricApi.getLatest(),
  })

  useEffect(() => {
    // 档案返回/刷新后回填表单；「取消编辑」也复用它来丢弃未保存的改动
    if (profileQuery.data) {
      profileForm.setFieldsValue(toFormValues(profileQuery.data))
    }
  }, [profileQuery.data, profileForm])

  // ==================== 保存档案 ====================

  const updateMutation = useMutation({
    mutationFn: (payload: UpdateProfileRequest) => userApi.updateProfile(payload),

    onSuccess: async () => {
      message.success('档案已更新')
      setEditing(false)
      // updateProfile 只返回 void（不回新档案），左栏要用最新数据，必须重新拉一次
      const refreshed = await profileQuery.refetch()
      // 侧边栏昵称读的是 userStore 里的精简信息，不同步的话会一直显示旧昵称
      if (refreshed.data) {
        useUserStore.getState().syncFromProfile(refreshed.data)
      }
    },

    onError: (error: unknown) => {
      // 失败**不退出编辑模式**：用户已填的内容必须留在表单里
      // 拦截器已对非静默错误码弹过全局提示，这里再弹一次就是重复 toast；
      // 只兜底非 ApiError（本地异常/网络层以外的意外）这种没人提示的情况
      if (!(error instanceof ApiError)) {
        message.error('保存失败，请稍后重试')
      }
    },
  })

  // ==================== 体测录入 ====================

  const addMetricMutation = useMutation({
    mutationFn: (payload: BodyMetricRequest) => bodyMetricApi.addMetric(payload),

    onSuccess: () => {
      message.success('身体数据已记录')
      metricForm.resetFields()
      // 体测写入后 Dashboard 的体重卡片与 Trends 的图表都要刷新（规范要求 invalidateQueries）
      void queryClient.invalidateQueries({ queryKey: ['bodyMetric'] })
      void queryClient.invalidateQueries({ queryKey: ['stats', 'dashboard'] })
    },

    onError: (error: unknown) => {
      // 3001「当天已有记录」被拦截器列入静默错误码，不会自动弹提示，
      // 必须由这里告诉用户「改用修改接口」，否则点按钮像是没反应
      if (error instanceof ApiError && error.code === ErrorCode.BODY_METRIC_DUPLICATE) {
        message.warning('当天已有身体数据记录，请使用修改接口')
      }
    },
  })

  // ==================== 交互 ====================

  const startEdit = (): void => setEditing(true)

  const cancelEdit = (): void => {
    setEditing(false)
    if (profileQuery.data) {
      profileForm.setFieldsValue(toFormValues(profileQuery.data))
    }
  }

  const handleSave = (values: ProfileFormValues): void => {
    const payload: UpdateProfileRequest = {
      nickname: values.nickname.trim(),
      gender: values.gender,
      // 留空 → undefined = 不修改；后端 UpdateProfileRequest 没有「把字段清空为 null」的语义
      birthDate: values.birthDate ? values.birthDate.format(DATE_FMT) : undefined,
      height: values.height ?? undefined,
      weight: values.weight ?? undefined,
      trainingGoal: values.trainingGoal,
      trainingLevel: values.trainingLevel,
      injuryRecord: values.injuryRecord ?? [],
    }
    updateMutation.mutate(payload)
  }

  const handleMetricSubmit = (values: MetricFormValues): void => {
    addMetricMutation.mutate({
      // recordDate 不传：后端默认记为当天。同一用户同一天只能有一条（重复返回 3001）
      weightKg: values.weightKg,
      waistCm: values.waistCm ?? undefined,
      armCm: values.armCm ?? undefined,
      legCm: values.legCm ?? undefined,
      bodyFatPct: values.bodyFatPct ?? undefined,
    })
  }

  // ==================== 渲染 ====================

  const profile = profileQuery.data
  const latestMetric = latestMetricQuery.data

  return (
    <Row gutter={[16, 16]}>
      {/* 左栏：头像卡片 + 体测录入（与规范 ASCII 稿一致，体测在左栏下方） */}
      <Col xs={24} lg={8}>
        <Card>
          <div className="flex flex-col items-center text-center">
            {profileQuery.isLoading ? (
              <Skeleton.Avatar active size={80} shape="circle" />
            ) : (
              <Avatar size={80} icon={<UserOutlined />} style={{ backgroundColor: '#1677ff' }} />
            )}

            <div className="mt-3">
              {profileQuery.isLoading ? (
                <Skeleton.Input active size="small" style={{ width: 120 }} />
              ) : (
                <div className="text-lg font-medium">{profile?.nickname ?? '--'}</div>
              )}
            </div>

            <div className="mt-1">
              {profileQuery.isLoading ? (
                <Skeleton.Input active size="small" style={{ width: 150 }} />
              ) : (
                <span className="text-xs text-gray-400">
                  {profile?.trainingGoal ?? '未设置目标'} · {profile?.trainingLevel ?? '未设置年限'}
                </span>
              )}
            </div>

            {/* 体测体重单独展示：这是「当前体重」，与下面档案表单里的注册初始体重不是一个东西 */}
            <div className="mt-4 w-full rounded bg-gray-50 px-3 py-2 text-left">
              <Tooltip title="取 t_body_metric 最新一条记录。档案表单里的「体重」是注册初始值，两者独立维护、不自动同步">
                <span className="cursor-help text-xs text-gray-500">最新体测体重</span>
              </Tooltip>
              <div className="mt-1">
                {latestMetricQuery.isLoading ? (
                  <Skeleton.Input active size="small" style={{ width: 80 }} />
                ) : (
                  <span className="text-base font-medium">{showWeight(latestMetric?.weightKg)}</span>
                )}
                {latestMetric?.recordDate && (
                  <span className="ml-2 text-xs text-gray-400">（{latestMetric.recordDate}）</span>
                )}
              </div>
            </div>

            <Button
              type="primary"
              block
              className="mt-4"
              icon={<EditOutlined />}
              disabled={editing || profileQuery.isLoading}
              onClick={startEdit}
            >
              编辑资料
            </Button>
            {editing && (
              <div className="mt-2 text-xs text-gray-400">正在编辑，请在右侧保存或取消</div>
            )}
          </div>
        </Card>

        <Card title="身体数据录入（体测）" className="mt-4">
          <Form
            form={metricForm}
            layout="vertical"
            requiredMark={false}
            disabled={addMetricMutation.isPending}
            onFinish={handleMetricSubmit}
          >
            <Form.Item
              name="weightKg"
              label="体重 (kg)"
              rules={[
                { required: true, message: '请输入体重' },
                { type: 'number', min: 30, max: 300, message: '体重需在 30-300 kg' },
              ]}
            >
              <InputNumber min={30} max={300} step={0.1} precision={1} style={{ width: '100%' }} placeholder="30-300" />
            </Form.Item>

            <Row gutter={12}>
              <Col xs={12}>
                <Form.Item name="waistCm" label="腰围 (cm)">
                  <InputNumber min={20} max={200} step={0.1} precision={1} style={{ width: '100%' }} placeholder="可选" />
                </Form.Item>
              </Col>
              <Col xs={12}>
                <Form.Item name="armCm" label="臂围 (cm)">
                  <InputNumber min={10} max={100} step={0.1} precision={1} style={{ width: '100%' }} placeholder="可选" />
                </Form.Item>
              </Col>
              <Col xs={12}>
                <Form.Item name="legCm" label="腿围 (cm)">
                  <InputNumber min={20} max={150} step={0.1} precision={1} style={{ width: '100%' }} placeholder="可选" />
                </Form.Item>
              </Col>
              <Col xs={12}>
                <Form.Item name="bodyFatPct" label="体脂率 (%)">
                  <InputNumber min={3} max={60} step={0.1} precision={1} style={{ width: '100%' }} placeholder="可选" />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item className="mb-0" extra="记录日期由后端取当天；同一天只能录一条（重复请用修改接口）">
              <Button type="primary" block htmlType="submit" loading={addMetricMutation.isPending}>
                {addMetricMutation.isPending ? '正在记录...' : '记录身体数据'}
              </Button>
            </Form.Item>
          </Form>
        </Card>
      </Col>

      {/* 右栏：档案表单（未进入编辑模式时整体 disabled） */}
      <Col xs={24} lg={16}>
        <Card
          title="个人档案"
          extra={
            !editing && profileQuery.isSuccess ? (
              <span className="text-xs text-gray-400">只读状态</span>
            ) : null
          }
        >
          {profileQuery.isError ? (
            <Result
              status="error"
              title="档案加载失败"
              subTitle="请检查后端服务与网络后重试"
              extra={
                <Button type="primary" onClick={() => void profileQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : profileQuery.isLoading ? (
            <ProfileFormSkeleton />
          ) : (
            <Form
              form={profileForm}
              layout="vertical"
              requiredMark={false}
              disabled={!editing || updateMutation.isPending}
              onFinish={handleSave}
            >
              <Row gutter={16}>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="nickname"
                    label="昵称"
                    rules={[
                      { required: true, message: '请输入昵称' },
                      { min: 2, max: 20, message: '昵称长度需 2-20 字符' },
                    ]}
                  >
                    <Input maxLength={20} placeholder="2-20 字符" />
                  </Form.Item>
                </Col>

                <Col xs={24} sm={12}>
                  <Form.Item name="gender" label="性别" extra="「未设置」会提交 gender=0">
                    <Radio.Group>
                      <Radio value={Gender.MALE}>男</Radio>
                      <Radio value={Gender.FEMALE}>女</Radio>
                      <Radio value={Gender.UNSET}>未设置</Radio>
                    </Radio.Group>
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item
                name="birthDate"
                label="出生日期"
                extra="留空表示不修改（后端没有「清空该字段」的语义）"
              >
                <DatePicker
                  style={{ width: '100%' }}
                  placeholder="选择出生日期"
                  disabledDate={(current: Dayjs) => current.isAfter(dayjs(), 'day')}
                />
              </Form.Item>

              <Row gutter={16}>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name="height"
                    label="身高 (cm)"
                    rules={[{ type: 'number', min: 50, max: 250, message: '身高需在 50-250 cm' }]}
                  >
                    <InputNumber min={50} max={250} style={{ width: '100%' }} placeholder="50-250" />
                  </Form.Item>
                </Col>

                <Col xs={24} sm={12}>
                  <Form.Item
                    name="weight"
                    label="体重 (kg)（注册初始值）"
                    extra="写的是 t_user.weight，只在改档案时更新；日常体重请在左侧「身体数据录入」记录"
                    rules={[{ type: 'number', min: 30, max: 300, message: '体重需在 30-300 kg' }]}
                  >
                    <InputNumber min={30} max={300} step={0.1} precision={1} style={{ width: '100%' }} placeholder="30-300" />
                  </Form.Item>
                </Col>
              </Row>

              <Row gutter={16}>
                <Col xs={24} sm={12}>
                  <Form.Item name="trainingGoal" label="训练目标">
                    <Select options={toOptions(TrainingGoal)} placeholder="增肌 / 减脂 / 保持" />
                  </Form.Item>
                </Col>

                <Col xs={24} sm={12}>
                  <Form.Item name="trainingLevel" label="训练年限">
                    <Select options={toOptions(TrainingLevel)} placeholder="新手 / 进阶 / 老手" />
                  </Form.Item>
                </Col>
              </Row>

              <Form.Item
                name="injuryRecord"
                label="伤病记录"
                extra="输入后回车即新增标签，可多个；没有伤病可留空"
              >
                <Select
                  mode="tags"
                  tokenSeparators={[',', '，']}
                  placeholder="如：右膝半月板损伤"
                />
              </Form.Item>

              <Form.Item label="手机号" extra="后端返回的是脱敏值，不支持修改">
                <Input value={profile?.phone ?? '--'} disabled readOnly />
              </Form.Item>

              <Form.Item className="mb-0">
                {editing ? (
                  <Space>
                    <Button type="primary" htmlType="submit" loading={updateMutation.isPending}>
                      {updateMutation.isPending ? '保存中...' : '保存修改'}
                    </Button>
                    <Button onClick={cancelEdit} disabled={updateMutation.isPending}>
                      取消
                    </Button>
                  </Space>
                ) : (
                  <span className="text-xs text-gray-400">
                    点击左侧「编辑资料」后可修改（当前为只读状态）
                  </span>
                )}
              </Form.Item>
            </Form>
          )}
        </Card>
      </Col>
    </Row>
  )
}
