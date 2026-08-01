import { describe, expect, it } from 'vitest';
import { summarizeQuantity } from '../quantitySummary';

describe('supply-chain quantity summary', () => {
  it('keeps decimal precision when every non-zero batch uses one unit', () => {
    const result = summarizeQuantity(
      [
        { quantity: 120, unit: 'kg' },
        { quantity: 7.5, unit: 'kg' },
      ],
      (row) => row.quantity,
    );

    expect(result).toEqual({ value: 127.5, unit: 'kg', reason: 'single-unit' });
  });

  it('refuses to add unlike units', () => {
    const result = summarizeQuantity(
      [
        { quantity: 10, unit: 'kg' },
        { quantity: 2, unit: 'box' },
      ],
      (row) => row.quantity,
    );

    expect(result).toEqual({ value: null, unit: null, reason: 'mixed-units' });
  });

  it('refuses to add a non-zero quantity whose unit is missing', () => {
    const result = summarizeQuantity(
      [
        { quantity: 10, unit: 'kg' },
        { quantity: 2, unit: null },
      ],
      (row) => row.quantity,
    );

    expect(result).toEqual({ value: null, unit: null, reason: 'missing-unit' });
  });

  it('keeps an empty result honest without inventing a unit', () => {
    const result = summarizeQuantity(
      [{ quantity: 0, unit: null }],
      (row) => row.quantity,
    );

    expect(result).toEqual({ value: 0, unit: null, reason: 'empty' });
  });
});
