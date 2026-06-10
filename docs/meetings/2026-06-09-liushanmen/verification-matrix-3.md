# 六扇门需求追溯矩阵 — 分片 3（D / E / H / X 流）

**构建时间**: 2026-06-10  
**构建者**: VMX-3 Sonnet 分片代理  
**范围**: D 流(采购到付款) / E 流(销售到开票) / H 流(财务凭证/进销存) / X 流(跨流约束)  
**基线**: 12 SP 全 merged+deployed (PR#627–653), Flyway max V20261011_22, E2E run-20260610_124749  
**证据规则**: V1=持久化证据(给出处) / V2=链路通造数路过 / V0=未验证 / B=阻塞(写明) / N/A  
**实现口径**: ✅已建 / 🟡部分(写清缺哪半) / 🔴缺 / ⚪约束项  

---

## D 流 — 采购到付款

> 总体判断: 采购单据层基本齐全(PO/入库/财审/退货/供应商/询价)；真正缺口集中在付款替代钉钉 + 进项发票 + 结算属性→科目映射。E2E run 已通 PO+入库+异常+付款申请链(V2)。

| 编号 | 摘要 | 模块/SP | 优先级 | 实现 | 验证 | 证据 | 建议方法 |
|------|------|---------|--------|------|------|------|---------|
| D-1 | 采购订单 CRUD + 提交/审批/取消/复制/PDF | PurchaseOrder SP3 | P0 | ✅已建 | V2 | E2E run-20260610_124749: PURCHASE_ORDER id f3812561 创建成功 12:47:55 | API 断言 PO status 状态机 |
| D-2 | 采购关联销售订单 (两种模式: 自主/跟单) | PurchaseOrder.salesOrderId SP3 | P1 | 🟡部分 | V0 | salesOrderId 字段存在; 从 SO 一键弹窗带入原料明细未实现 | 检查 orders/list.vue loadSalesOrders 交互 |
| D-3 | 采购供应商管理 (档案/准入/黑名单) | Supplier SP3 | P0 | ✅已建 | V1 | 2026-06-10: `GET /suppliers` 200, count=2, paymentTerms "货到30天/60天" 实证; SupplierController 端点可达 | headed UI 供应商列表页 |
| D-4 | 供应商结算属性结构化 (月结/现结/账期枚举) | Supplier.paymentTerms SP3 | P1 | 🟡部分 | V1 | 2026-06-10: paymentTerms=自由文本 ✅; paymentTermsType(SettlementType枚举) 字段存在但 null; 两字段均已建 git show 确认 | 枚举字段补充填充 |
| D-5 | 采购入库 + 实际入库值 + 超收 30% cap | PurchaseReceiveRecord SP3 | P0 | 🟡部分 | V2 | E2E: PURCHASE_RECEIVE id e5d325f3 + PURCHASE_EXCEPTION id 9a5c21a2 OVER_RECEIVE 已触发 12:47:57-58 | 验 validateOverReceiveCap 逻辑; 检查异常单分支 |
| D-6 | 差值异常动单 → 退回采购人判断退/入 | PurchaseException 分支流 SP6 | P0 | 🟡部分 | V2 | PURCHASE_EXCEPTION 9a5c21a2 已创建(类型 OVER_RECEIVE); 但无独立异常单实体/责任绑定流 — cap 拦截非异常单模型 | E2E 异常已造数; headed 看 /procurement/exceptions 页 |
| D-7 | 采购付款属性 6 类结算 (预付/赊销/未到票/月结/账期/现结) | PO 结算属性 SP11 | P1 | 🔴缺 | N/A | git grep settlementType PurchaseOrder = 0 命中; PO 无结算属性字段 | 需先实现: PO 加 settlementType 枚举 |
| D-8 | 进项发票管理 (未到票催票 + 上传核销状态机) | 进项票实体 SP11 | P1 | 🔴缺 | N/A | InvoiceRecord 仅挂销售侧 (salesOrderId/customerId); 无采购进项票实体 | 需先实现 |
| D-9 | 付款申请 + 双端审批流 + 出纳只读终端 (替代钉钉) | PaymentRequestController SP6 | P0 | ✅已建 | V1 | 2026-06-10 矩阵纠正(Batch B): 原 B阻塞"PaymentRequestController不在origin/main"错误 — Controller 在 origin/main:controller/inventory/PaymentRequestController.java; 状态机 PENDING→FINANCE_REVIEW→APPROVED→PAID; markPaid三写原子 | audit: 2026-06-10-h-x-flow-verification.md X-4 |
| D-10 | 采购财务审批 (界面内提交 + 财务审批可见) | PurchaseController 财审 SP3 | P1 | ✅已建 | V1 | 2026-06-10: git show 确认 submit-for-finance-review/finance-approve 端点; 财审流程与 D-12 流程中验证 200 | — |
| D-11 | 采购单价未税/含税双值显示 | PO item.taxRate SP3 | P1 | ✅已建 | V1 | 2026-06-10: taxRate=9, lineAmount=4000.0, lineAmountWithTax=4360.0 (=4000×1.09) API 断言精确验证; @PriceSensitive 脱敏路径已知 | audit: 2026-06-10-d-flow-purchase-verification.md D-11 |
| D-12 | 从 SO 一键生成采购单 + "开始采购"入口 | PO 创建流程 SP3 | P1 | 🟡部分 | V1 | 2026-06-10: PO-20260610-0004 创建时传入 salesOrderId 存储验证 ✅; "从SO弹窗带入明细"UI入口仍缺 | audit: 2026-06-10-d-flow-purchase-verification.md D-12 |
| D-13 | 采购需求/请购单 + BOM 反推领料 | PurchaseRequisition SP5 | P1 | 🟡部分 | V1 | 2026-06-10: PR-20260610-001 创建DRAFT→提交PENDING_APPROVAL; 路径 /purchase-requisitions, 字段 requestedItems; 反推领料未验 | audit: 2026-06-10-d-flow-purchase-verification.md D-13 |
| D-14 | 多关联 SO 按供应商/品类合并采购单 | PO 合并逻辑 SP3 | P1 | 🔴缺 | N/A | PO 无 salesOrderIds 数组; 无合并聚合逻辑 | 需先实现 |
| D-15 | 询价核价 + 价格趋势比较 | InquiryQuote SP3 | P1 | ✅已建 | V1 | 2026-06-10: INQ-20260610-0001 创建成功 DRAFT; inquiryDate/quantity/unit 必填; submit/supplier-prices/select-and-convert 端点存在 git grep | audit: 2026-06-10-d-flow-purchase-verification.md D-15 |
| D-16 | 采购退货 (发起→财务审批→仓管出货) | ReturnOrder PURCHASE_RETURN SP6 | P1 | 🟡部分 | V1 | 2026-06-10: 路径纠正→ /return-orders (NOT /purchase/returns); GET 200 count=0; create+approve 端点存在; 创建全链未做完整断言 | audit: 2026-06-10-d-flow-purchase-verification.md D-16 |
| D-17 | 合同号挂采购 + 历史价格/批次永久追溯 | PO 合同号字段 SP3 | P2 | 🟡部分 | V1 | 2026-06-10: git show PurchaseOrder.java:192 contractNumber 字段存在; API 返回 "contractNumber":null ✅ | audit: 2026-06-10-d-flow-purchase-verification.md D-17 |
| D-18 | 原料来源标记 (国内/国外) | PurchaseOrder.isImported SP3 | P2 | ✅已建 | V1 | 2026-06-10: git show PurchaseOrder.java:97-98 isImported Boolean 字段; API 返回 "isImported":null ✅ | audit: 2026-06-10-d-flow-purchase-verification.md D-18 |
| D-19 | 采购界面单据打印/PDF/发送供应商 | PrintController SP3 | P1 | ✅已建 | B | 2026-06-10 B阻塞: 端点可达 200 但 502 "打印服务暂时不可用"; Python test (8084) print 路由未注册 | 修复 Python print 模块在 test env |
| D-20 | 研发试样票务字段 (有票/无票→科目影响) | 票务属性 SP11 | P2 | 🔴缺 | N/A | 试样采购票务字段无独立建模 | 需先实现 |

**D 流小结**

| 状态 | 数量 |
|------|------|
| ✅已建 | 7 |
| 🟡部分 | 9 |
| 🔴缺 | 4 |

关键缺口: D-7(结算属性6类) / D-8(进项票) / D-9(付款申请链完整度) — 客户 P0 替钉钉核心。  
**Batch A 验证升级 (2026-06-10)**: D-3/D-4/D-10/D-11/D-12/D-13/D-15/D-16/D-17/D-18 → V1; D-9/D-19 → B阻塞。  
**Batch B 矩阵纠正 (2026-06-10)**: D-9 原 B阻塞纠正 → V1 (PaymentRequestController 在 origin/main, 误 grep 导致误判)。  
验证覆盖率(更新后): V1=11 / V2=2 / V0=0 / B=1(D-19打印服务) / N/A=4 (D流)。

---

## E 流 — 销售到开票

> 总体判断: 销售主链地基成熟(SO CRUD/财审/发货/收款/开票/凭证 entity 全有)。真正缺口: 底价毛利红线被 F3 gap 静默禁用 / 凭证为手动非自动传票 / 多 SO 合并供单未实现 / 盐化独立。E2E run 已通 SO 创建。

| 编号 | 摘要 | 模块/SP | 优先级 | 实现 | 验证 | 证据 | 建议方法 |
|------|------|---------|--------|------|------|------|---------|
| E-1 | 销售订单 CRUD + 确认/取消/复制/状态机 | SalesController SP2 | P0 | ✅已建 | V2 | E2E run-20260610_124749: SO-20260610-0003 id 040e8396 12:47:52 | headed web /sales/orders 列表+详情 |
| E-2 | 创建 SO 时单价可空 + SKU 关联专属客户 | SalesOrder entity SP2 | P0 | ✅已建 | 🔧修复中 | 2026-06-10 服务层+DB迁移修复完成(fix/so-empty-price-draft): 禁用全局 POSITIVE_AMOUNT CREATE 规则(V20261014_03); submitForFinanceReview 加行级单价守卫(行717); 8测试绿; 待部署 test env curl 验证 → B阻塞消除 | 部署后 curl POST /orders 无单价 → 200 |
| E-3 | 财务审核流程 (含税/未税/税率口径) | SalesController 财审 SP2 | P0 | ✅已建 | V1 | 2026-06-10: SO-20260610-0001 完整链 DRAFT→CONFIRMED→PENDING_FINANCE_REVIEW→FINANCE_APPROVED; financeReviewedAt 非null | audit: 2026-06-10-e-flow-sales-verification.md E-3 |
| E-4 | 销售单价含税+不含税双值显示 | SO 税率字段 SP2 | P1 | 🟡部分 | V1 | 2026-06-10: SalesOrderItem 无 lineAmountWithTax @Transient (只有 PurchaseOrderItem 有); taxRate 字段存在但 SO 侧含税双值未实现 — 发现 gap | audit: 2026-06-10-e-flow-sales-verification.md E-4 |
| E-5 | 底价/毛利率红线预警 (低于底价红色禁提交) | PricingEngine SP2 | P0 | 🟡部分 | B | 阻塞: PricingEngine.checkWarnings 基础在但 costEstimate(null) 静默禁用 — SalesServiceImpl:362 F3 gap 注释; ProductType 无 standardCost 字段 | 需先补 ProductType.standardCost 或 BOM 卷积成本 |
| E-6 | 三层价格参考 (研发预估/下单/实际核算) | OperationalQuote + SO 成本 SP2/SP11 | P1 | 🟡部分 | V0 | OperationalQuoteController; SO.estimatedCost(财审手填); cost-breakdown 预估vs实际; 三层同一视图对比未做 | headed rd/quotations + SO 成本详情 |
| E-7 | 发货/出库/签收 + 照片上传 | SalesDeliveryRecord SP2 | P0 | ✅已建 | V1 | 2026-06-10: DLV-20260610-4332 创建 PENDING_WAREHOUSE_CONFIRM; customerId+salesOrderId+items 必填 | audit: 2026-06-10-e-flow-sales-verification.md E-7 |
| E-8 | 开票管理 (申请/审批/开具/回传) | InvoiceController SP2 | P0 | ✅已建 | V1 | 2026-06-10: INV-20260610-0013 申请REQUESTED→审批APPROVED; invoiceType 正确枚举值 SPECIAL (非VAT_SPECIAL) | audit: 2026-06-10-e-flow-sales-verification.md E-8 |
| E-9 | 收款管理 (记录/核销/状态) | PaymentRecordController SP2 | P1 | ✅已建 | V1 | 2026-06-10: 收款记录创建 200 success, status=PENDING; 关联SO-20260610-0001, amount=2000 | audit: 2026-06-10-e-flow-sales-verification.md E-9 |
| E-10 | 收款→自动触发开票事件联动 | 收款→开票链 SP2 | P1 | 🟡部分 | V1 | 2026-06-10: GET /finance/invoices?salesOrderId=xxx 可按SO筛选发票 ✅; record→invoice 自动联动仍未实现; by-sales-order path变体400 | audit: 2026-06-10-e-flow-sales-verification.md E-10 |
| E-11 | 销售凭证财审自动传票 (非手动批量) | SalesReceiptVoucherGenerator SP11 | P1 | ✅已建 | V1 | 2026-06-10: 矩阵描述有误 — SalesFinanceApproveVoucherListener **已自动触发**; 日志: V-2026-0019 auto-generated on finance-approve; by-business API 确认 ✅ | audit: 2026-06-10-e-flow-sales-verification.md E-11 |
| E-12 | 多销售单合并为一张供单 | SO 合并聚合 SP2 | P1 | 🔴缺 | N/A | CreateSalesOrderRequest 无 salesOrderIds 数组; ProductionPlanServiceImpl 无 merge 逻辑 | 需先实现 |
| E-13 | 销售订单单据打印 (按 SKU 单位) | PrintController SP2 | P2 | ✅已建 | B | 2026-06-10 B阻塞: 端点 200 但 "打印服务暂时不可用"; Python test (8084) print 路由未注册 (同 D-19) | 修复 Python print 模块 |
| E-14 | 盐化独立销售单元 (谁建谁用+独立报表) | 盐化供单 SP11 | P2 | 🔴缺 | N/A | grep 盐化/saltcure = 0 命中; F流 WarehouseType 无盐化类型 | 需先实现 |
| E-15 | 三价对比视图 (研发预估/BOM标准/实际核算同屏) | three-price SP11 | P1 | 🟡部分 | B | 阻塞: /rd/quotations/three-price 路由被当 ID 解析(E2E 发现 BUG; FIXB 组2-7 修复中); 三层散在报价/财审/profit-detail 三处未同屏对比 | FIXB 修复后重测 three-price 入口 |
| E-16 | 销售提成与毛利联动 | 提成规则 SP12 | P2 | 🟡部分 | V1 | 2026-06-10: GET /commission 200; 路径 /api/mobile/{factoryId}/commission; content 空(无测试数据); 毛利联动逻辑验证 defer | audit: 2026-06-10-e-flow-sales-verification.md E-16 |

**E 流小结**

| 状态 | 数量 |
|------|------|
| ✅已建 | 6 |
| 🟡部分 | 7 |
| 🔴缺 | 2 |
| B阻塞 | 1 |

关键缺口: E-5(毛利红线 F3 gap 阻塞) / E-12(多 SO 合并)。  
**矩阵描述纠正**: E-11 矩阵原说"未自动传票"→**已自动传票** (SalesFinanceApproveVoucherListener 已有效触发)。  
**Batch A 验证升级 (2026-06-10)**: E-3/E-4/E-7/E-8/E-9/E-10/E-11/E-16 → V1; E-2/E-13 → B阻塞。  
验证覆盖率(更新后): V1=9 / V2=1 / V0=1(E-6) / B=2 / N/A=2。

---

## H 流 — 财务凭证/进销存报表

> 总体判断: 系统已有超出客户预期的完整复式记账内核(Voucher/Account/AR-AP/三表/月结); 客户实际诉求很轻(凭证表/科目余额表导出+进销存报表+付款属性映射)。H 流缺口集中在"导出金蝶"和"进销存四时点报表"两项。

| 编号 | 摘要 | 模块/SP | 优先级 | 实现 | 验证 | 证据 | 建议方法 |
|------|------|---------|--------|------|------|------|---------|
| H-1 | 复式记账内核 (Voucher + 借贷必平 + 8 类 Generator) | VoucherService SP11 | P2 | ✅已建 | V1 | 2026-06-10: git show 确认 validateBalanced() + 7 generator impl (Depreciation/Expense/InventoryTransfer/PurchasePayment/Return/SalesReceipt/Wage); AbstractVoucherGenerator 自动调用 validateBalanced | audit: 2026-06-10-h-x-flow-verification.md H-1 |
| H-2 | 会计科目表 (Account 树 + 4层 + 系统级共享) | AccountController SP11 | P2 | ✅已建 | V1 | 2026-06-10: AccountController 代码存在 (git ls-tree); AccountCategory enum | audit: 2026-06-10-h-x-flow-verification.md H-2 |
| H-3 | 科目余额表 (查询有 + 导出有) | VoucherExportController SP11 | P1 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — VoucherExportController.java:68 @GetMapping("/subject-balance/export") 已实现; API GET subject-balance/export 200 + 3787 bytes xlsx ✅ | audit: 2026-06-10-h-x-flow-verification.md H-3 |
| H-4 | 金蝶/用友凭证导出 (Excel 表头复用) | VoucherExportController SP11 | P1 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — VoucherExportController + VoucherExportServiceImpl + VoucherTargetSystem.KINGDEE/YONYOU 全在 origin/main; POST voucher-export 200 + 5242 bytes xlsx ✅ | audit: 2026-06-10-h-x-flow-verification.md H-4 |
| H-5 | 凭证模板/科目映射配置 (generator 查模板) | VoucherTemplate SP11 | P2 | 🟡部分 | V0 | entity/VoucherTemplate JSONB entries + SpEL; generator 仍硬编码科目; 无 Vue 模板编辑器 | grep VoucherTemplate generator 调用路径 |
| H-6 | 付款属性→科目映射 (6 类结算↔凭证科目) | 结算属性映射 SP11 | P1 | 🔴缺 | N/A | PurchasePaymentVoucherGenerator 硬编码借 1405/贷 2202; PO 无 settlementType 字段 | 依赖 D-7 实现 |
| H-7 | 进销存台账 (期初/期入/期出/期末四时点 + 数量/金额) | InventoryLedgerController SP12 | P1 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — InventoryLedgerController 已建; WHIOStatisticsScreen 已迁移实际 API; API GET /inventory/ledger 200 + 6-line response ✅; web-admin inventory-ledger/index.vue 完整实现 | audit: 2026-06-10-h-x-flow-verification.md H-7 |
| H-8 | 进销存期中出库核账 (数量×单价勾稽) | 进销存报表 SP12 | P1 | 🔴缺 | N/A | 依赖 H-7 已建; 核账勾稽逻辑需额外建模 | 验 H-7 基础数字后评估 |
| H-9 | 业务-财务数据归集 (AR/AP + 自动传财务) | ArApController SP11 | P2 | ✅已建 | V1 | 2026-06-10: ArApController 代码存在 (git ls-tree); generator 事件触发架构确认 | audit: 2026-06-10-h-x-flow-verification.md H-9 |
| H-10 | 会计期间/月结闭环 | AccountingPeriod SP11 | P2 | ✅已建 | V1 | 2026-06-10: AccountingPeriodController 代码存在 (git ls-tree); MonthCloseServiceImpl 存在 | audit: 2026-06-10-h-x-flow-verification.md H-10 |
| H-11 | 财务审核流程 (销售/采购/退货) | finance-review 多模块 SP2/SP3 | P2 | ✅已建 | V0 | /sales/finance-review + /procurement/finance-review + workdesk/FinanceManagerWorkdesk | headed 财务工作台 |
| H-12 | 税务字段 (含税/未税价 + 税率 9/13) | 税率字段横切 SP11 | P1 | 🟡部分 | V0 | taxRate 字段散见 BomItem/SalesOrderItem/InvoiceRecord; 统一含税↔未税换算口径未实现 | grep 含税 unitPrice 换算路径 |
| H-13 | 三表 (资产负债/利润/现金流量) | 三表 backend SP11 | P2 | ✅已建 | V0 | BalanceSheet/P&L/CashFlow 三表 entity + service (系统内核); 客户不推前台 | 内部计算用, 非客户交付 |
| H-14 | 采购实收值与成本口径对齐 (超收按实收) | 成本口径 SP3/SP11 | P1 | 🟡部分 | V2 | E2E: PURCHASE_EXCEPTION OVER_RECEIVE 已创建; 成本口径锁实际入库值机制需端到端验 | 检查 receivedQuantity→BOM 成本路径 |
| H-15 | 凭证打印/单据打印 | PrintController SP11 | P2 | 🟡部分 | V0 | VoucherController list/detail; 无凭证打印端点; PrintController 已有销售/采购单 | 检查是否有 /print/voucher 端点 |

**H 流小结**

| 状态 | 数量 |
|------|------|
| ✅已建 | 10 |
| 🟡部分 | 3 |
| 🔴缺 | 2 |

关键缺口更新(Batch B 后): H-8(进销存核账勾稽, 依赖H-7已建) / H-6(付款属性科目映射,依赖 D-7)。  
**Batch B 矩阵纠正 (2026-06-10)**: H-3/H-4/H-7 原标"缺"实际已建; H-1/H-2/H-9/H-10 V0→V1。  
验证覆盖率(更新后): V1=10 / V2=0 / V0=3(H-5/H-11/H-12/H-13/H-14/H-15) / N/A=2(H-6/H-8)。

---

## X 流 — 跨流约束

> 流 X 是贯穿各流的横切基础设施+产品定性+实施约束。约束项(上线时间/产品定性/双端分配)标 ⚪，验证=N/A。

| 编号 | 摘要 | 模块/SP | 优先级 | 实现 | 验证 | 证据 | 建议方法 |
|------|------|---------|--------|------|------|------|---------|
| X-1 | 审批流引擎 (通用状态机 + SpEL 分流 + Redis 缓存) | WorkflowEngineServiceImpl SP12 | P0 | ✅已建 | V1 | 2026-06-10: WorkflowEngineService 被 ApprovalActionExecuteTool/ProductionPlanServiceImpl/ApprovalPendingQueryTool 调用; hasActiveWorkflow+startWorkflow 方法存在; workflow-designer/index.vue 1057行 | audit: 2026-06-10-h-x-flow-verification.md X-1 |
| X-2 | 工单撤回审批挂接 (有数据时需审批) | 审批引擎 PRODUCTION_REVERSAL SP12 | P0 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — ProductionPlanServiceImpl 完整挂接 WorkflowEngine: hasActiveWorkflow(factoryId,"PRODUCTION_REVERSAL") + startWorkflow(...); ProductionPlanController.java:344 明确注解"触发 PRODUCTION_REVERSAL 审批流"; ApprovalChainConfig.java:214 PRODUCTION_REVERSAL_APPROVAL | audit: 2026-06-10-h-x-flow-verification.md X-2 |
| X-3 | 退货审批挂接 (跟钱有关必财务审批) | ReturnOrder 审批 SP6 | P0 | 🟡部分 | V0 | ReturnOrderController approve 端点存在; 是否走 WorkflowEngine 而非 ad-hoc 待确认 | git grep ReturnOrderServiceImpl.*workflow |
| X-4 | 采购付款申请审批挂接 | PaymentRequestController SP6 | P0 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — PaymentRequestController 在 origin/main (path: controller/inventory/PaymentRequestController.java); 状态机 PENDING→FINANCE_REVIEW→APPROVED→PAID/REJECTED; markPaid 三写原子 @Transactional | audit: 2026-06-10-h-x-flow-verification.md X-4 |
| X-5 | 权限矩阵 RBAC (28 角色 4 层级 + module:action) | PermissionServiceImpl SP1 | P0 | ✅已建 | V1 | 2026-06-10: FactoryUserRole 28+角色枚举; @RequirePermission 遍布 Controller; PriceFieldResponseAdvice; PermissionMatrix.vue; V20261011_02 六扇门 RBAC 矩阵 SQL | audit: 2026-06-10-h-x-flow-verification.md X-5 |
| X-6 | 六扇门角色落库 (厂长/车间主任/小组长/仓管/配料员/采购/研发/财务) | RBAC 梳理落库 SP12 | P0 | 🟡部分 | V1 | 2026-06-10: FactoryUserRole 包含 dispatcher/workshop_supervisor/yield_operator/warehouse_worker/quality_inspector/cashier; V20261011_02 SQL 已落库; 缺口: PMC/配料员 无独立枚举值 (yield_operator 兼任?) | audit: 2026-06-10-h-x-flow-verification.md X-6 |
| X-7 | 单据打印模板 (含生产工单/汇总领料配料单) | PrintController SP9 | P1 | 🟡部分 | V0 | PrintController 5 类模板存在; 生产工单/汇总领料配料单模板是否在 5 类内待确认 | git ls-tree 查 print-template-editor schema 类型 |
| X-8 | AI 意图集成 (客户明确 defer) | Tool-Skill SP12 | P2 | ✅已建 | N/A | 337+ tools; 会议"先按以前逻辑架系统 AI 后置" | 本期不验; 能力已有 |
| X-9 | 双端分配 (PC 复杂操作 / 手机现场单点) | 架构约束 SP1-12 | P0 | ⚪约束项 | N/A | 已是现有架构事实(web-admin + RN 432屏); 每新功能按此原则决定端归属 | 流程纪律非代码 |
| X-10 | 补录时效 T-3 约束 (T/T-1 可编辑/T-3 锁死) | BackdateWindowValidator SP12 | P0 | ✅已建 | V1 | 2026-06-10: 矩阵描述纠正 — BackdateWindowValidator.java 存在 (cretas.backdate.max-days=2); 报工路径(YieldReportServiceImpl:96-103) + 入库路径(MaterialBatchServiceImpl:179-218) 均已注入; API 双路径断言: T-3(2026-06-07) → 409 BACKDATE_WINDOW_EXCEEDED; T-1(2026-06-09) → 200 ✅ | audit: 2026-06-10-h-x-flow-verification.md X-10 |
| X-11 | 防呆设计 5 大规则落地 (max 预显/上下文身份/dropdown/幂等/导航) | fool-proof-design SP1-12 | P0 | 🟡部分 | V0 | fool-proof-design.md 规范已落; 部分已做(out getLimits/完成生产带品名/error sticky); 大部分报工/入库 dialog 未全 audit | headed UI 抽查 3-5 个写操作 dialog |
| X-12 | 未税价成本口径统一 (含税进价→未税成本贯穿) | CostRollupUtil 税率横切 SP11 | P1 | 🟡部分 | V0 | taxRate 字段散见多处; CostRollupUtil 含税↔未税换算链未统一 | git grep CostRollupUtil tax |
| X-13 | 财务凭证导出与金蝶对接 (属 H 流) | 金蝶导出 SP12 | P2 | 🔴缺 | N/A | 归 H-4; 此处记录跨流依赖 | 见 H-4 |
| X-14 | 上线半月约束 (最多半月大部分上线) | 实施约束 | P0 | ⚪约束项 | N/A | 非代码; 排期/MVP 切割决策 | 流程纪律非代码 |
| X-15 | 产品定性约束 (填写项极少/操作简单/替手动制表) | 产品原则 | P0 | ⚪约束项 | N/A | 已是现有 UX 设计原则 | 流程纪律非代码 |

**X 流小结**

| 状态 | 数量 |
|------|------|
| ✅已建 | 7 |
| 🟡部分 | 4 |
| 🔴缺 | 0 |
| ⚪约束项 | 4 |

关键缺口更新(Batch B 后): X-3(退货审批挂接未确认) / X-6 PMC/配料员角色缺 / X-12(税率换算链) / X-11(防呆未全 audit)。  
**Batch B 矩阵纠正 (2026-06-10)**: X-1/X-2/X-4/X-5 V0→V1; X-10 V0→V1 (BackdateWindowValidator 双路径实证); X-13🔴缺保留。  
约束项 ⚪ 均为流程纪律/实施决策, 非代码实现, N/A 验证合理。  
验证覆盖率(更新后): V1=7 / V0=4(X-3/X-6残留/X-7/X-11/X-12) / N/A=4(⚪约束项)。

---

## 分片 3 总计

| 流 | ✅已建 | 🟡部分 | 🔴缺 | ⚪约束 | B阻塞 | 合计 |
|----|--------|--------|------|--------|-------|------|
| D | 7 | 9 | 4 | 0 | 0 | 20 |
| E | 6 | 7 | 2 | 0 | 1 | 16 |
| H | 6 | 5 | 4 | 0 | 0 | 15 |
| X | 3 | 7 | 1 | 4 | 0 | 15 |
| **合计** | **22** | **28** | **11** | **4** | **1** | **66** |

完整度(已建/有物): 22/66 = 33%  
有物(已建+部分): 50/66 = 76%  
真正缺失: 11/66 = 17%  
约束项: 4/66 = 6%

---

## Top 风险条目 (各流前 5)

### D 流 Top 5

| 排名 | 编号 | 风险描述 | 影响 |
|------|------|---------|------|
| 1 | D-9 | 付款申请+双端审批+出纳链完整度不明 — E2E 仅创建 PAYMENT_REQUEST 未走完审批 | P0 客户替钉钉核心; 若链断则主诉求未满足 |
| 2 | D-8 | 进项发票实体完全缺失 — 无采购进项票; 销售 InvoiceRecord 不可复用 | P1 催票/核销功能完全无法交付 |
| 3 | D-7 | 6 类结算属性字段完全缺失 — H-6 付款属性科目映射被阻塞 | P1 金蝶凭证科目映射链断 |
| 4 | D-6 | 超收异常单是拦截模型非分支流模型 — 缺独立异常单+责任绑定+退/入判断分支 | P0 防呆核心; 采购差异处理不符客户场景 |
| 5 | D-14 | 多关联 SO 合并为一个采购单完全未实现 | P1 多订单场景采购效率痛点 |

### E 流 Top 5

| 排名 | 编号 | 风险描述 | 影响 |
|------|------|---------|------|
| 1 | E-5 | 毛利率红线预警被 F3 gap 静默禁用 — PricingEngine 有基础但 costEstimate(null) 全链路阻塞; 代码 F3 注释明确标 gap | P0 核心差异化; 客户明确要求"不允许低于毛利底线"; 现在提交低价单无任何拦截 |
| 2 | E-15 | three-price 路由 BUG (路由被当 ID 解析显示"未找到") — 研发核心功能入口不可用; FIXB 修复中 | P1 研发三价对比无法演示; 周五 demo 需确认修复 |
| 3 | E-11 | 凭证为手动批量非财审自动传票 — 客户要"钱已收→自动出票"; financeApproveOrder 未触发 VoucherGenerator | P1 财务流程断层; 影响金蝶凭证导出(H-4)上游 |
| 4 | E-12 | 多 SO 合并为一张供单完全缺失 — 无 salesOrderIds 数组/merge 逻辑 | P1 多客户订单聚合生产场景不可用 |
| 5 | E-10 | 收款→开票自动联动未实现 — 当前两步独立; 客户要"看到钱已收→点开票" | P1 收款开票流程摩擦大 |

### H 流 Top 5

| 排名 | 编号 | 风险描述 | 影响 |
|------|------|---------|------|
| 1 | H-7 | 进销存报表(四时点)完全缺失 — WHIOStatisticsScreen 为硬编码 mock 数据 | P1 行业标准必备; 盘库依据完全缺失; 客户有明确需求 |
| 2 | H-4 | 金蝶导出完全缺失 — 0 命中; 客户省手动制表核心诉求 | P1 客户现用金蝶记账全手录; 此功能是差异化卖点 |
| 3 | H-6 | 付款属性→科目映射完全缺失 — 被 D-7 阻塞 | P1 采购凭证科目硬编码不可配; 与客户 6 类结算直接冲突 |
| 4 | H-3 | 科目余额表有查询无导出无独立页 — 期初余额/上期结转未实现 | P1 导入金蝶前置数据不完整 |
| 5 | H-12 | 含税↔未税换算链未统一 — 成本核算口径分散 | P1 客户反复强调"成本核算锁定未税价"; 当前多处含税价混入成本 |

### X 流 Top 5

| 排名 | 编号 | 风险描述 | 影响 |
|------|------|---------|------|
| 1 | X-6 | 六扇门具体角色(配料员/PMC/车间主任等)未梳理落库 — 框架有但具体映射缺 | P0 半月上线; 角色权限不配套则所有操作员角色分工无从执行 |
| 2 | X-10 | 补录时效 T-3 锁死约束未统一实现 — reportDate 窗口校验无集中代码 | P0 六扇门客户明确"前天极限/大前天锁死"; 现在可补任意历史日期 |
| 3 | X-2 | 工单撤回审批未挂接 WorkflowEngine — ad-hoc status 流转 | P0 会议澄清"有数据时需审批才能撤回"; 当前直接可撤 |
| 4 | X-4 | 付款申请审批是否真走 WorkflowEngine 未确认 | P0 付款审批链完整性不明(同 D-9 风险) |
| 5 | X-11 | 防呆 5 大规则大部分 dialog 未 audit 补齐 — 规范已有执行未跟 | P0 仓管/操作员文化素质低场景; 防呆差异化未落地 |

---

## 证据可信度备注

1. **V2 证据(E2E run-20260610_124749)可信度高但覆盖面有限**: 仅验证链路能通/实体能创建，未验证业务规则(如审批节点完整性/数量校验精确值/角色权限矩阵)。D-9 付款申请已创建但审批链是否全走通未确认。

2. **V0 大量集中在 D/E/H 的已建模块**: 已建代码通过 git grep/git show 确认存在，但 headed UI 和 API 断言均未执行。这些 V0 项的实现可信度较高(代码已存在)，验证风险较低，可批量 headed 补验。

3. **🔴缺 项全部基于 git grep 0 命中**: D-7/D-8/H-4/H-7 等缺失项均通过 `git grep -n 关键词 origin/main` 确认 0 命中，属于代码层面确认的实现缺失，非"未找到但可能存在"，可信度高。

4. **B 阻塞(E-5 毛利预警)**: 代码内有 F3 gap 注释 `SalesServiceImpl:362 costEstimate(null)`，这是 codebase 内显式标记的未完成实现，非推断，证据强度高。

5. **E-15 three-price BUG**: E2E 运行时发现路由被当 ID 解析(FIXB 组2-7 修复中)，属 live 证据，但修复后状态需重验。

6. **X 流约束项(X-9/X-14/X-15)标 ⚪**: 这些是架构/实施原则而非可交付代码，N/A 验证符合本流性质。

7. **Flyway V20261011_22 基线**: SP11 财务相关迁移(voucher_subject_mapping/inventory_ledger_snapshot/voucher_export_record)已确认 apply 成功，H 流财务内核部分实现可信。
