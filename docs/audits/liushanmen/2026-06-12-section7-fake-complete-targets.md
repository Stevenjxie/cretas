# 六扇门 §7 假完整/孤岛靶点复验

日期: 2026-06-12  
环境: prod F006, web-admin `http://139.196.165.140:8086`, backend green `10020` 经 8086 `/api/mobile` 代理  
原则: 不改业务代码；写操作均使用 `DEMO-`/`DEMO-FRIDAY` 标记；SQL 坐实关键数据；页面打开、功能跑通、数据正确分开判定。

## 阻断 / OPEN

| 靶点 | 判定 | 证据 |
| --- | --- | --- |
| SP6 供应商银行信息 | 🔴 OPEN | 最新 approved payment `PR-F006-20260611-5424` 的 `bank_name/bank_account/payee_name` 均为空；`GET /payment-requests/approved` 给 cashier 返回 `bankName=null, bankAccount=null`。前端付款列表 DTO/表格也未展示银行字段。演示“一键付款/出纳付款”会缺收款账户。 |
| SP4-A3 标签前缀 | 🔴 OPEN | 对真实 DEMO 物料批次生成标签返回 `MA-F006-20260612005357-2324`；SQL 物料为 `冷冻猪舌`，`code=DDY004`，`primary_code` 为空，仍回退 `MA`。 |
| SP7 盘点 apply 老路径 | 🔴 OPEN(代码路径) | `/stocktakes/{id}/apply` 仍暴露；`FactoryStocktakeServiceImpl.apply()` 只校验 `APPROVED` 并直接改库存，不校验 `workflowInstanceId`。当前 2026-06-12 受月底发起门禁影响未新造盘点。 |
| SP12-T5 DisposalRecord 直批 | 🔴 OPEN | 创建 DEMO disposal record `id=3` 后直接 `PUT /disposal-records/3/approve` 返回 200；SQL: `is_approved=t`, `workflow_instance_id` 为空, `approved_by=1309`。 |
| 原料直收入库 moving_avg_price | ⚠️ 旁路异常 | 两个并发 `POST /processing/material-receipt` 写入冷冻猪舌成功后，批次数量 +6kg，但 `raw_material_types.moving_avg_price` 仍为 `0.0280`；按公式应约 `0.0302`。注意: 这不是 §7 #1 的 #713 WIP 主靶，需另列 triage。 |

## 19 项矩阵

| # | 靶点 | 判定 | 深度 | 证据摘要 |
| --- | --- | --- | --- | --- |
| 1 | SP1-T3 WIP moving weighted avg 并发 (#713) | ✅ CLOSED(中证据) | medium | prod 存在 partial unique index `uq_sfi_intermediate_batch_no(factory_id, intermediate_batch_no) where deleted_at is null`；F006 当前 `semi_finished_inventory` 无重复 `intermediate_batch_no`。未重放真实并发报工。 |
| 2 | SP2-T2 撤回回滚链 | ⚠️ PARTIAL | medium | 代码路径确认: `POST /processing/batches/{batchId}/reversal`, `PUT /reversals/{id}/approve`; `executeReversal` 软删报工、写 `REVERSE`、复位任务、清 `costUnitPrice`。DB 有 F006 `report_reversal_logs` 4 条 DONE，但本轮未新跑“撤回→清空 cost→重报新值”的完整链。 |
| 3 | SP3 §4.1 多段成本 | ⚠️ PARTIAL | medium | `SO-20260611-0004` `/multi-stage-cost` 返回单段: `materialCost=320.00`, `laborCost=null`, `outputUnitCost=40.0000`，两点人工 null 诚实。`SO-20260611-0001` 无 WIP 段返回空是数据不足，不判 bug。真多段半成品链未构造。 |
| 4 | BOM 包材“每产品用量”字段 | ✅ CLOSED | headed | `/production/bom` 当前路由经 `bom-unified` 加载 legacy BOM；点击“添加原辅料”并切“包材”后弹窗出现“每产品用量”。截图: `2026-06-12-section7-screenshots/bom-packaging-each-product-qty.png`。 |
| 5 | costUnitPrice null 链 | ✅ CLOSED(单 SO) | SQL | `SO-20260611-0004` 掌中宝行 `cost_unit_price=40.0000`；`SO-20260611-0001` 因未排批/无实际成本仍 null，不判坏。多 SO 撤回自愈未运行时闭环。 |
| 6 | 标签前缀非 MA | 🔴 OPEN | API+SQL | 见阻断项。 |
| 7 | 供应商银行信息 null | 🔴 OPEN | API+SQL+UI code | 见阻断项。 |
| 8 | direct inbound guard (#700) | ✅ CLOSED | API | `f006_sales_mgr`、`f006_viewer` 调 `POST /material-batches` 均 403，无低权直入库。 |
| 9 | WHInventoryCheck / stocktake apply 绕审批 | 🔴 OPEN(代码路径) | code+SQL | 见阻断项。 |
| 10 | SP8 generate-code 真 16 位 | ✅ CLOSED | API+data | 补 DEMO L2/L3 字典后 `GET /material-segments/generate-code?l1=001&l2=001999&l3=0019990001` 返回 `0019990001000001`。 |
| 11 | SP8 字典 UI 分段入口 | ✅ CLOSED | headed | `/warehouse/material-types` 新建弹窗显示 16 位编码级联/L1-L3/预览入口。截图: `2026-06-12-section7-screenshots/material-type-16digit-segment-ui.png`。 |
| 12 | QuotationTask `laborPerKg` silent drop | ✅ CLOSED | API+SQL | `POST /rd/quotations/71033.../submit` 带 `laborPerKg=12.34` 返回 200；SQL `labor_per_kg=12.3400`。 |
| 13 | `is_trial` silent drop | ✅ CLOSED | SQL | `PB-DEMO-TRIAL-ZHUSHE-20260611`、`PB-DEMO-TRIAL-ZZBZZ-20260611` 均 `is_trial=t` 且有 trial sample/cost。 |
| 14 | 三价进销存脱敏 | ✅ CLOSED | API | admin 可见 `preQuote/midQuote/actualCost`；`f006_sales_mgr` 三个绝对金额均 null，variance 仍可见。 |
| 15 | inventory ledger export 错方法 | ✅ CLOSED | API+xlsx | `GET /inventory/ledger/export` 返回 xlsx，表头为库存台账数量列，不是凭证序时账。 |
| 16 | xlsx export 金额泄露 | ✅ CLOSED(安全) | API+xlsx | `f006_sales_mgr` 凭证导出 403；库存台账 xlsx 无价格/金额字段，未发现金额字符串泄露。 |
| 17 | old `POST /sales/orders/{id}/cancel` 绕过 | ✅ CLOSED | API | 创建 DEMO SO 后 `f006_viewer` cancel 返回 403；`f006_sales_mgr` 可取消其销售单，符合角色权限。 |
| 18 | DisposalRecord direct approve bypass | 🔴 OPEN | API+SQL | 见阻断项。 |
| 19 | print routes PDF 502 | ✅ CLOSED | API | `/print/purchase-order/{id}` 200 `application/pdf` 28687 bytes；`/print/sales-order/{id}` 200 `application/pdf` 17373 bytes。 |

## 附件

- `docs/audits/liushanmen/2026-06-12-section7-screenshots/bom-page-loaded.png`
- `docs/audits/liushanmen/2026-06-12-section7-screenshots/bom-packaging-each-product-qty.png`
- `docs/audits/liushanmen/2026-06-12-section7-screenshots/material-type-16digit-segment-ui.png`

## 下一步

优先修复/决策项: 出纳付款银行信息、标签前缀 `MA`、DisposalRecord 直批、stocktake 老 apply 工作流校验。撤回自愈和真多段成本还需要单独构造“多段半成品链 + 撤回重报”深测，不能用当前单段 DEMO 数据假装闭环。
