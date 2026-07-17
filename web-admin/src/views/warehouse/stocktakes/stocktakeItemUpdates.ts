export interface StocktakeCountItem {
  id: string;
  actualQty: number | null;
  notes: string;
}

export interface StocktakeItemUpdatePayload {
  id: string;
  actualQty: number;
  notes: string;
}

export function buildStocktakeItemUpdates(
  items: StocktakeCountItem[],
): StocktakeItemUpdatePayload[] {
  return items
    .filter((item): item is StocktakeCountItem & { actualQty: number } => item.actualQty != null)
    .map((item) => ({
      id: item.id,
      actualQty: item.actualQty,
      notes: item.notes,
    }));
}
