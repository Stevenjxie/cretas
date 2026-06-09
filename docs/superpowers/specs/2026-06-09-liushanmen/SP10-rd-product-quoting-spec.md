# SP10 · 研发/产品经理报价 — 设计规范 (Spec)

> **子项**: SP10 · 研发/产品经理报价 (预报价 / 中报价 / 试制库)
> **Flyway 号段**: `V20260911_20` ~ `V20260911_29`
> **依赖**: SP3(三价成本引擎) + SP4(一物一码 / 税率)
> **执行波次**: 波4 (P1) — 可在 SP3+SP4 merge 后并行启动
> **生成**: 2026-06-09 Opus architect
> **脊梁**: 严格遵循 `00-master-blueprint.md` — 不得单独另建数据模型或绕过红线

---

## 1. 目标

G 流 — 研发/产品经理报价闭环, 覆盖从"样品审核通过"到"最终成本定价"的全链路:

1. **预报价 (Pre-Quote)**: 样品审核通过后, 报价人员依经验填写人工成本(元/kg成品口径) + 系统从 BomRecipe 自动取材料成本 → 汇总预成本价。
2. **中报价 (Mid-Quote)**: 100–200 kg 小试完成后, 从实际试制批次数据(移动均价 + 报工人工)自动汇算中试成本价, 与预报价对比。
3. **三价对比视图**: 预成本价 / 中试成本价 / 销售实际成本价(SP3产出) 三价并列 + 超支百分比报警(决策5: 百分比阈值, 默认10%)。
4. **试制批次标记**: 在现有 `ProductionBatch` 上加 `is_trial` 标记, 将小试批次与正式批次区分。

**不做 (MVP 明确推迟)**:
- 试制专用独立库存 (客户语: "先下负责得了", 已明确推迟)
- 试样采购按价位区间自动匹配原料 (技术可行但客户推迟)
- AI 自动估工时/人工 (预留 hook, 不实现)
- 试制 WIP 独立余额账 (复用正式批次链路, `is_trial=true` 区分即可)

---

## 2. 范围 (做 / 不做)

### 2.1 本子项做

| 功能 | 端 | 状态 |
|---|---|---|
| QuotationTask 补 `labor_per_kg` 字段(元/kg口径) + `quote_stage` 枚举 | be | 补字段 |
| QuotationTask 补 `bom_material_cost` 自动从BomRecipe取 | be | 新逻辑 |
| 中报价 `ProductMidQuote` 实体 — 与试制批次关联 | be | 🆕 |
| `ProductionBatch.is_trial` 标记 — 区分小试 vs 正式 | be | 补字段 |
| 中报价计算服务 — 从试制批次移动均价+人工汇算 | be | 🆕 |
| 三价对比端点 `GET /rd/quotations/{sampleId}/three-price-comparison` | be | 🆕 |
| 预报价表单 — 新增 `labor_per_kg` 人工字段 + BOM成本自动带入 | web | 补UI |
| 试制批次创建 — 下拉选"试制批次"(is_trial=true) + 标记提示 | web+RN | 补UI |
| 中报价详情卡片 — 试制批次汇算 + 与预报价对比 | web | 🆕 |
| 三价对比看板 tab (预报价/中报价/实际成本) + 超支百分比报警 | web | 🆕 |
| QuotationTask 端点扩充(预报价提交/中报价关联/确认) | be | 扩 |

### 2.2 不做 / 推迟

- 试制独立库存账户(仓库类型=TRIAL, 与正式分开) — 推迟
- 试样采购按价位区间选料 — 推迟
- AI 工时估算 — 推迟(可加 `ai_labor_estimate_hook` 占位)
- 报价流转到外部客户(邮件/PDF) — 不在本子项

---

## 3. 现状复用 (grep 已验证)

### 3.1 完整复用 (不改, 直接用)

| 现有代码 | 位置 | 用途 |
|---|---|---|
| `QuotationTask` (entity) | `entity/rd/QuotationTask.java` | 预报价核心实体 — 已有 materialCost/laborCost/overheadCost/totalCost/profitMargin/@PriceSensitive |
| `RdRequest` (entity) | `entity/rd/RdRequest.java` | 研发需求单 |
| `ProductSample` (entity) | `entity/rd/ProductSample.java` | 样品档案 — 含 productTypeId/bomProductTypeId/mainMaterialYieldRate/customerExpectedPrice |
| `ProductSampleTrackingRecord` | `entity/rd/ProductSampleTrackingRecord.java` | 样品进度追踪+照片 |
| `SampleApprovedEventListener` | `event/listener/SampleApprovedEventListener.java` | 样品审核通过→自动创建BomDraft + QuotationTask(PENDING) |
| `OperationalQuote` (entity) | `entity/sales/OperationalQuote.java` | 销售运营报价(3阶段审批) — 含 unitPrice/costPrice/marginRate |
| `BomRecipe` / `BomRecipeItem` | `entity/bom/` | BOM材料成本来源(totalCost字段) |
| `@PriceSensitive` + `PriceFieldResponseAdvice` | `security/` | 成本价对销售角色脱敏 |
| `CostRollupUtil` | `service/impl/` | 成本汇总工具 |
| AI Tools: `SampleToBomTool`, `SampleApproveTool` | `ai/tool/impl/` | AI意图工具 |
| `QuotationTaskRepository` | `repository/rd/` | 报价任务查询 |
| `ProductSampleRepository` | `repository/rd/` | 样品查询 |
| `RdController` | `controller/rd/RdController.java` | `/rd/requests`, `/rd/samples`, `/quotations` — 扩此controller |

### 3.2 复用+扩展

| 现有代码 | 扩展内容 |
|---|---|
| `QuotationTask` | 新增 `labor_per_kg`(人工元/kg口径) + `quote_stage`(PRE/MID_PENDING/MID/FINAL) + `bom_material_cost`(自动带入) |
| `ProductionBatch` | 新增 `is_trial`(boolean) + `trial_sample_id`(FK→ProductSample) |
| `RdController` | 新增中报价相关端点 + 三价对比端点 |
| `ProductSampleServiceImpl` | 扩充 `submitQuotation` 含 labor_per_kg 口径 + 自动取BOM成本 |
| `SampleApprovedEventListener` | 创建 QuotationTask 时 `quote_stage=PRE` |

### 3.3 依赖 SP3 产出 (必须 SP3 先 merge)

- `SalesServiceImpl.getFinanceCostBreakdown` — 三价对比中的"实际成本价"来源
- `cost_variance_threshold_pct` 配置 — 超支百分比报警阈值(SP3建, SP10读)
- `SemiFinishedInventoryTransaction` ledger — 中报价计算引用移动均价

### 3.4 依赖 SP4 产出 (必须 SP4 先 merge)

- `ProductType.tax_rate` — 报价含税/未税换算
- 批次条码标签 — 试制批次扫码标记场景

---

## 4. 数据模型增量

> 遵循蓝图 §2 "加字段必做全 4 处": Entity字段 + create set + update null-guard set + convertToDTO map

### 4.1 QuotationTask 扩字段 (迁移 V20260911_20)

```sql
-- V20260911_20__sp10_quotation_task_stage_and_labor.sql
ALTER TABLE quotation_tasks
    ADD COLUMN quote_stage      VARCHAR(20)    NOT NULL DEFAULT 'PRE',
    ADD COLUMN labor_per_kg     NUMERIC(12, 4) NULL COMMENT '人工成本 元/kg成品 — R&D经验值填写, 非BOM来源',
    ADD COLUMN bom_material_cost NUMERIC(15, 2) NULL COMMENT '自动从BomRecipe.totalCost带入, 展示用',
    ADD COLUMN mid_quote_id     VARCHAR(191)   NULL     COMMENT 'FK → product_mid_quotes.id, 中报价关联';

COMMENT ON COLUMN quotation_tasks.quote_stage IS 'PRE=预报价 | MID_PENDING=等待中试 | MID=中报价已汇算 | FINAL=最终确认';
COMMENT ON COLUMN quotation_tasks.labor_per_kg IS '研发人员经验估算. 单位: 元/kg成品. 与BOM独立 — BOM不含人工(G章 line295-299确认)';
```

**注意**: `laborCost`(已有字段, 总金额) vs `labor_per_kg`(新字段, 单价口径). 前者 = `labor_per_kg × 预估产量kg`. 两者都保留: 旧字段向后兼容; 新字段是G章需求的"元/kg"表达.

### 4.2 ProductionBatch 扩字段 (迁移 V20260911_21)

```sql
-- V20260911_21__sp10_production_batch_trial_flag.sql
ALTER TABLE production_batches
    ADD COLUMN is_trial         BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN trial_sample_id  VARCHAR(191)   NULL COMMENT 'FK → product_samples.id, 试制批次关联样品';

CREATE INDEX idx_pb_trial_sample ON production_batches(factory_id, is_trial, trial_sample_id)
    WHERE is_trial = TRUE;
```

### 4.3 新实体 ProductMidQuote (迁移 V20260911_22)

```sql
-- V20260911_22__sp10_product_mid_quote.sql
CREATE TABLE product_mid_quotes (
    id                  VARCHAR(191)   NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(191)   NOT NULL,
    sample_id           VARCHAR(191)   NOT NULL COMMENT 'FK → product_samples.id',
    quotation_task_id   VARCHAR(191)   NULL     COMMENT 'FK → quotation_tasks.id',
    trial_batch_id      VARCHAR(191)   NOT NULL COMMENT 'FK → production_batches.id (is_trial=true)',

    -- 中试实际数据
    trial_quantity_kg   NUMERIC(12, 4) NOT NULL COMMENT '试制实际投入量 kg',
    trial_output_kg     NUMERIC(12, 4) NULL     COMMENT '试制实际产出量 kg',
    actual_yield_rate   NUMERIC(6, 4)  NULL     COMMENT '实际出成率 (0.0~1.5)',

    -- 成本汇算 (来自移动均价引擎 + 报工人工, SP3依赖)
    material_cost_per_kg NUMERIC(12, 4) NULL    COMMENT '材料成本 元/kg成品 (移动均价自动汇算)',
    labor_cost_per_kg    NUMERIC(12, 4) NULL    COMMENT '人工成本 元/kg成品 (报工段 人数×工时×工价 / 产出kg)',
    overhead_cost_per_kg NUMERIC(12, 4) NULL    COMMENT '制造费用 元/kg成品 (按SP3配置摊)',
    total_cost_per_kg    NUMERIC(12, 4) NULL    COMMENT '中试成本价 元/kg成品 (三项合计)',

    -- 与预报价对比
    pre_quote_labor_per_kg   NUMERIC(12, 4) NULL COMMENT '预报价时的 labor_per_kg 快照',
    pre_quote_total_per_kg   NUMERIC(12, 4) NULL COMMENT '预报价时的 total 快照',
    cost_variance_pct        NUMERIC(6, 2)  NULL COMMENT '超支百分比=(中试成本-预报价)/预报价×100',
    variance_alert           BOOLEAN        NOT NULL DEFAULT FALSE COMMENT '是否触发超支报警',

    -- 状态
    status          VARCHAR(32)    NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|CALCULATED|CONFIRMED',
    calculated_at   TIMESTAMP      NULL,
    confirmed_by    BIGINT         NULL,
    confirmed_at    TIMESTAMP      NULL,
    remark          TEXT           NULL,

    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP      NULL,

    CONSTRAINT uq_mid_quote_batch UNIQUE (factory_id, trial_batch_id)
);

CREATE INDEX idx_pmq_factory_sample ON product_mid_quotes(factory_id, sample_id);
CREATE INDEX idx_pmq_quotation_task ON product_mid_quotes(quotation_task_id);
```

**Java Entity**: `ProductMidQuote extends BaseEntity` (com.cretas.aims.entity.rd).
全成本字段加 `@PriceSensitive`.

### 4.4 成本差异配置扩展 (依 SP3, 仅 SP3 没有则在此建)

SP3 负责建 `cost_variance_threshold_pct` (全局/产品级可配). SP10 只读. 若 SP3 未 merge, SP10 在 `V20260911_23` 中 fallback 建:

```sql
-- V20260911_23__sp10_cost_variance_config_fallback.sql
-- 仅当 SP3 未先 merge 时执行; SP3 已建则跳过(幂等: IF NOT EXISTS)
CREATE TABLE IF NOT EXISTS cost_variance_configs (
    id          VARCHAR(191) NOT NULL PRIMARY KEY,
    factory_id  VARCHAR(191) NOT NULL,
    product_type_id VARCHAR(191) NULL COMMENT 'NULL=全局默认',
    threshold_pct   NUMERIC(5,2) NOT NULL DEFAULT 10.00 COMMENT '超支百分比阈值 默认10%',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cvc_factory_product UNIQUE (factory_id, product_type_id)
);
```

---

## 5. 组件与数据流

### 5.1 预报价流 (已有 + 小补)

```
ProductSample APPROVED
    → SampleApprovedEventListener.autoCreateBomDraft()
        ├── 创建 BomRecipe DRAFT (已有)
        └── 创建 QuotationTask(status=PENDING, quote_stage=PRE) [新加 quote_stage]
            ↓
报价人员打开 QuotationTask 表单 (web)
    → 系统自动带入: bom_material_cost = BomRecipe.totalCost (若BOM已完成)
    → 报价人员填写: labor_per_kg (元/kg成品, 经验值)
    → 前端计算展示: totalCostPerKg = bom_material_cost/产量kg + labor_per_kg + overhead
    → 提交 PUT /api/mobile/{factoryId}/rd/quotations/{taskId}
        → QuotationTask: status=QUOTED, quote_stage=PRE, 记 labor_per_kg
```

### 5.2 中报价流 (新建)

```
生产管理员新建生产批次时选"试制" (web/RN)
    → ProductionBatch: is_trial=true, trial_sample_id=X
        ↓
试制批次报工 (复用现有 YieldReportServiceImpl, 无需改动)
    → 报工产生 SemiFinishedInventoryTransaction (移动均价, SP3提供)
    → 报工段记录 人数×工时×工价
        ↓
试制完成后 → 触发中报价汇算
    POST /api/mobile/{factoryId}/rd/mid-quotes/calculate
    Body: { trialBatchId, quotationTaskId }
        ↓
    ProductMidQuoteService.calculate():
        1. 取 is_trial=true 批次的 SemiFinishedInventoryTxn (SP3)
           → material_cost_per_kg = Σ(原料移动均价 × 用量) / 产出kg
        2. 取 ProductionReport.laborSegments (SP1/F006)
           → labor_cost_per_kg = Σ(人数×工时×工价) / 产出kg
        3. overhead_cost_per_kg = SP3 OverheadConfig 摊销
        4. total_cost_per_kg = 三项合计
        5. cost_variance_pct = (total - pre_quote_total) / pre_quote_total × 100
        6. variance_alert = |cost_variance_pct| > threshold_pct
        → 保存 ProductMidQuote(status=CALCULATED)
        → QuotationTask.quote_stage → MID, mid_quote_id → 关联
```

### 5.3 三价对比流 (新端点)

```
GET /api/mobile/{factoryId}/rd/quotations/{sampleId}/three-price-comparison
    ↓
ThreePriceComparisonDTO:
    priceQuote:   QuotationTask.totalCost / 产量 (预报价)
    midQuote:     ProductMidQuote.totalCostPerKg (中报价, 可null)
    actualCost:   SalesServiceImpl.getFinanceCostBreakdown(sampleId) (实际, SP3)
    varianceAlerts: [
        { stage: PRE_VS_MID, pct, alert },
        { stage: MID_VS_ACTUAL, pct, alert }
    ]
    thresholdPct: CostVarianceConfig.threshold_pct
```

---

## 6. 端点归属

### Backend (Java, 端口 10010, 路径 /api/mobile/{factoryId}/)

| 端点 | 方法 | 说明 | 状态 |
|---|---|---|---|
| `/rd/quotations` | GET | 列表 (支持 stage/status 过滤) | 已有,扩过滤 |
| `/rd/quotations/{taskId}` | GET | 详情 (含 bom_material_cost + labor_per_kg) | 已有,扩字段 |
| `/rd/quotations/{taskId}` | PUT | 提交预报价 (含 labor_per_kg) | 已有,扩字段 |
| `/rd/mid-quotes/calculate` | POST | 触发中报价汇算 | 🆕 |
| `/rd/mid-quotes/{midQuoteId}` | GET | 中报价详情 | 🆕 |
| `/rd/mid-quotes/{midQuoteId}/confirm` | PUT | 确认中报价 | 🆕 |
| `/rd/quotations/{sampleId}/three-price-comparison` | GET | 三价对比(全角色,@PriceSensitive脱敏) | 🆕 |
| `/production/batches` | POST | 新建批次(扩 is_trial + trial_sample_id 字段) | 已有,扩 |
| `/production/batches?isTrial=true` | GET | 筛选试制批次 | 已有,扩过滤 |

**权限**:
- 三价对比: 销售角色只见 `totalCostPerKg`(脱敏, 不见明细); 财务/研发/老板见全字段
- 中报价汇算: 需 `ROLE_PRODUCT_MANAGER` 或 `ROLE_RD_STAFF`
- 中报价确认: 需 `ROLE_FACTORY_ADMIN` 或 `ROLE_FINANCE`

### Web Admin (Vue, 路径 web-admin/src/views/rd/)

| 页面/组件 | 位置 | 说明 | 状态 |
|---|---|---|---|
| 报价任务列表 | `views/rd/quotations/list.vue` | 扩 stage badge + labor_per_kg 列 | 已有,扩 |
| 报价任务详情/表单 | `views/rd/quotations/detail.vue` | 新增 labor_per_kg 输入 + BOM成本自动带入提示 | 已有,扩 |
| 试制批次标记 | `views/production/batches/create.vue` | 新增"此为试制批次"checkbox + 关联样品下拉 | 已有,扩 |
| 中报价汇算卡 | `views/rd/mid-quotes/detail.vue` | 试制数据汇算 + 与预报价对比 + 超支预警 | 🆕 |
| 三价对比看板 | `views/rd/quotations/three-price.vue` | 三列并排 + 超支百分比 badge | 🆕 |

### React Native (不适用本子项)

SP10 的操作(预报价填写/中报价汇算/三价看板)均为管理后台功能, 报价人员用 web. 仅试制批次创建时 RN 端的"新建批次"表单扩 `is_trial` 开关(轻量, 复用现有 ProductionBatchScreen).

---

## 7. 错误处理 — fool-proof 4位一体

遵循 `.claude/rules/fool-proof-design.md`.

### 7.1 预报价提交 dialog

- **Rule 1 (预先显示边界)**: dialog 打开即显"本产品 BOM 材料成本: ¥X.XX/kg (基于当前BOM, 最后更新: Y日期)"; 若BOM未完成则诚实显"BOM尚未完成, 材料成本由您手填"
- **Rule 2 (上下文)**: dialog 标题 "提交预报价 — {样品名} {规格} ({样品编号})"
- **Rule 3 (下拉约束)**: 若有特殊说明选类型(成本偏高原因: 原料价格波动/季节性供应/新工艺学习期/其他)
- **Rule 4 (幂等)**: PUT /rd/quotations/{taskId} 同 taskId + stage=PRE 已 QUOTED → 409 + "已提交预报价, 是否查看?" + 跳转按钮
- **Rule 5 (dead-end)**: BOM未完成 → 提示"BOM尚未完成 → 前往BOM配置" + 一键跳转 /bom/{productTypeId}

### 7.2 中报价汇算 — 数据不足警告 (sticky toast)

- 试制批次报工不完整(无人工段) → backend 返 `{ success: true, data: {...}, warnings: ["人工数据缺失, labor_cost_per_kg=null, 请补充报工"] }` → 前端 sticky warning
- 试制批次未完成(PENDING/IN_PROGRESS) → 409 "试制批次尚未完成, 请先完成报工" + actionHint: `/production/batches/{id}`
- BOM材料成本 null (缺单价) → 中报价 material_cost_per_kg=null, 前端显"部分成本缺失, 仅供参考"(诚实 null, 不显0)
- 超支报警: **sticky warning(duration:0+showClose)** 不卡死 — `variance_alert=true` → "⚠️ 中试成本超出预报价 {X}%, 超出设定阈值 {threshold}%"; 仍可确认

### 7.3 三价对比 — null 诚实

- 中报价未汇算: `midQuote: null` → 前端显"等待中试" badge, 不显 ¥0
- 实际成本待SP3计算: `actualCost: null` → 前端显"等待出库结算"
- 超支: `varianceAlert=true` → 红色 badge + 具体 % + 可配阈值提示

---

## 8. 测试策略

### 8.1 Backend TDD (JUnit 5 + MockMvc)

| 测试单元 | 覆盖点 |
|---|---|
| `QuotationTaskServiceTest` | labor_per_kg 保存/读取; bom_material_cost 自动带入(BOM已完成/未完成两路); quote_stage 推进 |
| `ProductMidQuoteServiceTest` | calculate(): 材料成本汇算(有数据/无数据两路); labor汇算; overhead摊销; variance_pct计算; variance_alert触发; 幂等(已汇算→返已有) |
| `ThreePriceComparisonServiceTest` | 三价null组合(仅预/预+中/三价全); 超支百分比; @PriceSensitive脱敏(销售角色不见materialCostPerKg) |
| `ProductionBatchServiceTest` | is_trial=true 创建/查询; trial_sample_id FK约束 |
| `RdControllerTest` (MockMvc) | POST /mid-quotes/calculate: 401/403/409/200; GET /three-price-comparison: 脱敏对比 |

**验收命令**: `./mvnw test -pl backend/java/cretas-api -Dtest="*MidQuote*,*ThreePrice*,*QuotationTask*" -Dsurefire.failIfNoSpecifiedTests=false`

### 8.2 Web Admin

- `vue-tsc --noEmit` (全类型检查)
- `vite build` (无报错)
- E2E (headed, zh-CN): 预报价表单填写 + BOM自动带入 + 中报价汇算触发 + 三价对比看板渲染

### 8.3 RN

- `npx tsc --noEmit` (新增的 is_trial 字段类型)
- 手动: 新建批次 → 勾选"试制批次" → 确认关联样品下拉

---

## 9. 依赖与约束

### 9.1 SP3 依赖 (硬依赖, 必须先 merge)

- `SalesServiceImpl.getFinanceCostBreakdown` — 三价对比实际成本来源
- `cost_variance_threshold_pct` 配置表 — 超支阈值读取
- `SemiFinishedInventoryTransaction` + 移动均价引擎 — 中报价材料成本汇算
- **若 SP3 未 merge**: SP10 在 `V20260911_23` fallback 建轻量配置表; 实际成本在三价对比中返 null + 提示"等待成本引擎上线(SP3)"

### 9.2 SP4 依赖 (建议先 merge, 非强制)

- `ProductType.tax_rate` — 报价含税/未税换算
- 批次标签 — 试制批次扫码场景(可延迟, 不阻塞核心报价功能)

### 9.3 scope-lock (本子项独占文件)

| 文件/目录 | 锁定说明 |
|---|---|
| `entity/rd/ProductMidQuote.java` (🆕) | SP10 独占 |
| `service/rd/ProductMidQuoteService.java` (🆕) | SP10 独占 |
| `service/rd/impl/ProductMidQuoteServiceImpl.java` (🆕) | SP10 独占 |
| `controller/rd/RdController.java` | SP10 扩; 不与其他SP冲突 |
| `V20260911_20` ~ `V20260911_23` migration 文件 | SP10 独占号段 |
| `web-admin/src/views/rd/mid-quotes/` (🆕) | SP10 独占 |
| `web-admin/src/views/rd/quotations/three-price.vue` (🆕) | SP10 独占 |

**不改 (scope 禁区)**:

| 文件 | 原因 |
|---|---|
| `YieldReportServiceImpl` | SP1/SP2/SP9 锁 |
| `SemiFinishedInventory` / `SemiFinishedInventoryTxn` | SP1/SP2/SP3 锁 |
| `MaterialBatch` | SP4/SP6/SP7 锁 |
| `BomRecipeItem` | SP4/SP8 锁 |
| `SalesOrder*` | SP5 锁 |
| `CostRollupUtil` (只读引用OK) | SP3 写; SP10 只调用不改 |

---

## ⚠️ 10. 跨子项依赖/风险

### 风险1 — SP3 成本引擎 (高)
**中报价汇算**依赖 `SemiFinishedInventoryTxn` 移动均价(SP3建). SP3 未 merge 时 SP10 中报价无法汇算材料成本. 缓解: SP10 提前建表/Entity, 汇算逻辑在 SP3 merge 后补; V20260911_23 fallback 轻量配置.

### 风险2 — 人工口径分歧 (中)
蓝图 SP9 建 `LaborCostConfig`(人工双口径); SP10 中报价汇算需从报工段读 人数×工时×工价. 若 SP9 未 merge 且 WorkProcess.standardHourlyRate 未配置, 则 `labor_cost_per_kg=null` (诚实空, 不阻塞汇算, 只是该字段缺). 实施时确认 F006 报工链路(已 ship)的 laborSegments 字段结构稳定.

### 风险3 — QuotationTask DTO 往返丢失 (中)
`labor_per_kg` / `bom_material_cost` / `quote_stage` / `mid_quote_id` 四个新字段必须全 4 处同步(Entity + create set + update null-guard set + convertToDTO map). 遵循 `feedback_dto_roundtrip_silent_drop` 规则; 漏任一处→静默丢失.

### 风险4 — Flyway 撞号 (低, 有流程)
号段 V20260911_2x 已预分配给 SP10, 其他 SP 不使用. 但 merge 前必执行查重: `git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`. V20260911_23 是 fallback 迁移(幂等), 若 SP3 已建同表则两者冲突 — 实施时视 SP3 merge 状态决定是否跳过.

### 风险5 — @PriceSensitive 覆盖遗漏 (低)
`ProductMidQuote` 所有成本字段必须 `@PriceSensitive`. 遗漏→成本泄露给销售角色. 终审 Opus 需 grep 验证: `grep -n "@PriceSensitive" .../ProductMidQuote.java | wc -l` 应 ≥ 5.
