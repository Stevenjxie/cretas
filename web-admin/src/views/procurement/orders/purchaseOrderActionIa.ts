import type { RowAction } from '@/types/rowActions';

const PRIMARY_ACTION_IDS = new Set(['view-detail', 'print-pdf', 'submit']);
const FORBIDDEN_BUSINESS_PAGE_IDS = new Set(['approve', 'reject', 'audit', 'delete']);

export function purchaseOrderMoreActions(actions: RowAction[]): RowAction[] {
  const seen = new Set<string>();
  return actions.filter((action) => {
    if (PRIMARY_ACTION_IDS.has(action.id) || FORBIDDEN_BUSINESS_PAGE_IDS.has(action.id)) return false;
    if (seen.has(action.id)) return false;
    seen.add(action.id);
    return true;
  });
}

export function canSubmitPurchaseOrder(status: unknown, canWrite: boolean): boolean {
  return canWrite && String(status) === 'DRAFT';
}
