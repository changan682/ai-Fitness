import dayjs from 'dayjs'

interface Props {
  /** 要展示的日期，默认今天 */
  date?: string
}

/**
 * 日期头 —— 形如「2026年7月30日 星期四」
 * <p>
 * weekday 用 dayjs 的 `dddd`（已通过 `dayjs.locale('zh-cn')` 中文化），
 * 不自己写「星期" + ['日','一',...]」映射，避免与 DatePicker 的中文口径不一致。
 */
export default function DateHeader({ date }: Props) {
  const target = date ? dayjs(date) : dayjs()

  return (
    <div className="mb-4">
      <h2 className="m-0 text-lg font-semibold text-gray-800">
        {target.format('YYYY年M月D日')}
        <span className="ml-2 text-sm font-normal text-gray-500">{target.format('dddd')}</span>
      </h2>
    </div>
  )
}
