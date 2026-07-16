import type { TableRow } from '@/types/api'

type OrderEntityType = 'PURCHASE_ORDER' | 'SALES_ORDER' | string

function dateParts(date: Date, timeZone: string): { year: number; month: number; day: number } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const value = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((part) => part.type === type)?.value)
  return { year: value('year'), month: value('month'), day: value('day') }
}

function formatLocalDate(date: Date, timeZone: string, plusDays = 0): string {
  const { year, month, day } = dateParts(date, timeZone)
  const shifted = new Date(Date.UTC(year, month - 1, day + plusDays))
  return shifted.toISOString().slice(0, 10)
}

export function buildAiTemporalContext(now = new Date(), timeZone = 'Asia/Shanghai'): string {
  return `FACTORY_DATE_CONTEXT: timezone=${timeZone}; today=${formatLocalDate(now, timeZone)}; tomorrow=${formatLocalDate(now, timeZone, 1)}. Resolve relative dates only from this context.`
}

export function normalizeAiOrderDates(
  entityType: OrderEntityType,
  params: TableRow,
  userText: string,
  now = new Date(),
  timeZone = 'Asia/Shanghai',
): TableRow {
  const normalized = { ...params }
  const today = formatLocalDate(now, timeZone)
  const tomorrow = formatLocalDate(now, timeZone, 1)
  if (/(明日|明天|次日|tomorrow)/i.test(userText)) {
    normalized.orderDate = today
    if (entityType === 'PURCHASE_ORDER') normalized.expectedDeliveryDate = tomorrow
    if (entityType === 'SALES_ORDER') normalized.requiredDeliveryDate = tomorrow
  }
  return normalized
}
