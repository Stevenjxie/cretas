import { describe, expect, it } from 'vitest';
import { finishedGoodPlanOptions } from '../productionPlanProductOptions';

describe('production plan finished-goods selector', () => {
  it('keeps only active FINISHED_PRODUCT records without guessing from names', () => {
    const options = finishedGoodPlanOptions([
      { id: 'fg', name: '半成品风味鸡', productCategory: 'FINISHED_PRODUCT', isActive: true },
      { id: 'semi', name: '成品字样的半成品', productCategory: 'SEMI_FINISHED', isActive: true },
      { id: 'raw', name: '最终原料', productCategory: 'RAW_MATERIAL', isActive: true },
      { id: 'inactive', name: '停用成品', productCategory: 'FINISHED_PRODUCT', isActive: false },
      { id: 'legacy', name: '无分类旧记录', isActive: true },
    ]);

    expect(options.map((option) => option.id)).toEqual(['fg']);
  });
});
