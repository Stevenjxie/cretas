import { describe, expect, it } from 'vitest';
import { buildSupplierPricePayload } from './quotePayload';

describe('buildSupplierPricePayload', () => {
  it('preserves an explicit zero tax rate', () => {
    expect(buildSupplierPricePayload({
      supplierId: 'supplier-1',
      unitPrice: 12.5,
      taxRate: 0,
      deliveryDays: 0,
      remark: '',
    })).toEqual({
      supplierId: 'supplier-1',
      unitPrice: 12.5,
      taxRate: 0,
      deliveryDays: undefined,
      remark: undefined,
    });
  });
});
