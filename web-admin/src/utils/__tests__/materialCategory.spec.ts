import { describe, it, expect } from 'vitest';
import {
  BIG_CATEGORY_OPTIONS,
  bigCategoryOf,
  filterOptionsByBigCategory,
} from '../materialCategory';

/**
 * 「副产」曾经是这里的第 5 个大类 (`category='副产'`)。2026-07-31 走前端验收时推翻：
 * 那样副产 SKU 在 BOM「原料」页签里选不到，而「副产以后能当原料被别的 workflow 投入」
 * 正是把副产放进原料字典的初衷。现在副产是物料上的**标记**，判定见
 * `utils/byproductMaterial.ts`，本文件只管**材质**分类。
 */
describe('物料大类归类 (材质, 不含副产)', () => {
  it('大类选择器只有 4 类 + 其他, 没有「副产」', () => {
    const values = BIG_CATEGORY_OPTIONS.map((o) => o.value);
    expect(values).not.toContain('副产');
    expect(values).toEqual(['', '原料', '辅料', '调料', '包材', '其他']);
  });

  /**
   * 🔴 副产 SKU 的 category 是它的**材质**(如「原料」)，所以它落在原料桶里 ——
   * 这正是它能被当原料投入的前提。旧实现把它归进「副产」桶，等于把这条路堵死。
   */
  it('副产 SKU 按材质归类, 因此仍落在原料桶', () => {
    expect(bigCategoryOf('原料')).toBe('原料');
    expect(filterOptionsByBigCategory(
      [{ category: '原料', isByproduct: true }, { category: '包材' }],
      '原料',
    )).toEqual([{ category: '原料', isByproduct: true }]);
  });

  it('「副产」不再是一个大类值, 落进「其他」而不是自成一桶', () => {
    expect(bigCategoryOf('副产')).toBe('其他');
  });

  it('既有 4 类的归类不变', () => {
    expect(bigCategoryOf('主材')).toBe('原料');
    expect(bigCategoryOf('肉类')).toBe('原料');
    expect(bigCategoryOf('辅料')).toBe('辅料');
    expect(bigCategoryOf('添加剂')).toBe('辅料');
    expect(bigCategoryOf('调味料')).toBe('调料');
    expect(bigCategoryOf('包材')).toBe('包材');
    expect(bigCategoryOf('')).toBe('其他');
    expect(bigCategoryOf(null)).toBe('其他');
  });
});
