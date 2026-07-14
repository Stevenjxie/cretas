import { describe, expect, it } from 'vitest';
import { buildEqualPotWeightsKg } from '../potAllocation';

describe('buildEqualPotWeightsKg', () => {
  it('equally splits kg input into backend kg pot weights', () => {
    expect(buildEqualPotWeightsKg(300, 'kg', 3)).toEqual([100, 100, 100]);
  });

  it('converts g input to kg before equally splitting', () => {
    expect(buildEqualPotWeightsKg(300_000, 'g', 3)).toEqual([100, 100, 100]);
  });

  it('converts mg input to kg before equally splitting', () => {
    expect(buildEqualPotWeightsKg(300_000_000, 'mg', 3)).toEqual([100, 100, 100]);
  });

  it('preserves the six-decimal kg total when division has a remainder', () => {
    const weights = buildEqualPotWeightsKg(10, 'kg', 3);

    expect(weights).toEqual([3.333334, 3.333333, 3.333333]);
    expect(weights.reduce((sum, weight) => sum + weight, 0)).toBe(10);
  });

  it('rejects a non-weight input unit instead of pretending it is kg', () => {
    expect(() => buildEqualPotWeightsKg(300, '盒', 3)).toThrow('不支持按锅等分');
  });
});
