/**
 * Best-effort human-readable description of a Spring 6-field cron expression
 * (sec min hour day-of-month month day-of-week).
 *
 * Not a full parser — recognises common shapes and falls back to "自定义" when
 * the pattern is too complex. For "next run" preview we'd need a real parser
 * (cron-parser), which is not added in Phase 5 to keep the bundle lean.
 *
 * Spring cron fields: SEC MIN HOUR DOM MONTH DOW
 *   "0 0 9 * * ?"      → 每天 09:00
 *   "0 0 9 1 * ?"      → 每月 1 日 09:00
 *   "0 *\/30 * * * ?"   → 每 30 分钟
 *   "0 0 9 ? * MON"    → 每周一 09:00
 *   "0 0 * * * ?"      → 每小时整点
 */

const dowNames: Record<string, string> = {
  '0': '周日', '7': '周日', 'SUN': '周日',
  '1': '周一', 'MON': '周一',
  '2': '周二', 'TUE': '周二',
  '3': '周三', 'WED': '周三',
  '4': '周四', 'THU': '周四',
  '5': '周五', 'FRI': '周五',
  '6': '周六', 'SAT': '周六',
}

export function describeCron(expr: string | null | undefined): string {
  if (!expr) return ''
  const parts = expr.trim().split(/\s+/)
  if (parts.length < 6) return '格式不正确（需 6 字段）'
  // ignore optional 7th (year) field
  const [sec, min, hour, dom, mon, dow] = parts

  // Every N minutes: "0 *​/N * * * ?"
  const everyNMin = /^\*\/(\d+)$/.exec(min)
  if (sec === '0' && everyNMin && hour === '*' && dom === '*' && mon === '*' && (dow === '?' || dow === '*')) {
    return `每 ${everyNMin[1]} 分钟`
  }

  // Every N hours: "0 0 *​/N * * ?"
  const everyNHour = /^\*\/(\d+)$/.exec(hour)
  if (sec === '0' && min === '0' && everyNHour && dom === '*' && mon === '*' && (dow === '?' || dow === '*')) {
    return `每 ${everyNHour[1]} 小时`
  }

  // Hourly on the hour: "0 0 * * * ?"
  if (sec === '0' && min === '0' && hour === '*' && dom === '*' && mon === '*' && (dow === '?' || dow === '*')) {
    return '每小时整点'
  }

  // Weekday at HH:MM: "0 MIN HOUR ? * DOW"
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && (dom === '?' || dom === '*') && mon === '*' && dow !== '?' && dow !== '*') {
    const dows = dow.split(',').map(d => dowNames[d.toUpperCase()] || d).join('/')
    return `每${dows} ${pad(hour)}:${pad(min)}`
  }

  // Monthly on Nth at HH:MM: "0 MIN HOUR DOM * ?"
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && /^\d+$/.test(dom) && mon === '*' && (dow === '?' || dow === '*')) {
    return `每月 ${dom} 日 ${pad(hour)}:${pad(min)}`
  }

  // Daily at HH:MM:SS or HH:MM: "SEC MIN HOUR * * ?"
  if (/^\d+$/.test(sec) && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && (dow === '?' || dow === '*')) {
    return sec === '0'
      ? `每天 ${pad(hour)}:${pad(min)}`
      : `每天 ${pad(hour)}:${pad(min)}:${pad(sec)}`
  }

  // Yearly on M/D at HH:MM: "0 MIN HOUR DOM MON ?"
  if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && /^\d+$/.test(dom) && /^\d+$/.test(mon) && (dow === '?' || dow === '*')) {
    return `每年 ${mon} 月 ${dom} 日 ${pad(hour)}:${pad(min)}`
  }

  return '自定义表达式'
}

function pad(n: string | number): string {
  const s = String(n)
  return s.length < 2 ? '0' + s : s
}

/**
 * Common preset expressions for the create dialog quick-pick.
 */
export const CRON_PRESETS: Array<{ label: string; value: string }> = [
  { label: '每天 09:00',        value: '0 0 9 * * ?' },
  { label: '每小时整点',          value: '0 0 * * * ?' },
  { label: '每 30 分钟',          value: '0 */30 * * * ?' },
  { label: '每 15 分钟',          value: '0 */15 * * * ?' },
  { label: '每周一 09:00',       value: '0 0 9 ? * MON' },
  { label: '每月 1 日 09:00',    value: '0 0 9 1 * ?' },
  { label: '每月 1 日凌晨 02:00', value: '0 0 2 1 * ?' },
  { label: '每日凌晨 03:00',     value: '0 0 3 * * ?' },
]
