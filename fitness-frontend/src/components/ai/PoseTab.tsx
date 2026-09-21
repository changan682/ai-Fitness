import { CameraOutlined, InboxOutlined } from '@ant-design/icons'
import { Alert, Button, Card, Empty, List, Progress, Select, Space, Tag, Typography, Upload } from 'antd'
import { useMutation } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import aiApi from '@/api/aiApi'
import { ApiError } from '@/api/client'
import { ErrorCode, PoseAction, toOptions } from '@/types'
import type { PoseEvaluation } from '@/types'

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024 // 与后端 multipart 上限一致

/** 评分对应的进度环颜色：<60 红 / 60-80 橙 / >80 绿 */
function scoreColor(score: number): string {
  if (score < 60) return '#ff4d4f'
  if (score <= 80) return '#faad14'
  return '#52c41a'
}

/**
 * Tab 3 — 姿态评估（真实多模态）
 *
 * <h3>图片为什么不直接上传原图给 Python</h3>
 * 前端只负责把原图（≤10MB）交给 **Java**；Java 会先压缩到 ≤1MB 再转 Base64 转发 Python。
 * 前端完全不知道 Python 与多模态模型的存在（Java 是 BFF）。
 *
 * <h3>错误语义</h3>
 * - 9003：照片本身不可用（过小/格式不支持/模型看不清）→ 直接展示后端给的具体原因，
 *   引导用户换一张，而不是说「服务不可用」
 * - 6002：多模态服务真的挂了 → 展示兜底文案
 */
export default function PoseTab() {
  const [file, setFile] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [action, setAction] = useState<PoseAction>(PoseAction.SQUAT)
  const [result, setResult] = useState<PoseEvaluation | null>(null)
  const [inputError, setInputError] = useState<string | null>(null)
  const [fakePercent, setFakePercent] = useState(0)
  const timerRef = useRef<number | null>(null)

  const mutation = useMutation({
    mutationFn: () => aiApi.evaluatePose(file as File, action),
    onMutate: () => {
      setResult(null)
      setInputError(null)
    },
    onSuccess: (data) => setResult(data),
    onError: (error) => {
      if (error instanceof ApiError && error.code === ErrorCode.PARAM_INVALID) {
        // 9003：后端已给出「请上传一张能看清全身的清晰照片」这类可执行提示
        setInputError(error.msg)
      } else {
        setInputError('姿态评估服务暂时不可用，请稍后再试')
      }
    },
  })

  // 多模态推理要几十秒，用一个「假进度」给用户即时反馈（到 90% 停住，等真实结果）
  useEffect(() => {
    if (mutation.isPending) {
      setFakePercent(0)
      timerRef.current = window.setInterval(() => {
        setFakePercent((p) => (p >= 90 ? 90 : p + 3))
      }, 400)
    } else {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current)
        timerRef.current = null
      }
      setFakePercent(0)
    }
    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }, [mutation.isPending])

  // 释放预览用的 object URL，避免内存泄漏
  useEffect(() => {
    return () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl)
    }
  }, [previewUrl])

  const handleSelect = (selected: File): void => {
    setResult(null)
    setInputError(null)
    setFile(selected)
    if (previewUrl) URL.revokeObjectURL(previewUrl)
    setPreviewUrl(URL.createObjectURL(selected))
  }

  const handleReset = (): void => {
    setFile(null)
    setResult(null)
    setInputError(null)
    if (previewUrl) URL.revokeObjectURL(previewUrl)
    setPreviewUrl(null)
  }

  return (
    <Card>
      <Space wrap className="mb-4" size="middle">
        <span className="text-sm text-gray-600">动作名称</span>
        <Select
          style={{ width: 140 }}
          options={toOptions(PoseAction)}
          value={action}
          onChange={setAction}
          disabled={mutation.isPending}
        />
        <Button
          type="primary"
          icon={<CameraOutlined />}
          disabled={!file || mutation.isPending}
          loading={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? 'AI正在分析你的动作姿态...' : '开始评估'}
        </Button>
        {file && !mutation.isPending && (
          <Button type="link" onClick={handleReset}>
            重新选择
          </Button>
        )}
      </Space>

      {!file && (
        <Upload.Dragger
          accept="image/jpeg,image/png"
          maxCount={1}
          showUploadList={false}
          beforeUpload={(f) => {
            if (!/^image\/(jpeg|png)$/.test(f.type)) {
              setInputError('仅支持 JPG / PNG 格式')
              return Upload.LIST_IGNORE
            }
            if (f.size > MAX_UPLOAD_BYTES) {
              setInputError('图片不能超过 10MB')
              return Upload.LIST_IGNORE
            }
            handleSelect(f)
            // 返回 false 阻止 antd 自动上传：真正的请求由「开始评估」触发
            return false
          }}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽上传动作图片</p>
          <p className="ant-upload-hint">支持 JPG / PNG，单张不超过 10MB；建议全身入镜</p>
        </Upload.Dragger>
      )}

      {file && previewUrl && (
        <div className="mb-4">
          <img
            src={previewUrl}
            alt="待评估的动作图片"
            className="max-h-64 rounded border border-gray-200"
          />
          <div className="mt-1 text-xs text-gray-500">
            {file.name} · {(file.size / 1024).toFixed(0)} KB
          </div>
        </div>
      )}

      {mutation.isPending && (
        <div>
          <Progress percent={fakePercent} status="active" />
          <Typography.Text type="secondary">
            多模态模型正在看图并给出评分与建议，通常需要 10-30 秒…
          </Typography.Text>
        </div>
      )}

      {!mutation.isPending && inputError && (
        <Alert
          type="warning"
          showIcon
          message="无法完成评估"
          description={inputError}
          action={
            file ? (
              <Button size="small" onClick={() => mutation.mutate()}>
                重试
              </Button>
            ) : undefined
          }
        />
      )}

      {!mutation.isPending && result && (
        <div>
          {/*
            来源标注：MOCK_MODE=true 时后端返回的是本地模拟打分（由图片哈希派生），
            与真实多模态推理的响应结构完全一致。不标注就等于把编造的数字
            当成真实评估结果展示给用户 —— 因此这里必须显眼说明。
          */}
          {result.dataSource === 'mock_local' && (
            <Alert
              type="warning"
              showIcon
              className="mb-3"
              message="模拟结果（非真实图像分析）"
              description="当前服务处于 MOCK_MODE：评分与问题清单由本地算法生成，照片并未发送给多模态模型。此结果仅用于演示链路，请勿作为训练依据。"
            />
          )}
          <Space align="start" size="large" wrap>
            <Progress
              type="circle"
              percent={result.score}
              strokeColor={scoreColor(result.score)}
              format={(p) => `${p}分`}
            />
            <div>
              <div className="mb-2 text-base">
                标准度评分{result.dataSource === 'mock_local' ? '（模拟）' : ''}：
                <Tag color={scoreColor(result.score)}>{result.scoreLevel}</Tag>
              </div>
              {result.goodPoints.length > 0 && (
                <div className="mb-2">
                  {result.goodPoints.map((g) => (
                    <div key={g} className="text-sm text-green-700">
                      ✅ {g}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </Space>

          <div className="mt-4 grid gap-4 md:grid-cols-2">
            <Card size="small" title={`发现的问题（${result.issues.length}）`}>
              {result.issues.length > 0 ? (
                <List
                  size="small"
                  dataSource={result.issues}
                  renderItem={(item) => <List.Item>⚠️ {item}</List.Item>}
                />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未发现问题" />
              )}
            </Card>
            <Card size="small" title={`纠正建议（${result.suggestions.length}）`}>
              {result.suggestions.length > 0 ? (
                <List
                  size="small"
                  dataSource={result.suggestions}
                  renderItem={(item) => <List.Item>💡 {item}</List.Item>}
                />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无建议" />
              )}
            </Card>
          </div>
        </div>
      )}

      {!mutation.isPending && !result && !inputError && file && (
        <Empty description="图片已就绪，点击「开始评估」" />
      )}
    </Card>
  )
}
