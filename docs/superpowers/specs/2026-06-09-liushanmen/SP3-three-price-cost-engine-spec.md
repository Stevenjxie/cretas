# SP3 · 三价成本引擎 — 设计规格

**子项**: SP3 · 三价成本引擎（移动均价 + 标准价 + 超支百分比报警）
**归档**: `docs/superpowers/specs/2026-06-09-liushanmen/`
**Flyway 号段**: `V20260910_20` – `V20260910_29`（已占用 01-03，2x 段安全）
**依赖波次**: Wave 2（必须在 SP1 完成并合 main 后开始）
**最后更新**: 2026-06-09 Opus organizer

---

## 1. 目标

| 目标 | 说明 |
|------|------|
| 三价并列显示 | 任意销售订单行 → 并排显示：标准BOM成本、实际均价成本、销售价 |
| 移动均价引擎 | 每次半成品/成品产出后，按 IN 加权公式更新 `unitCost`；OUT 不改单价 |
| 超支百分比报警 | 实际成本 vs 标准成本偏差 > 阈值（默认 10%）→ 报警 flag + 提示文案 |
| 人工双口径对齐 | LaborCostConfig 补 `laborCostPerKg` 字段（研发估算口径）；实际口径仍走 workerCount × workMinutes × standardHourlyRate |
| 毛利红线联动 | 下单时若 `售价 < 成本 × (1 + 目标毛利率)` 则 web 红色预警；不卡死流程 |

**本子项不做（明确 out-of-scope）**：
- 建议销售价字段（决策 2 明确拒绝）
- 财务对接 API（决策 4，P2 偏后）
- 16 位编码分段（决策 3，P1）
- 能源/水电/气 成本分摊（客户确认不计入成本口径）
- SemiFinishedInventoryTransaction 表的创建（SP1 负责，SP3 只读）

---

## 2. 范围与端归属

| 功能 | Backend | Web-Admin | RN | 备注 |
|------|---------|-----------|----|----|
| 移动均价引擎（IN/OUT 计算） | ✅ 核心 | — | — | service 层，无 API 新增 |
| 超支阈值配置 CRUD | ✅ | ✅ 配置页 | — | `ProductCostVarianceConfig` 新表 |
| getOrderCostBreakdown 补三价+报警 | ✅ | — | — | 现有端点扩展 |
| 财审/销售订单 三价对比视图 | — | ✅ | — | FinanceCostBreakdown DTO 扩展字段 |
| 毛利红线预警（下单时） | ✅ 算最低价 | ✅ 实时预警 | — | Decision 2 |
| LaborCostConfig 补 laborCostPerKg | ✅ 字段 | ✅ 编辑表单 | — | 研发估算口径 |
| 人工双口径对比视图 | — | ✅ | — | 读现有数据不新增 API |

---

## 3. 现状复用（grep 验证）

### 3.1 可直接复用（不改接口）

| 代码 | 位置（grep 确认） | 复用方式 |
|------|-----------------|---------|
| `CostRollupUtil` | `service/shared/CostRollupUtil.java` | 所有成本算术（`calcItemCost`/`sumItemCosts`）必须调用此 util；scale-6 qty / scale-4 cost / HALF_UP |
| `BomRecipe.totalCost` | `entity/bom/BomRecipe.java` @PriceSensitive | 直接读作标准BOM成本，无需新增字段 |
| `SalesOrderItem.costUnitPrice` | `entity/sales/SalesOrderItem.java`（blueprint 确认存在） | 移动均价引擎写入此字段（回填） |
| `SemiFinishedInventory.unitCost` | `entity/SemiFinishedInventory.java` scale-4 | 移动均价 IN/OUT 的读写目标 |
| `@PriceSensitive` | `security/PriceSensitive.java` | 所有成本字段标注；`PriceFieldResponseAdvice` 自动剥除 |
| `FinanceCostBreakdown` DTO | `dto/inventory/FinanceCostBreakdown.java` | 扩展字段，无需新建 DTO |
| `getOrderCostBreakdown()` | `SalesServiceImpl.java:677` | 在此方法末段加入三价汇总 + 超支计算 |
| `LaborCostConfig` | `entity/bom/LaborCostConfig.java` | 补 `laborCostPerKg` 字段（迁移 V20260910_21） |

### 3.2 SP1 产出（Wave 2 前置条件）

| 代码 | 创建者 | SP3 用途 |
|------|--------|---------|
| `SemiFinishedInventoryTransaction` | SP1 | SP3 读取流水账核算混批加权成本 |
| `SemiFinishedInventoryService.consumeWip()` | SP1 | SP3 触发 OUT 操作时调用 |

---

## 4. 数据模型增量

### 4.1 新建表：`product_cost_variance_configs`

**目的**：存放超支百分比阈值（Decision 5 — 百分比可配，默认 10%）。

```sql
-- V20260910_20__product_cost_variance_config.sql
CREATE TABLE product_cost_variance_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    factory_id      VARCHAR(100) NOT NULL,
    product_type_id VARCHAR(100),                        -- NULL = 工厂全局默认
    threshold_pct   DECIMAL(5,2) NOT NULL DEFAULT 10.00, -- 默认 10%，可配
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT uq_variance_config UNIQUE (factory_id, product_type_id)
);
CREATE INDEX idx_variance_factory ON product_cost_variance_configs(factory_id);

-- trigger updated_at
CREATE TRIGGER trigger_variance_config_updated_at
BEFORE UPDATE ON product_cost_variance_configs
FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

**JPA Entity**: `ProductCostVarianceConfig`
- 继承 `BaseEntity`（需确保表含 created_at/updated_at/deleted_at，迁移已包含）
- `productTypeId` nullable（NULL = 工厂全局）
- `thresholdPct` BigDecimal scale-2，@PriceSensitive 不标注（阈值非价格敏感）

**查询优先级**：先按 `(factoryId, productTypeId)` 精确查；未命中则查 `(factoryId, NULL)`；仍未命中 → 使用系统默认 10%。

### 4.2 修改表：`bom_labor_cost_configs`（V20260910_21）

```sql
-- V20260910_21__labor_cost_per_kg_field.sql
ALTER TABLE bom_labor_cost_configs
    ADD COLUMN labor_cost_per_kg DECIMAL(15, 4);   -- 研发估算口径：元/kg 成品，nullable
COMMENT ON COLUMN bom_labor_cost_configs.labor_cost_per_kg
    IS '研发估算人工成本（元/kg成品）；实际成本口径仍用 workerCount×workMinutes×standardHourlyRate';
```

**JPA Entity `LaborCostConfig`**: 加字段
```java
@PriceSensitive
@Column(name = "labor_cost_per_kg", precision = 15, scale = 4)
private BigDecimal laborCostPerKg;  // nullable — 研发估算，未填不影响实际成本
```

**DTO roundtrip 4 处必做**：
1. `LaborCostConfigDTO` 声明 `laborCostPerKg`
2. create service 中 `set(dto.getLaborCostPerKg())`
3. update service 中 null-guard: `if (dto.getLaborCostPerKg() != null) entity.setLaborCostPerKg(...)`
4. `convertToDTO()` 中映射此字段

### 4.3 DTO 扩展：`FinanceCostBreakdown`（无迁移）

在现有 DTO 末段追加字段（@PriceSensitive 标注，保持与 `getOrderCostBreakdown()` 一致）：

```java
// 三价对比 + 超支报警
@PriceSensitive
private BigDecimal varianceAbsolute;       // 实际成本 - 标准成本（可负）
@PriceSensitive
private BigDecimal variancePct;            // (实际 - 标准) / 标准 × 100，scale-4
private Boolean    belowThreshold;         // true = 未超支；false = 超支；null = 数据不足无法判断
private String     alarmMessage;           // 超支时填人读文案，否则 null
```

`LineCostBreakdown` 同步追加（行级）：
```java
@PriceSensitive
private BigDecimal standardCostPerUnit;    // BomRecipe.totalCost / outputQuantityPerUnit（单位成本）
@PriceSensitive
private BigDecimal actualCostPerUnit;      // costUnitPrice（移动均价回填）
@PriceSensitive
private BigDecimal variancePct;
private Boolean    belowThreshold;
```

---

## 5. 移动均价引擎设计

### 5.1 IN（产出入库）— 加权公式

```
newUnitCost = (oldAvailableQty × oldUnitCost + inQty × inCost)
              / (oldAvailableQty + inQty)
              → scale-4 HALF_UP（使用 CostRollupUtil.COST_SCALE）
```

**inCost 计算**（本道工序实际总成本 / 本道产出量）：
- 材料成本：`CostRollupUtil.sumItemCosts(materialItems)` —— 任一材料无价 → inCost = null
- 人工成本：`workerCount × workMinutes/60 × standardHourlyRate` → 求和 over all labor segments
- 如果 `standardHourlyRate` 未配置（null）→ 人工成本 = null → inCost = null（诚实 null，不填假 0）
- 产出量 = 0 → 跳过本次 IN（防除零）

**并发控制**：`SELECT ... FOR UPDATE` 悲观行锁锁定 `SemiFinishedInventory` 行，同一批次同一工序序号只允许单线程写入。

**幂等键**：`(intermediateBatchNo, sourceWorkProcessTaskId)` — 重复提交返回已有 unitCost，不重算。

### 5.2 OUT（出库/领用）

- `unitCost` 不变（价格随批次"跟走"）
- `availableQuantity -= consumedQty`（scale-6 HALF_UP）
- `consumedQuantity += consumedQty`
- 若 `availableQuantity ≤ 0` → status = `DEPLETED`

### 5.3 实际成本回填 `SalesOrderItem.costUnitPrice`

触发时机：`completeProduction()` 建 `FinishedGoodsBatch` 后异步（`@Async`）执行：
1. 按 `productTypeId` + `factoryId` 找已建 FG 批次
2. 取 FG 建立时的 `SemiFinishedInventory.unitCost`（成品层移动均价）
3. 回填关联 `SalesOrderItem.costUnitPrice`（仅当 costUnitPrice 为 null 或明确需刷新时）
4. 触发 `OrderCostAlarmEvent`（Spring 事件）→ 超支检查异步执行

### 5.4 混批加权（同产品跨批次）

当同一销售订单行由多个批次混合交货时：
```
weightedActualCost = Σ(batchI.costUnitPrice × batchI.qty) / Σ(batchI.qty)
```
在 `getOrderCostBreakdown()` 行级汇总中完成（读 SalesOrderItem + FinishedGoodsBatch 关联）。

---

## 6. 超支报警逻辑

### 6.1 阈值查找（`CostVarianceService.resolveThreshold(factoryId, productTypeId)`）

```java
// 优先级: 产品级 → 工厂全局 → 系统默认 10%
Optional<ProductCostVarianceConfig> cfg = repository
    .findByFactoryIdAndProductTypeIdAndIsActiveTrue(factoryId, productTypeId);
if (cfg.isEmpty()) {
    cfg = repository.findByFactoryIdAndProductTypeIdIsNullAndIsActiveTrue(factoryId);
}
return cfg.map(c -> c.getThresholdPct())
          .orElse(new BigDecimal("10.00"));
```

### 6.2 报警判断（`getOrderCostBreakdown()` 末段）

```java
// variancePct = (actualCost - standardCost) / standardCost * 100
// 使用 CostRollupUtil 算术; standardCost=null 或 actualCost=null → belowThreshold=null
if (standardCost == null || actualCost == null) {
    breakdown.setBelowThreshold(null);
    breakdown.setAlarmMessage("成本数据不完整，无法判断超支");
} else {
    BigDecimal variancePct = actualCost.subtract(standardCost)
        .divide(standardCost, CostRollupUtil.COST_SCALE, HALF_UP)
        .multiply(BigDecimal.valueOf(100));
    breakdown.setVariancePct(variancePct);
    breakdown.setVarianceAbsolute(actualCost.subtract(standardCost).setScale(COST_SCALE, HALF_UP));
    BigDecimal threshold = costVarianceService.resolveThreshold(factoryId, productTypeId);
    boolean exceeded = variancePct.compareTo(threshold) > 0;
    breakdown.setBelowThreshold(!exceeded);
    if (exceeded) {
        breakdown.setAlarmMessage(String.format(
            "实际成本超出标准成本 %.2f%%（阈值 %.2f%%），请核查工序损耗",
            variancePct, threshold));
    }
}
```

### 6.3 毛利红线预警（下单时）

**后端** `SalesOrderService.validatePriceRedline(unitPrice, factoryId, productTypeId)`：
```
minPrice = actualCostPerUnit × (1 + targetGrossMarginRate)
if unitPrice < minPrice → throw PriceRedlineViolationException(
    message = "售价 ¥{unitPrice} 低于成本红线 ¥{minPrice}（目标毛利率 {rate}%）",
    actionHint = "请调整售价或联系管理员修改目标毛利率"
)
```
目标毛利率从 `LaborCostConfig`（全局口径）或工厂参数表读取，未配置时默认 10%。

**前端** web-admin 下单页面：销售价 input `blur` 事件触发实时校验 API，低于红线显示红色 warning badge（不 disable 提交按钮，依客户要求不卡死）。

---

## 7. 组件与数据流

```
[ProductionReport 提交]
         │
         ▼
 submitYieldReport()
  ├─ 计算 inCost（CostRollupUtil.sumItemCosts + 人工）
  ├─ SemiFinishedInventoryService.updateMovingAvgCost()   ← SP3 新增方法
  │     └─ SELECT FOR UPDATE → 加权公式 → save unitCost
  └─ 发布 ProductionCostUpdatedEvent
         │
         ▼
 @Async OrderCostBackfillListener
  ├─ 找关联 SalesOrderItem
  ├─ 回填 costUnitPrice
  └─ 发布 OrderCostAlarmEvent
         │
         ▼
 @Async OrderCostAlarmListener
  ├─ getOrderCostBreakdown() 重算三价
  ├─ resolveThreshold()
  ├─ 若超支 → 写 alarm_record（可选，backlog）
  └─ 若超支 → 推送通知（可选，backlog Wave 3）

[getOrderCostBreakdown() API 调用]
  ├─ 读 BomRecipe.totalCost （标准价）
  ├─ 读 SalesOrderItem.unitPrice（销售价）
  ├─ 读 SalesOrderItem.costUnitPrice（实际均价）
  ├─ 混批加权（多批次情况）
  ├─ 三价差计算 + 超支判断
  └─ 返回扩展 FinanceCostBreakdown（@PriceSensitive 守卫）
```

---

## 8. 错误处理（Fool-Proof 4 位一体）

所有 SP3 write 操作必须同时满足：

| 位 | 规则 | SP3 实施 |
|----|------|---------|
| a | 后端 response.message 具体 | "实际成本超出标准成本 XX.XX%（阈值 10.00%），请核查工序损耗" |
| b | 前端 toast 文案 = 后端 message | 直接渲 `e.response.data.message`，不改写 |
| c | toast sticky（duration:0 + showClose） | `ElMessage({ type:'warning', duration:0, showClose:true })` 用于超支警告 |
| d | 含 next action 提示 | alarmMessage 含"请核查工序损耗"；毛利红线含"请调整售价或联系管理员" |

**防重提交**（Rule 4）：`completeProduction` 幂等键 `(batchId, taskId)` — 重复请求返回已有成本数据，不重算；HTTP 409 响应含 `existingCostUnitPrice`。

**预先显示限制**（Rule 1）：超支报警 badge 在 FinanceCostBreakdown 加载时立即渲染（不等用户点击），让财务和销售人员打开页面即见警告状态。

---

## 9. 测试策略

### 9.1 单元测试（先写）

| 测试类 | 覆盖场景 |
|--------|---------|
| `MovingAvgCostEngineTest` | IN 加权公式（有旧库存/零库存首次入/产出量=0不更新）; OUT 单价不变; 并发（2 线程同时 IN 同一行，加锁后结果精确）; inCost 含 null 材料 → unitCost = null（诚实 null）|
| `CostVarianceServiceTest` | 阈值查找优先级（产品级>工厂全局>系统默认10%）; null actualCost → belowThreshold=null; variancePct 精度（HALF_UP，scale-4）; 超支/未超支 message 内容 |
| `SalesOrderRedlineTest` | unitPrice < minPrice → 抛 PriceRedlineViolationException 含 actionHint; unitPrice = minPrice → 通过（边界）; actualCost=null → 跳过红线校验（不假 0） |
| `LaborCostConfigDtoRoundtripTest` | laborCostPerKg 字段四处（声明/create set/update null-guard/convertToDTO map）全经过 |
| `ProductCostVarianceConfigRepositoryTest` | UNIQUE(factoryId, productTypeId) 约束; NULL productTypeId 为全局默认 |

### 9.2 集成测试

| 测试 | 验证 |
|------|------|
| `MovingAvgCostIT` | 完整链：submitYieldReport → unitCost 更新 → costUnitPrice 回填 → breakdown 含三价 |
| `VarianceAlarmIT` | 实际成本高于标准 11% → belowThreshold=false + alarmMessage 非空 |
| `PriceSensitiveIT` | sales 角色调 getOrderCostBreakdown → 所有 @PriceSensitive 字段 = null |

### 9.3 回归保护

- SP1 的 `SemiFinishedInventoryTest` 不得因 SP3 破坏（unitCost 字段已存在，SP3 只新增服务层逻辑）
- `CostRollupUtilTest` 已有 → SP3 不修改 util，只调用

---

## 10. 依赖

| 依赖 | 说明 | 阻塞 |
|------|------|------|
| **SP1（半成品 WIP 流水账）** | `SemiFinishedInventoryTransaction` 表由 SP1 创建；`SemiFinishedInventory.unitCost` 写权限由 SP1 服务管理；SP3 只在 SP1 merge 后开始 | **硬阻塞**，Wave 2 |
| `CostRollupUtil` | 已存在，直接复用 | 无 |
| `@PriceSensitive` + `PriceFieldResponseAdvice` | 已存在 | 无 |
| `BomRecipe.totalCost` | 已存在 | 无 |
| `LaborCostConfig` | 已存在，SP3 补字段（V20260910_21） | 无 |

---

## 11. 🔒 红线设计章（照蓝图 §3 逐字落地）

> **执行者只到 PR，由 Opus organizer 终审 + 从 main 部署 prod。**

### 11.1 🔒 成本口径（蓝图 §3.1）

- **成本口径 = 材料成本 + 人工成本 + 包材/辅料**（能源/水电/气不计入，客户确认）
- **税前口径**：所有成本计算使用含税价除以 (1 + 税率) 得到不含税价；税率 9%/13% 由采购记录字段决定；`CostRollupUtil.calcItemCost()` 调用前必须已换算为不含税单价
- 代码中任何对 `unitPrice` 直接使用的位置须验证"已去税"，否则加 `pretaxUnitPrice = unitPrice.divide(BigDecimal.ONE.add(taxRate), COST_SCALE, HALF_UP)` 步骤

### 11.2 🔒 移动均价引擎精度（蓝图 §3.2）

- **数量**: scale-6 HALF_UP（`CostRollupUtil.QTY_SCALE`）
- **成本**: scale-4 HALF_UP（`CostRollupUtil.COST_SCALE`）
- **任一投入无价格** → `inCost = null` → 本次 `unitCost` 更新**跳过**（不污染均价）；`SalesOrderItem.costUnitPrice` 保持原值或 null；`belowThreshold = null`（诚实不确定，非 false）
- 并发锁：`SELECT ... FOR UPDATE`；乐观锁 `@Version` 兜底冲突重试最多 3 次

### 11.3 🔒 成本字段 RBAC（蓝图 §3.3）

- `getOrderCostBreakdown()` 响应的 **所有成本字段必须** `@PriceSensitive` 标注
- 拥有 `finance:read` 或 `procurement:price:view` 权限的角色才能看到明文值
- 销售角色只能看到 `belowThreshold`（bool）和 `alarmMessage`（文案），不能看到具体金额
- **不允许**在前端 JS 中拼接/计算成本——敏感字段必须后端剥除后才到前端
- 执行者不得修改 `PriceFieldResponseAdvice`；任何对 @PriceSensitive 的扩展必须回 main 由 Opus 终审

### 11.4 🔒 毛利红线（蓝图 §3.4）

- 红线 = `actualCostPerUnit × (1 + targetGrossMarginRate)`；仅在 `actualCostPerUnit != null` 时校验
- **不卡死流程**（客户明确："低于底线提示红色，不是不允许"）→ 后端抛 `PriceRedlineViolationException` 但 HTTP 200 + 警告字段（非 4xx），前端 badge 显示；不返回 400/422 阻止下单
- 目标毛利率配置缺失时默认 10%（安全兜底）

### 11.5 🔒 迁移安全（蓝图 §3.5）

- SP3 独占号段 `V20260910_20` – `V20260910_29`；merge 前必须 `git ls-tree origin/main db/flyway | grep V20260910` 查重
- 新表 `product_cost_variance_configs` 不与其他子项共享；修改 `bom_labor_cost_configs` 的 V20260910_21 须在 SP5（BOM 关联引用此字段）之前 apply
- 执行者仅创建迁移文件 + PR；Opus 终审确认无撞号 + 无 schema 意外副作用后 merge

---

## 12. ⚠️ 跨子项依赖 / 风险

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | **SP1 未完成** → SemiFinishedInventoryTransaction 不存在，SP3 无法读混批流水账 | 移动均价引擎无完整历史；只能用当期 unitCost | 严格 Wave 2 串行：SP3 worktree off origin/main 且 SP1 已合 main 后才开始 |
| R2 | **Flyway 撞号**（01-03 已占用，2x 段分配给 SP3；其他子项若也分到 2x 段会冲突） | `out-of-order=false` 下部署报 "more than one migration"，阻断所有人 | merge 前必查 `git ls-tree origin/main db/flyway \| grep V20260910`；重编号未 apply 的 |
| R3 | **@PriceSensitive 范围扩展**（SP3 新字段 variancePct/belowThreshold 需要正确标注）若遗漏 | 成本数据泄漏给 sales 角色 | Opus 终审 PR diff 时检查每个新成本字段；`PriceSensitiveIT` 专项覆盖 |
| R4 | **SP5 依赖 laborCostPerKg**（SP5 BOM 成本卡需读此字段做研发/实际对比）→ V20260910_21 必须在 SP5 之前 apply | SP5 若先 merge 但 V20260910_21 未 apply → SP5 代码读 null 字段 | SP5 brief 卡注明"V20260910_21 为前置条件"；Opus 部署顺序控制 |
| R5 | **CostRollupUtil 税前换算**：现有调用 sitemap 中 `BomRecipeItem.calculateActualQuantity()` 未做税前换算；SP3 新路径须显式去税 | 含税成本被错误计入（夸大约 9-13%），与会计口径偏差 | SP3 在调 `CostRollupUtil.calcItemCost()` 前统一插入 `pretaxUnitPrice` 换算；专项单测覆盖税前/含税分支 |
