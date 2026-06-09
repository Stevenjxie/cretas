# SP9 · 人工双口径对比 + 人效模块 — 设计规格

> **Flyway 号段**: `V20260911_1x` (V20260911_10 ~ V20260911_19)
> **依赖**: SP1(生产闭环 BatchYield rollup) · SP3(三价成本引擎)
> **执行波次**: 波3(SP1/SP3 merge 进 main 后开工)
> **端**: backend + web-admin（客户明确: 人效模块本次 P2, 但 P0 基准字段需落）
> **生成**: 2026-06-09 Sonnet in-harness

---

## 1. 目标

老板核心诉求: "价格贵说明人效低或原辅材有问题" — 系统把**报价人工**与**财务实际人工**两个口径并排呈现, 量化每盒/每SKU 的人工差异, 支撑降本增效决策。

### 1.1 本子项做什么 (P0+P1 基准层)

| 层级 | 内容 | 优先级 |
|------|------|------|
| **M1** 研发预估人工字段 | `ProductType.quotedLaborCostPerKg`(元/kg成品) + BOM 维护入口 | P0 |
| **M2** 实际人工自动 rollup | 报工 `BatchYield.totalLaborCost` → 自动回填 `ProductionPlan.actualLaborCost`; 折算 `actualLaborCostPerBox` | P0 (依赖SP1 rollup) |
| **M3** 双口径对比 API | `GET /{fid}/labor-efficiency/compare` — 报价侧 + 实际侧 + 差异率 + 工序拆分 | P1 |
| **M4** 工序达成率 | 实际工时 / `WorkProcess.standardTimeMinutes` 达成率; 75%/150% 异常着色 | P1 |
| **M5** 人效模块 web 看板 | 已完工批次挂入待维护列表; 逐条维护界面(补/校工时人数, 查看报价 vs 实际) | P2 |

### 1.2 本子项不做什么

- 不建专门计件工资发放功能(已有 `WageCalculationService.PieceRateRule`; 本期不扩展)
- 不追溯人工成本来自哪个环节(摊到盒即可, 客户原话)
- 不修改现有 BOM 物料用量计算逻辑(人工字段独立于 BOM 用量, 防污染)
- M5 人效模块 web 本次出骨架, 不进行 P0 上线

---

## 2. 范围

```
scope-lock 文件 (本子项独占):
  backend:
    entity/ProductType.java          (+quotedLaborCostPerKg 列)
    service/labor/LaborEfficiencyService.java          (🆕)
    service/labor/impl/LaborEfficiencyServiceImpl.java (🆕)
    controller/LaborEfficiencyController.java          (🆕)
    dto/labor/LaborEfficiencyCompareDTO.java            (🆕)
    dto/labor/LaborVarianceItemDTO.java                 (🆕)
  web-admin:
    views/production/labor-efficiency/index.vue        (🆕)
  db/flyway: V20260911_1x__*.sql

scope-lock 只读/依赖(不改, 只读取):
  entity/ProductionPlan.java         (读 estimatedLaborCost / actualLaborCost)
  service/yield/YieldReportServiceImpl.java            (读 computeLaborCost 逻辑)
  dto/yield/BatchYieldDTO.java                         (读 totalLaborCost / steps[].laborCost)
  entity/WorkProcess.java                              (读 standardHourlyRate / estimatedMinutes)
  entity/bom/LaborCostConfig.java                      (读现有工序工价配置)
  entity/bom/BomRecipe.java                            (读 totalLaborCost 研发预估)
```

---

## 3. 现状复用清单 (grep 验证)

| 现有代码 | 路径 | SP9 使用方式 |
|---|---|---|
| `WorkProcess.standardHourlyRate` | entity/WorkProcess.java:75 | 读取工序时薪, 实际成本 = workers×minutes/60×rate |
| `BatchYieldDTO.totalLaborCost` | dto/yield/BatchYieldDTO.java:35 | SP1 产出整批人工总成本 — SP9 直接读, 不重算 |
| `StepYieldDTO.laborCost` | dto/yield/StepYieldDTO.java:37 | 逐工序人工 — 折盒拆分 |
| `BatchYieldDTO.steps[].totalWorkMinutes/totalWorkers` | BatchYieldDTO.java:29-31 | 工序达成率计算分母 |
| `ProductionPlan.estimatedLaborCost` | entity/ProductionPlan.java:102 | 计划级预估人工(现为手动 set, M2 改为从 rollup 自动填) |
| `ProductionPlan.actualLaborCost` | entity/ProductionPlan.java:105 | 同上 — 自动 rollup 目标 |
| `ProductionAnalyticsController` 路径 | controller/ProductionAnalyticsController.java:50 | budget-vs-actual 已有 estimatedLaborCost / actualLaborCost 列 — M2 改 rollup 后自动生效 |
| `CostVarianceReportDTO.laborVariance` | dto/report/CostVarianceReportDTO.java:108 | 已有差异 DTO — M3 可复用 |
| `BomRecipe.totalLaborCost` | entity/bom/BomRecipe.java:104 | 研发 BOM 层预估总人工(按整个 BOM 口径, 非元/kg) |
| `LaborCostConfig.unitPrice` | entity/bom/LaborCostConfig.java:67 | 工序工价配置 — M1 是对产品级 kg 口径的补充字段 |
| `ProductionReportServiceImpl.getEfficiencyAnalysisReport` | service/report/impl/:745 | 当前是空壳(totalOutput + 75 OEE), M4 扩展此方法 |
| `EfficiencyAnalysis.vue` 已有 budget-vs-actual tab | web-admin/views/production-analytics/:16 | M3 web 看板可以在此 tab 基础上扩展, 或建独立路由 |
| `@PriceSensitive` | security/PriceSensitive.java | M1 quotedLaborCostPerKg 标记此注解(成本字段对销售脱敏) |
| `ProductType.gramsPerUnit` | entity/ProductType.java:201 | 折盒换算: actualLaborCostPerBox = totalLaborCost ÷ (成品kg × 1000/gramsPerUnit) |

---

## 4. 数据模型增量

### 4.1 ProductType 加字段

```sql
-- V20260911_10__product_type_quoted_labor_cost.sql
ALTER TABLE product_types
  ADD COLUMN IF NOT EXISTS quoted_labor_cost_per_kg NUMERIC(12, 4) NULL
    COMMENT '研发预估人工成本(元/kg成品), 研发录入, @PriceSensitive';
```

- 必须做全 4 处 (entity+create+update+convertToDTO, 对齐 gramsPerUnit 镜像模式)
- `@PriceSensitive` 注解, 销售角色脱敏
- null = 未填, 诚实展示"-", 不默认 0

### 4.2 ProductionPlan 加自动 rollup 触发

不新增列 — 复用已有 `actualLaborCost` 列。
修改 `YieldReportServiceImpl` 在 `completeProduction` 路径中: 整批 `BatchYield.totalLaborCost` 非 null 时, `UPDATE production_plans SET actual_labor_cost = ? WHERE id = ?`。

> 注意: M2 修改 YieldReportServiceImpl — 与 SP1 同改此文件存在 scope-lock 冲突 → **M2 必须在 SP1 merge 进 main 后再做**, 否则撞文件。

### 4.3 新建 DTO

```
dto/labor/LaborEfficiencyCompareDTO.java  — 整批对比结果
  String batchNumber
  String productName
  BigDecimal quotedLaborCostPerKg           // 报价口径 (元/kg)
  BigDecimal actualLaborCostPerKg           // 实际口径 = totalLaborCost / goodQuantityKg
  BigDecimal quotedLaborCostPerBox          // 折盒 = quotedLaborCostPerKg × gramsPerUnit/1000
  BigDecimal actualLaborCostPerBox          // 折盒 = totalLaborCost / boxCount
  BigDecimal varianceRate                   // (actual-quoted)/quoted ×100%
  String varianceStatus                     // NORMAL/WARNING/CRITICAL (±10% 可配)
  List<LaborVarianceItemDTO> stepDetails    // 逐工序拆分

dto/labor/LaborVarianceItemDTO.java  — 逐工序明细
  String processName
  Integer totalWorkMinutes
  Integer totalWorkers
  BigDecimal laborCost                     // 本道实际人工
  BigDecimal laborCostPerBox               // = laborCost / boxCount
  BigDecimal achievementRate               // = totalWorkMinutes / (standardTimeMinutes × productionQty), null 若 standardTimeMinutes 未配
  String achievementAlert                  // null/BELOW(>+50%)/ABOVE(-25%)
```

### 4.4 数据库 — 无新表

SP9 无需新建表。唯一持久化变更是 4.1 的 `product_types` 加列。实际人工数据已在 `production_reports.labor_cost` 逐道持久化; 对比计算在服务层实时聚合。

---

## 5. 组件与数据流

```
研发填报价人工:
  web-admin: 产品库编辑弹窗 → PUT /{fid}/products/{id}
             字段: quotedLaborCostPerKg (元/kg成品)
             BOM 页 → 不填人工(BOM 只管用量配比, 避免污染)

报工完成 → 自动回填:
  YieldReportServiceImpl.completeProduction(batchId)
    → YieldCalculationServiceImpl.calculateSteps() → BatchYieldDTO.totalLaborCost
    → UPDATE production_plans SET actual_labor_cost = totalLaborCost (M2)

对比 API:
  GET /{fid}/labor-efficiency/compare?startDate=&endDate=&productTypeId=
    LaborEfficiencyServiceImpl:
      1. 取 ProductionPlan + ProductType(quotedLaborCostPerKg / gramsPerUnit)
      2. 取 BatchYieldDTO(totalLaborCost + steps[].laborCost + steps[].totalWorkMinutes)
      3. 计算 actualLaborCostPerKg = totalLaborCost / goodQuantityKg
      4. 折盒 = totalLaborCost / (goodQuantityKg × 1000 / gramsPerUnit)
      5. varianceRate = (actual - quoted) / quoted × 100 (quoted null → null, 不除零)
      6. achievementRate per step = totalWorkMinutes / (standardTimeMinutes × qty), standardTimeMinutes null → null
      7. 组装 LaborEfficiencyCompareDTO

人效模块 web 骨架 (M5, P2):
  views/production/labor-efficiency/index.vue
    tab1: 报价 vs 实际对比表(M3 API 驱动)
    tab2: 待维护列表(已完工批次, status=COMPLETED, actualLaborCost=null 的)
    tab3: 工序达成率看板(M4)
```

---

## 6. API 端点

| 方法 | 路径 | 说明 | 角色 |
|------|------|------|------|
| `GET` | `/{fid}/labor-efficiency/compare` | 双口径对比(带日期区间+产品过滤) | factory_super_admin/厂长/财务 |
| `GET` | `/{fid}/labor-efficiency/step-breakdown/{batchId}` | 单批次工序折盒拆分 | 同上 |
| `GET` | `/{fid}/labor-efficiency/achievement-rate` | 工序达成率看板(日期区间) | 同上 |

> 以上端点全部 `@PriceSensitive` 对销售角色脱敏(成本数字)。

---

## 7. 错误处理 — fool-proof 4位一体

| 场景 | 处理 |
|------|------|
| quotedLaborCostPerKg 未填 | API 返回 `quoted: null`, 前端显示"-"而非 0; 不影响实际侧展示 |
| WorkProcess.standardHourlyRate 未配置 | `achievementRate = null`, UI 显示"工价未配置"; 不阻止其他字段展示 |
| goodQuantityKg = 0(避免除零) | actualLaborCostPerKg = null, 诚实展示"-" |
| 批次报工未完成 | 实际侧 null, 前端展示"待完工" badge |
| 差异超阈值 | sticky error toast (duration:0, showClose), message="[产品名]人工成本超标 X%, 建议反馈研发/销售", actionHint = 研发确认链接 |

---

## 8. 测试策略

### 8.1 Backend 单元测试 (TDD 先写)

```
LaborEfficiencyServiceImplTest:
  test_compare_returns_null_quoted_when_not_set()
  test_compare_calculates_actual_per_box_correctly()
  test_variance_rate_null_when_quoted_null()
  test_achievement_rate_null_when_standard_minutes_not_set()
  test_zero_quantity_returns_null_cost_per_box()
  test_variance_status_normal_warning_critical()
  test_step_breakdown_sums_correctly()

ProductTypeServiceImplTest (扩展现有):
  test_create_product_type_persists_quoted_labor_cost()
  test_update_product_type_preserves_quoted_labor_cost()
  test_convert_to_dto_maps_quoted_labor_cost()
```

### 8.2 Web vue-tsc + build (不出新测试文件, 但必过)

```bash
cd web-admin && npx vue-tsc --noEmit && npm run build
```

### 8.3 E2E 验收 (headed, zh-CN)

```
场景1: 产品库编辑 → 填 quotedLaborCostPerKg = 5.00 → 保存 → 再打开确认持久化
场景2: 已完工批次 → GET compare API → 有 actualLaborCostPerBox, varianceRate 非 null
场景3: 差异 > 15% → 前端红色标注
```

---

## 9. 依赖与阻塞

| 依赖 | 说明 |
|------|------|
| **SP1 必须先 merge** | M2 修改 YieldReportServiceImpl 在 SP1 修改同文件后才可做, 否则撞 scope-lock |
| **SP3 推荐先 merge** | SP3 建立三价成本引擎; SP9 M3 对比 API 使用 `actualLaborCost` 由 SP3 移动均价引擎正确产出才有意义. SP3 未 merge 时 M3 数据质量低但不阻塞接口骨架 |
| ProductType.gramsPerUnit | 已有(entity/ProductType.java:201), 无阻塞 |

---

## ⚠️ 跨子项依赖 / 风险

1. **scope-lock 冲突 (YieldReportServiceImpl)**: SP9 M2 与 SP1/SP2 均修改此文件 → **必须串行**: SP1 → SP2 → SP9 M2。SP9 M1/M3/M4/M5 不改此文件, 可在 SP1 之前开工。

2. **SP3 成本口径对齐**: SP9 的 `actualLaborCostPerKg` 从 `BatchYield.totalLaborCost` 推导; SP3 移动均价引擎改变成本计算方式。SP3 merge 后需回归测试 M3 数值正确性。

3. **ProductType 4处修改**: 加 `quotedLaborCostPerKg` 需改 `ProductTypeServiceImpl.createProductType/updateProductType/convertToDTO`。若 SP4 同期改 `ProductType`(加 taxRate 字段), 需串行或协调字段 PR 合并。当前 scope-lock: SP4 修改 `ProductType`(主编码字段), SP9 修改 `ProductType`(人工字段) → **需串行或拆独立 PR**。建议 SP4 先 merge, SP9 在其之后 off origin/main 开工。

4. **客户 P2 优先级**: 客户明确"人效模块本次不管" → M5 人效模块 web 骨架是 P2, 不影响周五演示。M1(字段)+M3(API)需 P0 落地供 demo 展示用。

5. **折盒算法 ambiguity**: 逐工序成本分摊算法客户存疑(B §173)。M3 折盒用简单比例法(等比分摊到每盒), 在 UI 标注"按报工工时等比分摊"; 若客户周五提出不同分摊方案, 只需改 `LaborEfficiencyServiceImpl` 单服务, 无 schema 变更。
