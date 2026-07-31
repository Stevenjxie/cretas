export type PricingLine = {
  quantity?: number | string | null;
  unit?: string | null;
  quantityUnit?: string | null;
  unitPrice?: number | string | null;
  priceUnit?: string | null;
  lineAmount?: number | string | null;
  convertedPricingQuantity?: number | string | null;
};

/**
 * ⛔ **不要为了让展示变中文而往这里加条目**。
 *
 * `canonicalUnitCode` 读的是这张表, 而它不只用于展示 —— 还参与**构造 API 请求体**
 * (如单位换算接口的 fromUnit/toUnit)。2026-07-31 曾往这里加 `'斤' -> 'jin'`, 结果
 * `SeasoningBindingDialog` 发给后端的 `fromUnit` 从「斤」变成了「jin」, 被既有测试当场拦下。
 *
 * 要让某个规范码显示成中文, 加到下面的 {@link UNIT_LABELS} 即可 —— 那张表是纯展示,
 * 且中文写法本来就会原样透传 (查不到 label 就返回自身)。
 */
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

/**
 * 规范码 → 展示文案。
 *
 * 🔴 规则 (Steve 2026-07-31 拍板):
 * - **可换算的国际单位** (kg/g/mg/L/mL/mm/cm/m/km) 保留英文码 —— 它们真的参与换算,
 *   用户也认这套写法。
 * - **计数 / 包装单位** (box/bag/pcs/case/slice/pack/bottle/can/crate/pail/roll/portion/item)
 *   **一律不得以英文码示人** —— 它们不参与任何换算, 码只是个内部标识, 对用户没有意义。
 *   `斤`/`吨` 同理: `jin`/`t` 对用户是天书。
 *
 * 缺一条就会漏出英文码 (`displayUnit` 查不到 label 时原样返回)。同目录
 * `__tests__/unitDisplayContract.spec.ts` 直接读后端权威表比对, 后端加了单位这里没加就会红。
 */
const UNIT_LABELS: Record<string, string> = {
  // 可换算国际单位 —— 保持英文
  mg: 'mg', g: 'g', kg: 'kg', L: 'L', mL: 'mL',
  mm: 'mm', cm: 'cm', m: 'm', km: 'km',
  // 中文计量单位 —— 英文码对用户无意义
  jin: '斤', t: '吨',
  // 计数 / 包装 —— 一律中文
  pcs: '只', portion: '份', slice: '片', item: '项',
  box: '盒', case: '箱', bag: '袋', pack: '包', bottle: '瓶',
  can: '罐', crate: '框', pail: '桶', roll: '卷',
};

export function canonicalUnitCode(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  const legacyComposite = raw.match(/^(pcs|kg|g):(只|公斤|克)$/i);
  if (legacyComposite) return UNIT_ALIASES[legacyComposite[1].toLowerCase()] || legacyComposite[1];
  return UNIT_ALIASES[raw.toLowerCase()] || UNIT_ALIASES[raw] || raw;
}

/**
 * 件/个/只 三个中文计数标签都归一到 `pcs`, 但它们**互相不能替换**
 * (见下面 {@link DISTINCT_COUNT_LABELS}: 一只鸡不是一件包材)。所以展示时
 * 必须把用户原本填的那个标签原样还回去 —— 走一遍 `pcs` 再取 `UNIT_LABELS`
 * 会把三个都渲染成同一个字。
 *
 * 🔴 #1672 (2026-07-23) 把 `UNIT_LABELS.pcs` 从「件」改成「只」以对齐系统单位表的
 * `unitName`, 于是**用户填「件」的产品在界面上显示成「只」**。
 * `__tests__/productSpecification.spec.ts` 当天就红了, 但 vitest 当时不在任何
 * push 门禁里 (`vue-build-check` 挂 `if: inputs.full_audit`), 一直没人看见。
 */
const RAW_COUNT_LABELS = new Set(['只', '个', '件']);

export function displayUnit(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (RAW_COUNT_LABELS.has(raw)) return raw;
  const code = canonicalUnitCode(value);
  return UNIT_LABELS[code] || code;
}

/**
 * #1976 例外名单 —— 与后端 `UnitContractServiceImpl.DISTINCT_COUNT_LABELS` 同源。
 * 权威表把 件/个/只 都并进 pcs, 但业务上**一只 ≠ 一件**(一只鸡不是一件包材)。
 * 「件」不在名单里: 它就是 pcs 的中文名, 件≡pcs 是对的。
 */
const DISTINCT_COUNT_LABELS = new Set(['只', '个']);

/**
 * 判两个单位<b>是不是同一个</b> —— 中英写法互认, 但不合并 只/个/件。
 *
 * 🔴 为什么不能直接 `a === b`: 库里同一个单位有中英两种写法 (后端保存写规范码, 人工录入常是中文),
 * 字面比较会把「袋」和 `bag` 判成两种单位。2026-07-31 客户就是这么被拦住的; 前端还另有几处
 * 因此显示「跨单位不可比, 需配产品标准克重」——**而那个提示会把人引去做无用功**, 因为两个
 * 单位本来就是同一个, 配了标准克重也没用。
 *
 * 与后端 `UnitContractServiceImpl.crossLanguageCode` 逐条对应, 改一边要改另一边。
 */
export function sameUnit(left: unknown, right: unknown): boolean {
  const norm = (value: unknown) => {
    const raw = String(value ?? '').trim();
    if (!raw) return '';
    return DISTINCT_COUNT_LABELS.has(raw) ? raw : canonicalUnitCode(raw);
  };
  const l = norm(left);
  const r = norm(right);
  return l !== '' && l === r;
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
