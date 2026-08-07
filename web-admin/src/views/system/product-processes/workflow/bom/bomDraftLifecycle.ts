import type { BomRecipeSummary } from '@/api/bom';

export interface BomApiResponse<T> {
  success: boolean;
  data?: T | null;
  message?: string;
}

export type EnsureDraftApi = (
  factoryId: string,
  productTypeId: string,
  workflowRevisionId?: number | null,
) => Promise<BomApiResponse<BomRecipeSummary>>;

/**
 * Deduplicates rapid clicks for the same SKU. The pending entry includes refresh,
 * so every caller observes the same selected and fully refreshed draft.
 */
export function createBomDraftEnsurer(
  ensureDraftApi: EnsureDraftApi,
  refresh: (draft: BomRecipeSummary) => Promise<void>,
) {
  const pending = new Map<string, Promise<BomRecipeSummary>>();

  return (
    factoryId: string,
    productTypeId: string,
    workflowRevisionId?: number | null,
  ): Promise<BomRecipeSummary> => {
    const key = `${factoryId}:${productTypeId}:${workflowRevisionId ?? 'auto'}`;
    const existing = pending.get(key);
    if (existing) return existing;

    const request = (async () => {
      const response = await ensureDraftApi(factoryId, productTypeId, workflowRevisionId);
      if (!response.success || !response.data) {
        throw new Error(response.message || '无法创建或加载 BOM 草稿');
      }
      await refresh(response.data);
      return response.data;
    })().finally(() => pending.delete(key));

    pending.set(key, request);
    return request;
  };
}

export interface BomSkuActivationMeta {
  unit?: string | null;
  gramsPerUnit?: number | null;
}

/** Fast UI feedback; backend performs the authoritative activation validation. */
export function validateBomActivation(
  recipe: BomRecipeSummary,
  sku: BomSkuActivationMeta,
): string | null {
  if (!sku.unit?.trim()) return 'SKU 缺少基本单位，请先到产品档案补录后再激活。';
  if (sku.gramsPerUnit == null || !Number.isFinite(Number(sku.gramsPerUnit)) || Number(sku.gramsPerUnit) <= 0) {
    return 'SKU 缺少有效标准克重，请先到产品档案填写大于 0 的克重。';
  }

  const items = recipe.items ?? [];
  if (items.length === 0) return 'BOM 还是空的，请至少添加一条原料、辅料或包材明细。';

  for (const [index, item] of items.entries()) {
    const name = item.materialName?.trim() || `第 ${index + 1} 行`;
    if (!item.materialTypeId?.trim()) return `${name} 尚未关联物料，请重新选择物料。`;
    const rawMaterial = !item.materialCategory || item.materialCategory.toUpperCase() === 'RAW';
    const hasInvalidQuantity = item.standardQuantity != null
      && (!Number.isFinite(Number(item.standardQuantity)) || Number(item.standardQuantity) <= 0);
    if ((!rawMaterial && item.standardQuantity == null) || hasInvalidQuantity) {
      return `${name} 缺少有效标准用量；包材数量也必须大于 0。`;
    }
    if (!item.unit?.trim()) return `${name} 缺少计量单位，请补充后再激活。`;
  }
  return null;
}
