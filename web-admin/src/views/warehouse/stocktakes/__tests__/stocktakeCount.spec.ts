import { describe, expect, it } from 'vitest';
import {
  countDisplayUnit,
  countUncountedRows,
  fillSystemQuantities,
  fillSystemQuantity,
  nextCountInputIndex,
  type StocktakeCountRow,
} from '../stocktakeCount';

const row = (id: string, systemQty: number, actualQty: number | null, unit = 'box'): StocktakeCountRow => ({
  id,
  batchId: `uuid-${id}`,
  batchNo: `TRF-${id}`,
  materialCode: `RMT-${id}`,
  materialName: `物料${id}`,
  unit,
  systemQty,
  actualQty,
  notes: '',
});

describe('stocktake count UX contract', () => {
  it('fills seven blank rows from the system quantity without a persistence side effect', () => {
    const rows = [10, 10, 1.23, 0.12, 0.01, 70, 100].map((qty, index) => row(String(index), qty, null));
    const filled = fillSystemQuantities(rows);
    expect(filled.map((item) => item.actualQty)).toEqual([10, 10, 1.23, 0.12, 0.01, 70, 100]);
    expect(rows.every((item) => item.actualQty == null)).toBe(true);
    expect(countUncountedRows(filled)).toBe(0);
  });

  it('preserves a manually entered difference unless overwrite is explicitly selected', () => {
    const rows = [row('1', 10, 8), row('2', 5, null)];
    expect(fillSystemQuantities(rows).map((item) => item.actualQty)).toEqual([8, 5]);
    expect(fillSystemQuantities(rows, true).map((item) => item.actualQty)).toEqual([10, 5]);
    expect(fillSystemQuantity(rows[0]).actualQty).toBe(10);
  });

  it('keeps blanks as uncounted and provides deterministic keyboard progression', () => {
    expect(countUncountedRows([row('1', 1, 1), row('2', 2, null)])).toBe(1);
    expect(nextCountInputIndex(0, 2)).toBe(1);
    expect(nextCountInputIndex(1, 2)).toBeNull();
  });

  it.each([
    ['box', '盒'],
    ['case', '箱'],
    ['slice', '片'],
    ['kg', 'kg'],
    ['g', 'g'],
  ])('renders canonical %s as %s', (canonical, expected) => {
    expect(countDisplayUnit(canonical)).toBe(expected);
  });
});
