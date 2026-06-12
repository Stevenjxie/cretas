# 六扇门 Gold Rerun 3P0 复验

日期: 2026-06-12  
环境: prod F006, `http://127.0.0.1:10010/api/mobile`, SQL `cretas_prod_db`  
代码: latest `main`, `fix/gold-rerun-3p0` 已 merge/deploy  
标记: `DEMO-GOLD-REVERIFY-1781235965`  
工具: `docs/audits/liushanmen/gold_production_reverify_runner.py` + 续跑 SQL/API  

## 阻断项置顶

`production_mgr` 对二次加工批次真实工序任务报工仍会 403: `您不是该工序的负责人, 无权报工`。同一批次改用 `f006_admin` 可以继续报工并审批。因此这不是本轮三条 P0 成本修复的直接失败，但会影响用给定 `f006_production_mgr` 完整跑多段链。

二次加工计划已不再 500，但“真多段链 raw -> semi A -> semi B -> FG”仍未 deep-close: `semi B` 的半成品库存成本仍是 null，`multi-stage-cost` 只返回 1 段。根因证据见“延伸验证: 真多段链”。

## 运行备注

复验开始时 prod 10010 `cretas-backend` 处于 inactive，10011 仍在监听。已执行 `systemctl start cretas-backend` 恢复 10010；复验后确认:

```text
systemctl is-active cretas-backend => active
ss -lntp => *:10010 users:(("java",pid=2124829,...))
```

未改业务代码。

## 结论

| 断言 | 结论 | 关键证据 |
|---|---|---|
| 1 fast-path 撤回真执行 + null -> new value | PASS / deep-closed | fast-path reversal `DONE`; 老 reports 软删; SO 成本重报后从 0.0500 变 0.0833 |
| 2 二次加工计划不再 500 | PASS / deep-closed | `POST /processing/secondary-plan` HTTP 200; DB 新增 `plan_source_type=SECONDARY` |
| 3 同码第二批加权累加 | PASS / deep-closed | 同一 semiCode 两条 IN，report_id 不同; unit_cost = 2.0000 |
| 延伸: 二次加工串真多段成本 | OPEN | MS2/MS3 报工 API 200，但 semi B unit_cost null; multi-stage-cost stageCount=1 |

## 断言 1: Fast-Path 撤回

链路: `f006_production_mgr` 提交本人 fast-path `WHOLE_ORDER` 撤回，随后重报不同产量/成本。

API:

```text
POST /api/mobile/F006/processing/batches/1996/reversal
body={"reason":"DEMO-GOLD-REVERIFY-1781235965 fast-path WHOLE_ORDER reversal","remark":"DEMO-GOLD-REVERIFY-1781235965","reversalScope":"WHOLE_ORDER"}
HTTP 200, reversal id=10
```

SQL:

```sql
select so.order_number, soi.cost_unit_price
from sales_orders so join sales_order_items soi on soi.sales_order_id=so.id
where so.order_number='SO-20260612-0016';
```

```text
after first report: SO-20260612-0016 | 0.0500
after reversal:     SO-20260612-0016 | <null>
after re-report:    SO-20260612-0016 | 0.0833
```

```sql
select id, report_kind, approval_status, deleted_at
from production_reports
where batch_id=1996
order by id;
```

```text
527 INPUT  APPROVED deleted=2026-06-12 11:46:16.182043
528 OUTPUT APPROVED deleted=2026-06-12 11:46:16.182043
529 INPUT  APPROVED deleted=<live>
530 OUTPUT APPROVED deleted=<live>
```

```sql
select id, batch_id, status, submitted_by, approved_by, fast_path, reverted_txn_ids
from report_reversal_logs
where batch_id=1996;
```

```text
10 | 1996 | DONE | 1552 | <null> | true | []
```

判定: PASS。fast-path 不再只写 DONE 状态，实际执行了软删和成本清空；重报后成本回填为新值。

## 断言 2: 二次加工计划不再 500

API:

```text
POST /api/mobile/F006/processing/secondary-plan
body={"wipId":85,"quantity":"1","productTypeId":"1d7fbd73-8797-4933-83f1-46413a45992d","plannedDate":"2026-06-12"}
HTTP 200
plan.id=20017b0c-163f-4fc6-a65e-2a4e07da14c4
planNumber=SEC-F006-20260612-92CC61
```

SQL:

```sql
select id, plan_number, plan_source_type, secondary_source_wip_id, source_order_id
from production_plans
where id='20017b0c-163f-4fc6-a65e-2a4e07da14c4';
```

```text
20017b0c-163f-4fc6-a65e-2a4e07da14c4 | SEC-F006-20260612-92CC61 | SECONDARY | 85 | <null>
```

判定: PASS。原 500/空 id 问题已修，DB 有 `SECONDARY` 计划。

## 断言 3: 同码第二批加权累加

场景: 同一 `semiCode=DEMO-GOLD-REVERIFY-1781235965-WGT`，第一批 `1000kg@1`，第二批 `1000kg@3`。

SQL:

```sql
select s.intermediate_batch_no, t.txn_type, t.quantity, t.unit_cost_at_txn, t.report_id
from semi_finished_inventory_transactions t
join semi_finished_inventory s on s.id=t.semi_finished_id
where s.intermediate_batch_no='DEMO-GOLD-REVERIFY-1781235965-WGT'
order by t.id;
```

```text
DEMO-GOLD-REVERIFY-1781235965-WGT | IN | 1000.000000 | 1.0000 | 534
DEMO-GOLD-REVERIFY-1781235965-WGT | IN | 1000.000000 | 3.0000 | 536
```

```sql
select intermediate_batch_no, produced_quantity, available_quantity, unit_cost, accumulated_cost
from semi_finished_inventory
where intermediate_batch_no='DEMO-GOLD-REVERIFY-1781235965-WGT';
```

```text
DEMO-GOLD-REVERIFY-1781235965-WGT | 2000.00 | 2000.00 | 2.0000 | 4000.00
```

判定: PASS。第二批没有被旧同码幂等判断吞掉，移动加权均价正确。

## 延伸验证: 真多段链

链路: `raw -> MS-A -> secondary-plan MS2 -> MS-B -> secondary-plan MS3 -> FG`。  
初次用 `f006_production_mgr` 报 MS2 时 403，改用 `f006_admin` 续跑后所有 MS2/MS3 报工 API 均 200。

API:

```text
POST /api/mobile/F006/processing/secondary-plan
source WIP MS-A id=87
HTTP 200 plan=fc8396d5-c7e0-424e-a5dd-8b9f70c6a146 planNumber=SEC-F006-20260612-14D689

POST /api/mobile/F006/processing/secondary-plan
source WIP MS-B id=88
HTTP 200 plan=c8d0a5c9-466e-4fbe-b8a6-8e39b85ea59d planNumber=SEC-F006-20260612-928377

POST reports MS2/MS3 as f006_admin:
539 INPUT sourceWip=MS-A HTTP 200 approved
540 INPUT raw2 HTTP 200 approved
541 OUTPUT semi MS-B HTTP 200 approved
542 INPUT sourceWip=MS-B HTTP 200 approved
543 INPUT raw3 HTTP 200 approved
544 OUTPUT FG HTTP 200 approved
```

SQL:

```sql
select batch_id, id, report_kind, approval_status, input_quantity, output_quantity,
       material_cost, source_wip_no, semi_code
from production_reports
where batch_id in (2000,2001,2002)
order by batch_id,id;
```

```text
2000 | 537 | INPUT  | APPROVED | 10.00 | <null> | 10.00 | <null>                                      | <null>
2000 | 538 | OUTPUT | APPROVED | <null>| 10.00  | <null>| <null>                                      | DEMO-GOLD-REVERIFY-1781235965-MS-A
2001 | 539 | INPUT  | APPROVED | 5.00  | <null> | 5.00  | DEMO-GOLD-REVERIFY-1781235965-MS-A          | <null>
2001 | 540 | INPUT  | APPROVED | 5.00  | <null> | 10.00 | <null>                                      | <null>
2001 | 541 | OUTPUT | APPROVED | <null>| 5.00   | <null>| <null>                                      | DEMO-GOLD-REVERIFY-1781235965-MS-B
2002 | 542 | INPUT  | APPROVED | 2.00  | <null> | <null>| DEMO-GOLD-REVERIFY-1781235965-MS-B          | <null>
2002 | 543 | INPUT  | APPROVED | 2.00  | <null> | 6.00  | <null>                                      | <null>
2002 | 544 | OUTPUT | APPROVED | <null>| 2.00   | <null>| <null>                                      | <null>
```

```sql
select intermediate_batch_no, produced_quantity, consumed_quantity, available_quantity,
       unit_cost, accumulated_cost
from semi_finished_inventory
where intermediate_batch_no like 'DEMO-GOLD-REVERIFY-1781235965-MS%';
```

```text
MS-A | produced=10.00 | consumed=5.00 | available=5.00 | unit=1.0000 | acc=10.00
MS-B | produced=5.00  | consumed=2.00 | available=3.00 | unit=<null> | acc=<null>
```

```sql
select s.intermediate_batch_no, t.txn_type, t.source_type, t.quantity, t.unit_cost_at_txn, t.report_id
from semi_finished_inventory_transactions t
join semi_finished_inventory s on s.id=t.semi_finished_id
where s.intermediate_batch_no like 'DEMO-GOLD-REVERIFY-1781235965-MS%'
order by t.id;
```

```text
MS-A | IN | PRODUCTION_OUTPUT | 10.000000 | 1.0000 | 538
MS-B | IN | PRODUCTION_OUTPUT | 5.000000  | <null> | 541
```

```sql
select p.plan_number, p.plan_source_type, p.source_order_id, p.secondary_source_wip_id, b.id, b.batch_number
from production_plans p
left join production_batches b on b.production_plan_id=p.id
where b.batch_number like 'DEMO-GOLD-REVERIFY-1781235965-BATCH-MS%'
order by p.created_at;
```

```text
PLAN-1781236004513-04CB96A3 | NORMAL    | 34e03ca6-6b2c-4cd8-b658-95fb3e1db05e | <null> | 2000 | BATCH-MS1
SEC-F006-20260612-14D689    | SECONDARY | <null>                               | 87     | 2001 | BATCH-MS2
SEC-F006-20260612-928377    | SECONDARY | <null>                               | 88     | 2002 | BATCH-MS3-CONT
```

`multi-stage-cost`:

```text
GET /api/mobile/F006/sales/orders/34e03ca6-6b2c-4cd8-b658-95fb3e1db05e/multi-stage-cost
HTTP 200
stageCount=1
stages[0].semiCode=DEMO-GOLD-REVERIFY-1781235965-MS-A
stages[0].outputUnitCost=1.0
totalChainCost=null
totalCostPerBox=null
```

判定: OPEN。二次加工计划创建已修，但二次加工 OUTPUT 的成本没有把 `sourceWipNo` 的上游成本加进来，`MS-B` 库存成本为空；同时 SECONDARY plans 的 `source_order_id` 为空，订单维度多段成本接口只看到首段 normal plan。


---

## Organizer 批注 (2026-06-12) — 多段链 gap 已诊断, batched 到 headed ② 后统一修

3 个原 P0 已 deep-close (本 audit 上半部确认)。下列"真多段链 semiA→semiB→FG"是修好 secondary-plan 500 后**暴露的下一层** (B4 淋制混合计价 / C12 半成品多段), 非回归。决策: 先发全流程 headed E2E ②, 把多段链 + ② 暴露的其它成本/UI gap **批量到一个聚焦 session 一次修**。诊断如下 (省 rediscovery):

### Gap A — `/multi-stage-cost` stageCount=1 (订单维度只看到首段)
- **根因**: `SalesServiceImpl.getOrderMultiStageCost` (line ~1319) 用 `productionPlanRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId)` 找计划。`createSecondaryPlan` (`ProductionPlanServiceImpl` line ~1966) 只设 `secondarySourceWipId`, **没设 `source_order_id`** → secondary plan (semi B 段) 查不到 → 只返首段 normal plan (semi A)。
- **修法**: createSecondaryPlan 多跳追溯源订单回填 — `wipId → SemiFinishedInventory.batchId → ProductionBatch.productionPlanId → ProductionPlan.sourceOrderId/sourceOrderIds`, set 到 secondary plan。注意 semi A 可能被多单领用, 需确认 order 归属语义 (单订单 vs 多订单链)。

### Gap B — MS-B 半成品 unit_cost / accumulated_cost null (semi B 成本桥缺)
- **根因**: secondary plan 产 semi B 时消耗 semi A 作投入, 但 semi B 的 OUTPUT 报工成本 rollup 没把 semi A 的消耗成本 (semiA.unitCost × 消耗量) 卷进 material_cost。`WipInventoryServiceImpl.outputRollupWithBatchInputMaterial` (P0 两点桥) 只桥 `__MATERIAL_INPUT__` 哨兵料, **没桥 secondary-plan 的 WIP-as-input**。
- **修法**: secondary 生产链路 — 消耗 semi A 的 `deductForSecondaryPlan` 记录消耗成本, OUTPUT rollup 把该消耗成本作为 semi B 的 material input 卷入 (类比两点桥, 但 source 是被领用的 WIP 而非哨兵任务)。
- **代码锚点**: `WipInventoryServiceImpl` (outputRollup 邻域) + secondary 消耗路径 (`deductForSecondaryPlan`) + `ProductionPlanServiceImpl.createSecondaryPlan`。

### 验证 (批量修后)
真多段链 raw→semiA→secondary-plan→semiB→FG: semi B `unit_cost` 非 null + 移动加权 + `/multi-stage-cost` stageCount≥2 + 每段 material/labor 拆分。复用 `gold_production_rerun_runner.py` 多段分支。
