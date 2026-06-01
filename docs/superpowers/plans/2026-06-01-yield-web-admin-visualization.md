# 出成率报工 web-admin 可视化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让工厂管理者在 web-admin 看到出成率逐道报工结果(批次详情 998→382.08 累计 38.28% + 工序级聚合),纯读侧,不碰写入链路。

**Architecture:** 后端复用既有 `GET /yield`(补 processName + 跨单位 cumulative 防误导)+ 新增厂级聚合端点 `GET /production/yield/by-process`(native 聚合 production_reports YIELD 行);前端批次详情页加「出成率·逐道报工」卡 + KPI 回填,ProcessIOComparison 换数据源。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA(native query)+ PostgreSQL;Vue 3 + TypeScript + Element Plus;Playwright(headed)。

**Worktree:** `C:/Users/Steve/cretas-yield-web`(分支 `feat/yield-web-admin` off `origin/main`)。所有命令在此 worktree 内执行。

**Spec:** `docs/superpowers/specs/2026-06-01-yield-web-admin-visualization-design.md`(含 §9 审计修订)。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `backend/.../service/yield/impl/YieldCalculationServiceImpl.java` | 纯函数派生 | 改(cumulative 跨单位 guard) |
| `backend/.../service/yield/impl/YieldReportServiceImpl.java` | getYield 编排 | 改(processName enrich) |
| `backend/.../repository/workprocess/WorkProcessTaskRepository.java` | 工序任务 DAO | 改(加 batch fetch 方法) |
| `backend/.../dto/yield/ProcessYieldAggDTO.java` | 工序聚合 DTO | 建 |
| `backend/.../repository/ProductionReportRepository.java` | 报工 DAO | 改(加 native 聚合查询) |
| `backend/.../service/yield/YieldAnalysisService.java` | 聚合 service 接口 | 建 |
| `backend/.../service/yield/impl/YieldAnalysisServiceImpl.java` | 聚合 service 实现 | 建 |
| `backend/.../controller/YieldAnalysisController.java` | 聚合端点 | 建 |
| `backend/.../service/yield/impl/YieldCalculationServiceImplTest.java` | 派生单测 | 改(加跨单位 null 测) |
| `backend/.../service/yield/impl/YieldReportServiceImplTest.java` | enrich 单测 | 建 |
| `backend/.../service/yield/impl/YieldAnalysisServiceImplTest.java` | 聚合单测 | 建 |
| `web-admin/src/views/production/batches/detail.vue` | 批次详情页 | 改(yield 卡 + KPI) |
| `web-admin/src/views/production/ProcessIOComparison.vue` | 工序对比页 | 改(换源 + 空态) |

**实现顺序**: Task 1→2(后端 getYield)→ 3→4(后端聚合)→ 5(批次详情)→ 6(ProcessIO)→ 7(headed Playwright 验证)。后端先于前端。

**通用命令(每个后端 task 用)**:
```bash
cd C:/Users/Steve/cretas-yield-web/backend/java/cretas-api
# mvn 在 /c/tools/apache-maven-3.9.6/bin, 若不在 PATH:
export PATH="$PATH:/c/tools/apache-maven-3.9.6/bin"
```
> CI 会跑 `mvn -B clean verify`(surefire `*Test` + failsafe `*IT`),Java 单测在 CI 真跑。本地用 `mvn -Dtest=<TestClass> test` 快验。

---

## Task 1: 跨单位 cumulativeYieldRate 防误导 (单元 1b, audit YIELD-1 P0)

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldCalculationServiceImpl.java:84-97`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/impl/YieldCalculationServiceImplTest.java`

**背景**: 现 `calculateBatchYield` 在末道单位≠首道单位且 `standardGramsPerUnit==null` 时,仍用 `lastOutput/firstInput` 算 cumulative(混单位无意义值,如 3184盒/998kg≈3.19)。修法:不可折算时 cumulative 留 null。

- [ ] **Step 1: 写失败测试**

在 `YieldCalculationServiceImplTest.java` 加测试(同文件已有 `crossUnitBatch_convertsLastStepWithGramsPerUnit` 验非 null gramsPerUnit 路径,本测验 null 路径):

```java
@Test
void crossUnitBatch_nullGramsPerUnit_cumulativeIsNull() {
    // 首道 kg 投入, 末道 盒 产出, 单位不可比且无折算系数
    ProductionReport step1 = report(101L, 1, new BigDecimal("998"), "kg", new BigDecimal("935.5"), "kg");
    ProductionReport step2 = report(102L, 2, new BigDecimal("935.5"), "kg", new BigDecimal("3184"), "盒");
    BatchYieldDTO dto = service.calculateBatchYield(List.of(step1, step2), null);
    // 末道盒 vs 首道kg 不可折算 → 不输出错误比率
    assertThat(dto.getCumulativeYieldRate()).isNull();
}
```

如 `report(...)` 工厂方法不存在,参照同文件既有测试的 ProductionReport 构造方式(看 `crossUnitBatch_convertsLastStepWithGramsPerUnit` 怎么造 report)复用。`assertThat` 用 AssertJ(同文件既有 import)。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -Dtest=YieldCalculationServiceImplTest#crossUnitBatch_nullGramsPerUnit_cumulativeIsNull test
```
Expected: FAIL — 实际 cumulativeYieldRate ≈ 3.1904(非 null)。

- [ ] **Step 3: 改实现**

`YieldCalculationServiceImpl.java` 把现有 cumulative 计算块(约 88-97 行):

```java
        // 末道折算到首道单位 (盒->kg): output_盒 * gramsPerUnit / 1000
        BigDecimal lastOutputInFirstUnit = lastOutput;
        boolean sameUnit = first.getInputUnit() != null && first.getInputUnit().equals(last.getOutputUnit());
        if (!sameUnit && standardGramsPerUnit != null) {
            lastOutputInFirstUnit = lastOutput.multiply(standardGramsPerUnit)
                    .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
        }
        BigDecimal cumulative = null;
        if (firstInput != null && firstInput.compareTo(BigDecimal.ZERO) > 0) {
            cumulative = lastOutputInFirstUnit.divide(firstInput, YIELD_SCALE, RoundingMode.HALF_UP);
        }
```

改为:

```java
        // 末道折算到首道单位 (盒->kg): output_盒 * gramsPerUnit / 1000
        boolean sameUnit = first.getInputUnit() != null && first.getInputUnit().equals(last.getOutputUnit());
        // audit YIELD-1: 跨单位且无折算系数时不可比, cumulative 留 null (不输出 lastOutput/firstInput 的混单位错误值)
        boolean canComputeCumulative = sameUnit || standardGramsPerUnit != null;
        BigDecimal cumulative = null;
        if (canComputeCumulative && firstInput != null && firstInput.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal lastOutputInFirstUnit = lastOutput;
            if (!sameUnit) {  // 此分支 standardGramsPerUnit 必非 null
                lastOutputInFirstUnit = lastOutput.multiply(standardGramsPerUnit)
                        .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP);
            }
            cumulative = lastOutputInFirstUnit.divide(firstInput, YIELD_SCALE, RoundingMode.HALF_UP);
        }
```

- [ ] **Step 4: 跑测试确认通过(含既有测试不回归)**

```bash
mvn -Dtest=YieldCalculationServiceImplTest test
```
Expected: PASS — 新测试通过,既有 `crossUnitBatch_convertsLastStepWithGramsPerUnit`(传非 null gramsPerUnit)仍通过,同单位金标准测试(998→382.08 kg→kg = 0.3828)仍通过。

- [ ] **Step 5: Commit**

```bash
git commit -m "fix(yield): 跨单位无折算系数时 cumulativeYieldRate 留 null (audit YIELD-1)

末道单位≠首道单位且 standardGramsPerUnit=null 时不再输出 lastOutput/firstInput
的混单位错误比率(3184盒/998kg≈3.19), 改 null, 前端显 —。正确跨单位折算
需克重配置, 属 Phase A/B out-of-scope。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldCalculationServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/impl/YieldCalculationServiceImplTest.java
```

---

## Task 2: getYield 回填 processName + 批量查询方法 (单元 1, audit YIELD-4)

**Files:**
- Modify: `backend/.../repository/workprocess/WorkProcessTaskRepository.java`(加方法)
- Modify: `backend/.../service/yield/impl/YieldReportServiceImpl.java`(getYield + enrich)
- Test: `backend/.../service/yield/impl/YieldReportServiceImplTest.java`(建)

**背景**: `StepYieldDTO.processName` 字段存在但 `calculateSteps` 不设(留 null)。在 `getYield` caller 批量查 task→work_process→processName,enrich。

- [ ] **Step 1: 加批量查询方法**

`WorkProcessTaskRepository.java` 加(注意 import `java.util.Collection`):

```java
    /** 批量按 id 取任务 (audit YIELD-4: enrich processName 避免 N+1). */
    List<WorkProcessTask> findByFactoryIdAndIdIn(String factoryId, Collection<Long> ids);
```

- [ ] **Step 2: 写失败测试**

建 `YieldReportServiceImplTest.java`(Mockito 单测,mock 4 个 repo/service 依赖):

```java
package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.yield.YieldCalculationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YieldReportServiceImplTest {

    @Mock ProductionReportRepository reportRepo;
    @Mock WorkProcessTaskRepository taskRepo;
    @Mock WorkProcessRepository processRepo;
    @Mock YieldCalculationService calcSvc;
    @Mock ProcessingService processingService;
    @InjectMocks YieldReportServiceImpl service;

    @Test
    void getYield_enrichesProcessNamesFromWorkProcess() {
        // calcSvc 返回 2 步, processName 均 null (calculateSteps 不设)
        StepYieldDTO s1 = StepYieldDTO.builder().workProcessTaskId(24L).processOrder(1).build();
        StepYieldDTO s2 = StepYieldDTO.builder().workProcessTaskId(25L).processOrder(2).build();
        BatchYieldDTO dto = BatchYieldDTO.builder().steps(List.of(s1, s2)).build();
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(calcSvc.calculateBatchYield(any(), any())).thenReturn(dto);
        // task 24→工序 W1, task 25→工序 W2
        WorkProcessTask t1 = new WorkProcessTask(); t1.setId(24L); t1.setWorkProcessId("W1");
        WorkProcessTask t2 = new WorkProcessTask(); t2.setId(25L); t2.setWorkProcessId("W2");
        when(taskRepo.findByFactoryIdAndIdIn(eqFactory(), any())).thenReturn(List.of(t1, t2));
        WorkProcess w1 = new WorkProcess(); w1.setId("W1"); w1.setProcessName("处理");
        WorkProcess w2 = new WorkProcess(); w2.setId("W2"); w2.setProcessName("滚揉");
        when(processRepo.findAllById(any())).thenReturn(List.of(w1, w2));

        BatchYieldDTO out = service.getYield("F001", 1897L);

        assertThat(out.getSteps()).extracting(StepYieldDTO::getProcessName)
                .containsExactly("处理", "滚揉");
    }

    private static String eqFactory() { return org.mockito.ArgumentMatchers.eq("F001"); }
}
```

> 若 `WorkProcessTask` / `WorkProcess` 无无参 setter(非 Lombok),改用其实际构造方式;先 `grep "class WorkProcessTask" + @Data/@Setter` 确认。两实体均 `extends BaseEntity`,大概率有 setter。

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -Dtest=YieldReportServiceImplTest test
```
Expected: FAIL — processName 仍为 null(enrich 未实现)。

- [ ] **Step 4: 改 getYield 加 enrich**

`YieldReportServiceImpl.java` 顶部 import 加:
```java
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.WorkProcess;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
```
(`List`/`Map`/`Objects`/`WorkProcessTask` 已 import)

把现有 getYield:
```java
    @Override
    public BatchYieldDTO getYield(String factoryId, Long batchId) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        return calcSvc.calculateBatchYield(reports, null);
    }
```
改为:
```java
    @Override
    public BatchYieldDTO getYield(String factoryId, Long batchId) {
        List<ProductionReport> reports = reportRepo.findYieldReportsByBatch(factoryId, batchId);
        BatchYieldDTO dto = calcSvc.calculateBatchYield(reports, null);
        enrichProcessNames(factoryId, dto);
        return dto;
    }

    /** audit YIELD-4: 批量查 task→work_process→processName, 回填 steps (避免 N+1). 查不到留 null, 前端 fallback. */
    private void enrichProcessNames(String factoryId, BatchYieldDTO dto) {
        if (dto.getSteps() == null || dto.getSteps().isEmpty()) {
            return;
        }
        Set<Long> taskIds = dto.getSteps().stream()
                .map(StepYieldDTO::getWorkProcessTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return;
        }
        Map<Long, String> taskToProcessId = taskRepo.findByFactoryIdAndIdIn(factoryId, taskIds).stream()
                .filter(t -> t.getWorkProcessId() != null)
                .collect(Collectors.toMap(WorkProcessTask::getId, WorkProcessTask::getWorkProcessId, (a, b) -> a));
        Set<String> processIds = new HashSet<>(taskToProcessId.values());
        Map<String, String> processIdToName = processRepo.findAllById(processIds).stream()
                .collect(Collectors.toMap(WorkProcess::getId, WorkProcess::getProcessName, (a, b) -> a));
        for (StepYieldDTO step : dto.getSteps()) {
            String pid = taskToProcessId.get(step.getWorkProcessTaskId());
            if (pid != null) {
                step.setProcessName(processIdToName.get(pid));
            }
        }
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -Dtest=YieldReportServiceImplTest,YieldCalculationServiceImplTest test
```
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(yield): getYield 回填 processName (批量查询避免 N+1, audit YIELD-4)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- backend/java/cretas-api/src/main/java/com/cretas/aims/repository/workprocess/WorkProcessTaskRepository.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/impl/YieldReportServiceImplTest.java
```

---

## Task 3: ProcessYieldAggDTO + 聚合查询 + YieldAnalysisService (单元 2 后端)

**Files:**
- Create: `backend/.../dto/yield/ProcessYieldAggDTO.java`
- Modify: `backend/.../repository/ProductionReportRepository.java`(加 native 聚合查询)
- Create: `backend/.../service/yield/YieldAnalysisService.java`(接口)
- Create: `backend/.../service/yield/impl/YieldAnalysisServiceImpl.java`(实现)
- Test: `backend/.../service/yield/impl/YieldAnalysisServiceImplTest.java`(建)

- [ ] **Step 1: 建 DTO**

`dto/yield/ProcessYieldAggDTO.java`:
```java
package com.cretas.aims.dto.yield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 工序级出成率聚合 (厂级跨批, GET /production/yield/by-process 输出) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessYieldAggDTO {
    private String processName;
    private BigDecimal inputQuantity;   // Σ input_quantity, scale 2
    private BigDecimal outputQuantity;  // Σ output_quantity, scale 2
    private BigDecimal conversionRate;  // 出成率% 0-100, scale 1; 单位不可比时 null
    private BigDecimal wastageRate;     // 损耗率% 0-100, scale 1; 单位不可比时 null
    private String unit;                // 工序标准投入单位 (work_processes.unit)
    private Boolean unitComparable;     // wp.unit == wp.output_unit
    private Integer batchCount;
}
```
> 字段全 camelCase, 无 @JsonProperty(audit RULE-7: Jackson 默认 camelCase 自动序列化, 前端零转换)。

- [ ] **Step 2: 加聚合查询**

`ProductionReportRepository.java` 加方法(`LocalDate`/`List`/`Map`/`@Param` 已 import)。**用 snake_case 别名**(PG 未加引号别名折叠为小写,service 按 snake_case 读 Map key):

```java
    /**
     * audit 单元2: 厂级按工序聚合 YIELD 报工的投入/产出. native (沿用 analytics 投影惯例).
     * 单位取工序标准 wp.unit/wp.output_unit (audit SQL-2, 非报工记录单位).
     * 可空参数由 service 转 sentinel (audit SQL-1: 永不传 null, 避 PG 类型推断失败).
     */
    @Query(value = """
        SELECT
            wp.process_name AS process_name,
            COALESCE(SUM(CAST(pr.input_quantity AS DECIMAL(14,2))), 0) AS total_input,
            COALESCE(SUM(CAST(pr.output_quantity AS DECIMAL(14,2))), 0) AS total_output,
            wp.unit AS input_unit,
            wp.output_unit AS output_unit,
            COUNT(DISTINCT pr.batch_id) AS batch_count
        FROM production_reports pr
        JOIN work_process_tasks wpt ON pr.work_process_task_id = wpt.id AND wpt.deleted_at IS NULL
        JOIN work_processes wp ON wpt.work_process_id = wp.id
        WHERE pr.factory_id = :factoryId
          AND wpt.factory_id = :factoryId
          AND pr.report_type = 'YIELD'
          AND pr.deleted_at IS NULL
          AND pr.report_date BETWEEN :startDate AND :endDate
          AND (:productTypeId = '' OR wpt.product_type_id = :productTypeId)
        GROUP BY wp.id, wp.process_name, wp.unit, wp.output_unit
        ORDER BY MIN(wpt.process_order)
        """, nativeQuery = true)
    List<Map<String, Object>> aggregateYieldByProcess(
            @Param("factoryId") String factoryId,
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("productTypeId") String productTypeId);
```

- [ ] **Step 3: 建 service 接口**

`service/yield/YieldAnalysisService.java`:
```java
package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;

import java.time.LocalDate;
import java.util.List;

public interface YieldAnalysisService {
    /** 厂级按工序聚合出成率. start/end/productTypeId 可为 null (service 内转 sentinel). */
    List<ProcessYieldAggDTO> aggregateByProcess(String factoryId, LocalDate startDate, LocalDate endDate, String productTypeId);
}
```

- [ ] **Step 4: 写失败测试**

`service/yield/impl/YieldAnalysisServiceImplTest.java`:
```java
package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YieldAnalysisServiceImplTest {

    @Mock ProductionReportRepository reportRepo;
    @InjectMocks YieldAnalysisServiceImpl service;

    private Map<String, Object> row(String name, String in, String out, String inUnit, String outUnit, long cnt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("process_name", name);
        m.put("total_input", new BigDecimal(in));
        m.put("total_output", new BigDecimal(out));
        m.put("input_unit", inUnit);
        m.put("output_unit", outUnit);
        m.put("batch_count", cnt);
        return m;
    }

    @Test
    void aggregate_sameUnit_computesConversionAndWastage() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(row("处理", "300", "260", "kg", "kg", 2L)));
        List<ProcessYieldAggDTO> out = service.aggregateByProcess("F001", null, null, null);
        assertThat(out).hasSize(1);
        ProcessYieldAggDTO r = out.get(0);
        assertThat(r.getProcessName()).isEqualTo("处理");
        assertThat(r.getConversionRate()).isEqualByComparingTo("86.7");  // 260/300*100=86.666→86.7
        assertThat(r.getWastageRate()).isEqualByComparingTo("13.3");     // 40/300*100=13.333→13.3
        assertThat(r.getUnitComparable()).isTrue();
        assertThat(r.getBatchCount()).isEqualTo(2);
    }

    @Test
    void aggregate_crossUnit_conversionNull() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(List.of(row("末道", "998", "3184", "kg", "盒", 1L)));
        List<ProcessYieldAggDTO> out = service.aggregateByProcess("F001", null, null, null);
        assertThat(out.get(0).getUnitComparable()).isFalse();
        assertThat(out.get(0).getConversionRate()).isNull();
        assertThat(out.get(0).getWastageRate()).isNull();
    }

    @Test
    void aggregate_nullParams_convertedToSentinels() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString()))
                .thenReturn(List.of());
        service.aggregateByProcess("F001", null, null, null);
        // sentinel: start=1900-01-01, end=2999-12-31, productTypeId=""
        verify(reportRepo).aggregateYieldByProcess(eq("F001"),
                eq(LocalDate.of(1900, 1, 1)), eq(LocalDate.of(2999, 12, 31)), eq(""));
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

```bash
mvn -Dtest=YieldAnalysisServiceImplTest test
```
Expected: FAIL — `YieldAnalysisServiceImpl` 不存在(编译失败)。

- [ ] **Step 6: 建 service 实现**

`service/yield/impl/YieldAnalysisServiceImpl.java`:
```java
package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.YieldAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class YieldAnalysisServiceImpl implements YieldAnalysisService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final ProductionReportRepository reportRepo;

    @Override
    public List<ProcessYieldAggDTO> aggregateByProcess(String factoryId, LocalDate startDate, LocalDate endDate, String productTypeId) {
        // audit SQL-1: sentinel 避 PG null 参数类型推断失败
        LocalDate start = startDate != null ? startDate : LocalDate.of(1900, 1, 1);
        LocalDate end = endDate != null ? endDate : LocalDate.of(2999, 12, 31);
        String pt = productTypeId != null ? productTypeId : "";

        List<Map<String, Object>> rows = reportRepo.aggregateYieldByProcess(factoryId, start, end, pt);
        List<ProcessYieldAggDTO> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal totalInput = toBig(r.get("total_input"));
            BigDecimal totalOutput = toBig(r.get("total_output"));
            String inUnit = asStr(r.get("input_unit"));
            String outUnit = asStr(r.get("output_unit"));
            boolean comparable = Objects.equals(inUnit, outUnit);

            BigDecimal conversionRate = null;
            BigDecimal wastageRate = null;
            if (comparable && totalInput.compareTo(BigDecimal.ZERO) > 0) {
                conversionRate = totalOutput.multiply(HUNDRED).divide(totalInput, 1, RoundingMode.HALF_UP);
                BigDecimal waste = totalInput.subtract(totalOutput).multiply(HUNDRED)
                        .divide(totalInput, 1, RoundingMode.HALF_UP);
                wastageRate = waste.max(BigDecimal.ZERO);
            }

            result.add(ProcessYieldAggDTO.builder()
                    .processName(asStr(r.get("process_name")))
                    .inputQuantity(totalInput.setScale(2, RoundingMode.HALF_UP))
                    .outputQuantity(totalOutput.setScale(2, RoundingMode.HALF_UP))
                    .conversionRate(conversionRate)
                    .wastageRate(wastageRate)
                    .unit(inUnit)
                    .unitComparable(comparable)
                    .batchCount(toInt(r.get("batch_count")))
                    .build());
        }
        return result;
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static String asStr(Object v) {
        return v == null ? null : v.toString();
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

```bash
mvn -Dtest=YieldAnalysisServiceImplTest test
```
Expected: PASS(3 测试)。

- [ ] **Step 8: Commit**

```bash
git commit -m "feat(yield): ProcessYieldAggDTO + 厂级工序聚合 service (单元2, audit SQL-1/SQL-2/TESTING-2)

native 聚合 production_reports YIELD; sentinel 参数避 PG null 类型推断;
wp.unit 工序标准单位 + unitComparable 跨单位 conversionRate null。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/ProcessYieldAggDTO.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionReportRepository.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/YieldAnalysisService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldAnalysisServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/impl/YieldAnalysisServiceImplTest.java
```

---

## Task 4: YieldAnalysisController (单元 2 端点, audit RBAC-2)

**Files:**
- Create: `backend/.../controller/YieldAnalysisController.java`

> 模仿 `ProcessTaskController` 注解模式: 类级 `@RequireModule("production")`(合法 module, audit RBAC-2), GET 不加 `@RequirePermission`(模块即门 read, 同 ProcessTaskController GET 惯例)。operator(production:write 无 read)访问不到属有意为之。

- [ ] **Step 1: 建 controller**

`controller/YieldAnalysisController.java`:
```java
package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.service.yield.YieldAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/yield")
@Tag(name = "出成率分析", description = "厂级出成率聚合 (只读)")
@RequiredArgsConstructor
@RequireModule("production")
public class YieldAnalysisController {

    private final YieldAnalysisService yieldAnalysisService;

    @GetMapping("/by-process")
    @Operation(summary = "工序级出成率聚合 (厂级跨批, 按报工日期/产品筛选)")
    public ApiResponse<List<ProcessYieldAggDTO>> byProcess(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String productTypeId) {
        return ApiResponse.success(
                yieldAnalysisService.aggregateByProcess(factoryId, startDate, endDate, productTypeId));
    }
}
```

- [ ] **Step 2: 编译 + 全后端测试不回归**

```bash
mvn -B clean test -Dtest='Yield*'
```
Expected: 编译通过 + Yield* 系列测试全 PASS。

- [ ] **Step 3: 启动校验端点存在(可选, 本地若有 PG)**

如本地有 PG 可跑:
```bash
mvn spring-boot:run   # 另一终端
curl -s "http://localhost:10010/api/mobile/F001/production/yield/by-process" -H "Authorization: Bearer <token>" | head -c 200
```
Expected: `{"success":true,"data":[...]}`(无数据则 `data:[]`)。若无本地 PG, 跳过, 留 Task 7 在 prod 验。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(yield): YieldAnalysisController GET /production/yield/by-process (audit RBAC-2)

@RequireModule(production) 合法 module; GET 模块即门 (同 ProcessTaskController 惯例)。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/YieldAnalysisController.java
```

---

## Task 5: 批次详情页 yield 卡 + KPI 回填 (单元 3)

**Files:**
- Modify: `web-admin/src/views/production/batches/detail.vue`

> 前端无单测惯例,验证 = `vue-tsc` 类型检查 + `vite build` + Task 7 headed Playwright。

- [ ] **Step 1: 加 yieldData 取数(allSettled 第三请求)**

`detail.vue` `<script setup>`:
- state 区(约 33-39 行 refs 旁)加:
```ts
const yieldData = ref<any | null>(null);
```
- `loadData()` 把(约 50-53 行):
```ts
    const [batchRes, timelineRes] = await Promise.allSettled([
      get(`/${factoryId.value}/processing/batches/${batchId.value}`),
      get(`/${factoryId.value}/processing/batches/${batchId.value}/timeline`)
    ]);
```
改为(audit FE-VUE-1: 同步加第三个解构变量):
```ts
    const [batchRes, timelineRes, yieldRes] = await Promise.allSettled([
      get(`/${factoryId.value}/processing/batches/${batchId.value}`),
      get(`/${factoryId.value}/processing/batches/${batchId.value}/timeline`),
      get(`/${factoryId.value}/production/batches/${batchId.value}/yield`)
    ]);
```
- 在 timeline 分支后加 yield 分支:
```ts
    if (yieldRes.status === 'fulfilled' && yieldRes.value.success
        && yieldRes.value.data?.steps?.length > 0) {
      yieldData.value = yieldRes.value.data;
    } else {
      yieldData.value = null;
    }
```

- [ ] **Step 2: 加 KPI computed + 回填**

`<script setup>` 加 computed(import `computed` 已有):
```ts
// audit YIELD-2/单元3: 有 YIELD 数据时用末道产出回填"实际产量"
const hasYield = computed(() => !!yieldData.value?.steps?.length);
const displayActualQuantity = computed(() =>
  hasYield.value ? yieldData.value.lastStepOutput : batch.value?.actualQuantity);
const displayActualUnit = computed(() =>
  hasYield.value ? (yieldData.value.lastStepOutputUnit || '') : (batch.value?.unit || ''));
// audit YIELD-1: 跨单位 cumulative=null 显 —, 不能 *100 (null*100===0 会误显 0.0%)
const cumulativeDisplay = computed(() => {
  const r = yieldData.value?.cumulativeYieldRate;
  return r == null ? '—' : formatPercent(r * 100);
});
```

`<template>` 顶部「实际产量」KPI 卡(约 227-233 行)改用回填值:
```html
        <div class="kpi-card">
          <div class="kpi-label">实际产量</div>
          <div class="kpi-value" :class="{ 'text-success': Number(displayActualQuantity) > 0 }">
            {{ formatNum(displayActualQuantity) }}
          </div>
          <div class="kpi-unit">{{ displayActualUnit }}</div>
        </div>
```
紧随其后插入「累计出成率」卡(仅有 yield 时显):
```html
        <div v-if="hasYield" class="kpi-card">
          <div class="kpi-label">累计出成率</div>
          <div class="kpi-value">{{ cumulativeDisplay }}</div>
        </div>
```

- [ ] **Step 3: 改 KPI 网格列数(audit FE-VUE-3)**

`<style>` 里 `.kpi-row`(约 441-446 行):
```scss
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
```
(原 `repeat(5, 1fr)` 加卡会折行错位;`auto-fit` 自适应)。`@media(max-width:1200px)` 断点保留。

- [ ] **Step 4: 加「出成率·逐道报工」卡**

`<template>` 的 `detail-grid` 内(基本信息卡之后、原料消耗卡之前)插入:
```html
        <!-- 单元3: 出成率·逐道报工 (audit YIELD-1/5/6, FE-VUE-6) -->
        <el-card v-if="hasYield" shadow="never" class="detail-card">
          <template #header>
            <span class="section-title">出成率 · 逐道报工</span>
          </template>
          <el-table :data="yieldData.steps" border stripe size="small" style="width: 100%">
            <el-table-column label="道" width="60" align="center">
              <template #default="{ row }">{{ row.processOrder }}</template>
            </el-table-column>
            <el-table-column label="工序" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.processName || ('第' + row.processOrder + '道') }}</template>
            </el-table-column>
            <el-table-column label="投入" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalInput) }} {{ row.inputUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="产出" width="130" align="right">
              <template #default="{ row }">{{ formatNum(row.totalOutput) }} {{ row.outputUnit || '' }}</template>
            </el-table-column>
            <el-table-column label="出成率" width="110" align="center">
              <template #default="{ row }">
                <span v-if="!row.unitComparable">—</span>
                <span v-else :class="{ 'text-danger': row.yieldAlert }" :title="row.yieldAlert || ''">
                  {{ formatPercent(row.yieldRate * 100) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="结转" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.carryover == null">—</span>
                <span v-else :class="{ 'text-warning': Number(row.carryover) > 0 }">{{ formatNum(row.carryover) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="yield-summary">
            合计: {{ formatNum(yieldData.firstStepInput) }} {{ yieldData.firstStepInputUnit || '' }}
            → {{ formatNum(yieldData.lastStepOutput) }} {{ yieldData.lastStepOutputUnit || '' }}
            &nbsp;累计出成率 {{ cumulativeDisplay }}
          </div>
        </el-card>
```
`<style>` 加:
```scss
.yield-summary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  font-weight: 600;
  color: var(--text-color-primary, #303133);
}
```
> formatPercent 已存在(null→'-')。跨单位时 cumulativeDisplay='—'(单元1b 后端返 null),合计行 lastStepOutput 仍按原单位显量(audit YIELD-6: 不并排两异单位再配矛盾百分比 — 此处累计已是 '—')。

- [ ] **Step 5: 类型检查 + 构建**

```bash
cd C:/Users/Steve/cretas-yield-web/web-admin
npx vue-tsc --noEmit 2>&1 | grep -i "detail.vue" || echo "detail.vue 无类型错误"
```
Expected: detail.vue 无类型错误(`yieldData: any` 容忍动态字段)。

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(web): 批次详情页出成率逐道报工卡 + KPI 回填 (单元3)

实际产量回填 lastStepOutput + 累计出成率 KPI; 逐道表 processName fallback +
跨单位/保水/null 防护 (audit YIELD-1/5/6, FE-VUE-1/3/6)。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- web-admin/src/views/production/batches/detail.vue
```

---

## Task 6: ProcessIOComparison 换源 + 空态 (单元 4)

**Files:**
- Modify: `web-admin/src/views/production/ProcessIOComparison.vue`

- [ ] **Step 1: 换数据源 + 删旧聚合**

`<script setup>`:
- 删本页局部 `interface ProcessTaskItem`(约 37-47 行)。**勿动** `src/api/processProduction.ts` 的全局 ProcessTaskItem(audit FE-VUE-4)。
- 加 interface:
```ts
interface ProcessYieldAgg {
  processName: string;
  inputQuantity: number;
  outputQuantity: number;
  conversionRate: number | null;  // 后端已 0-100, 不再 ×100 (audit FE-VUE-5); 不可比为 null
  wastageRate: number | null;
  unit: string;
  unitComparable: boolean;
  batchCount: number;
}
```
- 删 `aggregateByProcess` 函数(约 129-178 行,整个函数)。
- `loadData()`(约 87-122 行)整体替换为:
```ts
async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {};
    if (dateRange.value && dateRange.value[0]) {
      params.startDate = dateRange.value[0];
      params.endDate = dateRange.value[1];
    }
    if (selectedProduct.value) {
      params.productTypeId = selectedProduct.value;
    }
    // 单元4: 换源 /process-tasks → /production/yield/by-process (后端已聚合, 直接渲染)
    const response = await get<ProcessYieldAgg[]>(
      `/${factoryId.value}/production/yield/by-process`, { params }
    );
    if (response.success && response.data) {
      tableData.value = (response.data || []).map((r) => ({
        processName: r.processName,
        processCategory: '',
        inputQuantity: r.inputQuantity,
        outputQuantity: r.outputQuantity,
        // 后端已 0-100 (audit FE-VUE-5: 不再 ×100); 不可比为 null
        conversionRate: r.conversionRate ?? null,
        wastageRate: r.wastageRate ?? null,
        unit: r.unit,
        batchCount: r.batchCount,
      })) as ProcessIORow[];
    } else {
      tableData.value = [];
    }
  } catch (error: any) {
    // audit RULE-5: 不在 catch 弹 toast — request.ts 拦截器已对 success=false 弹 sticky+actionHint
    console.error('加载工序出成率失败:', error);
    tableData.value = [];
  } finally {
    loading.value = false;
  }
}
```
> `ProcessIORow.conversionRate`/`wastageRate` 改为 `number | null`(改 interface 约 26-27 行类型)。

- [ ] **Step 2: 表格 null 容错 + KPI computed 容错**

- KPI `computed`(约 50-64 行)的 reduce 排除 null:
```ts
const kpi = computed(() => {
  const rows = tableData.value;
  if (rows.length === 0) return { processCount: 0, avgConversion: 0, avgWastage: 0, lowEfficiencyCount: 0 };
  const comparable = rows.filter((r) => r.conversionRate != null);
  const avgConversion = comparable.length
    ? comparable.reduce((s, r) => s + (r.conversionRate as number), 0) / comparable.length : 0;
  const avgWastage = comparable.length
    ? comparable.reduce((s, r) => s + (r.wastageRate as number ?? 0), 0) / comparable.length : 0;
  const lowEfficiency = comparable.filter((r) => (r.conversionRate as number) < 80).length;
  return {
    processCount: rows.length,
    avgConversion: Math.round(avgConversion * 10) / 10,
    avgWastage: Math.round(avgWastage * 10) / 10,
    lowEfficiencyCount: lowEfficiency,
  };
});
```
- 表格「转化率」「损耗率」列(约 296-317 行)null 显 "—":
```html
        <el-table-column label="转化率" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.conversionRate == null">—</span>
            <el-tag v-else :type="getConversionTagType(row.conversionRate)" size="small" effect="light">
              {{ row.conversionRate }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="损耗率" width="130" align="center">
          <template #default="{ row }">
            <span v-if="row.wastageRate == null">—</span>
            <el-tag v-else :type="getWastageTagType(row.wastageRate)" size="small" effect="light">
              {{ row.wastageRate }}%
            </el-tag>
          </template>
        </el-table-column>
```
- 「转化率进度」列(约 318-327 行)null 容错:
```html
        <el-table-column label="转化率进度" min-width="180">
          <template #default="{ row }">
            <span v-if="row.conversionRate == null">—</span>
            <el-progress v-else :percentage="Math.min(row.conversionRate, 100)"
              :color="getConversionColor(row.conversionRate)" :stroke-width="10" :show-text="false" />
          </template>
        </el-table-column>
```

- [ ] **Step 3: 加非 YIELD 工厂空态 + 日期 tooltip(audit SCOPE-1/SCOPE-3)**

- 表格 `empty-text` 改更明确,或在表格外加空态。最简: 表格 `empty-text="本厂暂无出成率报工数据 — 车间在 App 端逐道报工后此处自动汇总"`。
```html
      <el-table
        :data="tableData"
        v-loading="loading"
        empty-text="本厂暂无出成率报工数据 — 车间在 App 端逐道报工后此处自动汇总"
        stripe border style="width: 100%"
      >
```
- 日期选择器(约 220-229 行)加 tooltip 说明语义:
```html
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="报工开始日期"
              end-placeholder="报工结束日期"
              value-format="YYYY-MM-DD"
              style="width: 280px"
              title="按 YIELD 报工日期筛选"
              @change="handleSearch"
            />
```

- [ ] **Step 4: 类型检查 + 构建**

```bash
cd C:/Users/Steve/cretas-yield-web/web-admin
npx vue-tsc --noEmit 2>&1 | grep -i "ProcessIOComparison" || echo "ProcessIOComparison 无类型错误"
npm run build 2>&1 | tail -5
```
Expected: 无类型错误 + build 成功(vite 产出 dist)。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(web): ProcessIO 换 YIELD 聚合源 + 空态引导 (单元4, audit SCOPE-1/3, RULE-5, FE-VUE-4/5)

数据源 /process-tasks → /production/yield/by-process; 删本页局部聚合; null 容错;
非 YIELD 工厂空态引导; catch 不重复弹 toast。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- web-admin/src/views/production/ProcessIOComparison.vue
```

---

## Task 7: headed Playwright 端到端验证 (audit RULE-2)

**Files:** 无代码改动(验证 + 截图)。

> per `.claude/rules/playwright-headed-mode.md`: 必 headed。用 MCP headed 浏览器(默认 headed)手验,或仿 `playwright.config.ts` 的 `mealclaw-customer-ui` 项目加 headed E2E。本 task 用 MCP headed 浏览器 + prod 数据(batch 1897 = PDX-DEMO-86028, 已有猪舌 3 道 YIELD 报工)。

**前置**: 后端 Task 1-4 已部署到 prod(见下方部署小节),web-admin Task 5-6 已部署或本地 dev 指向 prod 后端。

- [ ] **Step 1: 批次详情验证**

headed 浏览器登录 prod web-admin(139:8086)factory_admin1/123456 → 访问 `/production/batches/1897` → 断言:
- 顶部「实际产量」显 `382.08 kg`(非 "-")
- 「累计出成率」KPI 显 `38.3%`
- 「出成率·逐道报工」卡 3 行: 道1 处理 998→935.5 93.7%, 道2 滚揉 935.5→1262.9 135.0%(text-danger 若 A7 告警), 道3 末道 1262.9→382.08 30.3%
- 合计行: `998 kg → 382.08 kg 累计出成率 38.3%`
- 截图 `task7-batch-detail.png`

- [ ] **Step 2: 工序对比页验证**

访问 `/production/process-io` → 断言:
- 表格显工序聚合行(处理/滚揉/末道),转化率与逐道一致(单批时)
- 截图 `task7-process-io.png`

- [ ] **Step 3: 非 YIELD 工厂空态验证(若有此类工厂账号)**

访问一个无 YIELD 报工的工厂的 `/production/process-io` → 断言空态文案 "本厂暂无出成率报工数据..." 显示(非崩溃/非旧计划数)。无此账号则跳过,记录"未覆盖"。

- [ ] **Step 4: 写验证文档 + headed verification block**

按 `.claude/rules/playwright-headed-mode.md` 要求,验证文档末尾贴 headed verification block(headless:false / viewport / locale / 中文字体真显 / screenshot)。

---

## 部署 (after all tasks, per HARD RULE worktree-and-main-only-deploy)

```bash
# 1. 确认 PR scope 干净
cd C:/Users/Steve/cretas-yield-web
git diff origin/main...HEAD --stat   # 应只有本 feature 文件, 无 sister 文件

# 2. PR → merge main (gh pr create / merge)
# 3. 切到 main 部署 (绝不从 feature 分支部署 prod)
git checkout main && git pull origin main
./scripts/deploy/deploy-backend.sh --env prod          # Java (默认 --mode bluegreen, audit RULE-6)
./scripts/deploy/deploy-web-admin.sh --env prod        # web-admin
# 4. 重启 systemd 活跃实例 (deploy 传 jar 不重启 → 新类不加载!)
ssh root@47.100.235.168 "systemctl restart cretas-backend"
# 5. 核对端点存在 (蓝绿活跃端口轮换, 先找活的)
for p in 10010 10020; do curl -s -m5 -o /dev/null -w "$p=%{http_code}\n" http://localhost:$p/api/mobile/health; done   # via SSH tunnel
curl -s ".../api/mobile/F001/production/yield/by-process" -H "Authorization: Bearer <token>" | head -c 200  # 非 404
```
> 纯读无 flyway 迁移, 但新 Java 类仍需重启进程加载。判 deploy 真生效靠端点返回 + 进程 etimes<300s, 不信 deploy 日志。

---

## Self-Review

**Spec coverage**: 单元1(Task2)/1b(Task1)/2(Task3+4)/3(Task5)/4(Task6)+ 验证(Task7)+ 部署 全覆盖。§9 审计修订: YIELD-1(T1)/SQL-1 sentinel(T3)/SQL-2 wp.unit(T3)/TESTING-2 unitComparable(T3)/RBAC-2 module(T4)/RBAC-3 factory_id(T3 SQL)/SCOPE-1 空态(T6)/SCOPE-3 日期(T6)/YIELD-4 批量查询(T2)/RULE-2 headed(T7)/RULE-5 catch toast(T6)/FE-VUE-1/3/5/6(T5/T6)/YIELD-5/6(T5)/RULE-7 无@JsonProperty(T3) 全落地。RBAC-4(path factoryId 校验): 跟随 ProcessTaskController 既有惯例(GET 不显式校验),平台级共性, 不在本 feature 单独引入。

**Placeholder scan**: 无 TBD/TODO; 每步含完整代码或精确命令。

**Type consistency**: ProcessYieldAggDTO 字段(processName/inputQuantity/outputQuantity/conversionRate/wastageRate/unit/unitComparable/batchCount)在 Task3 DTO、Task3 service map、Task6 前端 interface 三处一致。getYield enrich 用的 StepYieldDTO.setProcessName / WorkProcessTask.getId/getWorkProcessId / WorkProcess.getId/getProcessName 均已核对存在。native query snake_case 别名(process_name/total_input/total_output/input_unit/output_unit/batch_count)与 service `r.get("...")` 一致。
