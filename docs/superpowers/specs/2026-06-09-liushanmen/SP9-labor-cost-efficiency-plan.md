# SP9 · 人工双口径对比 + 人效模块 — 实施计划

> **波次**: 波3 (SP1 + SP3 merge 进 main 后开工)
> **Flyway 号段**: V20260911_10 ~ V20260911_19 (SP9 独占)
> **生成**: 2026-06-09 Sonnet in-harness
> **Fleet**: Java backend → Sonnet in-harness; Vue UI → Composer 2.5; 红线/终审 → Opus

---

## 阶段划分

| 阶段 | 任务 | scope-lock | 依赖 | 模型 | 优先级 |
|------|------|-----------|------|------|------|
| **P0-A** | M1: ProductType 加 quotedLaborCostPerKg (全4处) + Flyway V20260911_10 | ProductType.java + ProductTypeServiceImpl + 迁移 | SP4 merge (若 SP4 同改 ProductType, 必须 SP4 先) | Sonnet | P0 |
| **P0-B** | M2: YieldReportServiceImpl 加 actualLaborCost 自动 rollup | YieldReportServiceImpl.java + ProductionPlanRepository | **SP1 必须先 merge** | Sonnet | P0 (串行于SP1) |
| **P1-A** | M3: 双口径对比 API + DTO | LaborEfficiencyController + LaborEfficiencyServiceImpl + 两 DTO | P0-A 完成后 | Sonnet | P1 |
| **P1-B** | M4: 工序达成率 API 扩展 + EfficiencyAnalysis.vue 增强 | ProductionReportServiceImpl.getEfficiencyAnalysisReport + EfficiencyAnalysis.vue | P0-A 完成后 | Sonnet (BE) + Composer (Vue) | P1 |
| **P2** | M5: 人效模块 web 骨架(待维护列表) | views/production/labor-efficiency/index.vue | P1-A 完成后 | Composer | P2 (周五演示后) |

---

## Task 卡 (即贴即用)

---

### Task 1 — P0-A: ProductType 加研发预估人工字段 (Sonnet in-harness)

**目标**: 给 `ProductType` 加 `quotedLaborCostPerKg` 字段, 全4处 (entity + create + update + convertToDTO), Flyway V20260911_10。

**worktree**: 波3开工前执行
```bash
git worktree add -b sp9/m1-product-type-quoted-labor ../cretas-sp9-m1 origin/main
```

**允许改**:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductType.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductTypeServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/dto/product/ProductTypeDTO.java` (或等价 DTO)
- `backend/java/cretas-api/src/main/resources/db/flyway/V20260911_10__product_type_quoted_labor_cost.sql`
- 对应 test 文件

**禁改**: BOM 相关文件 / YieldReportServiceImpl / EfficiencyAnalysis.vue

**TDD 先写**:
```java
// ProductTypeServiceImplTest:
@Test void test_create_product_type_persists_quoted_labor_cost()
@Test void test_update_product_type_preserves_quoted_labor_cost_when_null_sent()
@Test void test_convert_to_dto_maps_quoted_labor_cost()
```

**Flyway 文件内容**:
```sql
-- V20260911_10__product_type_quoted_labor_cost.sql
ALTER TABLE product_types
  ADD COLUMN IF NOT EXISTS quoted_labor_cost_per_kg NUMERIC(12, 4) NULL;
COMMENT ON COLUMN product_types.quoted_labor_cost_per_kg IS '研发预估人工成本(元/kg成品), @PriceSensitive';
```

**规则摘要 (必须遵守)**:
- `@PriceSensitive` 注解: 销售角色看到 null, 不返回值
- null ≠ 0: 未填时 convertToDTO 传 null, 前端显示"-"
- 全4处 DTO 往返: entity 字段 → create set → update null-guard set (不覆盖 null) → convertToDTO map
- NUMERIC(12,4) scale-4 HALF_UP (对齐项目 CostRollupUtil 精度约定)
- `gramsPerUnit` 镜像: 和 `gramsPerUnit` 同样模式的 nullable 字段

**对齐确认 (merge前)**:
```bash
# Flyway 撞号检查 (SP9 独占 V20260911_10~19, 但 V20260911_01 已被系统占用 — 从 _10 开始)
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway/ | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 必须无输出 (无重复)
mvn test -pl backend/java/cretas-api -Dtest="ProductTypeServiceImplTest"
```

**交接**: PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 干净 → PR 给 Opus 终审

**⛔ 红线**: 不自部署 prod; PR 到 main 由 Opus 终审 + 部署

---

### Task 2 — P0-B: 自动 rollup actualLaborCost (Sonnet in-harness, SP1 merge 后才开)

**目标**: 在生产完工路径中, 将 `BatchYield.totalLaborCost` 自动回填 `ProductionPlan.actualLaborCost`, 替代现有手动 `updateActualCosts` endpoint。

**前置条件**: SP1 已 merge 进 origin/main (SP1 完成 YieldReportServiceImpl 整批 totalLaborCost 计算)

**worktree**:
```bash
git worktree add -b sp9/m2-rollup ../cretas-sp9-m2 origin/main
```

**允许改**:
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/yield/impl/YieldReportServiceImpl.java` (只增, 不改 SP1 已改的逻辑)
- 对应 test 文件

**禁改**: 其他文件 (特别是 SP1/SP2 修改的报工计算逻辑)

**实现要点**:
```java
// completeProduction() 路径末尾添加 (在 FG 建立成功之后, 事务内):
if (batchYield.getTotalLaborCost() != null) {
    productionPlanRepository.updateActualLaborCost(
        batch.getProductionPlanId(), batchYield.getTotalLaborCost()
    );
}
// null 不写入 (诚实空原则, 避免覆盖已有手动设置值)
```

**注意**: 使用 `REQUIRES_NEW` 还是同一事务取决于 SP1 完成后的架构 — 若 completeProduction 本已是独立事务 → 直接在内; 若有 fail-soft 父事务风险 → 参考 `feedback_failsoft_catch_cannot_save_doomed_tx` 规则用 REQUIRES_NEW 隔离。

**TDD**:
```java
@Test void test_rollup_sets_actual_labor_cost_on_plan_when_batch_completes()
@Test void test_rollup_skips_when_total_labor_cost_is_null()
@Test void test_rollup_does_not_overwrite_when_labor_cost_null()
```

**验收**: 手动完工一个有报工的批次 → GET /production-plans/{id} → `actualLaborCost` 自动有值

**交接**: PR off origin/main, Opus 终审

---

### Task 3 — P1-A: 双口径对比 API (Sonnet in-harness, P0-A 完后)

**目标**: 新建 `LaborEfficiencyController` + `LaborEfficiencyServiceImpl` + 两个 DTO, 实现双口径对比端点。

**worktree**:
```bash
git worktree add -b sp9/m3-compare-api ../cretas-sp9-m3 origin/main
```

**允许改**:
- `src/.../controller/LaborEfficiencyController.java`             (🆕)
- `src/.../service/labor/LaborEfficiencyService.java`             (🆕 接口)
- `src/.../service/labor/impl/LaborEfficiencyServiceImpl.java`   (🆕 实现)
- `src/.../dto/labor/LaborEfficiencyCompareDTO.java`              (🆕)
- `src/.../dto/labor/LaborVarianceItemDTO.java`                   (🆕)
- 对应 test 文件

**禁改**: 现有 Controller/Service 文件; ProductionPlan/ProductType entity; YieldReportServiceImpl

**端点设计**:
```
GET /{factoryId}/labor-efficiency/compare
  params: startDate(ISO), endDate(ISO), productTypeId(optional)
  returns: List<LaborEfficiencyCompareDTO>

GET /{factoryId}/labor-efficiency/step-breakdown/{batchId}
  returns: LaborEfficiencyCompareDTO (含 stepDetails)
```

**核心算法**:
```java
// 1. quotedPerBox 计算:
//    quotedPerBox = quotedLaborCostPerKg × gramsPerUnit / 1000
//    quotedLaborCostPerKg null → quotedPerBox = null

// 2. actualPerBox 计算:
//    boxCount = goodQuantityKg × 1000 / gramsPerUnit
//    boxCount = 0 → actualPerBox = null (诚实空)
//    actualPerBox = totalLaborCost / boxCount

// 3. varianceRate:
//    quoted null → varianceRate = null
//    varianceRate = (actual - quoted) / quoted × 100
//    警告阈值: ±10% = WARNING, ±20% = CRITICAL (对应 spec §7 fool-proof)

// 4. achievementRate per step:
//    standardTimeMinutes null → achievementRate = null
//    achievementRate = actualMinutes / (standardTimeMinutes × outputQty) × 100
//    alertLevel: <75 → BELOW; >150 → ABOVE

// 5. HALF_UP scale-4 throughout (per CostRollupUtil convention)
```

**TDD (先写)**:
```java
LaborEfficiencyServiceImplTest:
  test_compare_returns_null_quoted_when_product_type_not_set()
  test_compare_calculates_actual_per_box_correctly()
  test_variance_rate_null_when_quoted_null()
  test_zero_quantity_returns_null_cost_per_box()
  test_variance_status_normal_warning_critical()
  test_step_breakdown_sums_correctly()
  test_achievement_rate_null_when_standard_minutes_not_set()
  test_price_sensitive_strips_costs_for_sales_role()   // @PriceSensitive
```

**验收**:
```bash
mvn test -Dtest="LaborEfficiencyServiceImplTest"
curl "http://localhost:10010/api/mobile/{fid}/labor-efficiency/compare?startDate=2026-06-01&endDate=2026-06-09" \
  -H "Authorization: Bearer $TOKEN"
# 期望: 有 quotedPerBox / actualPerBox / varianceRate 字段, null 为 null 非 0
```

**交接**: PR off origin/main, Opus 终审

---

### Task 4 — P1-B: 工序达成率 API + EfficiencyAnalysis.vue 扩展

此任务分两个并行 worktree:

#### Task 4a — 后端达成率 API (Sonnet in-harness)

**worktree**:
```bash
git worktree add -b sp9/m4-achievement-be ../cretas-sp9-m4-be origin/main
```

**允许改**:
- `service/report/impl/ProductionReportServiceImpl.java` — 扩展 `getEfficiencyAnalysisReport()` (当前是空壳, 只加 achievementRate 字段, 不动 estimatedLaborCost/actualLaborCost 路径)
- `dto/report/EfficiencyAnalysisDTO.java` (若存在; 否则新建)
- 对应 test

**扩展 getEfficiencyAnalysisReport() 返回值加**:
```java
// 新增字段:
BigDecimal avgAchievementRate;        // 全部工序加权平均达成率
List<ProcessAchievementDTO> processes; // 逐工序达成率明细
// ProcessAchievementDTO: processName, standardMinutes, actualMinutes, achievementRate, alertLevel
```

#### Task 4b — Vue 前端扩展 (Composer 2.5)

**worktree** (Composer 侧, 独立):
```bash
git worktree add -b sp9/m4-achievement-fe ../cretas-sp9-m4-fe origin/main
```

**允许改**:
- `web-admin/src/views/production-analytics/EfficiencyAnalysis.vue`

**禁改**: 其他 vue 文件; 任何 java 文件

**UI 要求**:
- 在"预算 vs 实际"tab 旁增加"人工双口径"tab
- 显示表格: 产品名 | 报价/盒 | 实际/盒 | 差异率 | 状态(颜色标注)
- 差异 >10% 橙色; >20% 红色
- null 字段显示"-"(不显示 0)
- 差异超阈值时 sticky toast: duration:0, showClose: true (fool-proof 4位一体)

**Composer brief 关键规则 (必须自包含)**:
- 使用 Element Plus; 不使用 `as any`; 统一响应格式 `{ success, data, message }`
- null 不等于 0 — API 返回 null 时前端显示"-"
- sticky toast: `ElMessage({ message, type:'error', duration: 0, showClose: true })`
- `vue-tsc --noEmit && npm run build` 必须通过

**验收**: `npx vue-tsc --noEmit && npm run build`; headed 截图显示人工双口径 tab

---

### Task 5 — P2: 人效模块 web 骨架 (Composer 2.5, 周五演示后)

> P2 — 客户明确"人效模块本次不管", 周五不演示此功能, 按需开工

**worktree**:
```bash
git worktree add -b sp9/m5-efficiency-module ../cretas-sp9-m5 origin/main
```

**允许改**:
- `web-admin/src/views/production/labor-efficiency/index.vue`  (🆕)
- `web-admin/src/router/index.ts` (加路由)

**骨架功能**:
- tab1: 已完工批次双口径对比表 (复用 Task 3 API)
- tab2: 待维护列表 (已完工但 actualLaborCost=null 的批次)
- tab3: 工序达成率看板 (Task 4 API)

---

## Flyway 号段分配

| 迁移文件 | 内容 | 阶段 |
|---|---|---|
| V20260911_10 | `product_types.quoted_labor_cost_per_kg` 加列 | P0-A Task1 |
| V20260911_11 | (预留) 若需要新增配置表 | — |
| V20260911_12~19 | (预留) | — |

> 注: V20260911_01 已被 `intent_records_shadow_columns` 占用; SP9 从 _10 起步以留出 _01~_09 给 SP5-SP8 (若有)。

---

## 串行/并行依赖图

```
SP4 merge (若改 ProductType)
    ↓
Task 1 (P0-A: quotedLaborCostPerKg 字段)        ← 可独立开工, 不等SP1
    ↓
    ├── Task 3 (P1-A: 对比 API)                  ← P0-A 完后
    └── Task 4a (P1-B: 达成率 BE)                ← P0-A 完后
        └── Task 4b (P1-B: Vue 前端 Composer)    ← Task 4a merge 后

SP1 merge (YieldReportServiceImpl 整批 totalLaborCost)
    ↓
Task 2 (P0-B: actualLaborCost rollup)            ← SP1 merge 后单独 PR
    ↓ (rollup 有值后 Task 3 数据质量提升)

SP3 merge (三价成本引擎) → Task 3 数值准确性回归验证
```

---

## merge-before-deploy 安全检查

每次 PR merge 前必跑:
```bash
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway/ \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 必须无输出 (无 Flyway 重复版本号)

git diff origin/main...HEAD --stat
# 检查 scope 干净 (无 sister 文件夹带)
```

---

## 验收清单 (P0+P1 上线标准)

- [ ] `ProductType.quotedLaborCostPerKg` 可维护 (API + web 产品编辑)
- [ ] 研发填入值后 `GET compare` 返回非 null `quotedPerBox`
- [ ] 已完工批次 `actualLaborCostPerBox` 有值 (非 null)
- [ ] 差异率 > 10% 前端橙色高亮; > 20% 红色
- [ ] `@PriceSensitive` 脱敏: 销售角色查 compare API 成本字段全 null
- [ ] `quotedLaborCostPerKg = null` 时 `quotedPerBox = null`, 前端显示"-"
- [ ] Flyway 无重复版本 (uniq -d 无输出)
- [ ] `mvn test` 绿
- [ ] `vue-tsc --noEmit && npm run build` 绿

---

## 总成本估算

| 任务 | 预估工时 | 模型 |
|------|---------|------|
| Task 1 (字段+迁移) | 1.5h | Sonnet |
| Task 2 (rollup 钩子) | 1h | Sonnet (SP1 后) |
| Task 3 (对比 API) | 2.5h | Sonnet |
| Task 4a+4b (达成率) | 2h | Sonnet + Composer |
| Task 5 (P2 骨架) | 1.5h | Composer (周五后) |
| **合计 P0+P1** | **~7h** | |
