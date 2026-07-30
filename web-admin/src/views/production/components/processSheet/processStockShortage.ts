import { displayProcessUnit } from '@/utils/processSheetUnits';

export interface StockShortageLine {
  materialName: string;
  requiredText: string;
  availableText: string;
  shortageText: string;
}

export interface StockShortagePresentation {
  title: string;
  items: StockShortageLine[];
  action: string;
  rawMessage: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function readText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function amountText(value: unknown, unitValue: unknown): string | null {
  const amount = typeof value === 'number' || typeof value === 'string'
    ? String(value).trim()
    : '';
  const unit = readText(unitValue);
  if (!amount || !Number.isFinite(Number(amount))) return null;
  return unit ? `${amount} ${displayProcessUnit(unit)}` : amount;
}

function sourceLabel(sourceType: unknown): string {
  if (sourceType === 'PACKAGING') return '（包材）';
  if (sourceType === 'SEASONING') return '（调料）';
  return '';
}

function toPresentationLine(value: unknown): StockShortageLine | null {
  if (!isRecord(value)) return null;
  const materialName = readText(value.materialName) ?? readText(value.materialTypeId);
  const requiredText = amountText(value.required, value.unit);
  const availableText = amountText(value.available, value.unit);
  const shortageText = amountText(value.shortage, value.unit);
  if (!materialName || !requiredText || !availableText || !shortageText) return null;
  return {
    materialName: `${materialName}${sourceLabel(value.sourceType)}`,
    requiredText,
    availableText,
    shortageText,
  };
}

/**
 * The backend owns shortage quantities and material identity. The UI only localizes that DTO.
 * Keep raw-message-only rendering for older gateways that omit error data; remove that fallback
 * after every supported gateway preserves `ApiResponse.data` on non-2xx responses.
 */
export function presentStockShortage(
  message: string,
  payload?: unknown,
): StockShortagePresentation {
  const rawMessage = message.trim();
  const rawItems = isRecord(payload) && Array.isArray(payload.items) ? payload.items : [];
  const items = rawItems
    .map(toPresentationLine)
    .filter((item): item is StockShortageLine => item != null);

  return {
    title: '生产库投料不足，本行只能保存草稿',
    items,
    action: rawMessage.includes('联系仓管补料') ? '联系仓管补料' : '核对生产库库存后再正式报工',
    rawMessage,
  };
}
