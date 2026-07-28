import { describe, expect, it, vi } from 'vitest';
import type { BomRecipeItemView, BomRecipeSummary } from '@/api/bom';
import { createBomDraftEnsurer, validateBomActivation } from '../bomDraftLifecycle';

function recipe(overrides: Partial<BomRecipeSummary> = {}): BomRecipeSummary {
  return {
    id: 'DRAFT-1',
    factoryId: 'F006',
    recipeCode: 'BOM-1',
    productTypeId: 'SKU-1',
    productName: '测试产品',
    version: 1,
    isCurrent: false,
    status: 'DRAFT',
    items: [],
    ...overrides,
  };
}

function item(overrides: Partial<BomRecipeItemView> = {}): BomRecipeItemView {
  return {
    id: 1,
    recipeId: 'DRAFT-1',
    factoryId: 'F006',
    materialTypeId: 'M-1',
    materialName: '包装袋',
    standardQuantity: 1,
    unit: '个',
    materialCategory: 'PACKAGING',
    ...overrides,
  };
}

describe('BOM draft lifecycle behavior', () => {
  it('starts a zero-version SKU by refreshing the returned empty v1 draft', async () => {
    const draft = recipe();
    const api = vi.fn().mockResolvedValue({ success: true, data: draft });
    const refresh = vi.fn().mockResolvedValue(undefined);

    await expect(createBomDraftEnsurer(api, refresh)('F006', 'SKU-1')).resolves.toBe(draft);
    expect(api).toHaveBeenCalledWith('F006', 'SKU-1', undefined);
    expect(refresh).toHaveBeenCalledWith(draft);
  });

  it('continues the exact draft returned by the idempotent endpoint', async () => {
    const existing = recipe({ id: 'EXISTING-DRAFT', version: 3 });
    const refresh = vi.fn().mockResolvedValue(undefined);
    const ensure = createBomDraftEnsurer(
      vi.fn().mockResolvedValue({ success: true, data: existing }),
      refresh,
    );

    expect((await ensure('F006', 'SKU-1')).id).toBe('EXISTING-DRAFT');
    expect(refresh).toHaveBeenCalledWith(existing);
  });

  it('uses the same endpoint for a new version and selects the returned v2 draft', async () => {
    const cloned = recipe({ id: 'V2-DRAFT', version: 2 });
    const api = vi.fn().mockResolvedValue({ success: true, data: cloned });
    const refresh = vi.fn().mockResolvedValue(undefined);

    const result = await createBomDraftEnsurer(api, refresh)('F006', 'SKU-1');
    expect(result.version).toBe(2);
    expect(result.status).toBe('DRAFT');
    expect(result.isCurrent).toBe(false);
  });

  it('deduplicates rapid double clicks including the refresh phase', async () => {
    let resolveApi: ((value: { success: true; data: BomRecipeSummary }) => void) | undefined;
    const api = vi.fn(() => new Promise<{ success: true; data: BomRecipeSummary }>((resolve) => {
      resolveApi = resolve;
    }));
    const refresh = vi.fn().mockResolvedValue(undefined);
    const ensure = createBomDraftEnsurer(api, refresh);

    const first = ensure('F006', 'SKU-1');
    const second = ensure('F006', 'SKU-1');
    expect(first).toBe(second);
    resolveApi?.({ success: true, data: recipe() });
    await Promise.all([first, second]);
    expect(api).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('isolates single-flight requests by exact Workflow revision', async () => {
    const api = vi.fn().mockResolvedValue({ success: true, data: recipe() });
    const refresh = vi.fn().mockResolvedValue(undefined);
    const ensure = createBomDraftEnsurer(api, refresh);

    await Promise.all([
      ensure('F006', 'SKU-1', 101),
      ensure('F006', 'SKU-1', 102),
    ]);

    expect(api).toHaveBeenNthCalledWith(1, 'F006', 'SKU-1', 101);
    expect(api).toHaveBeenNthCalledWith(2, 'F006', 'SKU-1', 102);
  });

  it('surfaces the backend business message and does not refresh on failure', async () => {
    const refresh = vi.fn().mockResolvedValue(undefined);
    const ensure = createBomDraftEnsurer(
      vi.fn().mockResolvedValue({ success: false, message: 'SKU 未配置标准克重' }),
      refresh,
    );

    await expect(ensure('F006', 'SKU-1')).rejects.toThrow('SKU 未配置标准克重');
    expect(refresh).not.toHaveBeenCalled();
  });

  it('blocks activation when the BOM has no details', () => {
    expect(validateBomActivation(recipe(), { unit: '袋', gramsPerUnit: 500 }))
      .toContain('至少添加一条');
  });

  it('blocks missing material links and packaging quantities with row guidance', () => {
    expect(validateBomActivation(recipe({ items: [item({ materialTypeId: '' })] }), {
      unit: '袋', gramsPerUnit: 500,
    })).toContain('尚未关联物料');
    expect(validateBomActivation(recipe({ items: [item({ standardQuantity: null })] }), {
      unit: '袋', gramsPerUnit: 500,
    })).toContain('包材数量');
  });

  it('blocks invalid SKU output metadata and accepts a complete draft', () => {
    const complete = recipe({ items: [item()] });
    expect(validateBomActivation(complete, { unit: '', gramsPerUnit: 500 })).toContain('基本单位');
    expect(validateBomActivation(complete, { unit: '袋', gramsPerUnit: 0 })).toContain('标准克重');
    expect(validateBomActivation(complete, { unit: '袋', gramsPerUnit: 500 })).toBeNull();
  });
});
