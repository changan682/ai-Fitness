import { EditOutlined, LoadingOutlined, UserOutlined } from '@ant-design/icons'
import {
  Alert,
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
  Upload,
} from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import aiApi from '@/api/aiApi'
import bodyMetricApi from '@/api/bodyMetricApi'
import userApi from '@/api/userApi'
import { useUserStore } from '@/store'
import { ErrorCode, Gender, toOptions, TrainingGoal, TrainingLevel } from '@/types'
import type {
  BodyConsultResponse,
  BodyMetricRequest,
  UpdateProfileRequest,
  UserProfile,
} from '@/types'

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

// ==================== AI 主动追问（批次 D） ====================

/**
 * 风险级别 → antd Alert 类型
 * <p>
 * 后端只给**语义级别**（info/warn/high），用哪个组件是前端的事。
 * 映射错了后果很实际：把 `high` 画成蓝色 info，等于把「这周体重掉了 3kg」这种
 * 需要用户重视的提示弱化成一句普通说明。
 */
const RISK_ALERT_TYPE: Record<string, 'info' | 'warning' | 'error'> = {
  info: 'info',
  warn: 'warning',
  high: 'error',
}

/** 卡片顶部的「诚实标记」条目，结构与 antd Alert 对齐 */
interface ConsultNotice {
  type: 'info' | 'warning' | 'error'
  message: string
  description?: string
}

/**
 * 把「这份结果打了什么折扣」翻译成界面上的标记
 * <p>
 * 项目的硬规矩是**降级/兜底结果必须在界面上看得见**，因此这里宁可多标也不漏标：
 * - `rule_based`：压根没调用大模型，是后端规则模板拼出来的。不标就等于把模板包装成 AI 分析结论；
 * - `degraded`：用了大模型但走了降级路径（数据太少、模型超时回落等），必须挂出原因；
 * - 其它未知 `dataSource`：后端将来新增来源时也不会「静默通过」——
 *   漏标比多标严重得多，未知来源先如实展示出来。
 */
function buildConsultNotices(data: BodyConsultResponse): ConsultNotice[] {
  const notices: ConsultNotice[] = []
  if (data.dataSource === 'rule_based') {
    notices.push({
      type: 'warning',
      message: '规则生成（未使用大模型）',
      description: data.degradationReason ?? undefined,
    })
  } else if (data.dataSource !== 'llm') {
    notices.push({
      type: 'warning',
      message: `结果来源：${data.dataSource}`,
      description: data.degradationReason ?? undefined,
    })
  }
  if (data.degraded && data.dataSource === 'llm') {
    notices.push({
      type: 'warning',
      message: '降级回答',
      description: data.degradationReason ?? undefined,
    })
  }
  return notices
}

/**
 * 「🤖 AI 主动追问」卡片
 * <p>
 * <h3>为什么做成手动触发而不是自动拉取</h3>
 * 后端 `/ai/body-consult` 会真的走一次大模型（每轮都要带身体数据进上下文）。
 * 若把它挂在 `useQuery` 上自动 fetch，用户每次打开档案页、每次保存体测都会触发一次调用：
 * 既烧 token，也会在用户只想「记个体重」时突然弹出一屏追问。
 * 所以这里用 `useMutation` —— **只有点了按钮才会请求**。
 */
function BodyConsultCard() {
  const consultMutation = useMutation({
    mutationFn: () => aiApi.bodyConsult(),
  })

  const data = consultMutation.data
  const notices = data ? buildConsultNotices(data) : []
  const { error } = consultMutation

  return (
    <Card title="🤖 AI 主动追问" className="mt-4">
      {consultMutation.isPending ? (
        /* 请求中先出骨架：这个接口要等大模型，空白卡片会被当成「点了没反应」 */
        <Skeleton active paragraph={{ rows: 4 }} title={false} />
      ) : (
        <>
          {/* 诚实标记永远排在最前面：用户第一眼就该知道这份结论是怎么来的 */}
          {notices.map((n) => (
            <Alert
              key={n.message}
              type={n.type}
              showIcon
              className="mb-2"
              message={n.message}
              description={n.description}
            />
          ))}

          {consultMutation.isError && (
            <Alert
              type="warning"
              showIcon
              className="mb-2"
              message={
                error instanceof ApiError && error.msg
                  ? error.msg
                  : 'AI 暂时不可用，请稍后再试'
              }
            />
          )}

          {data && (
            <div className="space-y-3">
              <div>
                <div className="mb-1 text-xs text-gray-500">整体判断</div>
                <div className="whitespace-pre-wrap text-sm">{data.assessment}</div>
              </div>

              <div>
                <div className="mb-1 text-xs text-gray-500">趋势要点</div>
                <div className="whitespace-pre-wrap text-sm">{data.trendSummary}</div>
              </div>

              {/* 风险提示逐条用 Alert：级别不同颜色不同，别合并成一段文字 */}
              {data.riskFlags.length > 0 && (
                <div className="space-y-2">
                  {data.riskFlags.map((flag, index) => (
                    <Alert
                      key={`${flag.level}-${index}`}
                      type={RISK_ALERT_TYPE[flag.level] ?? 'info'}
                      showIcon
                      message={flag.text}
                    />
                  ))}
                </div>
              )}

              {data.questions.length > 0 && (
                <div>
                  <div className="mb-1 text-xs text-gray-500">AI 想问你</div>
                  <ol className="list-decimal pl-5 text-sm">
                    {data.questions.map((q) => (
                      <li key={q.id} className="mb-1">
                        <div>{q.text}</div>
                        {/* why 用灰色小字：解释「为什么问这个」，用户才知道值不值得回答 */}
                        <div className="text-xs text-gray-400">{q.why}</div>
                      </li>
                    ))}
                  </ol>
                </div>
              )}

              {data.suggestions.length > 0 && (
                <div>
                  <div className="mb-1 text-xs text-gray-500">建议</div>
                  <ol className="list-decimal pl-5 text-sm">
                    {data.suggestions.map((s) => (
                      <li key={s.title} className="mb-1">
                        <div className="font-medium">{s.title}</div>
                        <div className="text-xs text-gray-500">{s.detail}</div>
                      </li>
                    ))}
                  </ol>
                </div>
              )}

              <div className="text-xs text-gray-400">
                生成于 {data.generatedAt}
                {/* 命中缓存要说出来：否则用户会以为「这就是刚刚算出来的」 */}
                {data.cached ? '（命中服务端缓存）' : ''}
              </div>
            </div>
          )}

          {!data && !consultMutation.isError && (
            <p className="mb-3 text-xs text-gray-400">
              AI 会结合你的体测数据与近期训练记录，主动问几个和你有关系的问题。
            </p>
          )}

          <Button
            type="primary"
            block
            className="mt-3"
            loading={consultMutation.isPending}
            onClick={() => consultMutation.mutate()}
          >
            {data ? '重新看看我的变化' : '让 AI 看看我的变化'}
          </Button>
        </>
      )}
    </Card>
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

  // ==================== 上传头像 ====================

  /** 前端只做体验层校验（大文件/明显不是图片先拦下来，省一次往返），真正的校验在服务端 */
  const AVATAR_MAX_BYTES = 2 * 1024 * 1024
  const AVATAR_TYPES = ['image/jpeg', 'image/png']

  const uploadAvatarMutation = useMutation({
    mutationFn: (file: File) => userApi.uploadAvatar(file),

    onSuccess: async (data) => {
      message.success('头像已更新')
      // 左栏头像读的是档案里的 avatarUrl：更新 store 让顶栏立刻换图，
      // 再 invalidate 档案查询让左栏拿到权威值（后端已顺带失效 Redis 档案缓存）
      const current = useUserStore.getState().user
      if (current) {
        useUserStore.getState().setUser({ ...current, avatarUrl: data.avatarUrl })
      }
      await queryClient.invalidateQueries({ queryKey: PROFILE_KEY })
    },

    onError: (error: unknown) => {
      // 1004（格式/大小不合法）不在拦截器的静默名单里，会由拦截器弹提示；
      // 这里只兜底非 ApiError 的情况，避免同一错误弹两次
      if (!(error instanceof ApiError)) {
        message.error('头像上传失败，请稍后重试')
      }
    },
  })

  const handleAvatarSelected = (file: File): boolean => {
    if (!AVATAR_TYPES.includes(file.type)) {
      message.error('头像仅支持 JPG / PNG 格式')
      return false
    }
    if (file.size > AVATAR_MAX_BYTES) {
      message.error('头像最大 2MB，请压缩后再上传')
      return false
    }
    uploadAvatarMutation.mutate(file)
    return false // 返回 false 阻止 antd 自己发上传请求，统一走我们封装的 axios 实例
  }

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
              <Upload
                accept="image/jpeg,image/png"
                showUploadList={false}
                beforeUpload={handleAvatarSelected}
                disabled={uploadAvatarMutation.isPending}
              >
                <Tooltip title="点击更换头像（JPG/PNG，≤2MB）">
                  <div className="relative cursor-pointer">
                    <Avatar
                      size={80}
                      src={profile?.avatarUrl ?? undefined}
                      icon={<UserOutlined />}
                      style={profile?.avatarUrl ? undefined : { backgroundColor: '#1677ff' }}
                    />
                    {/* 悬停高亮：不做的话用户不知道这个圆圈可以点 */}
                    <div className="absolute inset-0 flex items-center justify-center rounded-full bg-black/45 text-white opacity-0 transition-opacity hover:opacity-100">
                      {uploadAvatarMutation.isPending ? (
                        <LoadingOutlined />
                      ) : (
                        <span className="text-xs">更换头像</span>
                      )}
                    </div>
                  </div>
                </Tooltip>
              </Upload>
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

        {/* 主动问询放在体测卡片下方：用户刚看完自己的体重/围度，提问的动机最强 */}
        <BodyConsultCard />
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
