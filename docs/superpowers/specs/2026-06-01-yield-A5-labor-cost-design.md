# 出成率报工 A5 — 人工成本按批次 + 计件 — 设计

**日期**: 2026-06-01
**状态**: 已审计定稿, Phase B — 可进入实施
**前置**: 报工体系统一 Phase A (PR #350/#354/#358 merged main), Phase D (PR #360 merged main)
**关联**: `ProductionReport.workProcessTaskId`, `WagePolicy`, `PieceRateRule`, `WageCalculationService`, `ProductionBatch.laborCost`

> **审计状态**: 草稿经对抗性代码审计 (2026-06-01), 发现 5 个 finding (F001–F005). 全部已纳入本定稿.
> Steve 产品决策: **计件单位 = kg 和件数 BOTH 支持** (BigDecimal 模式, rateUnit 维度).

---

## 目标

将 YIELD 报工中已采集的 **工人 (`workerId`) + 产出量 + 工时 (`totalWorkMinutes`)** 与现有工资基础设施打通, 计算每条批次 (`ProductionBatch`) 的人工成本, 写入 `ProductionBatch.laborCost`, 进而使总成本 (`totalCost`) / 单位成本 (`unitCost`) 准确反映实际人工投入.

---

## 现有基础设施 (已存在, A5 复用不重造)

代码审计确认如下组件**全部已存在**:

### 1. 工资基础层 (Sprint 5/6, 完整实现)

| 实体/服务 | 文件 | 现状 |
|---|---|---|
| `WageMode` 枚举 | `entity/enums/WageMode.java` | `PIECE_RATE / HOURLY / MIXED` 三种模式 — 确认存在 |
| `WagePolicy` | `entity/WagePolicy.java` | 员工级或工厂默认级 mode 配置 — 确认存在 |
| `PieceRateRule` | `entity/PieceRateRule.java` | 按 `processStageType` + `productTypeId` + 日期匹配; 3 阶梯计件; **当前 `calculateWage(int)` 只支持整数** — **F001: 需扩展 BigDecimal** |
| `HourlyRateRule` | `entity/HourlyRateRule.java` | 时薪 + 加班倍率 — 确认存在 |
| `WageCalculation` | `entity/WageCalculation.java` | 月度工资计算结果 — 确认存在 |
| `WageCalculationService` | `service/WageCalculationService.java` | 完整实现; `findApplicableRule` 是 private 方法, A5 需提升可见性或在 `YieldLaborCostService` 内复用查询 |
| `WageRecordTriggerService` | `service/WageRecordTriggerService.java` | Sprint 5 trigger — 确认存在 |

### 2. 批次人工成本字段 (已存在, 但当前写入路径不适配 YIELD)

`ProductionBatch.laborCost` (`NUMERIC(12,2)`, `@PriceSensitive`) 已存在.

**当前写入路径** (`ProcessingServiceImpl:1452-1459`):
```java
// 重新计算人工成本 - 使用 BatchWorkSession
List<BatchWorkSession> workSessions = batchWorkSessionRepository.findByBatchId(batchIdLong);
BigDecimal laborCost = workSessions.stream()
        .map(s -> s.getLaborCost() != null ? s.getLaborCost() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
if (laborCost.compareTo(BigDecimal.ZERO) > 0) {
    batch.setLaborCost(laborCost);   // 已有 >0 guard
}
```

该路径依赖 **`BatchWorkSession`** (工人签到/签退 + 工时 + 成本), 与 YIELD 报工体系是**独立**的两条路径. 已有 `> 0` guard, 但 F003 说明 YIELD 路径仍需明确冲突守卫.

### 3. `ProductionReport` 关键字段 (代码已验证)

| 字段 | 类型 | 说明 |
|---|---|---|
| `workerId` | Long | 报工工人 ID |
| `workProcessTaskId` | Long | 关联 WorkProcessTask (F002 mapping bridge) |
| `outputQuantity` | NUMERIC(12,2) | 本道产出量 (kg 或 件数, BigDecimal) |
| `totalWorkMinutes` | Integer | 工时 (分钟, 选填) |
| `productTypeId` | varchar(100) | 产品类型 |
| `batchId` | Long | 批次 ID |

**注意**: `ProductionReport.processCategory` (free-text, length 200) **不应直接用于 PieceRateRule 匹配** — 见 F002.

---

## F002 关键设计: processCategory ↔ processStageType 映射

### 问题根因 (已代码验证)

- `ProductionReport.processCategory` = 工人自由录入文本 (长度 200, 如 "卤蛋烤"), 不是标准化代码
- `PieceRateRule.processStageType` = 标准化工序代码 (长度 50, 如 "ROASTING")
- 草稿假设两者可直接比较 → `findApplicableRule(factoryId, report.processCategory, ...)` 几乎永远返回 empty → 计件成本 = 0

### 正确的 Mapping 路径 (代码审计确认)

```
ProductionReport.workProcessTaskId (Long)
  → WorkProcessTask (entity/workprocess/WorkProcessTask.java)
    → WorkProcessTask.workProcessId (String, FK → work_processes.id)
      → WorkProcess (entity/WorkProcess.java)
        → WorkProcess.processCategory (varchar 50) ← 这是标准化的工序分类
```

**`WorkProcess.processCategory`** (length 50) 与 `PieceRateRule.processStageType` (length 50) 是同语义同长度字段 — **这就是正确的匹配 key**:

```java
// YieldLaborCostService 的正确写法:
WorkProcess wp = workProcessRepo.findById(task.getWorkProcessId()).orElse(null);
String stageType = wp != null ? wp.getProcessCategory() : null;
Optional<PieceRateRule> rule = wageCalcSvc.findApplicableRule(factoryId, stageType, productTypeId, date);
```

### 配置前提

管理员在创建 `WorkProcess` 时, `processCategory` 必须填写与 `PieceRateRule.processStageType` 一致的值 (如 "ROASTING" / "MARINATING" 等). **这是运营约定, 不是代码约束**. Phase B 实施时:
1. 文档/配置说明中明确此约定
2. `YieldLaborCostService` 当 `stageType` 为 null 或未找到规则时, 记录 warn 日志 + 该工序人工成本记为 0 (不静默, 不报错中断)
3. **不新建 mapping 表** (过度设计) — 直接复用 `WorkProcess.processCategory` ≡ `processStageType`

### 阶梯 mapping 兼容 (F002 延伸)

`WageCalculationService.findApplicableRule` 当前是 `private`. A5 有两种复用方式:
- 方案 A: 在 `WageCalculationService` 中新增 `public` 方法 `findApplicableRulePublic(...)` 暴露给 `YieldLaborCostService` 注入使用
- 方案 B: `YieldLaborCostService` 直接注入 `PieceRateRuleRepository`, 实现与 `WageCalculationService.findApplicableRule` 相同的查找逻辑

**推荐方案 A** — 避免重复查找逻辑, 代价是修改 WageCalculationService 一行 (`private` → `public`).

---

## A5 设计方案 (Phase B)

### 核心思路

A5 = 在**按需触发**时, 将该批次所有 YIELD `ProductionReport` 按工人 + 工序聚合, 对每个工人按其 `WagePolicy.mode` 查匹配的 `PieceRateRule` / `HourlyRateRule`, 计算人工成本分项, 求和写入 `ProductionBatch.laborCost`.

```
YIELD ProductionReport (工人 + 产出 + 工时) per batch
  → 按 workerId + workProcessTaskId 分组
  → task.workProcessId → WorkProcess.processCategory (= PieceRateRule 查找 key)
  → 查 WagePolicy.mode (per employee)
  → PIECE_RATE: Σ outputQuantity × PieceRateRule (processCategory + productTypeId)
               PieceRateRule.rateUnit = KG → qty 直接用 (BigDecimal)
               PieceRateRule.rateUnit = PIECE → qty 直接用 (BigDecimal, 整数值 BigDecimal 也可)
  → HOURLY: Σ workMinutes / 60 × HourlyRateRule.baseHourlyRate (+ OT) [Phase B+]
  → MIXED: PIECE_RATE + HOURLY [Phase B+]
  → Σ 所有工人成本 → ProductionBatch.laborCost
  → batch.calculateMetrics() 重算 totalCost / unitCost
```

---

## Steve 产品决策: 计件单位 BOTH 支持 (kg + 件数)

Steve 已决策: **计件单位 = 重量(kg)和件数均支持**. 设计如下:

### PieceRateRule 实体变更 (F001)

**当前 (已代码验证)**:
- `tier1/2/3Threshold`: `Integer` (只支持整数件数)
- `calculateWage(int pieceCount)`: 只接受整数

**需变更**:

```java
// 新增字段
@Column(name = "rate_unit", length = 10, nullable = false)
@Builder.Default
private String rateUnit = "PIECE";   // "KG" | "PIECE"

// 阶梯阈值: Integer → BigDecimal (支持 kg 小数, 件数仍用整数值 BigDecimal)
@Column(name = "tier1_threshold", precision = 14, scale = 3)
private BigDecimal tier1Threshold;   // 原 Integer, 原值迁移无损

@Column(name = "tier2_threshold", precision = 14, scale = 3)
private BigDecimal tier2Threshold;

@Column(name = "tier3_threshold", precision = 14, scale = 3)
private BigDecimal tier3Threshold;
```

**新增 `calculateWage(BigDecimal qty)` 重载**:

```java
public BigDecimal calculateWage(BigDecimal qty) {
    if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
        return BigDecimal.ZERO;
    }

    BigDecimal totalWage = BigDecimal.ZERO;
    BigDecimal remaining = qty;

    // 第一阶梯
    if (tier1Rate != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal tier1Limit = (tier1Threshold != null && tier1Threshold.compareTo(BigDecimal.ZERO) > 0)
                ? tier1Threshold : null;
        BigDecimal tier1Qty = tier1Limit != null ? remaining.min(tier1Limit) : remaining;
        totalWage = totalWage.add(tier1Rate.multiply(tier1Qty));
        remaining = remaining.subtract(tier1Qty);
    }

    // 第二阶梯
    if (tier2Rate != null && remaining.compareTo(BigDecimal.ZERO) > 0 && tier2Threshold != null) {
        BigDecimal tier2Limit = tier2Threshold.subtract(
                tier1Threshold != null ? tier1Threshold : BigDecimal.ZERO);
        BigDecimal tier2Qty = remaining.min(tier2Limit.max(BigDecimal.ZERO));
        totalWage = totalWage.add(tier2Rate.multiply(tier2Qty));
        remaining = remaining.subtract(tier2Qty);
    }

    // 第三阶梯 (超出部分)
    if (tier3Rate != null && remaining.compareTo(BigDecimal.ZERO) > 0) {
        totalWage = totalWage.add(tier3Rate.multiply(remaining));
    }

    return totalWage;
}

// 向后兼容: 保留原 int 重载, 内部委托给 BigDecimal 版本
public BigDecimal calculateWage(int pieceCount) {
    return calculateWage(BigDecimal.valueOf(pieceCount));
}
```

**`getRateForPieceCount(int)` 同理**: 新增 `getRateForQuantity(BigDecimal qty)` 重载, 保留旧 int 版本委托 BigDecimal.

### rateUnit 语义

| `rateUnit` | 入参 qty 来源 | tier 阈值单位 | 示例 |
|---|---|---|---|
| `KG` | `ProductionReport.outputQuantity` 直接用 (BigDecimal) | kg | 卤猪舌每 kg 0.5 元 |
| `PIECE` | `ProductionReport.outputQuantity` 直接用 (BigDecimal, 整数值) | 件/盒/包 | 末道装盒每盒 0.1 元 |

**注**: 两种模式下 qty 来源一致 (`outputQuantity`), 区别在于配置规则时 `rateUnit` 的语义声明. 工厂管理员配置规则时指定是按 kg 还是按件, `YieldLaborCostService` 不做单位转换 — qty 如实传入, 意义由规则配置决定.

---

## F003: BatchWorkSession 冲突守卫

### 问题 (已代码验证)

`ProcessingServiceImpl:1452-1459` 的 BatchWorkSession 路径已有 `> 0` guard, 但 YIELD 路径会用 `YieldLaborCostService.calculateBatchLaborCost` 直接写 `batch.setLaborCost(yieldCost)`. 若同批次同时存在两条路径:
- BatchWorkSession 路径: 写一个值
- YIELD 路径: 覆盖写另一个值

### 守卫设计 (单一来源规则)

`YieldLaborCostService.calculateBatchLaborCost` 入口增加冲突检测:

```java
public LaborCostResult calculateBatchLaborCost(String factoryId, Long batchId, boolean force) {
    // 检查是否有 BatchWorkSession 记录 (旧路径)
    List<BatchWorkSession> existingSessions = batchWorkSessionRepo.findByBatchId(batchId);
    boolean hasBatchWorkSession = existingSessions.stream()
            .anyMatch(s -> s.getLaborCost() != null &&
                           s.getLaborCost().compareTo(BigDecimal.ZERO) > 0);

    if (hasBatchWorkSession && !force) {
        log.warn("批次 {} 已有 BatchWorkSession 人工成本记录, 跳过 YIELD 计算. 如需强制覆盖请传 force=true",
                batchId);
        return LaborCostResult.skipped("已有 BatchWorkSession 记录, 请传 force=true 强制覆盖");
    }

    if (hasBatchWorkSession && force) {
        log.warn("批次 {} 强制用 YIELD 路径覆盖 BatchWorkSession 人工成本 (force=true)", batchId);
    }

    // ... 继续计算
}
```

**API 暴露**:
- `POST /{factoryId}/production/batches/{batchId}/recalculate-labor-cost` — 默认 `force=false`
- `POST /{factoryId}/production/batches/{batchId}/recalculate-labor-cost?force=true` — 强制覆盖

**单一来源规则**: 一个批次的 `laborCost` 只由一条路径写. 优先级: BatchWorkSession (已有数据) > YIELD (新路径), 除非 `force=true` 显式覆盖.

**`LaborCostResult` DTO** (新建, 简单):
```java
public class LaborCostResult {
    private String status;           // "CALCULATED" | "SKIPPED" | "PARTIAL"
    private String message;
    private BigDecimal totalLaborCost;
    private List<WorkerCostDetail> details;  // 每个工人的计算明细

    public static LaborCostResult skipped(String reason) { ... }
}
```

---

## 需新增/修改的文件

### Phase B MVP (PIECE_RATE + 按需触发 — 1.5–2.5 天)

| 类型 | 文件路径 | 改动 |
|---|---|---|
| **修改实体** | `entity/PieceRateRule.java` | `tier1/2/3Threshold` Integer→BigDecimal; 新增 `rateUnit` 字段; 新增 `calculateWage(BigDecimal)` 重载; 旧 `calculateWage(int)` 委托新重载 |
| **数据库迁移** | `V20260601_02__piece_rate_rule_bigdecimal.sql` (Java flyway 迁移, **不是** Python migrations 目录) | `ALTER TABLE piece_rate_rules ALTER COLUMN tier1_threshold TYPE NUMERIC(14,3)` (同 tier2/3); `ADD COLUMN rate_unit VARCHAR(10) NOT NULL DEFAULT 'PIECE'` |
| **新建 Service** | `service/yield/YieldLaborCostService.java` | `calculateBatchLaborCost(factoryId, batchId, force)` 核心计算; F002 mapping path (task→wp→processCategory); F003 guard; 聚合 ProductionReport → PieceRateRule → 写 batch.laborCost |
| **新建 DTO** | `dto/yield/LaborCostResult.java` | 计算结果包装 (status/message/totalLaborCost/details) |
| **修改 Service** | `service/WageCalculationService.java` | `findApplicableRule` private → package-private 或 public (方案 A); 或不改, YieldLaborCostService 复用 Repository (方案 B) |
| **新建 Controller** | `controller/YieldLaborCostController.java` | `POST /{f}/production/batches/{id}/recalculate-labor-cost[?force=true]`; `@RequirePermission("production:read_write")` |
| **无改动** | `entity/WagePolicy.java`, `entity/HourlyRateRule.java`, `entity/ProductionBatch.java` | 直接复用 |

### Phase B 完整版 (含 HOURLY + 自动结清触发 — 额外 1–1.5 天)

| 类型 | 文件路径 | 改动 |
|---|---|---|
| 修改 YieldLaborCostService | 同上 | 支持 HOURLY 模式; `totalWorkMinutes` 来源 + 多批次工时分摊 |
| 修改结清服务 | `service/yield/impl/YieldSettlementServiceImpl.java` (如存在) | 结清时 call `YieldLaborCostService.calculateBatchLaborCost` |

### Phase C (UI — 明确延期)

| 类型 | 文件路径 | 说明 |
|---|---|---|
| **新建 Vue 页面** | `web-admin/src/views/hr/piece-rate-rules/` | **Phase C 才做** — 见 F005 |

---

## 核心计算逻辑 (PIECE_RATE 模式)

```java
// YieldLaborCostService.calculateBatchLaborCost (伪代码)

public LaborCostResult calculateBatchLaborCost(String factoryId, Long batchId, boolean force) {

    // F003 guard: BatchWorkSession 冲突检测
    List<BatchWorkSession> existingSessions = batchWorkSessionRepo.findByBatchId(batchId);
    boolean hasBWS = existingSessions.stream()
            .anyMatch(s -> s.getLaborCost() != null && s.getLaborCost().compareTo(BigDecimal.ZERO) > 0);
    if (hasBWS && !force) {
        return LaborCostResult.skipped("已有 BatchWorkSession 记录, 请传 force=true 强制覆盖");
    }

    // 查 YIELD 报工
    List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
    if (reports.isEmpty()) {
        return LaborCostResult.skipped("该批次无 YIELD 报工记录");
    }

    // 按 workerId + workProcessTaskId 分组 (逐工序, 精确计件)
    Map<Long, Map<Long, BigDecimal>> workerTaskOutput = reports.stream()
        .filter(r -> r.getWorkerId() != null && r.getOutputQuantity() != null)
        .collect(Collectors.groupingBy(
            ProductionReport::getWorkerId,
            Collectors.groupingBy(
                ProductionReport::getWorkProcessTaskId,
                Collectors.reducing(BigDecimal.ZERO,
                    ProductionReport::getOutputQuantity, BigDecimal::add))));

    // 批量查 task → workProcess (F002 mapping, 避免 N+1)
    Set<Long> taskIds = reports.stream()
        .map(ProductionReport::getWorkProcessTaskId)
        .filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, WorkProcessTask> taskMap = taskRepo.findAllById(taskIds).stream()
        .collect(Collectors.toMap(WorkProcessTask::getId, t -> t));
    Set<String> wpIds = taskMap.values().stream()
        .map(WorkProcessTask::getWorkProcessId).collect(Collectors.toSet());
    Map<String, WorkProcess> wpMap = wpRepo.findAllById(wpIds).stream()
        .collect(Collectors.toMap(WorkProcess::getId, wp -> wp));

    BigDecimal totalLaborCost = BigDecimal.ZERO;
    List<WorkerCostDetail> details = new ArrayList<>();
    LocalDate reportDate = reports.get(0).getReportDate();

    for (Map.Entry<Long, Map<Long, BigDecimal>> workerEntry : workerTaskOutput.entrySet()) {
        Long workerId = workerEntry.getKey();
        WageMode mode = wagePolicyService.resolveModeForEmployee(factoryId, workerId);

        BigDecimal workerCost = BigDecimal.ZERO;

        if (mode == WageMode.PIECE_RATE || mode == WageMode.MIXED) {
            for (Map.Entry<Long, BigDecimal> taskEntry : workerEntry.getValue().entrySet()) {
                Long taskId = taskEntry.getKey();
                BigDecimal qty = taskEntry.getValue();

                // F002: task → workProcess → processCategory (= PieceRateRule 查找 key)
                WorkProcessTask task = taskMap.get(taskId);
                WorkProcess wp = task != null ? wpMap.get(task.getWorkProcessId()) : null;
                String stageType = wp != null ? wp.getProcessCategory() : null;
                String productTypeId = task != null ? task.getProductTypeId() : null;

                if (stageType == null) {
                    log.warn("批次 {} 工序任务 {} 无法解析 processCategory, 跳过计件", batchId, taskId);
                    continue;
                }

                Optional<PieceRateRule> ruleOpt = wageCalcSvc.findApplicableRule(
                        factoryId, stageType, productTypeId, reportDate);
                if (ruleOpt.isEmpty()) {
                    log.warn("批次 {} 工序 {} 未找到计件规则, 跳过", batchId, stageType);
                    continue;
                }

                // F001: calculateWage(BigDecimal qty) — 同时支持 kg 和件数
                BigDecimal stageCost = ruleOpt.get().calculateWage(qty);
                workerCost = workerCost.add(stageCost);
            }
        }
        // HOURLY: Phase B+ defer (totalWorkMinutes 分摊复杂)

        totalLaborCost = totalLaborCost.add(workerCost);
        details.add(new WorkerCostDetail(workerId, workerCost));
    }

    // 写 batch.laborCost
    ProductionBatch batch = batchRepo.findByFactoryIdAndId(factoryId, batchId)
            .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
    batch.setLaborCost(totalLaborCost.setScale(2, RoundingMode.HALF_UP));
    batch.calculateMetrics();  // 重算 totalCost / unitCost
    batchRepo.save(batch);

    return LaborCostResult.calculated(totalLaborCost, details);
}
```

---

## 数据库迁移

迁移文件位置: `backend/java/cretas-api/src/main/resources/db/migration/` (Java Flyway, **不是** Python smartbi migrations 目录).

版本号: 确认 > prod 已应用 max version (查 flyway_schema_history 最大值), 以 `V20260601_02__...` 为例 (按当天实际 max+1 调整).

```sql
-- V20260601_02__piece_rate_rule_bigdecimal.sql

-- 1. tier threshold: Integer → NUMERIC(14,3) (kg 需小数; 原整数值迁移无损)
ALTER TABLE piece_rate_rules
    ALTER COLUMN tier1_threshold TYPE NUMERIC(14,3) USING tier1_threshold::NUMERIC,
    ALTER COLUMN tier2_threshold TYPE NUMERIC(14,3) USING tier2_threshold::NUMERIC,
    ALTER COLUMN tier3_threshold TYPE NUMERIC(14,3) USING tier3_threshold::NUMERIC;

-- 2. 新增 rate_unit 字段 (默认 PIECE 向后兼容)
ALTER TABLE piece_rate_rules
    ADD COLUMN IF NOT EXISTS rate_unit VARCHAR(10) NOT NULL DEFAULT 'PIECE';

COMMENT ON COLUMN piece_rate_rules.rate_unit IS 'KG=按重量计件, PIECE=按件数计件';
COMMENT ON COLUMN piece_rate_rules.tier1_threshold IS '第一阶梯数量阈值 (kg 或件数), NUMERIC(14,3)';
```

**向后兼容**: `calculateWage(int pieceCount)` 委托给 `calculateWage(BigDecimal)`, 现有调用方 (`WageCalculationService.calculateMonthly` 等) 无需修改.

---

## F005: PieceRateRule UI 明确延期到 Phase C

**已代码验证**: `web-admin/src/views/hr/` 目录下**无** `piece-rate-rules/` 页面. 仅有 `wage-policy/index.vue` (WagePolicy 配置) + `work-types/list.vue` (注释中提及 PieceRateRule 依赖但无配置页).

`WageController` (`/api/mobile/{factoryId}/wage/`) 已提供完整 PieceRateRule CRUD API.

**Phase B MVP 的 F006 配置路径**:
1. 工程师通过 API 或 SQL 直接在 `piece_rate_rules` 表插入 F006 规则数据
2. 触发 `POST /recalculate-labor-cost` → 验证计算结果
3. 管理员无需 web-admin UI 完成 Phase B 验证

**web-admin PieceRateRule 配置页 (CRUD: 规则名/工序阶段/产品类型/阶梯单价/生效日期/rateUnit) → Phase C**.

**利益相关者预期**: Phase B 交付时明确告知 Steve: F006 计件率须工程师手动配置 (`INSERT INTO piece_rate_rules ...`), 自助配置界面在 Phase C.

---

## 开放问题 (更新状态)

| # | 问题 | 状态 |
|---|---|---|
| OQ-1 | 计件单位: kg 还是件/盒? | **已决策 (Steve)**: BOTH 支持. `rateUnit` 字段区分. 设计已纳入. |
| OQ-2 | 计件粒度: 整批 vs 逐工序? | **建议逐工序** (已在设计中实施), 按 `workProcessTaskId` 分组, 复用现有 `findApplicableRule(processStageType)` |
| OQ-3 | 工时来源: `totalWorkMinutes` vs `TimeClockRecord`? | **仍开放** (HOURLY 模式决策). Phase B MVP 先做 PIECE_RATE, defer HOURLY 分摊. |
| OQ-4 | 触发时机: 结清时 vs 按需? | **建议按需 API** (Phase B MVP). 自动结清 trigger → Phase C. |
| OQ-5 | F006 是否在用 BatchWorkSession 签到/签退路径? | **仍开放**. 影响 F003 guard 的实际 force=true 使用频率. 若 F006 从未用 BWS, guard 实际上是防御性 no-op. |
| OQ-6 | F006 当前 piece_rate_rules 是否已有数据? | **仍开放**. Phase B 上线前需先配置. 工程师需 INSERT 规则. |

---

## 现实工作量估算 (F004 已修正)

草稿"~1 天"过于乐观. 实际分解:

| 范围 | 工作量 |
|---|---|
| DB 迁移 (`V__piece_rate_rule_bigdecimal.sql`) | 0.5 h |
| `PieceRateRule` BigDecimal 变更 + `calculateWage(BigDecimal)` 重载 + 单测 | 2–3 h |
| `YieldLaborCostService` 核心计算 (F002 mapping + F003 guard + PIECE_RATE 模式) | 4–6 h |
| `YieldLaborCostController` + DTO | 1–2 h |
| 单测 + 集成测试 (包含边界: 无规则/无 BWS/force 覆盖/跨单位) | 4–5 h |
| **Phase B MVP 合计** | **1.5–2.5 天** |
| + HOURLY 模式 + 工时分摊 + 自动结清 trigger | +1–1.5 天 |
| web-admin PieceRateRule UI (Phase C) | ~1 天 |

**关键工期因子**: PIECE_RATE/HOURLY/MIXED 三模式分支; F003 BatchWorkSession 冲突守卫; F002 task→wp mapping 批量查询; 单测与集成测试覆盖率.

---

## 测试计划

### 单测 (YieldLaborCostServiceTest)

| 场景 | 断言 |
|---|---|
| 1 工人 1 工序 PIECE_RATE, rateUnit=KG, qty=100.5kg, tier1Rate=0.5 | wage=50.25 |
| 1 工人 1 工序 PIECE_RATE, rateUnit=PIECE, qty=300 件, 3 阶梯规则 | 阶梯计算正确 |
| 工序 processCategory 为 null | 跳过该工序, warn log, 不抛异常 |
| 无匹配 PieceRateRule | 该工序 wage=0, warn log, 继续其他工序 |
| 批次无 YIELD 报工 | LaborCostResult.skipped |
| 批次有 BatchWorkSession cost>0, force=false | LaborCostResult.skipped, laborCost 未变 |
| 批次有 BatchWorkSession cost>0, force=true | 覆盖写入, warn log, CALCULATED |
| calculateWage(BigDecimal) 阶梯边界 | 等于阈值/跨阶梯均正确 |
| calculateWage(0) / calculateWage(null) | BigDecimal.ZERO |
| 旧 calculateWage(int) 委托新版 | 返回值与 calculateWage(BigDecimal.valueOf(n)) 一致 |

### 集成测试 (需真实 PG)

| 场景 | 断言 |
|---|---|
| 金标准猪舌批次 (998kg 投入, 382.08kg 产出, 3 道工序) + 配置计件率 | 总人工成本 = 各道产出 × 各道单价 之和 (精确数值) |
| F003: 同批次先跑 BWS 路径写 laborCost=100, 再调 YIELD 路径 force=false | laborCost 仍=100 (未覆盖) |
| F003: force=true | laborCost 改为 YIELD 计算值 |

### Flyway 迁移测试

- `piece_rate_rules.tier1_threshold` 升级后旧整数数据可读 (`::NUMERIC` 无损)
- `rate_unit` 默认 'PIECE' 向后兼容

---

## 与现有路径的关系

| 路径 | 场景 | Phase B 处理 |
|---|---|---|
| `BatchWorkSession` → `batch.laborCost` (`ProcessingServiceImpl`) | 老式生产报工 (签到/签退) | **不改**, F003 guard 保护. 若有 BWS 数据 force=false 时 YIELD 路径不覆盖 |
| `BomServiceImpl` 的 `LaborCostConfig` | BOM 计划成本 (非实绩) | **不改**, 语义不同 (计划 vs 实绩) |
| A5 新路径: YIELD → `batch.laborCost` | 新报工体系批次 | Phase B 新增, force 模式下可与 BWS 路径切换 |

---

## 依赖与前置

| 依赖 | 状态 |
|---|---|
| Phase A YIELD 数据流 | 已 shipped (PR #350/#354/#358) |
| Phase D RN 逐道报工 | 已 shipped (PR #360) |
| `WagePolicy` + `PieceRateRule` DB 数据 (F006 工序配置) | 需工程师配置后 Phase B 才有效 (OQ-6) |
| Flyway 迁移 DB 版本确认 | 实施前查 `flyway_schema_history` max version |

---

## 部署

per HARD RULE `.claude/rules/worktree-and-main-only-deploy.md`:
- 在独立 worktree 开发, off `origin/main`
- 完成 → PR → `git diff origin/main...HEAD --stat` 确认 scope 干净 → merge main
- **从 main 部署**: `./scripts/deploy/deploy-backend.sh --env prod`
- 部署后**必须** `systemctl restart cretas-backend` (Flyway 迁移才能跑, 新代码才能加载)
- 验证: `curl .../production/batches/{id}/recalculate-labor-cost -X POST` 返 LaborCostResult (非 404)

---

## §9 审计修订记录 (2026-06-01)

代码审计读以下文件: `entity/PieceRateRule.java`, `entity/ProductionReport.java`, `entity/WorkProcess.java`, `entity/workprocess/WorkProcessTask.java`, `service/WageCalculationService.java`, `service/impl/ProcessingServiceImpl.java` (lines 1450–1464), `controller/WageController.java`, `web-admin/src/views/hr/` 目录结构.

### Finding 修订表

| Finding | 级别 | 问题 (代码核实) | 修订 |
|---|---|---|---|
| **F001** | P1 | `PieceRateRule.tier1/2/3Threshold` 是 `Integer`; `calculateWage(int)` 只接受整数. Steve 决策支持 kg (小数) → 必须变更. 已代码验证: `@Column(name = "tier1_threshold") private Integer tier1Threshold`. | 阈值 Integer→BigDecimal (NUMERIC(14,3)); 新增 `calculateWage(BigDecimal qty)`; 旧 int 重载委托新版; DB 迁移 `ALTER COLUMN ... TYPE NUMERIC(14,3)`. |
| **F002** | P1 | `ProductionReport.processCategory` = 工人自由文本 (length 200, 如"卤蛋烤"). `PieceRateRule.processStageType` = 标准化代码 (length 50). 草稿直接用 processCategory 查 PieceRateRule → 几乎永远返回 empty → 计件成本 = 0. 已代码验证 ProductionReport.java:73 + PieceRateRule.java:52. | 正确 mapping: `report.workProcessTaskId → WorkProcessTask.workProcessId → WorkProcess.processCategory` (verified: WorkProcess.java:32 processCategory varchar 50) ≡ PieceRateRule.processStageType. 运营约定: WorkProcess 配置时 processCategory 必须与规则 processStageType 一致. `YieldLaborCostService` 批量查 task+wp, 用 wp.processCategory 做规则查找 key; null/未命中 → warn + 该工序 wage=0. |
| **F003** | P2 | `ProcessingServiceImpl:1452-1459` BatchWorkSession 路径已有 `> 0` guard, 但 YIELD 路径会无条件覆盖 `batch.laborCost`. 同批次两路径并存时最终结果取决于调用顺序. | `YieldLaborCostService` 入口 F003 guard: 检测 BWS cost>0 时默认 skip + warn; `force=true` 参数允许显式覆盖. API `?force=true` query param 暴露. `LaborCostResult` DTO 返回 status=SKIPPED/CALCULATED. |
| **F004** | P1 | 草稿"~1 天"估算忽略: BigDecimal 模式分支; BWS 冲突守卫; F002 task→wp mapping 批量查询; 3 模式 (PIECE/HOURLY/MIXED) 分支; 单测+集成测试. | 修正为 Phase B MVP = 1.5–2.5 天 (PIECE_RATE + 按需 API). HOURLY+结清 trigger = 额外 1–1.5 天. Phase C UI = ~1 天. |
| **F005** | P1 | `web-admin/src/views/hr/` 无 `piece-rate-rules/` 目录, 仅有 `wage-policy/index.vue`. `WageController` CRUD API 存在但前端零配置页. F006 无法自助配置计件率. 已代码验证: Glob hr/** + Grep PieceRateRule. | 明确: Phase B MVP 通过 API/SQL 配置计件率 (工程师操作). web-admin PieceRateRule 配置 UI (CRUD) → Phase C. 利益相关者预期在本 spec 及 PR 描述中明确. |

### 确认项 (Confirmations — 无需改动)

- `WagePolicy`, `PieceRateRule`, `HourlyRateRule`, `WageCalculation`, `WageCalculationService`, `WageRecordTriggerService`, `ProductionBatch.laborCost` 全部存在, 代码审计逐文件确认.
- `WageCalculationService.findApplicableRule` 实现逻辑正确 (优先精确匹配 → 工序匹配 → 工厂通用规则), A5 可复用.
- `BatchWorkSession` 路径已有 `> 0` guard (line 1457), F003 guard 是在 YIELD 路径层面的补充, 不需改动 `ProcessingServiceImpl`.
- `ProductionReport.workProcessTaskId` (Long) + `WorkProcessTask.workProcessId` (String) 是 F002 mapping chain 的存在基础, 已验证字段存在.
- `WorkProcess` 无 `processStageType` 字段, `processCategory` (varchar 50) 是唯一语义对齐候选, 对比 `PieceRateRule.processStageType` (varchar 50) 长度一致 — mapping 方案成立.
