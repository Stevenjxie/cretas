# BOM 画布融合 Phase 1：人工/均摊移出 BOM + 清理遗留用量列

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把人工成本、均摊费用两套配置从 BOM 中移除，并清理已无录入口却仍被读取的 `standard_quantity` 遗留路径，让「标准成本 = 辅料 + 包材」这条口径在代码里成立。

**Architecture:** 纯削减，不新增机制。后端删掉 `LaborCostConfig` / `OverheadCostConfig` 的 9 个端点与服务方法，`BomCostSummaryDTO` 的人工/均摊字段改为恒空（列表空、总额 null 而非 0），`totalCost` 只汇总物料。前端删掉 BOM 页「人工与均摊费用」区块与对应的成本合计路径。实体与数据库表保留不动（软下线），只切断读写入口。

**Tech Stack:** Java 21 + Spring Boot 3.2.12 + JUnit 5 + Mockito；Vue 3 + TypeScript + Element Plus + Vitest。

## Global Constraints

- 禁止降级处理：不返回假数据。人工/均摊总额一律 `null`，**禁止写 0**（0 表示「不要钱」，null 表示「不在此归集」）。
- 统一响应格式 `{ success, data, message }`，不改动。
- Java Entity 字段 camelCase ↔ 数据库列 snake_case，不改列名。
- **本 Phase 不写任何数据库 migration**：`bom_labor_cost_configs`、`bom_overhead_cost_configs` 两张表和 `bom_recipes.total_labor_cost` / `total_overhead_cost` / `bom_recipe_items.standard_quantity` 四个列全部保留。只断入口，不删数据。
- 并发提交纪律：每个 Task 结束用 `git commit -- <明确路径>` 锁定范围，提交后 `git show --name-only HEAD` 核对。
- 每个 Task 独立可测、独立可回滚。

---

## ⚠️ 测试基线（执行前必读）

`origin/main` 上 BOM 域**本来就有 5 个测试类是红的**，与本计划无关。已在 BASE commit 实测确认：

```
BomItemYieldRateTest                 5 run,  0F,  4E
BomRecipeSeasoningServiceTest       14 run,  9F,  3E
BomRecipePackagingSpecInferTest      8 run,  1F,  7E
BomRecipeServiceImplAddItemTest     23 run,  7F, 13E
BomRecipeServiceImplUomGuardTest    12 run,  6F,  6E
────────────────────────────────────────────────────
合计                                62 run, 23F, 33E
```

**因此本计划中所有「预期：全绿」一律读作「预期：无新增失败 —— 失败集合与上表逐类相同」。**

判据：跑完后逐类比对 `Tests run / Failures / Errors` 三个数。任何一类数字变大，或出现上表之外的新失败类，才是回归。

⛔ **不要去修上表里的历史失败** —— 它们不在本计划范围内，修它们会把 commit scope 撑爆且无法审查。

---

## 三份计划的拆分（Scope Check 结论）

设计文档 `docs/superpowers/specs/2026-08-04-bom-canvas-fusion-design.md` 覆盖三个能各自独立上线的子系统。硬塞进一个计划会做成没法执行的巨型文档，因此拆成三份：

| # | 计划 | 依赖 | 独立价值 |
|---|---|---|---|
| **Phase 1（本文档）** | 人工/均摊移出 BOM + 清理遗留用量列 | 无 | 立即减少一批无消费者的配置面，缩小后两期要表达的范围 |
| Phase 2 | 版本合并：workflow revision 草稿 + BOM family 草稿原子同生共死 | Phase 1 | 「升级到最新工艺」按钮下线，两套版本号不再脱节 |
| Phase 3 | 画布融合：辅料/包材 cell、标记体系、边界状态、三种业态 | Phase 2 | 画布成为唯一配置入口，BOM 页写入口下线 |

Phase 2 / 3 在 Phase 1 落地后各自单独写计划 —— 届时代码形状已变，现在写会写错。

---

## File Structure

| 文件 | 责任 | 本期改动 |
|---|---|---|
| `backend/.../controller/BomController.java` | BOM HTTP 入口 | 删 9 个 labor/overhead 端点及其私有映射方法 |
| `backend/.../service/BomService.java` | BOM 服务契约 | 删 9 个 labor/overhead 方法声明 |
| `backend/.../service/impl/BomServiceImpl.java` | BOM 服务实现 + 成本汇总 | 删实现；`calculateProductCost` 只算物料 |
| `backend/.../dto/bom/BomCostSummaryDTO.java` | 成本汇总响应 | 字段保留，加注释说明恒空口径 |
| `backend/.../service/bom/impl/BomRecipeServiceImpl.java` | 配方成本回算 | `totalLaborCost` / `totalOverheadCost` 恒 null |
| `web-admin/src/views/production/bom/index.vue` | BOM 编辑页 | 删「人工与均摊费用」区块、状态、API 调用、成本合计中的遗留用量路径 |

**不动的**：两个实体类、两个 Repository、两张表、四个数据库列、`dto/bom/CreateLaborCostRequest.java` 等请求 DTO（随端点一起变成死代码，Phase 3 统一清）。

---

### Task 1: 冻结成本汇总的新口径（后端契约）

`calculateProductCost` 今天把物料 + 人工 + 均摊三块加总成 `totalCost`。本任务先用测试把新口径钉死：人工/均摊列表为空、总额为 `null`、`totalCost` 只等于物料合计。

**Files:**
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/BomCostSummaryCaliberTest.java`（新建）
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/BomServiceImpl.java`

**Interfaces:**
- Consumes: `BomService#calculateProductCost(String factoryId, String productTypeId) -> BomCostSummaryDTO`
- Produces: 新口径契约 —— `BomCostSummaryDTO.laborCosts` / `overheadCosts` 恒为空 List，`laborCostTotal` / `overheadCostTotal` 恒 `null`，`totalCost == materialCostTotal`。Task 2/3/6 依赖此契约。

- [ ] **Step 1: 写失败测试**

新建 `BomCostSummaryCaliberTest.java`：

```java
package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 标准成本口径 = 辅料 + 包材。
 * 人工要等结算（实际工时 × 时薪 ÷ 实际箱数），均摊要等成本分析，两者都不在 BOM 归集。
 */
@ExtendWith(MockitoExtension.class)
class BomCostSummaryCaliberTest {

    @Mock BomRecipeItemRepository bomRecipeItemRepository;
    @InjectMocks BomServiceImpl bomService;

    private BomRecipeItem item(String name, String qty, String price) {
        BomRecipeItem it = new BomRecipeItem();
        it.setFactoryId("F001");
        it.setMaterialTypeId("M-" + name);
        it.setMaterialName(name);
        it.setStandardQuantity(new BigDecimal(qty));
        it.setYieldRate(new BigDecimal("100.00"));
        it.setUnit("kg");
        it.setUnitPrice(new BigDecimal(price));
        it.setTaxRate(BigDecimal.ZERO);
        return it;
    }

    @Test
    void calculateProductCost_omitsLaborAndOverhead() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        assertTrue(summary.getLaborCosts().isEmpty(), "人工明细必须为空");
        assertTrue(summary.getOverheadCosts().isEmpty(), "均摊明细必须为空");
    }

    @Test
    void calculateProductCost_totalsAreNullNotZero() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        // null = 这里不归集；0 = 人工不要钱。后者是假话。
        assertNull(summary.getLaborCostTotal(), "人工总额必须是 null 不是 0");
        assertNull(summary.getOverheadCostTotal(), "均摊总额必须是 null 不是 0");
    }

    @Test
    void calculateProductCost_totalCostEqualsMaterialOnly() {
        lenient().when(bomRecipeItemRepository.findCurrentByProduct(anyString(), anyString()))
                .thenReturn(List.of(item("八角", "2", "30.0000")));

        BomCostSummaryDTO summary = bomService.calculateProductCost("F001", "P001");

        assertEquals(0, summary.getTotalCost().compareTo(summary.getMaterialCostTotal()),
                "总成本必须只等于物料合计");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd backend/java/cretas-api
mvn test -Dtest=BomCostSummaryCaliberTest
```

预期：3 个测试全 FAIL，且**失败形态是 `NullPointerException`** —— 因为 `BomServiceImpl` 用构造器注入，`laborCostConfigRepository` / `overheadCostConfigRepository` 是 `private final` 但测试只 mock 了 `BomRecipeItemRepository`，`getGlobalLaborCosts` 会对 null repository 调方法。

**这是预期的失败形态，不要为了让它「干净地失败」去补 mock** —— Step 3 会把这两处调用整个删掉，补了 mock 反而要再删一次。

> `rawMaterialTypeRepository` 不需要 mock：`loadRawMaterialTypeSafely`（`BomServiceImpl:449`）有 `if (rawMaterialTypeRepository == null) return null` 守卫，注入为 null 是安全的。

> ⚠️ 若失败信息是「整类 mock 初始化失败」而不是上述 NPE，说明机器被并行任务压住导致 Mockito inline mock self-attach 失败 —— 停掉其他并行构建串行重跑，不要改代码。

> ⚠️ 若失败信息是「整类 mock 初始化失败」而不是断言不符，说明机器被并行任务压住导致 Mockito inline mock self-attach 失败 —— 停掉其他并行构建串行重跑，不要改代码。

- [ ] **Step 3: 改实现 —— 移除人工/均摊计算**

在 `BomServiceImpl.calculateProductCost` 中：

删除第 2、3 步的取数：

```java
        // 2. 获取人工成本（优先产品级别，其次全局）
        List<LaborCostConfig> laborCosts = getLaborCostsByProduct(factoryId, productTypeId);
        if (laborCosts.isEmpty()) {
            laborCosts = getGlobalLaborCosts(factoryId);
        }

        // 3. 获取均摊费用
        List<OverheadCostConfig> overheadCosts = getActiveOverheadCosts(factoryId);
```

删除第 5、6 步整段（`for (LaborCostConfig config : laborCosts)` 与 `for (OverheadCostConfig config : overheadCosts)` 两个循环及其 `laborCostItems` / `laborCostTotal` / `overheadCostItems` / `overheadCostTotal` 局部变量声明），替换为：

```java
        // 5. 人工与均摊不在 BOM 归集
        // 人工要等结算（实际工时 × 时薪 ÷ 实际箱数，见 processSheetLaborCost.ts），
        // 均摊要等成本分析。BOM 只配「每单位固定消耗的实物」。
        // 空列表 + null 总额：null 表示「此处不归集」，写 0 会被读成「不要钱」。
        List<BomCostSummaryDTO.LaborCostItem> laborCostItems = List.of();
        List<BomCostSummaryDTO.OverheadCostItem> overheadCostItems = List.of();
```

在构建 `BomCostSummaryDTO` 的地方，把三项合计改为只取物料，并把两个总额置 null：

```java
            .laborCosts(laborCostItems)
            .laborCostTotal(null)
            .overheadCosts(overheadCostItems)
            .overheadCostTotal(null)
            .totalCost(materialCostTotal)
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -Dtest=BomCostSummaryCaliberTest
```

预期：3 passed。

- [ ] **Step 5: 变异验证 —— 确认测试真的在守这条口径**

把 `.laborCostTotal(null)` 临时改成 `.laborCostTotal(BigDecimal.ZERO)`，重跑：

```bash
mvn test -Dtest=BomCostSummaryCaliberTest
```

预期：`totalsAreNullNotZero` 变红，报 `expected: <null> but was: <0>`。确认后改回 `null` 复绿。

> 这一步是必须的：断言 null 和断言 0 在「都没配」时表现一样，只有变异能证明测试抓的是口径而不是巧合。

- [ ] **Step 6: 跑全量 BOM 相关测试确认无回归**

```bash
mvn test -Dtest='Bom*Test'
```

预期：全绿。若 `BomControllerTest` 中 3 个 labor 测试仍通过，属正常（端点还在，Task 2 才删）。

- [ ] **Step 7: 提交**

```bash
git add backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/BomCostSummaryCaliberTest.java
git commit -m "refactor(bom): 标准成本口径收敛为辅料+包材, 人工/均摊总额置 null" -- \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/BomCostSummaryCaliberTest.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/BomServiceImpl.java
git show --name-only HEAD
```

核对输出只含上述 2 个文件。

---

### Task 2: 下线 9 个 labor/overhead 端点

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomController.java:106-205`
- Modify: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/BomControllerTest.java`

**Interfaces:**
- Consumes: 无（纯删除）
- Produces: `/api/mobile/{factoryId}/bom/labor*` 与 `/api/mobile/{factoryId}/bom/overhead*` 共 9 条路由不再存在。Task 6 前端删调用依赖此。

要删的端点（已核实行号与方法名）：

| 行 | 路由 | 方法 |
|---|---|---|
| 106 | `GET /labor` | `getLaborCosts` |
| 118 | `GET /labor/all` | `getAllLaborCosts` |
| 127 | `POST /labor` | `addLaborCost` |
| 139 | `PUT /labor/{id}` | `updateLaborCost` |
| 153 | `DELETE /labor/{id}` | `deleteLaborCost` |
| 165 | `GET /overhead` | `getOverheadCosts` |
| 174 | `POST /overhead` | `addOverheadCost` |
| 185 | `PUT /overhead/{id}` | `updateOverheadCost` |
| 200 | `DELETE /overhead/{id}` | `deleteOverheadCost` |

- [ ] **Step 1: 删掉 BomControllerTest 中已失效的 3 个测试**

`BomControllerTest.java` 中删除这三个方法及其独占的 import：

- `addLaborCost_minimumBody_fillsFactoryAndDefaults()`（:34）
- `addLaborCost_supportsProductScopedConfig()`（:56）
- `updateLaborCost_setsIdAndFactoryFromPath()`（:83）

同时删除随之无用的 import：`CreateLaborCostRequest`、`UpdateLaborCostRequest`、`LaborCostConfig`。

- [ ] **Step 2: 跑测试确认失败（编译错误即预期）**

```bash
cd backend/java/cretas-api
mvn test -Dtest=BomControllerTest
```

预期：PASS（只是少了 3 个测试）。若报 unused import 类的 lint 失败，按提示清干净。

- [ ] **Step 3: 删掉 Controller 的 9 个端点**

删除 `BomController.java` 中上表 9 个方法的完整方法体（含各自的 Javadoc 与 `@XxxMapping` 注解），以及只服务它们的两个私有映射方法：

```java
    private static LaborCostConfig toLaborCostConfig(CreateLaborCostRequest r) { ... }
    private static LaborCostConfig toLaborCostConfig(UpdateLaborCostRequest r) { ... }
```

清理随之无用的 import：`LaborCostConfig`、`OverheadCostConfig`、`CreateLaborCostRequest`、`UpdateLaborCostRequest`。

**保留** `:218` 的 `calculateProductCost` 端点和 `:228` 的 `calculateProductCosts` 端点 —— 它们是成本汇总入口，不删。

- [ ] **Step 4: 编译 + 跑测试**

```bash
mvn -q clean compile
mvn test -Dtest='Bom*Test'
```

预期：编译通过；`Bom*Test` 失败集合与基线表逐类相同，无新增。

- [ ] **Step 5: 确认路由真的没了**

```bash
grep -nE '"/labor|"/overhead' backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomController.java
```

预期：无输出。

- [ ] **Step 6: 提交**

```bash
git commit -m "refactor(bom): 下线人工/均摊 9 个配置端点 —— 结算时才有的数不在 BOM 配" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/controller/BomController.java \
  backend/java/cretas-api/src/test/java/com/cretas/aims/controller/BomControllerTest.java
git show --name-only HEAD
```

---

### Task 3: 删掉 BomService 的 9 个方法声明与实现

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/BomService.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/BomServiceImpl.java`

**Interfaces:**
- Consumes: Task 2 已移除全部 HTTP 调用方
- Produces: `BomService` 接口不再暴露 labor/overhead 方法

要删的声明（已核实签名）：

```java
List<LaborCostConfig> getLaborCostsByProduct(String factoryId, String productTypeId);  // :27
List<LaborCostConfig> getGlobalLaborCosts(String factoryId);                            // :35
List<LaborCostConfig> getAllLaborCosts(String factoryId);                               // :43
LaborCostConfig saveLaborCost(LaborCostConfig config);                                  // :51
void deleteLaborCost(Long id);                                                          // :58
List<OverheadCostConfig> getOverheadCosts(String factoryId);                            // :68
List<OverheadCostConfig> getActiveOverheadCosts(String factoryId);                      // :76
OverheadCostConfig saveOverheadCost(OverheadCostConfig config);                         // :84
OverheadCostConfig updateOverheadCost(String factoryId, Long id, OverheadCostConfig body); // :101
void deleteOverheadCost(Long id);                                                       // :108
```

- [ ] **Step 1: 先确认没有其他调用方**

```bash
cd backend/java/cretas-api
# ⚠️ 必须扫 src 全树, 不能只扫 src/main —— 测试树里有真实调用方
grep -rnE "getLaborCostsByProduct|getGlobalLaborCosts|getAllLaborCosts|saveLaborCost|deleteLaborCost|getOverheadCosts|getActiveOverheadCosts|saveOverheadCost|updateOverheadCost|deleteOverheadCost" src --include=*.java
# 位置构造 BomServiceImpl 的测试(删字段会让它们编译不过)
grep -rn "new BomServiceImpl(" src --include=*.java
```

**已实测的调用方清单（本计划初版把范围写成 `src/main/java`, 漏掉了测试树, 这里补全）**：

| 文件 | 情况 | 处置 |
|---|---|---|
| `BomServiceImplUpdateOverheadCostTest.java` | 专测 `updateOverheadCost` 的 T-R5-3 回归测试 | **随方法一起删** —— 被测方法不存在了 |
| `BomServiceImplPreTaxCaliberTest.java:37` | `new BomServiceImpl(3 个 repo)` 位置构造 | 改为单参 `new BomServiceImpl(bomItemRepository)`, 删两个 `@Mock` 字段 |
| `BomServiceImplUomCostReconciliationTest.java:57` | 同上 | 同上 |
| `BomDomainPriceFieldAdviceTest.java:214,232` | 断言 `getOverheadCosts().get(0)` | **不动** —— 它用 builder 手工造 DTO 测脱敏 advice, 不经 service |

若扫出上表之外的文件，**停下来先看清那是谁**，不要直接删。

- [ ] **Step 2: 删接口声明与实现**

在 `BomService.java` 删上述 10 个声明（含 Javadoc）与 `LaborCostConfig` / `OverheadCostConfig` 两个 import。

在 `BomServiceImpl.java` 删对应的 10 个 `@Override` 实现方法、两个注入字段：

```java
    private final LaborCostConfigRepository laborCostConfigRepository;
    private final OverheadCostConfigRepository overheadCostConfigRepository;
```

以及随之无用的 import。

- [ ] **Step 3: 编译**

```bash
cd backend/java/cretas-api
mvn -q clean compile
```

预期：BUILD SUCCESS。若报 `calculateLaborCost` / `calculateOverheadCost` 私有方法无引用，一并删除。

- [ ] **Step 4: 跑全量测试**

```bash
mvn test -Dtest='Bom*Test'
```

预期：失败集合与基线表逐类相同，无新增。

- [ ] **Step 5: JPA 启动闸自查**

本仓 CI 有 `JPA repository query startup gate`。两个 Repository 类保留但不再被注入，Spring 仍会扫描到它们，不影响启动。本地验证：

```bash
mvn -q test -Dtest=*ApplicationTests* 2>&1 | tail -20
```

预期：无 bean 创建失败。

- [ ] **Step 6: 提交**

```bash
git commit -m "refactor(bom): 移除 BomService 的人工/均摊服务方法" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/BomService.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/BomServiceImpl.java
git show --name-only HEAD
```

---

### Task 4: `total_labor_cost` / `total_overhead_cost` 恒 null

`bom_recipes` 上这两个列今天由配方成本回算写入。移除配置后它们必须是 null 而不是 0。

**Files:**
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/impl/BomRecipeCostNullCaliberTest.java`（新建）
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java`

**Interfaces:**
- Consumes: Task 1 的 null 口径
- Produces: `BomRecipe.totalLaborCost` / `totalOverheadCost` 在任何回算路径后均为 `null`

- [ ] **Step 1: 定位回算写入点**

```bash
grep -n "setTotalLaborCost\|setTotalOverheadCost\|recomputeMaterialCost" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java
```

记下行号 —— 下一步要在这些位置断言。

- [ ] **Step 2: 写失败测试**

新建 `BomRecipeCostNullCaliberTest.java`：

```java
package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomRecipe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工/均摊不在 BOM 归集：这两列必须留空，不能写 0。
 * 0 会被下游读成「这项成本是零」，null 才表示「此处不归集」。
 */
class BomRecipeCostNullCaliberTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java");

    @Test
    void newRecipe_laborAndOverheadTotalsDefaultToNull() {
        BomRecipe recipe = new BomRecipe();

        assertNull(recipe.getTotalLaborCost(), "人工总额默认必须是 null");
        assertNull(recipe.getTotalOverheadCost(), "均摊总额默认必须是 null");
    }

    /**
     * 分摊路径不得用 valueOrZero 把 null 塌成 0 —— 塌了就会把「不归集」写成「零成本」。
     * 这条断言落在源码上：该逻辑是 private 且深埋在多产出回算里，
     * 单测要跑通它需要构造整个 family + workflow revision，成本远高于收益。
     */
    @Test
    void allocationDoesNotCoerceNullCostsToZero() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertFalse(source.contains("valueOrZero(target.getTotalLaborCost())"),
                "人工分摊不能走 valueOrZero —— null 会被塌成 0");
        assertFalse(source.contains("valueOrZero(target.getTotalOverheadCost())"),
                "均摊分摊不能走 valueOrZero —— null 会被塌成 0");
        assertTrue(source.contains("target.getTotalLaborCost() == null"),
                "人工分摊必须显式判 null 后短路");
        assertTrue(source.contains("target.getTotalOverheadCost() == null"),
                "均摊分摊必须显式判 null 后短路");
    }
}
```

> 第二个测试用源码断言而不是行为断言，是有意的取舍：该分摊逻辑是 private、且要跑到它得先构造完整的 BOM family + workflow revision。源码断言能守住「不许用 `valueOrZero`」这条具体规则，成本低且不会假绿。**代价是它对重构敏感** —— 若将来把 `valueOrZero` 改名或把这段逻辑挪走，测试会红，届时要跟着改断言而不是删测试。

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend/java/cretas-api
mvn test -Dtest=BomRecipeCostNullCaliberTest
```

预期：`newRecipe_...` PASS（实体没有 `@Builder.Default`，本来就是 null）；`allocationDoesNotCoerceNullCostsToZero` **FAIL**，因为 `BomRecipeServiceImpl:2126-2127` 现在正是 `valueOrZero(...)` 写法。

若第二个测试意外 PASS，说明该行已被改动过或行号漂移 —— 先 `grep -n "valueOrZero(target.getTotal" BomRecipeServiceImpl.java` 看清现状再继续，不要直接进 Step 4。

- [ ] **Step 4: 修分摊路径的 null 塌陷**

`BomRecipeServiceImpl:2126-2127` 现在这样写：

```java
            BigDecimal allocatedLabor = valueOrZero(target.getTotalLaborCost()).multiply(allocation);
            BigDecimal allocatedOverhead = valueOrZero(target.getTotalOverheadCost()).multiply(allocation);
```

`valueOrZero` 会把 null 塌成 0，再乘分摊比写回 0 —— 正是要禁的。改为：

```java
            // 人工/均摊不在 BOM 归集：null 不参与分摊，也不塌成 0。
            BigDecimal allocatedLabor = target.getTotalLaborCost() == null
                    ? null : target.getTotalLaborCost().multiply(allocation);
            BigDecimal allocatedOverhead = target.getTotalOverheadCost() == null
                    ? null : target.getTotalOverheadCost().multiply(allocation);
```

若下游对 `allocatedLabor` 有非空假设导致编译或 NPE，把接收端一并改成允许 null（不要为了省事把 null 换回 0）。

- [ ] **Step 5: 找出所有写 0 的地方并清掉**

```bash
grep -n "setTotalLaborCost\|setTotalOverheadCost" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/*.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/*.java
```

逐一确认：任何 `setTotalLaborCost(BigDecimal.ZERO)` 或 `setTotalLaborCost(valueOrZero(...))` 改为 `setTotalLaborCost(null)`。
`SkuAssemblyService:280-281` 的 `copy.setTotalLaborCost(source.getTotalLaborCost())` 是原样复制，**保持不动**（源是 null 就复制 null，正确）。

- [ ] **Step 6: 跑测试**

```bash
mvn test -Dtest='BomRecipe*Test,BomCostSummaryCaliberTest'
```

预期：`BomRecipe*Test` 失败集合与基线表逐类相同，无新增；`BomCostSummaryCaliberTest` 3/3 绿。

- [ ] **Step 7: 提交**

```bash
git commit -m "refactor(bom): 人工/均摊总额恒 null, 分摊时不塌成 0" -- \
  backend/java/cretas-api/src/test/java/com/cretas/aims/service/bom/impl/BomRecipeCostNullCaliberTest.java \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/bom/impl/BomRecipeServiceImpl.java
git show --name-only HEAD
```

---

### Task 5: 前端删除「人工与均摊费用」区块

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue`
- Test: `web-admin/src/views/production/bom/__tests__/BomLaborOverheadRemoved.source.spec.ts`（新建）

**Interfaces:**
- Consumes: Task 2 已删除的 9 条路由
- Produces: BOM 页不再有任何 labor/overhead 的状态、请求与 UI

本仓已有 `*.source.spec.ts` 的源码断言测试范式（见同目录 `BomEditorCenteredLayout.source.spec.ts`），本任务沿用。

- [ ] **Step 1: 写失败测试**

新建 `BomLaborOverheadRemoved.source.spec.ts`：

```typescript
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(
  resolve(__dirname, '../index.vue'),
  'utf-8',
);

describe('BOM 页不再承载人工与均摊费用', () => {
  it('没有人工成本的状态与请求', () => {
    expect(source).not.toMatch(/laborCosts/);
    expect(source).not.toMatch(/\/bom\/labor/);
  });

  it('没有均摊费用的状态与请求', () => {
    expect(source).not.toMatch(/overheadCosts/);
    expect(source).not.toMatch(/\/bom\/overhead/);
  });

  it('没有「人工与均摊费用」区块标题', () => {
    expect(source).not.toContain('人工与均摊费用');
    expect(source).not.toContain('均摊费用表');
  });

  it('成本卡不再展示人工/均摊小计', () => {
    expect(source).not.toMatch(/laborCostTotal/);
    expect(source).not.toMatch(/overheadCostTotal/);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/production/bom/__tests__/BomLaborOverheadRemoved.source.spec.ts
```

预期：4 个测试全 FAIL。

- [ ] **Step 3: 删除模板区块**

在 `index.vue` 模板中删除：

- `:2614` 起的「人工与均摊费用」整个卡片区块，含其内的人工成本表
- `:2667` 起的 `<!-- Overhead Cost Table (均摊费用表) -->` 整段
- 两个表各自的新增/编辑 dialog 与工具栏按钮

- [ ] **Step 4: 删除 script 状态与方法**

删除以下（行号为改动前基准，实际以符号搜索为准）：

- `const laborCosts = ref<LaborCostRow[]>([]);`（:850）
- `const overheadCosts = ref<OverheadCostRow[]>([]);`（:867）
- `laborCostTotal` / `overheadCostTotal` 两个 computed（:1656、:1662）
- 所有 `loadLaborCosts` / `saveLaborCost` / `deleteLaborCost` / `loadOverheadCosts` / `saveOverheadCost` / `deleteOverheadCost` 函数
- `:927` 的 `laborCosts.value = [];` 重置
- `:1778` 的「暂无人工成本数据」导出分支
- `LaborCostRow` / `OverheadCostRow` 两个 interface
- 顶层 `BomCostSummaryView` 中的 `laborCostTotal?` / `overheadCostTotal?` 两个字段（:416、:417）

- [ ] **Step 5: 跑测试确认通过**

```bash
npx vitest run src/views/production/bom/__tests__/BomLaborOverheadRemoved.source.spec.ts
```

预期：4 passed。

- [ ] **Step 6: 跑 BOM 全量前端测试 + 类型检查**

```bash
npx vitest run src/views/production/bom
npx vue-tsc --noEmit -p tsconfig.json
```

预期：测试全绿，类型检查无新增错误。若 `vue-tsc` 报既有的历史错误，只需确认没有新增来自 `index.vue` 的错误。

- [ ] **Step 7: 提交**

```bash
git commit -m "refactor(bom-web): 删除 BOM 页人工与均摊费用区块" -- \
  web-admin/src/views/production/bom/index.vue \
  web-admin/src/views/production/bom/__tests__/BomLaborOverheadRemoved.source.spec.ts
git show --name-only HEAD
```

---

### Task 6: 清理 `standard_quantity` 遗留成本路径

`bom_recipe_items.standard_quantity` 已无录入口（非包材类别的添加对话框根本没有数量输入框），但前端成本合计还在读它，老数据里可能有值 —— 会出现「画布上没地方填、成本里却冒出个数」。

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue:1645-1654`
- Test: `web-admin/src/views/production/bom/__tests__/BomLegacyQuantityCleared.source.spec.ts`（新建）

**Interfaces:**
- Consumes: Task 5 之后的 `index.vue`
- Produces: 前端不再从 `standardQuantity` 推算原料成本

- [ ] **Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(__dirname, '../index.vue'), 'utf-8');

describe('遗留用量列不再参与成本', () => {
  it('材料成本合计只算包材，原料一律不计', () => {
    // 主链路没有数量：原料用多少由报工决定，前端不得据它推成本。
    // 但 standardQuantity 对包材是正经数据（每 1 份成品用量就存在这里），
    // 所以拦的是「有没有按类别过滤」，不是「有没有出现 standardQuantity」。
    const materialTotalBlock = source.slice(
      source.indexOf('const materialCostTotal'),
      source.indexOf('const materialCostTotal') + 900,
    );
    expect(materialTotalBlock).toMatch(/materialCategory !== 'PACKAGING'/);
    expect(materialTotalBlock).toMatch(/return sum;/);
    expect(materialTotalBlock).not.toMatch(/yieldRate/);
  });

  it('不再用 standardQuantity 判断待归集状态', () => {
    expect(source).not.toMatch(/hasPendingActualMaterialUsage/);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd web-admin
npx vitest run src/views/production/bom/__tests__/BomLegacyQuantityCleared.source.spec.ts
```

预期：2 个 FAIL。

- [ ] **Step 3: 改成本合计 —— 只算有 naturalQuantity 的包材**

`index.vue:1645` 现在这样：

```typescript
  return bomItems.value.reduce((sum, item) => {
    const qty = item.standardQuantity || 0;
    const yieldRate = item.yieldRate != null ? (Number(item.yieldRate) || 100) / 100 : 1;
    const price = item.unitPrice || 0;
    return sum + (yieldRate > 0 ? (qty / yieldRate) * price : 0);
  }, 0);
```

改为：

```typescript
  // 标准成本 = 辅料 + 包材。原料没有数量（报工时才知道），不参与前端估算。
  // 辅料成本由后端按 dosage_per_kg_g 归集，这里只汇总包材行。
  //
  // ⚠️ 包材用量读的是 standardQuantity，不是 naturalQuantity。
  // 「每 1 份成品包材用量」这个输入框，提交前被复制进 standardQuantity（index.vue:1367），
  // payload 里根本没有 naturalQuantity（:1310），编辑回填也是从 standardQuantity 读回（:1283），
  // 后端成本引擎同样用 getStandardQuantity()（BomServiceImpl:98）。
  // 也就是说 standard_quantity 这一列：对 RAW 是废弃脏数据，对 PACKAGING 是正经数据。
  // 本任务停用的是前者，靠类别过滤实现，不是靠停读整列。
  return bomItems.value.reduce((sum, item) => {
    if (item.materialCategory !== 'PACKAGING') return sum;
    const qty = Number(item.standardQuantity) || 0;
    const price = Number(item.unitPrice) || 0;
    return sum + qty * price;
  }, 0);
```

- [ ] **Step 4: 删除 `hasPendingActualMaterialUsage`**

删除 `:1652-1654` 的 computed：

```typescript
const hasPendingActualMaterialUsage = computed(() => bomItems.value.some((item) =>
  item.materialCategory !== 'PACKAGING' && item.standardQuantity == null,
));
```

并把模板 `:2276` 的标题切换改为固定文案：

```html
<span>当前归集成本</span>
```

理由：原料本来就不归集，这个状态不再是「暂时的」——标题永远是「归集成本」而不是「总成本」，不需要动态切换。

- [ ] **Step 5: 跑测试确认通过**

```bash
npx vitest run src/views/production/bom/__tests__/BomLegacyQuantityCleared.source.spec.ts
npx vitest run src/views/production/bom
```

预期：全绿（前端 vitest 无历史失败基线）。

- [ ] **Step 6: 确认没有残留引用**

```bash
grep -rn "hasPendingActualMaterialUsage" web-admin/src
```

预期：无输出。

- [ ] **Step 7: 提交**

```bash
git commit -m "refactor(bom-web): 成本合计不再读已无录入口的 standard_quantity" -- \
  web-admin/src/views/production/bom/index.vue \
  web-admin/src/views/production/bom/__tests__/BomLegacyQuantityCleared.source.spec.ts
git show --name-only HEAD
```

---

### Task 7: AI 上下文口径核对

`AIContextServiceImpl` 把 BOM 成本喂给 LLM。口径变了（不含人工/均摊），要确认它读的字段仍成立，且不会把降低后的数字当成「成本下降」讲给用户。

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/AIContextServiceImpl.java:98-106, 255-262`

**Interfaces:**
- Consumes: Task 1 的 `BomCostSummaryDTO.totalCost`（现只含物料）
- Produces: 无新接口，仅补口径注释与空值防御

- [ ] **Step 1: 确认它只读 totalCost**

```bash
cd backend/java/cretas-api/src/main/java/com/cretas/aims
grep -n "bomCost\." service/impl/AIContextServiceImpl.java
```

预期：只有 `bomCost.getTotalCost()` 两处（:102、:259）。若出现 `getLaborCostTotal()` 等，一并处理为 null-safe。

- [ ] **Step 2: 补口径注释**

在两处 `getTotalCost()` 调用上方各加一行：

```java
                    // BOM 成本口径 = 辅料 + 包材。原料无数量、人工与均摊等结算，
                    // 故此值不是完全成本，不能与批次实际成本直接比高低。
```

- [ ] **Step 3: 编译 + 跑测试**

```bash
cd backend/java/cretas-api
mvn -q clean compile
mvn test -Dtest='*AIContext*Test'
```

预期：编译通过；若无对应测试类，此步只需编译通过。

- [ ] **Step 4: 全量后端测试**

```bash
mvn test
```

预期：失败集合与基线表逐类相同，无新增。若出现 mock 初始化整类失败，串行重跑（见 Task 1 Step 2 的注记）。

- [ ] **Step 5: 提交**

```bash
git commit -m "docs(ai-context): 标注 BOM 成本口径不含人工与均摊" -- \
  backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/AIContextServiceImpl.java
git show --name-only HEAD
```

---

## 收尾验收

全部 7 个 Task 完成后，逐条核对：

- [ ] `grep -rn '"/labor\|"/overhead' backend/java/cretas-api/src/main/java/com/cretas/aims/controller/` 无输出
- [ ] `grep -rn 'laborCosts\|overheadCosts' web-admin/src/views/production/bom/index.vue` 无输出
- [ ] `grep -rn 'hasPendingActualMaterialUsage' web-admin/src` 无输出
- [ ] `cd backend/java/cretas-api && mvn test` 失败集合与基线表逐类相同，无新增
- [ ] `cd web-admin && npx vitest run` 全绿（前端无历史失败基线）
- [ ] BOM 页打开无 console 错误，成本卡显示「当前归集成本」，无人工/均摊区块
- [ ] 数据库四个列仍存在（`bom_recipes.total_labor_cost` / `total_overhead_cost`、`bom_recipe_items.standard_quantity`、两张 config 表未删）

**不做的事**：不写 migration、不删表、不删实体、不推 origin、不部署。合并与发布按仓库规则单独走。

---

## 与后续 Phase 的衔接

Phase 1 落地后再写 Phase 2 计划。届时需要重新核实的前提：

- `BomWorkflowRevisionService#requireCompleteActiveFamily` 的调用点是否仍是 `ProductProcessWorkflowServiceImpl:133` 与 `ProductProcessWorkflowActivationServiceImpl:68`
- `BomRecipe` 的草稿创建路径（`autoBindUniqueDraft` / `upgradeToLatestCompatibleDraft`）在本期改动后是否有位移
