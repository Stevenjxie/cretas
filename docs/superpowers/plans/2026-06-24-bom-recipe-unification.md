# BOM 统管配方+锅序 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax. This is a 🔒 refactor touching **real-customer (LIUSHANMEN) data** + the verified SP-A cost calc — the migration (U3) and read-path switch (U4) are Opus-only with mandatory before/after regression (0 diff per SKU). Do NOT touch prod data until U3/U4 pass on test + Opus terminal review.

**Goal:** Fold the 配方 (`product_recipes`/`recipe_ingredients`: 注射+熟制 调料 + 锅序规则) into the BOM subsystem so one SKU = one BOM = {原辅料 + 注射 + 熟制 + 锅序}, with recipe changes flowing through BOM versioning/ECN.

**Architecture:** Decision A (真折叠): add 锅序 columns to `bom_recipes` + a new `bom_seasoning_items` table; migrate `product_recipes` data into BOM (require existing BOM per SKU, don't auto-create); re-point `RecipeCostCalculator` + SP-F `materializeBatch.computeSeasoningCost` to read from BOM (algorithm unchanged: 第一锅全量 + (n-1)×ratio, 老汤 countInSeasoning=false 不计); keep `product_recipes` read-only as rollback. SKU-without-BOM at runtime → fool-proof auto-nav (`useCreateAndReturn`/`ReturnBanner`), not silent-0/dead-end.

**Tech Stack:** Java 21 / Spring Boot 3.2 / JPA / PostgreSQL / Flyway; Vue 3 + TS + Element Plus.

**Spec:** `docs/superpowers/specs/2026-06-24-bom-recipe-unification-design.md`.

**前置铁律:**
- 🔒 **成本计算零回归**: 算法一字不改;每个 SKU 迁移前后 `computeSeasoningCost` / `RecipeCostCalculator.perKg` 逐分吻合(SP-A 实测 0.55/0.34 基线)。任一 SKU 不一致 → 停。
- 🔒 **真客户数据**: LIUSHANMEN + F006 有现网 `product_recipes` + `bom_recipes`。迁移幂等 + 可回滚 + 先 test 灰度。
- worktree off origin/main(本计划独立于 SP-F,**新开 worktree**);scope-locked commit;Flyway 出 PR 前查重号;DEMO_FACTORY 验证,改真客户数据走灰度。

---

## File Structure (先确认实体真实字段)

**⚠️ 实施前必读真实实体**(本计划字段名按 SP-A/Explore 推断,实施者必须对真实 .java 核对):
- `entity/recipe/ProductRecipe.java` + `RecipeIngredient.java`(SP-A;字段 injectionRate/cookingPotBaseKg/subsequentPotRatio + ingredient section/dosagePerKgG/priceSource1/2/countInSeasoning)
- `entity/bom/BomRecipe.java` + `BomRecipeItem.java` + `BomVersion.java`
- `service/recipe/RecipeCostCalculator.java`(锅序成本算法)
- `service/processentry/impl/ClerkProcessEntryServiceImpl.java` 的 `computeSeasoningCost`(SP-F 调它)
- `api/productRecipe.ts` / `api/bom.ts`(前端)

**新建**: `bom_seasoning_items` 表 + `BomSeasoningItem` 实体/repo;迁移 Flyway;auto-nav 前端 composable 复用。
**改**: `BomRecipe`(加锅序列)+ `RecipeCostCalculator` + `computeSeasoningCost`(读路径)+ BOM API/前端 tab。

---

## U1: Flyway schema — bom_seasoning_items + bom_recipes 锅序列 (Sonnet, 🔒 schema)

**Files:** Create `backend/java/cretas-api/src/main/resources/db/flyway/V<next>__bom_seasoning_items.sql`

- [ ] **Step 1: 预检 Flyway 重号** — `git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9_]+' | sort | uniq -d`;取下一空号。
- [ ] **Step 2: 写迁移**
```sql
ALTER TABLE bom_recipes
  ADD COLUMN cooking_pot_base_kg NUMERIC,
  ADD COLUMN subsequent_pot_ratio NUMERIC,
  ADD COLUMN injection_rate NUMERIC;
CREATE TABLE bom_seasoning_items (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  bom_recipe_id BIGINT NOT NULL,
  section VARCHAR(16) NOT NULL,          -- INJECTION | COOKING
  name VARCHAR(128) NOT NULL,
  dosage_per_kg_g NUMERIC,
  price_source_1 NUMERIC,
  price_source_2 NUMERIC,
  count_in_seasoning BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT NOW(), updated_at TIMESTAMP DEFAULT NOW(), deleted_at TIMESTAMP NULL,
  CONSTRAINT fk_bsi_recipe FOREIGN KEY (bom_recipe_id) REFERENCES bom_recipes(id)
);
CREATE INDEX idx_bsi_recipe ON bom_seasoning_items (factory_id, bom_recipe_id) WHERE deleted_at IS NULL;
```
(对 `bom_recipes` 实际列类型/约束核对;若 bom_recipes 有版本表 `bom_versions` 承载真实  items,确认锅序列加在 `bom_recipes` 还是版本表 — 见 U2 决策。)
- [ ] **Step 3: compile** `./mvnw.cmd -q -o compile` → SUCCESS。
- [ ] **Step 4: Commit** scope-locked。

## U2: BomSeasoningItem 实体/repo + BomRecipe 扩字段 (Sonnet)
**Files:** Create `entity/bom/BomSeasoningItem.java` + `repository/BomSeasoningItemRepository.java`; Modify `entity/bom/BomRecipe.java`.
- [ ] **Step 1:** 读 `BomRecipe.java` + `BomRecipeItem.java` 确认风格(BaseEntity, @Where, 关联方式)。**关键决策**: 锅序/seasoning 挂在 `bom_recipes`(SKU 级)还是 `bom_versions`(版本级)？ 决策 3(配方走版本/ECN)→ 倾向挂**版本级**(配方改动版本化)。读 BomVersion 结构后定;若版本级则 seasoning FK 指 bom_version_id 而非 bom_recipe_id（同步改 U1 SQL）。
- [ ] **Step 2:** 写 `BomSeasoningItem` 实体(extends BaseEntity, @Where deleted_at IS NULL, 字段镜像表);`BomRecipe` 加 3 个锅序字段 + `@OneToMany seasoningItems`(或版本实体上)。
- [ ] **Step 3:** repo: `findByFactoryIdAndBomRecipeId`(或 ByVersionId)。
- [ ] **Step 4:** compile + commit。

## U3: 🔒🔒 迁移脚本 product_recipes → BOM (Opus, 真客户数据)
**Files:** Create migration runner (Java `@Component` one-shot OR a Flyway Java migration OR a guarded admin endpoint — pick per how SP-A/server-operations does data backfills; read `scripts/migrations/` + server-operations.md 的 smartbi-migration-runner 范式).
- [ ] **Step 1: 写迁移逻辑**(幂等):对每个未迁移的 `product_recipes` 行(factory_id, product_type_id):
  - 找对应 `bom_recipes`(同 factory+productTypeId)。**找不到 → 不建,记 WARN「SKU X 有配方无 BOM,需先建 BOM」到迁移报告**(决策 2:要求现有 BOM)。
  - 找到 → 写锅序列(cooking_pot_base_kg/subsequent_pot_ratio/injection_rate)到 BOM(或其 active 版本);`recipe_ingredients` 逐行 → `bom_seasoning_items`(section/name/dosage/price/countInSeasoning)。
  - 幂等: 已迁移(BOM 已有 seasoning items)跳过;用迁移台账或标记防重复。
- [ ] **Step 2: TDD(真 PG/H2)** — 种 product_recipe(13 料 M67 风格)+ 对应 bom_recipe → 跑迁移 → 断言 bom_seasoning_items 13 行、锅序列对、countInSeasoning 老汤=false 保留;无 BOM 的 SKU → 不建 + WARN。再跑一次(幂等)→ 不重复。
- [ ] **Step 3: 真 PG dry-run**(test 环境对 LIUSHANMEN/F006 真实 product_recipes 跑,**只读对比**,不 commit):列出每个 SKU 迁移后 seasoning items + 锅序,人工/脚本核对完整。
- [ ] **Step 4: Commit**。**不在 prod 跑**(U8 灰度才跑)。

## U4: 🔒 读路径切 BOM + 零回归 (Opus)
**Files:** Modify `service/recipe/RecipeCostCalculator.java` + `service/processentry/impl/ClerkProcessEntryServiceImpl.java`(computeSeasoningCost)。
- [ ] **Step 1: 回归基线** — 写测试:对一组 SKU(含 M67 13料),用**现有** product_recipes 路径算 `RecipeCostCalculator.perKg` + `computeSeasoningCost`,记下基线数(第一锅/第二锅 per-kg,如 0.55/0.34)。
- [ ] **Step 2:** 改 RecipeCostCalculator + computeSeasoningCost 从 BOM 模型读(bom_recipes 锅序列 + bom_seasoning_items),**算法逐行不变**(第一锅全量 + (n-1)×ratio,老汤 countInSeasoning=false 不计)。
- [ ] **Step 3: 零回归断言** — 同一组 SKU 迁移后走 BOM 路径,`perKg`/`computeSeasoningCost` 输出**逐分等于基线**(±0)。任一不等 → 停、查。
- [ ] **Step 4:** re-run SP-F 全套(ProcessSheet*/ClerkProcessEntry*/Recipe*)+ SP-A RecipeCostCalculatorTest/ProductRecipeServiceTest → 全绿。
- [ ] **Step 5: Commit**。

## U5: API 并入 BOM (Sonnet)
**Files:** Modify BOM controller/service + DTO;deprecate/redirect `/product-recipes` 写端点(读保留兼容)。
- [ ] BOM 创建/编辑 payload 带 seasoning(注射/熟制 items)+ 锅序参数;配方改动走 BOM 版本/ECN 流(决策 3)。读 BOM 时含 seasoning。TDD + commit。

## U6: 前端「调料配方」tab 改编辑 BOM 内嵌配方 (Sonnet/Composer)
**Files:** Modify `views/production/ProductRecipeView.vue`(或新组件)+ `api/bom.ts`。
- [ ] tab 编辑的是 BOM-hosted 配方(经 BOM 端点),非独立 ProductRecipe;保留锅序/注射/熟制 三段录入 + countInSeasoning。build + commit。

## U7: 自动跳转防呆 — SKU 无 BOM/配方 (Sonnet)
**Files:** Modify SP-F ProcessSheet(熟制 seasoning 算成本处)+ BOM 选 SKU 处;复用 `useCreateAndReturn`/`ReturnBanner`。
- [ ] 运行时 SKU 无 BOM/配方 → 不静默 0、不 dead-end:提示「该 SKU 未设置 BOM/配方」+ 一键跳设置页 + 设完快捷返回原处(见 [[feedback_returnbanner_double_decode_and_strict_audit]] / fool-proof Rule5)。后端 computeSeasoningCost 无配方时返明确 warning(非静默 0)。TDD + commit。

## U8: 灰度 + 终审 + 部署 (Opus 出货闸 🔒)
- [ ] test 环境跑 U3 迁移 → U4 读路径 → 对 test 数据全 SKU 前后对比 0 差异。
- [ ] Opus 终审全 diff(迁移/读路径/版本交互/跨租户)。
- [ ] **prod 灰度**: 备份 → 跑迁移 → **逐 SKU 前后 computeSeasoningCost 对比 0 差异** → 切读路径 → 监控。任一异常回滚(product_recipes 只读还在)。
- [ ] 稳定一个回归周期后,单独 cleanup 删 product_recipes(决策 4)。

---

## Self-Review (plan vs spec)
- ✅ §4 决策1 A真折叠 → U1/U2(schema+实体)+ U3(迁移)+ U4(读路径)。
- ✅ §4 决策1b/§7.2 缺BOM自动跳转防呆 → U7;迁移不自动建BOM → U3 Step1(WARN 不建)。
- ✅ §7.3 配方走版本/ECN → U2 决策(挂版本级)+ U5(走 BOM 版本流)。
- ✅ §7.4 product_recipes 留只读 → U3/U5 不删,U8 末尾 cleanup。
- ✅ §4决策4 零回归 → U4 Step1/3 基线+断言;§5 迁移真客户 → U3 dry-run + U8 灰度。
- ✅ §6 红线(真客户数据/成本无回归/SP-F依赖/版本/Flyway)→ U3/U4 Opus + U7(SP-F依赖)+ U1 查重号。
- 类型一致: `bom_seasoning_items`/`BomSeasoningItem`/锅序列名 跨 U1/U2/U3/U4 一致(实施者按真实 BomRecipe 字段最终定,U2 决策锁版本级 vs SKU级后全程统一)。
- ⚠️ 最大不确定: 锅序/seasoning 挂 bom_recipes 还是 bom_versions(U2 决策)—— 实施者读 BomVersion 后定,定了回填 U1 SQL FK。
