import { describe, expect, it } from 'vitest';
import { toSupplierHistoryViewRow } from './supplierHistory';

describe('supplier history DTO mapping', () => {
  it('maps the aggregated backend contract without inventing an order number', () => {
    expect(toSupplierHistoryViewRow({
      materialTypeId: 'RMT-1',
      materialName: '黄油鸡',
      lastPurchaseDate: '2026-07-16',
      actuallyReceivedQuantity: 125.5,
      quantityUnit: 'kg',
      orderCount: 3,
    })).toEqual({
      materialTypeId: 'RMT-1',
      materialName: '黄油鸡',
      lastPurchaseDate: '2026-07-16',
      receivedQuantity: 125.5,
      quantityUnit: 'kg',
      purchaseCount: 3,
    });
  });

  it('keeps missing aggregate values honest', () => {
    expect(toSupplierHistoryViewRow({ materialName: '盐' })).toEqual({
      materialTypeId: '',
      materialName: '盐',
      lastPurchaseDate: '',
      receivedQuantity: null,
      quantityUnit: '',
      purchaseCount: 0,
    });
  });
});
