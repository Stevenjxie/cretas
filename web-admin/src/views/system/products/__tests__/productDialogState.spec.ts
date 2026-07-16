import { describe, expect, it } from 'vitest';
import { isCurrentCategorySuggestion, reconcilePackagingSpecs } from '../productDialogState';

describe('product dialog category state', () => {
  it('clears packaging for semi-finished and restores exactly one default rule when switching back', () => {
    const initial = [{ name: '默认箱规' }];
    const semi = reconcilePackagingSpecs('SEMI_FINISHED', initial, () => ({ name: '默认箱规' }));
    const finished = reconcilePackagingSpecs('FINISHED_PRODUCT', semi, () => ({ name: '默认箱规' }));
    const semiAgain = reconcilePackagingSpecs('SEMI_FINISHED', finished, () => ({ name: '默认箱规' }));
    const finishedAgain = reconcilePackagingSpecs('FINISHED_PRODUCT', semiAgain, () => ({ name: '默认箱规' }));

    expect(semi).toEqual([]);
    expect(finished).toEqual([{ name: '默认箱规' }]);
    expect(finishedAgain).toEqual([{ name: '默认箱规' }]);
  });

  it('accepts only the response for the current name and product category', () => {
    expect(isCurrentCategorySuggestion('黄油鸡', 'FINISHED_PRODUCT', '黄油鸡', 'FINISHED_PRODUCT', 'FINISHED_PRODUCT')).toBe(true);
    expect(isCurrentCategorySuggestion('黄油鸡', 'FINISHED_PRODUCT', '黄油鸡', 'FINISHED_PRODUCT', 'SEMI_FINISHED')).toBe(false);
    expect(isCurrentCategorySuggestion('黄油鸡半成品', 'SEMI_FINISHED', '黄油鸡', 'FINISHED_PRODUCT', 'FINISHED_PRODUCT')).toBe(false);
  });
});
