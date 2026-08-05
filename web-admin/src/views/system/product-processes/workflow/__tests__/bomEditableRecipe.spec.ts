import { describe, expect, it } from 'vitest';
import { isWritableRecipe, pickEditableRecipe } from '../bomEditableRecipe';

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

  it('只有 DRAFT 可直接写入; 生效版必须先 ensureDraft', () => {
    expect(isWritableRecipe(draft)).toBe(true);
    expect(isWritableRecipe(active)).toBe(false);
    expect(isWritableRecipe(archived)).toBe(false);
    expect(isWritableRecipe(null)).toBe(false);
  });
});
