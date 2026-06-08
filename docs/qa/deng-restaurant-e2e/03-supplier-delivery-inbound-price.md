# 03 Supplier Delivery, Inbound Posting, Price Difference

## Entry Hook From 02

Require:

```yaml
chainId:
factoryId:
deliveryDate:
procurementPlan.planId:
procurementPlan.plannedLines:
supplierName:
evidenceRoot:
```

If `procurementPlan.planId` is missing because File 02 found a gap, continue with direct supplier delivery creation and mark the plan linkage as `GAP-03-PLAN-LINK`.

## Purpose

This tests the current P2P/inbound chain and the transcript's price-difference control point:

- Supplier delivers.
- Warehouse clerk compares actual quantity/quality/price with the planned order.
- Price anomalies require explanation or clear alert.
- Confirming inbound creates real inventory batches and payable/cost downstream records.

## Roles

| Role | Account | Expected Access |
|---|---|---|
| Warehouse | `qhj_warehouse_mgr` | create/edit/confirm delivery notes |
| Super | `qhj_prod` | full read/write and amount visibility |
| Finance | `qhj_finance_mgr` | read confirmed delivery notes and reconciliation context |
| Sales | `qhj_sales_mgr` | no direct access to P2P finance/cost APIs |

## Deep Scenario 03-A: Warehouse Creates Supplier Delivery Note

Depth: `deep`

Steps:

1. Login web-admin as `qhj_warehouse_mgr`.
2. Go to `/restaurant/supplier-delivery`.
3. Create delivery note:
   - supplier: `<SUPPLIER>`.
   - delivery no: `<DELIVERY_NO>`.
   - delivery date: `2026-06-07`.
   - source: manual or imported draft.
4. Enter actual lines:
   - 黄瓜 11.5 kg * 4.20 = 48.30.
   - 土豆粉 20 kg * 3.50 = 70.00.
   - 青花椒 5 kg * 46.00 = 230.00.
   - 洗洁精 1 箱 * 110.00 = 110.00.
5. Save draft.
6. Fresh navigate to list/detail by delivery no.

Pass criteria:

- Draft readback shows exact quantities/prices/total `458.30`.
- Supplier/material selection is searchable; no raw UUID-only path.
- If linked plan exists, the UI shows planned vs actual differences.
- Difference reason fields are available for quantity/price/quality, or gap is recorded.

## Deep Scenario 03-B: Price Difference Warning And Explanation

Depth: `deep` if warning blocks or captures explanation before confirm.

Steps:

1. On the draft detail, inspect price check for:
   - 青花椒 planned 40.00 vs actual 46.00.
   - 洗洁精 planned/historical 90.00 vs actual 110.00.
2. Attempt to confirm inbound without explanation.
3. Expected behavior:
   - Either confirmation is blocked until explanation is provided, or
   - confirmation is allowed but anomaly is created with explicit warning and action.
4. Add explanation:
   - 青花椒: `市场临时涨价，供应商已电话说明；需老板复核`.
   - 洗洁精: `规格变化待确认`.
5. Save and verify readback:
   - anomaly status.
   - explanation text.
   - supplier/material/old price/new price/threshold.

Pass criteria:

- System does not silently accept price increases without any trace.
- Anomaly compares against planned price and/or own 90-day baseline.
- Price decrease is not treated as fraud by default but remains visible.
- Unknown baseline shows `基线不足`, not fake normal/abnormal.

Gap to record if missing:

```yaml
gapId: GAP-03-B
gap: Price anomaly exists as separate analysis but is not integrated into warehouse acceptance path
requiredHook:
  deliveryNoteLineId:
  material:
  plannedUnitPrice:
  actualUnitPrice:
  baselineMode:
  anomalyId:
  explanation:
```

## Deep Scenario 03-C: Confirm Inbound Posting

Depth: `deep`

Steps:

1. Confirm inbound as warehouse.
2. Capture API response/toast.
3. Fresh navigate to delivery detail.
4. Verify:
   - note status `CONFIRMED` or `POSTED`.
   - posting status `POSTED`.
   - receive record id is present.
   - every line has `materialBatchId`.
5. Fresh navigate to inventory/batch or use API readback.
6. Verify material batches exist for the accepted lines.
7. Re-click confirm.

Pass criteria:

- First confirm creates receive record and material batches.
- Duplicate confirm returns 409 or idempotent no-op.
- No duplicate material batch is created.
- Invalid material cannot post and gives honest `FAILED` status/action.

## RBAC Direct API Regression

Use browser or script with authenticated tokens.

| Account | Supplier Delivery | Reconciliation | Cost Attribution |
|---|---|---|---|
| `qhj_warehouse_mgr` | list/detail/write allowed | finance pages denied | cost pages denied |
| `qhj_finance_mgr` | confirmed list/detail allowed | allowed | allowed |
| `qhj_sales_mgr` | denied or irrelevant | 403 | 403 |
| `qhj_prod` | allowed | allowed | allowed |

## Exit Hook To 04

```yaml
chainId:
factoryId:
deliveryNote:
  id:
  deliveryNo:
  status:
  postingStatus:
  totalAmount: 458.30
  evidence:
receiveRecord:
  id:
materialBatches:
  - material:
    materialBatchId:
    qty:
    unit:
    unitPrice:
priceAnomalies:
  - anomalyId:
    material:
    plannedUnitPrice:
    actualUnitPrice:
    baseline:
    explanation:
    status:
rbacEvidence:
blockingDefects:
gaps:
```

