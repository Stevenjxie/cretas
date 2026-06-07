/**
 * Row-action status machine + RBAC config (web-admin side).
 *
 * Mirror of frontend/CretasFoodTrace/src/config/rowActionsConfig.ts. The two
 * projects don't share a package; keep both files in sync when adding new
 * statuses or rebalancing the action lists.
 *
 * Status strings are the actual backend enum values (uppercase) found in
 * production list views.
 */

import { COMMON_ACTIONS, type EntityType } from '@/types/rowActions';

type ActionId = (typeof COMMON_ACTIONS)[keyof typeof COMMON_ACTIONS]['id'];

export const STATUS_ACTIONS_MAP: Readonly<Record<EntityType, Readonly<Record<string, readonly ActionId[]>>>> = {
  // T129 Part 2 — dedupe: 详情/编辑/确认/取消 are hardcoded inline buttons in list.vue;
  // only uncommon actions belong here (appear in RowActionMenu "更多" dropdown).
  salesOrder: {
    // 详情(hardcoded) + 编辑(hardcoded) + 确认(hardcoded) are already inline buttons for DRAFT.
    // Remaining uncommon actions: copy, delete, edit-price.
    DRAFT: ['copy', 'delete', 'edit-price'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-price-history'],
    // 取消 is hardcoded for DRAFT+CONFIRMED; remove from CONFIRMED to avoid duplicate.
    CONFIRMED: ['convert-to-production', 'convert-to-purchase', 'print-pdf'],
    APPROVED: ['convert-to-production', 'convert-to-purchase', 'print-pdf', 'undo-approval', 'cancel'],
    PENDING_FINANCE_REVIEW: [],
    FINANCE_APPROVED: ['convert-to-production', 'convert-to-purchase', 'print-pdf', 'cancel'],
    FINANCE_REJECTED: ['edit'],
    PROCESSING: ['print-pdf'],
    IN_PRODUCTION: ['print-pdf'],
    PARTIAL_DELIVERED: ['print-pdf', 'return'],
    SHIPPED: ['print-pdf', 'return'],
    COMPLETED: ['print-pdf', 'copy', 'return'],
    CANCELLED: ['copy'],
  },
  purchaseOrder: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail', 'edit-price'],
    SUBMITTED: ['approve', 'reject', 'view-price-history', 'view-detail'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-price-history', 'view-detail'],
    APPROVED: ['print-pdf', 'undo-approval', 'cancel', 'view-detail'],
    REJECTED: ['edit', 'view-detail'],
    PARTIAL_RECEIVED: ['view-detail', 'print-pdf'],
    RECEIVED: ['view-detail', 'print-pdf'],
    COMPLETED: ['view-detail', 'print-pdf', 'copy', 'return'],
    CANCELLED: ['view-detail', 'copy'],
  },
  productionPlan: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail'],
    PLANNED: ['edit', 'view-detail', 'cancel'],
    PENDING: ['edit', 'view-detail', 'cancel'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-detail'],
    CONFIRMED: ['view-detail', 'print-pdf', 'cancel'],
    APPROVED: ['print-pdf', 'undo-approval', 'cancel', 'view-detail'],
    IN_PROGRESS: ['view-detail', 'print-pdf', 'lock'],
    COMPLETED: ['view-detail', 'print-pdf', 'copy'],
    CANCELLED: ['view-detail', 'copy'],
  },
  processTask: {
    PENDING: ['view-detail', 'edit'],
    IN_PROGRESS: ['view-detail', 'print-pdf', 'lock'],
    COMPLETED: ['view-detail', 'print-pdf'],
    CANCELLED: ['view-detail'],
  },
  inventory: {
    IN_STOCK: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    LOW_STOCK: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    OUT_OF_STOCK: ['adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    EXPIRED: ['adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    EXPIRE: ['adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    LOW: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    NORMAL: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    SUFFICIENT: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
    SOLD_OUT: ['adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
  },
  whInbound: {
    PENDING: ['edit', 'submit', 'delete', 'view-detail'],
    RECEIVED: ['print-pdf', 'view-detail'],
    COMPLETED: ['print-pdf', 'view-detail'],
  },
  whOutbound: {
    PENDING: ['edit', 'submit', 'delete', 'view-detail'],
    SHIPPED: ['print-pdf', 'view-detail', 'return'],
    COMPLETED: ['print-pdf', 'view-detail'],
  },
  returnOrder: {
    DRAFT: ['edit', 'submit', 'delete', 'view-detail'],
    SUBMITTED: ['approve', 'reject', 'view-detail'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-detail'],
    APPROVED: ['print-pdf', 'view-detail'],
    REJECTED: ['edit', 'view-detail'],
    PROCESSING: ['view-detail', 'print-pdf'],
    COMPLETED: ['view-detail', 'print-pdf'],
  },
  transfer: {
    DRAFT: ['edit', 'submit', 'delete', 'view-detail'],
    REQUESTED: ['approve', 'reject', 'view-detail'],
    APPROVED: ['view-detail', 'print-pdf'],
    REJECTED: ['edit', 'view-detail'],
    SHIPPED: ['view-detail', 'print-pdf'],
    IN_TRANSIT: ['view-detail', 'print-pdf'],
    RECEIVED: ['view-detail', 'print-pdf'],
    CONFIRMED: ['view-detail', 'print-pdf'],
    COMPLETED: ['view-detail', 'print-pdf'],
    CANCELLED: ['view-detail'],
  },
  wastage: {
    DRAFT: ['edit', 'submit', 'delete', 'view-detail'],
    SUBMITTED: ['approve', 'reject', 'view-detail'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-detail'],
    APPROVED: ['view-detail', 'print-pdf'],
    REJECTED: ['edit', 'view-detail'],
  },
  sample: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-detail'],
    APPROVED: ['copy', 'view-detail'],
  },
  sampleBom: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail'],
    APPROVED: ['copy', 'view-detail', 'print-pdf'],
  },
};

export const DEFAULT_UNKNOWN_STATUS_ACTIONS: readonly ActionId[] = ['view-detail'];

export function getActionIdsForStatus(entityType: EntityType, status: string): readonly ActionId[] {
  const byStatus = STATUS_ACTIONS_MAP[entityType];
  if (!byStatus) return DEFAULT_UNKNOWN_STATUS_ACTIONS;
  const ids = byStatus[status];
  return ids ?? DEFAULT_UNKNOWN_STATUS_ACTIONS;
}

/** Action ids that mutate the entity. Used to honor `canEdit=false`. */
export const WRITE_ACTION_IDS: ReadonlySet<string> = new Set([
  'edit',
  'submit',
  'approve',
  'reject',
  'undo-approval',
  'cancel',
  'delete',
  'edit-price',
  'lock',
  'unlock',
  'convert-to-production',
  'convert-to-purchase',
  'convert-to-outsource',
  'transfer',
  'return',
  'adjust-inventory',
  'void-batch',
]);

export function isWriteAction(id: string): boolean {
  return WRITE_ACTION_IDS.has(id);
}
