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
  const relativeTomorrow = /(明日|明天|次日|tomorrow)/i.test(userText)
  if (relativeTomorrow) {
    normalized.orderDate = today
    if (entityType === 'PURCHASE_ORDER') normalized.expectedDeliveryDate = tomorrow
    if (entityType === 'SALES_ORDER') normalized.requiredDeliveryDate = tomorrow
    return normalized
  }

  // An LLM can occasionally copy a stale training/example year into a new order.
  // Preserve dates explicitly stated by the user, but never silently prefill an
  // unrequested historical date for a normal new-order flow.
  const explicitDateInUserText = /\b20\d{2}(?:[-/.年]\d{1,2})?(?:[-/.月]\d{1,2}日?)?/i.test(userText)
  if (!explicitDateInUserText) {
    const normalizePastDate = (value: unknown): unknown =>
      typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value < today
        ? today
        : value

    normalized.orderDate = normalizePastDate(normalized.orderDate)
    if (entityType === 'PURCHASE_ORDER') {
      normalized.expectedDeliveryDate = normalizePastDate(normalized.expectedDeliveryDate)
    }
    if (entityType === 'SALES_ORDER') {
      normalized.requiredDeliveryDate = normalizePastDate(normalized.requiredDeliveryDate)
    }
  }
  return normalized
}
