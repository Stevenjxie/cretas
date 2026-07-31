import { describe, it, expect } from 'vitest';
import { BYPRODUCT_CATEGORY, isByproductCategory, BIG_CATEGORY_OPTIONS } from '../materialCategory';

describe('副产大类', () => {
  it('副产是独立大类, 不混进原料桶', () => {
    expect(BYPRODUCT_CATEGORY).toBe('副产');
    expect(BIG_CATEGORY_OPTIONS.map((o) => o.value)).toContain('副产');
  });

  it('isByproductCategory 只认副产, 认不出的一律 false (不猜)', () => {
    expect(isByproductCategory('副产')).toBe(true);
    expect(isByproductCategory(' 副产 ')).toBe(true);
    expect(isByproductCategory('原料')).toBe(false);
    expect(isByproductCategory('辅料')).toBe(false);
    expect(isByproductCategory(null)).toBe(false);
    expect(isByproductCategory(undefined)).toBe(false);
    expect(isByproductCategory('')).toBe(false);
    expect(isByproductCategory('副食')).toBe(false);
  });
});
