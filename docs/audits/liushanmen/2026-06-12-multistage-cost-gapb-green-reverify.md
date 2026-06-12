# 六扇门多段链 Gap B green 复验

日期: 2026-06-12  
部署: prod active=green `v20260612_134001`  
标记: `DEMO-MS2-1781243456`  
账号: `f006_admin`, `f006_production_mgr`  
脚本: `docs/audits/liushanmen/multistage_cost_fix_runner.py`  

## 环境备注

任务指定 `127.0.0.1:10010/api/mobile`，但复验时现场为:

```text
cretas-backend.service       inactive dead    port 10010
cretas-backend-green.service active running   port 10020
```

为避免测到旧 blue/停服实例，本次实际对 active green 跑:

```text
MS_BASE=http://127.0.0.1:10020/api/mobile
MS_MARK_PREFIX=DEMO-MS2-
python docs/audits/liushanmen/multistage_cost_fix_runner.py
```

脚本退出码 `0`。未改业务代码；仅把 runner 增加了 `MS_MARK_PREFIX` / `MS_BASE` 环境变量支持，默认仍是原来的 `DEMO-MS-` / `10010`。

## 结论

| 断言 | 结论 | 证据 |
|---|---|---|
| Gap B: semiB `unit_cost = batch INPUT material 合计 / 产出量` | PASS | semiB `unit_cost=3.0000`, `accumulated_cost=15.00`; IN txn `unit_cost_at_txn=3.0000` |
| Gap A: 多段接口仍能看到二次加工段 | PASS | SECONDARY plans `source_order_id` 非空; `multi-stage-cost stageCount=3` |
| 同码第二批加权 | PASS | WGT `1000kg@1 + 1000kg@3` 后 `unit_cost=2.0000`; 两条 IN txn report_id 不同 |

## API 证据

### 多段成本接口

```text
GET /api/mobile/F006/sales/orders/eb4774e6-590c-4a55-8d59-4a7d13f35d2d/multi-stage-cost
HTTP 200
stageCount=3
```

关键段:

```text
SEMI-B: outputUnitCost=3.0, accumulatedCost=15.0
FG auto WIP: outputUnitCost=3.0, accumulatedCost=15.0
SEMI-A: outputUnitCost=1.0, accumulatedCost=10.0
```

## SQL 回读

### Plans

```sql
select p.id, p.plan_number, p.plan_source_type, p.source_order_id,
       p.secondary_source_wip_id, b.id, b.batch_number
from production_plans p
left join production_batches b on b.production_plan_id=p.id
where b.batch_number like 'DEMO-MS2-1781243456%'
order by p.created_at;
```

```text
e8d7d74e-5fbc-4037-8957-7c5abc71dafd | PLAN-1781243461282-EA3B9A1E | NORMAL    | eb4774e6-590c-4a55-8d59-4a7d13f35d2d | <null> | 2012 | BATCH-CHAIN
79b25dd5-cb9e-42f7-a3a2-0caadcf477da | SEC-F006-20260612-163AEC    | SECONDARY | eb4774e6-590c-4a55-8d59-4a7d13f35d2d | 97     | 2013 | BATCH-CHAIN-B
00c0be04-bd95-494f-a2da-7f9c1cd96dfe | SEC-F006-20260612-32B064    | SECONDARY | eb4774e6-590c-4a55-8d59-4a7d13f35d2d | 98     | 2014 | BATCH-CHAIN-FG
```

### Reports

```sql
select batch_id, id, report_kind, approval_status, input_quantity, output_quantity,
       material_cost, source_wip_no, semi_code
from production_reports
where batch_id in (2012,2013,2014)
order by batch_id,id;
```

```text
2012 | 564 | INPUT  | APPROVED | 10.00 | <null> | 10.00 | <null>                         | <null>
2012 | 565 | OUTPUT | APPROVED | <null>| 10.00  | <null>| <null>                         | DEMO-MS2-1781243456-SEMI-A
2013 | 566 | INPUT  | APPROVED | 5.00  | <null> | 5.00  | DEMO-MS2-1781243456-SEMI-A     | <null>
2013 | 567 | INPUT  | APPROVED | 5.00  | <null> | 10.00 | <null>                         | <null>
2013 | 568 | OUTPUT | APPROVED | <null>| 5.00   | <null>| <null>                         | DEMO-MS2-1781243456-SEMI-B
2014 | 569 | INPUT  | APPROVED | 5.00  | <null> | 15.00 | DEMO-MS2-1781243456-SEMI-B     | <null>
2014 | 570 | OUTPUT | APPROVED | <null>| 5.00   | <null>| <null>                         | <null>
```

Gap B 公式:

```text
batch 2013 INPUT material 合计 = 5.00 + 10.00 = 15.00
semiB 产出 = 5.00 kg
期望 unit_cost = 15.00 / 5.00 = 3.0000
```

### Semi Finished Inventory

```sql
select id, batch_id, intermediate_batch_no, produced_quantity, consumed_quantity,
       available_quantity, unit_cost, accumulated_cost, status
from semi_finished_inventory
where intermediate_batch_no like 'DEMO-MS2-1781243456%';
```

```text
97  | 2012 | DEMO-MS2-1781243456-SEMI-A | produced=10.00   | consumed=5.00 | available=5.00    | unit=1.0000 | acc=10.00   | AVAILABLE
98  | 2013 | DEMO-MS2-1781243456-SEMI-B | produced=5.00    | consumed=5.00 | available=0.00    | unit=3.0000 | acc=15.00   | DEPLETED
100 | 2015 | DEMO-MS2-1781243456-WGT    | produced=2000.00 | consumed=0.00 | available=2000.00 | unit=2.0000 | acc=4000.00 | AVAILABLE
```

### Semi Finished Inventory Transactions

```sql
select s.intermediate_batch_no, t.txn_type, t.source_type, t.quantity,
       t.unit_cost_at_txn, t.report_id
from semi_finished_inventory_transactions t
join semi_finished_inventory s on s.id=t.semi_finished_id
where s.intermediate_batch_no like 'DEMO-MS2-1781243456%'
order by t.id;
```

```text
SEMI-A | IN | PRODUCTION_OUTPUT | 10.000000   | 1.0000 | 565
SEMI-B | IN | PRODUCTION_OUTPUT | 5.000000    | 3.0000 | 568
WGT    | IN | PRODUCTION_OUTPUT | 1000.000000 | 1.0000 | 572
WGT    | IN | PRODUCTION_OUTPUT | 1000.000000 | 3.0000 | 574
```

判定: Gap B 修正版 deep-closed。semiB 库存成本和 IN ledger 成本都等于 `3.0000`；最终 FG INPUT 也读取 semiB 成本形成 `material_cost=15.00`。

