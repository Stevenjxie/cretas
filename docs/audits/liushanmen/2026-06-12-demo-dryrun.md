# 六扇门周五演示路径预跑审计（R2）

日期：2026-06-11 夜间预跑，面向 2026-06-12 客户演示  
环境：prod web-admin `http://139.196.165.140:8086`，后端活跃端口 `10020`，prod DB `cretas_prod_db`  
账号：`f006_admin`、`f006_sales_mgr`、`f006_cashier`、`f006_warehouse_mgr`、`f006_viewer`  
证据目录：`docs/audits/liushanmen/2026-06-12-demo-dryrun-screenshots/`

## 阻断项置顶

| 级别 | 项 | 结论 | 证据 |
|---|---|---|---|
| 🔴 | 出纳付款路径 | `f006_cashier/123456` 登录后访问 `/procurement/payment-requests` 被前端路由拦到 `/403`；`/finance/payments` 也 403。演示链里“出纳确认付款”当前不能用出纳身份展示。 | `2026-06-12-foolproof-screenshots/f006_cashier-payment-requests.png`、`2026-06-12-foolproof-screenshots/low-role-probe-results.json` |

## 关键数据与 SQL 坐实

| 项 | 结果 |
|---|---|
| 含税销售单 | `SO-20260611-0001`，未税 `4000.00`，税额 `520.00`，含税 `4520.00`，备注 `DEMO-tax-voucher-Friday demo (taxRate13)` |
| 含税凭证三行 | `V-2026-0054` 真 3 行：借 `1122` 应收 `4520.00`，贷 `6001` 收入 `4000.00`，贷 `2221.01` 销项税 `520.00` |
| 两点报工配置 | `factory_settings`: F006=`true`，F001=`false`；F006 工序 `reporting_required` 仅首末为 `true` |
| 采购付款数据 | `payment_requests` 仅 3 条 F006 数据，全部 `PAID`，无 `APPROVED/PENDING` 可演示出纳待付款 |
| 盘点数据 | `factory_stocktakes` F006 当前 0 条，盘点任务页为空 |
| 金蝶导出数据 | `voucher_export_records` 当前 0 条；导出配置/科目映射 UI 为空 |
| 并发数据说明 | 看到 `DEMO-771-VERIFY` 的 SO/计划/批次/库存，这是另一 agent 数据，本轮未清理、未触碰、未作为 bug 结论 |

## Part 1 路径结果

| 步骤 | 判定 | 观察 |
|---|---|---|
| 销售订单列表 | ✅ 秒开有数据 | `/sales/orders` 31 条记录；`SO-20260611-0001/0002/0003` 可见，“开始采购”按钮可见。截图 `01-sales-orders.png` |
| 含税订单详情 | ✅ 秒开有数据 | `/sales/orders/d2e...` 显示未税 4000 + 税额 520 = 含税 4520。截图 `02-sales-order-tax-detail.png` |
| 销售财审 | ✅ 秒开有数据 | `/sales/finance-review` 可达，有 1 条待财审。截图 `03-sales-finance-review.png` |
| 凭证详情三行 | ✅ 秒开有数据 | `/finance/voucher/acf4...` UI 显示“凭证分录 3 条”，金额与 SQL 一致。截图 `04-finance-voucher-tax-3row.png` |
| 采购订单 | ✅ 秒开有数据 | `/procurement/orders` 17 条，PO 数据可展示。截图 `05-procurement-orders.png` |
| 采购入库 | ✅ 秒开有数据 | `/procurement/receives` 有已确认入库记录。截图 `06-procurement-receives.png` |
| 采购财审 | ⚠️ 数据缺 | `/procurement/finance-review` 可达但“暂无待审采购单”。截图 `07-procurement-finance-review.png` |
| 付款申请 admin | ⚠️ 数据缺 | `/procurement/payment-requests` 可达，但仅 3 条已付款，无待审批/待出纳付款。截图 `08-payment-requests-admin.png` |
| 出纳付款 cashier | 🔴 断链 | `f006_cashier` 访问付款申请 403。见阻断项。 |
| 生产计划 | ✅ 秒开有数据 | `/production/plans` 53 条；多 SO 合并计划 `PLAN-1781183557299-F6CAC7DC` 可见，但属 `DEMO-771-VERIFY` 并发数据。截图 `09-production-plans.png` |
| 生产批次 | ✅ 秒开有数据 | `/production/batches` 37 条；批次列表可展示。截图 `10-production-batches.png` |
| 批次成本拆分 | ✅ 秒开有数据 | `/production/batches/1980` 显示原料 `1154.40`、人工 `273.00`、总成本 `1427.40`。工序任务为空属该批次数据形态。截图 `11-production-batch-cost-detail.png` |
| 报工撤回 | ✅ 秒开有数据 | `/production/reversals` 有 3 条已撤回记录。截图 `12-production-reversals.png` |
| BOM 成本 | ⚠️ 数据缺 | `/production/bom` 可达，但当前选择“测试成品乙”原辅料/人工/费用均空。截图 `13-production-bom.png` |
| 多级 BOM | ✅ 可达 | `/production/bom/tree` 可达，需要选择产品后展开。截图 `14-production-bom-tree.png` |
| 进销存台账 | ✅ 可达 | `/finance/inventory-ledger` 可达，需选择日期范围查询。截图 `15-finance-inventory-ledger.png` |
| 金蝶导出 | ⚠️ 数据缺 | `/finance/voucher-export` 可达，但配置、科目映射、导出记录为空。截图 `16-finance-voucher-export.png` |
| 盘点任务 | ⚠️ 数据缺 | `/warehouse/stocktakes` 可达但 0 条；可打开“发起盘点”。截图 `17-warehouse-stocktakes.png` |
| 仓库库存/盐化数据 | ✅ 秒开有数据 | `/warehouse/inventory` 有 14 批次，含 `SALTED-DEMO-MT-0001`；另有并发 `DEMO-771-VERIFY` 库存，不触碰。截图 `18-warehouse-inventory.png` |
| 进销存总览 | ✅ 秒开有数据 | `/analytics/supply-chain` 有采购、入库、领用、生产、出库汇总。截图 `19-analytics-supply-chain.png` |
| 研发样品 | ⚠️ 默认 tab 数据缺 | `/rd/samples` 可达，默认“样品管理”为空，但“价位选料”入口可见。截图 `20-rd-samples.png` |
| 三价对比 | ✅ 秒开有数据 | `/rd/quotations/71033.../three-price` 显示预报价 `18.9744/kg`、中报价 `18.3000/kg`、实际成本 `18.3000/kg`。截图 `21-rd-three-price.png` |
| 16 位编码预览入口 | ✅ 可达 | `/warehouse/material-types` 可达；新建弹窗有 16 位编码级联区。截图 `22-material-types-code-preview.png`、`23-material-types-create-dialog-code-preview.png` |

## 演示建议

周五前必须修：`cashier` 角色访问采购付款申请/出纳付款 403。否则付款“双审 + 出纳确认付款”只能用 admin 演示，会明显偏离客户角色路径。

演示数据需补齐：待采购财审 PO、待出纳付款申请、盘点任务、金蝶导出配置/科目映射。否则页面能打开，但关键动作只能讲解不能现场点完整。

不建议周五现场触碰：`DEMO-771-VERIFY` 并发链路数据。它由另一 agent 写入，当前不作为本审计结论。

## Headed Verification

Playwright 使用 headed Chromium，viewport `1920x1080`，locale `zh-CN`，实际页面截图已落盘。未改业务代码。
