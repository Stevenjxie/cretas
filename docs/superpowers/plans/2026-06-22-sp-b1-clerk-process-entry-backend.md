# SP-B1 文员逐道录入 — 后端物化 + 成本图 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or executing-plans). Steps use `- [ ]` checkboxes.

**Goal:** 后端接收「文员逐道工序录入」结构化负载，把每道工序写成 `production_reports`（复用 submitReport）并**物化成成本引擎可读的 `MaterialConsumption` + `MaterialBatch(source_doc_type='PRODUCTION_BATCH')` 图**（含拆分/混锅，按「投料kg×上游单价」写入时分摊），幂等，供 SP-C / 现成 `OrderCostBreakdownService` 算出正确单盒成本。

**Architecture:** 新 `ClerkProcessEntryService` 编排：拓扑序处理链（半成品批先于成品批）→ 每批每道 reuse `submitReport` 写报工事实 → 物化成本边（原料/调料/混锅来源）→ 半成品批产出转 `MaterialBatch`（priced）。`OrderCostBreakdownService.traceCost` 加**按消耗比例分摊**（防共享上游双重计数），对既有 1:1 链向后兼容。整次录入包一个幂等事务。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA + PostgreSQL；JUnit5/Mockito + 1 个 @SpringBootTest 集成测（H2）。

**Spec:** `docs/superpowers/specs/2026-06-22-clerk-process-entry-recipe-cost-design.md` §4–5。
**依赖:** SP-A 已合并（`RecipeCostCalculator`、`product_recipes`）。

**隔离:** `git worktree add -b feat/sp-b1-process-entry ../cretas-sp-b1 origin/main`。commit `git commit -- <paths>`。Flyway 号若需用 `V20261027_03`（27_01 SP-A 已用、27_02 给 SP-C 预留）—— 本 B1 **不建新表**（复用现有 material_consumptions/material_batches/production_reports），无迁移。

**Grounding（已验 origin/main）：**
- `submitReport(String factoryId, Long batchId, Long workerId, YieldReportRequest req)` 写 production_reports + WIP；workerId 来自 `@RequestAttribute("userId")`。
- `MaterialConsumption` 字段：factoryId, productionPlanId, productionBatchId(Long), batchId(String→MaterialBatch.id), materialTypeId, quantity, unitPrice, totalCost, sourceType('RAW_MATERIAL'|'SEMI_FINISHED'|'SEASONING'), consumedAt, consumptionTime, recordedBy。repo: `MaterialConsumptionRepository.findByProductionBatchIdAndFactoryId`。
- `MaterialBatch` 字段：id, factoryId, batchNumber, materialTypeId, warehouseId, createdBy, quantityUnit, inboundDate, receiptQuantity, reservedQuantity, usedQuantity, status, unitPrice, **sourceDocType, sourceDocId**。repo: `findByIdAndFactoryId`。
- `OrderCostBreakdownService.traceCost(factoryId, MaterialConsumption c, depth, visited)`：读 `c.getTotalCost()`；`mb=materialBatchRepository.findByIdAndFactoryId(c.getBatchId(),factoryId)`；若 `mb.sourceDocType=='PRODUCTION_BATCH' && sourceDocId!=null` → 递归 `consumptionRepository.findByProductionBatchIdAndFactoryId(parseLong(sourceDocId))`，`sum += r[0]`，返回 `sum>0?sum:own`。**当前忽略消耗 qty 比例 → 共享上游会双重计数。**
- settle 幂等范式：`productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndIdempotencyKeyAndDeletedAtIsNull` → 命中返缓存 / 否则 409。

---

### Task 1: ProcessChainEntryRequest DTO（API 契约，SP-B2 前端将消费）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessChainEntryRequest.java`

- [ ] **Step 1: 写 DTO（含嵌套）**

```java
package com.cretas.aims.dto.processentry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/** 文员逐道录入负载: 一条生产链(多个半成品批 + 1 成品批)。Spec §4. */
@Data
public class ProcessChainEntryRequest {

    @NotBlank
    private String idempotencyKey;

    /** 链中各批次(顺序: 上游半成品批在前, 成品批在后)。 */
    @NotNull
    private List<BatchEntry> batches;

    @Data
    public static class BatchEntry {
        /** 客户端分配的链内引用键(混锅来源用它指上游), 如 "焯水0613"。 */
        @NotBlank
        private String clientBatchKey;
        @NotBlank
        private String productTypeId;
        /** 可空: 系统生成 batchNumber。 */
        private String batchNumber;
        /** true=成品批(熟制→气调→包装); false=半成品批(原料→焯水)。 */
        private boolean finished;
        @NotNull
        private List<StepEntry> steps;
    }

    @Data
    public static class StepEntry {
        @NotNull
        private Integer processOrder;
        private String processName;
        /** 成本桶: RAW_MATERIAL | SEASONING | PACKAGING | null(普通工序) */
        private String processCategory;
        private BigDecimal inputQuantity;
        private BigDecimal outputQuantity;
        private String unit;                 // 默认 "kg"
        // 人工(起止+人数 → 工时)
        private String laborStartTime;       // "HH:mm"
        private String laborEndTime;
        private Integer workerCount;
        // 产出附加
        private List<Byproduct> byproducts;
        private BigDecimal wasteQuantity;
        private Integer sampleRetainQuantity;
        // 领料(首道): 消耗的原料 MaterialBatch
        private List<RawInput> rawMaterialInputs;
        // 熟制(混锅 + 调料)
        private Integer potCount;            // 锅数 N
        private List<BigDecimal> potRawKgs;  // 逐锅原料(N>1 必填)
        private List<UpstreamSource> upstreamSources; // 混锅来源
    }

    @Data
    public static class Byproduct {
        private String name;
        private BigDecimal quantity;
        private String unit;
        private BigDecimal unitPrice;        // 可空(无单价→不冲减)
    }

    @Data
    public static class RawInput {
        @NotBlank
        private String materialBatchId;      // 原料 MaterialBatch.id
        @NotNull
        private BigDecimal quantity;
    }

    @Data
    public static class UpstreamSource {
        /** 指向同负载里另一个 BatchEntry.clientBatchKey。 */
        @NotBlank
        private String sourceClientBatchKey;
        @NotNull
        private BigDecimal feedQuantityKg;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessChainEntryRequest.java
git commit -m "feat(sp-b1): ProcessChainEntryRequest 逐道录入 API 契约" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessChainEntryRequest.java
```

---

### Task 2: traceCost 按消耗比例分摊（KEYSTONE 修正 — 防共享上游双重计数）

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java`（`traceCost` 方法）
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/TraceCostApportionTest.java`

- [ ] **Step 1: 读现有 traceCost** —— 打开文件定位 `private BigDecimal[] traceCost(...)`，确认现有返回 `sum>0?sum:own` 与递归 `findByProductionBatchIdAndFactoryId(upstreamBatchId)`。记下 `MaterialBatch` 是否有 `receiptQuantity`（上游批总产出量，用于算比例）。

- [ ] **Step 2: 写失败测试（diamond: 1 父→2 子→各取一半, 父成本只计一次）**

```java
package com.cretas.aims.service.yield;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("traceCost 按消耗比例分摊")
class TraceCostApportionTest {

    private static final String F = "DEMO_FACTORY";

    @Mock MaterialConsumptionRepository consumptionRepo;
    @Mock MaterialBatchRepository materialBatchRepo;

    OrderCostBreakdownService svc;

    @BeforeEach
    void setUp() {
        // 仅注入这两个 repo 给纯算法路径; 其余依赖按构造器传 null/mock(见实现构造器)
        svc = OrderCostBreakdownService.forTraceTest(consumptionRepo, materialBatchRepo);
    }

    private MaterialConsumption cons(String batchId, String total) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(F);
        c.setBatchId(batchId);
        c.setTotalCost(new BigDecimal(total));
        return c;
    }

    private MaterialBatch wipBatch(String id, long srcProdBatchId, String receiptQty) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setFactoryId(F);
        mb.setSourceDocType("PRODUCTION_BATCH");
        mb.setSourceDocId(String.valueOf(srcProdBatchId));
        mb.setReceiptQuantity(new BigDecimal(receiptQty));
        return mb;
    }

    @Test
    @DisplayName("两个下游各消耗父批一半 → 父成本(¥600)不被双计, 各得¥300")
    void diamond_noDoubleCount() {
        // 父 WIP 批 id=100, 产出 100kg, 自身原料消耗 ¥600 (一条叶子消耗)
        MaterialConsumption parentRaw = cons("MB-RAW", "600"); // 叶子(MB-RAW 非 PRODUCTION_BATCH)
        lenient().when(materialBatchRepo.findByIdAndFactoryId(eq("MB-RAW"), eq(F)))
                .thenReturn(Optional.of(new MaterialBatch())); // sourceDocType null → 叶子
        lenient().when(consumptionRepo.findByProductionBatchIdAndFactoryId(eq(100L), eq(F)))
                .thenReturn(List.of(parentRaw));

        // 两个 WIP MaterialBatch 都指向父批 100, receiptQuantity=100(父总产出)
        lenient().when(materialBatchRepo.findByIdAndFactoryId(eq("MB-A"), eq(F)))
                .thenReturn(Optional.of(wipBatch("MB-A", 100L, "100")));
        lenient().when(materialBatchRepo.findByIdAndFactoryId(eq("MB-B"), eq(F)))
                .thenReturn(Optional.of(wipBatch("MB-B", 100L, "100")));

        // 下游 A 消耗 50kg, 下游 B 消耗 50kg
        BigDecimal a = svc.traceCostForTest(F, cons("MB-A", "300").withConsumedQty(new BigDecimal("50")));
        BigDecimal b = svc.traceCostForTest(F, cons("MB-B", "300").withConsumedQty(new BigDecimal("50")));

        // 各取父成本的 50/100 = ¥300; 合计 ¥600 = 父成本(不双计)
        assertEquals(0, new BigDecimal("300.00").compareTo(a));
        assertEquals(0, new BigDecimal("300.00").compareTo(b));
    }
}
```

> 注：上面的 `forTraceTest` / `traceCostForTest` / `withConsumedQty` 是为可测性加的轻量 hook（见 Step 3）。若实现者觉得用全 `@SpringBootTest` 跑真实 `compute()` 更稳，可改写为集成测，但**必须断言「共享上游不双计」**这一行为。

- [ ] **Step 3: 改 traceCost — 按 `consumedQty / upstreamReceiptQty` 分摊上游 sum**

打开 `OrderCostBreakdownService.traceCost`，把递归返回从「整额 sum」改为「按比例分摊」：

```java
// 现状(伪): return new BigDecimal[]{ sum.signum()>0 ? sum : own, depth };
// 改为: 上游 sum 按本次消耗占上游总产出的比例缩放
MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
    return new BigDecimal[]{ own, BigDecimal.valueOf(depth) };   // 叶子: 用消耗自身 totalCost
}
Long upstreamBatchId = parseLong(mb.getSourceDocId());
if (upstreamBatchId == null || !visited.add(upstreamBatchId)) {       // 环保护
    return new BigDecimal[]{ own, BigDecimal.valueOf(depth) };
}
List<MaterialConsumption> up = consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId);
BigDecimal upstreamSum = BigDecimal.ZERO;
for (MaterialConsumption u : up) {
    upstreamSum = upstreamSum.add(traceCost(factoryId, u, depth + 1, visited)[0]);
}
// ★ 按比例分摊: 本次消耗量 / 上游批总产出量。缺任一 → 退回 own(消耗自身切片, 已是写入时分摊值)
BigDecimal consumedQty = nz(c.getQuantity());
BigDecimal upstreamQty = nz(mb.getReceiptQuantity());
BigDecimal apportioned;
if (upstreamSum.signum() > 0 && consumedQty.signum() > 0 && upstreamQty.signum() > 0) {
    apportioned = upstreamSum.multiply(consumedQty)
            .divide(upstreamQty, 4, java.math.RoundingMode.HALF_UP);
} else {
    apportioned = own;   // 向后兼容: 1:1 全量消耗时 consumedQty==upstreamQty → 比例=1 ≈ upstreamSum; 缺量则用切片
}
return new BigDecimal[]{ apportioned.signum() > 0 ? apportioned : own, BigDecimal.valueOf(depth) };
```

向后兼容论证（写进代码注释）：既有单链 1:1 消耗时 `consumedQty == upstreamReceiptQty` → 比例=1 → `apportioned == upstreamSum`（与改前一致）；只有「部分消耗 / 共享上游」才缩放，正是要修的双计场景。`own` 作为缺量兜底（消耗 totalCost 已是写入时 qty×单价 的切片）。

为可测性，加 package-private 测试 hook（不污染生产 API）：
```java
// 测试可见构造器 + 直调封装(仅测 traceCost 路径)
static OrderCostBreakdownService forTraceTest(MaterialConsumptionRepository cRepo, MaterialBatchRepository mbRepo) {
    OrderCostBreakdownService s = new OrderCostBreakdownService(/* 其余依赖传 null, 见主构造器允许 */);
    s.consumptionRepository = cRepo; s.materialBatchRepository = mbRepo; return s;
}
BigDecimal traceCostForTest(String f, MaterialConsumption c) {
    return traceCost(f, c, 0, new java.util.HashSet<>())[0];
}
```
> 若主构造器不便传 null，实现者改用 `@SpringBootTest` 集成测验证同等行为（见 Step 2 注），删掉 hook。**关键是行为被测到，不拘形式。**

- [ ] **Step 4: 跑测试** `cd backend/java/cretas-api && mvn -q test -Dtest=TraceCostApportionTest` → PASS。
- [ ] **Step 5: 跑既有 cost 相关测试确认无回归** `mvn -q test -Dtest=*CostBreakdown*,*OrderCost*` → PASS（1:1 链不变）。
- [ ] **Step 6: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/TraceCostApportionTest.java
git commit -m "fix(sp-b1): traceCost 按消耗比例分摊上游成本 (防共享上游双重计数, 1:1 向后兼容)" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/TraceCostApportionTest.java
```

---

### Task 3: ClerkProcessEntryService — 物化编排（KEYSTONE）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/ClerkProcessEntryService.java`（接口）
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessChainEntryResult.java`

- [ ] **Step 1: 写结果 DTO**

```java
package com.cretas.aims.dto.processentry;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ProcessChainEntryResult {
    private boolean idempotentReplay;            // true=命中幂等缓存
    /** clientBatchKey → 生成的 ProductionBatch.id */
    private Map<String, Long> batchIdsByKey;
    /** clientBatchKey → 生成的 batchNumber */
    private Map<String, String> batchNumbersByKey;
    private String finishedBatchNumber;          // 成品批号(供 SP-C 按批次号查核算)
    private int reportsWritten;
    private int consumptionsWritten;
    private List<String> warnings;
}
```

- [ ] **Step 2: 写接口**

```java
package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;

public interface ClerkProcessEntryService {
    /**
     * 文员逐道录入一条生产链 + 物化成本图。幂等(idempotencyKey)。
     * @param operatorId 文员 token userId (禁用 SecurityUtils)
     */
    ProcessChainEntryResult recordChain(String factoryId, String planId,
                                        ProcessChainEntryRequest req, Long operatorId);
}
```

- [ ] **Step 3: 写失败测试（先定行为 — 见 Task 4，TDD 顺序: 先 Task 4 的测试，再回填本 impl）**

> 本 impl 的验证测试在 Task 4（集成测，验真实成本）。这里先写最小骨架让 Task 4 测试可编译运行。

- [ ] **Step 4: 写 impl（物化算法）**

```java
package com.cretas.aims.service.processentry.impl;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.*;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.recipe.RecipeCostCalculator;
import com.cretas.aims.service.recipe.SeasoningCost;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 文员逐道录入物化编排。Spec §4.5 / §5.2.
 * 成本图: 每条边 = 投料kg × 上游单价, 写入时分摊(防双计, 配合 Task 2 traceCost)。
 * 每个半成品批产出 → MaterialBatch(source_doc_type=PRODUCTION_BATCH) 供下游 + traceCost 回溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClerkProcessEntryServiceImpl implements ClerkProcessEntryService {

    private static final BigDecimal LABOR_DEFAULT_RATE = new BigDecimal("26"); // 兜底; 优先读 factory_cost_settings(SP-C)

    private final MaterialConsumptionRepository consumptionRepo;
    private final MaterialBatchRepository materialBatchRepo;
    private final ProductRecipeRepository recipeRepo;
    private final RecipeIngredientRepository ingredientRepo;
    // 复用现有报工 + 批次生命周期 (注入既有 service)
    private final com.cretas.aims.service.yield.YieldReportService yieldReportService;
    private final BatchLifecycleSupport batchSupport; // 见 Step 5 — 封装"建批次+取批号"

    @Override
    @Transactional
    public ProcessChainEntryResult recordChain(String factoryId, String planId,
                                               ProcessChainEntryRequest req, Long operatorId) {
        if (operatorId == null) throw new BusinessException(401, "未登录, 无法录入报工");
        // 幂等: 同 plan+key 已录 → 返回缓存(用 production_reports 上的 idempotency 标记或专表; 见 Step 6)
        Optional<ProcessChainEntryResult> replay = batchSupport.findReplay(factoryId, planId, req.getIdempotencyKey());
        if (replay.isPresent()) { replay.get().setIdempotentReplay(true); return replay.get(); }

        // 拓扑序: 半成品批(finished=false) 先于 成品批, 保证混锅来源已物化
        List<BatchEntry> ordered = new ArrayList<>(req.getBatches());
        ordered.sort(Comparator.comparing(BatchEntry::isFinished)); // false 在前

        Map<String, Long> batchIds = new HashMap<>();
        Map<String, String> batchNumbers = new HashMap<>();
        Map<String, BigDecimal> batchTotalCost = new HashMap<>();   // clientKey → 该批累计成本
        Map<String, BigDecimal> batchOutputQty = new HashMap<>();   // clientKey → 末道产出量
        Map<String, String> wipMaterialBatchId = new HashMap<>();   // clientKey → 该批产出的 MaterialBatch.id
        int reports = 0, consumptions = 0;
        String finishedBatchNo = null;

        for (BatchEntry be : ordered) {
            // 1. 建/取 ProductionBatch
            BatchLifecycleSupport.NewBatch nb = batchSupport.ensureBatch(factoryId, planId, be, operatorId);
            batchIds.put(be.getClientBatchKey(), nb.id());
            batchNumbers.put(be.getClientBatchKey(), nb.batchNumber());
            BigDecimal cost = BigDecimal.ZERO, lastOutput = BigDecimal.ZERO;

            for (StepEntry st : be.getSteps()) {
                // 2. 复用 submitReport 写报工事实(INPUT/SEGMENT/OUTPUT)
                BigDecimal laborCost = computeLaborCost(st);
                reports += batchSupport.submitStepReports(factoryId, nb.id(), operatorId, st, laborCost);
                cost = cost.add(laborCost);
                if (st.getOutputQuantity() != null) lastOutput = st.getOutputQuantity();

                // 3. 物化成本边
                // 3a. 原料(首道领料)
                if (st.getRawMaterialInputs() != null) {
                    for (RawInput ri : st.getRawMaterialInputs()) {
                        MaterialBatch rawMb = materialBatchRepo.findByIdAndFactoryId(ri.getMaterialBatchId(), factoryId)
                                .orElseThrow(() -> new BusinessException(404, "原料批次不存在: " + ri.getMaterialBatchId()));
                        BigDecimal lineCost = nz(rawMb.getUnitPrice()).multiply(nz(ri.getQuantity()))
                                .setScale(2, RoundingMode.HALF_UP);
                        writeConsumption(factoryId, planId, nb.id(), rawMb.getId(), rawMb.getMaterialTypeId(),
                                ri.getQuantity(), nz(rawMb.getUnitPrice()), lineCost, "RAW_MATERIAL", operatorId);
                        cost = cost.add(lineCost); consumptions++;
                    }
                }
                // 3b. 调料(熟制): SP-A 配方算
                if ("SEASONING".equals(st.getProcessCategory()) || st.getPotCount() != null) {
                    BigDecimal seasoning = computeSeasoning(factoryId, be.getProductTypeId(), st);
                    if (seasoning.signum() > 0) {
                        writeConsumption(factoryId, planId, nb.id(), null, null,
                                BigDecimal.ZERO, BigDecimal.ZERO, seasoning, "SEASONING", operatorId);
                        cost = cost.add(seasoning); consumptions++;
                    }
                }
                // 3c. 混锅来源: 每条边 = feedKg × 上游单价
                if (st.getUpstreamSources() != null) {
                    for (UpstreamSource us : st.getUpstreamSources()) {
                        String srcKey = us.getSourceClientBatchKey();
                        String srcMbId = wipMaterialBatchId.get(srcKey);
                        if (srcMbId == null) throw new BusinessException(400,
                                "混锅来源未先录入: " + srcKey);
                        MaterialBatch srcMb = materialBatchRepo.findByIdAndFactoryId(srcMbId, factoryId).orElseThrow();
                        BigDecimal edgeCost = nz(srcMb.getUnitPrice()).multiply(nz(us.getFeedQuantityKg()))
                                .setScale(2, RoundingMode.HALF_UP);
                        writeConsumption(factoryId, planId, nb.id(), srcMbId, srcMb.getMaterialTypeId(),
                                us.getFeedQuantityKg(), nz(srcMb.getUnitPrice()), edgeCost, "SEMI_FINISHED", operatorId);
                        cost = cost.add(edgeCost); consumptions++;
                    }
                }
            }

            batchTotalCost.put(be.getClientBatchKey(), cost);
            batchOutputQty.put(be.getClientBatchKey(), lastOutput);

            // 4. 半成品批 → 产出转 MaterialBatch(priced, source_doc_type=PRODUCTION_BATCH) 供下游消耗
            if (!be.isFinished() && lastOutput.signum() > 0) {
                BigDecimal unitPrice = cost.divide(lastOutput, 4, RoundingMode.HALF_UP);
                String mbId = batchSupport.materializeWipBatch(factoryId, nb, be.getProductTypeId(),
                        lastOutput, unitPrice, operatorId);
                wipMaterialBatchId.put(be.getClientBatchKey(), mbId);
            }
            if (be.isFinished()) finishedBatchNo = nb.batchNumber();
        }

        ProcessChainEntryResult result = new ProcessChainEntryResult();
        result.setBatchIdsByKey(batchIds);
        result.setBatchNumbersByKey(batchNumbers);
        result.setFinishedBatchNumber(finishedBatchNo);
        result.setReportsWritten(reports);
        result.setConsumptionsWritten(consumptions);
        result.setWarnings(new ArrayList<>());
        batchSupport.recordReplay(factoryId, planId, req.getIdempotencyKey(), result);
        return result;
    }

    private BigDecimal computeLaborCost(StepEntry st) {
        if (st.getLaborStartTime() == null || st.getLaborEndTime() == null || st.getWorkerCount() == null)
            return BigDecimal.ZERO;
        int minutes = batchSupport.minutesBetween(st.getLaborStartTime(), st.getLaborEndTime());
        BigDecimal hours = new BigDecimal(minutes).divide(new BigDecimal("60"), 4, RoundingMode.HALF_UP);
        BigDecimal workHours = hours.multiply(new BigDecimal(st.getWorkerCount()));
        return workHours.multiply(LABOR_DEFAULT_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeSeasoning(String factoryId, String productTypeId, StepEntry st) {
        Optional<ProductRecipe> r = recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(factoryId, productTypeId, "ACTIVE");
        if (r.isEmpty()) return BigDecimal.ZERO;
        List<RecipeIngredient> ings = ingredientRepo.findByRecipeIdOrderBySeqAsc(r.get().getId());
        BigDecimal injectionRawKg = nz(st.getInputQuantity());     // 注射 R = 熟制投入(近似; 真注射在滚揉, SP-C 可精化)
        List<BigDecimal> potRawKgs = (st.getPotRawKgs() != null && !st.getPotRawKgs().isEmpty())
                ? st.getPotRawKgs()
                : equalSplit(nz(st.getInputQuantity()), st.getPotCount() == null ? 1 : st.getPotCount());
        SeasoningCost sc = RecipeCostCalculator.compute(r.get(), ings, injectionRawKg, potRawKgs);
        return sc.getTotal();
    }

    private List<BigDecimal> equalSplit(BigDecimal total, int n) {
        if (n <= 0) n = 1;
        BigDecimal each = total.divide(new BigDecimal(n), 4, RoundingMode.HALF_UP);
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(each);
        return out;
    }

    private void writeConsumption(String factoryId, String planId, Long batchId, String upstreamBatchId,
                                  String materialTypeId, BigDecimal qty, BigDecimal unitPrice,
                                  BigDecimal totalCost, String sourceType, Long operatorId) {
        MaterialConsumption c = new MaterialConsumption();
        c.setFactoryId(factoryId);
        c.setProductionPlanId(planId);
        c.setProductionBatchId(batchId);
        c.setBatchId(upstreamBatchId);
        c.setMaterialTypeId(materialTypeId);
        c.setQuantity(qty);
        c.setUnitPrice(unitPrice);
        c.setTotalCost(totalCost);
        c.setSourceType(sourceType);
        c.setConsumedAt(LocalDateTime.now());
        c.setConsumptionTime(LocalDateTime.now());
        c.setRecordedBy(operatorId);
        consumptionRepo.save(c);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
```

- [ ] **Step 5: 写 `BatchLifecycleSupport`（封装批次生命周期 + 报工 + 幂等存储，复用既有 service）**

`Create: backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/BatchLifecycleSupport.java`

职责（实现者按既有代码接线，每个方法都有明确契约）：
- `NewBatch ensureBatch(factoryId, planId, BatchEntry, operatorId)` — 复用 `ProcessingServiceImpl.startProduction`/`ProductionBatch` 创建逻辑建批次, 返回 `record NewBatch(Long id, String batchNumber)`。若 `BatchEntry.batchNumber` 提供则用之, 否则系统生成(沿用既有 batchNumber 生成器)。
- `int submitStepReports(factoryId, batchId, operatorId, StepEntry, BigDecimal laborCost)` — 构造 `YieldReportRequest`(INPUT: rawMaterialInputs→materialBatchRefs; SEGMENT: laborSegments; OUTPUT: outputQuantity+byproducts+waste+sampleRetain; reportKind 逐阶段显式设置), 调 `yieldReportService.submitReport(factoryId, batchId, operatorId, req)`。返回写入 report 数。
- `String materializeWipBatch(factoryId, NewBatch, productTypeId, outputQty, unitPrice, operatorId)` — 建 `MaterialBatch`(id=UUID, batchNumber=NewBatch.batchNumber, materialTypeId=productTypeId, **sourceDocType="PRODUCTION_BATCH", sourceDocId=String.valueOf(NewBatch.id)**, receiptQuantity=outputQty, reservedQuantity=0, usedQuantity=0, quantityUnit="kg", unitPrice=unitPrice, status=AVAILABLE, warehouseId=默认仓 or null-allowed, createdBy=operatorId, inboundDate=now), save, 返回 id。
- `int minutesBetween(String hhmmStart, String hhmmEnd)` — 解析 "HH:mm" 算分钟差(跨日按 +24h 容错)。
- `Optional<ProcessChainEntryResult> findReplay(...)` / `void recordReplay(...)` — 幂等: 用 `production_settlements` 既有幂等表的范式, 或一张轻量 `process_entry_idempotency`(若无现成表, 加 Flyway `V20261027_03`)。MVP: 存 idempotencyKey→序列化 result(JSONB) per (factoryId, planId)。

> ⚠️ 实现者: `ensureBatch`/批号生成器/`startProduction` 的真实签名以 `ProcessingServiceImpl` 为准(grep `startProduction`、`generateBatchNumber`)。`MaterialBatchStatus.AVAILABLE` 枚举值以实际枚举为准。warehouseId 若 NOT NULL 约束, 用计划默认仓或链上首个原料批的仓。

- [ ] **Step 6: 幂等存储**（若用新表，加 `V20261027_03__process_entry_idempotency.sql`: id/factory_id/plan_id/idempotency_key(uniq)/result_json jsonb/created_at；否则复用既有机制）。

- [ ] **Step 7: 提交（service + support + result dto，按编译通过分步 commit）**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/ backend/java/cretas-api/src/main/java/com/cretas/aims/dto/processentry/ProcessChainEntryResult.java
git commit -m "feat(sp-b1): ClerkProcessEntryService 物化编排 (成本边/半成品批/幂等)" -- <上述 paths>
```

---

### Task 4: 集成测试 — 真实成本验证（diamond + 65.7:34.3 + 幂等 + 租户）

**Files:**
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ClerkProcessEntryIntegrationTest.java`（`@SpringBootTest`，H2）

- [ ] **Step 1: 写集成测**（核心断言，覆盖 spec §6）

```java
// @SpringBootTest + @ActiveProfiles("test") + @Transactional
// 注入 ClerkProcessEntryService + OrderCostBreakdownService + repos + 建一个 ACTIVE ProductRecipe(注射0.24/kg, 熟制0.31/kg)
//
// 用例 1 — 混锅 65.7:34.3:
//   录入: 焯水A 批(原料100kg@¥71.70 → 产出78kg) + 焯水B 批(原料? → 产出22kg, 单价¥170)
//   + 熟制成品批(混锅: A 投78kg, B 投22kg; 锅数1)
//   断言: OrderCostBreakdownService/getBatchYieldSummary 算出原料成本拆分 A:B ≈ 65.7%:34.3%
//
// 用例 2 — diamond 不双计:
//   焯水A(产出100kg, 成本¥600) → 两个熟制锅各吃50kg
//   断言: 两锅原料成本合计 = ¥600 (不是 ¥1200)
//
// 用例 3 — 幂等:
//   同 idempotencyKey 调 recordChain 两次 → 第二次 idempotentReplay=true, consumptions 不翻倍
//
// 用例 4 — 跨租户:
//   factoryId=DEMO_FACTORY 录入, 用 OTHER_FACTORY 查 → 查不到/404
//
// 用例 5 — recordedBy 非 null (C-B1 回归): 物化的 MaterialConsumption.recordedBy == operatorId
```

实现者按上面 5 个用例写出完整 @Test 方法（构造最小 fixture：ProductRecipe + 原料 MaterialBatch + plan）。每个用例独立断言。

- [ ] **Step 2: 跑** `mvn -q test -Dtest=ClerkProcessEntryIntegrationTest` → 5 用例全绿。修 impl 直到通过（TDD: 此测试驱动 Task 3 impl 收敛）。
- [ ] **Step 3: 提交**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ClerkProcessEntryIntegrationTest.java
git commit -m "test(sp-b1): 物化集成测 — 混锅65.7:34.3/diamond不双计/幂等/租户/recordedBy" -- backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/ClerkProcessEntryIntegrationTest.java
```

---

### Task 5: Controller 端点

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ClerkProcessEntryController.java`

- [ ] **Step 1: 写 Controller**

```java
package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;
import com.cretas.aims.service.MobileService; // 复用既有 getUserFromToken
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans/{planId}/process-entry")
@RequiredArgsConstructor
public class ClerkProcessEntryController {

    private final ClerkProcessEntryService service;

    @RequirePermission({"production:read_write"})
    @PostMapping
    public ApiResponse<ProcessChainEntryResult> record(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ProcessChainEntryRequest request) {
        return ApiResponse.success("逐道录入成功", service.recordChain(factoryId, planId, request, userId));
    }
}
```
> ⚠️ `@RequestAttribute("userId")` 是 YieldReportController 取 token userId 的范式(已验)。`@RequirePermission` import 包以 `ManufacturerRegistryController` 为准。

- [ ] **Step 2: 全量后端测试** `cd backend/java/cretas-api && mvn -q clean test` → BUILD SUCCESS，无回归。
- [ ] **Step 3: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ClerkProcessEntryController.java
git commit -m "feat(sp-b1): ClerkProcessEntryController 逐道录入端点" -- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ClerkProcessEntryController.java
```

---

## 验收与交接
- [ ] `mvn -q clean test` 绿（含 TraceCostApportionTest + ClerkProcessEntryIntegrationTest 5 用例）。
- [ ] `git diff origin/main...HEAD --stat` scope 干净（仅 processentry/ + traceCost + 可能 1 迁移）。
- [ ] **🔒 Opus 终审**：Task 2 改了既有成本引擎 `traceCost`（影响现有订单成本）→ 必须 Opus 终审确认 1:1 向后兼容 + 既有 cost 测试无回归，再合 main。
- [ ] **API 契约交 SP-B2**：`ProcessChainEntryRequest`/`Result` + 端点 `POST /{factoryId}/production-plans/{planId}/process-entry` 是 B2 前端要消费的契约。
- [ ] 不碰 F006/LIUSHANMEN；验证用 DEMO_FACTORY。

## Self-Review
- **Spec 覆盖**：§4.5 物化(MaterialConsumption/MaterialBatch)→Task3；§5.2 成本边 qty×单价→Task3 writeConsumption；C2 防双计→Task2 traceCost 分摊+diamond 测；幂等→Task3 Step5/6；recordedBy→writeConsumption operatorId。✅
- **类型一致**：`ProcessChainEntryRequest` 嵌套类 ↔ impl 解构 ↔ Result ↔ Controller。`recordChain(factoryId,planId,req,operatorId)` 贯穿。✅
- **No placeholder 例外（诚实标注）**：Task3 Step5 `BatchLifecycleSupport` 的 `ensureBatch`/批号生成/`materializeWipBatch` 给了**字段级契约 + 复用指向**，但未贴最终代码 —— 因为它包装的既有 `startProduction`/批号生成器签名需实现者对 origin/main 现场确认（防贴错签名）。这是**有意的 grounding 委托**，非偷懒：每个方法的输入/输出/要 set 的字段都已写死。实现者第一步应 grep 这些既有方法签名。Task4 集成测会钉死整体行为正确性。
- **YAGNI**：B1 不含前端、不含 SP-C 脱敏/双模式查询/工时单价配置表（工时单价用 ¥26 兜底常量, SP-C 再接 factory_cost_settings）。
