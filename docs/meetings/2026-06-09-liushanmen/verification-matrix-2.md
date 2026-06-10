# 六扇门需求追溯矩阵 · 分片2: C/F 流

> **v2 重做: 取证基于 origin/main 而非工作树**
> 生成时间: 2026-06-10
> 取证方法: `git fetch origin main` → `git ls-tree -r origin/main` + `git grep origin/main` — 工作树已知 stale (停 V20261001_02)，所有实现判断以 origin/main 为准
> E2E 证据: `scripts/e2e/liushanmen-demo/run-20260610_124749.json` (13 created + 1 error)
> 台账: `docs/dispatch/ACTIVE.md` (FIXB 批修复状态)
> 已知基线: V20261010_01..28 + V20261011_01..22 全在 origin/main；12 SP (PR#627-653) 全 merged + 部署 prod

---

## 图例

| 实现标记 | 含义 |
|---------|------|
| ✅已建 | 后端 Service/Entity/Migration + 前端屏完整对接 |
| 🟡部分 | 后端已建但前端缺失，或前端有但后端不完整 |
| 🔴缺 | 代码中未发现对应实现 |
| ⚪约束项 | 业务规则/配置项，非代码实现 |

| 验证标记 | 含义 |
|---------|------|
| V1强 | E2E run JSON 中有实体 ID / ACTIVE.md 真机证据 |
| V2弱 | 代码链路通，未跑端到端 |
| V0 | 未验证 |
| B阻塞 | 有阻塞因素，后跟原因 |
| N/A | 不适用 |

---

## C 流 · 生产闭环 (领料→成品/半成品, 二次加工, 撤回)

共 78 项

| 编号 | 需求摘要 | SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|------|---------|-----|--------|------|------|---------|------------|
| C-001 | 销售计划→财务审批→生产计划闭环 | SP2 | P0 | ✅已建 | V1强 | PRODUCTION_PLAN id:01d8470c E2E造 | 追查 plan.status APPROVED |
| C-002 | 生产计划创建时人员分配工序 | 核心 | P0 | ✅已建 | V2弱 | ProductionPlanServiceImpl.assignWorkers; V20261010_13工序任务表 | 真机创建计划→工序分配页 |
| C-003 | 开工生成批次，工序自动推送到APP | 核心 | P0 | ✅已建 | V1强 | BATCH_X(1973 DEMO-X-66881)+BATCH_Y(1974 DEMO-Y-66882) E2E造 | APP登录查任务列表 |
| C-004 | 批次报工: 产量/人数/时间三核心字段 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md BUG-2 真机验收; YieldReportServiceImpl三阶段 | 真机报工一道工序 |
| C-005 | 报工三阶段状态机(投入→时段→产出) | SP2 | P0 | ✅已建 | V1强 | ACTIVE.md 三阶段 SHIPPED; V20261010_14 report_kind字段 | 查 production_reports.report_kind |
| C-006 | 投入报工拍照上传(OSS证据) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 真机报工全链; evidenceImages OSS | 真机拍照报工 |
| C-007 | 时段报工(多段工时×人数) | SP2 | P0 | ✅已建 | V2弱 | YieldReportServiceImpl SEGMENT分支; laborSegments字段 | 同一批次提交多段SEGMENT报工 |
| C-008 | 产出报工(出成率自动计算) | 核心 | P0 | ✅已建 | V1强 | YIELD_REPORT_Y id:480 E2E; YieldReportServiceImpl OUTPUT分支 | 查 production_reports.actual_yield_rate |
| C-009 | 副产物/损耗/留样字段 | 核心 | P1 | ✅已建 | V1强 | ACTIVE.md 真机; byproducts/sampleRetainQuantity字段 | 查 production_reports.byproducts |
| C-010 | 工序成本: 人工成本(工时×人数×时薪) | SP9 | P0 | ✅已建 | V2弱 | YieldReportServiceImpl line:165 standardHourlyRate; V20261010_18 labor_cost字段 | 查 production_reports.labor_cost |
| C-011 | 工序成本: 材料领料折价 | SP9 | P0 | ✅已建 | V2弱 | WIP unitCost滚动成本; MaterialConsumptionServiceImpl | 领料后查批次成本 |
| C-012 | 批次报工联动任务状态(OUTPUT完成→task COMPLETED) | BUG-2 | P0 | ✅已建 | V1强 | ACTIVE.md BUG-2 SHIPPED prod; jar 5标记+API live task336 COMPLETED+真机列表推进 | 真机报 OUTPUT→任务列表消失 |
| C-013 | 批次可多次报工(按工序逐道报) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 真机 6道工序全链 | 批次1973 DEMO-X-66881还有4道可验 |
| C-014 | 报工幂等(同 taskId 同 phase 不重复) | 核心 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl幂等键 generateBatchNo taskId维度 | 连续提交同 phase 验证防重复 |
| C-015 | 报工后 WIP 出成率累加(跨天滚动) | SP1/G7 | P1 | ✅已建 | V2弱 | YieldStepServiceImpl累计; SemiFinishedInventoryTransaction | 查 semi_finished_inventory_transactions |
| C-016 | 产出报工建立 FG 成品库存 | 核心 | P1 | ✅已建 | V1强 | YIELD_REPORT_Y id:480 E2E 产出 | 查 finished_goods_batches |
| C-017 | 批次完工建 FG + 入库 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg AVAILABLE | 查 finished_goods_batches.status=AVAILABLE |
| C-018 | 成本核算: 实际入库值口径(非理论) | ⚪约束 | P0 | ⚪约束项 | N/A | 系统按实收量结算(采购入库实收触发成本) | 采购入库实收≠订单量时验证成本口径 |
| C-019 | 成本核算: 未税价口径 | ⚪约束 | P0 | ⚪约束项 | N/A | 含税/未税字段拆分; 成本用税前价 | 查采购单 unitPriceExcl字段 |
| C-020 | 生产批次成本价附在批次后 | 核心 | P1 | ✅已建 | V2弱 | ProductionBatch.unitCost字段; recalculateBatchCost端点 | 查 processing_batches.unit_cost |
| C-021 | 每批次领料有单据(MaterialRequisition) | 核心 | P0 | ✅已建 | V2弱 | MaterialRequisitionController; V20261010_05 material_requisitions | 创建批次后查领料单 |
| C-022 | 领料对应产品BOM自动算预领量 | 核心 | P0 | ✅已建 | V2弱 | BomExpansionService; ProductionPlanServiceImpl.calculateMaterialNeeds | 下计划后查预领量 |
| C-023 | 生产工单可打印(公单PDF) | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController line:264 /consolidated-material-requisition/{planId}; PrintControllerSp12T8Test | Web 计划详情→打印公单 |
| C-024 | 汇总领料单(整单一次汇总, 含多物料) | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController.buildConsolidatedRequisitionPayload line:675; 汇总领料单 PDF SP12 T8注释 | 调用/print/consolidated-material-requisition/{planId} |
| C-025 | 配料单(锅为单位派生, deferred) | defer | P2 | 🔴缺 | N/A | 客户明确本期deferred; 设计已预留派生关系描述 | 本期N/A |
| C-026 | 领料量自动按BOM配方反推(免人工算) | 核心 | P0 | ✅已建 | V2弱 | BomExpansionService.expand; standardQuantity/yieldRate计算 | 创建计划→查领料量=BOM推算值 |
| C-027 | 双单同步: 纸质单查档+数据单结算 | ⚪约束 | P1 | ⚪约束项 | N/A | 系统提供打印单据; 结算以系统数据为准 | 业务操作验证 |
| C-028 | 多销售单合并为单一供单 | 核心 | P1 | ✅已建 | V2弱 | ProductionPlanServiceImpl 合并逻辑; plan.sourceOrderIds | 多SO→合并计划→查 source_order_ids |
| C-029 | 销售单号/生产单号双向检索 | 核心 | P1 | ✅已建 | V2弱 | ProcessingBatchRepository.findBySalesOrderId; PlanRepository.findByOrderId | 按SO号查批次 |
| C-030 | P20/P80出成率自动计算(分位数) | SP2 | P1 | ✅已建 | V2弱 | YieldStandardCalculationServiceImpl: percentile(yieldRates, PERCENTILE_20/80); workProcessRepository.save(process)写回 | 跑3+批次后查 work_processes.standard_yield_min/max |
| C-031 | 出成率自动更新调度(下批领料用上批基准) | SP2 | P1 | ✅已建 | V2弱 | YieldStandardCalculationScheduler; MIN_SAMPLE_COUNT=3 | 等调度跑后查更新时间戳 |
| C-032 | 手动覆盖出成率标准时自动计算跳过 | SP2 | P2 | ✅已建 | V2弱 | YieldStandardCalculationServiceImpl.fillMissingStandards: 只填null字段; incrementSkippedManual() | 手动设置 standard_yield_min→跑调度→验字段不被覆盖 |
| C-033 | RN 二次加工批次能显示 batchSourceType | SP2 | P1 | 🟡部分 | V0 | 后端 createSecondaryPlan V20261011_07 planSourceType ✅; FIXB#2 DTO透传字段待做 | FIXB#2 完成后验: getBatchById.batchSourceType=SEMI_FINISHED |
| C-034 | 二次加工 WIP picker: 领用半成品库存 | SP2 | P1 | ✅已建 | V2弱 | SemiFinishedInventoryController; RN MaterialBatchPicker拦SEMI_FINISHED路径 | RN 二次加工→选半成品WIP |
| C-035 | 半成品价格: 前道出成价作下道原料价 | SP1 | P1 | ✅已建 | V2弱 | SemiFinishedInventoryTransaction.unitCost; WIP unitCost滚动 | 查 semi_finished_inventory_transactions.unit_cost |
| C-036 | 半成品按 code 区分价格(单库多SKU) | SP1 | P1 | ✅已建 | V2弱 | SemiFinishedInventoryTransaction.semiCode; V20261010_03 work_process_semi_code | 查不同 semi_code 的 unit_cost 不同 |
| C-037 | 创建二次加工计划(planSourceType=SECONDARY) | SP2 | P0 | ✅已建 | V2弱 | ProductionPlanServiceImpl.createSecondaryPlan line:1750; V20261011_07 production_plan_secondary_source_type | POST /processing/plans/secondary |
| C-038 | 二次加工 secondarySourceWipId 关联 | SP2 | P0 | ✅已建 | V2弱 | ProductionPlanServiceImpl 含 secondarySourceWipId; ProductionPlanServiceSecondaryTest.java | 查 production_plans.secondary_source_wip_id |
| C-039 | 半成品+原料混合投入(同批次) | SP1 | P1 | ✅已建 | V2弱 | SemiFinishedInventoryController.consumeSourceWip + 普通领料并存 | 二次加工批次同时有 WIP 领用+原料领用 |
| C-040 | 整单撤回(非单工序撤回) | SP2 | P0 | ✅已建 | V2弱 | ReportReversalController; ReportReversal.reversalType=FULL_BATCH | 查 report_reversal_logs 撤回类型 |
| C-041 | 撤回创建 ReportReversalLog 记录 | SP2 | P0 | ✅已建 | V1强 | REVERSAL_LOG id:3 E2E造; entity/ReportReversalLog.java; V20261011_08 | 查 report_reversal_logs id=3 |
| C-042 | 撤回联动任务状态复位 | SP2 | P0 | ✅已建 | V1强 | ACTIVE.md 撤回复位 V1; ReportReversalServiceTest 17绿; executeReversal task reset | 执行撤回后查 work_process_tasks.status=PENDING |
| C-043 | 无证据可直接撤回(skip审批) | SP2 | P1 | ✅已建 | V2弱 | ReportReversalServiceImpl直接执行路径(无证据=无report) | 无 production_reports 的批次→直接撤回 |
| C-044 | 撤回权限按角色判定 | SP12 | P1 | ✅已建 | V2弱 | RBAC @RequireRole 在 ReportReversalController; V20261011_01-06 角色表 | 低权限角色尝试撤回→403 |
| C-045 | 撤回审批流(approveReversal / rejectReversal) | SP2 | P0 | ✅已建 | V1强 | ReportReversalController line:94 approveReversal; line:110 rejectReversal; ACTIVE.md V1验证 | 创建撤回→审批→查状态=APPROVED |
| C-046 | 撤回执行(executeReversal + 成本回滚) | SP2 | P0 | ✅已建 | V1强 | ReportReversalServiceImpl.executeReversal; ACTIVE.md V1 | 执行撤回后查批次 WIP 数量恢复 |
| C-047 | 人员不绑SKU, 计划层临时分配 | 核心 | P0 | ✅已建 | V2弱 | WorkProcessTask.assignedTo; ProductionPlanServiceImpl临时分配 | 修改计划层工序负责人 |
| C-048 | 开工后工序推送到个人APP任务列表 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 工序-小组长 SHIPPED; WorkProcessTask push | 开工后APP登录查到对应任务 |
| C-049 | 计划日期可延后不影响(以实际开工为准) | ⚪约束 | P1 | ⚪约束项 | N/A | 系统不强制按计划日执行; startTime以实际为准 | 修改计划日期→验实际开工时间独立 |
| C-050 | 汇总领料单含多物料、多单合并 | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController /consolidated-material-requisition/{planId}; buildConsolidatedRequisitionPayload 多物料汇总 | Web 两个SO合并计划→打印查多行物料 |
| C-051 | 汇总领料单含双单据号(销售单号+生产单号) | 核心 | P1 | 🟡部分 | V0 | 打印端点存在; 双单号交叉引用是否渲染待验证 | 打印公单PDF→目视确认含 SO号+生产单号 |
| C-052 | 成本核算按未税价+加工费(BOM成本范围) | ⚪约束 | P0 | ⚪约束项 | N/A | 设计决策: 成本=人工+包材+辅料, 不含能源水电 | 查成本公式不含水电 |
| C-053 | 成本分摊到每盒(非批次) | SP9 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl OUTPUT unitCost per box计算; P4分摊逻辑 | 查 finished_goods_batches.cost_per_unit |
| C-054 | 不追溯人工来自哪个环节(只摊到盒) | ⚪约束 | P1 | ⚪约束项 | N/A | 设计决策: 人工摊到每盒不拆环节 | 查成本结构仅保留盒单价 |
| C-055 | 工时折钱算法(工段×工时×单价) | SP9 | P1 | ✅已建 | V2弱 | WorkProcess.standardHourlyRate; laborCost=workers×minutes×rate | 查 work_processes.standard_hourly_rate |
| C-056 | 成本核算含加工费 | ⚪约束 | P0 | ⚪约束项 | N/A | 系统包含 standardHourlyRate×工时 = 人工成本 | 验证成本包含人工分量 |
| C-057 | 出成率达成率模板(报工值对比测算值) | SP9 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl OUTPUT 出成率 vs WorkProcess.standardYieldMin/Max | 查出成率字段 vs standard_yield |
| C-058 | 达成率异常阈值(~90%/~100%/75%/150%告警) | SP9 | P2 | 🟡部分 | V0 | CostVarianceServiceImpl存在; 告警阈值是否配置75%/150%待查 | 查 product_cost_variance_configs 阈值配置 |
| C-059 | 报工UI防呆: P4/成品自动选择不手输 | fool-proof | P1 | ✅已建 | V1强 | ACTIVE.md 报工redesign; RN OUTPUT 报工自动推断产品类型 | 真机报工→无需手选P4/成品 |
| C-060 | 工序库(搭积木式工作流模板) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 掌中宝 WP-F006-ZZB-03; WorkProcess entity; V20261010_03 | 查 work_processes 工序库 |
| C-061 | 产品可跳过某工序(非固定工序链) | 核心 | P1 | ✅已建 | V2弱 | SKU工序模板可选配; 非必填工序 | 配置只3道工序的产品→批次不强制5道 |
| C-062 | 工序属性: 出成率/产出单位/标准工时/人效 | 核心 | P0 | ✅已建 | V2弱 | WorkProcess.standardYieldMin/Max/standardHourlyRate; V20261010_08 | 查 work_processes 字段齐全 |
| C-063 | 工序负责人配置(小组长) | SP2 | P0 | ✅已建 | V1强 | ACTIVE.md 工序-小组长 Phase1 SHIPPED; WorkProcessTask.assignedTo | APP f006_moyun登录查任务 |
| C-064 | 生产批次按销售订单关联(以销定产) | 核心 | P0 | ✅已建 | V1强 | PRODUCTION_PLAN+BATCH_X E2E链路从SO驱动 | 查 processing_batches.sales_order_id |
| C-065 | 采购→入库→领用→成品入库全链打通 | 核心 | P0 | ✅已建 | V1强 | E2E run 13 entities 全链; ACTIVE.md 6.1 prod闭环 | 整条链路端到端验证 |
| C-066 | 批次状态机(IN_PROGRESS→COMPLETED→FG建立) | 核心 | P0 | ✅已建 | V1强 | E2E BATCH_X 1973+BATCH_Y 1974+YIELD_REPORT_Y; ACTIVE.md F006 E2E | 查 processing_batches.status流转 |
| C-067 | 工厂逻辑简化: 领料→半成品→成品三段 | ⚪约束 | P0 | ⚪约束项 | N/A | 系统架构设计已简化; 三段流程已验 | 操作流程体验验证 |
| C-068 | 同单双产出(一成品+一半成品) | SP1 | P0 | ✅已建 | V1强 | ACTIVE.md 双产出 SF-ZZB-YZ 真机 V1; SemiFinishedInventoryTransaction | 掌中宝报工产出查到 semiCode 记录 |
| C-069 | P4半成品/成品系统自动选择不手输 | fool-proof | P1 | ✅已建 | V1强 | ACTIVE.md RN 报工屏 redesign 防呆; 三阶段自动推断 | 真机验报工屏无手选P4/成品 |
| C-070 | 半成品入库挂账核算价格(SemiFinishedInventory) | SP1 | P0 | ✅已建 | V1强 | ACTIVE.md G6/G7/G8 WIP出成率 SHIPPED; SemiFinishedInventoryController | 查 semi_finished_inventory.available_quantity |
| C-071 | 生产报损单(WastageReport FACTORY track) | SP7 | P1 | ✅已建 | V1强 | WASTAGE_REPORT id:d9d59a86 E2E造; entity/inventory/WastageReport.java; WastageReportServiceImpl | 查 wastage_reports id=d9d59a86 |
| C-072 | 报损审批流(PENDING_APPROVAL→APPROVED) | SP7 | P1 | ✅已建 | V2弱 | WastageReportServiceImpl PENDING_APPROVAL→APPROVED状态机; WastageReportController.approve | POST /{id}/approve 验状态变化 |
| C-073 | 报损后可重新领料(走调拨) | 核心 | P1 | ✅已建 | V2弱 | 调拨与报损不强关联; TransferController 独立 | 报损后创建新领料单不报错 |
| C-074 | 补录时效约束: T/T-1可补, T-2极限, T-3锁死 | 核心 | P1 | 🔴缺 | N/A | git grep reportWindow/backfillWindow/yieldWindow 无结果; 无时效约束字段 | 实现后: 尝试 T-3 补录→应400拒绝 |
| C-075 | T-3时效锁死硬规则(防审计漏洞) | 核心 | P1 | 🔴缺 | N/A | 同C-074; 无 reportDeadline/timeLimit字段 | 同C-074 |
| C-076 | 出成率随报工迭代自学习(下批用上批基准) | SP2 | P1 | ✅已建 | V2弱 | YieldStandardCalculationServiceImpl; ProductionReportRepository.findYieldStandardSamples | 批次完成后查 standard_yield_min/max 更新 |
| C-077 | 每天产量对应实际出成率统计 | SP9 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl OUTPUT 出成率字段; 每批记录 | 查 production_reports.actual_yield_rate 按日聚合 |
| C-078 | 多销售单汇总成一张生产单(同品合并) | 核心 | P1 | ✅已建 | V2弱 | ProductionPlanServiceImpl 合并; plan.sourceOrderIds[] | 两个SO→合并建计划→查 source_order_ids包含两个 |

---

### C 流统计

| 状态 | 数量 | 占比 |
|------|------|------|
| ✅已建 | 58 | 74% |
| 🟡部分 | 3 | 4% |
| 🔴缺 | 3 | 4% |
| ⚪约束项 | 14 | 18% |
| **合计** | **78** | **100%** |

| 验证级别 | 数量 |
|---------|------|
| V1强 (E2E/真机) | 24 |
| V2弱 (代码链路) | 34 |
| V0 未验证 | 3 |
| N/A | 17 |

**Top C 流风险**:

1. **C-074/C-075** (T-3时效锁死): 客户明确 constraint "大前天不允许补"，是审计防呆硬规则，未实现。工作量小但阻塞合规验收。
2. **C-033** (RN batchSourceType DTO): FIXB#2 已列修复，RN 二次加工流完整性依赖此字段。
3. **C-058** (达成率阈值告警): CostVarianceServiceImpl 存在但具体 75%/150% 阈值是否配置未验证。

---

## F 流 · 仓库管控 (出入库/盘点/报损/调拨/多仓)

共 70 项

| 编号 | 需求摘要 | SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|------|---------|-----|--------|------|------|---------|------------|
| F-001 | 入库时编码全部定死(原料/辅料/包材) | ⚪约束 | P0 | ⚪约束项 | N/A | 入库时选物料批次；编码在 RawMaterialType 已定义 | 入库操作无法新建编码 |
| F-002 | 一物一码防仓库出错料(最紧迫) | SP8 | P0 | ✅已建 | V2弱 | MaterialCodeSegmentController SP8; RawMaterialTypeController line:326 SP8前缀搜索 | 扫码查询自动弹出对应物料 |
| F-003 | 物料编码16位分段(前10位固定+后缀流水) | SP8 | P0 | ✅已建 | V2弱 | MaterialCodeSegmentController SP8 "物料16位分段编码字典"; V20261011_16 | 查 material_code_segments 结构 |
| F-004 | 编码级联生成(类型→部位逐级下拉) | SP8 | P1 | ✅已建 | V2弱 | RawMaterialTypeController line:330 SP8级联选择 | Web 新建物料→级联下拉选编码 |
| F-005 | BOM只关联前三位主编码 | SP8 | P1 | ✅已建 | V2弱 | CreateBomRecipeRequest line:128-130 SP8物料前三位主编码 | 查 bom_recipe_items.material_main_code 长度 |
| F-006 | 批次打标签(LB-F006-YYYYMMDD-XXXX) | SP8 | P0 | ✅已建 | V1强 | LABEL id:08cae0e7 code:LB-F006-20260610124809-3906 E2E造 | 扫标签 LB-F006-20260610124809-3906 |
| F-007 | 原料厂号字段 | 核心 | P1 | ✅已建 | V2弱 | MaterialBatch.manufacturer字段 | 查 material_batches.manufacturer |
| F-008 | 原料产地字段 | 核心 | P1 | ✅已建 | V2弱 | MaterialBatch.originRegion字段 | 查 material_batches.origin_region |
| F-009 | 厂号≠供应商(独立字段) | ⚪约束 | P1 | ⚪约束项 | N/A | manufacturer 字段独立于 supplier_id | 查字段区分 |
| F-010 | 原料名称只能下拉选(与BOM一致) | fool-proof | P0 | ✅已建 | V2弱 | RawMaterialTypeController 下拉搜索端点; 入库选物料类型而非自由输入 | 入库界面无法手输物料名 |
| F-011 | 批次厂号产地不做匹配校验 | ⚪约束 | P1 | ⚪约束项 | N/A | 字段存在但无校验逻辑 | 不同厂号相同物料可并存 |
| F-012 | 采购超收异常(OVER_RECEIVE)→入库异常单 | SP6 | P0 | ✅已建 | V1强 | PURCHASE_EXCEPTION id:9a5c21a2 ACCEPT_OVER decision E2E造 | 查 purchase_exceptions id=9a5c21a2 |
| F-013 | 采购少收异常(UNDER_RECEIVE)→入库异常单 | SP6 | P0 | ✅已建 | V2弱 | PurchaseExceptionServiceImpl; 同F-012逻辑路径 | 入库量<订单量→查 purchase_exceptions |
| F-014 | 异常单退回对应采购账号(责任绑定) | SP6 | P1 | ✅已建 | V2弱 | PurchaseException.purchaseOrderId 关联; 采购人员可查 | 采购账号登录查到自己名下异常单 |
| F-015 | 多收货物: 采购决定退还是入(ACCEPT_OVER/REJECT_OVER) | SP6 | P0 | ✅已建 | V1强 | PURCHASE_EXCEPTION id:9a5c21a2 decision=ACCEPT_OVER E2E | 查 purchase_exceptions.decision |
| F-016 | 采购退货单(整批, 在采购订单里建) | SP6 | P1 | ✅已建 | V2弱 | PurchaseReturnOrderController; ReturnOrderServiceImpl | 建退货单→查 purchase_return_orders |
| F-017 | 退货财务审批(跟钱有关) | SP6 | P1 | ✅已建 | V2弱 | PurchaseReturnOrder.status=PENDING_APPROVAL; WorkflowEngineService | 提退货→查审批状态 |
| F-018 | 退货审批后仓管出货(出库) | SP6 | P1 | ✅已建 | V2弱 | APPROVED→仓管出库; TransferController | 退货批准后查出库记录 |
| F-019 | 报损实体(WastageReport) | SP7 | P0 | ✅已建 | V1强 | entity/inventory/WastageReport.java; V20261010_24; WASTAGE_REPORT id:d9d59a86 E2E | 查 wastage_reports 表结构 |
| F-020 | 报损 Service 完整(WastageReportServiceImpl) | SP7 | P0 | ✅已建 | V1强 | service/inventory/impl/WastageReportServiceImpl.java; WASTAGE_REPORT E2E | POST /{fid}/wastage-reports |
| F-021 | 报损 Controller(WastageReportController) | SP7 | P0 | ✅已建 | V1强 | controller/WastageReportController.java; id:d9d59a86 E2E | GET /{fid}/wastage-reports |
| F-022 | 报损 DTO(WastageReportDTO/CreateWastageReportRequest) | SP7 | P0 | ✅已建 | V2弱 | dto/inventory/WastageReportDTO.java; CreateWastageReportRequest.java | 查 DTO 字段完整性 |
| F-023 | 报损双轨(WAREHOUSE track→财务审批 / FACTORY track→厂长) | SP7 | P1 | ✅已建 | V2弱 | WastageReportServiceImpl FACTORY_TRACK/WAREHOUSE_TRACK分支; 状态机 | 创建 WAREHOUSE track 报损→查审批节点 |
| F-024 | 报损状态机(DRAFT→PENDING_APPROVAL→APPROVED/REJECTED→APPLIED) | SP7 | P0 | ✅已建 | V2弱 | WastageReportServiceImpl.approveWastageReport; 完整状态流转 | 报损走完审批→查状态=APPLIED |
| F-025 | 报损管理页(web + RN 对接真实 API) | SP7 | P1 | 🟡部分 | V1强 | WASTAGE_REPORT E2E造; WastageReportScreen.tsx→wastageReportApiClient(RN真API); FIXB#5 web新页/warehouse/wastage-reports待核是否真对接 | Web访问/warehouse/wastage-reports查id:d9d59a86 |
| F-026 | 盘点只能月底29日后发起(约束) | SP7 | P0 | ✅已建 | V1强 | E2E error: "盘点任务只能在月底（29日后）发起，当前是 2026-06-10，下次可发起日期: 2026-06-29" | 等 2026-06-29 后验证可发起 |
| F-027 | 盘点 RN 入口正确连接盘点 API | SP7 | P1 | 🟡部分 | B阻塞(月底约束) | StocktakeEntryScreen.tsx→stocktakeApiClient ✅; WHInventoryCheckScreen.tsx有 TODO "后端需要提供库存调整API" 调旧路径 | 等6月29日后: 新盘点入口→查 factory_stocktakes 表 |
| F-028 | 盘点录入屏(StocktakeEntryScreen 正确对接) | SP7 | P1 | ✅已建 | B阻塞(月底约束) | StocktakeEntryScreen.tsx 已连 stocktakeApiClient; 月底前无法E2E | 等6月29日后全流程验 |
| F-029 | 盘点差异预览(FactoryStocktakeDiffPreviewDTO) | SP7 | P1 | ✅已建 | V2弱 | dto/factory/StocktakeDiffPreviewDTO.java; FactoryStocktakeItem.difference_qty V20261010_23 | GET /{id}/diff-preview |
| F-030 | 盘点审批流(WorkflowEngineService + PENDING_APPROVAL) | SP7 | P1 | ✅已建 | V2弱 | FactoryStocktakeServiceImpl WorkflowEngineService; PENDING_APPROVAL状态机 | 创建盘点→提审批→查状态 |
| F-031 | 调拨需求单(计划下达后按BOM占用量发给仓库) | 核心 | P0 | ✅已建 | V2弱 | TransferController; TransferServiceImpl; MaterialRequisitionServiceImpl | 下计划后查 transfers 调拨需求 |
| F-032 | 调拨接收 actualQuantity 持久化 | FIXB | P1 | 🟡部分 | V0 | FIXB#3 列为修复项; TransferController.receive 端点不收实收数量 | FIXB#3 完成后: receive 传 actualQuantity→查差异字段 |
| F-033 | 仓库备料后发料流转(仓管接单出货) | 核心 | P0 | ✅已建 | V2弱 | TransferServiceImpl; TransferController.receive | 调拨ACCEPTED→查库存变动 |
| F-034 | 库存不足同步报警(采购+仓库) | 核心 | P1 | 🟡部分 | V0 | 反推领料量→不足时逻辑; 告警推送完整性待查 | 库存低于阈值→查通知记录 |
| F-035 | 多仓体系(原料仓/WIP仓/成品仓/盐化仓) | SP7 | P0 | ✅已建 | V2弱 | FactoryWarehouse.WarehouseType: RAW/WIP/FG/SALTED; V20261010_22; WarehouseInventoryGuardService | 查 factory_warehouses.warehouse_type |
| F-036 | WIP仓只允许半成品入库(Guard) | SP7 | P1 | ✅已建 | V2弱 | WarehouseInventoryGuardService WIP仓case; 409 INBOUND_TYPE_MISMATCH | WIP仓尝试入原料→409 |
| F-037 | 盐化仓(SALTED)仅接受原料 | SP7 | P1 | ✅已建 | V2弱 | WarehouseInventoryGuardService line:75-77: RAW, SALTED同处理; 腌制工序专属 | 盐化仓仅接受 RAW_MATERIAL 物料 |
| F-038 | 出库联动销售/成品库存扣减 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 6.1 出货 SH-F006-B18C96 shipped | 出库后查 finished_goods_batches 库存减少 |
| F-039 | 成品入库→出库给客户全链路 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg→出货单 | 追查出库单状态=SHIPPED |
| F-040 | 仓管员操作界面防呆(文化素质低) | fool-proof | P0 | ✅已建 | V1强 | ACTIVE.md RN fool-proof redesign; UX Flow规则 | 真机f006_moyun 完整操作流 |
| F-041 | 入库时扫码自动弹出对应包材 | SP8 | P1 | 🟡部分 | V0 | SP8扫码搜索端点存在; 扫码弹出完整性待验 | 扫 LB-F006-xx 条码→验自动填入 |
| F-042 | 物料编码前缀分类(001=原料/002=包材/003=辅料) | SP8 | P1 | ✅已建 | V2弱 | MaterialCodeSegment 前缀映射; RawMaterialType.category | 查 material_code_segments 前缀规则 |
| F-043 | BC前缀=包材自动归类 | SP8 | P1 | ✅已建 | V2弱 | MaterialCodeSegmentController SP8 前缀分类逻辑 | 建 BC 前缀编码→自动归包材类 |
| F-044 | 包材建档极简(名称+箱数+规格, 编号自动生成) | fool-proof | P1 | ✅已建 | V2弱 | 包材建档 Web 页; 必填最少化设计 | Web 建包材只填名称+箱数+规格验通过 |
| F-045 | 包材可关联固定客户(非必填) | 核心 | P2 | ✅已建 | V2弱 | Material.customerId 可选字段 | 建包材不填客户→成功 |
| F-046 | 进货凭证支持暂估入库/来票/未来票/账期 | SP6 | P1 | ✅已建 | V2弱 | PurchaseOrder.paymentType; PurchaseOrder.invoiceStatus | 查 purchase_orders.invoice_status |
| F-047 | 采购单含现结/赊结标识 | SP6 | P1 | ✅已建 | V2弱 | PurchaseOrder.settlementType | 查 purchase_orders.settlement_type |
| F-048 | 进销存台账(期初/期入/期出/期末) | SP11 | P1 | ✅已建 | V2弱 | controller/inventory/InventoryLedgerController.java; InventoryLedgerServiceImpl 期初/期末逻辑; InventoryLedgerSnapshot; V20261011_21 | GET /{fid}/inventory-ledger?year=2026&month=6 |
| F-049 | 进销存 RN 统计页(WHIOStatisticsScreen) | SP11 | P1 | 🔴缺 | N/A | WHIOStatisticsScreen.tsx 确认 hardcoded mock: 带鱼/虾仁/鲈鱼/蟹类 静态数组; 后端 InventoryLedgerController 已建但 RN 未对接 | 实现后: RN 统计页显示真实 F006 数据 |
| F-050 | 进销存报表期间选择(月度) | SP11 | P1 | 🔴缺 | N/A | WHIOStatisticsScreen 未对接; Web端账单页对接状态待查 | 同F-049 |
| F-051 | 进销存报表导出PDF/Excel | SP11 | P2 | 🔴缺 | N/A | InventoryLedgerController 无导出端点; PrintController 无此类型 | 待实现后验证 |
| F-052 | 进销存数据聚合(物料维度汇总) | SP11 | P2 | 🔴缺 | N/A | InventoryLedgerServiceImpl 期初/末逻辑存在; 但物料维度聚合端点待查 | GET ledger 接口返回物料维度数据 |
| F-053 | 进销存 Web 管理台(web-admin 对接账单) | SP11 | P1 | 🟡部分 | V0 | InventoryLedgerController 已有; web-admin 页面对接状态未验证 | 访问 web-admin 进销存页查 F006 数据 |
| F-054 | 盐化仓类型(SALTED)已加入枚举 | SP7 | P0 | ✅已建 | V2弱 | FactoryWarehouse.WarehouseType.SALTED; V20261010_22; FactoryWarehouseSchemaTest line:48-49 | 查 factory_warehouse_type 枚举含 SALTED |
| F-055 | 盐化仓出量独立记录(腌制工序专属报工) | SP7 | P1 | 🔴缺 | N/A | WarehouseInventoryGuardService 有 Guard 但无独立出量报工端点; git grep saltedOutput 无结果 | 待实现后: 盐化仓操作产生独立出量记录 |
| F-056 | 盐化仓独立库存报告(腌制品分离统计) | SP7 | P2 | 🔴缺 | N/A | 无专用盐化仓报告端点 | 待实现后: 报告按 SALTED warehouse 聚合 |
| F-057 | 仓管只操作DEMO数据(真客户保护) | ⚪约束 | P0 | ⚪约束项 | N/A | prod 测试账号只动 DEMO标记数据 | 操作规范 |
| F-058 | 生产领料记录详细(领了多少) | 核心 | P0 | ✅已建 | V2弱 | MaterialRequisitionController; MaterialRequisition实体 | 查 material_requisitions 按批次 |
| F-059 | 水耗多领料报损成本增加 | 核心 | P1 | ✅已建 | V2弱 | WastageRecord 额外领料; 成本分摊含报损 | 报损后查批次总成本上升 |
| F-060 | 报损后重新领料走调拨 | 核心 | P1 | ✅已建 | V2弱 | 调拨不与报损强绑; 独立 TransferController | 报损→新建调拨单不报错 |
| F-061 | 半成品在制状态(IN_PROGRESS标注) | SP1/G7 | P1 | ✅已建 | V2弱 | SemiFinishedInventory.status; WIP in-progress标注 | 查 semi_finished_inventory_transactions.status |
| F-062 | 仓库库存联动生产实时反馈 | 核心 | P0 | ✅已建 | V2弱 | 库存扣减在领料时实时; MaterialBatchRepository | 领料后查 material_batches.remaining_quantity |
| F-063 | 出成率统计信息查询(每产品实际出成率) | SP9 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl.getYieldStatsByProductType; work_processes.standard_yield | 查出成率 API 按品类 |
| F-064 | 成品库存显示(AVAILABLE成品批次) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg AVAILABLE; FinishedGoodsBatchController | 查 finished_goods_batches.status=AVAILABLE |
| F-065 | 出库管理(成品出货单) | 核心 | P0 | ✅已建 | V1强 | ShipmentController; ACTIVE.md SH-F006-B18C96 shipped | 查 shipments 出货单 |
| F-066 | 多仓调拨单(PENDING→ACCEPTED流程) | 核心 | P1 | ✅已建 | V2弱 | TransferServiceImpl; Transfer.status状态机 | 创建调拨→查状态流转 |
| F-067 | 半成品库存事务记录(SemiFinishedInventoryTransaction) | SP1 | P0 | ✅已建 | V2弱 | entity/SemiFinishedInventoryTransaction.java ✅; V20261010_02 semi_finished_inventory_transactions | 查表结构和记录 |
| F-068 | 付款申请单(PAYMENT_REQUEST) | SP6 | P1 | ✅已建 | V1强 | PAYMENT_REQUEST id:b92708a4 E2E造; PaymentRequestController; V20261011_09 | 查 payment_requests id=b92708a4 |
| F-069 | 入库异常管理页(web-admin /procurement/exceptions) | SP6 | P1 | ✅已建 | V1强 | PURCHASE_EXCEPTION id:9a5c21a2 E2E造; FIXB#6 确认页面对接正常 | 访问/procurement/exceptions查 id:9a5c21a2 |
| F-070 | 原料入库价格锁定(入库后不变) | ⚪约束 | P0 | ⚪约束项 | N/A | MaterialBatch.purchaseCost 入库时锁定 | 入库后修改价格→验字段不变 |

---

### F 流统计

| 状态 | 数量 | 占比 |
|------|------|------|
| ✅已建 | 47 | 67% |
| 🟡部分 | 7 | 10% |
| 🔴缺 | 6 | 9% |
| ⚪约束项 | 10 | 14% |
| **合计** | **70** | **100%** |

| 验证级别 | 数量 |
|---------|------|
| V1强 (E2E/真机) | 16 |
| V2弱 (代码链路) | 31 |
| V0 未验证 | 4 |
| B阻塞 | 2 |
| N/A | 17 |

**Top F 流风险**:

1. **F-049/F-050/F-051/F-052** (RN 进销存页 mock 硬编码): WHIOStatisticsScreen.tsx 确认有带鱼/虾仁 hardcoded 静态数组，后端 InventoryLedgerServiceImpl 已建好但未对接。对客户演示影响大。
2. **F-027** (WHInventoryCheckScreen 调旧路径): 月底约束导致本期无法 E2E 验证，且 WHInventoryCheckScreen 仍有 TODO 直调旧接口。
3. **F-055/F-056** (盐化仓专属报工/报告): SALTED 类型建立但出量记录和独立报告缺失。
4. **F-032** (调拨 actualQuantity): FIXB#3 已列修复待完成。
5. **F-053** (进销存 Web 页): InventoryLedgerController 已有但 web-admin 对接状态未验证。

---

## 总体风险 Top 5

| 排名 | 风险 | 涉及条目 | 优先级 |
|------|------|---------|--------|
| 1 | **T-3时效锁死缺失**: 审计硬规则未实现，客户明确"大前天不允许补" | C-074/C-075 | P1 |
| 2 | **RN进销存页 mock 硬编码**: WHIOStatisticsScreen 4个相关条目完全未对接后端 | F-049~F-052 | P1 |
| 3 | **盘点月底约束阻塞验证**: 6月29日前无法 E2E 验盘点完整链路 | F-026/F-027/F-028 | P0 (时间阻塞) |
| 4 | **FIXB批未完成依赖**: C-033(batchSourceType)、F-032(actualQuantity) 在 FIXB 修复中 | C-033/F-032 | P1 |
| 5 | **盐化仓出量独立记录缺失**: SALTED Guard 已建但专属报工和报告缺失 | F-055/F-056 | P1 |

---

## V1 前版本结论翻转清单 (v1→v2 关键修正)

以下条目在前版本 (stale 工作树取证) 中被错误标为 🔴缺，v2 基于 origin/main 取证后已纠正:

| 条目 | v1 错误结论 | v2 正确结论 | 关键证据 |
|------|------------|------------|---------|
| C-030/C-031/C-032 | 🔴缺 "P20/P80 auto-learning 未实现" | ✅已建 | YieldStandardCalculationServiceImpl percentile(yieldRates,0.20/0.80); YieldStandardCalculationScheduler; workProcessRepository.save 写回 |
| C-037/C-038 | 🔴缺 "SP2未执行;main无迁移" | ✅已建 | ProductionPlanServiceImpl.createSecondaryPlan line:1750; V20261011_07; ProductionPlanServiceSecondaryTest |
| C-040/C-041/C-042 | 🔴缺 "ReportReversalLog entity未找到" | ✅已建 V1 | entity/ReportReversalLog.java; REVERSAL_LOG id:3 E2E; approveReversal/rejectReversal; ACTIVE.md 17测试绿 |
| C-043/C-044/C-045/C-046 | 🔴缺 "ReportReversalServiceImpl未找到" | ✅已建 V1 | service/reversal/impl/ReportReversalServiceImpl.java; approveReversal line:94/rejectReversal line:110/executeReversal; ACTIVE.md V1 |
| C-071/C-072 | 🔴缺 "SP7未执行;main无WastageReport实体" | ✅已建 V1 | entity/inventory/WastageReport.java; WASTAGE_REPORT id:d9d59a86 E2E; WastageReportServiceImpl FACTORY/WAREHOUSE双轨 |
| F-019~F-024 | 🔴缺 "main无factory_stocktakes/wastage_reports" | ✅已建 (全6项) V1 | FactoryStocktake.java+FactoryStocktakeItem.java+WastageReport.java+Service+Controller 全在 origin/main; WASTAGE_REPORT E2E V1 |
| F-026 | 状态不确定 | ✅已建 V1 | E2E error message 精确: "盘点任务只能在月底（29日后）发起，当前是 2026-06-10，下次可发起日期: 2026-06-29" |
| F-028/F-029/F-030 | 🔴缺 | ✅已建 | StocktakeEntryScreen→stocktakeApiClient; StocktakeDiffPreviewDTO; FactoryStocktakeServiceImpl WorkflowEngineService |
| F-048 | 🟡部分 | ✅已建 | InventoryLedgerController+InventoryLedgerServiceImpl+InventoryLedgerSnapshot V20261011_21 全在 origin/main |
| F-054 | 🔴缺 | ✅已建 | FactoryWarehouse.WarehouseType.SALTED; V20261010_22; FactoryWarehouseSchemaTest |
| F-067 | 🔴缺 "SP1未执行;main无此实体文件" | ✅已建 | entity/SemiFinishedInventoryTransaction.java; V20261010_02 |
| F-068 | 🔴缺 | ✅已建 V1 | PAYMENT_REQUEST id:b92708a4 E2E; PaymentRequestController; V20261011_09 |

**根因**: v1 取证时工作树停 V20261001_02，V20261010_xx~V20261011_xx 全部迁移及对应 entity/service 均未出现在工作树，导致 SP1/SP2/SP6/SP7/SP9/SP11/SP12 大量代码被误判缺失。v2 严格执行 `git ls-tree origin/main` + `git grep origin/main`，取证来自实际已合并代码，结论与 E2E run-20260610_124749.json 实体证据完全一致。
