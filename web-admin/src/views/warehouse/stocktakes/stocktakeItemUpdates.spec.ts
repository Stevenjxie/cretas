import { describe, expect, it } from 'vitest';
import { buildStocktakeItemUpdates } from './stocktakeItemUpdates';

describe('buildStocktakeItemUpdates', () => {
  it('keeps counted zero quantities and omits untouched rows', () => {
    expect(buildStocktakeItemUpdates([
      { id: 'counted', actualQty: 0, notes: '盘亏清零' },
      { id: 'untouched', actualQty: null, notes: '' },
      { id: 'positive', actualQty: 2.5, notes: '' },
    ])).toEqual([
      { id: 'counted', actualQty: 0, notes: '盘亏清零' },
      { id: 'positive', actualQty: 2.5, notes: '' },
    ]);
  });
});
