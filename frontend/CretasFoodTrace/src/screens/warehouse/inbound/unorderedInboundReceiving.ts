import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';

import type { CreateCustomerMaterialArrivalReceiptRequest } from '../../../services/api/warehouseReceivingApiClient';

export interface ReceiptDraft {
  noticeId: string;
  materialTypeId: string;
  warehouseId: string;
  quantityText: string;
  unit: string;
  externalBatchNumber?: string;
  notes?: string;
  completeNotice: boolean;
}

export function parseReceiptQuantity(value: string): number {
  const normalized = value.trim();
  if (!/^\d{1,8}(?:\.\d{1,2})?$/.test(normalized)) {
    throw new Error('实收数量最多 8 位整数和 2 位小数');
  }
  const quantity = Number(normalized);
  if (!Number.isFinite(quantity) || quantity < 0.01) {
    throw new Error('实收数量不能小于 0.01');
  }
  return quantity;
}

export function createReceiptIdempotencyKey(noticeId: string): string {
  return `rn-${noticeId.slice(0, 12)}-${uuidv4()}`.slice(0, 64);
}

export function buildReceiptPayload(
  draft: ReceiptDraft,
  idempotencyKey: string,
): CreateCustomerMaterialArrivalReceiptRequest {
  if (!draft.materialTypeId) throw new Error('请选择实际原料');
  if (!draft.warehouseId) throw new Error('请选择入库仓库');
  if (!draft.unit.trim()) throw new Error('所选原料缺少计量单位，请联系管理员维护');

  const externalBatchNumber = draft.externalBatchNumber?.trim();
  const notes = draft.notes?.trim();
  return {
    idempotencyKey,
    materialTypeId: draft.materialTypeId,
    warehouseId: draft.warehouseId,
    receivedQuantity: parseReceiptQuantity(draft.quantityText),
    unit: draft.unit.trim(),
    externalBatchNumber: externalBatchNumber || undefined,
    notes: notes || undefined,
    completeNotice: draft.completeNotice,
  };
}

export const UNORDERED_INBOUND_REASON_LABEL: Record<string, string> = {
  CUSTOMER_MATERIAL: '客户来料',
  GIFT: '赠予入库',
  OTHER: '其他无订单入库',
};
