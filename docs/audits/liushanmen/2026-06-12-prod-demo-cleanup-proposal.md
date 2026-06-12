# Prod DEMO Cleanup Proposal (read-only)

Date: 2026-06-12  
Target: `root@47.100.235.168`, PostgreSQL `cretas_prod_db`, factory `F006`  
Scope: proposal only. No `DELETE`, `UPDATE`, or `TRUNCATE` was executed in this session.

## Read-only Proof

- Worktree: `C:\Users\Steve\cretas-prod-demo-cleanup`
- Branch: `chore/prod-demo-cleanup-proposal` from `origin/main`
- SSH target used: `root@47.100.235.168`
- DB connection proof:

```sql
SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
-- cretas_prod_db | cretas_user | ::1 | 5432
```

- Only these DB command classes were executed: `SELECT` against application tables and `information_schema` / `pg_constraint`.
- Two query errors occurred while discovering schema (`material_batches.remark` missing; mixed bigint/varchar FK comparison). Both were failed `SELECT` statements.
- No SQL write statement was sent to the database.

## Matching Rules Used

Strict requested prefix rule returned zero rows:

```sql
-- Generated across all public tables with factory_id and text-like columns:
WHERE factory_id = 'F006'
  AND (<text column>::text ILIKE ANY (ARRAY[
    'DEMO-%','DEMO-FE%','FE2%','FE3%','MS%','MS2%','GOLD%','RERUN%','REVERIFY%'
  ]))
```

Because repo audit runners and UI evidence use values such as `DEMO-GOLD-RERUN-*`, `DEMO-GOLD-REVERIFY-*`, `DEMO-GOLD-WITHDRAW-*`, and `DEMO-MR*`, the actionable candidate inventory uses a broader contains-token rule plus FK-linked child rows:

```sql
ILIKE '%DEMO%' OR ILIKE '%GOLD%' OR ILIKE '%RERUN%' OR ILIKE '%REVERIFY%'
```

The cleanup should be reviewed before execution because `%MS%` / `%GOLD%` alone can be over-broad in generic text fields. The proposed deletes below are anchored on `factory_id='F006'` and known DEMO parent objects to reduce collateral risk.

## Table Inventory

Counts below use the linked-candidate CTE shown in the next section. `Session` means `created_at >= TIMESTAMP '2026-06-12 00:00:00'`; `Earlier` means older than that or null.

| Table | Candidate rows | Session | Earlier | First created_at | Last created_at |
|---|---:|---:|---:|---|---|
| disposal_records | 1 | 1 | 0 | 2026-06-12 00:53:13.670508 | 2026-06-12 00:53:13.670508 |
| finished_goods_batches | 7 | 5 | 2 | 2026-06-11 14:03:03.61085 | 2026-06-12 12:16:17.337452 |
| material_batches | 50 | 46 | 4 | 2026-06-11 16:14:08.898303 | 2026-06-12 18:23:31.848083 |
| material_consumptions | 5 | 4 | 1 | 2026-06-11 14:03:03.596109 | 2026-06-12 08:42:50.811552 |
| production_batches | 58 | 39 | 19 | 2026-06-02 19:00:34.006434 | 2026-06-12 18:23:32.052852 |
| production_plans | 83 | 71 | 12 | 2026-06-10 12:21:46.108897 | 2026-06-12 18:23:32.022315 |
| production_reports | 114 | 87 | 27 | 2026-06-10 12:42:37.012722 | 2026-06-12 18:24:23.860052 |
| purchase_order_items | 20 | 11 | 9 | 2026-05-14 04:18:19.955084 | 2026-06-12 13:18:24.241049 |
| purchase_orders | 20 | 11 | 9 | 2026-05-14 04:18:19.955084 | 2026-06-12 13:18:24.240798 |
| purchase_receive_items | 17 | 11 | 6 | 2026-06-10 12:17:23.935793 | 2026-06-12 13:20:07.475685 |
| purchase_receive_records | 17 | 11 | 6 | 2026-06-10 12:17:23.935547 | 2026-06-12 13:20:07.472006 |
| quality_inspections | 3 | 2 | 1 | 2026-06-11 21:23:55.500538 | 2026-06-12 08:42:50.838618 |
| report_reversal_logs | 11 | 7 | 4 | 2026-06-10 12:21:09.497248 | 2026-06-12 13:16:04.167228 |
| sales_delivery_items | 4 | 4 | 0 | 2026-06-12 12:00:25.706942 | 2026-06-12 12:16:24.899798 |
| sales_delivery_records | 4 | 4 | 0 | 2026-06-12 12:00:25.704437 | 2026-06-12 12:16:24.899601 |
| sales_order_items | 53 | 42 | 11 | 2026-06-10 12:20:57.83522 | 2026-06-12 18:23:31.889887 |
| sales_orders | 53 | 42 | 11 | 2026-06-10 12:20:57.822441 | 2026-06-12 18:23:31.886917 |
| semi_finished_inventory | 31 | 27 | 4 | 2026-06-11 21:56:59.80161 | 2026-06-12 15:30:09.777009 |
| semi_finished_inventory_transactions | 19 | 17 | 2 | 2026-06-11 22:34:38.811921 | 2026-06-12 13:51:31.812195 |
| work_process_tasks | 117 | 78 | 39 | 2026-06-10 12:42:19.68879 | 2026-06-12 18:23:32.11039 |

Zero-row related tables checked in this pass: `batch_work_sessions`, `batch_equipment_usage`, `quality_defects`, `quality_return_orders`, `rework_records`.

## Inventory SELECT SQL

```sql
WITH
demo_so AS (
  SELECT id::text id
  FROM sales_orders
  WHERE factory_id='F006' AND (order_number ILIKE '%DEMO%' OR remark ILIKE '%DEMO%')
),
demo_soi AS (
  SELECT soi.id::text id
  FROM sales_order_items soi
  LEFT JOIN sales_orders so ON so.id=soi.sales_order_id
  WHERE so.id::text IN (SELECT id FROM demo_so)
     OR (so.factory_id='F006' AND (soi.remark ILIKE '%DEMO%' OR soi.product_name ILIKE '%DEMO%'))
),
demo_po AS (
  SELECT po.id::text id
  FROM purchase_orders po
  WHERE po.factory_id='F006'
    AND (
      po.order_number ILIKE '%DEMO%'
      OR po.remark ILIKE '%DEMO%'
      OR po.contract_number ILIKE '%DEMO%'
      OR po.ai_invocation_metadata::text ILIKE '%DEMO%'
      OR po.order_number ILIKE '%GOLD%'
      OR po.remark ILIKE '%GOLD%'
      OR po.contract_number ILIKE '%GOLD%'
      OR po.ai_invocation_metadata::text ILIKE '%GOLD%'
      OR po.order_number ILIKE '%RERUN%'
      OR po.remark ILIKE '%RERUN%'
      OR po.contract_number ILIKE '%RERUN%'
      OR po.ai_invocation_metadata::text ILIKE '%RERUN%'
      OR po.order_number ILIKE '%REVERIFY%'
      OR po.remark ILIKE '%REVERIFY%'
      OR po.contract_number ILIKE '%REVERIFY%'
      OR po.ai_invocation_metadata::text ILIKE '%REVERIFY%'
      OR po.sales_order_id::text IN (SELECT id FROM demo_so)
    )
),
demo_poi AS (
  SELECT poi.id::text id
  FROM purchase_order_items poi
  LEFT JOIN purchase_orders po ON po.id=poi.purchase_order_id
  WHERE poi.purchase_order_id::text IN (SELECT id FROM demo_po)
     OR (
       po.factory_id='F006'
       AND (
         poi.remark ILIKE '%DEMO%'
         OR poi.material_name ILIKE '%DEMO%'
         OR poi.remark ILIKE '%GOLD%'
         OR poi.material_name ILIKE '%GOLD%'
         OR poi.remark ILIKE '%RERUN%'
         OR poi.material_name ILIKE '%RERUN%'
         OR poi.remark ILIKE '%REVERIFY%'
         OR poi.material_name ILIKE '%REVERIFY%'
       )
     )
),
demo_prr AS (
  SELECT r.id::text id
  FROM purchase_receive_records r
  WHERE r.factory_id='F006'
    AND (
      r.purchase_order_id::text IN (SELECT id FROM demo_po)
      OR r.receive_number ILIKE '%DEMO%'
      OR r.remark ILIKE '%DEMO%'
      OR r.ai_invocation_metadata::text ILIKE '%DEMO%'
      OR r.receive_number ILIKE '%GOLD%'
      OR r.remark ILIKE '%GOLD%'
      OR r.ai_invocation_metadata::text ILIKE '%GOLD%'
      OR r.receive_number ILIKE '%RERUN%'
      OR r.remark ILIKE '%RERUN%'
      OR r.ai_invocation_metadata::text ILIKE '%RERUN%'
      OR r.receive_number ILIKE '%REVERIFY%'
      OR r.remark ILIKE '%REVERIFY%'
      OR r.ai_invocation_metadata::text ILIKE '%REVERIFY%'
    )
),
demo_pri AS (
  SELECT i.id::text id
  FROM purchase_receive_items i
  LEFT JOIN purchase_receive_records r ON r.id=i.receive_record_id
  WHERE i.receive_record_id::text IN (SELECT id FROM demo_prr)
     OR (
       r.factory_id='F006'
       AND (
         i.remark ILIKE '%DEMO%'
         OR i.material_name ILIKE '%DEMO%'
         OR i.remark ILIKE '%GOLD%'
         OR i.material_name ILIKE '%GOLD%'
         OR i.remark ILIKE '%RERUN%'
         OR i.material_name ILIKE '%RERUN%'
         OR i.remark ILIKE '%REVERIFY%'
         OR i.material_name ILIKE '%REVERIFY%'
       )
     )
),
demo_mb AS (
  SELECT id::text id
  FROM material_batches
  WHERE factory_id='F006' AND (batch_number ILIKE '%DEMO%' OR notes ILIKE '%DEMO%')
),
demo_plan AS (
  SELECT p.id::text id
  FROM production_plans p
  WHERE p.factory_id='F006'
    AND (
      p.plan_number ILIKE '%DEMO%'
      OR p.notes ILIKE '%DEMO%'
      OR p.process_name ILIKE '%DEMO%'
      OR p.customer_order_number ILIKE '%DEMO%'
      OR p.source_order_id::text IN (SELECT id FROM demo_so)
      OR EXISTS (SELECT 1 FROM demo_so s WHERE p.source_order_ids::text ILIKE '%' || s.id || '%')
    )
),
demo_batch AS (
  SELECT b.id::text id
  FROM production_batches b
  WHERE b.factory_id='F006'
    AND (
      b.batch_number ILIKE '%DEMO%'
      OR b.notes ILIKE '%DEMO%'
      OR b.product_name ILIKE '%DEMO%'
      OR b.production_plan_id::text IN (SELECT id FROM demo_plan)
    )
),
demo_report AS (
  SELECT r.id::text id
  FROM production_reports r
  WHERE r.factory_id='F006'
    AND (
      r.batch_id::text IN (SELECT id FROM demo_batch)
      OR r.notes ILIKE '%DEMO%'
      OR r.source_wip_no ILIKE '%DEMO%'
      OR r.semi_code ILIKE '%DEMO%'
      OR r.intermediate_batch_no ILIKE '%DEMO%'
      OR r.product_name ILIKE '%DEMO%'
    )
),
demo_rev AS (
  SELECT rr.id::text id
  FROM report_reversal_logs rr
  WHERE rr.factory_id='F006'
    AND (
      rr.batch_id::text IN (SELECT id FROM demo_batch)
      OR rr.plan_id::text IN (SELECT id FROM demo_plan)
      OR rr.reason ILIKE '%DEMO%'
    )
),
demo_sfi AS (
  SELECT s.id::text id
  FROM semi_finished_inventory s
  WHERE s.factory_id='F006'
    AND (s.batch_id::text IN (SELECT id FROM demo_batch) OR s.intermediate_batch_no ILIKE '%DEMO%')
),
demo_qi AS (
  SELECT id::text id
  FROM quality_inspections
  WHERE production_batch_id::text IN (SELECT id FROM demo_batch)
     OR material_batch_id::text IN (SELECT id FROM demo_mb)
),
demo_rework AS (
  SELECT id::text id
  FROM rework_records
  WHERE material_batch_id::text IN (SELECT id FROM demo_mb)
     OR production_batch_id::text IN (SELECT id FROM demo_batch)
     OR quality_inspection_id::text IN (SELECT id FROM demo_qi)
),
demo_delivery AS (
  SELECT id::text id
  FROM sales_delivery_records
  WHERE factory_id='F006' AND sales_order_id::text IN (SELECT id FROM demo_so)
)
SELECT 'sales_orders' table_name, count(*) FROM demo_so
UNION ALL SELECT 'sales_order_items', count(*) FROM demo_soi
UNION ALL SELECT 'purchase_orders', count(*) FROM demo_po
UNION ALL SELECT 'purchase_order_items', count(*) FROM demo_poi
UNION ALL SELECT 'purchase_receive_records', count(*) FROM demo_prr
UNION ALL SELECT 'purchase_receive_items', count(*) FROM demo_pri
UNION ALL SELECT 'material_batches', count(*) FROM demo_mb
UNION ALL SELECT 'production_plans', count(*) FROM demo_plan
UNION ALL SELECT 'production_batches', count(*) FROM demo_batch
UNION ALL SELECT 'production_reports', count(*) FROM demo_report
UNION ALL SELECT 'report_reversal_logs', count(*) FROM demo_rev
UNION ALL SELECT 'semi_finished_inventory', count(*) FROM demo_sfi
UNION ALL SELECT 'quality_inspections', count(*) FROM demo_qi
UNION ALL SELECT 'sales_delivery_records', count(*) FROM demo_delivery;
```

## Recommended Delete Order

Child tables first:

1. `sales_delivery_items`
2. `purchase_receive_items`
3. `purchase_order_items`
4. `semi_finished_inventory_transactions`
5. `disposal_records`
6. `material_consumptions`
7. `production_plan_batch_usages`
8. `work_process_tasks`
9. `production_reports`
10. `quality_inspections`
11. `finished_goods_batches`
12. `sales_delivery_records`
13. `purchase_receive_records`
14. `report_reversal_logs`
15. `semi_finished_inventory`
16. `production_batches`
17. `purchase_orders`
18. `sales_order_items`
19. `production_plans`
20. `material_batches`
21. `sales_orders`

## Suggested DELETE SQL (do not run without approval)

Use the same CTE definitions from "Inventory SELECT SQL" before each statement or run in one reviewed transaction.

```sql
DELETE FROM sales_delivery_items
WHERE delivery_record_id::text IN (SELECT id FROM demo_delivery);

DELETE FROM purchase_receive_items
WHERE id::text IN (SELECT id FROM demo_pri);

DELETE FROM purchase_order_items
WHERE id::text IN (SELECT id FROM demo_poi);

DELETE FROM semi_finished_inventory_transactions
WHERE semi_finished_id::text IN (SELECT id FROM demo_sfi)
   OR report_id::text IN (SELECT id FROM demo_report);

DELETE FROM disposal_records
WHERE material_batch_id::text IN (SELECT id FROM demo_mb)
   OR production_batch_id::text IN (SELECT id FROM demo_batch)
   OR quality_inspection_id::text IN (SELECT id FROM demo_qi)
   OR rework_record_id::text IN (SELECT id FROM demo_rework);

DELETE FROM material_consumptions
WHERE production_batch_id::text IN (SELECT id FROM demo_batch)
   OR production_plan_id::text IN (SELECT id FROM demo_plan)
   OR batch_id::text IN (SELECT id FROM demo_mb)
   OR notes ILIKE '%DEMO%';

DELETE FROM production_plan_batch_usages
WHERE production_plan_id::text IN (SELECT id FROM demo_plan)
   OR material_batch_id::text IN (SELECT id FROM demo_mb);

DELETE FROM work_process_tasks
WHERE production_batch_id::text IN (SELECT id FROM demo_batch)
   OR (factory_id='F006' AND notes ILIKE '%DEMO%');

DELETE FROM production_reports
WHERE id::text IN (SELECT id FROM demo_report);

DELETE FROM quality_inspections
WHERE id::text IN (SELECT id FROM demo_qi);

DELETE FROM finished_goods_batches
WHERE factory_id='F006'
  AND (
    production_plan_id::text IN (SELECT id FROM demo_plan)
    OR reversal_log_id::text IN (SELECT id FROM demo_rev)
    OR batch_number ILIKE '%DEMO%'
    OR remark ILIKE '%DEMO%'
    OR inbound_remark ILIKE '%DEMO%'
    OR product_name ILIKE '%DEMO%'
  );

DELETE FROM sales_delivery_records
WHERE id::text IN (SELECT id FROM demo_delivery);

DELETE FROM purchase_receive_records
WHERE id::text IN (SELECT id FROM demo_prr);

DELETE FROM report_reversal_logs
WHERE id::text IN (SELECT id FROM demo_rev);

DELETE FROM semi_finished_inventory
WHERE id::text IN (SELECT id FROM demo_sfi);

DELETE FROM production_batches
WHERE id::text IN (SELECT id FROM demo_batch);

DELETE FROM purchase_orders
WHERE id::text IN (SELECT id FROM demo_po);

DELETE FROM sales_order_items
WHERE id::text IN (SELECT id FROM demo_soi);

DELETE FROM production_plans
WHERE id::text IN (SELECT id FROM demo_plan);

DELETE FROM material_batches
WHERE id::text IN (SELECT id FROM demo_mb);

DELETE FROM sales_orders
WHERE id::text IN (SELECT id FROM demo_so);
```

## Soft Delete vs Hard Delete

Recommended execution posture:

- Prefer hard delete for this cleanup after approval, because these rows are synthetic DEMO pollution and many linked rows are operational ledger artifacts. Keeping them soft-deleted can continue to pollute analytics if any query misses `deleted_at IS NULL`.
- Before hard delete, run the inventory CTE inside a transaction and record row counts again.
- If product/support needs an audit trail, export the candidate IDs and counts before deletion rather than relying on soft-deleted rows in prod.
- Do not use generic `%GOLD%` / `%MS%` standalone deletion without the `factory_id='F006'` and parent-link CTE guards above.

## Risk Notes

- `production_reports` has more linked rows (114) than direct DEMO-text rows (27) because reports attached to DEMO batches may not repeat the marker in their own text fields.
- `production_plans` has more linked rows (83) than direct DEMO-text rows (74) because some plans are linked to DEMO sales orders.
- `purchase_orders` / `purchase_receive_records` include 20 / 17 linked rows respectively. Eleven rows in each table were created on 2026-06-12; older rows go back to 2026-05-14 for purchase orders and should be reviewed separately before cleanup.
- The oldest linked candidate is `production_batches.created_at = 2026-06-02 19:00:34.006434`; review that batch before executing cleanup because it predates this session by 10 days.
- `finished_goods_batches` includes two earlier linked rows from 2026-06-11. Verify no customer delivery depends on them; the proposed order removes `sales_delivery_items` first.
