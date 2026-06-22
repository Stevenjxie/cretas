# SP-A 配方 BOM 维护 + 调料成本算法 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立「按 SKU 绑定的配方(注射+熟制)」主数据 + 一个纯函数调料成本算法(锅序规则: 第一锅全量 + (n−1)锅×比例, 老汤不计入, 比例 per-SKU 可配)，供后续 SP-C 成本引擎调用。

**Architecture:** 两张表 `product_recipes`(配方头, 1 SKU 1 条) + `recipe_ingredients`(明细, 注射段/熟制段)。一个无状态 `RecipeCostCalculator`(纯函数, BigDecimal) 是 keystone。CRUD service/controller 走项目既有 `@RequirePermission` + `ApiResponse` 范式。web-admin 一个 list+dialog 维护页。**只测 DEMO_FACTORY，绝不碰 F006/LIUSHANMEN。**

**Tech Stack:** Java 21 + Spring Boot 3 + JPA(Hibernate6) + PostgreSQL(Flyway) + JUnit5/Mockito；web-admin Vue3 + Element Plus + axios。

**Spec:** `docs/superpowers/specs/2026-06-22-clerk-process-entry-recipe-cost-design.md` §3。

**隔离铁律:** 实现前 `git worktree add -b feat/sp-a-recipe ../cretas-sp-a origin/main`。commit 用 `git commit -- <paths>`。Flyway 号 `V20261027_01`(origin/main 当前最高 `V20261026_07`，organizer 已为 SP-A 预留 27_01；SP-C 用 27_02，勿撞)。

---

### Task 1: Flyway 迁移 — 建两张表

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20261027_01__create_product_recipes.sql`

- [ ] **Step 1: 写迁移 SQL**

```sql
-- SP-A 配方 BOM: product_recipes(配方头) + recipe_ingredients(明细)
-- Spec 2026-06-22 §3.1. 1 SKU 1 条 ACTIVE 配方; 锅序规则(subsequent_pot_ratio)在配方头.

CREATE TABLE IF NOT EXISTS product_recipes (
    id VARCHAR(64) NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    product_type_id VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    injection_rate DECIMAL(8,4),
    cooking_pot_base_kg DECIMAL(12,3),
    subsequent_pot_ratio DECIMAL(8,4) NOT NULL DEFAULT 0.3333,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_precipe_factory_product
    ON product_recipes (factory_id, product_type_id);

CREATE TABLE IF NOT EXISTS recipe_ingredients (
    id VARCHAR(64) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    factory_id VARCHAR(64) NOT NULL,
    section VARCHAR(20) NOT NULL,           -- INJECTION | COOKING
    seq INT NOT NULL DEFAULT 0,
    name VARCHAR(200) NOT NULL,
    dosage_per_kg_g DECIMAL(14,4) NOT NULL, -- 每kg原料用量(g)
    price_source1 DECIMAL(14,4),
    price_source2 DECIMAL(14,4),
    count_in_seasoning BOOLEAN NOT NULL DEFAULT TRUE,  -- 老汤=false
    remark VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ringredient_recipe
    ON recipe_ingredients (recipe_id);
```

- [ ] **Step 2: 提交**

```bash
git add backend/java/cretas-api/src/main/resources/db/flyway/V20261027_01__create_product_recipes.sql
git commit -m "feat(sp-a): flyway 建 product_recipes + recipe_ingredients" -- backend/java/cretas-api/src/main/resources/db/flyway/V20261027_01__create_product_recipes.sql
```

---

### Task 2: 实体 ProductRecipe + RecipeIngredient

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/ProductRecipe.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/RecipeIngredient.java`

- [ ] **Step 1: 写 ProductRecipe 实体**

```java
package com.cretas.aims.entity.recipe;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "product_recipes", indexes = {
        @Index(name = "idx_precipe_factory_product", columnList = "factory_id,product_type_id")
})
@Where(clause = "deleted_at IS NULL")
public class ProductRecipe extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 64)
    private String productTypeId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 注射率, 如 0.20 */
    @Column(name = "injection_rate", precision = 8, scale = 4)
    private BigDecimal injectionRate;

    /** 熟制每锅基准原料(kg), 如 160 */
    @Column(name = "cooking_pot_base_kg", precision = 12, scale = 3)
    private BigDecimal cookingPotBaseKg;

    /** 第二锅起比例, 默认 0.3333, per-SKU 可配 */
    @Column(name = "subsequent_pot_ratio", nullable = false, precision = 8, scale = 4)
    private BigDecimal subsequentPotRatio;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
```

- [ ] **Step 2: 写 RecipeIngredient 实体**

```java
package com.cretas.aims.entity.recipe;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "recipe_ingredients", indexes = {
        @Index(name = "idx_ringredient_recipe", columnList = "recipe_id")
})
@Where(clause = "deleted_at IS NULL")
public class RecipeIngredient extends BaseEntity {

    /** 注射段 / 熟制段 */
    public static final String SECTION_INJECTION = "INJECTION";
    public static final String SECTION_COOKING = "COOKING";

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    @Column(name = "recipe_id", nullable = false, length = 64)
    private String recipeId;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "section", nullable = false, length = 20)
    private String section;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 每kg原料用量(g) */
    @Column(name = "dosage_per_kg_g", nullable = false, precision = 14, scale = 4)
    private BigDecimal dosagePerKgG;

    @Column(name = "price_source1", precision = 14, scale = 4)
    private BigDecimal priceSource1;

    @Column(name = "price_source2", precision = 14, scale = 4)
    private BigDecimal priceSource2;

    /** 老汤/高汤 = false (不计入调料) */
    @Column(name = "count_in_seasoning", nullable = false)
    private Boolean countInSeasoning;

    @Column(name = "remark", length = 500)
    private String remark;
}
```

- [ ] **Step 3: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/
git commit -m "feat(sp-a): ProductRecipe + RecipeIngredient 实体" -- backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/ProductRecipe.java backend/java/cretas-api/src/main/java/com/cretas/aims/entity/recipe/RecipeIngredient.java
```

---

### Task 3: Repositories

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/ProductRecipeRepository.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/RecipeIngredientRepository.java`

- [ ] **Step 1: 写 ProductRecipeRepository**

```java
package com.cretas.aims.repository.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRecipeRepository extends JpaRepository<ProductRecipe, String> {

    List<ProductRecipe> findByFactoryId(String factoryId);

    Optional<ProductRecipe> findByFactoryIdAndId(String factoryId, String id);

    /** 同 SKU 的 ACTIVE 配方(唯一性校验). */
    Optional<ProductRecipe> findByFactoryIdAndProductTypeIdAndStatus(
            String factoryId, String productTypeId, String status);
}
```

- [ ] **Step 2: 写 RecipeIngredientRepository**

```java
package com.cretas.aims.repository.recipe;

import com.cretas.aims.entity.recipe.RecipeIngredient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, String> {

    List<RecipeIngredient> findByRecipeIdOrderBySeqAsc(String recipeId);

    void deleteByRecipeId(String recipeId);
}
```

- [ ] **Step 3: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/
git commit -m "feat(sp-a): recipe repositories" -- backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/ProductRecipeRepository.java backend/java/cretas-api/src/main/java/com/cretas/aims/repository/recipe/RecipeIngredientRepository.java
```

---

### Task 4: RecipeCostCalculator 纯函数（KEYSTONE — 锅序成本算法）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/RecipeCostCalculator.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/SeasoningCost.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/RecipeCostCalculatorTest.java`

- [ ] **Step 1: 写结果对象 SeasoningCost**

```java
package com.cretas.aims.service.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;

/** 调料成本算法结果(绝对¥ + per-kg 速率, 供展示/引擎). */
@Data
@AllArgsConstructor
public class SeasoningCost {
    private BigDecimal injectionCostPerKg;   // 注射/kg
    private BigDecimal cookingFullCostPerKg; // 熟制全量/kg
    private BigDecimal injectionTotal;       // 注射总¥
    private BigDecimal cookingTotal;         // 熟制总¥(含锅序)
    private BigDecimal total;                // 合计¥
}
```

- [ ] **Step 2: 写失败测试（先验公式 + 锅序 + 老汤 + 取最高）**

```java
package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RecipeCostCalculator 锅序调料成本")
class RecipeCostCalculatorTest {

    private ProductRecipe recipe(String ratio) {
        ProductRecipe r = new ProductRecipe();
        r.setSubsequentPotRatio(new BigDecimal(ratio));
        return r;
    }

    private RecipeIngredient ing(String section, String name, String dosageG,
                                 String p1, String p2, boolean countIn) {
        RecipeIngredient i = new RecipeIngredient();
        i.setSection(section);
        i.setName(name);
        i.setDosagePerKgG(new BigDecimal(dosageG));
        i.setPriceSource1(p1 == null ? null : new BigDecimal(p1));
        i.setPriceSource2(p2 == null ? null : new BigDecimal(p2));
        i.setCountInSeasoning(countIn);
        return i;
    }

    // 注射/kg = Σ INJECTION: dosage_g/1000 × max(p1,p2)
    @Test
    @DisplayName("注射/kg 取两源最高")
    void injectionPerKg_takesMaxPrice() {
        // 料A: 1000g/kg × max(2, 5)=5 → 5.0 ; 料B: 500g/kg × (p2 null→p1=4) → 2.0
        List<RecipeIngredient> ings = List.of(
                ing("INJECTION", "A", "1000", "2", "5", true),
                ing("INJECTION", "B", "500", "4", null, true));
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, new BigDecimal("10"), List.of(new BigDecimal("10")));
        assertEquals(new BigDecimal("7.0000"), c.getInjectionCostPerKg());
    }

    // 熟制全量/kg 排除 count_in_seasoning=false(老汤)
    @Test
    @DisplayName("熟制全量/kg 排除老汤")
    void cookingPerKg_excludesOldSoup() {
        List<RecipeIngredient> ings = List.of(
                ing("COOKING", "八角", "1000", "1", null, true),    // 1.0/kg
                ing("COOKING", "高汤", "1000", "99", null, false)); // 老汤, 不计
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO, List.of(new BigDecimal("100")));
        assertEquals(new BigDecimal("1.0000"), c.getCookingFullCostPerKg());
    }

    // N=1: 熟制总 = R × cookFull × 1
    @Test
    @DisplayName("N=1 第一锅全量")
    void cooking_onePot_full() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "1", null, true)); // 1.0/kg
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO, List.of(new BigDecimal("160")));
        assertEquals(new BigDecimal("160.0000"), c.getCookingTotal());
    }

    // N=2 等锅: pot1×1 + pot2×ratio
    @Test
    @DisplayName("N=2 第二锅 1/3")
    void cooking_twoPots_secondThird() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "3", null, true)); // 3.0/kg
        // 80kg ×3×1 + 80kg ×3×(1/3) = 240 + 80 = 320
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, BigDecimal.ZERO,
                List.of(new BigDecimal("80"), new BigDecimal("80")));
        assertEquals(new BigDecimal("320.0000"), c.getCookingTotal());
    }

    // N=3: pot1 full, pot2&3 ratio
    @Test
    @DisplayName("N=3 第三锅同第二锅(ratio)")
    void cooking_threePots() {
        List<RecipeIngredient> ings = List.of(ing("COOKING", "料", "1000", "3", null, true));
        // 100×3×1 + 100×3×0.5 + 100×3×0.5 = 300 + 150 + 150 = 600 (用 ratio=0.5 验可配)
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.5"), ings, BigDecimal.ZERO,
                List.of(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100")));
        assertEquals(new BigDecimal("600.0000"), c.getCookingTotal());
    }

    // 注射 R 与熟制锅原料独立
    @Test
    @DisplayName("注射总用注射R, 与熟制锅独立")
    void injectionTotal_usesInjectionR() {
        List<RecipeIngredient> ings = List.of(
                ing("INJECTION", "A", "1000", "1", null, true),  // 1.0/kg
                ing("COOKING", "料", "1000", "2", null, true));  // 2.0/kg
        // 注射总 = 307 × 1.0 = 307 ; 熟制总 = 160 × 2.0 × 1 = 320 ; total=627
        SeasoningCost c = RecipeCostCalculator.compute(
                recipe("0.3333"), ings, new BigDecimal("307"), List.of(new BigDecimal("160")));
        assertEquals(new BigDecimal("307.0000"), c.getInjectionTotal());
        assertEquals(new BigDecimal("320.0000"), c.getCookingTotal());
        assertEquals(new BigDecimal("627.0000"), c.getTotal());
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=RecipeCostCalculatorTest`
Expected: 编译失败 — `RecipeCostCalculator` / `compute` 不存在。

- [ ] **Step 4: 写 RecipeCostCalculator 实现**

```java
package com.cretas.aims.service.recipe;

import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 调料成本纯函数(无状态). Spec 2026-06-22 §3.2.
 * 注射/kg = Σ INJECTION: dosage_g/1000 × max(p1,p2) — 每锅同量
 * 熟制/kg(全量) = Σ COOKING ∧ countInSeasoning: dosage_g/1000 × max(p1,p2)
 * 注射总 = R注射 × 注射/kg
 * 熟制总 = Σ_i 锅i原料 × 熟制/kg(全量) × (i==0 ? 1 : ratio)
 */
public final class RecipeCostCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal G_PER_KG = new BigDecimal("1000");

    private RecipeCostCalculator() {}

    /**
     * @param recipe         配方头(读 subsequentPotRatio)
     * @param ingredients    全部明细(注射段+熟制段)
     * @param injectionRawKg 注射前生料投入重(kg) — Spec §3.2 R注射
     * @param potRawKgs      逐锅熟制原料(kg), size=锅数N; 第1个=第一锅
     */
    public static SeasoningCost compute(ProductRecipe recipe,
                                        List<RecipeIngredient> ingredients,
                                        BigDecimal injectionRawKg,
                                        List<BigDecimal> potRawKgs) {
        BigDecimal injPerKg = perKg(ingredients, RecipeIngredient.SECTION_INJECTION, false);
        BigDecimal cookPerKg = perKg(ingredients, RecipeIngredient.SECTION_COOKING, true);

        BigDecimal injectionTotal = nz(injectionRawKg).multiply(injPerKg)
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal ratio = recipe.getSubsequentPotRatio() == null
                ? new BigDecimal("0.3333") : recipe.getSubsequentPotRatio();

        BigDecimal cookingTotal = BigDecimal.ZERO;
        if (potRawKgs != null) {
            for (int i = 0; i < potRawKgs.size(); i++) {
                BigDecimal potFactor = (i == 0) ? BigDecimal.ONE : ratio;
                cookingTotal = cookingTotal.add(
                        nz(potRawKgs.get(i)).multiply(cookPerKg).multiply(potFactor));
            }
        }
        cookingTotal = cookingTotal.setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal total = injectionTotal.add(cookingTotal).setScale(SCALE, RoundingMode.HALF_UP);
        return new SeasoningCost(injPerKg, cookPerKg, injectionTotal, cookingTotal, total);
    }

    /** Σ section 明细: dosage_g/1000 × max(p1,p2); excludeOldSoup 时跳过 countInSeasoning=false. */
    private static BigDecimal perKg(List<RecipeIngredient> ingredients,
                                    String section, boolean excludeOldSoup) {
        BigDecimal sum = BigDecimal.ZERO;
        if (ingredients == null) return sum.setScale(SCALE, RoundingMode.HALF_UP);
        for (RecipeIngredient ing : ingredients) {
            if (!section.equals(ing.getSection())) continue;
            if (excludeOldSoup && !Boolean.TRUE.equals(ing.getCountInSeasoning())) continue;
            BigDecimal dosageKgPerKg = nz(ing.getDosagePerKgG()).divide(G_PER_KG, 8, RoundingMode.HALF_UP);
            sum = sum.add(dosageKgPerKg.multiply(maxPrice(ing)));
        }
        return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxPrice(RecipeIngredient ing) {
        BigDecimal p1 = ing.getPriceSource1();
        BigDecimal p2 = ing.getPriceSource2();
        if (p1 == null && p2 == null) return BigDecimal.ZERO;
        if (p1 == null) return p2;
        if (p2 == null) return p1;
        return p1.max(p2);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=RecipeCostCalculatorTest`
Expected: PASS (6 个测试全绿)。

- [ ] **Step 6: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/RecipeCostCalculator.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/SeasoningCost.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/RecipeCostCalculatorTest.java
git commit -m "feat(sp-a): RecipeCostCalculator 锅序调料成本纯函数 + 测试" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/RecipeCostCalculator.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/SeasoningCost.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/RecipeCostCalculatorTest.java
```

---

### Task 5: DTO（请求 + 响应）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/RecipeIngredientDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/ProductRecipeDTO.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/SaveRecipeRequest.java`

- [ ] **Step 1: 写 RecipeIngredientDTO**

```java
package com.cretas.aims.dto.recipe;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RecipeIngredientDTO {
    private String id;
    private String section;        // INJECTION | COOKING
    private Integer seq;
    private String name;
    private BigDecimal dosagePerKgG;
    private BigDecimal priceSource1;
    private BigDecimal priceSource2;
    private Boolean countInSeasoning;
    private String remark;
}
```

- [ ] **Step 2: 写 ProductRecipeDTO（含算出的 per-kg 速率, 供维护页显示）**

```java
package com.cretas.aims.dto.recipe;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductRecipeDTO {
    private String id;
    private String factoryId;
    private String productTypeId;
    private String name;
    private BigDecimal injectionRate;
    private BigDecimal cookingPotBaseKg;
    private BigDecimal subsequentPotRatio;
    private String status;
    private Integer version;
    private List<RecipeIngredientDTO> ingredients;

    // 算出的展示值(每kg原料)
    private BigDecimal injectionCostPerKg;
    private BigDecimal cookingFullCostPerKg;
    private BigDecimal costPerKgFirstPot;      // 注射 + 熟制全量
    private BigDecimal costPerKgSubsequentPot; // 注射 + 熟制×ratio
}
```

- [ ] **Step 3: 写 SaveRecipeRequest（create/update 共用）**

```java
package com.cretas.aims.dto.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class SaveRecipeRequest {
    @NotBlank
    private String productTypeId;
    @NotBlank
    private String name;
    private BigDecimal injectionRate;
    private BigDecimal cookingPotBaseKg;
    /** 默认 0.3333; service 兜底 */
    private BigDecimal subsequentPotRatio;
    @NotNull
    private List<RecipeIngredientDTO> ingredients;
}
```

- [ ] **Step 4: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/
git commit -m "feat(sp-a): recipe DTO" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/RecipeIngredientDTO.java backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/ProductRecipeDTO.java backend/java/cretas-api/src/main/java/com/cretas/aims/dto/recipe/SaveRecipeRequest.java
```

---

### Task 6: Service 接口 + 实现（CRUD + 唯一性 + per-kg 速率装配）

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/ProductRecipeService.java`
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/impl/ProductRecipeServiceImpl.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/ProductRecipeServiceTest.java`

- [ ] **Step 1: 写 Service 接口**

```java
package com.cretas.aims.service.recipe;

import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import java.util.List;

public interface ProductRecipeService {
    List<ProductRecipeDTO> list(String factoryId);
    ProductRecipeDTO get(String factoryId, String id);
    ProductRecipeDTO create(String factoryId, SaveRecipeRequest request);
    ProductRecipeDTO update(String factoryId, String id, SaveRecipeRequest request);
    void delete(String factoryId, String id);
}
```

- [ ] **Step 2: 写失败测试（唯一性 409 + 跨租户 get 404 + per-kg 装配）**

```java
package com.cretas.aims.service.recipe;

import com.cretas.aims.dto.recipe.RecipeIngredientDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.recipe.impl.ProductRecipeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductRecipeService")
class ProductRecipeServiceTest {

    private static final String F = "DEMO_FACTORY";

    @Mock ProductRecipeRepository recipeRepo;
    @Mock RecipeIngredientRepository ingredientRepo;

    ProductRecipeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductRecipeServiceImpl(recipeRepo, ingredientRepo);
    }

    private SaveRecipeRequest req() {
        SaveRecipeRequest r = new SaveRecipeRequest();
        r.setProductTypeId("DF_pt10");
        r.setName("M67卤牛肉");
        r.setSubsequentPotRatio(new BigDecimal("0.3333"));
        RecipeIngredientDTO i = new RecipeIngredientDTO();
        i.setSection("COOKING");
        i.setName("料");
        i.setDosagePerKgG(new BigDecimal("1000"));
        i.setPriceSource1(new BigDecimal("1"));
        i.setCountInSeasoning(true);
        r.setIngredients(List.of(i));
        return r;
    }

    @Test
    @DisplayName("create 同 SKU 已有 ACTIVE 配方 → 409")
    void create_duplicateActive_throws409() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.of(new ProductRecipe()));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(F, req()));
        assertEquals(409, ex.getCode());
        verify(recipeRepo, never()).save(any());
    }

    @Test
    @DisplayName("get 跨租户(不存在于本厂) → 404")
    void get_otherFactory_throws404() {
        when(recipeRepo.findByFactoryIdAndId(F, "x")).thenReturn(Optional.empty());
        BusinessException ex = assertThrows(BusinessException.class, () -> service.get(F, "x"));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("create 成功并装配 per-kg 速率")
    void create_ok_assemblesRates() {
        when(recipeRepo.findByFactoryIdAndProductTypeIdAndStatus(F, "DF_pt10", "ACTIVE"))
                .thenReturn(Optional.empty());
        when(recipeRepo.save(any(ProductRecipe.class))).thenAnswer(inv -> {
            ProductRecipe p = inv.getArgument(0);
            if (p.getId() == null) p.setId("R-1");
            return p;
        });
        var dto = service.create(F, req());
        // 熟制全量/kg = 1000/1000 × 1 = 1.0; 第一锅每kg = 注射0 + 熟制1.0
        assertEquals(0, new BigDecimal("1.0000").compareTo(dto.getCookingFullCostPerKg()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(dto.getCostPerKgFirstPot()));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=ProductRecipeServiceTest`
Expected: 编译失败 — `ProductRecipeServiceImpl` 不存在。

- [ ] **Step 4: 写 Service 实现**

```java
package com.cretas.aims.service.recipe.impl;

import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.RecipeIngredientDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.entity.recipe.ProductRecipe;
import com.cretas.aims.entity.recipe.RecipeIngredient;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.recipe.ProductRecipeRepository;
import com.cretas.aims.repository.recipe.RecipeIngredientRepository;
import com.cretas.aims.service.recipe.ProductRecipeService;
import com.cretas.aims.service.recipe.RecipeCostCalculator;
import com.cretas.aims.service.recipe.SeasoningCost;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRecipeServiceImpl implements ProductRecipeService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final BigDecimal DEFAULT_RATIO = new BigDecimal("0.3333");

    private final ProductRecipeRepository recipeRepo;
    private final RecipeIngredientRepository ingredientRepo;

    @Override
    public List<ProductRecipeDTO> list(String factoryId) {
        List<ProductRecipeDTO> out = new ArrayList<>();
        for (ProductRecipe r : recipeRepo.findByFactoryId(factoryId)) {
            out.add(toDTO(r, ingredientRepo.findByRecipeIdOrderBySeqAsc(r.getId())));
        }
        return out;
    }

    @Override
    public ProductRecipeDTO get(String factoryId, String id) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        return toDTO(r, ingredientRepo.findByRecipeIdOrderBySeqAsc(r.getId()));
    }

    @Override
    @Transactional
    public ProductRecipeDTO create(String factoryId, SaveRecipeRequest req) {
        Optional<ProductRecipe> dup = recipeRepo
                .findByFactoryIdAndProductTypeIdAndStatus(factoryId, req.getProductTypeId(), STATUS_ACTIVE);
        if (dup.isPresent()) {
            throw new BusinessException(409, "该产品已有启用配方, 请先停用旧配方再新建")
                    .withCode("RECIPE_DUPLICATE");
        }
        validate(req);
        ProductRecipe r = new ProductRecipe();
        r.setFactoryId(factoryId);
        applyHead(r, req);
        r.setStatus(STATUS_ACTIVE);
        ProductRecipe saved = recipeRepo.save(r);
        saveIngredients(factoryId, saved.getId(), req.getIngredients());
        return toDTO(saved, ingredientRepo.findByRecipeIdOrderBySeqAsc(saved.getId()));
    }

    @Override
    @Transactional
    public ProductRecipeDTO update(String factoryId, String id, SaveRecipeRequest req) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        validate(req);
        applyHead(r, req);
        recipeRepo.save(r);
        ingredientRepo.deleteByRecipeId(id);
        saveIngredients(factoryId, id, req.getIngredients());
        return toDTO(r, ingredientRepo.findByRecipeIdOrderBySeqAsc(id));
    }

    @Override
    @Transactional
    public void delete(String factoryId, String id) {
        ProductRecipe r = recipeRepo.findByFactoryIdAndId(factoryId, id)
                .orElseThrow(() -> new BusinessException(404, "配方不存在"));
        r.setStatus("INACTIVE");
        r.softDelete();
        recipeRepo.save(r);
    }

    private void validate(SaveRecipeRequest req) {
        if (req.getIngredients() == null || req.getIngredients().isEmpty()) {
            throw new BusinessException(400, "配方至少需要一条料");
        }
        BigDecimal ratio = req.getSubsequentPotRatio();
        if (ratio != null && (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(400, "第二锅起比例须在 (0,1]");
        }
        for (RecipeIngredientDTO i : req.getIngredients()) {
            if (i.getPriceSource1() == null && i.getPriceSource2() == null) {
                throw new BusinessException(400, "料「" + i.getName() + "」单价两源至少填一个");
            }
        }
    }

    private void applyHead(ProductRecipe r, SaveRecipeRequest req) {
        r.setProductTypeId(req.getProductTypeId());
        r.setName(req.getName());
        r.setInjectionRate(req.getInjectionRate());
        r.setCookingPotBaseKg(req.getCookingPotBaseKg());
        r.setSubsequentPotRatio(req.getSubsequentPotRatio() == null ? DEFAULT_RATIO : req.getSubsequentPotRatio());
    }

    private void saveIngredients(String factoryId, String recipeId, List<RecipeIngredientDTO> items) {
        int seq = 0;
        for (RecipeIngredientDTO dto : items) {
            RecipeIngredient e = new RecipeIngredient();
            e.setRecipeId(recipeId);
            e.setFactoryId(factoryId);
            e.setSection(dto.getSection());
            e.setSeq(dto.getSeq() == null ? seq++ : dto.getSeq());
            e.setName(dto.getName());
            e.setDosagePerKgG(dto.getDosagePerKgG());
            e.setPriceSource1(dto.getPriceSource1());
            e.setPriceSource2(dto.getPriceSource2());
            e.setCountInSeasoning(dto.getCountInSeasoning() == null ? Boolean.TRUE : dto.getCountInSeasoning());
            e.setRemark(dto.getRemark());
            ingredientRepo.save(e);
        }
    }

    private ProductRecipeDTO toDTO(ProductRecipe r, List<RecipeIngredient> ings) {
        ProductRecipeDTO dto = new ProductRecipeDTO();
        dto.setId(r.getId());
        dto.setFactoryId(r.getFactoryId());
        dto.setProductTypeId(r.getProductTypeId());
        dto.setName(r.getName());
        dto.setInjectionRate(r.getInjectionRate());
        dto.setCookingPotBaseKg(r.getCookingPotBaseKg());
        dto.setSubsequentPotRatio(r.getSubsequentPotRatio());
        dto.setStatus(r.getStatus());
        dto.setVersion(r.getVersion());

        List<RecipeIngredientDTO> idtos = new ArrayList<>();
        for (RecipeIngredient i : ings) {
            RecipeIngredientDTO id = new RecipeIngredientDTO();
            id.setId(i.getId());
            id.setSection(i.getSection());
            id.setSeq(i.getSeq());
            id.setName(i.getName());
            id.setDosagePerKgG(i.getDosagePerKgG());
            id.setPriceSource1(i.getPriceSource1());
            id.setPriceSource2(i.getPriceSource2());
            id.setCountInSeasoning(i.getCountInSeasoning());
            id.setRemark(i.getRemark());
            idtos.add(id);
        }
        dto.setIngredients(idtos);

        // per-kg 速率(展示): injectionRawKg/potRawKgs 传 1kg/单锅 1kg 只为算速率
        SeasoningCost rate = RecipeCostCalculator.compute(
                r, ings, BigDecimal.ONE, List.of(BigDecimal.ONE));
        dto.setInjectionCostPerKg(rate.getInjectionCostPerKg());
        dto.setCookingFullCostPerKg(rate.getCookingFullCostPerKg());
        dto.setCostPerKgFirstPot(rate.getInjectionCostPerKg().add(rate.getCookingFullCostPerKg()));
        BigDecimal ratio = r.getSubsequentPotRatio() == null ? DEFAULT_RATIO : r.getSubsequentPotRatio();
        dto.setCostPerKgSubsequentPot(
                rate.getInjectionCostPerKg().add(rate.getCookingFullCostPerKg().multiply(ratio)));
        return dto;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend/java/cretas-api && mvn -q test -Dtest=ProductRecipeServiceTest`
Expected: PASS (3 测试)。

- [ ] **Step 6: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/ProductRecipeService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/impl/ProductRecipeServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/ProductRecipeServiceTest.java
git commit -m "feat(sp-a): ProductRecipeService CRUD + 唯一性/租户/速率装配 + 测试" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/ProductRecipeService.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/recipe/impl/ProductRecipeServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/recipe/ProductRecipeServiceTest.java
```

---

### Task 7: Controller

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductRecipeController.java`

- [ ] **Step 1: 写 Controller**

```java
package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.recipe.ProductRecipeDTO;
import com.cretas.aims.dto.recipe.SaveRecipeRequest;
import com.cretas.aims.service.recipe.ProductRecipeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mobile/{factoryId}/product-recipes")
@RequiredArgsConstructor
public class ProductRecipeController {

    private final ProductRecipeService service;

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping
    public ApiResponse<List<ProductRecipeDTO>> list(@PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(service.list(factoryId));
    }

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping("/{id}")
    public ApiResponse<ProductRecipeDTO> get(@PathVariable @NotBlank String factoryId,
                                             @PathVariable @NotBlank String id) {
        return ApiResponse.success(service.get(factoryId, id));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping
    public ApiResponse<ProductRecipeDTO> create(@PathVariable @NotBlank String factoryId,
                                                @Valid @RequestBody SaveRecipeRequest request) {
        return ApiResponse.success("配方创建成功", service.create(factoryId, request));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}")
    public ApiResponse<ProductRecipeDTO> update(@PathVariable @NotBlank String factoryId,
                                                @PathVariable @NotBlank String id,
                                                @Valid @RequestBody SaveRecipeRequest request) {
        return ApiResponse.success("配方更新成功", service.update(factoryId, id, request));
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotBlank String factoryId,
                                    @PathVariable @NotBlank String id) {
        service.delete(factoryId, id);
        return ApiResponse.success("配方已停用", null);
    }
}
```

> ⚠️ 验证 import：`@RequirePermission` 的实际包路径以 `ManufacturerRegistryController` 为准（grep `import.*RequirePermission`）。若该类在 `com.cretas.aims.annotation` 之外，改 import。`ApiResponse` 包 `com.cretas.aims.dto.common`。

- [ ] **Step 2: 编译 + 全量后端测试**

Run: `cd backend/java/cretas-api && mvn -q clean test`
Expected: BUILD SUCCESS，新测试全绿，无既有测试回归。

- [ ] **Step 3: 提交**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductRecipeController.java
git commit -m "feat(sp-a): ProductRecipeController CRUD 端点" -- backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductRecipeController.java
```

---

### Task 8: web-admin API client

**Files:**
- Create: `web-admin/src/api/productRecipe.ts`

- [ ] **Step 1: 写 API client**

```typescript
import { del, get, post, put } from './request';

export interface RecipeIngredient {
  id?: string;
  section: 'INJECTION' | 'COOKING';
  seq?: number;
  name: string;
  dosagePerKgG: number | null;
  priceSource1: number | null;
  priceSource2: number | null;
  countInSeasoning: boolean;
  remark?: string | null;
}

export interface ProductRecipe {
  id: string;
  factoryId: string;
  productTypeId: string;
  name: string;
  injectionRate?: number | null;
  cookingPotBaseKg?: number | null;
  subsequentPotRatio?: number | null;
  status: string;
  version: number;
  ingredients: RecipeIngredient[];
  injectionCostPerKg?: number;
  cookingFullCostPerKg?: number;
  costPerKgFirstPot?: number;
  costPerKgSubsequentPot?: number;
}

export interface SaveRecipePayload {
  productTypeId: string;
  name: string;
  injectionRate?: number | null;
  cookingPotBaseKg?: number | null;
  subsequentPotRatio?: number | null;
  ingredients: RecipeIngredient[];
}

export function listRecipes(factoryId: string) {
  return get<ProductRecipe[]>(`/${factoryId}/product-recipes`);
}
export function getRecipe(factoryId: string, id: string) {
  return get<ProductRecipe>(`/${factoryId}/product-recipes/${id}`);
}
export function createRecipe(factoryId: string, payload: SaveRecipePayload) {
  return post<ProductRecipe>(`/${factoryId}/product-recipes`, payload);
}
export function updateRecipe(factoryId: string, id: string, payload: SaveRecipePayload) {
  return put<ProductRecipe>(`/${factoryId}/product-recipes/${id}`, payload);
}
export function deleteRecipe(factoryId: string, id: string) {
  return del<void>(`/${factoryId}/product-recipes/${id}`);
}
```

- [ ] **Step 2: 提交**

```bash
git add web-admin/src/api/productRecipe.ts
git commit -m "feat(sp-a): web-admin 配方 API client" -- web-admin/src/api/productRecipe.ts
```

---

### Task 9: web-admin 配方维护页 + 路由 + 菜单

**Files:**
- Create: `web-admin/src/views/production/ProductRecipeView.vue`
- Modify: 路由表（grep `路由文件`: `web-admin/src/router/index.ts` 或 `web-admin/src/router/routes*.ts`，找到 `path: '/system/products'` 同组位置加一条）
- Modify: `web-admin/src/components/layout/menuConfig.ts`（生产管理组内加「配方维护」一项, `module:'production'`）

- [ ] **Step 1: 写维护页 Vue（list + 双段料表格 dialog + per-kg 速率显示）**

```vue
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  listRecipes, createRecipe, updateRecipe, deleteRecipe,
  type ProductRecipe, type RecipeIngredient, type SaveRecipePayload,
} from '@/api/productRecipe';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Delete } from '@element-plus/icons-vue';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId as string);

const loading = ref(false);
const rows = ref<ProductRecipe[]>([]);
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref<SaveRecipePayload>(blankForm());

function blankForm(): SaveRecipePayload {
  return {
    productTypeId: '', name: '', injectionRate: null,
    cookingPotBaseKg: null, subsequentPotRatio: 0.3333, ingredients: [],
  };
}
function addIngredient(section: 'INJECTION' | 'COOKING') {
  form.value.ingredients.push({
    section, name: '', dosagePerKgG: null,
    priceSource1: null, priceSource2: null, countInSeasoning: true, remark: '',
  });
}
function removeIngredient(i: RecipeIngredient) {
  form.value.ingredients = form.value.ingredients.filter((x) => x !== i);
}
const injectionRows = computed(() => form.value.ingredients.filter((i) => i.section === 'INJECTION'));
const cookingRows = computed(() => form.value.ingredients.filter((i) => i.section === 'COOKING'));

async function load() {
  loading.value = true;
  try {
    const resp = await listRecipes(factoryId.value);
    rows.value = resp.data || [];
  } catch (e: any) {
    ElMessage({ message: e.message || '加载失败', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}
function openCreate() { editingId.value = null; form.value = blankForm(); dialogVisible.value = true; }
function openEdit(row: ProductRecipe) {
  editingId.value = row.id;
  form.value = {
    productTypeId: row.productTypeId, name: row.name, injectionRate: row.injectionRate ?? null,
    cookingPotBaseKg: row.cookingPotBaseKg ?? null, subsequentPotRatio: row.subsequentPotRatio ?? 0.3333,
    ingredients: row.ingredients.map((i) => ({ ...i })),
  };
  dialogVisible.value = true;
}
async function save() {
  if (!form.value.productTypeId) { ElMessage.warning('请选择产品 SKU'); return; }
  if (!form.value.name) { ElMessage.warning('请填配方名'); return; }
  if (form.value.ingredients.length === 0) { ElMessage.warning('至少加一条料'); return; }
  try {
    if (editingId.value) await updateRecipe(factoryId.value, editingId.value, form.value);
    else await createRecipe(factoryId.value, form.value);
    ElMessage.success('保存成功');
    dialogVisible.value = false;
    await load();
  } catch (e: any) {
    ElMessage({ message: e.message || '保存失败', type: 'error', duration: 0, showClose: true });
  }
}
function onDelete(row: ProductRecipe) {
  ElMessageBox.confirm(`停用配方「${row.name}」？`, '警告', { type: 'warning' }).then(async () => {
    try {
      await deleteRecipe(factoryId.value, row.id);
      ElMessage.success('已停用');
      await load();
    } catch (e: any) {
      ElMessage({ message: e.message || '停用失败', type: 'error', duration: 0, showClose: true });
    }
  }).catch(() => {});
}
onMounted(load);
</script>

<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center;">
        <span>配方维护 (注射 + 熟制, 1 SKU 1 配方)</span>
        <div>
          <el-button type="primary" :icon="Plus" @click="openCreate">新建配方</el-button>
          <el-button :icon="Refresh" @click="load" />
        </div>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="name" label="配方名" />
      <el-table-column prop="productTypeId" label="产品 SKU" />
      <el-table-column label="每kg原料(第一锅)" >
        <template #default="{ row }">¥{{ row.costPerKgFirstPot?.toFixed(2) ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="每kg原料(第二锅起)">
        <template #default="{ row }">¥{{ row.costPerKgSubsequentPot?.toFixed(2) ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="onDelete(row)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑配方' : '新建配方'" width="900px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="产品 SKU"><el-input v-model="form.productTypeId" placeholder="product_type_id" /></el-form-item>
        <el-form-item label="配方名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="注射率"><el-input-number v-model="form.injectionRate" :precision="4" :step="0.01" /></el-form-item>
        <el-form-item label="每锅基准原料kg"><el-input-number v-model="form.cookingPotBaseKg" :precision="3" /></el-form-item>
        <el-form-item label="第二锅起比例"><el-input-number v-model="form.subsequentPotRatio" :precision="4" :min="0.0001" :max="1" /></el-form-item>
      </el-form>

      <el-divider>注射配方</el-divider>
      <el-button size="small" @click="addIngredient('INJECTION')">+ 注射料</el-button>
      <el-table :data="injectionRows" size="small">
        <el-table-column label="料名"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
        <el-table-column label="每kg用量g"><template #default="{ row }"><el-input-number v-model="row.dosagePerKgG" :precision="4" /></template></el-table-column>
        <el-table-column label="单价1"><template #default="{ row }"><el-input-number v-model="row.priceSource1" :precision="4" /></template></el-table-column>
        <el-table-column label="单价2"><template #default="{ row }"><el-input-number v-model="row.priceSource2" :precision="4" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="removeIngredient(row)">删</el-button></template></el-table-column>
      </el-table>

      <el-divider>熟制配方 (老汤勾去「计入调料」)</el-divider>
      <el-button size="small" @click="addIngredient('COOKING')">+ 熟制料</el-button>
      <el-table :data="cookingRows" size="small">
        <el-table-column label="料名"><template #default="{ row }"><el-input v-model="row.name" /></template></el-table-column>
        <el-table-column label="每kg用量g"><template #default="{ row }"><el-input-number v-model="row.dosagePerKgG" :precision="4" /></template></el-table-column>
        <el-table-column label="单价1"><template #default="{ row }"><el-input-number v-model="row.priceSource1" :precision="4" /></template></el-table-column>
        <el-table-column label="单价2"><template #default="{ row }"><el-input-number v-model="row.priceSource2" :precision="4" /></template></el-table-column>
        <el-table-column label="计入调料" width="90"><template #default="{ row }"><el-switch v-model="row.countInSeasoning" /></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" @click="removeIngredient(row)">删</el-button></template></el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
```

- [ ] **Step 2: 加路由**

grep 现有路由：`cd web-admin && grep -rn "system/products" src/router/`。在同一组(component lazy import 风格一致)加：

```typescript
{
  path: '/production/product-recipes',
  name: 'ProductRecipes',
  component: () => import('@/views/production/ProductRecipeView.vue'),
  meta: { title: '配方维护', module: 'production' },
},
```

- [ ] **Step 3: 加菜单项**

在 `web-admin/src/components/layout/menuConfig.ts` 的「生产管理」组 children 内（紧挨「产品-工序配置」后）加：

```typescript
{ path: '/production/product-recipes', title: '配方维护', icon: '', module: 'production' },
```

- [ ] **Step 4: 前端构建验证**

Run: `cd web-admin && npm run build`
Expected: 构建成功无类型错误。

- [ ] **Step 5: 提交**

```bash
git add web-admin/src/views/production/ProductRecipeView.vue web-admin/src/router web-admin/src/components/layout/menuConfig.ts
git commit -m "feat(sp-a): web-admin 配方维护页 + 路由 + 菜单" -- web-admin/src/views/production/ProductRecipeView.vue web-admin/src/router/index.ts web-admin/src/components/layout/menuConfig.ts
```
> 注：commit paths 以 Step 2 grep 出的真实路由文件为准。

---

## 验收与交接

- [ ] **后端全量测试绿**：`cd backend/java/cretas-api && mvn -q clean test`
- [ ] **前端构建绿**：`cd web-admin && npm run build`
- [ ] **PR scope 干净**：`git diff origin/main...HEAD --stat`（仅 recipe 相关文件，无 sister 夹带）
- [ ] **🔒 终审回 Opus**：merge/部署回 main 由 Opus 终审（SP-A 无红线，但 Flyway 迁移号要确认未与 SP-C 撞）。
- [ ] **不碰 F006/LIUSHANMEN**；配方功能本身通用，DEMO 验证用 DEMO_FACTORY 的 DF_pt10。

## Self-Review 记录
- **Spec 覆盖**：§3.1 数据模型→Task1/2；§3.2 算法→Task4；§3.4 维护页→Task9；唯一性/校验→Task6。✅
- **类型一致**：`SeasoningCost` 字段 ↔ calculator ↔ Service toDTO ↔ DTO ↔ TS interface 全对齐。`compute(recipe, ingredients, injectionRawKg, potRawKgs)` 签名贯穿 Task4/6。✅
- **无 placeholder**：每步含完整代码/命令/预期。路由文件路径在 Task9 Step2 用 grep 兜底（仓库路由文件名可能为 routes.ts，已注明）。✅
- **YAGNI**：SP-A 不含 SP-C 的脱敏/引擎接线（那是 SP-B/C）；section 用 String 常量非枚举(省文件)。
