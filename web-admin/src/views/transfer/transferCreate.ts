import { canonicalUnitCode } from '@/utils/unitPricing';

export type TransferItemType = 'RAW_MATERIAL' | 'FINISHED_GOODS' | 'PACKAGING_MATERIAL';
export type TransferType = 'HQ_TO_BRANCH' | 'BRANCH_TO_BRANCH' | 'BRANCH_TO_HQ' | 'WAREHOUSE_TO_WAREHOUSE';

export const TRANSFER_TYPE_OPTIONS: ReadonlyArray<{ value: TransferType; label: string }> = [
  { value: 'HQ_TO_BRANCH', label: '总部调往分部' },
  { value: 'BRANCH_TO_BRANCH', label: '分部之间调拨' },
  { value: 'BRANCH_TO_HQ', label: '分部退回总部' },
  { value: 'WAREHOUSE_TO_WAREHOUSE', label: '同一工厂仓库调拨' },
];

export interface TransferCreateRow {
  itemType: TransferItemType;
  selectedItemId: string;
  materialTypeId?: string;
  productTypeId?: string;
  itemName: string;
  quantity?: number;
  unit: string;
  unitPrice?: number;
  remark?: string;
  _currentStock?: number | string | null;
}

export interface TransferSelectableItem {
  id: string;
  name: string;
  code?: string;
  unit: string;
  currentStock: number;
  unitPrice?: number;
  category?: string;
}

export interface FinishedGoodsInventoryBatch {
  productTypeId?: string | null;
  productName?: string | null;
  unit?: string | null;
  status?: string | null;
  availableQuantity?: number | string | null;
  producedQuantity?: number | string | null;
  shippedQuantity?: number | string | null;
  reservedQuantity?: number | string | null;
  unitPrice?: number | string | null;
}

export interface MaterialInventoryBatch {
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCode?: string | null;
  materialCategory?: string | null;
  quantityUnit?: string | null;
  unit?: string | null;
  currentQuantity?: number | string | null;
  status?: string | null;
  unitPrice?: number | string | null;
}

function finiteNumber(value: unknown): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function finishedGoodsAvailable(batch: FinishedGoodsInventoryBatch): number {
  if (batch.availableQuantity !== null && batch.availableQuantity !== undefined) {
    return finiteNumber(batch.availableQuantity);
  }
  return finiteNumber(batch.producedQuantity)
    - finiteNumber(batch.shippedQuantity)
    - finiteNumber(batch.reservedQuantity);
}

/**
 * 把分仓库存的成品批次聚合为调拨产品选项。库存仍按 canonical unit 计算，
 * UI 仅在渲染时调用 displayUnit，避免把中文展示值写回 payload/DB。
 */
export function aggregateFinishedGoodsOptions(
  batches: FinishedGoodsInventoryBatch[],
): TransferSelectableItem[] {
  const grouped = new Map<string, TransferSelectableItem>();
  for (const batch of batches) {
    const productTypeId = String(batch.productTypeId ?? '').trim();
    const unit = canonicalUnitCode(batch.unit);
    const available = finishedGoodsAvailable(batch);
    if (!productTypeId || !unit || available <= 0 || String(batch.status ?? '').toUpperCase() !== 'AVAILABLE') {
      continue;
    }
    const existing = grouped.get(productTypeId);
    if (existing) {
      // 同一 SKU 的库存必须同单位；异常混合单位不跨单位相加，避免虚大可调量。
      if (existing.unit === unit) existing.currentStock += available;
      continue;
    }
    const unitPrice = finiteNumber(batch.unitPrice);
    grouped.set(productTypeId, {
      id: productTypeId,
      name: String(batch.productName || productTypeId),
      code: productTypeId,
      unit,
      currentStock: available,
      unitPrice: unitPrice > 0 ? unitPrice : undefined,
    });
  }
  return Array.from(grouped.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
}

export function aggregateMaterialInventoryOptions(
  batches: MaterialInventoryBatch[],
): TransferSelectableItem[] {
  const grouped = new Map<string, TransferSelectableItem>();
  for (const batch of batches) {
    const materialTypeId = String(batch.materialTypeId ?? '').trim();
    const unit = canonicalUnitCode(batch.quantityUnit || batch.unit);
    const available = finiteNumber(batch.currentQuantity);
    const status = String(batch.status ?? '').toUpperCase();
    if (!materialTypeId || !unit || available <= 0 || ['USED_UP', 'DEPLETED', 'EXPIRED', 'SCRAPPED'].includes(status)) {
      continue;
    }
    const existing = grouped.get(materialTypeId);
    if (existing) {
      if (existing.unit === unit) existing.currentStock += available;
      continue;
    }
    const unitPrice = finiteNumber(batch.unitPrice);
    grouped.set(materialTypeId, {
      id: materialTypeId,
      name: String(batch.materialName || materialTypeId),
      code: String(batch.materialCode || ''),
      unit,
      currentStock: available,
      unitPrice: unitPrice > 0 ? unitPrice : undefined,
      category: String(batch.materialCategory || ''),
    });
  }
  return Array.from(grouped.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
}

export function optionsForItemType(
  itemType: TransferItemType,
  materialOptions: TransferSelectableItem[],
  finishedGoodsOptions: TransferSelectableItem[],
): TransferSelectableItem[] {
  if (itemType === 'FINISHED_GOODS') return finishedGoodsOptions;
  return materialOptions.filter((option) => {
    const category = String(option.category || '').toUpperCase();
    if (!category) return true; // 历史主数据无 category 时保留兼容。
    return itemType === 'PACKAGING_MATERIAL'
      ? category === 'PACKAGING' || category === '包材'
      : category !== 'PACKAGING' && category !== '包材';
  });
}

export function applySelectedOption(row: TransferCreateRow, option: TransferSelectableItem): void {
  row.selectedItemId = option.id;
  row.itemName = option.name;
  row.unit = canonicalUnitCode(option.unit);
  row.unitPrice = option.unitPrice;
  row._currentStock = option.currentStock;
  if (row.itemType === 'FINISHED_GOODS') {
    row.productTypeId = option.id;
    row.materialTypeId = undefined;
  } else {
    row.materialTypeId = option.id;
    row.productTypeId = undefined;
  }
}

export function resetSelectedOption(row: TransferCreateRow): void {
  row.selectedItemId = '';
  row.materialTypeId = undefined;
  row.productTypeId = undefined;
  row.itemName = '';
  row.quantity = undefined;
  row.unit = '';
  row._currentStock = null;
}

export function toTransferItemPayload(row: TransferCreateRow) {
  const identity = row.itemType === 'FINISHED_GOODS'
    ? { productTypeId: row.productTypeId || row.selectedItemId }
    : { materialTypeId: row.materialTypeId || row.selectedItemId };
  return {
    itemType: row.itemType,
    ...identity,
    itemName: row.itemName,
    quantity: row.quantity,
    unit: canonicalUnitCode(row.unit),
    unitPrice: row.unitPrice,
    remark: row.remark || undefined,
  };
}
