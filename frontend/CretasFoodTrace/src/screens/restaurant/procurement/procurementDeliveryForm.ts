import type { MaterialType } from '../../../services/api/materialTypeApiClient';
import type { Supplier } from '../../../services/api/supplierApiClient';

export interface ProcurementDeliveryLineState {
  materialSearch: string;
  ingredientName: string;
  rawMaterialTypeId: string;
  quantity: string;
  unit: string;
  unitPrice: string;
}

export interface ProcurementDeliverySubmitState {
  supplierName: string;
  deliveryDate: string;
  lines: ProcurementDeliveryLineState[];
  quoteUploading: boolean;
  voiceUploading: boolean;
}

export function filterSupplierOptions(
  suppliers: Supplier[],
  search: string,
  limit = 5,
): Supplier[] {
  const query = search.trim().toLowerCase();
  if (!query) return [];
  return suppliers
    .filter((supplier) => [
      supplier.name,
      supplier.supplierCode,
      supplier.code,
      supplier.contactPerson,
      supplier.phone,
    ].some((value) => (value || '').toLowerCase().includes(query)))
    .slice(0, limit);
}

export function resolveRequisitionMaterialName(
  materialName: string | undefined,
  materialTypeId: string,
  materials: MaterialType[],
): string {
  const providedName = materialName?.trim();
  if (providedName) return providedName;
  return materials.find((material) => material.id === materialTypeId)?.name || '未命名食材';
}

export function getProcurementDeliverySubmitBlocker(
  state: ProcurementDeliverySubmitState,
): string | null {
  if (!state.supplierName.trim()) return '请填写供应商名称。';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(state.deliveryDate)) {
    return '送货日期请填写为 YYYY-MM-DD，例如 2026-08-02。';
  }

  const startedLines = state.lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => Boolean(
      line.materialSearch.trim()
        || line.ingredientName.trim()
        || line.rawMaterialTypeId.trim()
        || line.quantity.trim()
        || line.unitPrice.trim(),
    ));

  if (startedLines.length === 0) return '请至少选择一项食材并填写送货数量。';

  for (const { line, index } of startedLines) {
    if (!line.rawMaterialTypeId.trim()) return `第 ${index + 1} 行：请从候选中选择食材。`;
    const quantity = Number(line.quantity);
    if (!Number.isFinite(quantity) || quantity <= 0) return `第 ${index + 1} 行：送货数量必须大于 0。`;
    if (!line.unit.trim()) return `第 ${index + 1} 行：请填写送货单位。`;
    if (line.unitPrice.trim()) {
      const unitPrice = Number(line.unitPrice);
      if (!Number.isFinite(unitPrice) || unitPrice < 0) return `第 ${index + 1} 行：单价不能为负数。`;
    }
  }

  if (state.quoteUploading) return '报价照片正在上传，请等待完成。';
  if (state.voiceUploading) return '语音录音正在上传，请等待完成。';
  return null;
}
