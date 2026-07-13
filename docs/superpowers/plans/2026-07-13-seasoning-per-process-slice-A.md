# 调料配方按工序 — Slice A(后端模型+成本+报工识别)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`).
> **⚠️ 🔒🔒 红线:本片改成本核算口径 + Flyway 迁移。执行者做到实现+自测+PR 停;Opus 终审 + 从 main 部署。成本 keystone(A3/A4)建议 Opus 自写 + 对抗审计。**

**Goal:** 让调料成本**按工序**核算——每道工序读它自己的调料明细 + 锅序/注射参数,注射按绝对量;报工识别调味步改读工序类别。数据链跑通(不含 UI,UI 是 Slice B/C)。

**Architecture:** 复用 `RecipeCostCalculator`(熟制锅序数学不动,注射分支改绝对量);`BomSeasoningItem` 加 `work_process_id`;新增 per-(recipe×工序) 参数表 `bom_process_seasoning`;`computeSeasoningCost` 按 step 的 `workProcessId` 过滤 + 读该工序参数;调味步识别读 `WorkProcess.processCategory`(熟制/注射),名字正则降兜底。

**Tech Stack:** Java 21 / Spring Boot 3 / JPA(Hibernate 6)/ PostgreSQL / Flyway(`db/flyway/`)。测试 JUnit(现有 `RecipeCostCalculatorTest`、clerk entry 测试)。

**隔离:** worktree `cretas-season` off `origin/main`(已建)。每 Task commit;`git commit -- <files>` 锁 scope。

**关键现状锚点(已勘查):**
- `RecipeCostCalculator.compute(subsequentPotRatio, ingredients, injectionRawKg, potRawKgs)` — `service/recipe/RecipeCostCalculator.java`。注射 = `injectionRawKg × injPerKg`(L60);熟制 = `Σ potRawKgs[i] × cookPerKg × (i==0?1:ratio)`(L66-74)。
- `computeSeasoningCost(factoryId, productTypeId, st, warnings)` — `service/processentry/impl/ClerkProcessEntryServiceImpl.java:939-993`。现读**整个 SKU** 的 `bomSeasoningItemRepo.findByRecipeIdOrderBySeqAsc`(L958-959),不按工序。
- `isSeasoningStep(st)` L914-929:读 `st.getProcessCategory()=="SEASONING"` + `potCount` + 名字正则。**注意**:`StepEntry.processCategory`(报工携带)≠ `WorkProcess.processCategory`(主数据,前处理/加工/熟制)。
- `BomSeasoningItem` — `entity/bom/BomSeasoningItem.java`,`recipe_id` FK + `section`(INJECTION/COOKING),**无 workProcessId**。
- `WorkProcess.processCategory` — `entity/WorkProcess.java:37`(String)。前端下拉 `CATEGORIES` — `web-admin/src/views/system/work-processes/index.vue:38` = `前处理/加工/包装/灭菌/质检/存储/配送/其他`。
- Flyway 最新 = `V20261028_59`。**本片用 `V20261028_60`**(实施时再核对最新号防撞)。

---

## 文件结构

| 文件 | 责任 | 改动 |
|---|---|---|
| `web-admin/src/views/system/work-processes/index.vue` | 工序类别下拉 | 加「熟制」「注射」到 `CATEGORIES` |
| `.../db/flyway/V20261028_60__seasoning_per_process.sql` | schema | Create(列+表) |
| `entity/bom/BomSeasoningItem.java` | 调料明细行 | 加 `workProcessId` |
| `entity/bom/BomProcessSeasoning.java` | per-工序 锅序/注射参数 | Create |
| `repository/bom/BomProcessSeasoningRepository.java` | 取参数 | Create |
| `repository/bom/BomSeasoningItemRepository.java` | 按工序过滤 | 加 findBy...WorkProcessId |
| `service/recipe/RecipeCostCalculator.java` | 成本纯函数 | 注射分支改绝对量(新重载) |
| `service/processentry/impl/ClerkProcessEntryServiceImpl.java` | 报工成本 + 识别 | computeSeasoningCost 按工序 + isSeasoningStep 读工序类别 + 透传 workProcessId |
| `dto/.../StepEntry`(定位实施时确认) | 报工步 | 确保携带 workProcessId + 工序类别 |

---

## Task A0：工序类别加「熟制」「注射」

**Files:** Modify `web-admin/src/views/system/work-processes/index.vue:38`

- [ ] **Step 1:** `CATEGORIES` 加两值
```js
const CATEGORIES = [
  '前处理', '加工', '熟制', '注射', '包装', '灭菌', '质检', '存储', '配送', '其他'
];
```
- [ ] **Step 2:** 后端识别常量:在 ClerkProcessEntryServiceImpl(或一个 `SeasoningProcessCategory` 常量类)定义 `COOKING_CATEGORY="熟制"`、`INJECTION_CATEGORY="注射"`。供 A4/A5 引用,避免散字符串。
- [ ] **Step 3:** Commit `feat(seasoning): 工序类别加熟制/注射`（-- 上述两文件）

---

## Task A1：Flyway 迁移(列 + 参数表)

**Files:** Create `.../db/flyway/V20261028_60__seasoning_per_process.sql`(实施时 `ls db/flyway | tail` 核对最新号)

- [ ] **Step 1:** 写迁移
```sql
-- BomSeasoningItem 加工序归属(nullable, 迁移期兼容)
ALTER TABLE bom_seasoning_items ADD COLUMN IF NOT EXISTS work_process_id VARCHAR(50);
CREATE INDEX IF NOT EXISTS idx_bsi_recipe_wp ON bom_seasoning_items(recipe_id, work_process_id);

-- per-(recipe × 工序) 锅序/注射参数
CREATE TABLE IF NOT EXISTS bom_process_seasoning (
  id              VARCHAR(50) PRIMARY KEY,
  factory_id      VARCHAR(50) NOT NULL,
  recipe_id       VARCHAR(191) NOT NULL,
  work_process_id VARCHAR(50) NOT NULL,
  subsequent_pot_ratio NUMERIC(8,4),      -- 熟制第二锅起比例
  injection_amount_kg  NUMERIC(12,3),     -- 注射绝对量 kg
  notes           VARCHAR(500),
  created_at      TIMESTAMP DEFAULT NOW(),
  updated_at      TIMESTAMP DEFAULT NOW(),
  deleted_at      TIMESTAMP NULL,
  CONSTRAINT uq_bps_recipe_wp UNIQUE (recipe_id, work_process_id)
);
CREATE INDEX IF NOT EXISTS idx_bps_factory_recipe ON bom_process_seasoning(factory_id, recipe_id);
```
- [ ] **Step 2:** 本地/测试库 Flyway 跑通(启动后端或 `mvn flyway:migrate` 视配置)。验证表 + 列存在。
- [ ] **Step 3:** Commit `feat(seasoning): Flyway 工序调料参数表 + 明细加工序列`

---

## Task A2：Entity + Repository

**Files:** Modify `entity/bom/BomSeasoningItem.java`;Create `entity/bom/BomProcessSeasoning.java`、`repository/bom/BomProcessSeasoningRepository.java`;Modify `repository/bom/BomSeasoningItemRepository.java`

- [ ] **Step 1:** `BomSeasoningItem` 加字段
```java
@Column(name = "work_process_id", length = 50)
private String workProcessId;
```
- [ ] **Step 2:** 建 `BomProcessSeasoning`(继承 BaseEntity;字段 factoryId/recipeId/workProcessId/subsequentPotRatio(BigDecimal)/injectionAmountKg(BigDecimal)/notes;`@PrePersist` UUID id)。参照 `BomSeasoningItem` 风格。
- [ ] **Step 3:** `BomProcessSeasoningRepository`:`List<BomProcessSeasoning> findByRecipeIdAndDeletedAtIsNull(String recipeId)`、`Optional<BomProcessSeasoning> findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(String recipeId, String workProcessId)`。
- [ ] **Step 4:** `BomSeasoningItemRepository` 加 `List<BomSeasoningItem> findByRecipeIdAndWorkProcessIdOrderBySeqAsc(String recipeId, String workProcessId)`（保留原 findByRecipeId... 兼容）。
- [ ] **Step 5:** 编译 `mvn -q -pl backend/java/cretas-api compile`(或 IDE)。
- [ ] **Step 6:** Commit `feat(seasoning): BomSeasoningItem 工序字段 + BomProcessSeasoning 实体/仓库`

---

## Task A3：RecipeCostCalculator 注射改绝对量(🔒 Opus keystone + TDD)

**决策点(实施前 30 秒 Steve 确认口径):** 注射成本 = **绝对注射量(injection_amount_kg)× 注射内容 injPerKg**(内容明细仍按 dosage_g/kg-of-injection),即把现有 `injectionRawKg` 位置换成配置的绝对注射量。熟制分支完全不动。

**Files:** Modify `service/recipe/RecipeCostCalculator.java`;Test `.../RecipeCostCalculatorTest.java`(现有)

- [ ] **Step 1（失败测试）:** 加测试:给注射段两行 dosage/price,`injectionAmountKg=5` → 期望 `injectionTotal = 5 × injPerKg`,与旧 `injectionRawKg` 语义在数值上等价但**来源是绝对量**。同时保留一条熟制锅序测试断言不回归。
```java
// 伪:compute(ratio, injectionOnlyLines, /*injectionBasisKg*/ new BigDecimal("5"), potRawKgs=null)
// assertEquals(expectedInjTotal, sc.getInjectionTotal());
```
- [ ] **Step 2:** 跑测试确认失败(若沿用同签名则可能已过——此时改为新增语义测试:验证注射 basis 由调用方传绝对量,`RecipeCostCalculator` 不再假设它是"每 kg 原料")。
- [ ] **Step 3（实现）:** `RecipeCostCalculator` 注射分支保留 `basisKg × injPerKg` 形式,但**文档/参数名**从 `injectionRawKg` 改为 `injectionBasisKg`(语义:调用方决定传"绝对注射量")。若熟制/注射需要不同 basis,拆参数不混用。熟制 `potRawKgs` 逻辑 L66-75 一字不改。
- [ ] **Step 4:** 跑 `RecipeCostCalculatorTest` 全绿。
- [ ] **Step 5:** Commit `feat(seasoning): 注射成本改绝对量口径(calculator)`

---

## Task A4：computeSeasoningCost 按工序(🔒 Opus keystone)

**Files:** Modify `service/processentry/impl/ClerkProcessEntryServiceImpl.java:939-993`;确认 `StepEntry` 携带 `workProcessId`(定位其 DTO;若无则加并在报工路径填充)。

- [ ] **Step 1:** 确认/补 `StepEntry.workProcessId`。grep StepEntry 定义;报工 materialize/record 路径把该步的 `workProcessId` 填进去(来自 compiled task / 工序链)。若报工目前只有 processName/Order,按 (productTypeId, processName/Order) 解析出 workProcessId(读工序链)。
- [ ] **Step 2:** `computeSeasoningCost` 改为按工序:
  - 读 is_current BOM(不变)。
  - `bomSeasoningItemRepo.findByRecipeIdAndWorkProcessIdOrderBySeqAsc(recipeId, st.workProcessId)` — **只取该工序的调料明细**。
  - `bomProcessSeasoningRepo.findByRecipeIdAndWorkProcessIdAndDeletedAtIsNull(recipeId, st.workProcessId)` — 取该工序的 `subsequentPotRatio` + `injectionAmountKg`。
  - 注射 basis:该工序若是注射型 → 传 `injectionAmountKg`(绝对);熟制型 → 传 `potRawKgs`(报工锅数)+ 该工序 ratio。
  - `RecipeCostCalculator.compute(ratio, thisProcessLines, injectionBasisKg, potRawKgs)`。
  - **回退**:该工序无 bom_process_seasoning / 无明细 → 现有 warning(改成指向"该工序")+ 返 0;保留 product_recipes 兼容段(整 SKU legacy,不按工序,仅未迁移 SKU 用)。
- [ ] **Step 3:** null 容忍:workProcessId 为空(老报工路径未透传)→ 回退到旧"整 SKU"读法(保零回归),记 warning。
- [ ] **Step 4:** 单测:mock BOM + 两工序(熟制/注射)不同明细 → 报熟制步只算熟制成本、报注射步只算注射成本(绝对量)。
- [ ] **Step 5:** Commit `feat(seasoning): computeSeasoningCost 按工序读调料+参数`

---

## Task A5：调味步识别读工序类别

**Files:** Modify `ClerkProcessEntryServiceImpl.isSeasoningStep`(L914-929)+ 相关锅数识别点(ProcessSheetServiceImpl 同正则处)

- [ ] **Step 1:** `isSeasoningStep` 增加:若能拿到该步 `WorkProcess.processCategory`(经 workProcessId 解析)∈ {熟制, 注射} → true;熟制 → 需锅数,注射 → 需注射量。保留 `"SEASONING".equals(st.getProcessCategory())` 与名字正则为**兜底**。
- [ ] **Step 2:** 把 `熟制`/`注射` 常量(A0 Step2)用起来,避免裸字符串。
- [ ] **Step 3:** 单测:一个 processName 不含"熟/卤"但 WorkProcess.processCategory='熟制' 的步 → 正确识别为调味步(旧正则会漏)。
- [ ] **Step 4:** Commit `feat(seasoning): 调味步识别读工序类别(熟制/注射)`

---

## Task A6：存量迁移(best-effort,F006 空)

**Files:** 迁移可并入 A1 的 V20261028_60 或单独 `V20261028_61`(数据回填)

- [ ] **Step 1:** 回填:对每个有 `bom_seasoning_items` 的 recipe,若该 SKU 有唯一熟制工序 → 把 header `subsequent_pot_ratio` 写入该工序的 `bom_process_seasoning`,并把 COOKING 段明细的 `work_process_id` 指向它;INJECTION 段类推(唯一注射工序)。多/无 → 留空 + 记(注释说明需人工)。
- [ ] **Step 2:** F006 实测:`bom_seasoning_items` 该厂应为空 → 迁移近似 no-op,验证不报错。
- [ ] **Step 3:** `injection_rate`(旧率)不搬成绝对量(口径不同),注释标注。
- [ ] **Step 4:** Commit `feat(seasoning): 存量调料按工序回填(best-effort)`

---

## Task A7：验证(数据链跑通)

- [ ] **Step 1:** 全量单测:`mvn -q -pl backend/java/cretas-api test -Dtest=RecipeCostCalculatorTest,*ClerkProcessEntry*` 全绿。
- [ ] **Step 2:** 造数据端到端(测试环境或本地):建一个 SKU,workflow 含熟制工序 + 注射工序(各设 processCategory);配 `bom_process_seasoning`(熟制 ratio、注射 amount)+ 各工序 `bom_seasoning_items`;报熟制步(记 2 锅)+ 注射步 → 校验各工序调料成本分别按各自参数算对、互不混。
- [ ] **Step 3:** 回归:一个未配 per-工序 的老 SKU 报工 → 走回退,成本行为不变(零回归)。
- [ ] **Step 4:** PR scope:`git diff origin/main...HEAD --stat` 只含本片文件 + spec/plan。

---

## Self-Review(对照 spec Slice A)
- 工序类别加熟制/注射 → A0 ✓
- BomSeasoningItem.work_process_id + bom_process_seasoning → A1/A2 ✓
- computeSeasoningCost 按工序(注射绝对量)→ A4 + A3 ✓
- isSeasoningStep 读工序类别 → A5 ✓
- workProcessId 透传报工路径 → A4 Step1 ✓
- 迁移 best-effort → A6 ✓
- Placeholder:A3/A4 标注实施前需读 StepEntry DTO + 确认注射口径(30秒 Steve 确认)——非占位符,是 🔒 谨慎点。
- 一致性:`bom_process_seasoning` / `injectionAmountKg` / `workProcessId` 命名全片一致。

## Execution
🔒🔒 成本红线:A3/A4 keystone 建议 Opus 自写 + 对抗审计;A0/A1/A2/A6 机械部分可派 Sonnet in-harness。执行者做到 PR 停,Opus 终审 + 从 main 部署。
