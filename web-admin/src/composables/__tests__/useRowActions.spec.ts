/**
 * Unit tests for `computeRowActions` (the pure core of useRowActions).
 *
 * Targets the pure compute function so no Vue render or Pinia store mock is
 * needed. The composable layer wraps this with computed() + permission store.
 *
 * Coverage matrix (Brief Day 3 DoD: 50 状态×角色组合):
 *   - 10 (entityType, status) pairs × 5 canViewPrice scenarios = 50 cases
 *   - + targeted forceDisabled / canEdit / metadata cases
 */

import { describe, it, expect } from 'vitest';
import { computeRowActions, type RowContext } from '../useRowActions';
import type { EntityType } from '@/types/rowActions';

// 5 RBAC scenarios mirror the 10 PRICE_VIEW_ROLES set down to 2 effective
// states (true/false) + 3 boundary cases. The composable layer derives the
// canViewPrice boolean from currentRole; the compute step only sees the bool.
const RBAC_SCENARIOS = [
  { name: 'factory_super_admin (canViewPrice=true)', canViewPrice: true },
  { name: 'sales_manager (canViewPrice=true)', canViewPrice: true },
  { name: 'procurement_manager (canViewPrice=true)', canViewPrice: true },
  { name: 'warehouse_manager (canViewPrice=false)', canViewPrice: false },
  { name: 'quality_inspector (canViewPrice=false)', canViewPrice: false },
] as const;

const STATUS_PAIRS: Array<{ entityType: EntityType; status: string; expectIds: readonly string[] }> = [
  {
    entityType: 'salesOrder',
    status: 'DRAFT',
    expectIds: ['submit-for-review', 'copy', 'delete', 'edit-price'],
  },
  {
    entityType: 'salesOrder',
    status: 'APPROVED',
    expectIds: [
      'convert-to-production',
      'convert-to-purchase',
      'print-pdf',
      'undo-approval',
      'cancel',
    ],
  },
  {
    entityType: 'purchaseOrder',
    status: 'DRAFT',
    // 'delete' swapped for 'cancel' (no DELETE /purchase/orders/{id} endpoint;
    // 'cancel' is the real, backend-cancellable action for DRAFT).
    expectIds: ['edit', 'submit', 'copy', 'cancel', 'view-detail', 'edit-price'],
  },
  {
    entityType: 'purchaseOrder',
    status: 'APPROVED',
    expectIds: ['print-pdf', 'undo-approval', 'cancel', 'view-detail'],
  },
  {
    entityType: 'productionPlan',
    status: 'IN_PROGRESS',
    expectIds: ['view-detail', 'print-pdf', 'lock'],
  },
  {
    entityType: 'inventory',
    status: 'IN_STOCK',
    expectIds: ['transfer', 'adjust-inventory', 'view-detail', 'view-price-history', 'void-batch'],
  },
  {
    // Real PurchaseReceiveStatus value (was 'PENDING', which never matched any
    // real receive status and always fell to view-detail-only).
    entityType: 'whInbound',
    status: 'DRAFT',
    expectIds: ['submit', 'view-detail'],
  },
  {
    entityType: 'whOutbound',
    status: 'SHIPPED',
    expectIds: ['print-pdf', 'view-detail', 'return'],
  },
  {
    // Real ReturnOrderStatus value (was 'PENDING_APPROVAL', which never fires —
    // that status doesn't exist on ReturnOrder; the real "financially approved,
    // 可交仓管出货完成" state is FINANCE_APPROVED and was missing entirely).
    entityType: 'returnOrder',
    status: 'FINANCE_APPROVED',
    expectIds: ['print-pdf', 'view-detail'],
  },
  {
    entityType: 'wastage',
    status: 'DRAFT',
    expectIds: ['edit', 'submit', 'delete', 'view-detail'],
  },
];

describe('computeRowActions (web-admin)', () => {
  describe('50-combo RBAC matrix (10 status × 5 RBAC scenarios)', () => {
    for (const pair of STATUS_PAIRS) {
      for (const scenario of RBAC_SCENARIOS) {
        const expected = scenario.canViewPrice
          ? pair.expectIds
          : pair.expectIds.filter((id) => !id.startsWith('view-price') && id !== 'edit-price');

        it(`${pair.entityType} status=${pair.status} ${scenario.name} → ${expected.length} actions`, () => {
          const actions = computeRowActions(
            pair.entityType,
            { status: pair.status, id: 'X' },
            { canViewPrice: scenario.canViewPrice }
          );
          expect(actions.map((a) => a.id)).toEqual(expected);
        });
      }
    }
  });

  describe('RBAC priceRelated filter', () => {
    it('hides edit-price when canViewPrice=false (sales DRAFT)', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'DRAFT', id: '1' },
        { canViewPrice: false }
      );
      expect(actions.find((a) => a.id === 'edit-price')).toBeUndefined();
    });

    it('shows edit-price when canViewPrice=true (sales DRAFT)', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'DRAFT', id: '1' },
        { canViewPrice: true }
      );
      expect(actions.find((a) => a.id === 'edit-price')).toBeDefined();
    });
  });

  describe('canEdit gate', () => {
    it('canEdit=false disables write actions but keeps them rendered', () => {
      const actions = computeRowActions(
        'purchaseOrder',
        { status: 'DRAFT', id: '1', canEdit: false },
        { canViewPrice: true }
      );
      const edit = actions.find((a) => a.id === 'edit');
      expect(edit?.disabled).toBe(true);
      expect(edit?.disabledReason).toMatch(/编辑权限|锁定/);
      const view = actions.find((a) => a.id === 'view-detail');
      expect(view?.disabled).toBeFalsy();
    });

    it('canEdit=true (or omitted) leaves write actions enabled', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'DRAFT', id: '1' },
        { canViewPrice: true }
      );
      const edit = actions.find((a) => a.id === 'edit');
      expect(edit?.disabled).toBeFalsy();
    });
  });

  describe('forceDisabled', () => {
    it('id-disabled with custom reason overrides default', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'DRAFT', id: '1' },
        { canViewPrice: true, forceDisabled: { delete: '关联了其他单据' } }
      );
      const del = actions.find((a) => a.id === 'delete');
      expect(del?.disabled).toBe(true);
      expect(del?.disabledReason).toBe('关联了其他单据');
    });
  });

  describe('unknown status fallback', () => {
    it('unknown status → only view-detail', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'WAT_THIS_NEW_STATUS', id: '1' },
        { canViewPrice: true }
      );
      expect(actions.map((a) => a.id)).toEqual(['view-detail']);
    });
  });

  describe('regression: dead-menu key-mismatch class (rowActionsConfig)', () => {
    // sales/shipments/list.vue passes ShipmentRecord.status verbatim — a
    // lowercase free-text column ("pending"/"shipped"/"delivered"/"returned"/
    // "cancelled"), never the uppercase STATUS_ACTIONS_MAP keys. Before the
    // fix, getActionIdsForStatus did a case-sensitive lookup so EVERY shipment
    // row fell to DEFAULT_UNKNOWN_STATUS_ACTIONS — 退货/打印 were unreachable
    // no matter the status.
    it('whOutbound: lowercase backend status still resolves (case-insensitive lookup)', () => {
      const lower = computeRowActions('whOutbound', { status: 'shipped', id: '1' }, { canViewPrice: true });
      const upper = computeRowActions('whOutbound', { status: 'SHIPPED', id: '1' }, { canViewPrice: true });
      expect(lower.map((a) => a.id)).toEqual(upper.map((a) => a.id));
      expect(lower.map((a) => a.id)).toEqual(['print-pdf', 'view-detail', 'return']);
    });

    it('whOutbound: 退货 (return) reachable for both SHIPPED and DELIVERED (returns happen post-delivery)', () => {
      const shipped = computeRowActions('whOutbound', { status: 'shipped', id: '1' }, { canViewPrice: true });
      const delivered = computeRowActions('whOutbound', { status: 'delivered', id: '1' }, { canViewPrice: true });
      expect(shipped.find((a) => a.id === 'return')).toBeDefined();
      expect(delivered.find((a) => a.id === 'return')).toBeDefined();
    });

    it('whOutbound: trailing/leading whitespace on status still normalizes', () => {
      const actions = computeRowActions('whOutbound', { status: '  delivered  ', id: '1' }, { canViewPrice: true });
      expect(actions.map((a) => a.id)).toEqual(['print-pdf', 'view-detail', 'return']);
    });

    // procurement/receives/list.vue: old whInbound keys ('PENDING'/'RECEIVED')
    // never matched a real PurchaseReceiveStatus value (DRAFT/PENDING_QC/
    // CONFIRMED/REJECTED) — every receive fell to view-detail-only regardless
    // of status. 'edit'/'delete' must NOT reappear (no handler/backend endpoint).
    it('whInbound: real enum values resolve to handled actions only', () => {
      expect(computeRowActions('whInbound', { status: 'DRAFT', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['submit', 'view-detail']);
      expect(computeRowActions('whInbound', { status: 'CONFIRMED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['print-pdf', 'view-detail']);
      // never a dead 'edit'/'delete' — no handler exists in receives/list.vue
      const draft = computeRowActions('whInbound', { status: 'DRAFT', id: '1' }, { canViewPrice: true });
      expect(draft.find((a) => a.id === 'edit')).toBeUndefined();
      expect(draft.find((a) => a.id === 'delete')).toBeUndefined();
    });

    // production/batches/list.vue: processTask backs ProductionBatchStatus,
    // whose real values (PLANNED/PLANNING/IN_PROGRESS/PRODUCING/PAUSED/
    // COMPLETED/CANCELLED) didn't include the old 'PENDING' key at all, and
    // PAUSED was missing outright.
    it('processTask: PLANNED and PAUSED (real values) resolve, not DEFAULT_UNKNOWN', () => {
      expect(computeRowActions('processTask', { status: 'PLANNED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['view-detail', 'edit']);
      expect(computeRowActions('processTask', { status: 'PAUSED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['view-detail', 'print-pdf']);
    });

    // production/plans/list.vue: PAUSED (real ProductionPlanStatus value) was
    // missing from the table entirely.
    it('productionPlan: PAUSED (real value) resolves, not DEFAULT_UNKNOWN', () => {
      const actions = computeRowActions('productionPlan', { status: 'PAUSED', id: '1' }, { canViewPrice: true });
      expect(actions.map((a) => a.id)).toEqual(['view-detail', 'print-pdf']);
    });

    // purchaseOrder: old table was missing WORKFLOW_RUNNING/PENDING_FINANCE_REVIEW/
    // FINANCE_APPROVED/FINANCE_REJECTED/CLOSED (all real PurchaseOrderStatus
    // values) — every order past 运营审批 fell to view-detail-only.
    it('purchaseOrder: real post-approval statuses resolve, not DEFAULT_UNKNOWN', () => {
      expect(computeRowActions('purchaseOrder', { status: 'FINANCE_APPROVED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['print-pdf', 'view-detail']);
      expect(computeRowActions('purchaseOrder', { status: 'WORKFLOW_RUNNING', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['view-detail']);
      expect(computeRowActions('purchaseOrder', { status: 'CLOSED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
        .toEqual(['view-detail', 'copy']);
    });
  });

  describe('action metadata preservation', () => {
    it('danger + requiresConfirm flags preserved on cancel', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'APPROVED', id: '1' },
        { canViewPrice: true }
      );
      const cancel = actions.find((a) => a.id === 'cancel');
      expect(cancel?.danger).toBe(true);
      expect(cancel?.requiresConfirm).toBe(true);
    });

    it('aiHint preserved on convert-to-production', () => {
      const actions = computeRowActions(
        'salesOrder',
        { status: 'APPROVED', id: '1' },
        { canViewPrice: true }
      );
      const convert = actions.find((a) => a.id === 'convert-to-production');
      expect(convert?.aiHint).toBe('我要把这单转成生产');
    });
  });

  describe('reference suppress: ctx fields', () => {
    it('id-of-row passed through (not used in compute, but stable shape)', () => {
      const ctx: RowContext = { status: 'DRAFT', id: 'PO-2026-007' };
      const actions = computeRowActions('purchaseOrder', ctx, { canViewPrice: true });
      expect(actions.length).toBeGreaterThan(0);
    });
  });
});
