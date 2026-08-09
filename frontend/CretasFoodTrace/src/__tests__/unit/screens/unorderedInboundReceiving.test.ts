import {
  buildReceiptPayload,
  createReceiptIdempotencyKey,
  parseReceiptQuantity,
} from '../../../screens/warehouse/inbound/unorderedInboundReceiving';

let mockUuidSequence = 0;
jest.mock('uuid', () => ({
  v4: () => `00000000-0000-4000-8000-${String(++mockUuidSequence).padStart(12, '0')}`,
}));

const validDraft = {
  noticeId: 'notice-1',
  materialTypeId: 'material-1',
  warehouseId: 'warehouse-1',
  quantityText: '12.34',
  unit: 'kg',
  externalBatchNumber: '  CUSTOMER-001  ',
  notes: '  第一车  ',
  completeNotice: false,
};

describe('unordered inbound receipt safeguards', () => {
  it.each(['0', '0.001', '1.234', '123456789', '-1', 'abc', ''])('rejects invalid quantity %s', (value) => {
    expect(() => parseReceiptQuantity(value)).toThrow();
  });

  it.each([
    ['0.01', 0.01],
    ['1', 1],
    ['99999999.99', 99999999.99],
  ])('accepts backend-compatible quantity %s', (value, expected) => {
    expect(parseReceiptQuantity(value)).toBe(expected);
  });

  it('builds a partial receipt and trims optional facts', () => {
    expect(buildReceiptPayload(validDraft, 'attempt-1')).toEqual({
      idempotencyKey: 'attempt-1',
      materialTypeId: 'material-1',
      warehouseId: 'warehouse-1',
      receivedQuantity: 12.34,
      unit: 'kg',
      externalBatchNumber: 'CUSTOMER-001',
      notes: '第一车',
      completeNotice: false,
    });
  });

  it('requires material, warehouse and master-data unit selections', () => {
    expect(() => buildReceiptPayload({ ...validDraft, materialTypeId: '' }, 'attempt')).toThrow('请选择实际原料');
    expect(() => buildReceiptPayload({ ...validDraft, warehouseId: '' }, 'attempt')).toThrow('请选择入库仓库');
    expect(() => buildReceiptPayload({ ...validDraft, unit: '' }, 'attempt')).toThrow('缺少计量单位');
  });

  it('creates bounded, notice-scoped idempotency keys', () => {
    const first = createReceiptIdempotencyKey('notice-1234567890');
    const second = createReceiptIdempotencyKey('notice-1234567890');
    expect(first).toMatch(/^rn-notice-12345-/);
    expect(first.length).toBeLessThanOrEqual(64);
    expect(second).not.toBe(first);
  });
});
