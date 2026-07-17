export interface StocktakeCountItem {
  id: string;
  actualQty: number | null;
  notes: string;
}

export interface StocktakeItemUpdatePayload {
  itemId: string;
  actualQty: number;
  notes: string;
}

export function buildStocktakeItemUpdates(
  items: StocktakeCountItem[],
): StocktakeItemUpdatePayload[] {
  return items
    .filter((item): item is StocktakeCountItem & { actualQty: number } => item.actualQty != null)
    .map((item) => ({
      itemId: item.id,
      actualQty: item.actualQty,
      notes: item.notes,
    }));
}
