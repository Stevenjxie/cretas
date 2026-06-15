/**
 * Row-action status machine + RBAC config (RN side).
 *
 * Two pieces of pure data:
 *   - PRICE_VIEW_ROLES: roles allowed to see price-related actions. Mirrors
 *     web-admin/src/store/modules/permission.ts:PRICE_VIEW_ROLES which itself
 *     mirrors backend `PermissionServiceImpl.PRICE_VIEW_ROLES`. Keep all three
 *     in sync when adding/removing a role.
 *   - STATUS_ACTIONS_MAP: per-entity-type → per-status → ordered list of
 *     COMMON_ACTIONS ids that should appear on that row. Status strings are
 *     the actual backend enum values (uppercase) — see each entity's
 *     ListScreen STATUS_MAP for the canonical list.
 *
 * useRowActions(...) does the actual filter + assembly.
 */

import { COMMON_ACTIONS, type EntityType } from '../types/rowActions';

/** Roles allowed to see priceRelated actions. Keep in sync with web-admin + backend. */
export const PRICE_VIEW_ROLES: ReadonlySet<string> = new Set([
  'factory_super_admin',
  'platform_admin',
  'procurement_manager',
  'finance_manager',
  'cashier',
  'sales_manager',
  'dispatcher',
  'production_manager',
  'restaurant_manager',
  'permission_admin',
  'department_admin',
]);

type ActionId = (typeof COMMON_ACTIONS)[keyof typeof COMMON_ACTIONS]['id'];

/**
 * Per-entity status → action-id list. Includes both the canonical workflow
 * statuses (DRAFT/PENDING_APPROVAL/APPROVED/...) and the actual backend
 * variants found in production list screens (CONFIRMED/SUBMITTED/REQUESTED/
 * PARTIAL_DELIVERED/...). Unknown statuses fall back to view-detail only.
 *
 * Lists are intentionally short (3-7 items) so the BottomSheet/Menu doesn't
 * become a wall of buttons. Edit here, not at the call site.
 */
export const STATUS_ACTIONS_MAP: Readonly<Record<EntityType, Readonly<Record<string, readonly ActionId[]>>>> = {
  salesOrder: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail', 'edit-price'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-price-history', 'view-detail'],
    CONFIRMED: ['convert-to-production', 'convert-to-purchase', 'print-pdf', 'cancel', 'view-detail'],
    APPROVED: ['convert-to-production', 'convert-to-purchase', 'print-pdf', 'undo-approval', 'cancel', 'view-detail'],
    PROCESSING: ['view-detail', 'print-pdf'],
    IN_PRODUCTION: ['view-detail', 'print-pdf'],
    PARTIAL_DELIVERED: ['view-detail', 'print-pdf', 'return'],
    SHIPPED: ['view-detail', 'print-pdf', 'return'],
    COMPLETED: ['view-detail', 'print-pdf', 'copy', 'return'],
    CANCELLED: ['view-detail', 'copy'],
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
    // Real screens (FinishedGoods/WHInventory) compute these from quantity
    // ratios; pass the derived label through.
    IN_STOCK: ['transfer', 'view-detail', 'view-price-history'],
    LOW_STOCK: ['transfer', 'view-detail', 'view-price-history'],
    OUT_OF_STOCK: ['view-detail', 'view-price-history'],
    EXPIRED: ['view-detail', 'view-price-history'],
    EXPIRE: ['view-detail', 'view-price-history'], // WHInventory warning string
    LOW: ['transfer', 'view-detail', 'view-price-history'],
    NORMAL: ['transfer', 'view-detail', 'view-price-history'],
    SUFFICIENT: ['transfer', 'view-detail', 'view-price-history'],
    SOLD_OUT: ['view-detail', 'view-price-history'],
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

/** Default action list for an unknown status — view only. */
export const DEFAULT_UNKNOWN_STATUS_ACTIONS: readonly ActionId[] = ['view-detail'];

/**
 * Lookup helper. Returns the configured action ids for (entityType, status),
 * falling back to DEFAULT_UNKNOWN_STATUS_ACTIONS for unrecognized statuses.
 */
export function getActionIdsForStatus(entityType: EntityType, status: string): readonly ActionId[] {
  const byStatus = STATUS_ACTIONS_MAP[entityType];
  if (!byStatus) return DEFAULT_UNKNOWN_STATUS_ACTIONS;
  const ids = byStatus[status];
  return ids ?? DEFAULT_UNKNOWN_STATUS_ACTIONS;
}

/** True iff the role may see price-related actions. */
export function roleCanViewPrice(role: string | null | undefined): boolean {
  if (!role) return false;
  return PRICE_VIEW_ROLES.has(role);
}
