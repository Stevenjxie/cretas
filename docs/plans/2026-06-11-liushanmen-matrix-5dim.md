# 六扇门 ERP 五维矩阵 — SP × 功能点 × 成熟度 (136 项)

**日期**: 2026-06-11 · 配套: `2026-06-11-liushanmen-5dim-audit.md` (综合判断) / `2026-06-11-liushanmen-todolist.md` (优先级)

图例: ⬛ MISSING · 🟧 BUILT_UNTESTED · 🟨 BUILT_TESTED · 🟩 BUILT_TESTED_UIUX · ✅ BUILT_TESTED_UIUX_FRONTEND · ⬜ NA_NO_FRONTEND
标记: ⚠️假完整 (测试 mock 真实路径/占位/下游断链) · 🔗孤岛 (建好未接线)

---

## SP1 — 同单双产出 (半成品+成品)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| T1 SFI Txn 实体+迁移 V20261010_01/02/03 | 🟨 | MED | 缺 repository 层单测 |
| T2a ProductionReport 4 新字段 DTO 往返 | 🟧 | MED | 无 roundtrip 专测, silent-drop 风险 |
| T2b WorkProcess.semiFinishedOutputCode | 🟧 | LOW | DTO 序列化路径无测 |
| T3 移动加权均价 IN + FOR UPDATE + 幂等 | 🟨⚠️ | HIGH | 并发仅 mock-verify, 无真实 DB 锁测试 |
| T4 output-options 端点 + 路由 | 🟨 | LOW | 4 场景测试; 实现位置偏 spec 但等价 |
| T5 RN OUTPUT 阶段 UI (自动检测 outputKind) | 🟩 | MED | 自动选择优于 spec 手动3选1 (符合客户原话); 无 headed |
| T6 web 批次详情双产出展示 + SFI 流水入口 | ⬛ | HIGH | 完全未实施, 运营可见性盲区 |

## SP2 — 二次加工 + 整单撤回

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| T1 数据模型 V20261011_07~10 + ReversalLog | 🟨 | LOW | prod 实证 3 条 DONE; 34 单测 |
| T2 ReportReversalService 三层守卫+重放 | 🟨⚠️ | MED | BUG-R1/R2 用 assertTrue(true) 占位, CI 不报警 |
| T3 deductForSecondaryPlan + FOR UPDATE | 🟨⚠️ | MED | spec 要求的并发用例未实现 |
| T4 createSecondaryPlan 跨单计划 | 🟨 | LOW | 6 测试 |
| 撤回审批 5 端点 (POST/GET/approve/reject/wip) | 🟨 | LOW | prod 已实际调用 |
| web 撤回 dialog (内嵌 batches/detail) | 🟩 | LOW | 5 原因 dropdown + sticky 409; 无 headed |
| web plans 列表 SECONDARY 来源标签 | ⬛ | MED | 无关键字命中 |
| web 二次加工计划创建 UI | ⬛ | HIGH | POST /secondary-plan 端点孤岛, 厂长无入口 |
| RN YieldBatchSelectScreen 领半成品 tab | ⬛ | HIGH | 文件无 tab/SEMI 关键字 |
| RN ReversalSubmitScreen 低输入撤回屏 | ⬛ | MED | 功能内嵌 BatchDetailScreen 可部分弥补 |
| RN YieldStepReportScreen 二次加工识别+领WIP | 🟩 | LOW | batchSourceType BUG-2 已修; 无 headed |
| E2E sp2-reversal.spec.ts (4 场景) | ⬛ | MED | 无文件 |
| W0 WriteGuard 覆盖撤回 | 🟧 | LOW | REST 端点不走 AI tool 路径, spec 假设存疑 |
| replayMovingAverage 均价重放 | 🟨 | LOW | RP-1/2/3 数值验证 |
| 幂等 UNIQUE(batch,scope) | 🟨 | LOW | — |

## SP3 — 三价成本引擎 (B 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| §4.1 移动均价引擎 (postSemiOutputLedger) | 🟧⚠️ | HIGH | 算术有测; 并发/集成全缺 (MovingAvgCostIT 不存在) |
| §4.3 成本诚实 null 不写假 0 | 🟨 | LOW | SC-7 覆盖 |
| §5 超支报警 CostVarianceService | 🟨 | MED | 阈值三级链+精度有测; 无集成 |
| §6 ProductCostVarianceConfig 实体+迁移 | 🟨 | LOW | V20261010_20 |
| §6 LaborCostConfig.laborCostPerKg | 🟧 | HIGH | 后端 4 处齐; **BOM 前端表单无此字段, 无法配置** |
| §7 超支配置 CRUD 页 | 🟩 | LOW | 无 headed |
| §9 FinanceCostBreakdown DTO + @PriceSensitive | 🟨 | MED | PriceSensitiveIT 缺 — 脱敏未被证明 |
| §9 getOrderCostBreakdown 三价对比 | 🟩 | MED | E-6 审计标 V0 未充分验证 |
| §10 事件链 (回填+报警 listener) | 🟧⚠️ | HIGH | 零测试; B-47: 测试环境 unitCost=null 链未通 |
| §11 毛利红线 | 🟩 | **CRIT** | 实现 409 阻断 vs spec "不卡死" — 语义冲突需拍板 (归 SP5) |
| §12 人工双口径对比视图 | ⬛ | HIGH | 无前端实现 |
| §8 Phase E 集成测试 ×4 + 并发锁测试 | ⬛ | HIGH | 全缺, 仅 Mockito |

## SP4 — 一物一码补缺 (A+B 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| A4 厂号/产地全链路 | 🟩 | MED | RN 详情只读有; **RN 批次编辑屏无录入** |
| A5 批次条码标签 (生成+扫码端点) | 🟨🔗 | HIGH | web 无生成按钮 + RN 未对接 scan → 业务价值 UI 层不可用 |
| A8 税率换算 — RawMaterialType 路径 | 🟩 | LOW | 较完整 |
| A8 税率换算 — ProductType 路径 | 🟧 | HIGH | **仅 DDL**; service/UI/测试零实现 |
| B5/B6 BOM perPortion + semiFinishedRefCode | 🟩 | LOW | 无 HTTP 集成测试 |
| A3 类别数字前缀 | 🟨🔗⚠️ | HIGH | 15 测试全绿但 LabelService 未调用 → 标签仍 'MA', 需求未生效 |

## SP5 — 毛利红线 + 佣金 + 凭证 (E 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| 毛利红线 check-margin API + UI | 🟩 | HIGH | 公式偏离 spec (实现更对); sales_rep 被门控排除→无预警被 409 硬拒 (违反防呆 R1) |
| 含税/未税双价展示 | 🟧 | MED | 汇总有"不含税"; per-item 未税列缺; getLineAmountWithTax 无测 |
| 佣金预览 + 监听器 | 🟩 | MED | finance-review 页缺佣金字段; 独立端点缺 (非强依赖) |
| 财审自动凭证 listener | ⬜ | LOW | E2E 日志实证 V-2026-0019 生成 ✅ |
| productionPlan.sourceOrderIds 字段 | 🟧 | LOW | spec 已 defer UI; 写入端点未建 |
| FactoryGrossMarginConfig 配置实体 | 🟧 | HIGH | **无 admin CRUD UI/API — 红线参数只能 SQL 直写** |
| commission-preview GET 端点 | ⬛ | LOW | 便利接口 |
| source-orders PUT 端点 | ⬛ | LOW | P1 defer |

## SP6 — 采购到付款 (D 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| PO settlement/paymentTerms 字段 | 🟨 | MED | — |
| 收货异常 + PurchaseException 决策 | 🟩 | MED | 17 测试 + web 决策 dialog |
| PaymentRequest P0 状态机 | 🟩 | HIGH | 硬编码状态机非 WorkflowEngine; 34 测试 |
| PaymentRequestApprovedDTO (G1 修) | 🟨 | HIGH | 已修; 但 bank info 实测 null (见下) |
| G2 全入库前置检查 | 🟨 | HIGH | PREPAID 豁免 ✓ |
| PurchaseInvoice 上传/对账/逾期提醒 | 🟩 | MED | OCR 实接 DashScope 未确认 |
| 退货 withGoods→DEFECTIVE 批次 | 🟨 | MED | **无财务审批门 (转录明确要求)** |
| 会计科目映射表 V20261010_11 | 🟨 | LOW | — |
| cashier 角色配置 | 🟧 | HIGH | F006 零 cashier 用户 → 出纳链不可 E2E |
| exceptions/detail.vue | ⬛ | LOW | list 内嵌 dialog 覆盖主流程 |
| payment-requests/detail.vue | ⬛ | MED | — |
| invoices/detail.vue | ⬛ | LOW | — |
| RN PurchaseReceiveScreen | ⬛ | HIGH | 仓管移动收货主触点缺失 |
| RN CashierTerminalScreen | ⬛ | HIGH | 出纳只能 web |
| RN PurchaseExceptionScreen | ⬛ | MED | — |
| G7 settlementType 继承 | 🟨 | MED | 已修 |
| 供应商银行信息显示 | 🟧 | HIGH | 实测 null, 出纳不知打款到哪 |

## SP7 — 四仓体系 + 盘点报损 (F 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| F1 SALTED 仓库类型 | ✅ | LOW | — |
| F4 入库守卫 (无单据禁直调) | 🟧🔗 | **CRIT** | **生产代码零调用点 — 红线形同虚设** |
| F5 报损 WastageReport 后端 | 🟨 | MED | 16 测试 + prod 实证; 无 headed |
| F5 RN WastageReportScreen | 🟩 | LOW | 防呆 F2/F3/F7 满足 |
| F5 web 报损审批页 | 🟩 | HIGH | **PRODUCTION_WASTE 前后端枚举不一致 → 提交 400** |
| F6 盘点 FactoryStocktake 后端 | 🟨 | MED | API+DB 双证全链 V1 (并 SP12 T4) |
| F6 RN StocktakeEntryScreen | 🟩 | LOW | — |
| F6 web stocktakes 页 | 🟩 | LOW | — |
| WHInventoryCheckScreen 旧绕过封堵 | ⬛ | **CRIT** | **第141行仍直调 updateBatch 绕过状态机** |
| F7 调拨仓库维度过滤 | 🟧 | MED | 列表端点无 warehouse 参数 |
| F8 退料公式 | 🟧 | LOW | 实际逻辑已在 (超 spec 占位范围) |
| F9 库存预警+采购通知 | 🟧 | MED | 双向通知未确认 |
| F10 进销存台账占位 | 🟨 | LOW | 实际完整 (SP11 范畴) |
| F11 盐化独立扣量/报表端点 | ⬛ | HIGH | 转录 4 次强调, 零实现 |
| Flyway 号段 V20261010_22-24 | 🟨 | LOW | 偏 spec 规划号但功能正确 |
| W2 角色码修复 | 🟨 | LOW | — |

## SP8 — 16位分段编码

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| T01 分段字典表 + CRUD + tree | 🟨 | MED | Controller 层无测 |
| T02 16位生成器 (fallback SP4) | 🟨⚠️ | HIGH | **generate-code 预览端点后端不存在 → 前端 404 静默降级假编码** |
| T03 primaryCode 冗余字段 4 点 | 🟨 | LOW | — |
| T04 BomRecipeItem.primaryCodeRef + 回填 | 🟨 | MED | 服务层 auto-backfill 无测 |
| T05 级联下拉 UI | 🟩 | MED | 空字典 dead-end 仅文字提示 (违防呆 R5) |
| T06 search-by-code 端点 | 🟨 | LOW | — |
| 缩小版兜底 (T03+T04+T06) | 🟨 | LOW | 可用 |
| BOM 配方页 primaryCodeRef 筛选栏 | ⬛ | MED | spec 承诺 ✅ 未建 |
| 分段字典管理页面 | ⬛ | HIGH | **无 UI 入口, 字典只能 API 直建 → 级联永远空, P0 阻断** |
| 存量编码不迁移 | ⬜ | LOW | 蓄意设计 |

## SP9 — 人效双口径 (I 流) — 最薄弱 SP

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| M1 quotedLaborCostPerKg 后端 4 点 | 🟨 | HIGH | 8 测试; **产品编辑 UI 无录入控件 → quoted 永远 null** |
| M2 完工写回 actualLaborCost | ⬛ | HIGH | SP1 scope-lock 已解未补 → actual 永远 null |
| M3 双口径对比 API | 🟨⚠️ | HIGH | 8 mock 测试绿, 但 quoted/actual 双 null → 功能空转 |
| M4 工序达成率 | ⬛ | HIGH | 字段占位 null, 端点不存在 (P1 承诺) |
| M5 看板 (维护列表+图表) | ⬛ | MED | 单 tab 表格, 客户期望图表 |
| @PriceSensitive 脱敏 | ⬛ | HIGH | **零实现, 销售可读实际人工成本** |
| sidebar 导航入口 | ⬛ | MED | 路由在, 菜单无 → 用户发现不了 |
| 产品表单 quotedLaborCostPerKg 输入 | ⬛ | HIGH | — |
| step-breakdown/{batchId} 端点 | ⬛ | MED | 客户明确要逐工序拆分 |
| 集成测试 + 脱敏测试 | ⬛ | MED | spec 用例 ~50% 未实现 |

## SP10 — 三价报价 (G 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| QuotationTask 扩字段 V20261011_15 | 🟨⚠️ | HIGH | **laborPerKg silent drop (service/convertToDTO 缺 2 处)** |
| ProductionBatch is_trial 字段+web UI | 🟩⚠️ | HIGH | **CreateRequest 无字段 → 勾选不落库 (silent drop)** |
| ProductMidQuote 实体+迁移 | 🟨 | MED | 反射测试; varianceThresholdPct 列一致性未确认 |
| 中报价汇算服务 | 🟨 | MED | spec 承诺自动汇算移动均价, 实现降级手填 |
| 三价对比服务+端点 | 🟨 | **CRIT** | **DTO 零 @PriceSensitive — 成本对销售全量泄露** |
| PUT confirm 中报价端点 | ⬛ | HIGH | GET 详情也是 stub |
| 预报价 web 表单 | 🟩 | LOW | laborPerKg 因 silent drop 实际无效 |
| 试制批次 web UI | 🟩 | HIGH | 见 silent drop |
| mid-quotes/detail.vue | 🟩 | MED | 试制下拉因后端无过滤返全量 |
| three-price.vue 看板 | 🟩 | MED | 软脱敏仅客户端 |
| 超支配置 fallback 迁移 | 🟨 | LOW | 阈值无 UI 管理 |
| RN is_trial 开关 | ⬛ | MED | — |
| GET batches?isTrial=true 过滤 | ⬛ | HIGH | 前端依赖, 后端无实现 |
| quotations stage 过滤 | 🟧 | LOW | — |
| SampleApprovedEventListener 设 PRE | 🟧 | LOW | 依赖 DB DEFAULT |

## SP11 — 进销存 + 凭证导出 (H 流) — 最成熟 SP, 但双 CRITICAL

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| F1 进销存报表查询 | ✅ | HIGH | API 实证 200; @PriceSensitive 6 字段 (R1 ✓) |
| F2 进销存 Excel 导出 | 🟧⚠️ | **CRIT** | **调错方法 → 下载凭证流水非进销存 (货不对板)** |
| F3 凭证序时账导出 (金蝶/用友) | ✅ | HIGH | API 实证 5242B; 但 R2 缺 (见下) |
| F4 科目余额表导出 | ✅ | HIGH | 期初余额聚合未测 |
| F5 付款属性→科目映射 CRUD | 🟩 | MED | Controller 测缺 |
| F6 导出配置管理 (R5 唯一约束 ✓) | 🟩 | MED | Controller 测缺 |
| D1 月结触发期初快照 | 🟧 | MED | hook 在; SnapshotService 零测 — 快照错会污染后续所有期初 |
| R2 导出线程 PriceSensitiveContext | ⬛ | **CRIT** | **xlsx 下载绕过 JSON 层脱敏 = 数据泄露通道** |

## SP12 — RBAC + 审批工作流 + 打印 (X 流)

| 功能点 | 维度 | 严重度 | 关键缺口/证据 |
|---|---|---|---|
| T1 cashier/quality_controller 角色 | 🟨 | LOW | F006 无 cashier 实际账号 |
| T2 PRODUCTION_REVERSAL DecisionType | 🟨 | MED | wired=false |
| T3 生产计划撤回审批 | 🟨 | **CRIT** | **旧 POST /cancel 未封, 任意角色绕过审批 (红线 R3 违反)** |
| T4 盘点 workflow 全链 | ✅ | LOW | audit 全链 V1 实证 |
| T5 DisposalRecord 接 workflow | 🟧🔗 | HIGH | submitForApproval 死代码; 旧 approve 直批; moduleCode 与 spec 不符 |
| T6 PaymentRequest + PAYMENT 流 | 🟨 | HIGH | 硬编码状态机非 WorkflowEngine; workflowInstanceId 恒 null |
| T7 Python 打印 2 路由 | ⬛ | HIGH | **完全缺失 → Java/UI 调用必 502 (audit 实证)** |
| T8 Java 打印 2 端点 | 🟨⚠️ | MED | 8 测试但下游断链; Sprint6 stub 未换 |
| T9 web 申请撤回 dialog + 打印按钮 | 🟧 | HIGH | 打印按钮在 (必502); **申请撤回 dialog 完全未建, 仍调旧 cancel** |
| T10 web 付款申请列表+审批+出纳 | 🟩 | MED | /approved 明细字段缺, 出纳无法实际付款 |
| RBAC 权限矩阵种子 | ⬜ | LOW | R1 warehouse_worker 无 adjust ✓ |
| X-10 补录 T-3 时效锁 | ✅ | LOW | API 409/200 实证 |

---

## 汇总

| 维度 | SP1 | SP2 | SP3 | SP4 | SP5 | SP6 | SP7 | SP8 | SP9 | SP10 | SP11 | SP12 | 计 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ⬛ MISSING | 1 | 5 | 2 | 0 | 2 | 6 | 2 | 2 | 8 | 3 | 1 | 1 | **33** |
| 🟧 BUILT_UNTESTED | 2 | 1 | 3 | 1 | 3 | 2 | 4 | 0 | 0 | 2 | 2 | 2 | **22** |
| 🟨 BUILT_TESTED | 3 | 7 | 4 | 2 | 0 | 6 | 5 | 6 | 2 | 5 | 0 | 5 | **45** |
| 🟩 +UIUX | 1 | 2 | 3 | 3 | 2 | 3 | 4 | 1 | 0 | 5 | 2 | 1 | **27** |
| ✅ +前端验 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 3 | 2 | **6** |
| ⬜ NA | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | **3** |
| **计** | 7 | 15 | 12 | 6 | 8 | 17 | 16 | 10 | 10 | 15 | 8 | 12 | **136** |

⚠️假完整 13 处 / 🔗孤岛 6 处 — 明细见主审计文件 §3 / §2(X-3)。

---

# 🔄 修订层 — 2026-06-12（#754-771 + organizer gate 后）

> **上面 136 项是 2026-06-11 快照**，其后 18 PR #754-771 + 早期 Wave4-W9(#696-719) 修了一批。本修订层**不重写原快照**（留审计轨迹），只标"哪些 gap 有 PR 声称修复 + 当前验证强度"。
>
> **⛔ 诚实纪律**（本项目反复栽的坑）: **PR-merged ≠ real-path-verified**。所以分三档，**不把"PR 合了"当"✅前端验"**：
> - ✅**LIVE**: organizer 今晚 prod 真实路径/SQL/jar 坐实
> - 🟡**CLAIMED**: PR 已 merge+部署，但未在真实数据路径验证 → **降级为"🟧 BUILT_UNTESTED(claimed)"**，进 Codex §7 测试靶
> - 🔴**OPEN**: 无 PR 触及或确认仍开

## A. organizer 今晚 LIVE 坐实（✅ 升维，有硬证据）

| 原矩阵项 | 原维度 | 新维度 | 证据(2026-06-11 organizer gate) |
|---|---|---|---|
| SP6 cashier 角色配置 / SP12-T10 出纳付款 | 🟧 HIGH "F006零cashier→出纳链不可E2E" | **✅ LIVE** | #772: web PERMISSION_MATRIX 补 cashier 行 + DB L1 cashier\|sales\|r(id391)；headed 真登录 f006_cashier 三付款页全直达不403(修前403)；prod 建 APPROVED 付款 PR-F006-20260611-5424 出纳可演 mark-paid |
| SP5 含税/未税双价 + 财审自动凭证 | 🟧/⬜ | **✅ LIVE** | 凭证三行 V-2026-0054 SQL 坐实(借1122/4520·贷6001/4000·贷2221.01/520 平衡) |
| SP4/SP8 BOM packQtyPerProduct(包材每产品用量) | (gap #769) | **✅ LIVE** | 部署 bundle 含「每产品用量」`v-if=PACKAGING` 条件渲染；切包材类别真出现(截图坐实) |
| SP5 productionPlan.sourceOrderIds 多SO回填 | 🟧 LOW "写入端点未建" | **✅ LIVE(代码)** | #771 OrderCostBackfillListener 遍历 getSourceOrderIds() 在 jar；单SO回填日志真落库。**多SO端到端经"加号"路径未坐实→留 Codex §7 靶1/D2-A1** |
| (非矩阵项) 新建SO单价落0 | — | **✅ LIVE 修复** | 根因=遗留 AUD3_PROBE 审计定价策略(TIERED 100%折扣 maxQty100)毒化所有≤100量F006订单；organizer disable+重验落价68。**真 live bug 非测试假象** |
| SP-report 两点报工默认 | 🟨 | **✅ LIVE** | factory_settings F006=true/F001=false SQL 坐实 |

## B. PR 声称修复，🟡 CLAIMED 待 Codex 真实路径验证（降级 BUILT_UNTESTED-claimed，进 §7 靶）

| 原矩阵项 | 原维度/严重 | 触及 PR | Codex 验法 |
|---|---|---|---|
| SP3-§6 LaborCostConfig BOM 前端无字段 | 🟧 HIGH | #707 SP9双口径 | 开 BOM/产品表单找人工成本/kg 录入控件真在否 |
| SP5 FactoryGrossMarginConfig 无 admin CRUD | 🟧 HIGH | #706 毛利红线配置CRUD | 调5端点 + 找配置 UI 入口真可配否 |
| SP5 毛利红线 409 阻断 vs spec不卡死(CRIT语义冲突) | 🟩 CRIT | #714 销售价warn非409 | sales 下低价单→是 warn 红字非 409 硬拒否 |
| SP9 人效双口径 M1/M2/M3 quoted/actual 双null空转 | ⬛/🟨 HIGH×3 | #707 actualLaborCost rollup | 配工价+完工→quoted/actual 真有值否(矩阵最差SP) |
| SP9 @PriceSensitive 脱敏零实现(销售可读人工成本) | ⬛ HIGH | #707 @PriceSensitive | sales 角色调人效端点→金额脱敏否(X-1) |
| SP10 三价对比 DTO 零@PriceSensitive 成本泄露 | 🟨 CRIT | #693/#695 脱敏 | sales 角色调三价端点→脱敏否(X-1) |
| SP10 中报价 GET stub / PUT confirm 缺 | 🟨/⬛ HIGH | #710 去stub真读+状态机 | 中报价详情真数据否 + confirm CALCULATED→CONFIRMED |
| SP10 laborPerKg / is_trial silent drop | 🟨⚠️ HIGH×2 | #754中试库/#767研发域 | 建报价填laborPerKg+勾is_trial→SQL查落库否(DTO往返) |
| SP6 RN 收货屏缺失 | ⬛ HIGH | #709 WHPurchaseReceiveListScreen | RN 收货屏注册可达否 |
| SP6 G7 settlementType 继承 | 🟨 | #767/#678 | 付款继承结算方式否 |
| SP7-F4 入库守卫零调用点(CRIT红线虚设) | 🟧🔗 CRIT | #700 INVENTORY_MUTATION_ROLES | 无单直调入库→拦否 |
| SP7 盘盈盘损分列 | (F6) | #715 profitQty/lossQty | 盘点分列+金额@PriceSensitive否 |
| SP12-T3 旧cancel未封任意角色绕过(CRIT红线) | 🟨 CRIT | #705撤回角色分离/#719 AI RBAC | 低权角色调旧cancel→拦否(HTTP+AI双路径) |
| SP12 AI路径写tool无鉴权 | (X-3) | #719中央ToolRbacEnforcer 56工具 | 低权经AI删批次/财审→拦否 |
| SP1-T3 移动均价并发仅mock | 🟨⚠️ HIGH | #713 ensure-row-then-lock | 并发两收货同物料→均价加权正确无丢 |
| SP3 多段成本 | (新需求) | #770 multi-stage-cost | 多段链→端点逐段料+人工+制费(D2-C) |
| SP8-T02 generate-code 端点不存在404假码 | 🟨⚠️ HIGH | #754 16位码 | 调 generate-code 真返码否 |
| SP12-T7 Python打印2路由缺→502 | ⬛ HIGH | #674 systemd+字体 | 打印按钮→PDF非502否 |
| SP2 调拨实收差异/双单号silent-drop | (长尾) | #764双单号PDF+调拨指示单 | 调拨/双单号落库否 |
| SP7 报损双实体PRODUCTION_WASTE枚举不一致400 | 🟩 HIGH | #766报损双实体证伪 | 报损提交→400否(枚举对齐) |

## C. 🔴 仍 OPEN（今晚发现 / 无 PR 确认触及）

| 项 | 严重 | 状态 |
|---|---|---|
| **BUG-RCV** 采购收货批次 unit_price 不从PO行价兜底→null→材料成本丢失 | 🟡真gap | 今晚 gate 发现；撞防呆核心(仓管不填价)；待 Steve 定后端兜底(红线财务)。admin手填可绕→非硬演示阻断 |
| **BUG-MR500** /processing/material-receipt 500 | 🟡 | 次要端点(/material-batches可替代)，triage 后定 |
| SP6 供应商银行信息实测null | 🟧 HIGH | 矩阵标，无PR确认→Codex §7 靶7 真验 |
| SP11-F2 进销存导出货不对板(CRIT) | 🟧⚠️ CRIT | 无PR确认→Codex §7 靶15 真验 |
| SP11-R2 导出线程绕过脱敏泄露(CRIT) | ⬛ CRIT | 无PR确认→Codex §7 靶16 真验 |
| SP7 WHInventoryCheck 141行直调绕过(CRIT) | ⬛ CRIT | 无PR确认→Codex §7 靶9 真验 |
| SP8 分段字典无UI(P0阻断) | ⬛ HIGH | 矩阵标→Codex §7 靶11 真验入口是否已建 |
| SP4-A3 类别前缀LabelService未调用→标签仍'MA' | 🟨🔗⚠️ HIGH | #708税换算可能触及→Codex §7 靶6 真验标签 |

## 修订后成熟度修正（粗估，待 Codex 真实验证后定稿）

- **✅ LIVE 升维**: 6 项有硬证据(cashier链/凭证三行/packQty/sourceOrderIds代码/两点配置/SO价修复)
- **🟡 CLAIMED 降级处理**: ~20 项原 ⬛/🟧 有 PR 但**不当 ✅** → 标 "BUILT_UNTESTED(claimed)" 进 Codex §7 测试靶
- **🔴 仍 OPEN**: ~8 项(含今晚2新发现 BUG-RCV/MR500 + 6 CRITICAL/HIGH 待真验)
- **方法论**: 原快照 136 项的 ✅前端验 仅 6 项(4%) 是诚实的；#754-771 后**真正升到"✅前端验"必须 Codex real-path 坐实**，PR-merged 只够 "🟡claimed"。**这是本项目避免"PR合了就当完成"反复栽坑的纪律**。

> **下一步**: Codex 按 `docs/dispatch/2026-06-12-codex-test-master-plan.md` §7(19靶) + §8(六流真实数据) 跑，把 🟡CLAIMED 逐个判 ✅CLOSED/🔴OPEN，回 organizer gate。每验证一项 → 本修订层更新该项最终维度。
