import { describe, expect, it } from 'vitest';
import { calculateLaborPerBox } from '../processSheetLaborCost';

describe('calculateLaborPerBox', () => {
  it('uses allocated step weight and box weight, matching the customer Excel step formula', () => {
    expect(calculateLaborPerBox({
      hourlyRate: 26,
      totalHours: 24,
      boxWeightGrams: 100,
      allocationWeightKg: 307,
      actualBoxes: 703,
    })).toBe(0.2033);
  });

  it('falls back to the system default hourly rate when the row has no manual rate', () => {
    expect(calculateLaborPerBox({
      hourlyRate: null,
      totalHours: 3,
      boxWeightGrams: 100,
      allocationWeightKg: 10,
    })).toBe(0.78);
  });

  it('keeps the old per-box fallback when no kg allocation is available', () => {
    expect(calculateLaborPerBox({
      hourlyRate: 26,
      totalHours: 24,
      actualBoxes: 703,
    })).toBe(0.8876);
  });
});
