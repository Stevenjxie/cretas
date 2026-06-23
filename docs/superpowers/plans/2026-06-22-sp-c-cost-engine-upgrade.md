# SP-C 核算引擎升级 — 双模式 keying + 工时单价配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** 成品出厂核算支持「按生产批次号」查询（存货生产无订单号），+ 工时单价改 `factory_cost_settings` 配置（替 SP-B1 ¥26 硬编码）。

**Architecture:** 抽出 `OrderCostBreakdownService.compute(orderId)` 的「批次列表→成本」内核 `computeForBatches(batches, label, maskPrice)`，by-order 与 by-batch 都调它。出成率同理加 `getBatchYieldByNumber`。新增 `factory_cost_settings` 表 + repo，`ClerkProcessEntryServiceImpl` 工时单价改读配置。web-admin 核算页加 订单号/批次号 切换。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA + PostgreSQL; web-admin Vue3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-06-22-clerk-process-entry-recipe-cost-design.md` §5。
**依赖:** SP-A + SP-B1 + SP-B2 已合并 main。

**⚠️ 工作基线（重要）:** **从最新 `origin/main` fork worktree**（含 SP-A/B1/B2）。`git worktree add -b feat/sp-c-cost-engine ../cretas-sp-c origin/main`。web-admin: `cd web-admin && npm install --prefer-offline --legacy-peer-deps`（⛔ 禁 mklink /J）。commit `git commit -- <paths>`。

**🔢 Flyway 号:** 用 **`V20261027_04`**（main 上 27_01=SP-A、27_03=SP-B1 已应用; 27_02 不可用——小于已应用的 27_03 会 out-of-order 失败）。开工前 `ls backend/java/cretas-api/src/main/resources/db/flyway/ | sort | tail -3` 确认最大号, 取更大的。

**Grounding（已验, 注意修正下述）:**
- `OrderYieldController` `GET /{factoryId}/production/orders/{orderId}/yield-summary` → `YieldReportServiceImpl.getOrderYieldSummary(factoryId, orderId)`；**per-batch `getYield(factoryId, Long batchId)` 已存在**（可直接 wrap）。
- `ProductionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)` **存在且 factory-scoped（非 @Deprecated 全局版）**。
- `OrderCostBreakdownController` `GET /{factoryId}/production/orders/{orderId}/cost-breakdown`，`maskPrice = priceMaskResolver.shouldMaskPrice(authorization)`（procurement:price:view），调 `OrderCostBreakdownService.compute(factoryId, orderId, maskPrice)`。**compute 硬绑 orderId→plans→batches**——by-batch 需抽内核。
- `OrderCostBreakdownDTO` 是**强类型 DTO**，`compute` 内 `maskCosts(dto)` 手动 null 金额字段（非裸 Map，脱敏已安全）。SourceCost 字段含 `@PriceSensitive`。**→ 本 SP-C 保持现有 `shouldMaskPrice`(procurement) 门控不变**（typed DTO 已防泄漏；改 finance gate 会变更既有访问语义，属产品决策，本期不做）。
- **SP-B1 `ClerkProcessEntryServiceImpl` 有 `LABOR_RATE_DEFAULT = new BigDecimal("26")`**（main 上）——本 SP-C 替换为配置读取。
- web-admin 核算页 `web-admin/src/views/production-analytics/M67YieldCost.vue`：`load()` 调 yield-summary + `loadCostBreakdown()` 调 cost-breakdown，`orderId` ref + 刷新。api 用 `@/api/request` 的 `get<T>`。

---

### Task 1: factory_cost_settings 表 + 实体 + repo

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_04__create_factory_cost_settings.sql`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactoryCostSettings.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/config/FactoryCostSettingsRepository.java`

- [ ] **Step 0: 确认 Flyway 最大号** `ls backend/java/cretas-api/src/main/resources/db/flyway/ | sort | tail -3`。若已有 ≥ `V20261027_04`，本文件改用更大号。

- [ ] **Step 1: 写迁移**

```sql
-- SP-C 工厂成本参数 (工时单价等), 替 SP-B1 ¥26 硬编码。Spec §5.3.
CREATE TABLE IF NOT EXISTS factory_cost_settings (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    labor_hourly_rate NUMERIC(12,2),       -- ¥/工时; null=未配置
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uq_factory_cost_settings_factory UNIQUE (factory_id)
);
```
> 仅建表, **不 INSERT 任何工厂值**（值按工厂配置, 不 prod-wide 写; demo 值单独 seed DEMO_FACTORY）。

- [ ] **Step 2: 实体（继承 BaseEntity, 范式照 SP-A ProductRecipe）**

```java
package com.cretas.aims.entity.config;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "factory_cost_settings")
@Where(clause = "deleted_at IS NULL")
public class FactoryCostSettings extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** ¥/工时; null = 未配置 */
    @Column(name = "labor_hourly_rate", precision = 12, scale = 2)
    private BigDecimal laborHourlyRate;
}
```

- [ ] **Step 3: repo**

```java
package com.cretas.aims.repository.config;

import com.cretas.aims.entity.config.FactoryCostSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface FactoryCostSettingsRepository extends JpaRepository<FactoryCostSettings, Long> {
    Optional<FactoryCostSettings> findByFactoryId(String factoryId);
}
```

- [ ] **Step 4: 提交** `git add backend/java/cretas-api/src/main/resources/db/flyway/V20261027_04__create_factory_cost_settings.sql backend/java/cretas-api/src/main/java/com/cretas/aims/entity/config/FactoryCostSettings.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/config/FactoryCostSettingsRepository.java && git commit -m "feat(sp-c): factory_cost_settings 表+实体+repo (工时单价配置)" -- <paths>`

---

### Task 2: ClerkProcessEntryServiceImpl 工时单价改读配置

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/processentry/impl/ClerkProcessEntryServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/processentry/LaborRateConfigTest.java`

- [ ] **Step 1: 读现有** —— 定位 `LABOR_RATE_DEFAULT`(¥26) 与 `computeLaborCost(StepEntry)`（用该常量）。确认构造器(`@RequiredArgsConstructor`)注入字段。

- [ ] **Step 2: 写失败测试**

```java
// @ExtendWith(MockitoExtension.class)
// mock FactoryCostSettingsRepository: findByFactoryId 返回 laborHourlyRate=30 → 工时成本用 30; 返回 empty → 用默认 26 + warning
// 用例1: 配置 30, step 1h×2人 → laborCost = 2工时×30 = 60
// 用例2: 未配置 → 用 26, 且 result.warnings 含 "工时单价未配置"
```
（实现者按 ClerkProcessEntryServiceImpl 实际方法写——若 computeLaborCost 私有, 测公开的 recordChain 路径并断言写入的 report/consumption labor 或 result; 用 ReflectionTestUtils 注入 repo mock。）

- [ ] **Step 3: 改 impl**
- 注入 `private final FactoryCostSettingsRepository costSettingsRepository;`（加到构造器字段）。
- 加方法：
```java
private BigDecimal resolveLaborRate(String factoryId, List<String> warnings) {
    return costSettingsRepository.findByFactoryId(factoryId)
        .map(FactoryCostSettings::getLaborHourlyRate)
        .filter(r -> r != null && r.signum() > 0)
        .orElseGet(() -> {
            warnings.add("工时单价未配置, 暂用默认 ¥" + LABOR_RATE_DEFAULT + "/工时, 请在工厂成本设置中配置");
            return LABOR_RATE_DEFAULT;
        });
}
```
- `recordChain` 开头算一次 `BigDecimal laborRate = resolveLaborRate(factoryId, warnings);`（warnings 即返回 result 的 list），把 `computeLaborCost` 改成用 `laborRate` 参数而非常量。保留 `LABOR_RATE_DEFAULT` 作 fallback 常量。

- [ ] **Step 4: 跑测试** `.\mvnw.cmd test -Dtest=LaborRateConfigTest,ClerkProcessEntryIntegrationTest,ClerkProcessEntryServiceImplTest` → 绿（含既有 SP-B1 测试无回归; 既有测试无 cost-settings mock → 走 fallback 26, 行为不变）。
- [ ] **Step 5: 提交** scope-locked。

---

### Task 3: 按批次号 出成率 + 成本 (后端核心)

**Files:**
- Modify: `backend/.../service/yield/YieldReportService.java` (+impl) — 加 `getBatchYieldByNumber`
- Modify: `backend/.../service/yield/OrderCostBreakdownService.java` — 抽 `computeForBatches` + 加 `computeByBatch`
- Modify: `backend/.../controller/OrderYieldController.java` 或新增 batch 端点; `OrderCostBreakdownController.java`
- Test: `backend/.../service/yield/ByBatchKeyingTest.java`

- [ ] **Step 1: 出成率 by-batch** —— `YieldReportService` 加：
```java
BatchYieldDTO getBatchYieldByNumber(String factoryId, String batchNumber);
```
impl:
```java
@Override @Transactional(readOnly = true)
public BatchYieldDTO getBatchYieldByNumber(String factoryId, String batchNumber) {
    ProductionBatch batch = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
        .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchNumber));
    return getYield(factoryId, batch.getId());   // 复用既有单批出成率
}
```

- [ ] **Step 2: 成本 by-batch — 抽内核** —— 在 `OrderCostBreakdownService`：
  - 把 `compute(factoryId, orderId, maskPrice)` 里「拿到 `List<ProductionBatch> batches` 之后」的全部累加/traceCost/分桶/副产/留样/maskCosts 逻辑抽成：
    ```java
    private OrderCostBreakdownDTO computeForBatches(String factoryId, String label,
            List<ProductionBatch> batches, boolean maskPrice) { /* 原 compute 主体 */ }
    ```
    `compute` 改为：解析 orderId→planIds→batches 后 `return computeForBatches(factoryId, orderId, batches, maskPrice);`（label 即原 orderId, 填 DTO.orderId）。
  - 加：
    ```java
    public OrderCostBreakdownDTO computeByBatch(String factoryId, String batchNumber, boolean maskPrice) {
        ProductionBatch b = batchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
            .orElseThrow(() -> new BusinessException(404, "生产批次不存在: " + batchNumber));
        return computeForBatches(factoryId, batchNumber, List.of(b), maskPrice);
    }
    ```
  > 注意: DTO.orderId 字段 by-batch 时填 batchNumber（前端只作展示 label）。

- [ ] **Step 3: 端点（🔒 多租户 — 必带 factoryId 段）**
  - `OrderYieldController`（class `@RequestMapping("/api/mobile/{factoryId}/production/orders")` 不含 batches）→ 在 `YieldReportController`（class `@RequestMapping("/api/mobile/{factoryId}/production/batches/{batchId}")`... 确认其 mapping）或新建 batch 端点。**最简**: 新增一个 controller 或在合适 controller 加：
    ```java
    @RequirePermission({"production:read"})
    @GetMapping("/api/mobile/{factoryId}/production/batches/{batchNumber}/yield-summary")
    public ApiResponse<BatchYieldDTO> batchYield(@PathVariable String factoryId, @PathVariable String batchNumber) {
        return ApiResponse.success(yieldReportService.getBatchYieldByNumber(factoryId, batchNumber));
    }
    @RequirePermission({"production:read"})
    @GetMapping("/api/mobile/{factoryId}/production/batches/{batchNumber}/cost-breakdown")
    public ApiResponse<OrderCostBreakdownDTO> batchCost(@PathVariable String factoryId,
            @PathVariable String batchNumber,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(orderCostBreakdownService.computeByBatch(factoryId, batchNumber, maskPrice));
    }
    ```
  - **路径必含 `{factoryId}`**（JwtAuthInterceptor 工厂守卫才触发）+ service 用 `findByFactoryIdAndBatchNumber`（已 factory-scoped）→ 跨租户安全。**禁用** 任何全局 `findByBatchNumber`。

- [ ] **Step 4: 测试 `ByBatchKeyingTest`**（@SpringBootTest 或 mockito）：
  - by-batch yield: 存货生产批（无订单）→ getBatchYieldByNumber 返回正确出成率。
  - by-batch cost: 同一条 SP-B1 物化的链, by-batch cost == 该批 by-order 等价（或直接断成本拆分正确）。
  - **跨租户**: factory=A 的 batchNumber, 用 factory=B 查 → 404（findByFactoryIdAndBatchNumber 不串）。
  - 复用 SP-B1 的物化 fixture（recordChain）建数据, 再 by-batch 查。

- [ ] **Step 5: 全量后端测试 `.\mvnw.cmd test`** → BUILD SUCCESS 无回归（既有 by-order compute 抽内核后行为不变——加一条 by-order 回归断言）。
- [ ] **Step 6: 提交** scope-locked（service + controller + test）。

---

### Task 4: web-admin 核算页 双模式查询

**Files:**
- Modify: `web-admin/src/views/production-analytics/M67YieldCost.vue`

- [ ] **Step 1: 读现有** `load()` + `loadCostBreakdown()` + `orderId` ref。

- [ ] **Step 2: 加 查询模式切换**
- 加 `const queryMode = ref<'order'|'batch'>('order'); const batchNumber = ref('');`
- 模板：订单号 input 旁加 `<el-radio-group v-model="queryMode">` 订单号/批次号 + 条件显示对应 input。
- `load()`/`loadCostBreakdown()`：按 `queryMode` 选 URL：
  - order: `/${factoryId}/production/orders/${orderId}/yield-summary` + `/cost-breakdown`（现状）
  - batch: `/${factoryId}/production/batches/${batchNumber}/yield-summary` + `/cost-breakdown`
- by-batch 的 yield-summary 返回 `BatchYieldDTO`（单批, 非 OrderYieldSummaryDTO 的聚合形态）——前端兼容：单批时把 `batches=[该DTO]`、`overallYieldRate=该批 cumulativeYieldRate`、`totalLaborCost` 等映射进现有渲染结构（加一个 normalize 函数）。cost-breakdown 形态一致（都是 OrderCostBreakdownDTO）。

- [ ] **Step 3: 构建** `cd web-admin && npm run build` 绿。
- [ ] **Step 4: 提交** scope-locked。

---

## 验收与交接
- [ ] `.\mvnw.cmd test` 全绿（ByBatchKeyingTest + LaborRateConfigTest + 既有无回归, esp. OrderCostBreakdownServiceTest by-order）。
- [ ] `cd web-admin && npm run build` 绿。
- [ ] `git diff origin/main...HEAD --stat` scope 干净。
- [ ] **🔒 Opus 终审**: by-batch 端点多租户（factoryId 段 + findByFactoryIdAndBatchNumber）+ compute 抽内核未改 by-order 行为（既有成本零回归）→ 必经终审再合 + 部署。
- [ ] **端到端（部署后 prod/DEMO_FACTORY）**: ① 给 DEMO_FACTORY 配 `factory_cost_settings`（labor_hourly_rate=26 或实际, 单独 seed）; ② 用 SP-B2 面板录一条存货生产链 → 拿 finishedBatchNumber → 核算页「按批次号」查 → 出成率/单盒成本正确（这是存货生产闭环, SP-B+SP-C 合体的最终证明）。
- [ ] 不碰 F006/六膳门; DEMO_FACTORY 验证。

## Self-Review
- **Spec 覆盖**: §5.1 双模式→Task3; §5.3 工时单价→Task1/2; §5.5 脱敏→保持现有 typed-DTO+procurement gate（已安全, 决策记录）; 核算页 toggle→Task4。✅
- **类型一致**: `getBatchYieldByNumber`/`computeByBatch`/`computeForBatches` 签名贯穿; `FactoryCostSettings.laborHourlyRate` ↔ resolveLaborRate。✅
- **修正 stale-explore**: Flyway 用 `V20261027_04`(非 27_01/02); 目标 SP-B1 真实 `LABOR_RATE_DEFAULT`。✅
- **No-placeholder 例外**: Task3 Step3 端点挂哪个 controller、Task2 computeLaborCost 私有方法签名、Task4 BatchYieldDTO→渲染 normalize, 需实现者对最新 origin/main 现场确认（已给指向）。
- **YAGNI**: 不改既有 by-order 脱敏 gate（procurement, typed DTO 已安全）; 工时单价配置 UI 暂不做（值用 seed/直接 SQL 配; 后续可加设置页）; 不做 §5.4 逐批出成率分布(min/max)展示（现有够用）。
