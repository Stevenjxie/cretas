/**
 * 副产抵扣的**展示**格式化。
 *
 * 🔴 只做格式化, 不算钱 —— 金额由后端 ByproductCreditService.creditOf 算好返回 (Task 3)。
 * 本仓 2026-07-31 一天连修五处「同一件事多套实现」, 前端再算一遍就是第六处; 两边一旦漂开,
 * 用户看到的抵扣额与成本表里真正扣掉的会对不上, 而这种偏差不会报错。
 *
 * 🔴 null 与 0 必须分得开:
 *   - null = 还没人确认过 → 「未抵扣」
 *   - 0    = 有人确认了「这批不值钱」→ 「0.00」
 * 把 null 显示成 0.00 是禁降级里说的臆造默认值 —— 会让「漏确认」看起来像「已确认为 0」,
 * 与既有 collectionStatus /「未归集」同一套诚实语义。
 */

/** 抵扣额展示。null / undefined / 非有限数一律「未抵扣」, 绝不退化成 0。 */
export function formatCredit(credit: number | null | undefined): string {
  if (credit == null || !Number.isFinite(credit)) return '未抵扣';
  return credit.toFixed(2);
}

/** 单价展示。与抵扣额同一套诚实语义, 但单价按本仓口径保留 4 位。 */
export function formatCreditUnitPrice(unitPrice: number | null | undefined): string {
  if (unitPrice == null || !Number.isFinite(unitPrice)) return '未确认';
  return unitPrice.toFixed(4);
}

/**
 * 是否已确认单价。
 *
 * 必须**同时**有单价和确认时间 —— 有价但没有确认时间那是 BOM/SKU 带过来的参考价,
 * 还没有人拍板。判成 CONFIRMED 会让「系统猜的价」冒充「人确认的价」, 直接影响抵扣后成本。
 * 反过来, 单价为 0 是真实的确认结果, 不能因为 0 是 falsy 就被当成没填。
 */
export function creditStatus(
  unitPrice: number | null | undefined,
  confirmedAt: string | null | undefined,
): 'CONFIRMED' | 'PENDING' {
  const priced = unitPrice != null && Number.isFinite(unitPrice);
  const confirmed = confirmedAt != null && String(confirmedAt).trim() !== '';
  return priced && confirmed ? 'CONFIRMED' : 'PENDING';
}
