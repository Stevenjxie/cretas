# 06 Owner Analysis: KPI, Health Report, Alerts, AI

## Entry Hook From 05

Require:

```yaml
chainId:
factoryId:
period:
upstreamUploads.posUploadId:
upstreamUploads.financeUploadId:
reconciliation.id:
pnl:
priceAnomalies:
wastage:
stocktakes:
```

## Purpose

This is the boss-facing outcome: Deng should not care which clerk typed what. He should see where money is made/lost and what action to take.

This file covers:

- Store manager 6 KPI dashboard.
- AI health report / diagnostic.
- Margin alerts.
- Supplier price alert.
- Target completion.
- CRM/marketing/operations analysis smoke/depth where data exists.

## Roles

| Role | Account | Expected |
|---|---|---|
| Super | `qhj_prod` | full owner view with amounts |
| Finance | `qhj_finance_mgr` | finance amounts visible |
| Sales | `qhj_sales_mgr` | sales/CRM accessible, finance/cost amount masked or denied |
| Warehouse | `qhj_warehouse_mgr` | operational counts/stock, restricted financial amount |

## Deep Scenario 06-A: Store KPI 6 Cards

Depth: `deep`

Steps:

1. Login as `qhj_prod`.
2. Open `/restaurant/analytics/role-kpi`.
3. Select/confirm period including the uploaded and inbound data.
4. Verify cards:
   - 日营收.
   - 客单价.
   - 订单数.
   - 毛利率.
   - 食材成本率.
   - 目标完成率.
5. Capture health badge color and progress bar.
6. Cross-check values against File 01 and File 05 where possible.

Pass criteria:

- Amount cards show real amounts to super.
- Cost/gross-margin cards are honest about insufficient cost-card/POS-name coverage.
- Target missing shows `去配置目标`.
- No blank card without next action.

## Deep Scenario 06-B: Amount RBAC On KPI

Depth: `deep`

Steps:

1. Login as `qhj_sales_mgr`.
2. Open the same KPI route or direct API.
3. Verify:
   - absolute revenue/avg bill amount are `null` or displayed as `--`, not `0`.
   - ratios/counts remain visible where allowed.
4. Direct API check with missing/unknown role if possible.

Pass criteria:

- RBAC fail-closed.
- Masking cannot be confused with real zero.

## Deep Scenario 06-C: AI Health Report / Diagnostic

Depth: `deep` if report reads uploaded data and gives actionable diagnoses.

Steps:

1. Open AI health report or SmartBI AI chat.
2. Generate report for `PERIOD=2026-06`.
3. Verify report mentions or computes:
   - food cost ratio.
   - labor ratio if finance upload includes it.
   - supplier price anomaly.
   - wastage/stocktake variance if posted.
   - POS revenue/order trend.
4. Verify coverage note for missing channels:
   - Dianping/Douyin/group-buying if no data.
   - table count if not configured.
   - cost card insufficient if recipe/POS names unmatched.

Pass criteria:

- No fake health diagnosis when data is missing.
- Actions are specific and time-bucketed where health report exists.
- It can answer "为什么食材占比变高，是采购贵了还是损耗高了？" using current evidence, or says which source is missing.

## Deep Scenario 06-D: Supplier Price Alert

Depth: `deep`

Steps:

1. Use AI chat or supplier price alert page/query:
   - `2026年6月供应商价格预警，基线用90天自身均价`
2. Verify 青花椒 and/or 洗洁精 price jump appears.
3. Verify alert includes:
   - material.
   - supplier.
   - current price.
   - baseline/planned price.
   - delta percentage.
   - explanation from File 03 if saved.
4. Ack/mark reviewed if UI supports it.

Pass criteria:

- Alert comes from real delivery/price rows.
- Baseline mode is explicit.
- Amount masking works by role.

## Medium/Deep Scenario 06-E: Margin Alert And Cost Card

Steps:

1. Ask AI/chat or open dish cost card:
   - `青花椒鱼这个菜的成本卡和毛利率`
2. Verify:
   - POS dish name resolves to recipe/cost where data exists.
   - If qhj coverage is low, the system says `成本数据不足`.
   - no fake margin is returned.
3. If recipe version UI exists, test approval/supersede/idempotent approval.

Expected gap:

- qhj POS name resolution coverage may be low. This is acceptable if honestly displayed.

## Medium Scenario 06-F: CRM/Marketing/Group Buying Analysis

Steps:

1. Test CRM/customer ownership if data exists:
   - customer lifecycle.
   - salesperson attribution.
   - phone PII masking.
2. Test Dianping/Douyin/group-buying/ad ROI if data exists or upload route exists.
3. Ask:
   - `点评分数下降了吗，团购券合不合理，投流ROI怎么样？`

Expected:

- Existing data should produce analysis.
- Missing channels should return coverage notes and next action.

## Exit Hook To 07

```yaml
chainId:
factoryId:
period:
upstreamChainKeys:
  posUploadId:
  financeUploadId:
  deliveryNoteId:
  receiveRecordId:
  reconciliationId:
  materialBatchIds:
  priceAnomalyIds:
kpiDashboard:
  superEvidence:
  salesMaskedEvidence:
  values:
    revenue:
    avgTicket:
    orderCount:
    grossMargin:
    foodCostRatio:
    targetCompletion:
healthReport:
  reportId:
  diagnoses:
  coverageNote:
supplierPriceAlert:
  alertIds:
  materials:
  explanationReadback:
marginCostCard:
  dish:
  result:
  coverageNote:
crmMarketing:
  crmEvidence:
  platformEvidence:
  gaps:
blockingDefects:
```
