# 六扇门工厂 ERP-lite — 全程架构蓝图 (Master Blueprint)

> **keystone, Opus 执笔。** 12 个子项 spec+plan 的共同脊梁: 统一数据模型 / 红线关键设计 / 依赖与执行波次 / 共享约定。所有子项 spec **必须**遵循本蓝图, 不得各写各的数据模型或红线设计。
> **来源**: `docs/meetings/2026-06-09-liushanmen/`(requirements-catalog.md / 需求与现状分析.md / 排期-roadmap.md / 决策选项表.md)。
> **生成**: 2026-06-09 Opus organizer。

## 0. 策略与已锁决策

- **目标**: 全部 12 子项 spec+plan 一次出齐 → 统一排波执行 → **周五演示, 客户提最后一波建议** → 微调。Friday 是 demo+反馈点, **非阻塞决策门**。
- **待客户周五确认项 → 先按推荐建, 不等**: ③编码=小补(数字前缀+前三位主编码, 16位分段列P1) ④财务=仅导金蝶/用友表头凭证表(不接API)。周五若客户要严格16位/要接API → 微调/升级。
- **5 决策(已 Steve 拍板, 全 B)**: ①半成品=同单双产出 ②不要建议价用毛利红线 ③编码小补 ④财务导表 ⑤超支用百分比。
- **现状**: 大量已 ship(gap 显示 103 模块 35 已建/43 部分/25 缺) → **gap-fill 不是 greenfield**。每子项 spec 必先 grep 现有代码, 复用既有(BomRecipe/SemiFinishedInventory/YieldReport/SalesOrder/CostRollupUtil/RBAC/@PriceSensitive)。

---

## 1. 子项总表 (12 SP)

| SP | 名称 | 主流 | 端 | Flyway 号段 | scope-lock 主文件 | 依赖 | 🔒 |
|---|---|---|---|---|---|---|---|
| SP1 | 生产闭环-同单双产出+半成品库回挂 | C | be+web+RN | V20260910_0x | YieldReportServiceImpl, SemiFinishedInventory(+Txn), ProductionReport, completeProduction | 无(地基) | 🔒库存事务 |
| SP2 | 二次加工(同单sourceWip+跨单独立单)+整单撤回 | C | be+web+RN | V20260910_1x | YieldReportServiceImpl, WipInventoryService, ProductionPlan, 新 ReportReversal | SP1 | 🔒事务回滚 |
| SP3 | 三价成本引擎(移动均价+标准价+超支百分比报警) | B | be+web | V20260910_2x | CostRollupUtil, SemiFinishedInventoryTxn, BomRecipe, SalesServiceImpl.getFinanceCostBreakdown | SP1 | 🔒成本口径 |
| SP4 | 一物一码补缺(厂号/产地+批次条码+税率含未税+BOM按份数/组合装) | A+B | be+web+RN | V20260910_3x | MaterialBatch, RawMaterialType, BomRecipeItem, material-types/list.vue | 无 | — |
| SP5 | 销售到开票(含未税税率+毛利红线+开票传票) | E | be+web | V20260910_4x | SalesOrder, SalesOrderItem, PriceFieldResponseAdvice, sales/*.vue | SP3(红线基准),SP4(税率) | 🔒毛利红线 |
| SP6 | 采购到付款(入库超收少收异常+退货+付款申请审批+票据核销) | D | be+web+RN | V20260910_5x | Purchase*, Supplier, MaterialBatch入库, 新 PaymentRequest/Invoice | SP4 | 🔒审批/科目 |
| SP7 | 仓库管控(盘点发起→财务批+报损拍照分两路+调拨备货+多仓) | F | be+web+RN | V20260910_6x | Warehouse(+type), Transfer, 新 Stocktake/WastageReport, MaterialBatch | SP4 | 🔒权限/审批 |
| SP8 | 16位分段编码体系 | A3 | be+web | V20260911_0x | 编码生成器, BomRecipeItem 关联键 | SP4 | — |
| SP9 | 人工双口径对比 + 人效模块 | B4+I | be+web | V20260911_1x | LaborCostConfig, ProductionReport人效, BomRecipe人工 | SP1,SP3 | — |
| SP10 | 研发/产品经理报价(预报价/中报价/试制库) | G | be+web | V20260911_2x | 新 ProductQuote, 研发试制 Warehouse, ProductType | SP3,SP4 | — |
| SP11 | 财务凭证表导出(金蝶/用友表头) + 进销存报表 | H | be+web | V20260911_3x | 新 VoucherExport, InventoryLedger 报表 | SP6,SP7 | 🔒会计口径 |
| SP12 | 跨流: 通用审批流引擎 + 权限矩阵补全 + 单据打印 | X | be+web | V20260911_4x | 新 ApprovalFlow 引擎, RBAC, 打印模板 | (P0 用轻量状态机解耦) | 🔒权限 |

> **Flyway 号段预分配** 防跨子项撞号(教训: feedback_flyway_cross_session_dup_collision)。每子项只在自己号段内编号; **merge 后部署前**仍须 `git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d` 查重。

---

## 2. 跨流数据模型增量

> 加字段必做全 4 处(教训 feedback_dto_roundtrip_silent_drop): Entity字段 + create set + update null-guard set + convertToDTO map。继承 BaseEntity 必有 created_at/updated_at/deleted_at。

### 2.1 半成品(SP1/2/3 核心)
- **`SemiFinishedInventory`**(已存在, 重量库无批次): 复用。确保 `unit_cost` 为移动均价。一表多 code(焯水猪蹄/熟制猪蹄各一行, 不建多库)。
- **`SemiFinishedInventoryTransaction`**(🆕 流水账, SP1 建): `id, factory_id, semi_finished_id, txn_type(IN/OUT/REVERSE/ADJUST), quantity, unit_cost(IN时), source_type(PRODUCTION_OUTPUT/SECONDARY_CONSUME/REVERSAL/STOCKTAKE), source_ref(批次/单号), balance_after, balance_cost_after, created_at`。**支撑: 移动均价重算 / 撤回回退 / 盘点 / 二次加工 lineage。** 没有流水账无法精确回退移动均价。
- **`ProductionReport`** 产出: 一道完工报工可产出 **FINISHED(成品→FG) + SEMI(半成品→SemiFinishedInventory)** 两类(同单双产出)。已有 report_kind/三阶段, 扩产出类型枚举。

### 2.2 二次加工 + 撤回(SP2)
- 领半成品: 复用 `sourceWipNo` + `WipInventoryService.validateSourceWip`。**跨单独立单**: 新生产单 source=`SEMI_FINISHED`, 领 `SemiFinishedInventory`(走 OUT txn)。
- **`ReportReversalLog`**(🆕): `id, factory_id, batch_id/plan_id, reversal_scope(WHOLE_ORDER), submitted_by, approved_by, reason, status(PENDING/APPROVED/DONE), reverted_txn_ids, created_at`。整单撤回审计 + 幂等键。

### 2.3 三价成本(SP3)
- **标准成本价**: `BomRecipe.total_cost`(已有, 料)+ `LaborCostConfig`/`OverheadCostConfig`(已有, 人工/均摊)。
- **销售价**: `SalesOrderItem` 下单填(已有)。
- **实际成本价**: 移动均价引擎产出, 回填 `SalesOrderItem.costUnitPrice`(已有)。
- **超支报警**: 新 `cost_variance_threshold_pct`(产品级/全局配置, 默认 10%)。`SalesServiceImpl.getFinanceCostBreakdown`(已有)加 variance% + 报警 flag + 通知钩子。

### 2.4 一物一码(SP4)
- **`MaterialBatch`** 加 `factory_number`(厂号) + `origin_place`(产地) 两列(批次属性, 厂号≠供应商, 不做匹配校验)。领料/报工按厂号选批次(RN+web)。
- **批次条码标签**: 复用 `Label` 实体扩到原料批次场景 + 扫码查询端点(返编码/厂号/重量)。
- **税率**: `ProductType`/`SalesOrderItem` 加 `tax_rate`(枚举 9%/13%)+ 含税↔未税换算(成本核算用未税)。
- **BOM 按份数**: `BomRecipeItem` 辅料/调料项标记 `per_portion`(按成品份数, 不管组成); 组合装 AB包 = BOM 引用两个半成品 code + 包材。

### 2.5 采购/仓库/财务(SP6/7/11)
- **`Warehouse`** 加 `warehouse_type`(RAW原料仓/PRODUCTION生产库/EXTERNAL外仓/SALTED盐化)。生产库只挂半成品(零原料库存, 用完退仓库)。盐化单独扣(走盐化供单)。
- **采购付款属性**(SP6): 采购单加多选结算状态(预付/赊销先入库/未到票/月结/账期/现结)→ 映射会计科目(SP11 凭证用)。
- **`PaymentRequest`/`PurchaseReturn`/`Stocktake`/`WastageReport`/`Invoice`/`VoucherExport`**: 各子项新建(见各 spec)。

---

## 3. 🔒 红线关键设计 (Opus 执笔, 不可改)

### 3.1 整单撤回 — 事务与回退 (SP2)
**决策**: 整单撤回 + 审批环节 + 无证据(未产生数据)可直接撤 + 按角色判权 + **仅当下游未提走 WIP/成品时可撤**。

**前置守卫(fail-closed, 违反→409 + actionHint, fool-proof Rule 5)**:
1. 该单产出的半成品 **已被下游领用**(SemiFinishedInventoryTransaction 有引用本单的 OUT) → 409 "已被下游单 {X} 领用, 请先撤下游"。
2. 成品已出库/发货(Shipment 引用) → 409。
3. 审批: 有报工数据 → 提交撤回申请 → 按角色审批(撤回是写操作, 过 W0 WriteGuard 确认门); 无数据 → 直接撤回跳审批。

**回退动作(单一事务, null 安全不抛 — 教训 feedback_failsoft_catch_cannot_save_doomed_tx)**:
- 反向已领用的原料/上游半成品: `MaterialBatch.used_quantity` 回补 + `SemiFinishedInventoryTransaction` REVERSE 行。
- 作废本单产出的 SemiFinishedInventory IN 行 + FG 行(软删/状态置 REVERSED)。
- **移动均价回退**: 该半成品 code 按 `(余额值 − 本批IN值)/(余额量 − 本批IN量)` 重算 unit_cost; 用流水账精确还原(故 2.1 必须有 Txn ledger)。若期间已有其它 IN/OUT 交错 → 重放该 code 流水账(排除被撤 IN)重算余额+单价。
- 幂等(Rule 4): ReportReversalLog 已 DONE → 第二次返回 already-reverted, 不重复回补。

**⛔ 事务铁律**: 回退逻辑全在一个 @Transactional 内且 null 安全(不抛), 或对真正独立的副作用用 REQUIRES_NEW。**禁止** fail-soft try/catch 吞内层异常("救不回 doomed tx", 已复发 3 次)。

### 3.2 移动均价成本引擎 (SP3, 复用扩展现有 WIP unitCost)
- **IN(生产产出入库)**: `newCost = (oldQty×oldCost + inQty×inCost)/(oldQty+inQty)`; `inCost = 本道总成本/产出qty`; 本道总成本 = Σ投入(原料按批次进价 / 上游半成品按其当前 unit_cost) + 人工(报工 人数×工时×工价) + 调料(按份数死价)。
- **OUT(二次加工/下道领用)**: 按当前 unit_cost 出账, 余额减, **单价不变**(移动平均特性)。
- **并发**: 同 code 并发 IN/OUT → 悲观行锁 `SELECT ... FOR UPDATE`(短事务)。
- **精度**: 数量 scale-6, 成本 scale-4, ROUND_HALF_UP(对齐 CostRollupUtil)。`_decimal` 序列化遵循 Java BigDecimal 约定。
- **诚实空**: 任一投入无单价 → 该道 inCost 诚实 null(不显 0), 不污染均价(对齐现有 hasNullPrice→null)。

### 3.3 毛利红线预警 (SP5)
- 下单填价 → 后端算 `minPrice = standardCostPrice × (1 + targetGrossMargin)`(基准用**标准成本价**, 稳定; 实际成本事后对比)。`targetGrossMargin` 产品级/全局可配。
- 低于 minPrice → **红色 sticky 预警(duration:0+showClose), 不卡死提交**(fool-proof 4位一体)。
- **🔒 脱敏不泄露**: 成本价对销售角色 @PriceSensitive 脱敏。红线判定**在后端算**, 前端只收到 `belowRedline:bool + 预警文案`, **不下发成本数值给销售**。
- 提成联动: 毛利率→提成(复用现有 CommissionRule.tierConfig)。

### 3.4 权限矩阵 + 仓库零自主权 (SP7, cross-cutting)
- **仓库无自主改库存权**: 所有 出入库/盘亏盈/报损 必须有单据来源(采购入库单/退货单/调拨单/盘点任务/报损单)+ 经审批后才动数据。仓管=执行操作员, 不发起。
- 角色: 仓管/厂长(车间主任)/小组长/operator(纯报工)/财务/出纳/采购员/销售员/品控。复用现有 RBAC + @RequireRole + 请求属性 role(非空 SecurityContext, 教训 C1孪生坑)。缺的角色(出纳/品控)增量加。
- 所有审批/库存变动 audit 留痕(who/when/node/before-after)。

### 3.5 审批流 — P0 解耦策略 (SP12 vs P0)
- **通用审批流引擎 = SP12(P1)**。**P0 的 D付款/F盘点报损/SP2撤回 不依赖大引擎** → 各用**轻量 per-单据审批状态机**(PENDING→APPROVED→DONE + 审批人 + 双端可批)。避免 P0 卡在大引擎上。SP12 落地后再统一迁移到引擎(适配器)。

### 3.6 Flyway 跨子项排号 (全子项)
- 号段预分配(见 §1 表)。每子项只用自己号段。
- **merge 后部署前**必查重(见 §1 注)。撞号→重编号未 apply 的(已 apply 不动避 orphan)。

---

## 4. 依赖图 + scope-lock 执行波次

> "一口气全做"≠字面并行。多子项改**同一批文件**必撞(教训 5/30 ¥0)。按 scope-lock 排波: 同波内文件不重叠可并行, 跨波串行。每子项独立 worktree off origin/main, PR 回 main, 🔒 Opus 终审从 main 部署。

```
波1 (地基, 无依赖, 可并行):  SP1(生产闭环) ‖ SP4(一物一码)
波2 (依赖波1):              SP2(二次加工+撤回, 依SP1) ‖ SP3(三价成本, 依SP1) ‖ SP6(采购付款, 依SP4) ‖ SP7(仓库管控, 依SP4)
   ⚠️ SP1/SP2 同改 YieldReportServiceImpl → SP2 必接 SP1 之后(串行, 不同波)
   ⚠️ SP3/SP1 同改 SemiFinishedInventoryTxn → SP3 接 SP1 之后
波3 (依赖波2):              SP5(销售开票, 依SP3红线+SP4税率) ‖ SP9(人工人效, 依SP1+SP3) ‖ SP8(16位编码, 依SP4)
波4 (P1/P2):               SP10(研发报价, 依SP3+SP4) ‖ SP12(审批流引擎+权限) → SP11(财务凭证, 依SP6+SP7+SP12)
```

**scope-lock 冲突点(必串行)**: `YieldReportServiceImpl`(SP1→SP2→SP9) · `SemiFinishedInventory(+Txn)`(SP1→SP2→SP3) · `MaterialBatch`(SP4→SP6→SP7) · `BomRecipeItem`(SP4→SP8) · `SalesOrder*`(SP5 独占)。同一文件的子项**不并发**, 按依赖串行或 keystone-先写再交棒。

---

## 5. 共享约定

- **端边界**: RN-app=操作员手机(领料/报工/撤回提交/调拨接收/盘点录入, 低输入 fool-proof, 走 ux-flow gate); web-admin=管理后台(配置/审批/财审/报表/CRUD); backend=Java(实体/服务/事务/权限/Flyway)。
- **DTO 往返**: 加字段全 4 处(Entity+create+update+convertToDTO), 镜像 gramsPerUnit 既有模式。
- **fool-proof 4 位一体**: 任何写操作 dialog 必 Rule1(max预显)+Rule2(品名/单号context)+Rule3(原因dropdown)+Rule4(幂等防重)+Rule5(dead-end跳转); error toast sticky(duration:0)+后端真实message+next action。
- **Java↔逻辑**: Decimal `is not None` 三元; HALF_UP; Map.of 序列化按 golden; 见 `.claude/rules/python-java-port.md`(若涉 Python parity)。
- **测试**: 每子项 TDD(先红后绿); backend mvnw 守卫测试; web vue-tsc+build; RN tsc; UI E2E 必 headed(zh-CN, 见 playwright-headed-mode rule)。
- **🔒 红线收尾**: SP1/2/3/5/6/7/11/12 红线部分执行者只到 PR + 自测; Opus 终审 diff(`git diff origin/main...HEAD --stat` 确认 scope 干净) → merge main → 从 main 部署 → 核对运行 jar 含修复。
- **commit 锁 scope**: `git commit -- F1 F2` 或 safe-commit.sh。worktree 各自 `npm install --prefer-offline`(⛔ 禁 mklink /J node_modules)。

---

## 6. 周五演示会(全做完后)
- 演示全链路: 一物一码建料 → SKU/BOM → 销售订单(毛利红线) → 财审 → 采购/调拨 → 开工 → 同单双产出报工(成品+半成品) → 二次加工领半成品 → 撤回 → 成品入库 → 出库开票 → 三价成本对比。
- 让客户提最后一波建议 → 微调。**周五待确认**: 编码是否严格16位 / 财务是否接API(否则维持 小补+导表)。
