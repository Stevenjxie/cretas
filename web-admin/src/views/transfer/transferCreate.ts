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
  materialPackagingSpecId?: string;
  packagingOptions?: Array<{
    id: string;
    name: string;
    packageUnit: string;
    baseUnit: string;
    conversionFactor: number;
    defaultSpec?: boolean;
  }>;
  _inventoryUnit?: string;
  _packageFactor?: number;
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
  row._inventoryUnit = canonicalUnitCode(option.unit);
  row._packageFactor = 1;
  row.materialPackagingSpecId = undefined;
  row.packagingOptions = [];
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
  row._inventoryUnit = '';
  row._packageFactor = 1;
  row.materialPackagingSpecId = undefined;
  row.packagingOptions = [];
  row._currentStock = null;
}

/**
 * 一行的物料身份 —— 原料/包材按 materialTypeId, 成品按 productTypeId。未选物料返回 null。
 */
export function transferRowIdentity(row: TransferCreateRow): string | null {
  const finishedGoods = row.itemType === 'FINISHED_GOODS';
  const id = String((finishedGoods ? row.productTypeId : row.materialTypeId) || row.selectedItemId || '').trim();
  if (!id) return null;
  return `${finishedGoods ? 'P' : 'M'}:${id}`;
}

export interface DuplicateTransferRowGroup {
  identity: string;
  name: string;
  rows: TransferCreateRow[];
  /** 合计基本量 = Σ(行数量 × 包装换算数), 与 _currentStock 同口径可直接比较。 */
  totalBaseQuantity: number;
  baseUnit: string;
}

/**
 * 找出同一物料被写成多行的那一组 (没有则 null)。
 *
 * <p>2026-08-09 六膳门 prod 事故 TRF-20260809-1790: 「金蒜牛排调味料 滚揉用」写了两行各
 * 1000kg, 而主仓该原料只有 1000kg。建单逐行 `quantity > _currentStock` 校验、后端逐行
 * `ensureCreateQuantityAvailable`、详情页逐行 `isStockShortage` —— 三处都按行比, 每行
 * 1000 ≤ 1000 全部合法, 没有一处把两行加起来看。审批通过后点「确认调拨入库」才由
 * `deductSourceInventory` 抛 "缺少 1000", 而那时明细已不可编辑, 只能取消重建。
 */
export function findDuplicateTransferRow(rows: TransferCreateRow[]): DuplicateTransferRowGroup | null {
  const groups = new Map<string, TransferCreateRow[]>();
  for (const row of rows) {
    const identity = transferRowIdentity(row);
    if (!identity) continue; // 未选物料的空行交给"请为每行选择物料"提示, 这里不抢答
    const bucket = groups.get(identity);
    if (bucket) bucket.push(row);
    else groups.set(identity, [row]);
  }
  for (const [identity, bucket] of groups) {
    if (bucket.length < 2) continue;
    const first = bucket[0];
    return {
      identity,
      name: first.itemName || identity.slice(2),
      rows: bucket,
      totalBaseQuantity: bucket.reduce(
        (sum, row) => sum + Number(row.quantity || 0) * Number(row._packageFactor || 1), 0),
      baseUnit: first._inventoryUnit || first.unit || '',
    };
  }
  return null;
}

/** 详情页 (后端返回) 的明细行形状 —— 与建单表单行不同, 但物料身份的定义必须是同一条。 */
export interface TransferDetailItemRow {
  itemType?: string | null;
  materialTypeId?: string | null;
  productTypeId?: string | null;
  quantity?: number | string | null;
  currentStock?: number | string | null;
}

export function transferDetailRowIdentity(row: TransferDetailItemRow): string | null {
  const finishedGoods = row.itemType === 'FINISHED_GOODS';
  const id = String((finishedGoods ? row.productTypeId : row.materialTypeId) ?? '').trim();
  return id ? `${finishedGoods ? 'P' : 'M'}:${id}` : null;
}

/**
 * 本单对每个物料的<b>合计</b>需求量 (基本单位)。
 *
 * <p>详情页的 `currentStock` 是<b>按物料</b>查的实时可用量 —— 同一物料的两行会各自拿到同一个
 * 数字。逐行比 "1000 ≤ 1000" 两行都判"够", 但它们扣的是同一批库存。必须先按物料加总。
 */
export function aggregateTransferDemand(rows: TransferDetailItemRow[]): Map<string, number> {
  const totals = new Map<string, number>();
  for (const row of rows) {
    const identity = transferDetailRowIdentity(row);
    if (!identity) continue;
    const qty = Number(row.quantity);
    if (!Number.isFinite(qty)) continue;
    totals.set(identity, (totals.get(identity) ?? 0) + qty);
  }
  return totals;
}

/** 该行是否缺货 —— 比的是"该物料在本单的合计需求", 不是本行数量。 */
export function isTransferRowShortage(
  row: TransferDetailItemRow,
  demand: Map<string, number>,
): boolean {
  if (row.currentStock === null || row.currentStock === undefined) return false;
  if (row.quantity === null || row.quantity === undefined) return false;
  const stock = Number(row.currentStock);
  const quantity = Number(row.quantity);
  if (!Number.isFinite(stock) || !Number.isFinite(quantity)) return false;
  // 无重复行时合计恒等于本行数量, 与旧的逐行行为一致。
  return stock < (demand.get(transferDetailRowIdentity(row) ?? '') ?? quantity);
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
    ...(row.materialPackagingSpecId
      ? { materialPackagingSpecId: row.materialPackagingSpecId }
      : {}),
    unitPrice: row.unitPrice == null
      ? undefined
      : row.unitPrice * Number(row._packageFactor || 1),
    remark: row.remark || undefined,
  };
}
