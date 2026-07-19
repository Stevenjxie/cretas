export type PricingLine = {
  quantity?: number | string | null;
  unit?: string | null;
  quantityUnit?: string | null;
  unitPrice?: number | string | null;
  priceUnit?: string | null;
  lineAmount?: number | string | null;
  convertedPricingQuantity?: number | string | null;
};

const UNIT_ALIASES: Record<string, string> = {
  kg: 'kg', '千克': 'kg', '公斤': 'kg',
  g: 'g', '克': 'g',
  box: 'box', '盒': 'box',
  case: 'case', '箱': 'case',
  slice: 'slice', '片': 'slice',
  bag: 'bag', '袋': 'bag',
  pcs: 'pcs', pc: 'pcs', piece: 'pcs', pieces: 'pcs', '个': 'pcs', '只': 'pcs', '件': 'pcs',
  l: 'L', '升': 'L',
  ml: 'mL', '毫升': 'mL',
};

const UNIT_LABELS: Record<string, string> = {
  kg: 'kg', g: 'g', box: '盒', case: '箱', slice: '片', bag: '袋', pcs: '件', L: 'L', mL: 'mL',
};

export function canonicalUnitCode(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  return UNIT_ALIASES[raw.toLowerCase()] || UNIT_ALIASES[raw] || raw;
}

export function displayUnit(value: unknown): string {
  const code = canonicalUnitCode(value);
  return UNIT_LABELS[code] || code;
}

export function formatPriceUnit(value: unknown): string {
  const code = canonicalUnitCode(value);
  return code ? `元/${displayUnit(code)}` : '计价单位未配置';
}

export function mergeCanonicalUnitOptions(...sources: Array<unknown | unknown[]>): string[] {
  const units = new Set<string>();
  sources.flatMap((source) => Array.isArray(source) ? source : [source]).forEach((source) => {
    const unit = canonicalUnitCode(source);
    if (unit) units.add(unit);
  });
  return Array.from(units);
}

export type PurchaseSuggestionPricing = {
  unit?: string | null;
  quantityUnit?: string | null;
  priceUnit?: string | null;
  referencePriceUnit?: string | null;
};

/**
 * 后端采购建议的权威字段是 referencePriceUnit。priceUnit 仅作为版本兼容别名，
 * 最后才回退到数量单位以兼容尚未升级的旧响应。
 */
export function resolvePurchaseSuggestionUnits(item: PurchaseSuggestionPricing) {
  const quantityUnit = canonicalUnitCode(item.quantityUnit || item.unit);
  const priceUnit = canonicalUnitCode(item.referencePriceUnit || item.priceUnit || item.unit);
  return { quantityUnit, priceUnit };
}

export function purchaseOrderPricingPayload<T extends PurchaseSuggestionPricing>(item: T) {
  const { quantityUnit, priceUnit } = resolvePurchaseSuggestionUnits(item);
  return {
    ...item,
    unit: quantityUnit,
    quantityUnit,
    priceUnit,
  };
}

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

export type PricingAmountPreview = {
  amount: number | null;
  source: 'backend-line-amount' | 'backend-converted-quantity' | 'same-unit' | 'pending';
  message: string;
};

/**
 * 金额只接受三种权威口径：后端 lineAmount、后端换算后的计价数量、或数量/计价单位完全相同。
 * 单位不同且没有后端换算结果时必须 fail closed，绝不猜 conversionFactor。
 */
export function pricingAmountPreview(line: PricingLine): PricingAmountPreview {
  const backendAmount = finiteNumber(line.lineAmount);
  if (backendAmount !== null) {
    return { amount: backendAmount, source: 'backend-line-amount', message: '' };
  }

  const unitPrice = finiteNumber(line.unitPrice);
  const convertedQuantity = finiteNumber(line.convertedPricingQuantity);
  if (unitPrice !== null && convertedQuantity !== null) {
    return {
      amount: convertedQuantity * unitPrice,
      source: 'backend-converted-quantity',
      message: '',
    };
  }

  const quantity = finiteNumber(line.quantity);
  const quantityUnit = canonicalUnitCode(line.quantityUnit || line.unit);
  const priceUnit = canonicalUnitCode(line.priceUnit);
  if (quantity !== null && unitPrice !== null && quantityUnit && quantityUnit === priceUnit) {
    return { amount: quantity * unitPrice, source: 'same-unit', message: '' };
  }

  return {
    amount: null,
    source: 'pending',
    message: '保存后由系统换算',
  };
}
