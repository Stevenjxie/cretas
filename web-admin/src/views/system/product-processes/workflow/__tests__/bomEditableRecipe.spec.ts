import { describe, expect, it } from 'vitest';
import {
  buildDraftBomNotice,
  isWritableRecipe,
  pickEditableRecipe,
  pickOwningProductId,
} from '../bomEditableRecipe';

/**
 * 这些用例钉的是 2026-08-05 prod 实测到的死锁:
 * 画布走 /current 拿到 ACTIVE, 而调料/包材写入只接受 DRAFT, 于是永远 409。
 */
describe('画布解析可编辑 BOM 版本', () => {
  const archived = { id: 'r1', version: 1, status: 'ARCHIVED', isCurrent: false };
  const active = { id: 'r2', version: 2, status: 'ACTIVE', isCurrent: true };
  const draft = { id: 'r3', version: 3, status: 'DRAFT', isCurrent: false };

  it('有草稿时选草稿, 而不是生效版 —— 这正是 409 的根因', () => {
    expect(pickEditableRecipe([archived, active, draft])?.id).toBe('r3');
  });

  it('没有草稿时退回生效版', () => {
    expect(pickEditableRecipe([archived, active])?.id).toBe('r2');
  });

  it('永不选归档版', () => {
    expect(pickEditableRecipe([archived])).toBeNull();
  });

  it('多个草稿取版本号最大的', () => {
    const older = { id: 'd1', version: 3, status: 'DRAFT', isCurrent: false };
    const newer = { id: 'd2', version: 4, status: 'DRAFT', isCurrent: false };
    expect(pickEditableRecipe([newer, older])?.id).toBe('d2');
    expect(pickEditableRecipe([older, newer])?.id).toBe('d2');
  });

  it('is_current 缺失时仍能认出 ACTIVE', () => {
    expect(pickEditableRecipe([{ id: 'r9', version: 1, status: 'ACTIVE' }])?.id).toBe('r9');
  });

  it('状态大小写不敏感', () => {
    expect(pickEditableRecipe([{ id: 'r8', version: 1, status: 'draft' }])?.id).toBe('r8');
  });

  it('空输入返回 null 而不是抛错', () => {
    expect(pickEditableRecipe([])).toBeNull();
    expect(pickEditableRecipe(null)).toBeNull();
    expect(pickEditableRecipe(undefined)).toBeNull();
  });

  describe('草稿未生效必须说出来', () => {
    it('解析到草稿时给出提示, 并带上产线此刻在用的版本号', () => {
      const notice = buildDraftBomNotice('p1', '干式熟成鸡 400g', [archived, active, draft]);
      expect(notice).toEqual({
        productTypeId: 'p1',
        productName: '干式熟成鸡 400g',
        recipeId: 'r3',
        draftVersion: 3,
        activeVersion: 2,
      });
    });

    it('没有草稿时不提示 —— 画布显示的就是生产口径', () => {
      expect(buildDraftBomNotice('p1', 'x', [archived, active])).toBeNull();
    });

    it('全新产品的首版草稿也要提示, 此时产线没有任何生效版本', () => {
      const notice = buildDraftBomNotice('p2', 'y', [{ id: 'd0', version: 1, status: 'DRAFT' }]);
      expect(notice?.draftVersion).toBe(1);
      expect(notice?.activeVersion).toBeNull();
    });

    it('无版本时不提示而不是抛错', () => {
      expect(buildDraftBomNotice('p3', 'z', [])).toBeNull();
      expect(buildDraftBomNotice('p3', 'z', null)).toBeNull();
    });
  });

  describe('冷启动: 一条 BOM 版本都没有时, 靠图上的终端产出定归属', () => {
    it('唯一产出时能定归属', () => {
      expect(pickOwningProductId([{ skuId: 'sku-1' }])).toBe('sku-1');
    });

    it('同一产出重复出现仍算唯一', () => {
      expect(pickOwningProductId([{ skuId: 'sku-1' }, { skuId: 'sku-1' }])).toBe('sku-1');
    });

    it('联合生产(多产出)不猜 —— 记到哪份配方是业务决策', () => {
      expect(pickOwningProductId([{ skuId: 'sku-1' }, { skuId: 'sku-2' }])).toBeNull();
    });

    it('产出未绑定 SKU 时不算数', () => {
      expect(pickOwningProductId([{ skuId: null }, { skuId: '' }])).toBeNull();
      expect(pickOwningProductId([{ skuId: null }, { skuId: 'sku-9' }])).toBe('sku-9');
    });

    it('空输入返回 null 而不是抛错', () => {
      expect(pickOwningProductId([])).toBeNull();
      expect(pickOwningProductId(undefined)).toBeNull();
    });
  });

  it('只有 DRAFT 可直接写入; 生效版必须先 ensureDraft', () => {
    expect(isWritableRecipe(draft)).toBe(true);
    expect(isWritableRecipe(active)).toBe(false);
    expect(isWritableRecipe(archived)).toBe(false);
    expect(isWritableRecipe(null)).toBe(false);
  });
});
