# Gold Final Gate Summary - 2026-06-12

## Completed

1. Final regression: `2026-06-12-gold-regression-live.md`
   - #774 confirm and material-receipt live pass.
   - #775 plan-level completion generated FG inventory and did not roll back.
   - #776 cashier approved payment bank info present.
   - #777 disposal idempotency and label prefix pass.
   - BOM unit regression no longer blocks receive confirm.

2. Production deep: `2026-06-12-gold-production-deep.md`
   - 🔴 OPEN: F006 two-point cost rollup does not deep-close. Input report has material cost; output task/WIP/SO cost stay null.
   - This blocks withdrawal self-heal, true multi-stage cost, and mixed weighted costing proof.

3. WeChat report closure: `2026-06-12-gold-wechat-report-closure.md`
   - RN real-photo operator uploads pass for two different operators.
   - OCR artifacts are assistive only.
   - Full 6.1-6.3 real-number cost closure remains blocked by the two-point cost rollup issue.

4. RN other roles: `2026-06-12-gold-rn-role-deep.md`
   - Procurement PO, warehouse inbound, sales SO confirmation all live-write and SQL-readback pass.
   - Foolproof 5 pass.

## Organizer Gate

Primary blocker to assign:

```text
F006 two-point costing rollup:
INPUT task material_cost is not rolled into OUTPUT task WIP unit_cost.
Result: semi_finished_inventory.unit_cost null, production batch cost null, sales_order_items.cost_unit_price null.
```

Observed final chain:

```text
SO SO-20260612-0008
Plan cbc06022-fa6e-458c-8557-139ccd611d5a
Batch 1989 DEMO-GOLD-WITHDRAW-1781225994
INPUT report 509 task 365 material_cost=0.50
OUTPUT reports 510/511 task 366 material_cost/labor_cost null
WIP 79 unit_cost null
SO item 549 cost_unit_price null
```

Secondary non-blocking observation:

```text
Plan-level FG auto-inbound succeeded for #775, but async transfer voucher creation logged a zero debit/credit check-constraint failure. Main transaction was not rolled back.
```
