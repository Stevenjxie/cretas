# 04 Inventory, Requisition, Wastage, Stocktake, Cost Attribution

## Entry Hook From 03

Require:

```yaml
chainId:
factoryId:
receiveRecord.id:
materialBatches:
priceAnomalies:
evidenceRoot:
```

## Purpose

This validates the part Deng emphasized as "today's wastage can be visible tomorrow":

- Inbound inventory is available to issue/requisition.
- Kitchen/stalls consume materials.
- Wastage is assigned to person/stall.
- Stocktake happens on 10/20/30.
- Cost attribution can be traced by department/stall/person.

## Roles

| Role | Account | Action |
|---|---|---|
| Warehouse | `qhj_warehouse_mgr` | inventory, requisition approval, wastage, stocktake |
| Chef/operator | `qhj_operator` or chef accounts | request/use materials |
| Super | `qhj_prod` | dashboard and full amount readback |
| Sales | `qhj_sales_mgr` | negative amount/cost visibility check |

## Deep Scenario 04-A: Inventory Batch Readback From Inbound

Depth: `deep`

Steps:

1. Login as warehouse.
2. Open inventory/batch screen or API for the material batch IDs from File 03.
3. Verify each batch:
   - material name.
   - supplier.
   - inbound delivery/receive record reference.
   - quantity.
   - unit price if role can view cost.

Pass criteria:

- Inbound note is not just a document; it created deductable inventory.
- Batch quantity matches actual accepted quantity.
- Batch cost visibility respects `canViewCost`.

## Deep Scenario 04-B: Kitchen/Stall Requisition Or Transfer

Depth: `deep` if inventory decreases or downstream consumption record exists. If current flow only approves without stock deduction, record it explicitly.

Steps:

1. Login as chef/operator.
2. Create requisition/transfer:
   - 热菜档口 uses 土豆粉 6 kg.
   - 冷菜档口 uses 黄瓜 3 kg.
   - 调料 uses 青花椒 0.5 kg.
3. Submit.
4. Login as warehouse.
5. Approve requisition/transfer.
6. Fresh readback:
   - requisition status approved.
   - responsible stall/person.
   - batch or material deduction.
   - remaining stock.

Pass criteria:

- Requester and approver are recorded.
- Material cannot exceed available stock without clear error.
- If approval does not deduct stock, mark `GAP-04-B-DEDUCTION`.

## Deep Scenario 04-C: Wastage By Person/Stall

Depth: `deep`

Steps:

1. Login as warehouse or responsible operator.
2. Create wastage:
   - material: 黄瓜.
   - qty: 0.8 kg.
   - reason: `切配损耗`.
   - stall: 冷菜档口.
   - responsible person: test operator/chef.
   - date: `2026-06-07`.
3. Submit/approve as required.
4. Fresh navigate to wastage summary/AI chat.
5. Verify wastage is grouped by person and stall.

Pass criteria:

- Cost fields are hidden for roles without cost permission.
- Rates/counts remain visible where allowed.
- No amount is shown as `0` just because of RBAC masking.

## Deep Scenario 04-D: 10/20/30 Stocktake

Depth: `deep`

Steps:

1. Create stocktake for `2026-06-10`.
2. Count one or more inbound materials:
   - 青花椒 counted 4.2 kg after 0.5 kg use and expected remaining.
3. Submit stocktake.
4. Verify variance:
   - system expected qty.
   - actual qty.
   - variance qty and variance cost if role allows.
5. Repeat or template plan for `2026-06-20` and `2026-06-30`.

Pass criteria:

- Stocktake dates support Deng's 10/20/30 cycle.
- Variance is visible next day in dashboards/summary.
- Variance is not silently ignored.

## Exit Hook To 05

```yaml
chainId:
factoryId:
upstreamDelivery:
  deliveryNoteId:
  receiveRecordId:
  supplierName:
  deliveryTotal:
upstreamUploads:
  posUploadId:
  financeUploadId:
inventoryReadback:
  batchEvidence:
requisitions:
  - id:
    material:
    qty:
    stall:
    requester:
    status:
    stockDeducted:
wastage:
  - id:
    material:
    qty:
    stall:
    responsiblePerson:
    reason:
    costMaskedForNonCostRole:
stocktakes:
  - id:
    stocktakeDate:
    status:
    varianceSummary:
costAttributionInputs:
  deliveryNoteId:
  receiveRecordId:
  requisitionIds:
  wastageIds:
  stocktakeIds:
gaps:
blockingDefects:
```
