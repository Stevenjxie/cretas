# 02 Chef Report Demand To Procurement Plan

## Entry Hook From 01

Require:

```yaml
chainId:
period:
factoryId:
posUpload.uploadId:
financeUpload.uploadId:
supplierPurchaseUpload.uploadId:
evidenceRoot:
```

## Purpose

This file tests the part the transcript explicitly describes before warehouse acceptance:

Kitchen/stall people report required ingredients on mobile/small-program style screens. Purchaser/store manager aggregates them into a plan/order. The next day warehouse accepts supplier delivery against that plan.

This is the area most likely still incomplete. The test must prove whether it exists, and if not, leave a precise gap hook for implementation.

## Roles

| Role | Account | Action |
|---|---|---|
| Chef/stall | `qhj_chef_cold`, `qhj_chef_hot` | Report tomorrow's needs |
| Warehouse | `qhj_warehouse_mgr` | View plan for expected delivery |
| Super/store manager | `qhj_prod` | Aggregate/approve/downstream handoff |
| Sales | `qhj_sales_mgr` | Negative access check |

If chef accounts do not exist, use `qhj_operator` only to verify available mobile restaurant functions, and record missing chef-role setup.

## Deep Scenario 02-A: Chef/Stall Reports Tomorrow's Needs

Depth: `deep` if submit + list/detail readback exists. Otherwise `smoke/medium` with gap record.

Steps:

1. Login RN/Expo web/mobile as cold dish chef or operator.
2. Find 餐饮端 entry for 报货/申报计划/采购计划/要货.
3. Create demand for `REPORT_DATE=2026-06-06`, `DELIVERY_DATE=2026-06-07`:
   - 冷菜档口: 黄瓜 12 kg, expected unit price 4.20.
4. Submit.
5. Fresh navigate to demand list/detail.
6. Verify:
   - department/stall is recorded.
   - requester identity is recorded.
   - date is recorded.
   - material, qty, unit, expected price are recorded.

Repeat with hot dish chef:

- 热菜档口: 土豆粉 20 kg, expected unit price 3.50.

Repeat with seasoning/material owner:

- 调料负责人: 青花椒 5 kg, expected unit price 40.00.
- 前厅物料: 洗洁精 1 箱, expected unit price 90.00.

Pass criteria:

- A front-line user can enter demand without typing raw UUIDs.
- Material selection has search or friendly fallback.
- Duplicate click does not create duplicate demand.
- Error toast is sticky and includes the next action.

Expected gap if not implemented:

```yaml
gapId: GAP-02-A
gap: Chef/stall report-demand module not found or cannot create purchase demand
requiredFields:
  - requestDate
  - deliveryDate
  - stallOrDepartment
  - requesterUserId
  - rawMaterialTypeId
  - requestedQuantity
  - unit
  - expectedUnitPrice
  - note
```

## Deep Scenario 02-B: Store Manager Aggregates Procurement Plan

Depth: `deep` if plan submit/readback exists.

Steps:

1. Login as `qhj_prod`.
2. Open restaurant procurement plan/report aggregation page.
3. Filter delivery date `2026-06-07`.
4. Select the four demand lines from Scenario 02-A.
5. Generate procurement plan:
   - supplier: `<SUPPLIER>`.
   - expected delivery date: `2026-06-07`.
   - planned lines as in 00 baseline data.
6. Submit/approve/downward dispatch.
7. Fresh navigate to plan detail.

Pass criteria:

- Plan groups lines by supplier/material.
- System keeps original requester/stall lineage.
- Plan can be used by warehouse as expected-delivery context.
- Supplement order / second confirmation is available or recorded as gap.

Gap to record if absent:

```yaml
gapId: GAP-02-B
gap: Procurement plan aggregation is missing
hookNeededForNextFile:
  procurementPlanId:
  plannedLines:
  planStatus:
```

## Edge Scenarios

| Case | Input | Expected |
|---|---|---|
| Duplicate demand | same chef/material/date submitted twice | 409 or idempotent update, no duplicate |
| Missing quantity | material selected, qty empty | blocked before submit |
| Negative qty | `-1 kg` | blocked before submit |
| Unknown material | free text "新鲜鱼头" | unmatched candidate or forced material choice |
| Supplement order | add `香菜 2 kg` after plan submitted | creates supplement plan with audit trail |
| Second confirmation | supplier calls back changed qty | plan status shows pending second confirm |

## Exit Hook To 03

Fill this before moving to `03-supplier-delivery-inbound-price.md`:

```yaml
chainId:
factoryId:
reportDate:
deliveryDate:
chefDemandIds:
  cold:
  hot:
  seasoning:
  frontMaterial:
procurementPlan:
  planId:
  status:
  supplierId:
  supplierName:
  plannedLines:
    - material:
      qty:
      unit:
      expectedUnitPrice:
      sourceDemandId:
      stallOrDepartment:
gaps:
  - gapId:
    status:
    exactPageOrApiMissing:
evidence:
  screenshots:
  api:
blockingDefects:
```

