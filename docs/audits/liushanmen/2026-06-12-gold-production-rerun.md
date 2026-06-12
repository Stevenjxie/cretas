# 2026-06-12 Gold Production Rerun

Scope: rerun the three previously blocked production deep-close assertions after #779/#780.

Environment: prod `F006`, latest `main` (`git pull --ff-only`: already up to date), real API through `http://127.0.0.1:10010/api/mobile`, SQL readback from `cretas_prod_db`.

Write marker: `DEMO-GOLD-RERUN-*`.

Runner: `docs/audits/liushanmen/gold_production_rerun_runner.py`.

## Gate Verdict

| Target | Verdict | Evidence |
|---|---:|---|
| #779/#780 baseline cost backfill | PASS | Existing `SO-20260612-0008` now has `cost_unit_price=0.0217`. |
| Withdrawal self-heal, approval path | PASS / deep closed | New non-fast-path chain: `0.0500 -> <null> -> 0.0833`, old reports soft-deleted, new reports live. |
| Withdrawal self-heal, fast-path | OPEN / P0 | Fast-path reversal returns `DONE` but skips execution; reports remain live and cost is not cleared. |
| True multi-stage chain via `createSecondaryPlan` | OPEN / P0 | `POST /processing/secondary-plan` returns 500 for real WIP rows. Cannot build raw -> semi A -> semi B -> FG. |
| Mixed moving-average costing | OPEN / P0 | Second approved SEMI output with same `semiCode` is ignored by idempotency guard; WIP unit cost stays `1.0000` instead of weighted average. |

## Baseline

SQL:

```sql
select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.order_number='SO-20260612-0008';
```

Output:

```text
SO-20260612-0008|FINANCE_APPROVED|549|0.0217
```

## 1. Withdrawal Self-Heal

### Approval Path: PASS

This path was forced through 4-eyes approval by submitting reversal as `f006_admin` and approving as `f006_production_mgr`, avoiding fast-path.

Created:

```text
SO:    SO-20260612-0015 / 457e398f-2b71-4f4d-bf29-9fd889fda43a
Plan:  e868a86d-5d49-4965-a22e-c8df52c7f030
Batch: 1995
Reports: 523/524 first run, 525/526 re-report
Reversal log: 9
```

First cost SQL:

```sql
select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.id='457e398f-2b71-4f4d-bf29-9fd889fda43a'
order by soi.id;
```

Output:

```text
SO-20260612-0015|FINANCE_APPROVED|556|0.0500
```

After approved WHOLE_ORDER reversal SQL:

```sql
select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.id='457e398f-2b71-4f4d-bf29-9fd889fda43a'
order by soi.id;
```

Output:

```text
SO-20260612-0015|FINANCE_APPROVED|556|<null>
```

After re-report SQL:

```sql
select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.id='457e398f-2b71-4f4d-bf29-9fd889fda43a'
order by soi.id;
```

Output:

```text
SO-20260612-0015|FINANCE_APPROVED|556|0.0833
```

Report/reversal readback:

```sql
select 'report|'||id||'|'||report_kind||'|'||approval_status||'|'||coalesce(deleted_at::text,'<live>')||'|'||coalesce(material_cost::text,'<null>')||'|'||coalesce(output_quantity::text,'<null>')
from production_reports
where batch_id=1995
order by id;

select 'rev|'||id||'|'||status||'|'||batch_id||'|'||submitted_by||'|'||coalesce(approved_by::text,'<null>')||'|'||coalesce(fast_path::text,'<null>')
from report_reversal_logs
where batch_id=1995
order by id;
```

Output:

```text
report|523|INPUT|APPROVED|2026-06-12 11:02:20.480487|0.50|<null>
report|524|OUTPUT|APPROVED|2026-06-12 11:02:20.480487|<null>|10.00
report|525|INPUT|APPROVED|<live>|1.00|<null>
report|526|OUTPUT|APPROVED|<live>|<null>|8.00

rev|9|DONE|1995|1309|1552|false
```

### Fast-Path: OPEN / P0

Same-user fast-path was exercised by production manager submitting reversal for own fresh reports.

Created:

```text
SO:    SO-20260612-0011 / ff189bad-5adb-41b4-b059-9c93ef6b306c
Plan:  88029424-61ba-4f4d-9718-171c65b91883
Batch: 1990
Reversal log: 8
```

Current SQL:

```sql
select so.order_number||'|'||so.id||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.remark like 'DEMO-GOLD-RERUN-1781233101%'
order by so.created_at;
```

Output excerpt:

```text
SO-20260612-0011|ff189bad-5adb-41b4-b059-9c93ef6b306c|FINANCE_APPROVED|552|0.0500
```

Reports stayed live after `DONE` reversal:

```sql
select 'report|'||batch_id||'|'||id||'|'||report_kind||'|'||approval_status||'|'||coalesce(deleted_at::text,'<live>')||'|'||coalesce(input_quantity::text,'<null>')||'|'||coalesce(output_quantity::text,'<null>')||'|'||coalesce(material_cost::text,'<null>')||'|'||coalesce(output_kind,'<null>')
from production_reports
where batch_id=1990
order by id;
```

Output:

```text
report|1990|513|INPUT|APPROVED|<live>|0.50|<null>|0.50|<null>
report|1990|514|OUTPUT|APPROVED|<live>|<null>|10.00|<null>|FINISHED
report|1990|515|INPUT|APPROVED|<live>|0.50|<null>|1.00|<null>
report|1990|516|OUTPUT|APPROVED|<live>|<null>|8.00|<null>|FINISHED
```

Reversal log:

```sql
select id,batch_id,status,submitted_by,approved_by,reason,created_at,updated_at
from report_reversal_logs
where id=8;
```

Output:

```text
8 | 1990 | DONE | 1552 | <null> | DEMO-GOLD-RERUN-1781233101 WHOLE_ORDER reversal | 2026-06-12 10:58:31.275713 | 2026-06-12 10:58:31.275713
```

Likely cause in code:

```text
backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java:172
backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java:199
backend/java/cretas-api/src/main/java/com/cretas/aims/service/reversal/impl/ReportReversalServiceImpl.java:219
```

The fast-path branch saves `initialStatus=DONE`, then calls `executeReversal(saved.getId(), factoryId)`. `executeReversal` returns immediately when status is already `DONE`, so no soft delete, no reverse txns, no cost clear.

## 2. True Multi-Stage Semi-Finished Chain

Attempted path:

1. Raw material -> SEMI A via normal SO plan.
2. `POST /api/mobile/F006/processing/secondary-plan` with `wipId=81`, `quantity=1`, same product type.
3. Repeated with WIP id `82` to confirm not data-specific.

SEMI A exists and has unit cost:

```sql
select id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>')
from semi_finished_inventory
where factory_id='F006'
  and intermediate_batch_no='DEMO-GOLD-RERUN-1781233101-SEMI-A'
  and deleted_at is null;
```

Output:

```text
81|DEMO-GOLD-RERUN-1781233101-SEMI-A|10.00|10.00|0.1000|1.00
```

API result:

```text
POST /api/mobile/F006/processing/secondary-plan
body: {"wipId":81,"quantity":"1","productTypeId":"1d7fbd73-8797-4933-83f1-46413a45992d","plannedDate":"2026-06-12"}
HTTP 500 trace 23E2738C

POST /api/mobile/F006/processing/secondary-plan
body: {"wipId":82,"quantity":"1","productTypeId":"1d7fbd73-8797-4933-83f1-46413a45992d","plannedDate":"2026-06-12"}
HTTP 500 trace C73DD228
```

Prod log stack:

```text
[23E2738C] 数据访问异常: Identifier of entity 'com.cretas.aims.entity.ProductionPlan' must be manually assigned before calling 'persist()'
org.springframework.orm.jpa.JpaSystemException: Identifier of entity 'com.cretas.aims.entity.ProductionPlan' must be manually assigned before calling 'persist()'
  at com.cretas.aims.service.impl.ProductionPlanServiceImpl.createSecondaryPlan(ProductionPlanServiceImpl.java:1978)
  at com.cretas.aims.controller.ReportReversalController.createSecondaryPlan(ReportReversalController.java:176)
Caused by: org.hibernate.id.IdentifierGenerationException: Identifier of entity 'com.cretas.aims.entity.ProductionPlan' must be manually assigned before calling 'persist()'
```

Related code point:

```text
backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java:1978
```

Current endpoint readback for the MS order remains single-stage because secondary plan cannot be created:

```text
GET /api/mobile/F006/sales/orders/5ccf4e0e-e9f5-44ef-8b54-5d0fadfcf52b/multi-stage-cost
HTTP 200 stageCount=1
stage: DEMO-GOLD-RERUN-1781233101-SEMI-A, outputUnitCost=0.1000, accumulatedCost=1.00
```

## 3. Mixed Moving-Average Costing

Attempted scenario:

1. First batch produces `DEMO-GOLD-RERUN-1781233101-WGT`, 1000 kg at unit cost 1.0000.
2. Second batch reports another 1000 kg under the same `semiCode`, material cost 3000.00.
3. Expected moving average if old 1000 + new 1000: `(1000*1 + 1000*3) / 2000 = 2.0000`.
4. Customer target after consuming 500 old first would be `(500*1 + 1000*3) / 1500 = 2.3333`; this could not be fully executed because `createSecondaryPlan` 500s, but the more basic same-code accumulation already fails.

Reports:

```sql
select id,batch_id,report_kind,approval_status,material_cost,output_quantity,output_kind,semi_code,deleted_at
from production_reports
where batch_id in (1993,1994)
order by id;
```

Output:

```text
519 | 1993 | INPUT  | APPROVED | 1000.00 |        |      | 
520 | 1993 | OUTPUT | APPROVED |         | 1000.00 | SEMI | DEMO-GOLD-RERUN-1781233101-WGT
521 | 1994 | INPUT  | APPROVED | 3000.00 |        |      |
522 | 1994 | OUTPUT | APPROVED |         | 1000.00 | SEMI | DEMO-GOLD-RERUN-1781233101-WGT
```

WIP stayed at first cost:

```sql
select id,intermediate_batch_no,produced_quantity,consumed_quantity,available_quantity,unit_cost,accumulated_cost,status
from semi_finished_inventory
where intermediate_batch_no='DEMO-GOLD-RERUN-1781233101-WGT';
```

Output:

```text
82 | DEMO-GOLD-RERUN-1781233101-WGT | 1000.00 | 0.00 | 1000.00 | 1.0000 | 1000.00 | AVAILABLE
```

Only one IN txn exists:

```sql
select 'txn|'||t.id||'|'||t.semi_finished_id||'|'||t.txn_type||'|'||t.source_type||'|'||coalesce(t.source_ref,'<null>')||'|'||t.quantity||'|'||coalesce(t.unit_cost_at_txn::text,'<null>')||'|'||coalesce(t.report_id::text,'<null>')
from semi_finished_inventory_transactions t
join semi_finished_inventory s on s.id=t.semi_finished_id
where s.intermediate_batch_no='DEMO-GOLD-RERUN-1781233101-WGT'
order by t.id;
```

Output:

```text
txn|4|82|IN|PRODUCTION_OUTPUT|DEMO-GOLD-RERUN-1781233101-WGT|1000.000000|1.0000|520
```

Likely cause in code:

```text
backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java:176
backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl/WipInventoryServiceImpl.java:235
```

`postSemiOutputLedger` uses `findByFactoryIdAndSourceRefAndTxnType(factoryId, semiCode, IN)` as its idempotency guard, while the IN txn `sourceRef` is also `semiCode`. Therefore a legitimate second production into the same semi-finished code is treated as duplicate and skipped.

## Organizer Bugs

1. `BUG-GOLD-RERUN-FASTPATH-REVERSAL`: fast-path whole-order reversal returns `DONE` without executing reversal. Repro: batch `1990`, log `8`. Non-fast-path batch `1995`, log `9`, proves the execution body itself works.
2. `BUG-GOLD-RERUN-SECONDARY-PLAN-500`: `createSecondaryPlan` returns 500 for valid WIP ids `81` and `82`; stack says `ProductionPlan.id` was not assigned before persist.
3. `BUG-GOLD-RERUN-WEIGHTED-AVG-SKIP`: same `semiCode` second production is skipped by idempotency guard; no second IN txn, no moving average.

## Honest Conclusion

The #779/#780 cost propagation fix is live: two-point INPUT material cost now rolls into OUTPUT and backfills SO cost. The withdrawal self-heal is deep closed only on the normal approval path.

The full requested closure is not yet complete because three real production blockers remain: fast-path reversal skip, secondary-plan 500, and same-code moving-average skip. These are real prod-path issues found with live DEMO data, not test-data gaps.
