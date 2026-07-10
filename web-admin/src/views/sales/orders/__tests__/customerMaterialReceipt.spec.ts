import { describe, expect, it } from 'vitest';
import { buildCustomerMaterialReceiptPayload } from '../customerMaterialReceipt';

describe('buildCustomerMaterialReceiptPayload', () => {
  it('只提交客供料入库表单字段，来源单据由订单路径锁定', () => {
    const payload = buildCustomerMaterialReceiptPayload({
      materialTypeId: 'MAT-001',
      receiptDate: '2026-07-10',
      receiptQuantity: 12.5,
      quantityUnit: 'kg',
      totalWeight: 12.2,
      totalValue: 0,
      warehouseId: 'WH-RAW',
      notes: '客户随单带料',
    }, 'SO-001');

    expect(payload).toEqual({
      materialTypeId: 'MAT-001',
      receiptDate: '2026-07-10',
      receiptQuantity: 12.5,
      quantityUnit: 'kg',
      totalWeight: 12.2,
      totalValue: 0,
      warehouseId: 'WH-RAW',
      notes: '客户随单带料',
    });
    expect(payload).not.toHaveProperty('supplierId');
    expect(payload).not.toHaveProperty('sourceDocType');
    expect(payload).not.toHaveProperty('sourceDocId');
  });
});
