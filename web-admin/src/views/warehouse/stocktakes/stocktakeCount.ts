import { displayUnit } from '@/utils/unitPricing';

export interface StocktakeCountRow {
  id: string;
  batchId: string;
  batchNo: string;
  materialCode: string;
  materialName: string;
  unit: string;
  systemQty: number;
  actualQty: number | null;
  notes: string;
}

export function countDisplayUnit(unit: string | null | undefined): string {
  return displayUnit(unit || '') || '-';
}

export function countUncountedRows(rows: StocktakeCountRow[]): number {
  return rows.filter((row) => row.actualQty == null).length;
}

/** Fill only uncounted rows by default. Explicit overwrite is a separate user decision. */
export function fillSystemQuantities(
  rows: StocktakeCountRow[],
  overwrite = false,
): StocktakeCountRow[] {
  return rows.map((row) => (
    overwrite || row.actualQty == null
      ? { ...row, actualQty: row.systemQty }
      : row
  ));
}

export function fillSystemQuantity(row: StocktakeCountRow): StocktakeCountRow {
  return { ...row, actualQty: row.systemQty };
}

export function nextCountInputIndex(currentIndex: number, rowCount: number): number | null {
  return currentIndex >= 0 && currentIndex + 1 < rowCount ? currentIndex + 1 : null;
}
