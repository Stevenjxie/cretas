import { describe, expect, it } from 'vitest';
import { productionPlanTypeLabel } from '../productionPlanType';

describe('productionPlanTypeLabel', () => {
  it('uses the authoritative source type for current plans', () => {
    expect(productionPlanTypeLabel({ sourceType: 'SAFETY_STOCK', sourceOrderId: 'legacy-noise' }))
      .toBe('库存生产');
    expect(productionPlanTypeLabel({ sourceType: 'CUSTOMER_ORDER' })).toBe('订单生产');
  });

  it('uses sales-order linkage only for legacy source types', () => {
    expect(productionPlanTypeLabel({ sourceType: 'MANUAL', sourceOrderId: 'SO-1' }))
      .toBe('订单生产');
    expect(productionPlanTypeLabel({ sourceType: 'EXCEL_IMPORT', sourceOrderIds: ['SO-1'] }))
      .toBe('订单生产');
    expect(productionPlanTypeLabel({ sourceType: 'MANUAL' })).toBe('库存生产');
  });
});
