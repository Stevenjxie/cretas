export interface ShortageRequestGroupInput {
  batchId: number;
  productTypeId: string | null | undefined;
  plannedQuantity: number | null | undefined;
}

export interface ShortageRequestGroup {
  key: string;
  productTypeId: string;
  quantity: number;
  batchIds: number[];
}

export function normalizeShortageQuantity(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 1;
}

export function groupShortageRequests(entries: ShortageRequestGroupInput[]): ShortageRequestGroup[] {
  const groups = new Map<string, ShortageRequestGroup>();

  for (const entry of entries) {
    const productTypeId = entry.productTypeId;
    if (!productTypeId) continue;

    const quantity = normalizeShortageQuantity(entry.plannedQuantity);
    const key = `${productTypeId}|${quantity}`;
    const existing = groups.get(key);
    if (existing) {
      existing.batchIds.push(entry.batchId);
      continue;
    }

    groups.set(key, {
      key,
      productTypeId,
      quantity,
      batchIds: [entry.batchId],
    });
  }

  return Array.from(groups.values());
}
