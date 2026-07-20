import { describe, expect, it } from 'vitest';
import type { RowAction } from '@/types/rowActions';
import { canSubmitPurchaseOrder, purchaseOrderMoreActions } from './purchaseOrderActionIa';

function action(id: string): RowAction {
  return { id, label: id, icon: '' };
}

describe('purchase order row action IA', () => {
  it('keeps primary actions out of more and removes business-page approval and audit entries', () => {
    const result = purchaseOrderMoreActions([
      action('view-detail'), action('print-pdf'), action('submit'), action('edit'), action('cancel'),
      action('copy'), action('approve'), action('reject'), action('audit'), action('delete'), action('edit'),
    ]);
    expect(result.map((item) => item.id)).toEqual(['edit', 'cancel', 'copy']);
  });

  it('shows submit only for writable drafts', () => {
    expect(canSubmitPurchaseOrder('DRAFT', true)).toBe(true);
    expect(canSubmitPurchaseOrder('DRAFT', false)).toBe(false);
    expect(canSubmitPurchaseOrder('SUBMITTED', true)).toBe(false);
    expect(canSubmitPurchaseOrder('APPROVED', true)).toBe(false);
  });
});
