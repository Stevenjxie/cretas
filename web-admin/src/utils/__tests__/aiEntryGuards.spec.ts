import { describe, expect, it } from 'vitest';
import { productAiGuard, productionPlanAiGuard } from '../aiEntryGuards';

describe('AI entry guards', () => {
  it('routes raw materials away from the SKU page', () => {
    expect(productAiGuard({ productCategory: 'RAW_MATERIAL' })).toContain('原料类型字典');
    expect(productAiGuard({ productCategory: 'SEMI_FINISHED' })).toBeNull();
  });

  it('requires quantity plus a unit matching the unique SKU', () => {
    const products = [{ id: 'PT-1', name: '干式熟成脆皮鸡 400g', unit: '袋' }];
    expect(productionPlanAiGuard({ productTypeName: '干式熟成脆皮鸡 400g', plannedQuantity: 500 }, products)).toContain('缺少数量单位');
    expect(productionPlanAiGuard({ productTypeName: '干式熟成脆皮鸡 400g', plannedQuantity: 500, quantityUnit: 'kg' }, products)).toContain('SKU 单位为 bag');
    expect(productionPlanAiGuard({ productTypeName: '干式熟成脆皮鸡 400g', plannedQuantity: 500, quantityUnit: 'bag' }, products)).toBeNull();
  });

  it('allows authoritative physical conversions instead of forcing exact unit text', () => {
    expect(productionPlanAiGuard(
      { productTypeName: '盐水', plannedQuantity: 500, quantityUnit: 'g' },
      [{ id: 'PT-2', name: '盐水', unit: 'kg' }],
    )).toBeNull();
    expect(productionPlanAiGuard(
      { productTypeName: '干式熟成脆皮鸡 400g', plannedQuantity: 10, quantityUnit: 'kg' },
      [{ id: 'PT-3', name: '干式熟成脆皮鸡 400g', unit: '袋', gramsPerUnit: 400 }],
    )).toBeNull();
  });
});
