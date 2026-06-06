# 05 Finance Reconciliation, P&L, Payment

## Entry Hook From 04

Require:

```yaml
chainId:
factoryId:
upstreamDelivery.deliveryNoteId:
upstreamDelivery.receiveRecordId:
upstreamUploads.financeUploadId:
costAttributionInputs:
inventory/wastage/stocktake evidence:
```

## Purpose

This validates Deng's monthly finance rhythm:

- Supplier reconciliation before the 5th.
- Report before the 10th.
- Payment around the 15th/20th.
- P&L includes revenue, food cost, labor, rent, utilities, depreciation/amortization, stock loss, packaging/tableware breakage.

## Roles

| Role | Account | Action |
|---|---|---|
| Finance | `qhj_finance_mgr` | reconciliation, payable, P&L |
| Super | `qhj_prod` | full financial review |
| Warehouse | `qhj_warehouse_mgr` | read operational note, not finance-only functions |
| Sales | `qhj_sales_mgr` | negative access |

## Deep Scenario 05-A: Supplier Monthly Reconciliation

Depth: `deep`

Steps:

1. Login as `qhj_finance_mgr`.
2. Open `/restaurant/supplier-reconciliation`.
3. Select period `2026-06` and supplier `<SUPPLIER>`.
4. Pull confirmed delivery note from File 03.
5. Create reconciliation:
   - delivery total `458.30`.
   - adjustment `0`.
   - net payable `458.30`.
6. Confirm/save.
7. Fresh navigate to reconciliation detail.
8. Verify it links to:
   - supplier.
   - delivery note id.
   - payable transaction if generated.
   - amount `458.30`.

Pass criteria:

- Finance can read confirmed delivery notes.
- Duplicate reconciliation is rejected or idempotent.
- Sales direct access is 403.

## Deep Scenario 05-B: Cost Attribution

Depth: `deep`

Steps:

1. Login as `qhj_finance_mgr`.
2. Open `/restaurant/cost-attribution`.
3. Filter period `2026-06`.
4. Verify delivery/inbound cost, wastage, requisition, and stocktake variance are visible or explicitly marked not yet posted.
5. Cross-check amount totals:
   - delivery cost `458.30`.
   - wastage quantity/cost from File 04 if posted.
6. Capture page/API evidence.

Pass criteria:

- Finance sees amounts.
- Non-finance roles either get 403 or amount masking.
- Missing deduction/posting is surfaced as gap, not hidden.

## Deep Scenario 05-C: P&L/Owner Finance Report

Depth: `deep`

Steps:

1. Use finance upload from File 01 as period source.
2. Open finance/P&L or AI health/P&L page.
3. Verify line items include where data exists:
   - revenue.
   - food cost/procurement cost.
   - labor cost.
   - rent.
   - utilities.
   - warehouse loss.
   - packaging/paper/tissue/tableware breakage.
   - equipment depreciation.
   - renovation amortization.
4. Ask AI or report query:
   - `2026年6月食材占比是多少，人工占比是多少，净利是多少？`
5. Capture structured response.

Pass criteria:

- Report uses real uploaded/current gold data.
- Missing lines produce coverage notes.
- No fake P&L categories are invented.

## Medium Scenario 05-D: Payment Voucher/Approval

Depth: `medium` unless payment voucher persistence exists.

Steps:

1. In reconciliation detail, find payment/付款审核/voucher action.
2. Create payment schedule for `2026-06-15` or `2026-06-20`.
3. Attach or record voucher metadata if supported.
4. Verify finance/super readback.

Expected:

- If implemented: payment record links reconciliation and amount.
- If missing: record `GAP-05-D-PAYMENT-VOUCHER`.

## Exit Hook To 06

```yaml
chainId:
factoryId:
period:
upstreamDelivery:
  deliveryNoteId:
  receiveRecordId:
  supplierName:
  deliveryTotal:
upstreamUploads:
  posUploadId:
  financeUploadId:
reconciliation:
  id:
  supplier:
  amount: 458.30
  status:
  deliveryNoteIds:
payable:
  transactionId:
  amount:
  paymentDate:
  voucherId:
pnl:
  revenue:
  foodCost:
  laborCost:
  rentUtilities:
  lossCost:
  depreciation:
  amortization:
  netProfit:
  coverageNote:
rbacEvidence:
gaps:
blockingDefects:
```
