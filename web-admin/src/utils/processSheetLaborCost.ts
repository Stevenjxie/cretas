export const DEFAULT_PROCESS_SHEET_LABOR_RATE = 26;

export interface LaborPerBoxInput {
  hourlyRate?: number | null;
  totalHours?: number | null;
  boxWeightGrams?: number | null;
  allocationWeightKg?: number | null;
  actualBoxes?: number | null;
}

const round4 = (value: number) => Math.round(value * 10000) / 10000;

const positive = (value?: number | null): number | null => (
  value != null && Number.isFinite(value) && value > 0 ? value : null
);

export function calculateLaborPerBox(input: LaborPerBoxInput): number | null {
  const hours = positive(input.totalHours);
  if (hours == null) return null;

  const rate = positive(input.hourlyRate) ?? DEFAULT_PROCESS_SHEET_LABOR_RATE;
  const boxes = positive(input.actualBoxes);
  const allocationKg = positive(input.allocationWeightKg);
  const boxKg = positive(input.boxWeightGrams) != null
    ? positive(input.boxWeightGrams)! / 1000
    : allocationKg != null && boxes != null
      ? allocationKg / boxes
      : null;

  if (allocationKg != null && boxKg != null) {
    return round4((hours * rate * boxKg) / allocationKg);
  }

  if (boxes != null) {
    return round4((hours * rate) / boxes);
  }

  return null;
}
