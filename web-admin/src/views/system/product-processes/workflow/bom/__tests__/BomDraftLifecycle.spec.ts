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
    // 2026-08-13: ensureDraft 增加了第 4 个参数 dropObsoleteInputs。
    // 不传时为 undefined = 保持原行为。
    expect(api).toHaveBeenCalledWith('F006', 'SKU-1', undefined, undefined);
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

    expect(api).toHaveBeenNthCalledWith(1, 'F006', 'SKU-1', 101, undefined);
    expect(api).toHaveBeenNthCalledWith(2, 'F006', 'SKU-1', 102, undefined);
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

/**
 * 🔴 2026-08-13 生产实测: 「移除这 N 行并重试」点下去毫无作用, 那几行原封不动。
 * 原实现逐行 DELETE /bom-recipes/items/{id}, 而那条路结构上走不通:
 * 建草稿时草稿还没建成(行属于 ACTIVE → deleteItem 拒), 就算是 DRAFT
 * hasCompleteWorkflowIdentity 也拒 —— 而「绑着画布槽位」正是这些行被选中的判据。
 * 改成把「人已确认」回传给 ensure-draft, 由后端在同一事务里删它自己算出的孤儿行。
 */
describe('🔴 createBomDraftEnsurer —— dropObsoleteInputs 透传与去重', () => {
  it('把确认标志透传给 ensure-draft', async () => {
    const calls: unknown[][] = [];
    const api = ((...args: unknown[]) => {
      calls.push(args);
      return Promise.resolve({ success: true, data: { id: 'r1' } });
    }) as never;
    const ensure = createBomDraftEnsurer(api, async () => {});

    await ensure('F006', 'PT-1', 7, true);

    expect(calls[0]).toEqual(['F006', 'PT-1', 7, true]);
  });

  it('确认后的重试不被去重合并回刚失败的那次 —— 否则用户点了确认还是同一个 409', async () => {
    const seen: Array<boolean | undefined> = [];
    let resolveFirst: ((v: unknown) => void) | null = null;
    const api = ((_f: string, _p: string, _r: number | null, drop?: boolean) => {
      seen.push(drop);
      if (seen.length === 1) return new Promise((res) => { resolveFirst = res; });
      return Promise.resolve({ success: true, data: { id: 'r1' } });
    }) as never;
    const ensure = createBomDraftEnsurer(api, async () => {});

    const first = ensure('F006', 'PT-1', 7, false);   // 还在飞
    const retry = ensure('F006', 'PT-1', 7, true);    // 确认后重试

    await retry;
    expect(seen).toEqual([false, true]);              // 真的发了两次, 第二次带 true

    resolveFirst?.({ success: true, data: { id: 'r1' } });
    await first;
  });

  it('同参数仍然去重 —— 没有把防连点的原有行为改坏', async () => {
    let n = 0;
    const api = (() => { n += 1; return new Promise((res) => setTimeout(() => res({ success: true, data: { id: 'r1' } }), 5)); }) as never;
    const ensure = createBomDraftEnsurer(api, async () => {});

    await Promise.all([ensure('F006', 'PT-1', 7, true), ensure('F006', 'PT-1', 7, true)]);

    expect(n).toBe(1);
  });
});
