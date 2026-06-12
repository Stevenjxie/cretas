# Gold Production Deep Audit - 2026-06-12

Environment: prod Java `127.0.0.1:10010` via SSH `root@47.100.235.168`.
Accounts: `f006_admin`, `f006_production_mgr`, `f006_moyun`.
Runner: `docs/audits/liushanmen/gold_production_deep_runner.py`.
Write marker: `DEMO-GOLD`.

## Verdict

🔴 OPEN - production cost deep closure is not closed on the real F006 two-point path.

The real chain can create SO, finance approve, create CUSTOMER_ORDER production plan, create/start batch, spawn F006 two-point tasks, submit and approve INPUT/OUTPUT reports. However, `sales_order_items.cost_unit_price` remains `null` after approved two-point reports.

This blocks the requested withdrawal self-heal proof (`value -> null -> new value`) because the first `value` is never written.

## Key Evidence

Created final proving chain:

- SO: `SO-20260612-0008`, id `eb5a6b80-da53-4160-bb2d-8dc32bb247b1`
- SO item: `549`
- Plan: `cbc06022-fa6e-458c-8557-139ccd611d5a`
- Batch: `1989`, `DEMO-GOLD-WITHDRAW-1781225994`
- Tasks: `365` INPUT, `366` OUTPUT
- Reports: `509` INPUT, `510` OUTPUT, `511` second OUTPUT after reversal fast path
- Reversal log: `7`

SQL readback:

```text
SO:
SO-20260612-0008 | FINANCE_APPROVED | item 549 | cost_unit_price=<null>

Plan:
id=cbc06022-fa6e-458c-8557-139ccd611d5a
source_order_id=eb5a6b80-da53-4160-bb2d-8dc32bb247b1
source_order_ids=["eb5a6b80-da53-4160-bb2d-8dc32bb247b1"]
skip_process_reporting=true

Tasks:
365 | process_order=0    | __MATERIAL_INPUT__ | PENDING   | assigned_to=1552
366 | process_order=9999 | __FINAL_OUTPUT__   | COMPLETED | assigned_to=1552

Reports:
509 | task=365 | APPROVED | INPUT  | input=0.50 | material_cost=0.50 | material_batch_refs=[{"unit":"kg","quantity":0.5,"materialBatchId":"0d4c986c-6bfb-4228-b8fa-77364f4b461e"}]
510 | task=366 | APPROVED | OUTPUT | output=10.00 | material_cost=<null> | labor_cost=<null>
511 | task=366 | APPROVED | OUTPUT | output=8.00  | material_cost=<null> | labor_cost=<null>

WIP:
79 | batch=1989 | ...-B1989-S9999-366 | produced=18.00 | available=18.00 | accumulated_cost=<null> | unit_cost=<null> | AVAILABLE

Batch:
1989 | DEMO-GOLD-WITHDRAW-1781225994 | IN_PROGRESS | unit_cost=<null> | total_cost=<null> | material_cost=<null> | labor_cost=<null>

Reversal:
7 | DONE | batch=1989 | submitted_by=1552 | approved_by=<null> | fast_path=true
```

## Repro Steps

1. Login `f006_admin`, `f006_production_mgr`, `f006_moyun`.
2. Create SO with tax, product `1d7fbd73-8797-4933-83f1-46413a45992d`, qty `10kg`, unit price `88`, remark `DEMO-GOLD`.
3. Confirm SO, submit finance review, finance approve.
4. Create CUSTOMER_ORDER production plan with `sourceOrderId/sourceOrderIds`, `processName`, `batchDate`.
5. Create and start batch; spawn tasks.
6. Submit INPUT report on task `365` with priced material batch `0d4c986c-6bfb-4228-b8fa-77364f4b461e`; approve.
7. Submit OUTPUT report on task `366`; approve.
8. Poll `sales_order_items.cost_unit_price`: remains `<null>`.

## Diagnosis

The INPUT report correctly records `material_cost=0.50`. The OUTPUT report produces WIP row `79`, but its `accumulated_cost/unit_cost` remain null, so `ProductionCostUpdatedEvent` is not useful for order backfill.

Likely cause from code inspection: `WipInventoryServiceImpl.calculateTaskCostRollup(factoryId, workProcessTaskId)` aggregates costs only for the same `workProcessTaskId`. In F006 two-point mode, material cost is on task `365`, while final output is task `366`; the output task cannot see the input task cost, so produced WIP has null cost.

## Secondary Findings

- `f006_moyun` submitting against task assigned to `1552` returned 403: `您不是该工序的负责人, 无权报工`. This is valid guard behavior; operator testing needs task assignment first.
- Reversal submitted by the responsible production manager was fast-path `DONE`, so a later approve attempt returned 409 `当前状态: DONE`. That is expected for fast-path reversal, but the self-heal cost assertion is untestable because cost was null before reversal.

## Blocked Assertions

- 🔴 Withdrawal self-heal `value -> null -> new value`: blocked by initial cost backfill failure.
- 🔴 True multi-stage semi-finished chain: not deep-closed; base two-point cost cannot produce a priced WIP unitCost.
- 🔴 Mixed weighted costing: not deep-closed; weighted average cannot be trusted until WIP unitCost is populated.
