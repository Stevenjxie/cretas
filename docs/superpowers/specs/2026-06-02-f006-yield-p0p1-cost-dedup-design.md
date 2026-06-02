# F006 报工/出成率 P0+P1 修复设计 (成本/去重/工时/WIP选择/今日工序/分订单/收货明细)

> 来源: 二次核对 `docs/audits/2026-06-02-f006-second-pass-coverage-verification.md` 的 P0+P1 缺口
> Steve 决策: 成本口径=人工+材料(领料折价); 工价源=WorkProcess.standardHourlyRate(工序配,未配诚实null); WIP成本=accumulatedCost/unitCost滚动累加; 范围=P0+P1 八项
> 日期: 2026-06-02 · worktree `cretas-p0p1` (off origin/main b506afedb) · 分支 feat/f006-yield-p0p1-cost-dedup
> 迁移号: V20260909_* 起 (最新已占 V20260908_02; PR 前必 fetch 复核防撞车)

---

## 总览: 8 项修复 → 7 个实现单元

| 单元 | 缺口 | 级别 | 后端 | 前端 |
|---|---|---|---|---|
| A 成本 | #2 逐道成本 + #3 人工成本 | P0 | WorkProcess工价/WIP成本/Report成本字段+算法+DTO+计算 | RN+web-admin 成本展示 |
| B 去重 | #1 累加式被全天去重封死 | P0 | WorkReportingServiceImpl 改 5min 窗口 | — |
| C 工时SUM | #4 多区间工时不累计 | P1 | EmployeeProcessSegment SUM 聚合查询+端点 | — |
| D WIP选择 | #5 多笔WIP无选择器 | P1 | (listWip 已存在) | RN listWip 选择器 |
| E 今日工序 | #6 今日工序无日期过滤 | P1 | ProcessCheckinController 日期过滤 | — |
| F 分订单 | #7 分订单下钻缺失 | P1 | by-order yield 聚合端点 | web-admin 按订单出成率 |
| G 收货明细 | #8 收货分次时序不准 | P1 | per-receive 明细端点 | web-admin 接 /cumulative-received + 时序列 |

不破坏已上线: 全部新增字段 nullable; 成本=null 时诚实显示"未配工价"不缩 0; 旧路径报工去重放宽不影响逐道路径(逐道本无去重)。

---

## 单元 A — 逐道成本 + 人工成本 (P0 #2/#3, 最大单元)

### A 设计原理 (write-time 计算, 持久化, 点时成本)

成本在 `submitReport()` 写入时计算并持久化到 `ProductionReport`,WIP 成本滚动累加到 `SemiFinishedInventory`。点时成本: 工价/单价后续变化不回改历史 (会计正确)。

**逐道成本 = 人工成本 + 材料成本:**
- **人工成本** = `workerCount × (workMinutes / 60) × workProcess.standardHourlyRate`
  - 任一项 null (workerCount/workMinutes/standardHourlyRate) → laborCost = **null** (诚实, 不缩 0)
- **材料成本** = Σ 该道领用:
  - 原料领用 (materialBatchRefs → MaterialBatch.unitPrice): `Σ refQty × MaterialBatch.unitPrice`
  - 半成品领用 (sourceWipNo → SemiFinishedInventory.unitCost): `consumedQty × WIP.unitCost`
  - 全部领用项均无价 → materialCost = null (诚实)

**WIP 成本滚动:**
- 道N **产出** WIP (submitReport upsert WIP): `WIP.accumulatedCost += (本道 materialCost + 本道 laborCost)`; `WIP.unitCost = accumulatedCost / producedQuantity` (producedQuantity>0 时, 跨天累加自然成立)
- 道N+1 **领用** 该 WIP: 本道 materialCost 取 `consumedQty × WIP.unitCost` (上一步已算好的滚动成本)
- 首道无 WIP 领用, 材料成本来自原料批次

### A.1 WorkProcess.standardHourlyRate 字段 + 配置

**实体** `entity/WorkProcess.java` (在 outputUnit 后, line 68 附近):
```java
@Column(name = "standard_hourly_rate", precision = 8, scale = 2)
private BigDecimal standardHourlyRate;   // 该工序标准时薪(元/小时), null=未配
```
**迁移** `db/flyway/V20260909_01__work_process_standard_hourly_rate.sql` (镜像 V20260908_02 的 to_regclass + ADD COLUMN IF NOT EXISTS 守卫):
```sql
DO $$ BEGIN
  IF to_regclass('public.work_processes') IS NOT NULL THEN
    ALTER TABLE work_processes ADD COLUMN IF NOT EXISTS standard_hourly_rate NUMERIC(8,2);
  END IF;
END $$;
```
(注: 表名以实际为准, 实现时 grep `@Table` 确认 work_processes)
**DTO** `dto/.../WorkProcessDTO.java` 加 `standardHourlyRate` 字段 + service 映射 (跟 standardYieldMin/Max 同模式)。
**web-admin** `views/.../work-processes/index.vue`: 工序表单加"标准时薪(元/小时)"输入 (跟出成率上下限同区), 选填。**同时修 #12**: 现有两个"产出单位"标签 → 第一个 (line 264 绑 unit) 改"计量单位/主单位"。

### A.2 SemiFinishedInventory 成本字段

**实体** `entity/SemiFinishedInventory.java` 加:
```java
@Column(name = "accumulated_cost", precision = 14, scale = 2)
private BigDecimal accumulatedCost;   // 滚动累计成本; null=无成本数据
@Column(name = "unit_cost", precision = 14, scale = 4)
private BigDecimal unitCost;          // = accumulatedCost / producedQuantity
```
**迁移** `V20260909_02__semi_finished_inventory_cost.sql` (to_regclass semi_finished_inventory + 两列 ADD IF NOT EXISTS)。

### A.3 ProductionReport 成本字段

**实体** `entity/ProductionReport.java` 加:
```java
@Column(name = "labor_cost", precision = 14, scale = 2)
private BigDecimal laborCost;
@Column(name = "material_cost", precision = 14, scale = 2)
private BigDecimal materialCost;
```
**迁移** `V20260909_03__production_report_cost.sql` (to_regclass production_reports + 两列)。

### A.4 成本计算 (YieldReportServiceImpl.submitReport)

在 submitReport 构建 ProductionReport 前算成本, 注入 builder; WIP upsert 时滚动 accumulatedCost/unitCost。
- 注入依赖: WorkProcessRepository (取 standardHourlyRate), MaterialBatchRepository (已注入, 取 unitPrice), SemiFinishedInventoryRepository (已有, 取 WIP unitCost)。
- 新建私有方法 `computeLaborCost(workProcessTask, workerCount, workMinutes)` 和 `computeMaterialCost(factoryId, materialBatchRefs, sourceWipNo, consumedQty)`。
- WIP 产出 upsert (现有 G6 逻辑) 处加: `accumulatedCost = nullSafeAdd(existing.accumulatedCost, laborCost, materialCost)`; `unitCost = producedQuantity>0 ? accumulatedCost/producedQuantity : null` (BigDecimal divide scale 4 HALF_UP)。
- WIP 领用 (consumeSourceWip, 现有) 处: materialCost 的 WIP 部分 = `consumedQty × sourceWip.unitCost` (null-safe)。
- **null 传播**: 任一输入缺失对应成本项为 null, 不写 0 (per python-java rule 风格的诚实 null)。

### A.5 DTO 成本字段 + 聚合

**StepYieldDTO** 加 `laborCost`/`materialCost`/`stepCost` (BigDecimal)。
**BatchYieldDTO** 加 `totalLaborCost`/`totalMaterialCost`/`totalCost`。
**YieldCalculationServiceImpl.calculateSteps**: 每 task 组 SUM laborCost+materialCost (null-safe, 全 null 则该项 null); stepCost = laborCost+materialCost。批次 totals 同。

### A.6 成本展示 (RN + web-admin)

**RN** `YieldStepReportScreen.tsx`: 提交成功后/批次汇总区显示本道"人工¥X + 材料¥Y = ¥Z"; null 显示"未配工价/无成本数据"。
**web-admin** `batches/detail.vue`: 逐道表格加 人工成本/材料成本/小计 列; KPI 区加 总成本; null 显"—"不显 0。

### A 验收
- 道1 投入100原料(单价¥10)+3人×60min×¥20工价 → 材料¥1000 + 人工¥60 = ¥1060; WIP accumulatedCost=1060 unitCost=10.6
- 道2 领用80 WIP(¥10.6)+2人×30min×¥18 → 材料¥848 + 人工¥18 = ¥866
- 未配工价 → laborCost=null, web-admin 显"—"

---

## 单元 B — 旧路径去重放宽 5min 窗口 (P0 #1)

`service/impl/WorkReportingServiceImpl.java:68-82`: 全天去重 → 5 分钟窗口 (只防 double-click, 不防累加报工)。
- 新 repo 方法 `ProductionReportRepository`:
  `existsByFactoryIdAndWorkerIdAndBatchIdAndReportTypeAndCreatedAtAfterAndDeletedAtIsNull(String, Long, Long, String, LocalDateTime)` + batchId-null 版。
- submitReport: `LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);` 用 createdAt-after 查; duplicate → 409 "5分钟内已对该批次提交过报工, 请勿重复点击 (如需累加请稍后再报)"。
- 消息改为防重复点击语义, 不再是"今天已提交"。
- 验收: 同批次同工序同工人, 间隔>5min 两次提交都成功 (累加); <5min 第二次 409。

---

## 单元 C — 多区间工时 SUM 聚合 (P1 #4)

`EmployeeProcessSegment` 多段签到签退工时累加 (REQ-14 "所有上班下班时间")。
- `EmployeeProcessSegmentRepository` 加聚合查询:
  ```java
  @Query("SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (s.endAt - s.startAt))/60), 0) " +
         "FROM EmployeeProcessSegment s WHERE s.factoryId=:factoryId AND s.employeeId=:employeeId " +
         "AND s.processId=:processId AND s.endAt IS NOT NULL AND s.deletedAt IS NULL")
  Double sumMinutesByEmployeeAndProcess(...);
  ```
  (Hibernate 6 EXTRACT EPOCH on interval; 若 JPQL 不支持改 native query)
- `EmployeeProcessSegmentService` 加 `getTotalMinutes(factoryId, employeeId, processId)` + Controller `GET /segments/total-minutes`。
- 验收: 同员工同工序两段 (60min + 30min) → total=90。

---

## 单元 D — 多笔 WIP 选择器 RN (P1 #5)

`getLimits().sourceWipNo == null && wipAvailable > 0` (多笔 AVAILABLE WIP 歧义) 时:
- RN `YieldStepReportScreen.tsx`: 调 `yieldReportApi.listWip(batchId)` (api 已存在), 渲染 WIP 选择列表 (intermediateBatchNo + processName + availableQuantity + unit), 选中→ submit 的 sourceWipNo=选中 intermediateBatchNo。
- 单笔 (sourceWipNo 非 null) 保持现有自动回填, 不显选择器。
- 验收: 上道跨天 2 笔 WIP → 显示 2 项可选; 选中后提交带正确 sourceWipNo, 后端扣对应 WIP。

---

## 单元 E — 今日工序日期过滤 (P1 #6)

`controller/ProcessCheckinController.java:204-221 getAvailableProcesses`: 加 plannedDate=today 过滤。
- 流过滤加: `(p.getPlannedDate() == null || p.getPlannedDate().isEqual(LocalDate.now()))` (保留无日期的兼容), 或加 repo 方法 `findByFactoryIdAndPlannedDate`。优先 repo 查询 (性能)。
- 验收: 多天计划只返回今日工序。

---

## 单元 F — 分订单出成率聚合 (P1 #7)

订单链: `ProductionPlan.source_order_id = orderId` → 多 plan → `ProductionBatch.productionPlanId IN(plan ids)` → 多 batch → 聚合各 batch yield。
- 新端点 `YieldReportController`: `GET /api/mobile/{factoryId}/production/orders/{orderId}/yield-summary` → `OrderYieldSummaryDTO { orderId, batches:[BatchYieldDTO], totalFirstInput, totalLastOutput, overallYieldRate, totalCost }`。
- service: 查 plan by source_order_id → batch by productionPlanId → 逐 batch getYield → 汇总 (跨 batch 单位可比才算总出成率, 否则诚实 null)。
- web-admin: 订单详情或批次列表加"按订单出成率"区/入口 (复用 batches/detail.vue 的 yield 展示组件)。
- 验收: 一订单下 2 批次 → 返回 2 batch + 汇总投入产出。

---

## 单元 G — 收货分次时序明细 (P1 #8)

- **修跨页累计**: web-admin `receives/list.vue` cumulativeForRow() 页内聚合 → 改调后端 `GET .../purchase/orders/{orderId}/cumulative-received` (已存在)。新增 api client 方法。
- **时序明细**: 新端点 `GET .../purchase/orders/{orderId}/receives` 返回该订单每次收货事件 (createdAt + items + qty), 按时间排序。web-admin 展开行/弹窗显示"第1次 X (日期), 第2次 Y (日期)"。
- 数据源: `PurchaseReceiveRecord` 实体 (已有 createdAt)。
- 验收: 同订单收 2 次 → 明细按时序列出两条; 累计跨页准确。

---

## 实现顺序 (依赖)

1. 单元 A (成本, 最大, 含迁移 V20260909_01/02/03) — A.1→A.2→A.3→A.4→A.5→A.6
2. 单元 B (去重, 独立小)
3. 单元 E (今日工序, 独立小)
4. 单元 C (工时SUM, 独立)
5. 单元 D (WIP选择器, 依赖 listWip 已存在)
6. 单元 F (分订单, 独立中)
7. 单元 G (收货明细, 独立中)

每单元后端 TDD + 单测; RN/web-admin 编译通过 (tsc/vue-tsc)。全部 merge main 后从 main 部署 prod + headed E2E 验证。

## 不做 (本轮外, 记录)
- #9 报表导入 / #10 达成率口径改数量 / #11 逐道审批 / #13 plans allow-create / #14 采购防呆三缺口 / #15 线边仓EOD清零 / #16 WIP退回调拨 — 下一轮。
