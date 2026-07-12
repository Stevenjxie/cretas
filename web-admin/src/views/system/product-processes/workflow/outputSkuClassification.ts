export type WorkflowOutputMaterialKind = 'SEMI_FINISHED' | 'FINISHED_GOOD';

const FINISHED_OUTPUT_CATEGORIES = new Set([
  'FINISHED_PRODUCT',
  'CONTRACT_MANUFACTURING',
  'CUSTOMER_MATERIAL',
  'DISH',
  'COMBO',
]);

export function classifyOutputSkuCategory(
  productCategory?: string | null,
): WorkflowOutputMaterialKind | null {
  if (productCategory === 'SEMI_FINISHED') return 'SEMI_FINISHED';
  if (productCategory && FINISHED_OUTPUT_CATEGORIES.has(productCategory)) return 'FINISHED_GOOD';
  return null;
}

/** 产出 SKU 候选的最小形状 (product-types/options 精简端点返回的字段子集)。 */
export interface OutputSkuCandidate {
  id: string;
  name: string;
  code?: string;
  unit?: string;
  specification?: string | null;
  productCategory?: string;
}

/** 去空白归一化 (中文不 lowercase, 与 normProcessName 同规则)。 */
function normName(s: string | undefined | null): string {
  return (s || '').replace(/\s+/g, '');
}

/**
 * #5 AI 建流程自动绑定产出 SKU: 在给定 kind 的候选池里按「去空白精确同名」查找。
 *
 * 防呆铁律: 只有**唯一**精确匹配才返回 —— 0 个匹配 (库里没有) 或 ≥2 个同名 (歧义) 都返回 null,
 * 让产出 Cell 保持"待选择/现场创建"由用户决定, 绝不乱绑一个可能错的 SKU。kind 必须一致
 * (成品产出只匹配成品 SKU, 半成品只匹配半成品), 避免把半成品误绑成成品。
 */
export function matchOutputSkuByName<T extends OutputSkuCandidate>(
  name: string | undefined | null,
  targetKind: WorkflowOutputMaterialKind,
  pool: readonly T[],
): T | null {
  const q = normName(name);
  if (!q) return null;
  const hits = pool.filter(
    (o) => classifyOutputSkuCategory(o.productCategory) === targetKind && normName(o.name) === q,
  );
  return hits.length === 1 ? hits[0] : null;
}
