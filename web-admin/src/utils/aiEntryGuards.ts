import { canonicalUnitCode } from './unitPricing';

export type AiProductCandidate = {
  id?: unknown;
  name?: unknown;
  unit?: unknown;
  baseUnit?: unknown;
  gramsPerUnit?: unknown;
};

const MASS_UNITS = new Set(['g', 'kg', 'ton']);

export const NON_SKU_MATERIAL_CATEGORIES = new Set(['RAW_MATERIAL', 'PACKAGING', 'SEASONING']);

export function productAiGuard(params: Record<string, unknown>): string | null {
  const category = String(params.productCategory || '').trim();
  if (NON_SKU_MATERIAL_CATEGORIES.has(category)) {
    return '原料、辅料和包材不在 SKU 页面创建，请前往「原料类型字典」维护。';
  }
  return null;
}

export function findUniqueProductByName(
  name: unknown,
  products: readonly AiProductCandidate[],
): AiProductCandidate | null {
  const target = String(name ?? '').trim().toLocaleLowerCase();
  if (!target) return null;
  const matches = products.filter((product) => String(product.name ?? '').trim().toLocaleLowerCase() === target);
  return matches.length === 1 ? matches[0] : null;
}

export function productionPlanAiGuard(
  params: Record<string, unknown>,
  products: readonly AiProductCandidate[],
): string | null {
  const requestedUnit = canonicalUnitCode(params.quantityUnit || params.plannedUnit || params.unit);
  if (!requestedUnit) return 'AI 计划缺少数量单位，不能填入表单。请补充如 kg、g、盒或箱。';

  const product = findUniqueProductByName(params.productTypeName, products);
  if (!product) return '产品名称未唯一匹配现有 SKU，不能填入表单。请使用完整、准确的产品名称。';

  const skuUnit = canonicalUnitCode(product.unit || product.baseUnit);
  if (!skuUnit) return '所选 SKU 未配置单位，不能应用 AI 计划。请先完善 SKU 单位。';
  const massConvertible = MASS_UNITS.has(requestedUnit) && MASS_UNITS.has(skuUnit);
  const skuHasWeightBridge = Number(product.gramsPerUnit) > 0
    && (MASS_UNITS.has(requestedUnit) || MASS_UNITS.has(skuUnit));
  if (requestedUnit !== skuUnit && !massConvertible && !skuHasWeightBridge) {
    return `AI 数量单位为 ${requestedUnit}，但 SKU 单位为 ${skuUnit}。请按 SKU 单位重新描述数量。`;
  }
  return null;
}
