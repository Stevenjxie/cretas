import {
  validateQty,
  canSubmitRows,
  buildActualQuantities,
} from '../../../screens/warehouse/outbound/warehouseDeliveryConfirm.logic';

describe('warehouseDeliveryConfirm.logic (防呆 Rule 1: 实发不超计划)', () => {
  describe('validateQty', () => {
    it('accepts actual == planned', () => {
      expect(validateQty('100', 100)).toBeNull();
    });
    it('accepts actual < planned', () => {
      expect(validateQty('80', 100)).toBeNull();
    });
    it('rejects over-ship (actual > planned) — Rule 1', () => {
      expect(validateQty('130', 100)).toBe('不能超过计划 100');
    });
    it('rejects negative', () => {
      expect(validateQty('-5', 100)).toBe('不能为负');
    });
    it('rejects empty', () => {
      expect(validateQty('', 100)).toBe('不能为空');
    });
    it('rejects non-numeric', () => {
      expect(validateQty('abc', 100)).toBe('请输入数字');
    });
  });

  describe('canSubmitRows', () => {
    it('false when empty', () => {
      expect(canSubmitRows([])).toBe(false);
    });
    it('false when any row over-ships', () => {
      expect(
        canSubmitRows([
          { id: '1', plannedQty: 100, actualQtyText: '100' },
          { id: '2', plannedQty: 50, actualQtyText: '60' }, // over
        ]),
      ).toBe(false);
    });
    it('true when all rows valid', () => {
      expect(
        canSubmitRows([
          { id: '1', plannedQty: 100, actualQtyText: '100' },
          { id: '2', plannedQty: 50, actualQtyText: '40' },
        ]),
      ).toBe(true);
    });
  });

  describe('buildActualQuantities', () => {
    it('maps row id → numeric actual qty', () => {
      expect(
        buildActualQuantities([
          { id: '51', plannedQty: 100, actualQtyText: '80' },
          { id: '52', plannedQty: 50, actualQtyText: '50' },
        ]),
      ).toEqual({ '51': 80, '52': 50 });
    });
    it('skips rows without id', () => {
      expect(buildActualQuantities([{ id: '', plannedQty: 10, actualQtyText: '10' }])).toEqual({});
    });
  });
});
