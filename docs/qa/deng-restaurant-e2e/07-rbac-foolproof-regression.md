# 07 RBAC And Fool-Proof Regression

## Entry Hook From 06

Require all previous hooks:

```yaml
chainId:
factoryId:
period:
upstreamChainKeys:
  deliveryNoteId:
  materialBatchIds:
  reconciliationId:
kpiDashboard:
healthReport:
knownGaps:
```

## Purpose

This final file is the cross-cutting sign-off. It verifies that the workflow is usable by real low-friction restaurant roles and that sensitive values fail closed.

## Role Regression Matrix

Run these checks with browser and direct API where practical.

| Module | Super | Warehouse | Finance | Sales | Operator/Chef |
|---|---|---|---|---|---|
| Generic upload | allowed | maybe limited | finance allowed | finance denied/masked | mobile-only likely denied |
| Chef report demand | manager read | related read | denied | denied | create own |
| Procurement plan | allowed | read expected deliveries | denied/read only | denied | own demand only |
| Supplier delivery | allowed | create/confirm | read confirmed | denied | denied |
| Price anomaly | allowed | create explanation | read financial impact | amount masked/denied | denied |
| Inventory/batch | allowed | allowed | amount/cost read | denied/masked | limited own use |
| Wastage/stocktake | allowed | allowed | cost read | denied/masked | create own if supported |
| Reconciliation | allowed | denied/read-only | allowed | 403 | denied |
| Cost attribution | allowed | denied/read-only | allowed | 403 | denied |
| KPI dashboard | full values | masked cost/amount as configured | full finance values | amount masked | limited/mobile |
| AI health report | full | operational subset | finance subset | sales subset | usually denied |

## Deep Scenario 07-A: Amount Masking Is Null, Not Zero

Steps:

1. Login as `qhj_prod`; capture amount values from:
   - KPI revenue/avg ticket.
   - delivery total.
   - reconciliation amount.
   - cost attribution.
2. Login as `qhj_sales_mgr`.
3. Open same pages/direct APIs where accessible.
4. Verify sensitive amounts are:
   - `null` in API, or
   - `--` in UI.
5. Verify they are not:
   - `0`.
   - stale cached values from super login.
   - visible in exported/downloaded data.

Pass criteria:

- Role missing/unknown also masks amounts.
- Ratios/counts remain visible only where policy allows.

## Deep Scenario 07-B: Fool-Proof Write Flows

Check write dialogs from prior files:

| Flow | Must Show Before Submit | Must Block/Guide |
|---|---|---|
| Upload | file type, period/date, parsed rows | unsupported file type, low confidence |
| Chef demand | requester, stall, material, qty, date | duplicate, negative qty, missing material |
| Delivery note | supplier, planned vs actual, total | invalid material, duplicate confirm |
| Price explanation | material, old/new price, delta | explanation required if policy blocks |
| Wastage | material, qty, stall/person, reason | over-stock, missing responsible person |
| Stocktake | expected qty, counted qty, variance | impossible negative count |
| Reconciliation | supplier, period, delivery notes, net payable | duplicate reconciliation |
| KPI/AI report | period, store, data coverage | missing data next action |

For every business-rule rejection:

- Toast/message is sticky or otherwise not easy to miss.
- Message comes from backend where possible.
- Message includes next action.
- Submit button cannot create duplicate records on double click.

## Deep Scenario 07-C: Downstream Consistency Sweep

Use business keys from previous hooks:

1. Delivery total `458.30` should match:
   - delivery detail.
   - receive record.
   - reconciliation.
   - payable/cost attribution where posted.
2. Material quantities should match:
   - delivery accepted qty.
   - material batch qty.
   - requisition/wastage/stocktake variance.
3. Price anomaly should match:
   - delivery line actual price.
   - supplier price alert current price.
   - explanation readback.
4. Period should match:
   - upload period.
   - P&L period.
   - KPI/health report period.

Pass criteria:

- No downstream module shows stale or different business key.
- If a module intentionally has no integration yet, record exact gap.

## Final Report Template

Fill this after all files are run:

```yaml
chainId:
factoryId:
period:
depthBreakdown:
  smoke:
  medium:
  deep:
businessChainStatus:
  dataFoundation:
  chefDemandProcurementPlan:
  supplierInboundPrice:
  inventoryLossStocktake:
  financeReconciliationPnl:
  ownerKpiAi:
  rbacFoolProof:
deepPasses:
  - scenario:
    evidence:
failures:
  - id:
    severity:
    module:
    expected:
    actual:
    evidence:
gaps:
  - gapId:
    sourceTranscriptNeed:
    currentCodeStatus:
    suggestedImplementationHook:
deploymentStatus:
  prodTested:
  prodUrl:
  commitOrVersion:
```

## Completion Rule

The Deng restaurant line is test-complete only when:

- Each file has an Exit Hook.
- At least one true `deep` chain reaches owner KPI/AI output from created/imported data.
- Every transcript-required missing link is recorded as a gap with exact module/API hook.
- RBAC and fool-proof checks are not deferred.
