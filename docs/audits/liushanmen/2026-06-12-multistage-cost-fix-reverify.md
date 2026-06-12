# 六扇门 multi-stage cost fix 复验

日期: 2026-06-12  
环境: prod F006, blue `v20260612_125911`, `http://127.0.0.1:10010/api/mobile`  
DB: `cretas_prod_db`  
标记: `DEMO-MS-1781240935`  
账号: `f006_admin`, `f006_production_mgr`  
脚本: `docs/audits/liushanmen/multistage_cost_fix_runner.py`

## 结论

| 断言 | 结论 | 证据 |
|---|---|---|
| Gap A: SECONDARY plan `source_order_id` 非空 + `multi-stage-cost` 看到 semiA/semiB | PASS | secondary plans 都回填同一个 sourceOrderId; API `stageCount=3`，包含 semiA 和 semiB |
| Gap B: semiB `unit_cost = (semiA成本 + 本段料/人工) / 产出量` | OPEN | semiA 有成本 1.0000，MS2 INPUT 成本 5+10 已入 report，但 semiB `unit_cost=<null>`，IN txn `unit_cost_at_txn=<null>` |
| 回归: semi B 同码第二批加权累加 | PASS | `1000kg@1 + 1000kg@3` 后 WGT `unit_cost=2.0000`，两条 IN txn report_id 不同 |

脚本返回非 0，原因是 Gap B 未闭环。未改业务代码。

## API 关键响应

### 二次加工计划

```text
POST /api/mobile/F006/processing/secondary-plan
body={"wipId":90,"quantity":"5","productTypeId":"1d7fbd73-8797-4933-83f1-46413a45992d","plannedDate":"2026-06-12"}
HTTP 200
plan=f5db2db9-3646-44ad-8d3a-b5ae05440e04
planNumber=SEC-F006-20260612-3BD855
```

```text
POST /api/mobile/F006/processing/secondary-plan
body={"wipId":91,"quantity":"5","productTypeId":"1d7fbd73-8797-4933-83f1-46413a45992d","plannedDate":"2026-06-12"}
HTTP 200
plan=e4270940-8390-483a-8d8c-f182ce8025ab
planNumber=SEC-F006-20260612-A49F77
```

### 多段成本接口

```text
GET /api/mobile/F006/sales/orders/b2ff7c72-5d43-4fe8-8eb6-df8491aa59e0/multi-stage-cost
HTTP 200
stageCount=3
stages:
  - semiCode=DEMO-MS-1781240935-SEMI-B, outputUnitCost=null, accumulatedCost=null
  - semiCode=1d7fbd73-8797-4933-83f1-46413a45992d-B2005-S5-398, outputUnitCost=null, accumulatedCost=null
  - semiCode=DEMO-MS-1781240935-SEMI-A, outputUnitCost=1.0, accumulatedCost=10.0
```

判定: Gap A 的“能看到二次加工段”已修；但 Gap B 的 semiB 成本仍空，所以多段成本金额未闭环。

## SQL 证据

### 销售订单

```sql
select so.order_number, so.id, so.status, soi.cost_unit_price, so.remark
from sales_orders so
join sales_order_items soi on soi.sales_order_id=so.id
where so.remark like 'DEMO-MS-1781240935%';
```

```text
SO-20260612-0026 | b2ff7c72-5d43-4fe8-8eb6-df8491aa59e0 | FINANCE_APPROVED | 1.0000 | DEMO-MS-1781240935 SO CHAIN
SO-20260612-0027 | 5fbf0e05-c420-4723-997e-5c6c78bfa2ec | FINANCE_APPROVED | 1.0000 | DEMO-MS-1781240935 SO WGT1
SO-20260612-0028 | 34490be6-4ba7-40ea-83b7-27097b434e3f | FINANCE_APPROVED | 2.0000 | DEMO-MS-1781240935 SO WGT2
```

### Plan lineage

```sql
select p.id, p.plan_number, p.plan_source_type, p.source_order_id,
       p.secondary_source_wip_id, b.id, b.batch_number
from production_plans p
left join production_batches b on b.production_plan_id=p.id
where b.batch_number like 'DEMO-MS-1781240935%'
order by p.created_at;
```

```text
c0eb0c26-7300-4991-9017-e5c1be917c02 | PLAN-1781240940965-D4F4D546 | NORMAL    | b2ff7c72-5d43-4fe8-8eb6-df8491aa59e0 | <null> | 2003 | BATCH-CHAIN
f5db2db9-3646-44ad-8d3a-b5ae05440e04 | SEC-F006-20260612-3BD855    | SECONDARY | b2ff7c72-5d43-4fe8-8eb6-df8491aa59e0 | 90     | 2004 | BATCH-CHAIN-B
e4270940-8390-483a-8d8c-f182ce8025ab | SEC-F006-20260612-A49F77    | SECONDARY | b2ff7c72-5d43-4fe8-8eb6-df8491aa59e0 | 91     | 2005 | BATCH-CHAIN-FG
```

判定: Gap A 的 `source_order_id` 非空已通过。

### Reports

```sql
select batch_id, id, report_kind, approval_status, input_quantity, output_quantity,
       material_cost, source_wip_no, semi_code
from production_reports
where batch_id in (2003,2004,2005)
order by batch_id,id;
```

```text
2003 | 545 | INPUT  | APPROVED | 10.00 | <null> | 10.00 | <null>                        | <null>
2003 | 546 | OUTPUT | APPROVED | <null>| 10.00  | <null>| <null>                        | DEMO-MS-1781240935-SEMI-A
2004 | 547 | INPUT  | APPROVED | 5.00  | <null> | 5.00  | DEMO-MS-1781240935-SEMI-A     | <null>
2004 | 548 | INPUT  | APPROVED | 5.00  | <null> | 10.00 | <null>                        | <null>
2004 | 549 | OUTPUT | APPROVED | <null>| 5.00   | <null>| <null>                        | DEMO-MS-1781240935-SEMI-B
2005 | 550 | INPUT  | APPROVED | 5.00  | <null> | <null>| DEMO-MS-1781240935-SEMI-B     | <null>
2005 | 551 | OUTPUT | APPROVED | <null>| 5.00   | <null>| <null>                        | <null>
```

注意: 本段 semiB 的两个 INPUT 成本已经存在: `5.00` 来自 semiA 消耗，`10.00` 来自本段 RAW-B。按断言公式，semiB 产出 5kg 的期望成本是 `(1.0000 * 5 + 10) / 5 = 3.0000`。

### Semi inventory and transactions

```sql
select id, batch_id, intermediate_batch_no, produced_quantity, consumed_quantity,
       available_quantity, unit_cost, accumulated_cost, status
from semi_finished_inventory
where intermediate_batch_no like 'DEMO-MS-1781240935%';
```

```text
90 | 2003 | DEMO-MS-1781240935-SEMI-A | produced=10.00 | consumed=5.00 | available=5.00    | unit=1.0000 | acc=10.00   | AVAILABLE
91 | 2004 | DEMO-MS-1781240935-SEMI-B | produced=5.00  | consumed=5.00 | available=0.00    | unit=<null> | acc=<null> | DEPLETED
93 | 2006 | DEMO-MS-1781240935-WGT    | produced=2000.00 | consumed=0.00 | available=2000.00 | unit=2.0000 | acc=4000.00 | AVAILABLE
```

```sql
select s.intermediate_batch_no, t.txn_type, t.source_type, t.quantity,
       t.unit_cost_at_txn, t.report_id
from semi_finished_inventory_transactions t
join semi_finished_inventory s on s.id=t.semi_finished_id
where s.intermediate_batch_no like 'DEMO-MS-1781240935%'
order by t.id;
```

```text
SEMI-A | IN | PRODUCTION_OUTPUT | 10.000000   | 1.0000 | 546
SEMI-B | IN | PRODUCTION_OUTPUT | 5.000000    | <null> | 549
WGT    | IN | PRODUCTION_OUTPUT | 1000.000000 | 1.0000 | 553
WGT    | IN | PRODUCTION_OUTPUT | 1000.000000 | 3.0000 | 555
```

判定:

- Gap B: OPEN。`SEMI-B` 应为 `3.0000`，实际 `unit_cost=<null>` / `unit_cost_at_txn=<null>`。
- 加权回归: PASS。`WGT` 两笔 IN 正常累计，最终移动均价 `2.0000`。

## 根因边界

本地代码确认 `sourceWipNo` 在三阶段报工中只会保存在 INPUT/legacy，OUTPUT 会被置空；本次 API 也按这个口径落库。`SEMI-B` 的 INPUT reports 已经把上游 WIP 成本和本段原料成本算出来，但 OUTPUT 半成品入账仍没有把同批 INPUT `material_cost` 汇总为 inUnitCost。

因此这不是“测试没带 sourceWipNo”的假阴性；实际断点是 OUTPUT -> `semi_finished_inventory` 成本入账仍未吸收本批 INPUT 成本。

