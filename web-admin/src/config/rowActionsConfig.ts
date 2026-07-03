/**
 * Row-action status machine + RBAC config (web-admin side).
 *
 * Mirror of frontend/CretasFoodTrace/src/config/rowActionsConfig.ts. The two
 * projects don't share a package; keep both files in sync when adding new
 * statuses or rebalancing the action lists.
 *
 * Status strings are the actual backend enum values (uppercase) found in
 * production list views. `getActionIdsForStatus` canonicalizes (trim +
 * uppercase) the incoming status before lookup, so keys here MUST be
 * uppercase — see the case-mismatch class fixed below.
 *
 * ⛔ Dead-menu bug class (fool-proof-design.md Rule 5, fixed here):
 * every key below MUST be a value the backend entity's status enum can
 * actually emit (verified against `entity/enums/*Status.java` — see PR
 * description), AND every action id listed for a status MUST have a real
 * `case` in that screen's `handleRowActionClick` (verified against each
 * `views/**\/list.vue`). A key that doesn't match the real enum, or an
 * action id with no handler, makes the "更多" dropdown resolve to
 * `DEFAULT_UNKNOWN_STATUS_ACTIONS` (only 详情) or falls through to a
 * debug toast — the whole menu looks alive but every click is dead.
 */

import { COMMON_ACTIONS, type EntityType } from '@/types/rowActions';

type ActionId = (typeof COMMON_ACTIONS)[keyof typeof COMMON_ACTIONS]['id'];

export const STATUS_ACTIONS_MAP: Readonly<Record<EntityType, Readonly<Record<string, readonly ActionId[]>>>> = {
  // T129 Part 2 — dedupe: 详情/编辑/确认/取消 are hardcoded inline buttons in list.vue;
  // only uncommon actions belong here (appear in RowActionMenu "更多" dropdown).
  salesOrder: {
    // 详情(hardcoded) + 编辑(hardcoded) + 确认(hardcoded) are already inline buttons for DRAFT.
    // Remaining uncommon actions: copy, delete, edit-price.
    DRAFT: ['submit-for-review', 'copy', 'delete', 'edit-price'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-price-history'],
    // 取消 is hardcoded for DRAFT+CONFIRMED; remove from CONFIRMED to avoid duplicate.
    CONFIRMED: ['submit-for-review', 'convert-to-production', 'convert-to-purchase', 'print-pdf'],
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
  // PurchaseOrderStatus.java real values: DRAFT / SUBMITTED / WORKFLOW_RUNNING /
  // APPROVED / PENDING_FINANCE_REVIEW / FINANCE_APPROVED / FINANCE_REJECTED /
  // PARTIAL_RECEIVED / COMPLETED / CANCELLED / CLOSED. Old table was missing
  // WORKFLOW_RUNNING/PENDING_FINANCE_REVIEW/FINANCE_APPROVED/FINANCE_REJECTED/CLOSED
  // entirely — those rows fell to DEFAULT_UNKNOWN_STATUS_ACTIONS (仅详情), a dead
  // menu for any order past 运营审批. 'PENDING_APPROVAL'/'REJECTED' never fire
  // (not real enum values); 'RECEIVED' doesn't exist either (real value is
  // PARTIAL_RECEIVED / COMPLETED). DRAFT's 'delete' had zero handler + zero backend
  // endpoint (no DELETE /purchase/orders/{id}) — swapped for 'cancel' (real handler,
  // backend-cancellable for DRAFT per OrderUsageWhitelists.PO_CANCELLABLE).
  purchaseOrder: {
    DRAFT: ['edit', 'submit', 'copy', 'cancel', 'view-detail', 'edit-price'],
    SUBMITTED: ['approve', 'reject', 'view-price-history', 'view-detail', 'cancel'],
    WORKFLOW_RUNNING: ['view-detail'],
    APPROVED: ['print-pdf', 'undo-approval', 'cancel', 'view-detail'],
    // PO_CANCELLABLE also includes PENDING_FINANCE_REVIEW + FINANCE_REJECTED.
    PENDING_FINANCE_REVIEW: ['cancel', 'view-detail'],
    FINANCE_APPROVED: ['print-pdf', 'view-detail'],
    FINANCE_REJECTED: ['edit', 'cancel', 'view-detail'],
    PARTIAL_RECEIVED: ['view-detail', 'print-pdf'],
    COMPLETED: ['view-detail', 'print-pdf', 'copy', 'return'],
    CANCELLED: ['view-detail', 'copy'],
    CLOSED: ['view-detail', 'copy'],
  },
  productionPlan: {
    DRAFT: ['edit', 'submit', 'copy', 'delete', 'view-detail'],
    // PREPARED = M-PREP-1 草稿态 (POST /production-plans/draft); 后端 update/delete 把
    // PREPARED 与 PENDING 一视同仁 (ProductionPlanServiceImpl#updateProductionPlan /
    // #deleteProductionPlan). 之前该 status 没进这张表, 落到 DEFAULT_UNKNOWN_STATUS_ACTIONS
    // (只有 view-detail) — 草稿态计划实际上一个操作按钮都没有, 同一类 dead-stub 缺口。
    PREPARED: ['edit', 'view-detail', 'cancel'],
    PLANNED: ['edit', 'view-detail', 'cancel'],
    PENDING: ['edit', 'view-detail', 'cancel'],
    PENDING_APPROVAL: ['approve', 'reject', 'view-detail'],
    CONFIRMED: ['view-detail', 'print-pdf', 'cancel'],
    APPROVED: ['print-pdf', 'undo-approval', 'cancel', 'view-detail'],
    IN_PROGRESS: ['view-detail', 'print-pdf', 'lock'],
    // PAUSED (real ProductionPlanStatus value) was missing entirely — paused
    // plans fell to DEFAULT_UNKNOWN_STATUS_ACTIONS then got their lone
    // 'view-detail' filtered out by rowActionsFor() → 无可用操作.
    PAUSED: ['view-detail', 'print-pdf'],
    COMPLETED: ['view-detail', 'print-pdf', 'copy'],
    CANCELLED: ['view-detail', 'copy'],
  },
  // 'processTask' backs production/batches/list.vue → GET /processing/batches →
  // ProductionBatch entity → ProductionBatchStatus.java real values: PLANNED /
  // PLANNING (legacy alias) / IN_PROGRESS / PRODUCING (legacy alias) / PAUSED /
  // COMPLETED / CANCELLED. 'PENDING' is not a real value — PLANNED/PLANNING rows
  // (i.e. every batch before it's started) fell to DEFAULT_UNKNOWN_STATUS_ACTIONS,
  // and list.vue's rowActionsFor() filters out even that lone 'view-detail', so
  // the dropdown showed "无可用操作" for the entire pre-start lifecycle. PAUSED/
  // PRODUCING were simply missing (same dead-menu symptom). 'edit'/'lock' are
  // force-disabled at the call site (no batch-edit form + no lock API exist yet)
  // rather than click-then-toast — see rowActionsFor() forceDisabled in
  // production/batches/list.vue.
  processTask: {
    PLANNED: ['view-detail', 'edit'],
    PLANNING: ['view-detail', 'edit'],
    IN_PROGRESS: ['view-detail', 'print-pdf', 'lock'],
    PRODUCING: ['view-detail', 'print-pdf', 'lock'],
    PAUSED: ['view-detail', 'print-pdf'],
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
  // 'whInbound' backs procurement/receives/list.vue → PurchaseReceiveRecord entity →
  // PurchaseReceiveStatus.java real values: DRAFT / PENDING_QC / CONFIRMED / REJECTED
  // (there's no 'PENDING' — the ReceiveRow TS interface itself had drifted to
  // 'DRAFT'|'CONFIRMED'|'CANCELLED', also wrong). Old 'PENDING'/'RECEIVED' keys
  // NEVER matched a real receive status → 100% of receives fell to
  // DEFAULT_UNKNOWN_STATUS_ACTIONS (仅详情): DRAFT receives couldn't 确认入库,
  // CONFIRMED couldn't 打印. 'edit'/'delete' dropped — no handler in list.vue and
  // no PUT/DELETE /purchase/receives/{id} endpoint exists.
  whInbound: {
    DRAFT: ['submit', 'view-detail'],
    PENDING_QC: ['view-detail'],
    CONFIRMED: ['print-pdf', 'view-detail'],
    REJECTED: ['view-detail'],
  },
  // 'whOutbound' backs sales/shipments/list.vue → ShipmentRecord entity, status is a
  // free-text column (not a Java enum) but ShipmentRecordService only ever sets:
  // pending / shipped / delivered / returned / cancelled (all lowercase — the
  // page's own getStatusType()/getStatusText() already `.toUpperCase()` the value
  // before their display-label lookup, which is the tell). Old keys were
  // 'PENDING'/'SHIPPED'/'COMPLETED' — with case-sensitive lookup NONE of them ever
  // matched a lowercase real status, so 退货/打印 were unreachable from every row
  // regardless of status. Now fixed at two layers: (1) getActionIdsForStatus()
  // below canonicalizes to uppercase before lookup, (2) keys widened to the real
  // status set including 'DELIVERED' (was missing — 'COMPLETED' never exists) and
  // 'RETURNED'/'CANCELLED'. 退货 available for SHIPPED *and* DELIVERED per product
  // ask (returns happen post-delivery too).
  whOutbound: {
    PENDING: ['view-detail'],
    SHIPPED: ['print-pdf', 'view-detail', 'return'],
    DELIVERED: ['print-pdf', 'view-detail', 'return'],
    RETURNED: ['print-pdf', 'view-detail'],
    CANCELLED: ['view-detail'],
  },
  // 'returnOrder' backs sales/returns/list.vue (read-only list: only view-detail +
  // print-pdf are ever handled there — edit/submit/delete/approve/reject/undo-approval
  // have zero case in that screen's handleRowActionClick, write actions on returns
  // live on the detail route). ReturnOrderStatus.java real values match the page's
  // own STATUS_OPTIONS exactly: DRAFT/SUBMITTED/APPROVED/FINANCE_APPROVED/REJECTED/
  // PROCESSING/COMPLETED. Old table was missing 'FINANCE_APPROVED' (real value) —
  // those rows (financially approved, 可交仓管出货完成) fell to 仅详情, losing 打印.
  // 'PENDING_APPROVAL' never fires (not a real value; SUBMITTED already covers it).
  returnOrder: {
    DRAFT: ['view-detail'],
    SUBMITTED: ['view-detail'],
    APPROVED: ['view-detail'],
    FINANCE_APPROVED: ['print-pdf', 'view-detail'],
    REJECTED: ['view-detail'],
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

/**
 * Central fix for the case-mismatch half of the dead-menu bug class: some
 * screens pass through the raw backend value verbatim (e.g. ShipmentRecord's
 * free-text `status` column is lowercase — "pending"/"shipped"/"delivered"),
 * while STATUS_ACTIONS_MAP keys are uniformly uppercase. A case-sensitive
 * `byStatus[status]` lookup silently misses those rows and falls back to
 * DEFAULT_UNKNOWN_STATUS_ACTIONS (仅详情) — the action menu looks populated
 * but every uncommon action is unreachable. Canonicalize once, here, so no
 * future call site has to remember to uppercase its own status string.
 */
export function getActionIdsForStatus(entityType: EntityType, status: string): readonly ActionId[] {
  const byStatus = STATUS_ACTIONS_MAP[entityType];
  if (!byStatus) return DEFAULT_UNKNOWN_STATUS_ACTIONS;
  const normalized = String(status ?? '').trim().toUpperCase();
  const ids = byStatus[normalized];
  return ids ?? DEFAULT_UNKNOWN_STATUS_ACTIONS;
}

/** Action ids that mutate the entity. Used to honor `canEdit=false`. */
export const WRITE_ACTION_IDS: ReadonlySet<string> = new Set([
  'edit',
  'submit',
  'submit-for-review',
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
