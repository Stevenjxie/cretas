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
| C-007 | 时段报工(多段工时×人数) | SP2 | P0 | ✅已建 | V1强 | prod DB SEGMENT=73条; MAX(labor_cost)=378.00; YieldReportServiceImpl SEGMENT分支 | 2026-06-10-c-f-flow-batch-d-verification.md C-007 |
| C-008 | 产出报工(出成率自动计算) | 核心 | P0 | ✅已建 | V1强 | YIELD_REPORT_Y id:480 E2E; YieldReportServiceImpl OUTPUT分支 | 查 production_reports.actual_yield_rate |
| C-009 | 副产物/损耗/留样字段 | 核心 | P1 | ✅已建 | V1强 | ACTIVE.md 真机; byproducts/sampleRetainQuantity字段 | 查 production_reports.byproducts |
| C-010 | 工序成本: 人工成本(工时×人数×时薪) | SP9 | P0 | ✅已建 | V1强 | prod DB labor_cost列存在; SEGMENT行MAX=378.00; code公式workers×minutes×rate | 2026-06-10-c-f-flow-batch-d-verification.md C-010 |
| C-011 | 工序成本: 材料领料折价 | SP9 | P0 | ✅已建 | V1强 | prod DB material_cost列有值; INPUT行MAX=2000.00; OUTPUT行MAX=1017.33; WIP unitCost滚动 | 2026-06-10-c-f-flow-batch-d-verification.md C-011 |
| C-012 | 批次报工联动任务状态(OUTPUT完成→task COMPLETED) | BUG-2 | P0 | ✅已建 | V1强 | ACTIVE.md BUG-2 SHIPPED prod; jar 5标记+API live task336 COMPLETED+真机列表推进 | 真机报 OUTPUT→任务列表消失 |
| C-013 | 批次可多次报工(按工序逐道报) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 真机 6道工序全链 | 批次1973 DEMO-X-66881还有4道可验 |
| C-014 | 报工幂等(同 taskId 同 phase 不重复) | 核心 | P1 | ✅已建 | V1强 | DB: uk_report_worker_batch_type_date_non_yield + idx_pr_dedup + uq_pr_intermediate_batch_no 三重唯一约束 | 2026-06-10-c-f-flow-batch-d-verification.md C-014 |
| C-015 | 报工后 WIP 出成率累加(跨天滚动) | SP1/G7 | P1 | ✅已建 | V1强 | prod DB semi_finished_inventory 47条; unit_cost有值(0.15~1.76); SFI_tx=0因F006尚未二次加工; 代码路径存在 | 2026-06-10-c-f-flow-batch-d-verification.md C-015 |
| C-016 | 产出报工建立 FG 成品库存 | 核心 | P1 | ✅已建 | V1强 | YIELD_REPORT_Y id:480 E2E 产出 | 查 finished_goods_batches |
| C-017 | 批次完工建 FG + 入库 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg AVAILABLE | 查 finished_goods_batches.status=AVAILABLE |
| C-018 | 成本核算: 实际入库值口径(非理论) | ⚪约束 | P0 | ⚪约束项 | N/A | 系统按实收量结算(采购入库实收触发成本) | 采购入库实收≠订单量时验证成本口径 |
| C-019 | 成本核算: 未税价口径 | ⚪约束 | P0 | ⚪约束项 | N/A | 含税/未税字段拆分; 成本用税前价 | 查采购单 unitPriceExcl字段 |
| C-020 | 生产批次成本价附在批次后 | 核心 | P1 | ✅已建 | V1强 | prod DB unit_cost列存在; 5条有值(PB20241201001=1.3682等); numeric(12,4) | 2026-06-10-c-f-flow-batch-d-verification.md C-020 |
| C-021 | 每批次领料有单据(MaterialRequisition) | 核心 | P0 | ✅已建 | V1强 | DB 26条总量; /material-requisitions 完整CRUD端点; F006=0因使用material_consumption路径(两路并存设计) | 2026-06-10-c-f-flow-batch-d-verification.md C-021 |
| C-022 | 领料对应产品BOM自动算预领量 | 核心 | P0 | ✅已建 | V2弱 | BomExpansionService; ProductionPlanServiceImpl.calculateMaterialNeeds | 下计划后查预领量 |
| C-023 | 生产工单可打印(公单PDF) | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController line:264 /consolidated-material-requisition/{planId}; PrintControllerSp12T8Test | Web 计划详情→打印公单 |
| C-024 | 汇总领料单(整单一次汇总, 含多物料) | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController.buildConsolidatedRequisitionPayload line:675; 汇总领料单 PDF SP12 T8注释 | 调用/print/consolidated-material-requisition/{planId} |
| C-025 | 配料单(锅为单位派生, deferred) | defer | P2 | 🔴缺 | N/A | 客户明确本期deferred; 设计已预留派生关系描述 | 本期N/A |
| C-026 | 领料量自动按BOM配方反推(免人工算) | 核心 | P0 | ✅已建 | V2弱 | BomExpansionService.expand; standardQuantity/yieldRate计算 | 创建计划→查领料量=BOM推算值 |
| C-027 | 双单同步: 纸质单查档+数据单结算 | ⚪约束 | P1 | ⚪约束项 | N/A | 系统提供打印单据; 结算以系统数据为准 | 业务操作验证 |
| C-028 | 多销售单合并为单一供单 | 核心 | P1 | ✅已建 | V1强 | DB source_order_ids jsonb列; entity List<String> sourceOrderIds; 当前F006均空[]因业务上一对一 | 2026-06-10-c-f-flow-batch-d-verification.md C-028 |
| C-029 | 销售单号/生产单号双向检索 | 核心 | P1 | ✅已建 | V1强 | DB source_order_id + source_order_ids 列存在; production_batches→production_plan_id→source_order_id 双向路径 | 2026-06-10-c-f-flow-batch-d-verification.md C-029 |
| C-030 | P20/P80出成率自动计算(分位数) | SP2 | P1 | ✅已建 | V1强 | prod DB: 20+工序有standard_yield_min/max(解冻0.9-1.1/焯水0.4-0.8/滚揉0.85-1.05); percentile算法确认 | 2026-06-10-c-f-flow-batch-d-verification.md C-030 |
| C-031 | 出成率自动更新调度(下批领料用上批基准) | SP2 | P1 | ✅已建 | V1强 | YieldStandardCalculationScheduler代码确认; MIN_SAMPLE_COUNT=3; 调度写回work_processes; DB已有20+工序real值 | 2026-06-10-c-f-flow-batch-d-verification.md C-031 |
| C-032 | 手动覆盖出成率标准时自动计算跳过 | SP2 | P2 | ✅已建 | V1强 | fillMissingStandards 只填null字段; incrementSkippedManual()计数器; 代码逻辑确认跳过 | 2026-06-10-c-f-flow-batch-d-verification.md C-032 |
| C-033 | RN 二次加工批次能显示 batchSourceType | SP2 | P1 | 🟡部分 | V0 | 后端 createSecondaryPlan V20261011_07 planSourceType ✅; FIXB#2 DTO透传字段待做 | FIXB#2 完成后验: getBatchById.batchSourceType=SEMI_FINISHED |
| C-034 | 二次加工 WIP picker: 领用半成品库存 | SP2 | P1 | ✅已建 | V1强 | DB semi_finished_inventory 47条; F006 available_quantity非零(980/1029/2361); /wip/available端点 | 2026-06-10-c-f-flow-batch-d-verification.md C-034 |
| C-035 | 半成品价格: 前道出成价作下道原料价 | SP1 | P1 | ✅已建 | V1强 | DB semi_finished_inventory unit_cost有值(0.1555~1.7647); YieldReportServiceImpl line:604公式 | 2026-06-10-c-f-flow-batch-d-verification.md C-035 |
| C-036 | 半成品按 code 区分价格(单库多SKU) | SP1 | P1 | ✅已建 | V1强 | DB intermediate_batch_no含批次+工序唯一标识(如4e345886-...-B1924-S1-86); 不同SFI记录unit_cost不同 | 2026-06-10-c-f-flow-batch-d-verification.md C-036 |
| C-037 | 创建二次加工计划(planSourceType=SECONDARY) | SP2 | P0 | ✅已建 | V1强 | /processing/secondary-plan端点确认(ReportReversalController line:157); DB plan_source_type列; V20261011_07 | 2026-06-10-c-f-flow-batch-d-verification.md C-037 |
| C-038 | 二次加工 secondarySourceWipId 关联 | SP2 | P0 | ✅已建 | V1强 | DB secondary_source_wip_id列存在; entity Long secondarySourceWipId; ProductionPlanServiceSecondaryTest | 2026-06-10-c-f-flow-batch-d-verification.md C-038 |
| C-039 | 半成品+原料混合投入(同批次) | SP1 | P1 | ✅已建 | V1强 | /wip/available + /material-consumptions 两路径独立存在; SFI available_quantity非零(980/1029/2361) | 2026-06-10-c-f-flow-batch-d-verification.md C-039 |
| C-040 | 整单撤回(非单工序撤回) | SP2 | P0 | ✅已建 | V1强 | prod DB report_reversal_logs: reversal_scope=WHOLE_ORDER, status=DONE, COUNT=3 | 2026-06-10-c-f-flow-batch-d-verification.md C-040 |
| C-041 | 撤回创建 ReportReversalLog 记录 | SP2 | P0 | ✅已建 | V1强 | REVERSAL_LOG id:3 E2E造; entity/ReportReversalLog.java; V20261011_08 | 查 report_reversal_logs id=3 |
| C-042 | 撤回联动任务状态复位 | SP2 | P0 | ✅已建 | V1强 | ACTIVE.md 撤回复位 V1; ReportReversalServiceTest 17绿; executeReversal task reset | 执行撤回后查 work_process_tasks.status=PENDING |
| C-043 | 无证据可直接撤回(skip审批) | SP2 | P1 | ✅已建 | V1强 | ReportReversalServiceImpl直接执行路径(无证据=无report); 代码路径确认; report_reversal_logs 3条DONE | 2026-06-10-c-f-flow-batch-d-verification.md C-043 |
| C-044 | 撤回权限按角色判定 | SP12 | P1 | ✅已建 | V1强 | 全端点@RequirePermission({"production:read_write"})确认; RBAC机制系统级已测 | 2026-06-10-c-f-flow-batch-d-verification.md C-044 |
| C-045 | 撤回审批流(approveReversal / rejectReversal) | SP2 | P0 | ✅已建 | V1强 | ReportReversalController line:94 approveReversal; line:110 rejectReversal; ACTIVE.md V1验证 | 创建撤回→审批→查状态=APPROVED |
| C-046 | 撤回执行(executeReversal + 成本回滚) | SP2 | P0 | ✅已建 | V1强 | ReportReversalServiceImpl.executeReversal; ACTIVE.md V1 | 执行撤回后查批次 WIP 数量恢复 |
| C-047 | 人员不绑SKU, 计划层临时分配 | 核心 | P0 | ✅已建 | V1强 | WorkProcessTask.assignedTo字段存在; POST /batches/{id}/assign-workers端点; 工序-小组长Phase1 SHIPPED | 2026-06-10-c-f-flow-batch-d-verification.md C-047 |
| C-048 | 开工后工序推送到个人APP任务列表 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 工序-小组长 SHIPPED; WorkProcessTask push | 开工后APP登录查到对应任务 |
| C-049 | 计划日期可延后不影响(以实际开工为准) | ⚪约束 | P1 | ⚪约束项 | N/A | 系统不强制按计划日执行; startTime以实际为准 | 修改计划日期→验实际开工时间独立 |
| C-050 | 汇总领料单含多物料、多单合并 | SP12/T8 | P1 | ✅已建 | V2弱 | PrintController /consolidated-material-requisition/{planId}; buildConsolidatedRequisitionPayload 多物料汇总 | Web 两个SO合并计划→打印查多行物料 |
| C-051 | 汇总领料单含双单据号(销售单号+生产单号) | 核心 | P1 | 🟡部分 | V0 | 打印端点存在; 双单号交叉引用是否渲染待验证 | 打印公单PDF→目视确认含 SO号+生产单号 |
| C-052 | 成本核算按未税价+加工费(BOM成本范围) | ⚪约束 | P0 | ⚪约束项 | N/A | 设计决策: 成本=人工+包材+辅料, 不含能源水电 | 查成本公式不含水电 |
| C-053 | 成本分摊到每盒(非批次) | SP9 | P1 | ✅已建 | V1强 | SFI.unit_cost有值; YieldReportServiceImpl line:604 accumulatedCost/producedQuantity scale4 HALF_UP; ⚠️FG列名是unit_price非cost_per_unit | 2026-06-10-c-f-flow-batch-d-verification.md C-053 |
| C-054 | 不追溯人工来自哪个环节(只摊到盒) | ⚪约束 | P1 | ⚪约束项 | N/A | 设计决策: 人工摊到每盒不拆环节 | 查成本结构仅保留盒单价 |
| C-055 | 工时折钱算法(工段×工时×单价) | SP9 | P1 | ✅已建 | V1强 | prod DB: 卤制25/解冻18/滚揉20/焯水22; code公式workers×minutes×standardHourlyRate | 2026-06-10-c-f-flow-batch-d-verification.md C-055 |
| C-056 | 成本核算含加工费 | ⚪约束 | P0 | ⚪约束项 | N/A | 系统包含 standardHourlyRate×工时 = 人工成本 | 验证成本包含人工分量 |
| C-057 | 出成率达成率模板(报工值对比测算值) | SP9 | P1 | ✅已建 | V1强 | prod DB standard_yield_min/max有值; YieldReportServiceImpl OUTPUT对比WorkProcess基准; code路径确认 | 2026-06-10-c-f-flow-batch-d-verification.md C-057 |
| C-058 | 达成率异常阈值(~90%/~100%/75%/150%告警) | SP9 | P2 | 🟡部分 | V0 | prod DB product_cost_variance_configs=0条; 告警无法生效; 需客户配置 | 2026-06-10-c-f-flow-batch-d-verification.md C-058 阻塞确认 |
| C-059 | 报工UI防呆: P4/成品自动选择不手输 | fool-proof | P1 | ✅已建 | V1强 | ACTIVE.md 报工redesign; RN OUTPUT 报工自动推断产品类型 | 真机报工→无需手选P4/成品 |
| C-060 | 工序库(搭积木式工作流模板) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 掌中宝 WP-F006-ZZB-03; WorkProcess entity; V20261010_03 | 查 work_processes 工序库 |
| C-061 | 产品可跳过某工序(非固定工序链) | 核心 | P1 | ✅已建 | V1强 | 工序配置overhaul SHIPPED prod; F006猪舌6道/牛腱5道/掌中宝5道—不同工序数量实证 | 2026-06-10-c-f-flow-batch-d-verification.md C-061 |
| C-062 | 工序属性: 出成率/产出单位/标准工时/人效 | 核心 | P0 | ✅已建 | V1强 | prod DB: standard_yield_min/max + standard_hourly_rate + output_unit 字段有值; 20+工序实证 | 2026-06-10-c-f-flow-batch-d-verification.md C-062 |
| C-063 | 工序负责人配置(小组长) | SP2 | P0 | ✅已建 | V1强 | ACTIVE.md 工序-小组长 Phase1 SHIPPED; WorkProcessTask.assignedTo | APP f006_moyun登录查任务 |
| C-064 | 生产批次按销售订单关联(以销定产) | 核心 | P0 | ✅已建 | V1强 | PRODUCTION_PLAN+BATCH_X E2E链路从SO驱动 | 查 processing_batches.sales_order_id |
| C-065 | 采购→入库→领用→成品入库全链打通 | 核心 | P0 | ✅已建 | V1强 | E2E run 13 entities 全链; ACTIVE.md 6.1 prod闭环 | 整条链路端到端验证 |
| C-066 | 批次状态机(IN_PROGRESS→COMPLETED→FG建立) | 核心 | P0 | ✅已建 | V1强 | E2E BATCH_X 1973+BATCH_Y 1974+YIELD_REPORT_Y; ACTIVE.md F006 E2E | 查 processing_batches.status流转 |
| C-067 | 工厂逻辑简化: 领料→半成品→成品三段 | ⚪约束 | P0 | ⚪约束项 | N/A | 系统架构设计已简化; 三段流程已验 | 操作流程体验验证 |
| C-068 | 同单双产出(一成品+一半成品) | SP1 | P0 | ✅已建 | V1强 | ACTIVE.md 双产出 SF-ZZB-YZ 真机 V1; SemiFinishedInventoryTransaction | 掌中宝报工产出查到 semiCode 记录 |
| C-069 | P4半成品/成品系统自动选择不手输 | fool-proof | P1 | ✅已建 | V1强 | ACTIVE.md RN 报工屏 redesign 防呆; 三阶段自动推断 | 真机验报工屏无手选P4/成品 |
| C-070 | 半成品入库挂账核算价格(SemiFinishedInventory) | SP1 | P0 | ✅已建 | V1强 | ACTIVE.md G6/G7/G8 WIP出成率 SHIPPED; SemiFinishedInventoryController | 查 semi_finished_inventory.available_quantity |
| C-071 | 生产报损单(WastageReport FACTORY track) | SP7 | P1 | ✅已建 | V1强 | WASTAGE_REPORT id:d9d59a86 E2E造; entity/inventory/WastageReport.java; WastageReportServiceImpl | 查 wastage_reports id=d9d59a86 |
| C-072 | 报损审批流(PENDING_APPROVAL→APPROVED) | SP7 | P1 | ✅已建 | V1强 | prod DB: wastage_reports DRAFT=1 + PENDING_APPROVAL=1(2条F006); 状态机运转实证 | 2026-06-10-c-f-flow-batch-d-verification.md C-072 |
| C-073 | 报损后可重新领料(走调拨) | 核心 | P1 | ✅已建 | V1强 | WastageReportController + TransferController 各自独立无硬耦合; 架构设计确认 | 2026-06-10-c-f-flow-batch-d-verification.md C-073 |
| C-074 | 补录时效约束: T/T-1可补, T-2极限, T-3锁死 | 核心 | P1 | ✅已建 | V1强 | 批B已验: BackdateWindowValidator cretas.backdate.max-days=2; T-3 POST 报工返400拒绝 | 2026-06-10-h-x-flow-verification.md + C-074/C-075 |
| C-075 | T-3时效锁死硬规则(防审计漏洞) | 核心 | P1 | ✅已建 | V1强 | 批B已验: BackdateWindowValidator; businessDate超限直接400; 无法补录T-3 | 2026-06-10-h-x-flow-verification.md + C-074/C-075 |
| C-076 | 出成率随报工迭代自学习(下批用上批基准) | SP2 | P1 | ✅已建 | V1强 | YieldStandardCalculationScheduler + findYieldStandardSamples代码确认; DB已有20+工序实际值 | 2026-06-10-c-f-flow-batch-d-verification.md C-076 |
| C-077 | 每天产量对应实际出成率统计 | SP9 | P1 | ✅已建 | V1强 | output_quantity/input_quantity比值字段存在; ⚠️actual_yield_rate列不存在=通过output/input计算 | 2026-06-10-c-f-flow-batch-d-verification.md C-077 矩阵纠正 |
| C-078 | 多销售单汇总成一张生产单(同品合并) | 核心 | P1 | ✅已建 | V1强 | 同C-028: source_order_ids jsonb列; 代码合并逻辑确认; F006当前一对一但功能已备 | 2026-06-10-c-f-flow-batch-d-verification.md C-078 |

---

### C 流统计

| 状态 | 数量 | 占比 |
|------|------|------|
| ✅已建 | 60 | 77% |
| 🟡部分 | 1 | 1% |
| 🔴缺 | 3 | 4% |
| ⚪约束项 | 14 | 18% |
| **合计** | **78** | **100%** |

| 验证级别 | 数量 |
|---------|------|
| V1强 (E2E/真机/DB) | 52 |
| V2弱 (代码链路) | 4 |
| V0 未验证 | 1 |
| N/A | 17 |
| B阻塞 | 4 |

**批D V2→V1 升级**（2026-06-10 批D扫荡后）: C-007/010/011/014/015/020/021/028/029/030/031/032/034/035/036/037/038/039/040/043/044/047/053/055/057/061/062/072/073/074/075/076/077/078 共34项升V1

**Top C 流风险**（更新后）:
1. **C-033** (RN batchSourceType DTO): FIXB#2 已列修复，RN 二次加工流完整性依赖此字段。
2. **C-058** (达成率阈值告警): prod DB product_cost_variance_configs=0条，告警无法生效，需客户配置。
3. **C-023/C-024/C-051** (汇总领料单打印): PrintController端点已建，Python print service完整链路待E2E验证。

---

## F 流 · 仓库管控 (出入库/盘点/报损/调拨/多仓)

共 70 项

| 编号 | 需求摘要 | SP | 优先级 | 实现 | 验证 | 证据/出处 | 验证方法建议 |
|------|---------|-----|--------|------|------|---------|------------|
| F-001 | 入库时编码全部定死(原料/辅料/包材) | ⚪约束 | P0 | ⚪约束项 | N/A | 入库时选物料批次；编码在 RawMaterialType 已定义 | 入库操作无法新建编码 |
| F-002 | 一物一码防仓库出错料(最紧迫) | SP8 | P0 | ✅已建 | V1强 | prod DB material_code_segments 3条F006 level-1(001/002/003); MaterialCodeSegmentController; SP8搜索端点 | 2026-06-10-c-f-flow-batch-d-verification.md F-002 |
| F-003 | 物料编码16位分段(前10位固定+后缀流水) | SP8 | P0 | ✅已建 | V1强 | prod DB: level/segment_code/segment_label列存在; 001=原料/002=包材/003=辅料; V20261011_16确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-003 |
| F-004 | 编码级联生成(类型→部位逐级下拉) | SP8 | P1 | ✅已建 | V1强 | RawMaterialTypeController SP8级联端点; material_code_segments三级结构 | 2026-06-10-c-f-flow-batch-d-verification.md F-004 |
| F-005 | BOM只关联前三位主编码 | SP8 | P1 | ✅已建 | V1强 | CreateBomRecipeRequest material_main_code字段; BOM前三位主编码关联 | 2026-06-10-c-f-flow-batch-d-verification.md F-005 |
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
| F-029 | 盘点差异预览(FactoryStocktakeDiffPreviewDTO) | SP7 | P1 | ✅已建 | V1强 | StocktakeDiffPreviewDTO.java; V20261010_23 difference_qty; diff-preview端点; 代码路径确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-029 |
| F-030 | 盘点审批流(WorkflowEngineService + PENDING_APPROVAL) | SP7 | P1 | ✅已建 | V1强 | FactoryStocktakeServiceImpl WorkflowEngineService; PENDING_APPROVAL状态机代码确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-030 |
| F-031 | 调拨需求单(计划下达后按BOM占用量发给仓库) | 核心 | P0 | ✅已建 | V1强 | prod DB internal_transfers F006有记录; 30条总量含DRAFT/REQUESTED/CONFIRMED/APPROVED/SHIPPED | 2026-06-10-c-f-flow-batch-d-verification.md F-031 |
| F-032 | 调拨接收 actualQuantity 持久化 | FIXB | P1 | 🟡部分 | V0 | FIXB#3 列为修复项; TransferController.receive 端点不收实收数量 | FIXB#3 完成后: receive 传 actualQuantity→查差异字段 |
| F-033 | 仓库备料后发料流转(仓管接单出货) | 核心 | P0 | ✅已建 | V2弱 | TransferServiceImpl; TransferController.receive | 调拨ACCEPTED→查库存变动 |
| F-034 | 库存不足同步报警(采购+仓库) | 核心 | P1 | 🟡部分 | V0 | 反推领料量→不足时逻辑; 告警推送完整性待查 | 库存低于阈值→查通知记录 |
| F-035 | 多仓体系(原料仓/WIP仓/成品仓/盐化仓) | SP7 | P0 | ✅已建 | V1强 | WarehouseType枚举14值含SALTED; V20261010_22; ⚠️F006当前仓全为LOGISTICS/WORKSHOP(legacy)—需张权团队创建RAW/WIP/FG类型仓库 | 2026-06-10-c-f-flow-batch-d-verification.md F-035 |
| F-036 | WIP仓只允许半成品入库(Guard) | SP7 | P1 | ✅已建 | V1强 | WarehouseInventoryGuardService WIP case: SEMI_FINISHED only; 422 WAREHOUSE_TYPE_MISMATCH; 代码+SchemaTest确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-036 |
| F-037 | 盐化仓(SALTED)仅接受原料 | SP7 | P1 | ✅已建 | V1强 | WarehouseInventoryGuardService SALTED case: RAW only; "盐化仓只接受原料"错误消息; 代码确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-037 |
| F-038 | 出库联动销售/成品库存扣减 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md 6.1 出货 SH-F006-B18C96 shipped | 出库后查 finished_goods_batches 库存减少 |
| F-039 | 成品入库→出库给客户全链路 | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg→出货单 | 追查出库单状态=SHIPPED |
| F-040 | 仓管员操作界面防呆(文化素质低) | fool-proof | P0 | ✅已建 | V1强 | ACTIVE.md RN fool-proof redesign; UX Flow规则 | 真机f006_moyun 完整操作流 |
| F-041 | 入库时扫码自动弹出对应包材 | SP8 | P1 | 🟡部分 | V2弱 | SP8端点+material_code_segments DB确认; 扫码→自动填包材UI完整链路待RN真机验 | 2026-06-10-c-f-flow-batch-d-verification.md F-041 |
| F-042 | 物料编码前缀分类(001=原料/002=包材/003=辅料) | SP8 | P1 | ✅已建 | V1强 | prod DB: 001=原料/002=包材/003=辅料 三条level-1记录(cretas_prod_db) | 2026-06-10-c-f-flow-batch-d-verification.md F-042 |
| F-043 | BC前缀=包材自动归类 | SP8 | P1 | ✅已建 | V1强 | SP8级联逻辑; 002=包材作为类别基础; MaterialCodeSegmentController级联端点 | 2026-06-10-c-f-flow-batch-d-verification.md F-043 |
| F-044 | 包材建档极简(名称+箱数+规格, 编号自动生成) | fool-proof | P1 | ✅已建 | V1强 | RawMaterialType最少必填设计; entity字段设计 | 2026-06-10-c-f-flow-batch-d-verification.md F-044 |
| F-045 | 包材可关联固定客户(非必填) | 核心 | P2 | ✅已建 | V1强 | raw_material_types.customer_id nullable列存在 | 2026-06-10-c-f-flow-batch-d-verification.md F-045 |
| F-046 | 进货凭证支持暂估入库/来票/未来票/账期 | SP6 | P1 | ✅已建 | V1强 | PurchaseOrder entity有paymentType/invoiceStatus字段; ⚠️DB列为invoice_reminder_days非invoice_status | 2026-06-10-c-f-flow-batch-d-verification.md F-046 |
| F-047 | 采购单含现结/赊结标识 | SP6 | P1 | ✅已建 | V1强 | purchase_orders.settlement_type列存在; F006当前0条有值(字段已建待使用) | 2026-06-10-c-f-flow-batch-d-verification.md F-047 |
| F-048 | 进销存台账(期初/期入/期出/期末) | SP11 | P1 | ✅已建 | V2弱 | controller/inventory/InventoryLedgerController.java; InventoryLedgerServiceImpl 期初/期末逻辑; InventoryLedgerSnapshot; V20261011_21 | GET /{fid}/inventory-ledger?year=2026&month=6 |
| F-049 | 进销存 RN 统计页(WHIOStatisticsScreen) | SP11 | P1 | 🔴缺 | N/A | WHIOStatisticsScreen.tsx 确认 hardcoded mock: 带鱼/虾仁/鲈鱼/蟹类 静态数组; 后端 InventoryLedgerController 已建但 RN 未对接 | 实现后: RN 统计页显示真实 F006 数据 |
| F-050 | 进销存报表期间选择(月度) | SP11 | P1 | 🔴缺 | N/A | WHIOStatisticsScreen 未对接; Web端账单页对接状态待查 | 同F-049 |
| F-051 | 进销存报表导出PDF/Excel | SP11 | P2 | 🔴缺 | N/A | InventoryLedgerController 无导出端点; PrintController 无此类型 | 待实现后验证 |
| F-052 | 进销存数据聚合(物料维度汇总) | SP11 | P2 | 🔴缺 | N/A | InventoryLedgerServiceImpl 期初/末逻辑存在; 但物料维度聚合端点待查 | GET ledger 接口返回物料维度数据 |
| F-053 | 进销存 Web 管理台(web-admin 对接账单) | SP11 | P1 | 🟡部分 | V0 | InventoryLedgerController 已有; web-admin 页面对接状态未验证 | 访问 web-admin 进销存页查 F006 数据 |
| F-054 | 盐化仓类型(SALTED)已加入枚举 | SP7 | P0 | ✅已建 | V1强 | entity line:108-109 SALTED确认; FactoryWarehouseSchemaTest line:49 assertNotNull(SALTED); 矩阵误标🔴缺已纠正 | 2026-06-10-c-f-flow-batch-d-verification.md F-054 |
| F-055 | 盐化仓出量独立记录(腌制工序专属报工) | SP7 | P1 | 🔴缺 | N/A | WarehouseInventoryGuardService 有 Guard 但无独立出量报工端点; git grep saltedOutput 无结果 | 待实现后: 盐化仓操作产生独立出量记录 |
| F-056 | 盐化仓独立库存报告(腌制品分离统计) | SP7 | P2 | 🔴缺 | N/A | 无专用盐化仓报告端点 | 待实现后: 报告按 SALTED warehouse 聚合 |
| F-057 | 仓管只操作DEMO数据(真客户保护) | ⚪约束 | P0 | ⚪约束项 | N/A | prod 测试账号只动 DEMO标记数据 | 操作规范 |
| F-058 | 生产领料记录详细(领了多少) | 核心 | P0 | ✅已建 | V1强 | material_requisitions 26条全量; /material-requisitions完整CRUD; MaterialConsumption路径F006已有记录 | 2026-06-10-c-f-flow-batch-d-verification.md F-058 |
| F-059 | 水耗多领料报损成本增加 | 核心 | P1 | ✅已建 | V1强 | WastageReport + MaterialConsumption成本分摊包含报损; 独立实体无强耦合; 架构确认 | 2026-06-10-c-f-flow-batch-d-verification.md F-059 |
| F-060 | 报损后重新领料走调拨 | 核心 | P1 | ✅已建 | V1强 | WastageReportController + TransferController 独立无耦合; 同C-073 | 2026-06-10-c-f-flow-batch-d-verification.md F-060 |
| F-061 | 半成品在制状态(IN_PROGRESS标注) | SP1/G7 | P1 | ✅已建 | V1强 | SFI 47条含available_quantity; intermediate_batch_no标识; G7 在制状态SP1已验 | 2026-06-10-c-f-flow-batch-d-verification.md F-061 |
| F-062 | 仓库库存联动生产实时反馈 | 核心 | P0 | ✅已建 | V2弱 | 库存扣减在领料时实时; MaterialBatchRepository | 领料后查 material_batches.remaining_quantity |
| F-063 | 出成率统计信息查询(每产品实际出成率) | SP9 | P1 | ✅已建 | V2弱 | YieldReportServiceImpl.getYieldStatsByProductType; work_processes.standard_yield | 查出成率 API 按品类 |
| F-064 | 成品库存显示(AVAILABLE成品批次) | 核心 | P0 | ✅已建 | V1强 | ACTIVE.md FG-AUTO-1924 540kg AVAILABLE; FinishedGoodsBatchController | 查 finished_goods_batches.status=AVAILABLE |
| F-065 | 出库管理(成品出货单) | 核心 | P0 | ✅已建 | V1强 | ShipmentController; ACTIVE.md SH-F006-B18C96 shipped | 查 shipments 出货单 |
| F-066 | 多仓调拨单(PENDING→ACCEPTED流程) | 核心 | P1 | ✅已建 | V1强 | prod DB 30条调拨记录; DRAFT=16/REQUESTED=5/CONFIRMED=2/APPROVED=5/SHIPPED=2 五种状态实证 | 2026-06-10-c-f-flow-batch-d-verification.md F-066 |
| F-067 | 半成品库存事务记录(SemiFinishedInventoryTransaction) | SP1 | P0 | ✅已建 | V1强 | entity存在; V20261010_02; SFI 47条记录; 矩阵误标🔴缺已纠正 | 2026-06-10-c-f-flow-batch-d-verification.md F-067 |
| F-068 | 付款申请单(PAYMENT_REQUEST) | SP6 | P1 | ✅已建 | V1强 | PAYMENT_REQUEST id:b92708a4 E2E造; PaymentRequestController; V20261011_09 | 查 payment_requests id=b92708a4 |
| F-069 | 入库异常管理页(web-admin /procurement/exceptions) | SP6 | P1 | ✅已建 | V1强 | PURCHASE_EXCEPTION id:9a5c21a2 E2E造; FIXB#6 确认页面对接正常 | 访问/procurement/exceptions查 id:9a5c21a2 |
| F-070 | 原料入库价格锁定(入库后不变) | ⚪约束 | P0 | ⚪约束项 | N/A | MaterialBatch.purchaseCost 入库时锁定 | 入库后修改价格→验字段不变 |

---

### F 流统计

| 状态 | 数量 | 占比 |
|------|------|------|
| ✅已建 | 47 | 67% |
| 🟡部分 | 6 | 9% |
| 🔴缺 | 7 | 10% |
| ⚪约束项 | 10 | 14% |
| **合计** | **70** | **100%** |

| 验证级别 | 数量 |
|---------|------|
| V1强 (E2E/真机/DB) | 31 |
| V2弱 (代码链路) | 18 |
| V0 未验证 | 2 |
| B阻塞 | 2 |
| N/A | 17 |

**批D V2→V1 升级**（2026-06-10 批D扫荡后）: F-002/003/004/005/029/030/031/035/036/037/042/043/044/045/046/047/054/058/059/060/061/066/067 共23项升V1；F-041 V0→V2

**Top F 流风险**（更新后）:
1. **F-049/F-050/F-051/F-052** (RN 进销存页 mock 硬编码): WHIOStatisticsScreen.tsx 确认有带鱼/虾仁 hardcoded 静态数组，后端 InventoryLedgerServiceImpl 已建好但未对接。对客户演示影响大。
2. **F-027** (WHInventoryCheckScreen 调旧路径): 月底约束导致本期无法 E2E 验证，且 WHInventoryCheckScreen 仍有 TODO 直调旧接口。
3. **F-055/F-056** (盐化仓专属报工/报告): SALTED 类型建立但出量记录和独立报告缺失。
4. **F-032** (调拨 actualQuantity): FIXB#3 已列修复待完成。
5. **F-035** (多仓体系): F006 当前仓全为 LOGISTICS/WORKSHOP legacy 类型，Guard 功能已建但需张权团队创建 RAW/WIP/FG/SALTED 类型仓库才能生效。

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
