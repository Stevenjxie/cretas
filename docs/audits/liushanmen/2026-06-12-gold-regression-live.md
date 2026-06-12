# 六扇门 gold 最终回归 - 今晚修复 live 复验

日期: 2026-06-12
环境: prod `47.100.235.168`, active backend `127.0.0.1:10010` via SSH
账号: `f006_admin`, `f006_cashier`
写入标记: `DEMO-GOLD`
深度: medium/deep 混合；HTTP live + SQL 回读，非页面截图块

## 结论

✅ #774 / #775 / #776 / #777 主修复点 live 回归通过。

⚠️ 旁路发现: 计划级完工生成 FG 后，异步 `TransferVoucherListener` 生成 0 金额调拨凭证失败，违反 `voucher_entries.chk_ve_single_side`。主交易未回滚、FG 已入库，因此不是 #775 阻断，但应进 bug backlog。

## 结果矩阵

| 项 | 判定 | 证据 |
|---|---|---|
| #774 confirm-500 doomed-tx | ✅ PASS | `POST /purchase/receives/{id}/confirm` HTTP 200，收货 `RCV-20260612-3769` CONFIRMED |
| #774 MR-500 主键 | ✅ PASS | `POST /processing/material-receipt` 带 `warehouseId` HTTP 200，批次 `DEMO-GOLD-MR-OK-1781224591` created |
| #775 MR-400 | ✅ PASS | 同端点缺 `warehouseId` HTTP 400，message=`请指定入库仓库`，hintTarget=`warehouseId` |
| #775 onBatchCompleted | ✅ PASS | 计划 `da2bd9c1...` start→complete HTTP 200；两个 DEMO 批次 COMPLETED；两个 FG 自动入库 |
| #776 出纳银行信息 | ✅ PASS | `f006_cashier GET /payment-requests/approved` 第一条含 `bankName=中国工商银行北京朝阳支行`, `bankAccount=6222021001012345678` |
| #777 报损幂等 | ✅ PASS | 已审批 `disposal_records.id=3` 再 approve HTTP 400，message=`报废记录已审批, 请勿重复操作` |
| #777 标签前缀 | ✅ PASS | 新建 code 2 位原料使 `primary_code=<null>`；物料批次标签生成 `YL-F006-20260612083637-0632`，非 `MA` |
| BOM 单位数据修 | ✅ PASS | 用包材 `吸塑盒2014-3.5` 创建/确认收货 HTTP 200；未复现旧 `无法换算` 导致 confirm 500 |

## 关键 live 响应

```text
#774 confirm
receive_id=e883ce2b-964f-4524-9d59-64c4de1a7454
receiveNumber=RCV-20260612-3769
HTTP 200 message=入库确认成功，物料批次已创建

#774 MR OK
batch_id=a2fe43ab-ac1d-42e4-ba0f-c12f7d21b4e5
batchNumber=DEMO-GOLD-MR-OK-1781224591
HTTP 200 status=AVAILABLE warehouseId=6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e

#775 MR missing warehouse
HTTP 400
message=请指定入库仓库
actionHint=原材料接收必须选择目标仓库
hintTarget=warehouseId

#776 cashier approved
requestNumber=PR-F006-20260611-5424
bankName=中国工商银行北京朝阳支行
bankAccount=6222021001012345678

#777 label
material_id=RMT_1781224596933
material_code=UQ
primaryCode=null
labelCode=YL-F006-20260612083637-0632
```

## SQL 回读

```sql
select id,receive_number,status,warehouse_id
from purchase_receive_records
where id='e883ce2b-964f-4524-9d59-64c4de1a7454';
-- e883ce2b-964f-4524-9d59-64c4de1a7454|RCV-20260612-3769|CONFIRMED|6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e

select id,batch_number,status,warehouse_id
from material_batches
where id='a2fe43ab-ac1d-42e4-ba0f-c12f7d21b4e5';
-- a2fe43ab-ac1d-42e4-ba0f-c12f7d21b4e5|DEMO-GOLD-MR-OK-1781224591|AVAILABLE|6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e

select id,code,coalesce(primary_code,'<null>'),category
from raw_material_types
where id='RMT_1781224596933';
-- RMT_1781224596933|UQ|<null>|原料

select id,label_code,batch_id
from labels
where label_code='YL-F006-20260612083637-0632';
-- 636d5e37-fd0b-4470-a705-a7405c9f392a|YL-F006-20260612083637-0632|0d4c986c-6bfb-4228-b8fa-77364f4b461e

select p.id,p.status,p.actual_quantity,b.id,b.batch_number,b.status,b.actual_quantity,b.good_quantity
from production_plans p
join production_batches b on b.production_plan_id=p.id
where p.id='da2bd9c1-c94d-418b-8488-d54a3c2273ea'
order by b.id;
-- da2bd9c1...|COMPLETED|30.00|1982|DEMO-X-87757|COMPLETED|10.00|10.00
-- da2bd9c1...|COMPLETED|30.00|1983|DEMO-Y-87759|COMPLETED|10.00|10.00

select id,batch_number,produced_quantity,production_plan_id,status,warehouse_id,created_at
from finished_goods_batches
where factory_id='F006'
  and production_plan_id='da2bd9c1-c94d-418b-8488-d54a3c2273ea'
order by created_at desc;
-- bf776198-6420-4eb2-a706-817fbebb0020|FG-AUTO-20260612-1983|83.3300|da2bd9c1...|AVAILABLE|bbede96c-025a-4f96-9d8c-672410b5ed00|2026-06-12 08:42:50.819197
-- 2f8148f2-d346-48a2-853f-943302ee3e17|FG-AUTO-20260612-1982|83.3300|da2bd9c1...|AVAILABLE|bbede96c-025a-4f96-9d8c-672410b5ed00|2026-06-12 08:42:50.704498
```

## 旁路问题

⚠️ `TransferVoucherListener` 在 FG 自动入库后异步失败:

```text
2026-06-12 08:42:50.882 TransferVoucherListener - Voucher generation failed for Transfer 68f92f95-2c38-4437-9131-f8680caa2f4f
ERROR: new row for relation "voucher_entries" violates check constraint "chk_ve_single_side"
Failing row: debit=0.00, credit=0.00, subject=库存商品, description=调入仓库
```

判定: 主链未回滚，`production_plans`/`production_batches`/`finished_goods_batches` 均已落库；这是异步凭证副作用 bug，非 #775 阻断。

## 可复现脚本

本轮使用的 SSH-curl 辅助脚本保留在:

`docs/audits/liushanmen/gold_regression_runner.py`
