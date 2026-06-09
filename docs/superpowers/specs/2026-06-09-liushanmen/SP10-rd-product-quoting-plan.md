# SP10 · 研发/产品经理报价 — 实施计划 (Plan)

> **子项**: SP10 · 研发/产品经理报价 (预报价 / 中报价 / 试制库)
> **Flyway 号段**: `V20260911_20` ~ `V20260911_29` (SP10 独占)
> **波次**: 波4 — SP3 + SP4 merge 后启动
> **执行规则**:
> - worktree: `git worktree add -b feat/sp10-rd-quoting ../cretas-sp10 origin/main`
> - commit: `git commit -- <files>` (path-locked, 防并发污染)
> - 🔒 红线: 权限/成本脱敏 → Opus 终审; impl 执行者到 PR 停
> - Fleet: Codex/GPT 暂停 → 只 Composer(out-of-harness UI) + Sonnet in-harness(rule-heavy Java)
> - merge 后部署前必查重: `git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`

---

## 前置条件

| 条件 | 验证方式 |
|---|---|
| SP3 已 merge 到 main | `git log origin/main --oneline | grep SP3` + `git ls-tree origin/main db/flyway | grep V20260910_2` |
| SP4 已 merge 到 main | `git log origin/main --oneline | grep SP4` + `git ls-tree origin/main db/flyway | grep V20260910_3` |
| worktree off origin/main | `git worktree add -b feat/sp10-rd-quoting ../cretas-sp10 origin/main` |

若 SP3 未 merge: T2 中报价汇算改为 STUB 实现 (material_cost_per_kg=null, 加 TODO:SP3), T3 三价对比 actualCost 返 null + 占位 UI.

---

## 任务分解 (TDD: 先红后绿)

### T1 — 数据层: QuotationTask/ProductionBatch 扩字段 + ProductMidQuote 实体

**模型路由**: Sonnet in-harness (rule-heavy Java: DTO往返4处 + @PriceSensitive + BaseEntity)
**worktree**: `feat/sp10-rd-quoting` (独立)
**effort**: high

#### 允许改

```
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/rd/QuotationTask.java
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/rd/ProductMidQuote.java   (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/entity/production/ProductionBatch.java
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/QuotationTaskDTO.java       (已有,扩)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/ProductMidQuoteDTO.java     (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/CreateProductMidQuoteRequest.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/repository/rd/ProductMidQuoteRepository.java (🆕)
db/flyway/V20260911_20__sp10_quotation_task_stage_and_labor.sql
db/flyway/V20260911_21__sp10_production_batch_trial_flag.sql
db/flyway/V20260911_22__sp10_product_mid_quote.sql
```

#### 禁改

`YieldReportServiceImpl`, `SemiFinishedInventory*`, `MaterialBatch`, `BomRecipeItem`, `SalesOrder*`

#### 先写测试

```java
// QuotationTaskDTORoundtripTest
@Test void laborPerKg_savedAndLoaded() {
    QuotationTask task = ...;
    task.setLaborPerKg(new BigDecimal("12.50"));
    task.setQuoteStage("PRE");
    task.setBomMaterialCost(new BigDecimal("35.00"));
    repo.save(task);
    QuotationTask loaded = repo.findById(task.getId()).orElseThrow();
    assertThat(loaded.getLaborPerKg()).isEqualByComparingTo("12.50");
    assertThat(loaded.getQuoteStage()).isEqualTo("PRE");
}

// ProductMidQuoteEntityTest
@Test void productMidQuote_persistAndFindBySampleId() {
    ProductMidQuote q = buildMidQuote();
    repo.save(q);
    List<ProductMidQuote> found = repo.findByFactoryIdAndSampleId(q.getFactoryId(), q.getSampleId());
    assertThat(found).hasSize(1);
    assertThat(found.get(0).getTotalCostPerKg()).isEqualByComparingTo("47.50");
}

// ProductionBatchTrialFlagTest
@Test void trialBatch_flaggedAndQueryable() {
    ProductionBatch batch = buildBatch(true, "sample-001");
    batchRepo.save(batch);
    List<ProductionBatch> trials = batchRepo.findByFactoryIdAndIsTrialTrue("F006");
    assertThat(trials).anyMatch(b -> b.getTrialSampleId().equals("sample-001"));
}
```

#### 实施要点

1. `QuotationTask` 加三字段: `laborPerKg`(@PriceSensitive) + `quoteStage`(String, default "PRE") + `bomMaterialCost`(@PriceSensitive) + `midQuoteId`
2. `ProductMidQuote extends BaseEntity` — 全成本字段 `@PriceSensitive`(至少5个), `@Column(nullable=false)` 仅 factoryId/sampleId/trialBatchId/status, 其余 nullable 诚实 null
3. `ProductionBatch` 加 `isTrial`(default false) + `trialSampleId`
4. DTO 往返全 4 处(遵循 feedback_dto_roundtrip_silent_drop)
5. Flyway migration 顺序: V20260911_20 → 21 → 22 → 23(fallback, 幂等 IF NOT EXISTS)

#### 验收

```bash
./mvnw test -pl backend/java/cretas-api -Dtest="QuotationTaskDTORoundtripTest,ProductMidQuoteEntityTest,ProductionBatchTrialFlagTest"
# 全绿 + 0 编译警告
```

---

### T2 — 服务层: 中报价汇算引擎

**模型路由**: Sonnet in-harness (rule-heavy: SP3移动均价引用 + null诚实 + HALF_UP精度 + 幂等)
**worktree**: 同 `feat/sp10-rd-quoting`
**依赖**: T1 完成 + SP3 已有 `SemiFinishedInventoryTxn` ledger
**effort**: high

#### 允许改

```
backend/java/cretas-api/src/main/java/com/cretas/aims/service/rd/ProductMidQuoteService.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/service/rd/impl/ProductMidQuoteServiceImpl.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/MidQuoteCalculationResultDTO.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/ThreePriceComparisonDTO.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/service/rd/impl/ProductSampleServiceImpl.java (扩 submitQuotation 含 bom_material_cost 自动带入)
backend/java/cretas-api/src/main/java/com/cretas/aims/repository/rd/ProductMidQuoteRepository.java (🆕, T1已建接口)
```

#### 禁改

`CostRollupUtil`(只调用不改), `SemiFinishedInventoryTxn`(只读), `YieldReportServiceImpl`, `SalesServiceImpl`(只调用不改)

#### 先写测试

```java
// ProductMidQuoteServiceTest
@Test void calculate_withFullData_computesCorrectCosts() {
    // Given: 试制批次 is_trial=true, 报工2段(3人×4h×15元/h + 2人×2h×15元/h), 产出100kg
    // Given: SemiFinishedInventoryTxn 材料投入合计 3000元/100kg→30元/kg
    // Given: OverheadConfig 5元/kg
    // When:
    MidQuoteCalculationResultDTO result = service.calculate(factoryId, "trial-batch-001", "qt-001");
    // Then:
    assertThat(result.getMaterialCostPerKg()).isEqualByComparingTo("30.00"); // 移动均价
    // labor = (3×4×15 + 2×2×15) / 100 = (180+60)/100 = 2.40 元/kg
    assertThat(result.getLaborCostPerKg()).isEqualByComparingTo("2.40");
    assertThat(result.getOverheadCostPerKg()).isEqualByComparingTo("5.00");
    assertThat(result.getTotalCostPerKg()).isEqualByComparingTo("37.40");
}

@Test void calculate_withNullLaborSegments_returnsNullLaborCost() {
    // 无报工人工段 → labor_cost_per_kg=null (诚实空, 不返0)
    MidQuoteCalculationResultDTO result = service.calculate(factoryId, "trial-batch-no-labor", "qt-002");
    assertThat(result.getLaborCostPerKg()).isNull();
    assertThat(result.getTotalCostPerKg()).isNull(); // 有null组件则合计null
}

@Test void calculate_idempotent_returnsExistingOnReCalculate() {
    // 已汇算 → 重复调用返已有(不创建新记录)
    service.calculate(factoryId, "trial-batch-001", "qt-001");
    ProductMidQuote existing = service.calculate(factoryId, "trial-batch-001", "qt-001");
    assertThat(midQuoteRepo.countByTrialBatchId("trial-batch-001")).isEqualTo(1L);
}

@Test void calculate_varianceAlert_triggeredWhenOverThreshold() {
    // pre_quote_total=30元/kg, 中试算出35元/kg → variance=16.67% > 10% threshold
    MidQuoteCalculationResultDTO r = service.calculate(...);
    assertThat(r.getCostVariancePct()).isGreaterThan(new BigDecimal("10"));
    assertThat(r.isVarianceAlert()).isTrue();
}

// ProductSampleServiceImplTest (扩submitQuotation)
@Test void submitQuotation_withCompleteBom_autoFillsBomMaterialCost() {
    // BomRecipe.totalCost = 35.50 → 提交时 bom_material_cost 自动带入
    QuotationTask saved = service.submitQuotation(factoryId, taskId, req);
    assertThat(saved.getBomMaterialCost()).isEqualByComparingTo("35.50");
}

@Test void submitQuotation_withIncompleteBom_bomMaterialCostNull() {
    // BOM DRAFT, totalCost=null → bom_material_cost=null (诚实)
    QuotationTask saved = service.submitQuotation(factoryId, taskId, req);
    assertThat(saved.getBomMaterialCost()).isNull();
}
```

#### 实施要点

1. `calculate()` 流程:
   a. 校验 batch.is_trial=true (不是→400 "非试制批次"), batch.status=COMPLETED (未完成→409)
   b. 幂等检查: `findByFactoryIdAndTrialBatchId` 已存在且 CONFIRMED → 返已有
   c. 材料成本: 从 `SemiFinishedInventoryTxn` 汇算(SP3 ledger); 或 fallback 从 `ProductionReport.materialCost` (若SP3未merge)
   d. 人工成本: 从 `ProductionReport.laborSegments` 读 `workerCount×workMinutes×workProcess.standardHourlyRate / 3600`; 任一段工价 null → 该段跳过; 全null → laborCostPerKg=null
   e. 合计: 任一 null → totalCostPerKg=null (诚实, Rule "禁止降级处理")
   f. variance: 仅 pre_quote_total 和 totalCostPerKg 均非null时计算
   g. 保存 ProductMidQuote(status=CALCULATED); QuotationTask.quoteStage→MID; midQuoteId→关联
2. 精度: ROUND_HALF_UP, scale-4 (对齐 CostRollupUtil)
3. 幂等键: UNIQUE(factory_id, trial_batch_id) 已在迁移中建

#### 验收

```bash
./mvnw test -pl backend/java/cretas-api -Dtest="ProductMidQuoteServiceTest,ProductSampleServiceImplTest"
# 所有 test void calculate_* 绿
```

---

### T3 — Controller: 端点 + 三价对比 + 脱敏

**模型路由**: 🔒 Opus (脱敏/权限是红线 — 成本泄露给销售角色的bug; 执行到PR, Opus终审diff)
**worktree**: 同 `feat/sp10-rd-quoting`
**effort**: high (Opus 本体直接做, brief+review不值 — keystone小而微妙)

#### 允许改

```
backend/java/cretas-api/src/main/java/com/cretas/aims/controller/rd/RdController.java (扩)
backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/ThreePriceComparisonDTO.java (🆕, T2已建)
backend/java/cretas-api/src/main/java/com/cretas/aims/service/rd/ThreePriceComparisonService.java (🆕)
backend/java/cretas-api/src/main/java/com/cretas/aims/service/rd/impl/ThreePriceComparisonServiceImpl.java (🆕)
```

#### 先写测试

```java
// RdControllerTest (MockMvc)
@Test void calculateMidQuote_unauthorizedRole_returns403() {
    mockMvc.perform(post("/api/mobile/F006/rd/mid-quotes/calculate")
        .header("role", "sales") // 非 PRODUCT_MANAGER/RD_STAFF
        .contentType(APPLICATION_JSON).content("{...}"))
        .andExpect(status().isForbidden());
}

@Test void threePriceComparison_salesRole_costDetailsDesensitized() {
    // 销售角色: totalCostPerKg 脱敏(null或***), materialCostPerKg null
    mockMvc.perform(get("/api/mobile/F006/rd/quotations/sample-001/three-price-comparison")
        .header("role", "sales"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.midQuote.materialCostPerKg").doesNotExist()); // @PriceSensitive
}

@Test void threePriceComparison_adminRole_seesAllCosts() {
    mockMvc.perform(get("/api/mobile/F006/rd/quotations/sample-001/three-price-comparison")
        .header("role", "factory_super_admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.midQuote.totalCostPerKg").exists());
}

@Test void calculateMidQuote_batchNotCompleted_returns409() {
    // 试制批次 status=IN_PROGRESS → 409 + actionHint
    mockMvc.perform(post("/api/mobile/F006/rd/mid-quotes/calculate")
        .header("role", "product_manager")
        .content("{\"trialBatchId\":\"in-progress-batch\",\"quotationTaskId\":\"qt-001\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("尚未完成")));
}
```

#### 实施要点

1. 新端点:
   - `POST /rd/mid-quotes/calculate` → `@RequireRole({"PRODUCT_MANAGER","RD_STAFF","FACTORY_ADMIN"})`
   - `GET /rd/mid-quotes/{id}` → `@PriceSensitive` 通过 ResponseAdvice 自动处理
   - `PUT /rd/mid-quotes/{id}/confirm` → `@RequireRole({"FACTORY_ADMIN","FINANCE"})`
   - `GET /rd/quotations/{sampleId}/three-price-comparison` → 所有角色可见(但@PriceSensitive脱敏细节)
2. `ThreePriceComparisonServiceImpl.compare()`:
   - preQuote: 从 QuotationTask (stage=PRE, QUOTED) 取 laborPerKg + bomMaterialCost + totalCost
   - midQuote: 从 ProductMidQuote (CALCULATED/CONFIRMED) 取
   - actualCost: 调用 `SalesServiceImpl.getFinanceCostBreakdown(sampleId)` (SP3产出); SP3未merge返null + warning
3. **🔒 脱敏**: `ThreePriceComparisonDTO` 成本明细字段加 `@PriceSensitive` — PriceFieldResponseAdvice 自动过滤销售角色字段
4. **红线验证**: `grep -n "@PriceSensitive" .../ProductMidQuote.java | wc -l` ≥ 5; `grep -n "@PriceSensitive" .../ThreePriceComparisonDTO.java | wc -l` ≥ 4

#### 🔒 收尾约束

**只做到 PR + 自测. 不许自部署 prod. 回 main 由 Opus 终审:**
```bash
git diff origin/main...HEAD --stat   # 确认 scope 干净 (仅 rd/ controller + 新实体 + migration)
# 验证无 SalesOrder*/YieldReport*/SemiFinishedInventory* 被改动
```

#### 验收

```bash
./mvnw test -pl backend/java/cretas-api -Dtest="RdControllerTest,ThreePriceComparisonServiceTest"
# 401/403/409/200 全覆盖; 脱敏对比通过
```

---

### T4 — Web Admin: 预报价表单扩展

**模型路由**: Composer 2.5 (out-of-harness, UI/样式)
**worktree**: 同 `feat/sp10-rd-quoting`
**effort**: default

#### Brief (self-contained, Composer不看.claude/rules)

```
目标: 扩展 web-admin 研发报价表单
  1. views/rd/quotations/detail.vue 或 form.vue:
     - 新增 "人工成本 (元/kg成品)" 数字输入框 (labor_per_kg, 小数4位)
     - 标注: "研发经验估算, 单位: 元/kg成品" (tooltip/说明文字)
     - BOM材料成本自动带入展示: "BOM材料成本: ¥{bomMaterialCost}/kg" 只读文本
       (若 null 显 "BOM尚未完成, 请手填" + 黄色提示)
     - quote_stage badge: PRE=预报价/蓝色, MID=中报价/橙色, FINAL=最终/绿色
  2. views/production/batches/create.vue:
     - 新增 "此为试制批次" el-switch
     - 选中时展示"关联样品" el-select (调 /rd/samples?status=APPROVED 接口)
  3. 类型: 在 src/types/ 扩 QuotationTask 类型加 laborPerKg/bomMaterialCost/quoteStage/midQuoteId

禁改: views/rd/mid-quotes/ (T5单独做), views/production/ 其他文件, src/api/ 之外的 java

验收: vue-tsc --noEmit + vite build 无报错
```

#### 允许改

```
web-admin/src/views/rd/quotations/detail.vue (或 form.vue, 视现有文件)
web-admin/src/views/production/batches/create.vue
web-admin/src/types/rd.ts (或相关类型文件)
web-admin/src/api/rd.ts (加 labor_per_kg 字段)
```

#### 验收

```bash
cd web-admin && npx vue-tsc --noEmit && npx vite build
# 无 TypeScript 错误; build 成功
```

---

### T5 — Web Admin: 中报价卡片 + 三价对比看板

**模型路由**: Composer 2.5 (out-of-harness, 纯UI新页面)
**worktree**: 同 `feat/sp10-rd-quoting`
**依赖**: T3 后端端点 + T4 基础类型
**effort**: default

#### Brief (self-contained)

```
目标: 新建两个 Vue 组件
  1. web-admin/src/views/rd/mid-quotes/detail.vue — 中报价详情卡片
     - 显示: 试制批次号/产品名/试制日期
     - 成本三行: 材料成本/人工成本/制造费用 (各 "¥X.XX/kg" 或 "-待汇算-")
     - 合计总成本行 (粗体)
     - 与预报价对比: 箭头 + 超支百分比 badge (超支>threshold→红色, 正常→绿色)
     - 超支警告: el-alert type="warning" sticky 按 fool-proof Rule (不卡死)
     - "汇算" button → 调 POST /rd/mid-quotes/calculate
     - "确认中报价" button (仅 FACTORY_ADMIN/FINANCE 角色显示)

  2. web-admin/src/views/rd/quotations/three-price.vue — 三价对比看板
     - 三列: 预成本价 | 中试成本价 | 实际成本价
     - 每列标题 + 金额 (null 显占位 "等待数据")
     - 超支 badge: 预→中偏差% + 中→实际偏差%
     - 使用 Element Plus el-table 或 el-descriptions 布局

API: GET /api/mobile/{factoryId}/rd/quotations/{sampleId}/three-price-comparison
类型文件: web-admin/src/types/rd.ts (T4已扩, 沿用)

fool-proof:
  - null 字段显 "-" 或状态说明(不显 ¥0.00)
  - 超支 warning: duration:0 + showClose (sticky)
  - "汇算" button 点击中 loading + disable

验收: vue-tsc --noEmit + vite build 无报错
禁改: backend/, db/, 其他 views/ 子目录
```

#### 允许改

```
web-admin/src/views/rd/mid-quotes/detail.vue (🆕)
web-admin/src/views/rd/quotations/three-price.vue (🆕)
web-admin/src/views/rd/quotations/detail.vue (仅加三价对比入口tab, 不改其他逻辑)
web-admin/src/api/rd.ts (加 calculateMidQuote / getThreePriceComparison 方法)
web-admin/src/router/index.ts (加 /rd/mid-quotes/:id + /rd/quotations/:sampleId/three-price 路由)
```

#### 验收

```bash
cd web-admin && npx vue-tsc --noEmit && npx vite build
# + 手动 headed: 打开三价对比看板 → null 占位正确显示; 超支 badge 渲染
```

---

### T6 — 集成验收 + E2E (headed)

**模型路由**: Sonnet in-harness (CLI/集成 + 构建)
**worktree**: 同 `feat/sp10-rd-quoting`
**依赖**: T1~T5 全绿

#### 验收检查单

```bash
# 1. 全量后端测试 (SP10相关)
./mvnw test -pl backend/java/cretas-api \
  -Dtest="*MidQuote*,*ThreePrice*,*QuotationTask*,*ProductionBatch*Trial*" \
  -Dsurefire.failIfNoSpecifiedTests=false
# 预期: ≥ 15 tests, ALL GREEN

# 2. Flyway号段查重 (merge前必做)
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 预期: 无重复输出

# 3. Web类型+构建
cd web-admin && npx vue-tsc --noEmit && npx vite build
# 预期: 无TypeScript错误

# 4. scope 干净确认
git diff origin/main...HEAD --stat | grep -v "rd/\|migration\|V20260911_2"
# 预期: 仅SP10相关文件 (无YieldReportServiceImpl/SemiFinishedInventory等)

# 5. @PriceSensitive 覆盖验证 (Opus终审执行)
grep -c "@PriceSensitive" backend/java/cretas-api/src/main/java/com/cretas/aims/entity/rd/ProductMidQuote.java
# 预期: ≥ 5
grep -c "@PriceSensitive" backend/java/cretas-api/src/main/java/com/cretas/aims/dto/rd/ThreePriceComparisonDTO.java
# 预期: ≥ 4
```

#### E2E (headed, zh-CN)

```
场景1: 预报价表单
  1. 打开 /rd/quotations/{taskId}
  2. 填写 labor_per_kg = 12.50
  3. 验证: BOM材料成本自动带入提示显示 (或"BOM尚未完成"提示)
  4. 提交 → toast "预报价提交成功"
  5. 重复提交 → 幂等拦截 409 + "已提交预报价, 是否查看?" 弹窗

场景2: 试制批次标记
  1. 打开新建批次 /production/batches/create
  2. 勾选"此为试制批次" → 关联样品下拉显示
  3. 选择样品 → 保存 → 批次列表显示[试制]badge

场景3: 中报价汇算 (需已有试制批次完成报工)
  1. 打开 /rd/mid-quotes (或从样品详情进入)
  2. 点击"汇算" → loading → 中报价卡片渲染
  3. 若超出阈值: 黄色 warning sticky 显示超支%

场景4: 三价对比看板
  1. 打开 /rd/quotations/{sampleId}/three-price
  2. 验证三列: 预成本价(有值)/中试成本价(有值或"等待数据")/实际成本(SP3有则有值)
  3. 销售账号登录: 成本明细字段不可见(脱敏)
```

---

## Flyway 号段使用计划

| 迁移文件 | 内容 | 状态 |
|---|---|---|
| `V20260911_20__sp10_quotation_task_stage_and_labor.sql` | QuotationTask 扩字段 | T1 |
| `V20260911_21__sp10_production_batch_trial_flag.sql` | ProductionBatch.is_trial | T1 |
| `V20260911_22__sp10_product_mid_quote.sql` | ProductMidQuote 表 | T1 |
| `V20260911_23__sp10_cost_variance_config_fallback.sql` | 超支阈值配置 fallback (幂等) | T1 |
| `V20260911_24` ~ `V20260911_29` | 预留 (扩展/hotfix) | — |

**查重纪律** (每次 PR 前必执行):
```bash
git fetch origin
git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 无输出 = 安全; 有输出 = 必须重编号未 apply 的
```

---

## 分发总览

| # | 任务 | 模型 | effort | orchestration | 分支 | 🔒 |
|---|---|---|---|---|---|---|
| T1 | 数据层扩字段 + 新实体 | Sonnet in-harness | high | inline | feat/sp10-rd-quoting | — |
| T2 | 中报价汇算服务 | Sonnet in-harness | high | inline | feat/sp10-rd-quoting | — |
| T3 | Controller + 三价对比 + 脱敏 | 🔒 Opus 本体 | high | inline | feat/sp10-rd-quoting | 🔒 脱敏/权限 |
| T4 | Web 预报价表单扩展 | Composer 2.5 | default | inline | feat/sp10-rd-quoting | — |
| T5 | Web 中报价卡片 + 三价看板 | Composer 2.5 | default | inline | feat/sp10-rd-quoting | — |
| T6 | 集成验收 + E2E | Sonnet in-harness | high | inline | feat/sp10-rd-quoting | — |
| 终审 | diff 审查 + 部署 | 🔒 Opus organizer | high | inline | origin/main | 🔒 |

**并行**: T1 → T2 → T3 (串行, 各依赖前者). T4 可与 T2 并行(不改同一文件). T5 依赖 T3(需后端端点) + T4(类型).

**收尾**: T3 做到 PR 停, 不自部署. Opus organizer 终审 `git diff origin/main...HEAD --stat` → merge main → 从 main 部署 → 核对 jar 含 ProductMidQuote 标记.

---

## 🔒 红线收尾协议

T3 (脱敏/权限) 执行者约束:
1. 只做到 "实现 + 自测 + PR off origin/main"
2. 不许自部署 prod
3. PR 描述包含: `@PriceSensitive grep 结果` + `MockMvc 403/脱敏测试截图`
4. Opus 终审执行:
   ```bash
   gh pr diff <PR号>   # 验远端 diff (非本地 worktree)
   grep -c "@PriceSensitive" backend/.../ProductMidQuote.java    # ≥ 5
   grep -c "@PriceSensitive" backend/.../ThreePriceComparisonDTO.java  # ≥ 4
   git diff origin/main...HEAD --stat | grep -E "YieldReport|SemiFinished|MaterialBatch|SalesOrder"
   # 预期: 无输出 (scope 干净)
   ```
5. merge → `./scripts/deploy/deploy-backend.sh --env prod` (从 main)
6. 核对 jar: `ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/cretas/aims/entity/rd/ProductMidQuote.class' | strings | grep -c 'laborCostPerKg'"`
   → 预期: ≥ 1
