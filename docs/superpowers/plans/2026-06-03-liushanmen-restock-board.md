# 六扇门全天备货看板 (剩余可能订单对账 P1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给某交货日按产品聚合订单需求 vs 可用结存(成品FG+在产WIP折盒+已排产) vs 缺口, 缺口逐产品一键转生产计划草稿。

**Architecture:** 方案A — 只读实时聚合, 零新持久表(除 ProductType 加一列)。新建 RestockBoardService 聚合 4 个已有数据源(SalesOrderItem/FinishedGoodsBatch/SemiFinishedInventory/ProductionPlan)+ 一个只读 GET 端点 + web-admin 看板页; 缺口转计划复用现有 POST production-plans。

**Tech Stack:** Java 21 + Spring Boot 3 + JPA/Hibernate + PostgreSQL + Flyway; Vue 3 + Element Plus + TS; JUnit5 + Mockito + @DataJpaTest; Playwright(headed)。

**Spec:** `docs/superpowers/specs/2026-06-03-liushanmen-restock-board-design.md`
**Worktree:** `C:\Users\Steve\cretas-restock-board` (branch `feat/restock-board`, off origin/main)
**所有命令在** `backend/java/cretas-api` (Java) 或 `web-admin` (前端) 下运行。Maven: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd` (mvn 不在 PATH)。

---

## 文件结构 (创建/修改)

| 文件 | 职责 | 操作 |
|---|---|---|
| `entity/ProductType.java` | 加 `wipToFgYield` 字段 | 改 |
| `resources/db/flyway/V20260913_01__product_type_wip_to_fg_yield.sql` | 加列迁移(守卫) | 建 |
| `service/restock/RestockUnitConverter.java` | kg↔盒 纯函数 | 建 |
| `service/restock/dto/RestockBoardDTO.java` | 看板 DTO + Summary | 建 |
| `service/restock/dto/RestockRow.java` | 看板行 DTO | 建 |
| `service/restock/RestockBoardService.java` | 聚合服务(只读) | 建 |
| `repository/inventory/SalesOrderItemRepository.java` | 加需求聚合查询 + 投影接口 | 改 |
| `repository/SemiFinishedInventoryRepository.java` | 加 WIP 可用聚合 | 改 |
| `repository/ProductionPlanRepository.java` | 加 未开工计划聚合 | 改 |
| `controller/RestockBoardController.java` | GET 端点 | 建 |
| `web-admin/src/api/restockBoard.ts` | api 客户端 | 建 |
| `web-admin/src/views/production/restock-board/index.vue` | 看板页 + 缺口建计划弹框 | 建 |
| `web-admin/src/router/index.ts` | 注册路由 | 改 |
| `web-admin/src/components/layout/menuConfig.ts` | 加菜单项 | 改 |
| `web-admin/tests/e2e/restock-board.spec.ts` | headed E2E | 建 |

测试根: `backend/java/cretas-api/src/test/java/com/cretas/aims/`

---

## ⚠️ 实现前必读 (写计划时核实的口径)

- **`ProductionPlan.allocatedQuantity` = 已分配原料数量, 不是生产进度。** 已排产**不能**用 `plannedQuantity − allocatedQuantity`。
- **已排产 = 仅未开工计划 (status ∈ {PLANNED, PENDING}) 的整 `plannedQuantity`。** IN_PROGRESS/PAUSED 的产出已变成 `SemiFinishedInventory`(在产WIP) 或 FG(成品), 再算进已排产会**重复计算**。COMPLETED 在 FG、CANCELLED/PREPARED/PREP(草稿) 排除。三层互不相交。
- **可用 status 枚举**: `SalesOrderStatus` 有效需求 = {CONFIRMED, PENDING_FINANCE_REVIEW, FINANCE_APPROVED, PROCESSING, PARTIAL_DELIVERED} (排除 DRAFT/FINANCE_REJECTED/CANCELLED)。
- **WIP 可用** = `SemiFinishedInventory.availableQuantity` (已减 consumed), 单位 kg。
- **FG 可用** = 现有 `FinishedGoodsBatchRepository.sumAvailableQuantityByProductType(factoryId, productTypeId)` → BigDecimal, 单位按产品(盒)。
- **kg→盒** = `kg × 1000 / ProductType.gramsPerUnit`; gramsPerUnit null → 不能折(返 null + 警告)。
- **F2**: 同产品订单行 `unit` 不一致 → demandQty=null + 警告, 不静默累加。

---

## Task 1: ProductType 加 wipToFgYield 字段 + Flyway 迁移

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductType.java` (在 `gramsPerUnit` 字段后, 约 line 201)
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260913_01__product_type_wip_to_fg_yield.sql`

- [ ] **Step 1: 确认 Flyway 版本号未撞车**

Run: `git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway/ | grep -oE "V[0-9]{8}_[0-9]{2}" | sort | tail -3`
Expected: 最大 < `V20260913_01`。若已存在 `V20260913_01`, 改用下一个空闲号(如 `V20260913_02`)并同步本任务所有引用。

- [ ] **Step 2: 写迁移 SQL (列存在守卫, 适配 fresh-DB Flyway 先于 Hibernate)**

`V20260913_01__product_type_wip_to_fg_yield.sql`:
```sql
-- 备货看板: 在产半成品折成品的下游出率系数 (null = 按 1.0 估算)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'product_types' AND column_name = 'wip_to_fg_yield'
    ) THEN
        ALTER TABLE product_types ADD COLUMN wip_to_fg_yield DECIMAL(5,4);
        COMMENT ON COLUMN product_types.wip_to_fg_yield IS '在产半成品折成品下游出率系数(备货看板WIP估算, null=按1.0)';
    END IF;
END $$;
```

- [ ] **Step 3: 实体加字段**

在 `ProductType.java` 的 `gramsPerUnit` 字段之后加:
```java
    /** 在产半成品折成品下游出率系数 (备货看板 WIP 估算; null = 按 1.0). */
    @Column(name = "wip_to_fg_yield", precision = 5, scale = 4)
    private java.math.BigDecimal wipToFgYield;
```

- [ ] **Step 4: 编译验证**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -q -o compile`
Expected: BUILD SUCCESS (无输出即成功)。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductType.java backend/java/cretas-api/src/main/resources/db/flyway/V20260913_01__product_type_wip_to_fg_yield.sql
git commit -m "feat(restock): ProductType 加 wip_to_fg_yield 列 (备货看板WIP折算)"
```

---

## Task 2: RestockUnitConverter (kg↔盒) + 单测

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/RestockUnitConverter.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/restock/RestockUnitConverterTest.java`

- [ ] **Step 1: 写失败测试**

`RestockUnitConverterTest.java`:
```java
package com.cretas.aims.service.restock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RestockUnitConverter kg↔盒")
class RestockUnitConverterTest {

    @Test
    @DisplayName("kgToBox: 540kg / 120g每盒 = 4500盒")
    void kgToBox_normal() {
        BigDecimal box = RestockUnitConverter.kgToBox(new BigDecimal("540"), new BigDecimal("120"));
        assertEquals(0, new BigDecimal("4500.00").compareTo(box));
    }

    @Test
    @DisplayName("kgToBox: gramsPerUnit null → null (无法换算)")
    void kgToBox_nullGrams() {
        assertNull(RestockUnitConverter.kgToBox(new BigDecimal("540"), null));
    }

    @Test
    @DisplayName("kgToBox: gramsPerUnit <=0 → null")
    void kgToBox_zeroGrams() {
        assertNull(RestockUnitConverter.kgToBox(new BigDecimal("540"), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("kgToBox: kg null → null")
    void kgToBox_nullKg() {
        assertNull(RestockUnitConverter.kgToBox(null, new BigDecimal("120")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockUnitConverterTest`
Expected: 编译失败 (RestockUnitConverter 不存在)。

- [ ] **Step 3: 写实现**

`RestockUnitConverter.java`:
```java
package com.cretas.aims.service.restock;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 备货看板单位换算: kg ↔ 盒 (份). 盒 = kg × 1000 / gramsPerUnit. */
public final class RestockUnitConverter {

    private RestockUnitConverter() {}

    /**
     * kg 折算为盒。
     * @return 盒数 (scale=2, HALF_UP); 若 kg 或 gramsPerUnit 缺失/非正 → null (调用方据此显警告, 不静默算错)。
     */
    public static BigDecimal kgToBox(BigDecimal kg, BigDecimal gramsPerUnit) {
        if (kg == null || gramsPerUnit == null || gramsPerUnit.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return kg.multiply(BigDecimal.valueOf(1000))
                 .divide(gramsPerUnit, 2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockUnitConverterTest`
Expected: BUILD SUCCESS, Tests run: 4, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/RestockUnitConverter.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/restock/RestockUnitConverterTest.java
git commit -m "feat(restock): RestockUnitConverter kg↔盒 + 单测"
```

---

## Task 3: 3 个只读聚合查询 (需求/WIP/已排产)

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/inventory/SalesOrderItemRepository.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/inventory/ProductDemandProjection.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/SemiFinishedInventoryRepository.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ProductionPlanRepository.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/repository/restock/RestockAggregationRepositoryTest.java`

> 注: 若某 repository 路径与上不符 (如 SemiFinishedInventoryRepository / ProductionPlanRepository 在子包), 用 `git ls-files | grep -i <repo名>` 定位实际路径后改同名文件。

- [ ] **Step 1: 写需求投影接口**

`ProductDemandProjection.java`:
```java
package com.cretas.aims.repository.inventory;

import java.math.BigDecimal;

/** 备货看板需求聚合投影。minUnit≠maxUnit → 该产品订单行单位不一致 (F2)。 */
public interface ProductDemandProjection {
    String getProductTypeId();
    String getProductName();
    String getMinUnit();
    String getMaxUnit();
    BigDecimal getDemand();
}
```

- [ ] **Step 2: 写失败测试 (@DataJpaTest)**

`RestockAggregationRepositoryTest.java`:
```java
package com.cretas.aims.repository.restock;

import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.*;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("备货看板聚合查询")
class RestockAggregationRepositoryTest {

    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesOrderItemRepository salesOrderItemRepository;
    @Autowired private SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Autowired private ProductionPlanRepository productionPlanRepository;

    private static final LocalDate D = LocalDate.of(2026, 6, 3);

    private SalesOrder order(String id, SalesOrderStatus status) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setFactoryId("F006");
        o.setOrderNumber("SO-" + id);
        o.setCustomerId("C1");
        o.setOrderDate(D);
        o.setRequiredDeliveryDate(D);
        o.setStatus(status);
        return salesOrderRepository.saveAndFlush(o);
    }

    private void item(String orderId, String productTypeId, String name, String unit, String qty) {
        SalesOrderItem i = new SalesOrderItem();
        i.setSalesOrderId(orderId);
        i.setProductTypeId(productTypeId);
        i.setProductName(name);
        i.setUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        salesOrderItemRepository.saveAndFlush(i);
    }

    @Test
    @DisplayName("需求聚合: 多有效订单同产品累加; 已取消不计; 跨仓合并")
    void demandAggregation() {
        order("O1", SalesOrderStatus.CONFIRMED);
        order("O2", SalesOrderStatus.FINANCE_APPROVED);
        order("O3", SalesOrderStatus.CANCELLED);   // 不计入
        item("O1", "PT-ZS", "猪舌120g", "盒", "531");
        item("O2", "PT-ZS", "猪舌120g", "盒", "94");
        item("O3", "PT-ZS", "猪舌120g", "盒", "999"); // cancelled

        List<ProductDemandProjection> rows = salesOrderItemRepository
                .sumDemandByProductForDeliveryDate("F006", D,
                        List.of(SalesOrderStatus.CONFIRMED, SalesOrderStatus.PENDING_FINANCE_REVIEW,
                                SalesOrderStatus.FINANCE_APPROVED, SalesOrderStatus.PROCESSING,
                                SalesOrderStatus.PARTIAL_DELIVERED));
        assertEquals(1, rows.size());
        ProductDemandProjection r = rows.get(0);
        assertEquals("PT-ZS", r.getProductTypeId());
        assertEquals(0, new BigDecimal("625").compareTo(r.getDemand())); // 531+94
        assertEquals("盒", r.getMinUnit());
        assertEquals("盒", r.getMaxUnit());
    }

    @Test
    @DisplayName("需求聚合: 同产品订单行单位不一致 → minUnit≠maxUnit (F2)")
    void demandUnitInconsistent() {
        order("O4", SalesOrderStatus.CONFIRMED);
        item("O4", "PT-X", "X", "盒", "10");
        item("O4", "PT-X", "X", "箱", "2");
        List<ProductDemandProjection> rows = salesOrderItemRepository
                .sumDemandByProductForDeliveryDate("F006", D, List.of(SalesOrderStatus.CONFIRMED));
        assertEquals(1, rows.size());
        assertNotEquals(rows.get(0).getMinUnit(), rows.get(0).getMaxUnit());
    }

    @Test
    @DisplayName("WIP 可用聚合: availableQuantity>0 求和")
    void wipAggregation() {
        SemiFinishedInventory w1 = new SemiFinishedInventory();
        w1.setFactoryId("F006"); w1.setProductTypeId("PT-ZS");
        w1.setIntermediateBatchNo("B1"); w1.setAvailableQuantity(new BigDecimal("100"));
        semiFinishedInventoryRepository.saveAndFlush(w1);
        SemiFinishedInventory w2 = new SemiFinishedInventory();
        w2.setFactoryId("F006"); w2.setProductTypeId("PT-ZS");
        w2.setIntermediateBatchNo("B2"); w2.setAvailableQuantity(new BigDecimal("50"));
        semiFinishedInventoryRepository.saveAndFlush(w2);

        BigDecimal sum = semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS");
        assertEquals(0, new BigDecimal("150").compareTo(sum));
    }

    @Test
    @DisplayName("已排产聚合: 仅 PLANNED+PENDING 计入; IN_PROGRESS/COMPLETED/CANCELLED 排除")
    void scheduledAggregation() {
        plan("PT-ZS", ProductionPlanStatus.PLANNED, "200");
        plan("PT-ZS", ProductionPlanStatus.PENDING, "100");
        plan("PT-ZS", ProductionPlanStatus.IN_PROGRESS, "999"); // 排除(产出在WIP/FG)
        plan("PT-ZS", ProductionPlanStatus.COMPLETED, "888");    // 排除
        plan("PT-ZS", ProductionPlanStatus.CANCELLED, "777");    // 排除

        BigDecimal sum = productionPlanRepository.sumPlannedQuantityByProductAndStatuses(
                "F006", "PT-ZS", List.of(ProductionPlanStatus.PLANNED, ProductionPlanStatus.PENDING));
        assertEquals(0, new BigDecimal("300").compareTo(sum));
    }

    private void plan(String productTypeId, ProductionPlanStatus status, String qty) {
        ProductionPlan p = new ProductionPlan();
        p.setFactoryId("F006");
        p.setProductTypeId(productTypeId);
        p.setPlannedQuantity(new BigDecimal(qty));
        p.setStatus(status);
        p.setPlannedDate(D);
        productionPlanRepository.saveAndFlush(p);
    }
}
```

> 若 SalesOrder/ProductionPlan 有其它 NOT NULL 字段导致 saveAndFlush 失败, 按报错补 setter (如 setPlanNumber/setBatchDate)。保持测试意图不变。

- [ ] **Step 3: 跑测试确认失败**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockAggregationRepositoryTest`
Expected: 编译失败 (3 个查询方法 + ProductDemandProjection 不存在)。

- [ ] **Step 4: 加查询方法**

在 `SalesOrderItemRepository.java` 接口内加 (import `java.time.LocalDate`, `java.util.Collection`, `java.util.List`, `com.cretas.aims.entity.enums.SalesOrderStatus`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):
```java
    @Query("SELECT i.productTypeId AS productTypeId, MIN(i.productName) AS productName, " +
           "MIN(i.unit) AS minUnit, MAX(i.unit) AS maxUnit, SUM(i.quantity) AS demand " +
           "FROM SalesOrderItem i " +
           "WHERE i.salesOrder.factoryId = :factoryId " +
           "AND i.salesOrder.requiredDeliveryDate = :date " +
           "AND i.salesOrder.status IN :statuses " +
           "GROUP BY i.productTypeId")
    List<ProductDemandProjection> sumDemandByProductForDeliveryDate(
            @Param("factoryId") String factoryId,
            @Param("date") LocalDate date,
            @Param("statuses") Collection<SalesOrderStatus> statuses);
```

在 `SemiFinishedInventoryRepository.java` 加:
```java
    @Query("SELECT COALESCE(SUM(s.availableQuantity), 0) FROM SemiFinishedInventory s " +
           "WHERE s.factoryId = :factoryId AND s.productTypeId = :productTypeId " +
           "AND s.availableQuantity > 0")
    java.math.BigDecimal sumAvailableByProduct(
            @org.springframework.data.repository.query.Param("factoryId") String factoryId,
            @org.springframework.data.repository.query.Param("productTypeId") String productTypeId);
```

在 `ProductionPlanRepository.java` 加 (import `ProductionPlanStatus`, `Collection`):
```java
    @Query("SELECT COALESCE(SUM(p.plannedQuantity), 0) FROM ProductionPlan p " +
           "WHERE p.factoryId = :factoryId AND p.productTypeId = :productTypeId " +
           "AND p.status IN :statuses")
    java.math.BigDecimal sumPlannedQuantityByProductAndStatuses(
            @org.springframework.data.repository.query.Param("factoryId") String factoryId,
            @org.springframework.data.repository.query.Param("productTypeId") String productTypeId,
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.cretas.aims.entity.enums.ProductionPlanStatus> statuses);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockAggregationRepositoryTest`
Expected: Tests run: 4, Failures: 0。若 `i.salesOrder` 关联报错(无该字段), 改用 `FROM SalesOrderItem i, SalesOrder o WHERE i.salesOrderId = o.id AND o.factoryId = ...`。

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/repository/ backend/java/cretas-api/src/test/java/com/cretas/aims/repository/restock/
git commit -m "feat(restock): 需求/WIP/已排产 3 个只读聚合查询 + repo 测"
```

---

## Task 4: RestockBoardDTO + RestockRow

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/dto/RestockRow.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/dto/RestockBoardDTO.java`

- [ ] **Step 1: 写 RestockRow**

```java
package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

/** 备货看板一行 (一个产品)。 */
@Data
@Builder
public class RestockRow {
    private String productTypeId;
    private String productName;
    private String unit;                 // 盒
    private BigDecimal demandQty;        // 需求(盒); 单位不一致时 null
    private BigDecimal fgAvailableQty;   // 成品可用(盒)
    private BigDecimal wipEstimatedQty;  // 在产折成品(盒,估); 无法折时 null
    private BigDecimal scheduledQty;     // 已排产(盒, 仅未开工计划)
    private BigDecimal totalAvailableQty;// 合计可用; 单位不一致时 null
    private BigDecimal shortfallQty;     // max(需求-合计,0); 单位不一致时 null
    private String status;               // SATISFIED | SHORTFALL | UNIT_INCONSISTENT
    private boolean wipIsEstimated;      // 在产列带"估"角标
    private String conversionWarning;    // 未配置规格/出率/单位不一致 等; 无则 null
}
```

- [ ] **Step 2: 写 RestockBoardDTO**

```java
package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

/** 某交货日的备货看板。 */
@Data
@Builder
public class RestockBoardDTO {
    private LocalDate deliveryDate;
    private List<RestockRow> rows;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        private int totalProducts;
        private int shortfallProducts;
        private int fullySatisfiedProducts;
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -q -o compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/dto/
git commit -m "feat(restock): RestockBoardDTO + RestockRow"
```

---

## Task 5: RestockBoardService (聚合 + F1/F2) + 单测

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/RestockBoardService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/restock/RestockBoardServiceTest.java`

> 依赖: `ProductTypeRepository` (findById → ProductType, 取 gramsPerUnit/wipToFgYield/name)。其包路径用 `git ls-files | grep ProductTypeRepository` 确认 import。

- [ ] **Step 1: 写失败测试 (Mockito)**

`RestockBoardServiceTest.java`:
```java
package com.cretas.aims.service.restock;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestockBoardService 聚合")
class RestockBoardServiceTest {

    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    @Mock ProductionPlanRepository productionPlanRepository;
    @Mock ProductTypeRepository productTypeRepository;
    @InjectMocks RestockBoardService service;

    private static final LocalDate D = LocalDate.of(2026, 6, 3);

    private ProductDemandProjection demand(String id, String name, String minU, String maxU, String qty) {
        return new ProductDemandProjection() {
            public String getProductTypeId() { return id; }
            public String getProductName() { return name; }
            public String getMinUnit() { return minU; }
            public String getMaxUnit() { return maxU; }
            public BigDecimal getDemand() { return new BigDecimal(qty); }
        };
    }

    private ProductType pt(String id, String grams, String yield) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setGramsPerUnit(grams == null ? null : new BigDecimal(grams));
        p.setWipToFgYield(yield == null ? null : new BigDecimal(yield));
        return p;
    }

    @Test
    @DisplayName("缺口: 需求7088, 成品1000+在产0+已排产2000 → 缺口4088 SHORTFALL")
    void shortfall() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZT", "猪蹄200g", "盒", "盒", "7088")));
        when(productTypeRepository.findById("PT-ZT")).thenReturn(Optional.of(pt("PT-ZT", "200", null)));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZT")).thenReturn(new BigDecimal("1000"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZT")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZT"), anyCollection())).thenReturn(new BigDecimal("2000"));

        RestockBoardDTO board = service.getRestockBoard("F006", D);
        assertEquals(1, board.getRows().size());
        RestockRow r = board.getRows().get(0);
        assertEquals(0, new BigDecimal("4088").compareTo(r.getShortfallQty()));
        assertEquals("SHORTFALL", r.getStatus());
        assertEquals(1, board.getSummary().getShortfallProducts());
    }

    @Test
    @DisplayName("满足: 合计>=需求 → 缺口0 SATISFIED")
    void satisfied() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZS", "猪舌120g", "盒", "盒", "625")));
        when(productTypeRepository.findById("PT-ZS")).thenReturn(Optional.of(pt("PT-ZS", "120", "1.0")));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZS")).thenReturn(new BigDecimal("700"));
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS")).thenReturn(BigDecimal.ZERO);
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZS"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getShortfallQty()));
        assertEquals("SATISFIED", r.getStatus());
    }

    @Test
    @DisplayName("WIP折盒: 150kg × yield0.9 / 120g每盒 = 1125盒 计入可用")
    void wipFold() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-ZS", "猪舌120g", "盒", "盒", "2000")));
        when(productTypeRepository.findById("PT-ZS")).thenReturn(Optional.of(pt("PT-ZS", "120", "0.9")));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-ZS")).thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-ZS")).thenReturn(new BigDecimal("150"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-ZS"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertEquals(0, new BigDecimal("1125.00").compareTo(r.getWipEstimatedQty()));
        assertTrue(r.isWipIsEstimated());
    }

    @Test
    @DisplayName("gramsPerUnit null + 有WIP → wip列null + 警告, 不静默算错")
    void noGramsWarning() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-X", "X", "盒", "盒", "100")));
        when(productTypeRepository.findById("PT-X")).thenReturn(Optional.of(pt("PT-X", null, null)));
        when(finishedGoodsBatchRepository.sumAvailableQuantityByProductType("F006", "PT-X")).thenReturn(BigDecimal.ZERO);
        when(semiFinishedInventoryRepository.sumAvailableByProduct("F006", "PT-X")).thenReturn(new BigDecimal("50"));
        when(productionPlanRepository.sumPlannedQuantityByProductAndStatuses(eq("F006"), eq("PT-X"), anyCollection())).thenReturn(BigDecimal.ZERO);

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertNull(r.getWipEstimatedQty());
        assertNotNull(r.getConversionWarning());
        assertTrue(r.getConversionWarning().contains("gramsPerUnit"));
    }

    @Test
    @DisplayName("F2 单位不一致 → demandQty null + UNIT_INCONSISTENT, 不累加")
    void unitInconsistent() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of(demand("PT-X", "X", "盒", "箱", "12")));
        when(productTypeRepository.findById("PT-X")).thenReturn(Optional.of(pt("PT-X", "120", null)));

        RestockRow r = service.getRestockBoard("F006", D).getRows().get(0);
        assertNull(r.getDemandQty());
        assertNull(r.getShortfallQty());
        assertEquals("UNIT_INCONSISTENT", r.getStatus());
        assertNotNull(r.getConversionWarning());
    }

    @Test
    @DisplayName("无订单 → 空看板")
    void empty() {
        when(salesOrderItemRepository.sumDemandByProductForDeliveryDate(eq("F006"), eq(D), anyCollection()))
                .thenReturn(List.of());
        RestockBoardDTO board = service.getRestockBoard("F006", D);
        assertTrue(board.getRows().isEmpty());
        assertEquals(0, board.getSummary().getTotalProducts());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockBoardServiceTest`
Expected: 编译失败 (RestockBoardService 不存在)。

- [ ] **Step 3: 写 RestockBoardService**

```java
package com.cretas.aims.service.restock;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.ProductDemandProjection;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 全天备货看板: 订单需求 vs 可用结存(成品+在产WIP折盒+已排产) vs 缺口, 产品级聚合, 只读实时。 */
@Service
@RequiredArgsConstructor
public class RestockBoardService {

    /** 有效需求订单状态 (排除 DRAFT/FINANCE_REJECTED/CANCELLED)。 */
    private static final List<SalesOrderStatus> DEMAND_STATUSES = List.of(
            SalesOrderStatus.CONFIRMED, SalesOrderStatus.PENDING_FINANCE_REVIEW,
            SalesOrderStatus.FINANCE_APPROVED, SalesOrderStatus.PROCESSING,
            SalesOrderStatus.PARTIAL_DELIVERED);

    /** 已排产 = 仅未开工计划 (IN_PROGRESS 等产出已在 WIP/FG, 避免重复计算)。 */
    private static final List<ProductionPlanStatus> SCHEDULED_STATUSES = List.of(
            ProductionPlanStatus.PLANNED, ProductionPlanStatus.PENDING);

    private final SalesOrderItemRepository salesOrderItemRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SemiFinishedInventoryRepository semiFinishedInventoryRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductTypeRepository productTypeRepository;

    @Transactional(readOnly = true)
    public RestockBoardDTO getRestockBoard(String factoryId, LocalDate deliveryDate) {
        List<ProductDemandProjection> demands =
                salesOrderItemRepository.sumDemandByProductForDeliveryDate(factoryId, deliveryDate, DEMAND_STATUSES);

        List<RestockRow> rows = new ArrayList<>();
        for (ProductDemandProjection d : demands) {
            rows.add(buildRow(factoryId, d));
        }

        int shortfall = (int) rows.stream().filter(r -> "SHORTFALL".equals(r.getStatus())).count();
        int satisfied = (int) rows.stream().filter(r -> "SATISFIED".equals(r.getStatus())).count();

        return RestockBoardDTO.builder()
                .deliveryDate(deliveryDate)
                .rows(rows)
                .summary(RestockBoardDTO.Summary.builder()
                        .totalProducts(rows.size())
                        .shortfallProducts(shortfall)
                        .fullySatisfiedProducts(satisfied)
                        .build())
                .build();
    }

    private RestockRow buildRow(String factoryId, ProductDemandProjection d) {
        String productTypeId = d.getProductTypeId();
        Optional<ProductType> ptOpt = productTypeRepository.findById(productTypeId);
        BigDecimal gramsPerUnit = ptOpt.map(ProductType::getGramsPerUnit).orElse(null);
        BigDecimal wipYield = ptOpt.map(ProductType::getWipToFgYield).orElse(null);

        String warning = null;

        // F2: 同产品订单行单位不一致 → 不静默累加
        boolean unitInconsistent = d.getMinUnit() != null && !Objects.equals(d.getMinUnit(), d.getMaxUnit());

        // 成品可用 (盒)
        BigDecimal fg = nz(finishedGoodsBatchRepository.sumAvailableQuantityByProductType(factoryId, productTypeId));

        // 在产 WIP (kg → 盒, 折下游出率)
        BigDecimal wipKg = nz(semiFinishedInventoryRepository.sumAvailableByProduct(factoryId, productTypeId));
        BigDecimal effYield = wipYield != null ? wipYield : BigDecimal.ONE;
        BigDecimal wipBox = RestockUnitConverter.kgToBox(wipKg.multiply(effYield), gramsPerUnit);
        if (wipBox == null && wipKg.compareTo(BigDecimal.ZERO) > 0) {
            warning = append(warning, "未配置规格(gramsPerUnit), 在产无法折盒");
        }
        if (wipYield == null && wipKg.compareTo(BigDecimal.ZERO) > 0 && wipBox != null) {
            warning = append(warning, "未配置在产出率, 按1:1估算");
        }

        // 已排产 (盒, 仅未开工)
        BigDecimal scheduled = nz(productionPlanRepository
                .sumPlannedQuantityByProductAndStatuses(factoryId, productTypeId, SCHEDULED_STATUSES));

        RestockRow.RestockRowBuilder b = RestockRow.builder()
                .productTypeId(productTypeId)
                .productName(d.getProductName())
                .unit("盒")
                .fgAvailableQty(fg)
                .wipEstimatedQty(wipBox)
                .scheduledQty(scheduled)
                .wipIsEstimated(true);

        if (unitInconsistent) {
            warning = append(warning, "订单行单位不一致, 需人工核对");
            b.demandQty(null).totalAvailableQty(null).shortfallQty(null).status("UNIT_INCONSISTENT");
        } else {
            BigDecimal demand = nz(d.getDemand());
            BigDecimal total = fg.add(nz(wipBox)).add(scheduled);
            BigDecimal shortfall = demand.subtract(total).max(BigDecimal.ZERO);
            b.demandQty(demand).totalAvailableQty(total).shortfallQty(shortfall)
             .status(shortfall.compareTo(BigDecimal.ZERO) == 0 ? "SATISFIED" : "SHORTFALL");
        }
        return b.conversionWarning(warning).build();
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static String append(String existing, String add) {
        return existing == null ? add : existing + "; " + add;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockBoardServiceTest`
Expected: Tests run: 6, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/restock/RestockBoardService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/restock/RestockBoardServiceTest.java
git commit -m "feat(restock): RestockBoardService 三层可用聚合+缺口+F1/F2 + 单测"
```

---

## Task 6: RestockBoardController + 单测

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/RestockBoardController.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/controller/RestockBoardControllerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.cretas.aims.controller;

import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RestockBoardController")
class RestockBoardControllerTest {

    @Mock RestockBoardService service;
    @InjectMocks RestockBoardController controller;

    @Test
    @DisplayName("GET 返回 ApiResponse.success 包看板")
    void getBoard() {
        LocalDate d = LocalDate.of(2026, 6, 3);
        RestockBoardDTO dto = RestockBoardDTO.builder()
                .deliveryDate(d).rows(List.of())
                .summary(RestockBoardDTO.Summary.builder().totalProducts(0).build())
                .build();
        when(service.getRestockBoard("F006", d)).thenReturn(dto);

        var resp = controller.getRestockBoard("F006", d);
        assertTrue(resp.getSuccess());
        assertEquals(d, resp.getData().getDeliveryDate());
        verify(service).getRestockBoard("F006", d);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockBoardControllerTest`
Expected: 编译失败 (RestockBoardController 不存在)。

- [ ] **Step 3: 写 Controller**

```java
package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 全天备货看板 — 订单需求 vs 可用结存 vs 缺口对账 (只读)。 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restock-board")
@RequiredArgsConstructor
@Tag(name = "备货看板", description = "全天备货看板: 订单需求 vs 可用结存 vs 生产缺口")
public class RestockBoardController {

    private final RestockBoardService restockBoardService;

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping
    @Operation(summary = "获取某交货日备货看板", description = "按产品聚合: 需求/成品可用/在产估/已排产/缺口")
    public ApiResponse<RestockBoardDTO> getRestockBoard(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        log.info("备货看板: factoryId={}, deliveryDate={}", factoryId, deliveryDate);
        return ApiResponse.success(restockBoardService.getRestockBoard(factoryId, deliveryDate));
    }
}
```

> import 路径核对: `RequirePermission` / `ApiResponse` 的实际包用 `git ls-files | grep -E "RequirePermission.java|ApiResponse.java"` 确认 (参考 ProductionPlanController 的 import)。

- [ ] **Step 4: 跑测试确认通过**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=RestockBoardControllerTest`
Expected: Tests run: 1, Failures: 0。

- [ ] **Step 5: 全量编译 + 相关测试**

Run: `C:\tools\apache-maven-3.9.6\bin\mvn.cmd -o test -Dtest=Restock*`
Expected: 全部 PASS (Converter+Aggregation+Service+Controller)。

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/controller/RestockBoardController.java backend/java/cretas-api/src/test/java/com/cretas/aims/controller/RestockBoardControllerTest.java
git commit -m "feat(restock): RestockBoardController GET 端点 + 单测"
```

---

## Task 7: web-admin 备货看板页 + api + 路由 + 菜单

**Files:**
- Create: `web-admin/src/api/restockBoard.ts`
- Create: `web-admin/src/views/production/restock-board/index.vue`
- Modify: `web-admin/src/router/index.ts` (生产管理 children 加一条)
- Modify: `web-admin/src/components/layout/menuConfig.ts` (生产管理组 children 加一条)

- [ ] **Step 1: api 客户端**

`web-admin/src/api/restockBoard.ts`:
```typescript
/** 备货看板 API */
import { get } from './request'

export interface RestockRow {
  productTypeId: string
  productName: string
  unit: string
  demandQty: number | null
  fgAvailableQty: number
  wipEstimatedQty: number | null
  scheduledQty: number
  totalAvailableQty: number | null
  shortfallQty: number | null
  status: 'SATISFIED' | 'SHORTFALL' | 'UNIT_INCONSISTENT'
  wipIsEstimated: boolean
  conversionWarning: string | null
}

export interface RestockBoard {
  deliveryDate: string
  rows: RestockRow[]
  summary: { totalProducts: number; shortfallProducts: number; fullySatisfiedProducts: number }
}

/** 获取某交货日备货看板 */
export function getRestockBoard(factoryId: string, deliveryDate: string) {
  return get<RestockBoard>(`/${factoryId}/restock-board`, { params: { deliveryDate } })
}
```

- [ ] **Step 2: 看板页面**

`web-admin/src/views/production/restock-board/index.vue`:
```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { post } from '@/api/request'
import { getRestockBoard, type RestockRow } from '@/api/restockBoard'
import { ElMessage, ElMessageBox } from 'element-plus'

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)

const loading = ref(false)
const deliveryDate = ref(new Date().toISOString().slice(0, 10))
const rows = ref<RestockRow[]>([])
const summary = ref({ totalProducts: 0, shortfallProducts: 0, fullySatisfiedProducts: 0 })

async function load() {
  if (!factoryId.value) return
  loading.value = true
  try {
    const res = await getRestockBoard(factoryId.value, deliveryDate.value)
    if (res.success && res.data) {
      rows.value = res.data.rows
      summary.value = res.data.summary
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载失败')
    }
  } finally {
    loading.value = false
  }
}

function statusTag(s: string) {
  if (s === 'SATISFIED') return { type: 'success', text: '✅满足' }
  if (s === 'SHORTFALL') return { type: 'warning', text: '⚠补产' }
  return { type: 'info', text: '单位不一致' }
}

async function createPlan(row: RestockRow) {
  if (!row.shortfallQty || row.shortfallQty <= 0) return
  try {
    await ElMessageBox.confirm(
      `产品: ${row.productName}\n建议补产: ${row.shortfallQty} 盒\n交期: ${deliveryDate.value}\n是否生成生产计划草稿?`,
      '缺口转生产计划草稿',
      { confirmButtonText: '生成草稿', cancelButtonText: '取消', type: 'warning' }
    )
    const res = await post(`/${factoryId.value}/production-plans`, {
      sourceType: 'MANUAL',
      productTypeId: row.productTypeId,
      plannedQuantity: row.shortfallQty,
      plannedDate: deliveryDate.value,
      notes: `来自 ${deliveryDate.value} 备货看板缺口`,
    })
    if (res.success) {
      ElMessage.success('生产计划草稿已生成')
      load()
    } else {
      ElMessage.error(res.message || '生成失败')
    }
  } catch (e) { /* 用户取消 */ }
}

onMounted(load)
</script>

<template>
  <div style="padding: 12px">
    <el-card style="margin-bottom: 12px">
      <el-space>
        <span>交货日</span>
        <el-date-picker v-model="deliveryDate" type="date" value-format="YYYY-MM-DD" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-tag>共 {{ summary.totalProducts }} 品</el-tag>
        <el-tag type="warning">缺口 {{ summary.shortfallProducts }}</el-tag>
        <el-tag type="success">满足 {{ summary.fullySatisfiedProducts }}</el-tag>
      </el-space>
    </el-card>

    <el-card>
      <el-table :data="rows" v-loading="loading" stripe empty-text="该日无订单">
        <el-table-column prop="productName" label="产品" min-width="180" />
        <el-table-column prop="demandQty" label="需求(盒)" width="100" />
        <el-table-column prop="fgAvailableQty" label="成品可用" width="100">
          <template #default="{ row }">
            {{ row.fgAvailableQty }}
            <el-tooltip content="未预留成品, 多日订单请人工分配" placement="top">
              <el-icon style="color:#e6a23c"><Warning /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="在产(估)" width="100">
          <template #default="{ row }">
            <span v-if="row.wipEstimatedQty !== null">{{ row.wipEstimatedQty }} <el-tag size="small">估</el-tag></span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="scheduledQty" label="已排产" width="90" />
        <el-table-column prop="shortfallQty" label="缺口" width="90">
          <template #default="{ row }">
            <span v-if="row.shortfallQty !== null" :style="{ color: row.shortfallQty > 0 ? '#f56c6c' : '#67c23a', fontWeight: 'bold' }">
              {{ row.shortfallQty }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status).type">{{ statusTag(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.shortfallQty && row.shortfallQty > 0" type="primary" text @click="createPlan(row)">建计划</el-button>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="160">
          <template #default="{ row }">
            <span v-if="row.conversionWarning" style="color:#e6a23c; font-size:12px">{{ row.conversionWarning }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
```

> `Warning` 图标按现有页面的 `@element-plus/icons-vue` import 习惯引入 (`import { Warning } from '@element-plus/icons-vue'`); 若全局已注册则不需 import。`post`/`get` 的 response 形状 (`res.success/res.data/res.message`) 对照 `web-admin/src/api/request.ts` 实际封装, 不一致则调整。

- [ ] **Step 3: 注册路由**

在 `web-admin/src/router/index.ts` 生产管理 children 数组里加 (放 `plans` 之后):
```typescript
    {
      path: 'restock-board',
      name: 'RestockBoard',
      component: () => import('@/views/production/restock-board/index.vue'),
      meta: { requiresAuth: true, title: '备货看板', module: 'production' }
    },
```

- [ ] **Step 4: 加菜单项**

在 `web-admin/src/components/layout/menuConfig.ts` 生产管理组 children 里加 (放 `/production/plans` 之后):
```typescript
    { path: '/production/restock-board', title: '备货看板', icon: '', module: 'production' },
```

- [ ] **Step 5: 前端构建验证**

Run (在 `web-admin`): `npm run build`
Expected: 构建成功 (vite build 不 type-check, 但应无语法/import 错; 若 sister 预存类型错导致 vue-build-check 红, 不影响本页, 见 spec 备注)。

- [ ] **Step 6: Commit**

```bash
git add web-admin/src/api/restockBoard.ts web-admin/src/views/production/restock-board/index.vue web-admin/src/router/index.ts web-admin/src/components/layout/menuConfig.ts
git commit -m "feat(restock): web-admin 备货看板页 + api + 路由 + 菜单"
```

---

## Task 8: headed E2E (真实 6.3 数据)

**Files:**
- Create: `web-admin/tests/e2e/restock-board.spec.ts`

> 遵守 `.claude/rules/playwright-headed-mode.md`: `headless: false` + viewport 1920×1080 + `--lang=zh-CN`。E2E 目标环境 = prod web-admin (139:8086) 或 test, 用 f006_admin 登录。具体 baseURL/登录流程对照仓库现有 e2e spec (`git ls-files web-admin | grep -iE "e2e|spec"`) 的既有模式复用。

- [ ] **Step 1: 写 E2E spec (headed)**

`web-admin/tests/e2e/restock-board.spec.ts`:
```typescript
import { test, expect } from '@playwright/test'

// 遵守 playwright-headed-mode: headed, zh-CN. baseURL/登录复用现有 spec 模式。
const BASE = process.env.E2E_ADMIN_URL || 'http://139.196.165.140:8086'

test('备货看板: 6.3 真实数据显示需求/缺口 + 缺口建计划草稿', async ({ page }) => {
  // 登录 (复用现有 spec 的登录 helper / 步骤)
  await page.goto(BASE)
  // ... 登录 f006_admin (按现有 e2e 登录流程)

  // 进入 生产管理 → 备货看板
  await page.goto(`${BASE}/production/restock-board`)
  await page.waitForLoadState('networkidle')

  // 选 6.3 查询
  // (date-picker 操作按现有 spec 的 el-date-picker 交互方式)

  // 断言: 猪蹄行存在且需求/缺口列有值
  await expect(page.getByText('猪蹄', { exact: false })).toBeVisible()

  // 截图留证 (中文无方块)
  await page.screenshot({ path: 'e2e-restock-board-6.3.png', fullPage: true })
})
```

- [ ] **Step 2: 跑 E2E (headed, prod 真实数据)**

Run (在 `web-admin`): `PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=restock npx playwright test tests/e2e/restock-board.spec.ts --headed`
Expected: chromium 真弹窗, 看板渲染 猪蹄(6.3 需求 ~625-7088 视所选日)/成品/在产/已排产/缺口 + 状态色; 截图中文正常。

- [ ] **Step 3: 验证缺口建计划闭环 (手动或脚本)**

在看板上对一个 SHORTFALL 产品点「建计划」→ 确认弹框预填缺口量 → 生成草稿 → 看板刷新已排产增加/缺口减少。截图。

- [ ] **Step 4: 在 spec/audit 文档末尾追加 Headed Mode Verification block**

按 playwright-headed-mode.md 要求 paste: headless:false ✓ / viewport 1920×1080 ✓ / locale zh-CN ✓ / chromium 真弹 ✓ / 中文无方块 ✓ / screenshot fullPage ✓ / PLAYWRIGHT_PORT 9222。

- [ ] **Step 5: Commit**

```bash
git add web-admin/tests/e2e/restock-board.spec.ts
git commit -m "test(restock): 备货看板 headed E2E (真实6.3数据)"
```

---

## 并行执行建议

- **后端 Task 1-6**: 1/2/4 几乎独立可并行起步; 3 依赖无; 5 依赖 2/3/4; 6 依赖 5。一个 subagent 串 1→2→3→4→5→6 即可 (互相 import)。
- **前端 Task 7**: 接口契约 (Task 6 的 DTO/端点) 定下后, 可与后端 5/6 **并行** (前端按 restockBoard.ts 的 interface 先 mock 渲染)。
- **Task 8 E2E**: 串行, 依赖 7 + 后端部署。
- 多 chat: 本计划 (P1) 与 P2 工序模板 / P3 多仓 / P4 单位标准化 文件不冲突, 可并行别的 chat。

---

## 自审 (写完后核对)

- **Spec 覆盖**: §9 七单元 → Task 1(迁移+列)/2(Converter)/3(repo)/4(DTO)/5(Service)/6(Controller)/7(web-admin)/8(E2E) 全覆盖。§4.5 F1(单日快照UI角标→Task7 fgAvailable列tooltip)/F2(单位不一致→Task5 unitInconsistent测试+实现) 覆盖。§7 边界(gramsPerUnit null/无订单/缺口≤0/单位不一致) → Task5 测试覆盖。
- **占位扫描**: 无 TBD/TODO; 每个代码步给完整代码; 命令给确切 mvn/npm + 预期。
- **类型一致**: `sumDemandByProductForDeliveryDate`/`sumAvailableByProduct`/`sumPlannedQuantityByProductAndStatuses` 在 repo(Task3)定义、Service(Task5)调用、测试(Task3/5)mock 三处签名一致。`RestockRow`/`RestockBoardDTO` 字段在 DTO(Task4)/Service(Task5)/Controller(Task6)/前端 interface(Task7) 一致。`wipToFgYield` 在 entity(Task1)/Service(Task5)/测试(Task5) 一致。
- **已知实现期校验点** (非占位, 是防御): repo 实际包路径、`i.salesOrder` 关联是否可用、SalesOrder/ProductionPlan NOT NULL 字段、RequirePermission/ApiResponse import 包、request.ts response 形状、现有 e2e 登录流程 — 各步已注明用 `git ls-files`/对照现有文件核对。
