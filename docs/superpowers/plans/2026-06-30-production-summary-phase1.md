# 库存生产「阅读汇总」(Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给生产计划加一个只读「阅读汇总」端点+前端视图,聚合 总投入原料 / 总产出成品 / 剩余半成品折算 / 真实总出成率 / 总成本,让客户看到一个(长期挂着的)库存生产计划到底产出了什么。

**Architecture:** 纯读侧聚合。新 `ProductionSummaryService` 复用已有 `ProcessSheetService.getInventoryYieldCard`(plan-scoped 各道 WIP 行)+ `ProductionBatchRepository.findByFactoryIdAndProductionPlanId`(成品批)+ `OrderCostBreakdownService.computeByBatch`(逐成品批成本)。不写任何库存/成本/历史,不改 ProductionBatch.planId(陷阱 1)。成本字段走 `priceMaskResolver` 脱敏(陷阱 4)。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA;web-admin Vue 3 + Element Plus。

**前置阅读(执行者必看)**:`docs/plans/2026-06-30-inventory-production-line-spec.md`(Phase 1 节 + 顶部 5 个 🔒 陷阱)、`docs/audits/2026-06-30-production-inventory-cost-state.md`。

---

## ⚠️ 一个业务规则必须先和客户确认(阻塞 Task 3)

**剩余半成品"折算"口径** —— 客户原话"半成品折成多少成品或原料,扣掉以后算真实出成率"(转录 line 39)。两种等价 formula,需客户拍板:

- **方案 R(推荐,可用现有字段)**:把剩余 WIP 折回**原料当量**,从总投入扣除 →
  `真实总出成率 = 总产出成品当量 / (总投入原料 − Σ 剩余半成品折回原料当量)`,其中 `剩余折回原料 = Σ remaining_p ÷ (cumulativeYieldRate_p/100)`(`cumulativeYieldRate` 已存在于 yield-card 每行 = 该道产出/首道投入)。
- **方案 F**:把剩余 WIP 折成**成品当量**累加进分子 → 需 stage→finished 下游出成率(无现成字段,要用 `/bom/yield-estimate` 历史出成率)。

**Task 3 实现把折算逻辑隔离成单方法 `foldRemainingToRawEquiv()`,默认走方案 R;若客户要方案 F 只换这一个方法。** 先按 R 建,plan 末尾标 follow-up 跟客户确认。

---

## File Structure

- **Create** `dto/yield/ProductionSummaryDTO.java` — 汇总响应(五量 + 各批明细)。
- **Create** `service/yield/ProductionSummaryService.java` — 聚合逻辑(keystone)。
- **Create** `service/yield/ProductionSummaryServiceTest.java` — 聚合数学单测(@ExtendWith Mockito,mock 三个数据源)。
- **Modify** `controller/ProductionPlanController.java` — 加 `GET /{planId}/production-summary`(复用 `priceMaskResolver`)。
- **Modify** `repository/ProductionBatchRepository.java:46-53` — 统一 `findByFactoryId`(排 CLERK_WIP)vs `findByFactoryIdAndStatus`(不排)口径矛盾(附带修)。
- **Create** `web-admin/src/views/production/components/ProductionSummaryDialog.vue` — 阅读汇总弹窗。
- **Modify** `web-admin/src/views/production/plans/list.vue` — 加"阅读汇总"按钮入口。
- **Modify** `web-admin/src/api/productionPlan.ts`(或对应 api 文件)— 加 `getProductionSummary`。

---

## Task 1: ProductionSummaryDTO + 总投入/总产出 聚合 (keystone)

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/ProductionSummaryDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/ProductionSummaryService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/ProductionSummaryServiceTest.java`

- [ ] **Step 1: 写 DTO**

```java
package com.cretas.aims.dto.yield;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ProductionSummaryDTO {
    private String planId;
    private String planNumber;
    private String productTypeId;
    private String productName;
    private BigDecimal totalRawInput;            // 总投入原料 (首道原料投入 Σ, 跨批)
    private BigDecimal totalFinishedOutput;      // 总产出成品 (CLK-B Σ)
    private BigDecimal remainingSemiRawEquiv;    // 剩余半成品折回原料当量 (方案 R)
    private BigDecimal realYieldRate;            // 真实总出成率 % = 成品 / (投入 − 剩余折回)
    private BigDecimal totalCost;                // Σ computeByBatch (脱敏可 null)
    private boolean priceMasked;
    private List<BatchLine> batches;             // 各批明细

    @Data
    @Builder
    public static class BatchLine {
        private String batchNumber;
        private Integer processOrder;
        private String processName;
        private BigDecimal produced;
        private BigDecimal remaining;
        private String status;                   // COMPLETED(成品) / 其它(WIP)
        private BigDecimal cumulativeYieldRate;
    }
}
```

- [ ] **Step 2: 写 service 骨架 + 总投入/总产出 聚合(先不做折算/成本)**

> `ProcessSheetInventoryItem` 字段(已核实):`batchNumber, produced, used, remaining, status, inputQuantity, processOrder, processName, unit, stepYieldRate, cumulativeYieldRate`。首道 = 最小 processOrder;成品行 `status=="COMPLETED"`。

```java
package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductionSummaryService {
    private final ProcessSheetService processSheetService;
    private final ProductionBatchRepository productionBatchRepository;
    private final OrderCostBreakdownService orderCostBreakdownService;

    public ProductionSummaryDTO computeSummary(String factoryId, String planId, boolean maskPrice) {
        List<ProcessSheetInventoryItem> items = processSheetService.getInventoryYieldCard(factoryId, planId);

        int minOrder = items.stream()
                .filter(i -> i.getProcessOrder() != null)
                .map(ProcessSheetInventoryItem::getProcessOrder)
                .min(Comparator.naturalOrder()).orElse(0);

        // 总投入原料 = Σ 首道(最小 processOrder)各批 inputQuantity (跨批 Σ, 非单批)
        BigDecimal totalRawInput = items.stream()
                .filter(i -> i.getProcessOrder() != null && i.getProcessOrder() == minOrder)
                .map(i -> nz(i.getInputQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 总产出成品 = Σ CLK-B(REGULAR)批 quantity (plan-scoped)
        List<ProductionBatch> planBatches =
                productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        BigDecimal totalFinishedOutput = planBatches.stream()
                .filter(b -> "REGULAR".equals(b.getBatchType()))
                .map(b -> nz(b.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ProductionSummaryDTO.builder()
                .planId(planId)
                .totalRawInput(totalRawInput)
                .totalFinishedOutput(totalFinishedOutput)
                .priceMasked(maskPrice)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
```

- [ ] **Step 3: 写失败单测(总投入/总产出)**

```java
package com.cretas.aims.service.yield;

import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.yield.ProductionSummaryDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionSummaryServiceTest {
    @Mock ProcessSheetService processSheetService;
    @Mock ProductionBatchRepository productionBatchRepository;
    @Mock OrderCostBreakdownService orderCostBreakdownService;
    @InjectMocks ProductionSummaryService service;

    private ProcessSheetInventoryItem item(int order, String status, BigDecimal input,
                                           BigDecimal produced, BigDecimal remaining, BigDecimal cumYield) {
        ProcessSheetInventoryItem i = new ProcessSheetInventoryItem();
        i.setProcessOrder(order); i.setStatus(status); i.setInputQuantity(input);
        i.setProduced(produced); i.setRemaining(remaining); i.setCumulativeYieldRate(cumYield);
        return i;
    }
    private ProductionBatch clkB(BigDecimal qty) {
        ProductionBatch b = new ProductionBatch(); b.setBatchType("REGULAR"); b.setQuantity(qty); return b;
    }

    @Test
    void totalRawInput_sumsFirstProcessAcrossBatches() {
        when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of(
                item(1, "IN_PROGRESS", new BigDecimal("2.0"), new BigDecimal("1.8"), new BigDecimal("0.2"), null),
                item(1, "IN_PROGRESS", new BigDecimal("3.0"), new BigDecimal("2.7"), new BigDecimal("0.3"), null), // 第二批首道
                item(2, "IN_PROGRESS", new BigDecimal("1.8"), new BigDecimal("1.6"), new BigDecimal("1.6"), new BigDecimal("90"))
        ));
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
                .thenReturn(List.of(clkB(new BigDecimal("1.5")), clkB(new BigDecimal("0.5"))));

        ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

        assertThat(dto.getTotalRawInput()).isEqualByComparingTo("5.0");      // 2.0 + 3.0 (跨批首道)
        assertThat(dto.getTotalFinishedOutput()).isEqualByComparingTo("2.0"); // 1.5 + 0.5
    }
}
```

- [ ] **Step 4: 跑测,确认从 RED→GREEN**

Run: `cd backend/java/cretas-api && mvn -q -o test -Dtest=ProductionSummaryServiceTest`
Expected: PASS(上面 service 已实现总投入/总产出)。若 ProcessSheetInventoryItem 无 setter,改用 builder(核实该类 @Data/@Builder)。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/dto/yield/ProductionSummaryDTO.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/ProductionSummaryService.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/ProductionSummaryServiceTest.java
git commit -m "feat(production-summary): 总投入/总产出 跨批聚合 + 单测"
```

---

## Task 2: 剩余半成品折回原料当量 + 真实总出成率 (方案 R)

**Files:**
- Modify: `service/yield/ProductionSummaryService.java`
- Test: `service/yield/ProductionSummaryServiceTest.java`

- [ ] **Step 1: 写失败单测(折算 + 真实出成率)**

```java
@Test
void realYield_subtractsRemainingFoldedBackToRaw() {
    // 首道投入 10; 第2道(WIP)剩余 0.9, cumulativeYieldRate=90% → 折回原料 = 0.9/0.9 = 1.0
    // 成品 8.0; 真实出成率 = 8.0 / (10 − 1.0) = 88.89%
    when(processSheetService.getInventoryYieldCard("F006", "P1")).thenReturn(List.of(
            item(1, "IN_PROGRESS", new BigDecimal("10.0"), new BigDecimal("9.0"), new BigDecimal("0"), null),
            item(2, "IN_PROGRESS", null, new BigDecimal("0.9"), new BigDecimal("0.9"), new BigDecimal("90")),
            item(3, "COMPLETED", null, new BigDecimal("8.0"), new BigDecimal("0"), new BigDecimal("80")) // 成品行不计折算
    ));
    when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "P1"))
            .thenReturn(List.of(clkB(new BigDecimal("8.0"))));

    ProductionSummaryDTO dto = service.computeSummary("F006", "P1", false);

    assertThat(dto.getRemainingSemiRawEquiv()).isEqualByComparingTo("1.0");
    assertThat(dto.getRealYieldRate()).isEqualByComparingTo("88.89");
}
```

- [ ] **Step 2: 实现折算 + 真实出成率(隔离成单方法,便于换方案 F)**

```java
// computeSummary 内, 在 totalFinishedOutput 之后:
BigDecimal remainingSemiRawEquiv = foldRemainingToRawEquiv(items, minOrder);
BigDecimal denom = totalRawInput.subtract(remainingSemiRawEquiv);
BigDecimal realYield = denom.signum() > 0
        ? totalFinishedOutput.multiply(new BigDecimal("100"))
            .divide(denom, 2, RoundingMode.HALF_UP)
        : null;
// builder 追加 .remainingSemiRawEquiv(remainingSemiRawEquiv).realYieldRate(realYield)

/** 方案 R: 剩余 WIP(非成品行)按 cumulativeYieldRate 折回首道原料当量。
 *  折回原料 = remaining ÷ (cumulativeYieldRate/100)。成品行(COMPLETED)与首道行不计。 */
private BigDecimal foldRemainingToRawEquiv(List<ProcessSheetInventoryItem> items, int minOrder) {
    BigDecimal sum = BigDecimal.ZERO;
    for (ProcessSheetInventoryItem i : items) {
        if ("COMPLETED".equals(i.getStatus())) continue;            // 成品行不是半成品
        if (i.getProcessOrder() != null && i.getProcessOrder() == minOrder) continue; // 首道余料另计/忽略
        BigDecimal rem = nz(i.getRemaining());
        BigDecimal cum = i.getCumulativeYieldRate();
        if (rem.signum() <= 0 || cum == null || cum.signum() <= 0) continue;
        sum = sum.add(rem.multiply(new BigDecimal("100")).divide(cum, 4, RoundingMode.HALF_UP));
    }
    return sum.setScale(4, RoundingMode.HALF_UP);
}
```

- [ ] **Step 3: 跑测 GREEN**

Run: `mvn -q -o test -Dtest=ProductionSummaryServiceTest`
Expected: PASS(remainingSemiRawEquiv=1.0, realYieldRate=88.89)。

- [ ] **Step 4: Commit**

```bash
git add -A backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/ProductionSummaryService.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/yield/ProductionSummaryServiceTest.java
git commit -m "feat(production-summary): 方案R 剩余半成品折回原料当量 + 真实总出成率"
```

---

## Task 3: 总成本(逐成品批 computeByBatch 累加 + 脱敏)+ 各批明细

**Files:**
- Modify: `service/yield/ProductionSummaryService.java`
- Test: `service/yield/ProductionSummaryServiceTest.java`

- [ ] **Step 1: 写失败单测(成本累加 + maskPrice=null)**

```java
@Test
void totalCost_sumsComputeByBatch_andMasks() {
    com.cretas.aims.dto.yield.OrderCostBreakdownDTO cb1 =
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO.builder().totalCost(new BigDecimal("100")).build();
    com.cretas.aims.dto.yield.OrderCostBreakdownDTO cb2 =
            com.cretas.aims.dto.yield.OrderCostBreakdownDTO.builder().totalCost(new BigDecimal("50")).build();
    ProductionBatch b1 = clkB(new BigDecimal("1")); b1.setBatchNumber("CLK-B-1");
    ProductionBatch b2 = clkB(new BigDecimal("1")); b2.setBatchNumber("CLK-B-2");
    when(processSheetService.getInventoryYieldCard("F006","P1")).thenReturn(List.of());
    when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006","P1")).thenReturn(List.of(b1, b2));
    when(orderCostBreakdownService.computeByBatch("F006","CLK-B-1", false)).thenReturn(cb1);
    when(orderCostBreakdownService.computeByBatch("F006","CLK-B-2", false)).thenReturn(cb2);

    assertThat(service.computeSummary("F006","P1", false).getTotalCost()).isEqualByComparingTo("150");
    // 脱敏: maskPrice=true 时 totalCost 置 null (不调 computeByBatch 或调但丢弃金额)
    assertThat(service.computeSummary("F006","P1", true).getTotalCost()).isNull();
}
```

- [ ] **Step 2: 实现成本累加 + 脱敏 + 各批明细**

> 核实 `OrderCostBreakdownDTO` 的 totalCost getter 名(`getTotalCost`)。脱敏:maskPrice=true 直接不算成本(置 null),与 `@PriceSensitive` 一致。

```java
// computeSummary 内:
BigDecimal totalCost = null;
if (!maskPrice) {
    totalCost = planBatches.stream()
            .filter(b -> "REGULAR".equals(b.getBatchType()) && b.getBatchNumber() != null)
            .map(b -> orderCostBreakdownService.computeByBatch(factoryId, b.getBatchNumber(), false))
            .filter(java.util.Objects::nonNull)
            .map(cb -> nz(cb.getTotalCost()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
}
// 各批明细: items → BatchLine
List<ProductionSummaryDTO.BatchLine> lines = items.stream()
        .map(i -> ProductionSummaryDTO.BatchLine.builder()
                .batchNumber(i.getBatchNumber()).processOrder(i.getProcessOrder())
                .processName(i.getProcessName()).produced(i.getProduced())
                .remaining(i.getRemaining()).status(i.getStatus())
                .cumulativeYieldRate(i.getCumulativeYieldRate()).build())
        .toList();
// builder 追加 .totalCost(totalCost).batches(lines)
```

- [ ] **Step 3: 跑测 GREEN**

Run: `mvn -q -o test -Dtest=ProductionSummaryServiceTest`
Expected: PASS(150 / null)。

- [ ] **Step 4: Commit**

```bash
git add -A backend/java/cretas-api/.../ProductionSummaryService.java .../ProductionSummaryServiceTest.java
git commit -m "feat(production-summary): 总成本逐批累加+脱敏 + 各批明细"
```

---

## Task 4: Controller 端点 + 脱敏门控

**Files:**
- Modify: `controller/ProductionPlanController.java`
- (参考: `controller/ProductionBatchCostController.java:71-81` 的 `priceMaskResolver.shouldMaskPrice(authorization)` 写法)

- [ ] **Step 1: 加端点**

```java
// 注入 (若未注入): private final ProductionSummaryService productionSummaryService;
//                private final PriceMaskResolver priceMaskResolver;

@GetMapping("/{planId}/production-summary")
public ApiResponse<ProductionSummaryDTO> getProductionSummary(
        @PathVariable String factoryId,
        @PathVariable String planId,
        @RequestHeader(value = "Authorization", required = false) String authorization) {
    boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
    return ApiResponse.success(productionSummaryService.computeSummary(factoryId, planId, maskPrice));
}
```

- [ ] **Step 2: 编译 + 启动冒烟**

Run: `cd backend/java/cretas-api && mvn -q -o compile`
Expected: 编译通过。手测(本地或部署后):`curl .../{factoryId}/production-plans/{planId}/production-summary` 返回五量。

- [ ] **Step 3: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionPlanController.java
git commit -m "feat(production-summary): GET /{planId}/production-summary 端点 + 脱敏门控"
```

---

## Task 5: 附带修 — 统一 CLERK_WIP 过滤口径

**Files:**
- Modify: `repository/ProductionBatchRepository.java:46-53`

- [ ] **Step 1: 让 `findByFactoryIdAndStatus` 也排除 CLERK_WIP(与 `findByFactoryId` 一致)**

把派生查询改为 `@Query`,与 `:46-48` 同口径:

```java
@Query("SELECT p FROM ProductionBatch p WHERE p.factoryId = :factoryId AND p.status = :status " +
       "AND p.batchType <> 'CLERK_WIP'")
Page<ProductionBatch> findByFactoryIdAndStatus(@Param("factoryId") String factoryId,
        @Param("status") ProductionBatchStatus status, Pageable pageable);
```

- [ ] **Step 2: 跑相关单测确认无回归**

Run: `mvn -q -o test -Dtest=*ProductionBatch*,*Processing*`
Expected: PASS(若有断言依赖旧行为则同步更新)。

- [ ] **Step 3: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionBatchRepository.java
git commit -m "fix(batches): findByFactoryIdAndStatus 也排除 CLERK_WIP (口径统一)"
```

---

## Task 6: 前端「阅读汇总」入口 + 弹窗

**Files:**
- Modify: `web-admin/src/api/*` — 加 `getProductionSummary(factoryId, planId)`
- Create: `web-admin/src/views/production/components/ProductionSummaryDialog.vue`
- Modify: `web-admin/src/views/production/plans/list.vue` — 行操作加"阅读汇总"按钮

- [ ] **Step 1: api 函数**

```ts
export function getProductionSummary(factoryId: string, planId: string) {
  return request.get(`/${factoryId}/production-plans/${planId}/production-summary`);
}
```

- [ ] **Step 2: 弹窗组件**(参考现有 `views/production` 下 el-dialog + el-descriptions/el-table 模式)

展示:总投入原料 / 总产出成品 / 剩余半成品折回原料当量 / **真实总出成率** / 总成本(脱敏时显"—")+ 各批明细 el-table(批号/工序/产出/剩余/状态)。防呆:数字带单位、批号可点进批次详情、`priceMasked` 时成本列显"无权限"。

- [ ] **Step 3: list.vue 行操作加按钮**

```vue
<el-button link type="primary" size="small" @click="openSummary(row)">阅读汇总</el-button>
```
+ `openSummary(row)` 调 api 打开弹窗。

- [ ] **Step 4: headed 验证(per playwright-headed-mode rule)**

跑一个 headed spec(参考 `tests/e2e-yield-mixed-sku/` 模式):建/取一个有产出的计划 → 点"阅读汇总" → 断言五量渲染 + 真实出成率 == 后端 oracle(三方:DTO==DOM)。

- [ ] **Step 5: Commit**

```bash
git add web-admin/src/api/* web-admin/src/views/production/components/ProductionSummaryDialog.vue \
  web-admin/src/views/production/plans/list.vue
git commit -m "feat(production-summary): 前端阅读汇总入口+弹窗 (headed 验证)"
```

---

## Self-Review (已跑)

- **Spec coverage**:总投入(T1)/总产出(T1)/剩余半成品折算(T2)/真实总出成率(T2)/总成本+脱敏(T3)/端点(T4)/CLERK_WIP 口径(T5)/前端(T6)—— Phase 1 spec 全覆盖。
- **🔒 陷阱**:陷阱1(不挂 WIP planId)✓ 用 plan-scoped yield-card;陷阱4(成本订单键返空)✓ 用 computeByBatch 逐批;陷阱5(remaining 含成品行/未折算)✓ T2 过滤 COMPLETED + cumulativeYieldRate 折算;脱敏 ✓ priceMaskResolver。
- **Placeholder scan**:无 TODO/"略";折算业务规则已显式标"需客户确认"+ 隔离单方法。
- **Type consistency**:`computeSummary(factoryId, planId, maskPrice)` / `ProcessSheetInventoryItem` getter / `OrderCostBreakdownDTO.getTotalCost` —— 跨任务一致。**执行者注意**:Step 4 跑测前先核实 `ProcessSheetInventoryItem` 是 @Data(有 setter)还是只 @Builder;若只 builder 则测试用 builder 构造。

## Follow-up(非阻塞 Phase 1 ship,但要跟进)
1. **跟客户确认折算口径**:方案 R(折回原料,已实现)vs 方案 F(折成成品)。换方案只改 `foldRemainingToRawEquiv`。
2. 首道"余料"(minOrder 行的 remaining)当前不计入折算 —— 确认是否要计(通常首道领料即投,无余料)。
3. 永续计划下成品批可能很多 → `computeByBatch` 逐批调用的性能(N 个成品批 N 次成本回溯);若慢,加批量版或缓存。
