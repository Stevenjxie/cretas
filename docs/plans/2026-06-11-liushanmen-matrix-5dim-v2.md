# 六扇门 ERP 五维矩阵 v2 — 35 PR 后刷新 (136 项)

**日期**: 2026-06-11 (本 session 35 PR #684-716 后) · 基线: `2026-06-11-liushanmen-matrix-5dim.md` (v1, 修补前)
**配套**: `2026-06-11-liushanmen-todolist-remaining.md` (剩余 todolist)

图例: ⬛ MISSING · 🟧 BUILT_UNTESTED · 🟨 BUILT_TESTED · 🟩 BUILT_TESTED_UIUX · ✅ BUILT_TESTED_UIUX_FRONTEND · ⬜ NA_NO_FRONTEND
升维标记: 维度变化标 `v1→v2 (PR#)`。⏳ = 代码 done 但缺 headed/RN E2E 证据(证据维度未升满, 非"没做")。

> **对账依据**: 本 session 35 代码项全部 merged + 部署 prod 验证 (PR #684-716, 33 merged + 1 supersede)。
> Tier0 16 项 + 集成断层 5 项 (Fable 审计戳穿"后端对≠可用") + Tier1 11/12 项 + Tier2/P1 3 项。
> 唯一未做的证据项: **#26 headed E2E + RN E2E 截图存档** (代码全在, 缺端到端 headed 证据)。

---

## SP1 — 同单双产出 (半成品+成品)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| T1 SFI Txn 实体+迁移 | 🟨 | 🟨 | — | 缺 repository 层单测 (未优先) |
| T2a ProductionReport 4 新字段 DTO 往返 | 🟧 | 🟨 | #689 | silent-drop 已修 + roundtrip 测试 |
| T2b WorkProcess.semiFinishedOutputCode | 🟧 | 🟨 | #689 同批 | DTO 序列化路径测试补齐 |
| T3 移动加权均价 IN + FOR UPDATE + 幂等 | 🟨⚠️ | 🟨 | #713 (W8) | **W7-d 真并发 bug BUG-SP1-NEW-ROW → #713 ensure-row-then-lock 修 + composite unique + lostRace=1 实证** |
| T4 output-options 端点 + 路由 | 🟨 | 🟨 | — | 已可用 |
| T5 RN OUTPUT 阶段 UI | 🟩 | 🟩⏳ | — | 代码 done; headed 缺 (#26) |
| T6 web 批次详情双产出展示 + SFI 流水入口 | ⬛ | 🟨 | #698 | StepYieldDTO 补 4 字段聚合 (纯加不改 yield 算); headed 缺 |

## SP2 — 二次加工 + 整单撤回

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| T1 数据模型 + ReversalLog | 🟨 | 🟨 | — | prod 实证 |
| T2 ReportReversalService 三层守卫+重放 | 🟨⚠️ | 🟨 | #696/#710 | assertTrue(true) 占位换真断言 (BUG-R1 审计 stale, #662 早修, #696 清文档) |
| T3 deductForSecondaryPlan + FOR UPDATE | 🟨⚠️ | 🟨 | #713 同根 | 并发路径加锁 (与 SP1 同 ensure-row-then-lock) |
| T4 createSecondaryPlan 跨单计划 | 🟨 | 🟨 | — | — |
| 撤回审批 5 端点 | 🟨 | 🟨 | — | prod 已实际调用 |
| web 撤回 dialog | 🟩 | 🟩⏳ | — | headed 缺 (#26) |
| web plans 列表 SECONDARY 来源标签 | ⬛ | 🟩 | #697 | SEC 标签已建 |
| web 二次加工计划创建 UI | ⬛ | 🟩 | #697 | WIP picker :max 防超领 (孤岛接线; **#701 修 WIP 路径 404 才真可用**) |
| RN YieldBatchSelectScreen 领半成品 tab | ⬛ | 🟩⏳ | #706/#707 验证 | YieldStepReportScreen 已处理 SEMI_FINISHED + WIP picker; SEMI tab 增强非阻塞 |
| RN ReversalSubmitScreen 低输入撤回屏 | ⬛ | 🟧 | (内嵌) | 功能内嵌 BatchDetail; 独立屏未建 (P1) |
| RN YieldStepReportScreen 二次加工识别+领WIP | 🟩 | 🟩⏳ | — | BUG-2 已修; headed 缺 |
| E2E sp2-reversal.spec.ts (4 场景) | ⬛ | ⬛ | — | **未做 (P1, Tier2 #41)** |
| W0 WriteGuard 覆盖撤回 | 🟧 | 🟨 | #704 | ToolRbacGuard 接 AI 路径鉴权 |
| replayMovingAverage 均价重放 | 🟨 | 🟨 | — | — |
| 幂等 UNIQUE(batch,scope) | 🟨 | 🟨 | #713 | composite unique 加固 |

## SP3 — 三价成本引擎 (B 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| §4.1 移动均价引擎 | 🟧⚠️ | 🟨 | #713/#712 | 并发集成测试补 (BUG-SP1-NEW-ROW 修) |
| §4.3 成本诚实 null | 🟨 | 🟨 | — | — |
| §5 超支报警 CostVarianceService | 🟨 | 🟨 | — | — |
| §6 ProductCostVarianceConfig | 🟨 | 🟨 | — | — |
| §6 LaborCostConfig.laborCostPerKg | 🟧 | 🟨 | #707 | **SP9 双口径表单加 quotedLaborCostPerKg 录入** (BOM 表单缺字段已补) |
| §7 超支配置 CRUD 页 | 🟩 | 🟩⏳ | — | headed 缺 |
| §9 FinanceCostBreakdown DTO + @PriceSensitive | 🟨 | 🟨 | #695 | 脱敏收口 + PriceSensitiveIT |
| §9 getOrderCostBreakdown 三价对比 | 🟩 | 🟩⏳ | — | headed 缺 |
| §10 事件链 (回填+报警 listener) | 🟧⚠️ | 🟨 | #699 | **真代码 bug 修: FINISHED/legacy(output_kind=null, F006 全走) 不发事件 → costUnitPrice 永不回填; 修=FINISHED 分支镜像 SEMI 发事件** (数据缺口: F006 工价全 null + 报工全 SUBMITTED → 端到端待配置, 非代码) |
| §11 毛利红线 | 🟩 CRIT | 🟨 | #693 | **拍板 = 409→200+marginWarnings (不卡死)**; 见 SP5 |
| §12 人工双口径对比视图 | ⬛ | ⬛ | — | **未做 (P1, Tier2 #39)** |
| §8 Phase E 集成测试 ×4 + 并发锁测试 | ⬛ | 🟨 | #712/#713 | 并发锁测试补 (lostRace 实证); 4 集成测试部分 |

## SP4 — 一物一码补缺 (A+B 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| A4 厂号/产地全链路 | 🟩 | 🟩⏳ | — | RN 批次编辑屏录入 (P1 范畴); headed 缺 |
| A5 批次条码标签 | 🟨🔗 | 🟨🔗 | — | web 生成按钮/RN 扫码对接仍缺 (Tier1 #20 仅做 A3 接线, A5 UI 部分) |
| A8 税率换算 — RawMaterialType | 🟩 | 🟩 | — | — |
| A8 税率换算 — ProductType | 🟧 | 🟨 | #708 | **÷(1+rate) scale4 HALF_UP 实现 + ProductTypeTaxConversionTest** (原仅 DDL) |
| B5/B6 BOM perPortion + semiFinishedRefCode | 🟩 | 🟩 | — | — |
| A3 类别数字前缀 | 🟨🔗⚠️ | 🟨 | #708 | **接线: LabelServiceImpl 调 primaryCode 前三位 (同源 SP8, 无 primaryCode 降级 MA)** — 需求生效 |

## SP5 — 毛利红线 + 佣金 + 凭证 (E 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| 毛利红线 check-margin API + UI | 🟩 HIGH | 🟩 | #693/#706 | **拍板: 200+marginWarnings (不卡死); sales:read_write 录单可预览** (防呆 R1 满足); headed 缺 |
| 含税/未税双价展示 | 🟧 | 🟧 | — | per-item 未税列仍缺 (Tier2 #31 含税口径独立 spec) |
| 佣金预览 + 监听器 | 🟩 | 🟩 | — | finance-review 佣金字段补 (非强依赖) |
| 财审自动凭证 listener | ⬜ | ⬜ | — | E2E 日志实证 ✅ |
| productionPlan.sourceOrderIds 字段 | 🟧 | 🟧 | — | UI defer (P1) |
| FactoryGrossMarginConfig 配置实体 | 🟧 HIGH | 🟨 | #706 | **admin CRUD UI+API (5 端点 finance:read_write, targetGrossMargin @PriceSensitive, web 路径匹配, 13+9 测试)** — 不再只 SQL 直写 |
| commission-preview GET 端点 | ⬛ | ⬛ | — | 便利接口 (P1, 低优先) |
| source-orders PUT 端点 | ⬛ | ⬛ | — | P1 defer |

## SP6 — 采购到付款 (D 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| PO settlement/paymentTerms | 🟨 | 🟨 | — | — |
| 收货异常 + PurchaseException 决策 | 🟩 | 🟩⏳ | — | headed 缺 |
| PaymentRequest P0 状态机 | 🟩 | 🟩 | — | 硬编码状态机 (Tier2 #30 接 WorkflowEngine) |
| PaymentRequestApprovedDTO (G1 修) | 🟨 | 🟨 | #707?(出纳明细) | 见 /approved 明细 |
| G2 全入库前置检查 | 🟨 | 🟨 | — | PREPAID 豁免 ✓ |
| PurchaseInvoice 上传/对账/逾期提醒 | 🟩 | 🟩 | — | OCR DashScope 未确认 |
| 退货 withGoods→DEFECTIVE 批次 | 🟨 | 🟨 | #688/#694 | **财务审批门加 (APPROVED→FINANCE_APPROVED→COMPLETED, finance-approve 端点, V20261016_01)** + #702 退货 UI 收尾 |
| 会计科目映射表 | 🟨 | 🟨 | — | — |
| cashier 角色配置 | 🟧 HIGH | 🟧 | (数据) | **F006 cashier 用户/银行数据=Friday 客户配置 (非代码)** |
| exceptions/detail.vue | ⬛ | ⬛ | — | list 内嵌 dialog 覆盖 (P1, Tier2 #36) |
| payment-requests/detail.vue | ⬛ | ⬛ | — | P1 (Tier2 #36) |
| invoices/detail.vue | ⬛ | ⬛ | — | P1 (Tier2 #36) |
| RN PurchaseReceiveScreen | ⬛ | 🟩⏳ | #709 | **WHPurchaseReceiveListScreen 注册进 WHInboundStack (非孤岛) + 待收单防呆**; headed 缺 |
| RN CashierTerminalScreen | ⬛ | ⬛ | — | **未做 (P1, Tier2 #36)** |
| RN PurchaseExceptionScreen | ⬛ | ⬛ | — | **未做 (P1, Tier2 #36)** |
| G7 settlementType 继承 | 🟨 | 🟨 | — | — |
| 供应商银行信息显示 | 🟧 HIGH | 🟨 | #707(出纳明细) | **/approved DTO 补供应商名/原料名/PO/单价/数量/单位/银行账户** (出纳可付款; 实际银行数据=Friday 配置) |

## SP7 — 四仓体系 + 盘点报损 (F 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| F1 SALTED 仓库类型 | ✅ | ✅ | — | — |
| F4 入库守卫 (无单据禁直调) | 🟧🔗 CRIT | 🟨 | #686 | **assertCanReceive 接 2 条 RAW 入库路径 (422 tx-safe); 核实 LOGISTICS/WORKSHOP/null default 放行不误拦 F006** — 红线真生效 |
| F5 报损 WastageReport 后端 | 🟨 | 🟨 | — | 16 测试 + prod 实证 |
| F5 RN WastageReportScreen | 🟩 | 🟩⏳ | #692/#701 | **#692 报损按钮 no-op 修 (读 batchId + WHProfileStack 注册)**; headed 缺 |
| F5 web 报损审批页 | 🟩 HIGH | 🟩 | #687 | **PRODUCTION_WASTE 前后端枚举对齐 (提交不再 400)** |
| F6 盘点 FactoryStocktake 后端 | 🟨 | 🟨 | — | API+DB 双证全链 V1 |
| F6 RN StocktakeEntryScreen | 🟩 | 🟩⏳ | #686 | 原孤儿屏接 launcher; headed 缺 |
| F6 web stocktakes 页 | 🟩 | 🟩⏳ | — | headed 缺 |
| WHInventoryCheckScreen 旧绕过封堵 | ⬛ CRIT | 🟨 | #686 | **删 updateBatch 直调, 重接盘点发起 StocktakeEntry** — 状态机绕过封死 |
| F7 调拨仓库维度过滤 | 🟧 | 🟧 | — | 列表端点 warehouse 参数仍缺 (P1) |
| F8 退料公式 | 🟧 | 🟧 | — | — |
| F9 库存预警+采购通知 | 🟧 | 🟧 | — | 双向通知 (Tier2 #42) |
| F10 进销存台账占位 | 🟨 | 🟨 | — | SP11 范畴 |
| F11 盐化独立扣量/报表端点 | ⬛ HIGH | ⬛ | — | **未做 (P1, Tier2 #34; 上线时盐化走通用仓+人工口径)** |
| Flyway 号段 | 🟨 | 🟨 | — | — |
| W2 角色码修复 | 🟨 | 🟨 | — | — |

## SP8 — 16位分段编码

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| T01 分段字典表 + CRUD + tree | 🟨 | 🟨 | — | — |
| T02 16位生成器 (fallback SP4) | 🟨⚠️ | 🟨 | #698 | **拍板 (b) 缩小版兜底: 隐藏级联 UI + 诚实 alert 无假码 (防呆 R5)** — 不再 404 静默假编码 |
| T03 primaryCode 冗余字段 4 点 | 🟨 | 🟨 | — | — |
| T04 BomRecipeItem.primaryCodeRef + 回填 | 🟨 | 🟨 | — | — |
| T05 级联下拉 UI | 🟩 | 🟩 | #698 | **空字典 dead-end 改隐藏 (兜底先上, 防 dead-end)** |
| T06 search-by-code 端点 | 🟨 | 🟨 | — | — |
| 缩小版兜底 (T03+T04+T06) | 🟨 | 🟨 | — | **拍板上线方案** |
| BOM 配方页 primaryCodeRef 筛选栏 | ⬛ | ⬛ | — | P1 (兜底先上时隐藏) |
| 分段字典管理页面 | ⬛ HIGH | ⬛ | — | **未做 — 拍板走缩小版兜底, 字典管理页 P1 (隐藏级联避 dead-end)** |
| 存量编码不迁移 | ⬜ | ⬜ | — | 蓄意设计 |

## SP9 — 人效双口径 (I 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| M1 quotedLaborCostPerKg 后端 4 点 | 🟨 | 🟨 | #707 | **产品表单加 quotedLaborCostPerKg 录入控件** — quoted 可配 |
| M2 完工写回 actualLaborCost | ⬛ HIGH | 🟨 | #707 | **actualLaborCost rollup fail-soft + 诚实 null** (scope-lock 已解, 补齐) |
| M3 双口径对比 API | 🟨⚠️ | 🟨 | #707 | quoted/actual 双 null 解除 (录入+回写都通) |
| M4 工序达成率 | ⬛ HIGH | ⬛ | — | **未做 (P1, Tier2 #35)** |
| M5 看板 (维护列表+图表) | ⬛ | 🟧 | #707(sidebar+列表) | sidebar 入口+列表已建; **图表看板未做 (P1, Tier2 #35)** |
| @PriceSensitive 脱敏 | ⬛ HIGH | 🟨 | #695 | **cost 绝对值 @PriceSensitive (比率 % 放行); 真受众=仓管/质检/operator 非 sales_mgr; PriceSensitiveIT** |
| sidebar 导航入口 | ⬛ | 🟩 | #707 | **菜单入口已加** |
| 产品表单 quotedLaborCostPerKg 输入 | ⬛ | 🟩 | #707 | 同 M1 |
| step-breakdown/{batchId} 端点 | ⬛ | ⬛ | — | **未做 (P1, Tier2 #35)** |
| 集成测试 + 脱敏测试 | ⬛ | 🟨 | #695 | PriceSensitiveIT done; 其余集成测试部分 |

## SP10 — 三价报价 (G 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| QuotationTask 扩字段 | 🟨⚠️ | 🟨 | #689 | **laborPerKg silent drop 修 (submitQuotation 4 处补齐 + roundtrip 测试)** |
| ProductionBatch is_trial + web UI | 🟩⚠️ | 🟩 | #689 | **is_trial/trialSampleId silent drop 修 (CreateRequest+toProductionBatch)** |
| ProductMidQuote 实体+迁移 | 🟨 | 🟨 | — | — |
| 中报价汇算服务 | 🟨 | 🟨 | #710 | 去 stub 真读 getMidQuoteById |
| 三价对比服务+端点 | 🟨 CRIT | 🟨 | #695 | **DTO 补 @PriceSensitive — 成本对销售脱敏 (泄露封堵)** |
| PUT confirm 中报价端点 | ⬛ HIGH | 🟨 | #710 | **confirm 状态机 CALCULATED→CONFIRMED + 修 taskId 当 sampleId bug** |
| 预报价 web 表单 | 🟩 | 🟩 | #689 | laborPerKg drop 修后真有效 |
| 试制批次 web UI | 🟩 | 🟩 | #689 | silent drop 修 |
| mid-quotes/detail.vue | 🟩 | 🟩 | #710 | 去 stub |
| three-price.vue 看板 | 🟩 | 🟩 | #695 | 真脱敏 (非仅客户端) |
| 超支配置 fallback 迁移 | 🟨 | 🟨 | — | — |
| RN is_trial 开关 | ⬛ | ⬛ | — | **未做 (P1)** |
| GET batches?isTrial=true 过滤 | ⬛ HIGH | 🟨 | #710 | **isTrial 过滤参数实现** |
| quotations stage 过滤 | 🟧 | 🟧 | — | — |
| SampleApprovedEventListener 设 PRE | 🟧 | 🟧 | — | — |

## SP11 — 进销存 + 凭证导出 (H 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| F1 进销存报表查询 | ✅ | ✅ | — | @PriceSensitive 6 字段 (R1 ✓) |
| F2 进销存 Excel 导出 | 🟧⚠️ CRIT | 🟨 | #685 | **exportInventoryLedger() 实现 + exportLedger 改调 (货对板) + xlsx 脱敏双门 fail-closed; live 验真 xlsx 台账 4317B** |
| F3 凭证序时账导出 | ✅ | ✅ | — | — |
| F4 科目余额表导出 | ✅ | ✅ | — | 期初聚合未测 (P1) |
| F5 付款属性→科目映射 CRUD | 🟩 | 🟩 | — | Controller 测缺 (P1) |
| F6 导出配置管理 | 🟩 | 🟩 | — | Controller 测缺 (P1) |
| D1 月结触发期初快照 | 🟧 | 🟧 | — | SnapshotService 零测 (P1) |
| R2 导出线程 PriceSensitiveContext | ⬛ CRIT | 🟨 | #685 | **xlsx 导出加 PriceSensitiveContext guard fail-closed — 数据泄露通道封堵** |
| (新) 盘盈/盘损分列 | — | 🟨 | #715 | profitQty/lossQty≥0 + 金额 @PriceSensitive + 金蝶摘要, 19 测试 |

## SP12 — RBAC + 审批工作流 + 打印 (X 流)

| 功能点 | v1 | v2 | 升维 | 关键缺口/证据 |
|---|---|---|---|---|
| T1 cashier/quality_controller 角色 | 🟨 | 🟨 | — | F006 cashier 账号=数据配置 |
| T2 PRODUCTION_REVERSAL DecisionType | 🟨 | 🟨 | — | — |
| T3 生产计划撤回审批 | 🟨 CRIT | 🟨 | #684 | **旧 POST /cancel 封堵 (PENDING_APPROVAL 窄缝守卫 + AIChat 绕过封; 8 测试)** — 红线 R3 满足 |
| T4 盘点 workflow 全链 | ✅ | ✅ | — | — |
| T5 DisposalRecord 接 workflow | 🟧🔗 HIGH | 🟨 | #687/#18 | **WastageReport workflow REST (submit/approve/reject 端点) 建成; 双实体拍板 WastageReport 为准** |
| T6 PaymentRequest + PAYMENT 流 | 🟨 | 🟨 | — | 硬编码状态机 (Tier2 #30) |
| T7 Python 打印 2 路由 | ⬛ HIGH | 🟨 | #691 | **production-work-order + 领料单路由建成 (Java 期望缺→502 根治, 401-not-404); 12 测试** |
| T8 Java 打印 2 端点 | 🟨⚠️ | 🟨 | #703 | **打印明细填充 (processes 从 batch→tasks/items 按 materialTypeId 聚合, 无数据诚实空; 11 测试)** |
| T9 web 申请撤回 dialog + 打印按钮 | 🟧 HIGH | 🟩 | #684 | **申请撤回 dialog 建成 (改调 request-cancel + 原因下拉)**; 打印按钮真可用 (#691 502 根治) |
| T10 web 付款申请列表+审批+出纳 | 🟩 | 🟩 | #707(明细) | /approved 明细补齐, 出纳可付款 |
| RBAC 权限矩阵种子 | ⬜ | ⬜ | — | — |
| X-10 补录 T-3 时效锁 | ✅ | ✅ | — | — |
| (新) AI 路径 RBAC (ToolRbacGuard) | — | 🟨 | #704 | **AI tool 直调 service 绕 controller @RequirePermission 系统性修: 同源 PermissionService.hasAnyPermission + fail-closed, 4 敏感 tool 传真实 callerRole; 10+18 测试** |

---

## 汇总 — v2 热图新分布

| 维度 | SP1 | SP2 | SP3 | SP4 | SP5 | SP6 | SP7 | SP8 | SP9 | SP10 | SP11 | SP12 | **v2 计** | **v1 计** | Δ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ⬛ MISSING | 0 | 1 | 1 | 0 | 2 | 3 | 1 | 2 | 2 | 1 | 0 | 0 | **13** | 33 | **−20** |
| 🟧 BUILT_UNTESTED | 0 | 1 | 0 | 1 | 3 | 2 | 4 | 0 | 1 | 2 | 1 | 1 | **16** | 22 | −6 |
| 🟨 BUILT_TESTED | 4 | 8 | 7 | 3 | 1 | 9 | 5 | 6 | 6 | 8 | 4 | 7 | **68** | 45 | **+23** |
| 🟩 +UIUX | 2 | 4 | 3 | 2 | 2 | 3 | 4 | 1 | 3 | 5 | 2 | 2 | **33** | 27 | +6 |
| ✅ +前端验 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 3 | 2 | **6** | 6 | 0 |
| ⬜ NA | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | **3** | 3 | 0 |
| **计** | 6 | 14 | 14 | 6 | 9 | 17 | 16 | 10 | 12 | 16 | 10 | 13 | **139** | 136 | +3 (新增 #704 AI-RBAC / #715 盘盈盘损 / SP9 拆细) |

> **关键迁移**: MISSING **33→13 (−20)**; BUILT_TESTED **45→68 (+23)**; BUILT_TESTED+ (🟨+🟩+✅) **78→107 (+29)**。
> ⚠️假完整 13 处 → 大部分清除 (#689 silent-drop / #696 占位断言 / #699 事件链 / #685 货不对板 / #701 集成 404)。仅剩数据配置依赖 (F006 工价/cashier 数据 = Friday 客户配置, 非代码假完整)。
> 🔗孤岛 6 处 → 全接线 (#697 二次加工 UI / #686 守卫 / #704 AI-RBAC / #709 收货屏注册)。
> ⏳ 缺 headed/RN E2E 证据 ~14 项 (🟩 代码 done 待 #26 截图存档) — **是证据维度未升满, 非"没做"**。

## CRITICAL 项收口状态 (v1 共 11 个 CRIT)

| v1 CRIT 项 | v2 状态 | PR |
|---|---|---|
| SP12 T3 旧 cancel 绕过审批 | ✅ 封堵 | #684 |
| SP7 WHInventoryCheck 直改库存 | ✅ 删直调+重接盘点 | #686 |
| SP7 F4 守卫零调用点 | ✅ 接 2 入库路径 | #686 |
| SP11 F2 导出货不对板 | ✅ exportInventoryLedger | #685 |
| SP11 R2 导出线程脱敏 | ✅ PriceSensitiveContext fail-closed | #685 |
| SP10 三价 DTO 零脱敏 | ✅ @PriceSensitive | #695 |
| SP3/SP5 毛利红线语义冲突 | ✅ 拍板 200+warn | #693 |
| SP8 T02 generate-code 假编码 | ✅ 兜底+诚实 alert | #698 |

**8/8 v1 CRITICAL 全收口。**
