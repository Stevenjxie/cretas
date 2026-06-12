# 2026-06-12 Regression Smoke Gate 1

执行时间：2026-06-12 00:00-00:08 CST  
环境：prod `47.100.235.168`，Java 活跃端口 `10010`，web-admin `http://139.196.165.140:8086`  
账号：`f006_admin`、`f006_cashier`、`f006_sales_mgr` / `123456`  
原则：headed + 真实 prod 数据 + SQL 坐实；写操作均带 `DEMO-` 标记；未改代码。

## 阻断/新发现

| 严重级别 | 结论 | 证据 |
|---|---|---|
| 🔴 OPEN | 采购收货确认仍可 500：`POST /api/mobile/F006/purchase/receives/{id}/confirm` 对新建 `DEMO-SMOKE-773-20260612-0001` 收货单返回 500。请求链实际已创建批次/应付/异常单，但事务最终 rollback，接口对用户失败。 | PO `PO-20260612-0001` / `e24ab0b5-4328-46fc-aaf6-38d6a63f1cbc`；收货单 `RCV-20260612-0613` / `a335c245-f00b-4d93-b67a-8f7e4d4576fa`；返回追踪码 `5F05D66C`。日志：`UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` at `PurchaseServiceImpl.confirmReceive` / `PurchaseController.confirmReceive`。同链路前置日志含 `PurchaseOrderVoucherListener` 的 `ObjectOptimisticLockingFailureException`，以及 `SupplyChainOrchestrator` BOM 单位换算异常：`原料「吸塑盒2014-3.5」BOM单位(g)与库存单位(件)无法换算`。 |
| 🔴 OPEN | BUG-MR500 仍可复现：`POST /api/mobile/F006/processing/material-receipt` 返回 500。 | payload `batchNumber=DEMO-MR500-20260612-0001`，追踪码 `5591CE82`。prod 栈：`JpaSystemException: Identifier of entity 'com.cretas.aims.entity.MaterialBatch' must be manually assigned before calling 'persist()'`，落点 `ProcessingServiceImpl.createMaterialReceipt(ProcessingServiceImpl.java:573)`，controller `ProcessingController.createMaterialReceipt(ProcessingController.java:345)`。 |

## 通过项

| 项 | 判定 | 证据 |
|---|---|---|
| prod health | ✅ PASS | `10010 /api/mobile/health` 返回 `UP`；`8083 /health` 返回 `healthy`；`10020` 无健康响应，本轮按当前活跃 `10010` 验。 |
| 登录 | ✅ PASS | `f006_admin`、`f006_cashier`、`f006_sales_mgr` 统一登录均 `success=true`，token 正常返回。 |
| 含税凭证三行 | ✅ PASS | SQL 查 F006 最新凭证 `V-2026-0058`：`entry_count=3`，借贷各 `7673.60`。三行：`1405` 借 `7040.00`、`2202` 贷 `7673.60`、`2221.02` 借 `633.60`。另第一轮销售含税凭证 `V-2026-0056` 仍为 3 行。 |
| F006 两点报工配置 | ✅ PASS | SQL：`factory_settings.skip_process_reporting_default = true`。工序表显示 F006 成品链首尾 `reporting_required=true`，中间工序 false，例如掌中宝链：水解化冻 true，焯水/油炸/熟制伴汁 false，气调 true。 |
| headed admin 关键页 | ✅ PASS | `f006_admin` UI 登录后 `/dashboard`、`/sales/orders`、`/production/batches`、`/procurement/payment-requests`、`/finance/voucher/7690e661-26e0-4c4d-8bfe-a0b27dd8018e` 均可达，未落 `/403`。截图目录：`docs/audits/liushanmen/2026-06-12-regression-smoke-screenshots/`。 |
| #772 cashier 三付款页不 403 | ✅ PASS | `f006_cashier` headed 登录后，`/procurement/payment-requests`、`/sales/payment-requests`、`/finance/payments` finalUrl 均停留目标路径，`blocked=false`。截图：`f006_cashier-cashier-procurement-payment-requests.png`、`f006_cashier-cashier-sales-payment-requests.png`、`f006_cashier-cashier-finance-payments.png`。 |
| #773 收货价继承 | ✅ PASS（创建阶段） | 新建 PO `PO-20260612-0001`，PO 行 `unit_price=31.2300`。创建收货单时收货 item 未传 `unitPrice`，返回和 SQL 均显示 `purchase_receive_items.unit_price=31.2300`。注意：确认入库阶段另报上面的 500，导致未生成最终 material batch。 |

## SQL 摘要

```sql
-- #773 价格继承
SELECT po.order_number, poi.id, poi.material_type_id, poi.quantity, poi.unit_price, poi.tax_rate
FROM purchase_orders po JOIN purchase_order_items poi ON poi.purchase_order_id=po.id
WHERE po.id='e24ab0b5-4328-46fc-aaf6-38d6a63f1cbc';
-- PO-20260612-0001 / item 171 / RMT_1777689969263 / qty 12 / unit_price 31.2300 / tax_rate 9.00

SELECT pr.receive_number, pr.status, pr.warehouse_id, pri.id, pri.received_quantity, pri.unit_price, pri.material_batch_id
FROM purchase_receive_records pr JOIN purchase_receive_items pri ON pri.receive_record_id=pr.id
WHERE pr.id='a335c245-f00b-4d93-b67a-8f7e4d4576fa';
-- RCV-20260612-0613 / DRAFT / item 91 / received_quantity 5.0000 / unit_price 31.2300 / material_batch_id NULL
```

## 截图/产物

- Headed UI 结果 JSON：`docs/audits/liushanmen/2026-06-12-regression-smoke-screenshots/headed-ui-smoke-result.json`
- Headed UI 截图目录：`docs/audits/liushanmen/2026-06-12-regression-smoke-screenshots/`

## Gate 1 结论

Smoke 基线总体可用，#772 已回归通过，#773 的“收货行单价继承”已坐实通过。  
但采购确认入库存在新的真实 prod 500 阻断，且 BUG-MR500 仍 open。建议 organizer gate：先修两个 500，再继续大范围六流 E2E，避免后续采购/仓储链路重复卡在同一故障点。
