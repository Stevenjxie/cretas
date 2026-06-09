# SP3 · 三价成本引擎 — 实施计划

**子项**: SP3 · 三价成本引擎（移动均价 + 标准价 + 超支百分比报警）
**Flyway 号段**: V20260910_20 / V20260910_21
**Wave**: 2（硬前置：SP1 合 main 后才开始）
**最后更新**: 2026-06-09 Opus organizer

---

## Flyway 号段查重纪律

**每次 merge 前必执行**（防撞车）：
```bash
git fetch origin
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
```
如有重复 → 立即重编未 apply 的。

已知占用（截至 2026-06-09）：V20260910_01 / 02 / 03  
SP3 使用：**V20260910_20**（新表）、**V20260910_21**（加字段）  
SP3 预留备用：V20260910_22 – V20260910_29（如迭代需补迁移）

---

## Worktree 隔离 + Scope Lock

```bash
# SP1 已合 main 后才执行此命令
git worktree add -b feat/sp3-three-price-cost ../cretas-sp3 origin/main
cd ../cretas-sp3
```

### 本子项独占文件（scope lock）

| 文件 | 操作 | 冲突风险 |
|------|------|---------|
| `db/flyway/V20260910_20__product_cost_variance_config.sql` | 新建 | 其他 SP 不碰 2x 号段 |
| `db/flyway/V20260910_21__labor_cost_per_kg_field.sql` | 新建 | SP5 读此字段，顺序 SP3→SP5 |
| `entity/bom/LaborCostConfig.java` | 加字段 | SP5 可能同时读，串行或分文件 |
| `service/inventory/impl/SalesServiceImpl.java:677` 区域 | 扩展 | SP6（结算）可能同文件，merge 时 diff 注意 |
| `dto/inventory/FinanceCostBreakdown.java` | 加字段 | SP6、SP9 可能读此 DTO — 串行 |
| `entity/bom/ProductCostVarianceConfig.java` | 新建 | 无冲突 |
| `service/CostVarianceService.java` | 新建 | 无冲突 |
| `service/inventory/impl/SemiFinishedInventoryService.java`（新增 `updateMovingAvgCost`）| 扩展 | SP1 已 ship 此文件；SP3 只在其末段加方法 |

### 与其他 SP 串行约束

| 约束 | 原因 |
|------|------|
| SP1 先于 SP3 | SemiFinishedInventoryTransaction 由 SP1 创建 |
| SP3 先于 SP5 | SP5 BOM 成本卡读 laborCostPerKg（V20260910_21 必须先 apply） |
| SP3 先于 SP9 | SP9（提成）依赖 costUnitPrice 回填完整 |
| SP6 可与 SP3 并行 | SP6 结算读 FinanceCostBreakdown，但 SP3 只加字段不改既有字段含义 → merge 时 FinanceCostBreakdown.java 注意 diff 合并 |

---

## 任务分解（TDD 分阶段）

### Phase A：数据模型 + 迁移（无业务逻辑）

---

#### Task A1 — Flyway 迁移 + Entity

**改动文件**：
- `db/flyway/V20260910_20__product_cost_variance_config.sql`（新建）
- `db/flyway/V20260910_21__labor_cost_per_kg_field.sql`（新建）
- `entity/bom/ProductCostVarianceConfig.java`（新建）
- `entity/bom/LaborCostConfig.java`（加 `laborCostPerKg` 字段）
- `dto/bom/LaborCostConfigDTO.java`（补 4 处 roundtrip）

**先写测试**：
```java
// ProductCostVarianceConfigRepositoryTest
@Test void shouldEnforceUniquePerFactoryAndProduct() // UNIQUE 约束
@Test void nullProductTypeIdMeansGlobalDefault()      // productTypeId=NULL 为全局
@Test void shouldFindGlobalWhenProductSpecificMissing()

// LaborCostConfigDtoRoundtripTest
@Test void laborCostPerKgSurvivesCreateAndReload()    // 4处roundtrip
@Test void updateWithNullShouldNotOverwriteExisting() // null-guard
```

**分发卡 A1**：

```
卡 A1 → Sonnet in-harness
目标: 创建 Flyway 迁移 V20260910_20/21 + ProductCostVarianceConfig Entity + LaborCostConfig 补字段 + DTO 4 处 roundtrip
worktree: git worktree add -b feat/sp3-three-price-cost ../cretas-sp3 origin/main
允许改: db/flyway/V20260910_2*.sql, entity/bom/ProductCostVarianceConfig.java,
        entity/bom/LaborCostConfig.java, dto/bom/LaborCostConfigDTO.java,
        repository/bom/ProductCostVarianceConfigRepository.java
禁改: SemiFinishedInventory.java, SalesServiceImpl.java, CostRollupUtil.java (只读)
验收:
  mvn test -pl backend/java/cretas-api -Dtest=ProductCostVarianceConfigRepositoryTest,LaborCostConfigDtoRoundtripTest
  所有测试通过 + mvn compile 无 error
并行: ✅ A1 与 A2 独立（不同文件）
交接: PR off origin/main → git diff origin/main...HEAD --stat 仅含上述文件
⛔ 收尾约束: 仅做到 PR; 不 merge; Opus 终审 Flyway 号段无撞车后 merge
```

---

#### Task A2 — ProductCostVarianceConfig CRUD API + web 配置页框架

**改动文件**：
- `controller/bom/ProductCostVarianceConfigController.java`（新建）
- `service/bom/CostVarianceService.java`（新建接口）
- `service/bom/impl/CostVarianceServiceImpl.java`（新建，含 `resolveThreshold` 方法）
- `web-admin/src/views/bom/CostVarianceConfigView.vue`（新建，el-table + el-form）

**先写测试**：
```java
// CostVarianceServiceTest
@Test void resolveThreshold_productSpecificWins()  // 产品级 > 全局
@Test void resolveThreshold_fallsBackToGlobal()     // 无产品级 → 全局
@Test void resolveThreshold_defaultsTen()           // 无任何配置 → 10.00
@Test void resolveThreshold_nullActualCostReturnsNull() // 诚实 null
```

**分发卡 A2**：

```
卡 A2 → Sonnet in-harness
目标: ProductCostVarianceConfig 的 CRUD controller/service + resolveThreshold 业务 + web 配置页
worktree: 同 A1 worktree（A1 完成后 A2 在同分支继续）
允许改: controller/bom/ProductCostVarianceConfigController.java,
        service/bom/CostVarianceService.java,
        service/bom/impl/CostVarianceServiceImpl.java,
        web-admin/src/views/bom/CostVarianceConfigView.vue
禁改: CostRollupUtil.java, SalesServiceImpl.java, @PriceSensitive 相关类
验收:
  mvn test -Dtest=CostVarianceServiceTest
  vue build 无 error
并行: ❌ A2 依赖 A1（Entity 必须先存在）
交接: PR off origin/main，scope 干净
⛔ 收尾约束: 仅做到 PR; Opus 终审
```

---

### Phase B：移动均价引擎核心

---

#### Task B1 — SemiFinishedInventoryService.updateMovingAvgCost()

**改动文件**：
- `service/inventory/impl/SemiFinishedInventoryService.java`（加 `updateMovingAvgCost(batchId, taskId, inQty, inCost)` 方法）
  - 内部 `SELECT ... FOR UPDATE`（悲观锁）
  - 调用 `CostRollupUtil.COST_SCALE` 做加权
  - inCost=null → 跳过更新，不污染均价
  - 幂等：`(intermediateBatchNo, sourceWorkProcessTaskId)` 唯一键

**先写测试**：
```java
// MovingAvgCostEngineTest
@Test void inQtyZeroShouldSkip()             // 产出量=0 → unitCost 不变
@Test void firstInWithNoExistingStock()      // 首次入库：newCost = inCost
@Test void weightedAvgWithExistingStock()    // 有旧库存：加权公式
@Test void nullInCostShouldSkipUpdate()      // inCost=null → unitCost 不变（诚实 null）
@Test void concurrentInShouldBeConsistent()  // 2线程同时 IN：结果精确
@Test void idempotentOnSameTaskId()          // 重复 taskId → 返回已有值不重算
@Test void outShouldNotChangeUnitCost()      // OUT：unitCost 不变，qty 减少
```

**分发卡 B1**：

```
卡 B1 → Sonnet in-harness
目标: updateMovingAvgCost() 移动均价 IN/OUT 核心逻辑，含悲观锁 + CostRollupUtil + 诚实 null
worktree: feat/sp3-three-price-cost off origin/main（SP1 已合后）
允许改: service/inventory/impl/SemiFinishedInventoryService.java（仅末段加方法）
禁改: CostRollupUtil.java（只调用不改）; SemiFinishedInventory.java 现有字段语义
前置: SP1 已合 main，SemiFinishedInventoryTransaction 表已存在
规则摘要（out-of-harness 时内联，但 Sonnet in-harness 自动加载 .claude/rules）:
  - scale-6 qty / scale-4 cost / HALF_UP (CostRollupUtil 常量)
  - 任一 null 输入 → 结果 null，不假 0
  - DTO roundtrip 4 处必做（见 feedback_dto_roundtrip_silent_drop.md）
验收:
  mvn test -Dtest=MovingAvgCostEngineTest （7 个测试全绿）
并行: ❌ B1 依赖 A1（表和 Entity 必须先存在）
交接: PR，scope 仅 SemiFinishedInventoryService 此方法块
⛔ 收尾约束: 仅 PR; Opus 终审并发/null 逻辑
```

---

#### Task B2 — 报工提交触发均价更新 + costUnitPrice 回填

**改动文件**：
- `service/inventory/impl/ProductionReportServiceImpl.java`（`submitYieldReport` 末段调用 B1 的 `updateMovingAvgCost`）
- `event/ProductionCostUpdatedEvent.java`（新建 Spring 事件）
- `event/OrderCostBackfillListener.java`（新建 @Async @EventListener，回填 costUnitPrice）
- `event/OrderCostAlarmListener.java`（新建 @Async，触发超支检查）

**先写测试**：
```java
// CostBackfillIT（集成测试）
@Test void submitYieldReport_triggersUnitCostUpdate()   // 提交后 unitCost 已更新
@Test void backfillListener_setsOrderItemCostUnitPrice() // costUnitPrice 被回填
@Test void backfillListener_skipsWhenCostNull()          // cost null → 不回填 null
```

**分发卡 B2**：

```
卡 B2 → Sonnet in-harness
目标: submitYieldReport 触发均价更新 + @Async 回填 costUnitPrice + Spring 事件链
worktree: 同 feat/sp3-three-price-cost
允许改: service/inventory/impl/ProductionReportServiceImpl.java（submitYieldReport 末段）,
        event/ProductionCostUpdatedEvent.java（新建）,
        event/OrderCostBackfillListener.java（新建）,
        event/OrderCostAlarmListener.java（新建）
禁改: completeProduction 主事务链（fail-soft catch 救不回 doomed 事务，见 feedback_failsoft_catch_cannot_save_doomed_tx）
规则摘要: @Async 监听器用 REQUIRES_NEW 隔离事务，不参与父事务
验收:
  mvn test -Dtest=CostBackfillIT
并行: ❌ B2 依赖 B1
⛔ 收尾约束: 仅 PR; Opus 终审 @Async 事务隔离
```

---

### Phase C：三价比对 + 超支报警

---

#### Task C1 — getOrderCostBreakdown 扩展三价 + 超支判断

**改动文件**：
- `service/inventory/impl/SalesServiceImpl.java`（`getOrderCostBreakdown()` 行 677 区域末段）
- `dto/inventory/FinanceCostBreakdown.java`（加 variancePct/varianceAbsolute/belowThreshold/alarmMessage）
- `dto/inventory/LineCostBreakdown.java`（加 standardCostPerUnit/actualCostPerUnit/variancePct/belowThreshold）

**先写测试**：
```java
// CostVarianceAlarmTest
@Test void variancePct_calculatedCorrectly()               // (actual-std)/std*100 精度
@Test void belowThreshold_trueWhenVarianceUnder10Pct()
@Test void belowThreshold_falseWhenVarianceOver10Pct()
@Test void belowThreshold_nullWhenStandardCostNull()       // 诚实 null
@Test void belowThreshold_nullWhenActualCostNull()
@Test void alarmMessage_isNullWhenNotExceeded()
@Test void alarmMessage_containsVariancePctWhenExceeded()  // 文案含具体数字
@Test void mixedBatchWeightedCost_multipleItems()          // 混批加权
```

**分发卡 C1**：

```
卡 C1 → Sonnet in-harness
目标: getOrderCostBreakdown() 扩展三价字段 + variancePct 计算 + 超支 flag + alarmMessage
worktree: 同 feat/sp3-three-price-cost
允许改: service/inventory/impl/SalesServiceImpl.java (方法 getOrderCostBreakdown 末段),
        dto/inventory/FinanceCostBreakdown.java,
        dto/inventory/LineCostBreakdown.java
禁改: CostRollupUtil.java; @PriceSensitive 注解机制
规则摘要:
  - 所有新成本字段加 @PriceSensitive
  - null actualCost → belowThreshold=null（不是 false！）
  - CostRollupUtil.COST_SCALE=4, HALF_UP
验收:
  mvn test -Dtest=CostVarianceAlarmTest
并行: ❌ C1 依赖 B1+B2（costUnitPrice 需先有数据路径）
⛔ 收尾约束: 仅 PR; Opus 终审 @PriceSensitive 标注完整性
```

---

#### Task C2 — 毛利红线预警（下单时）

**改动文件**：
- `service/inventory/impl/SalesOrderServiceImpl.java`（`createSalesOrderItem` 加 `validatePriceRedline`）
- `exception/PriceRedlineViolationException.java`（新建，HTTP 200 + warn 字段，非 4xx）
- `dto/sales/SalesOrderItemCreateRequest.java`（确认 `unitPrice` 字段存在）
- `web-admin/src/views/sales/orders/create.vue` 或 `list.vue`（销售价输入 blur → 实时红线校验）

**先写测试**：
```java
// SalesOrderRedlineTest
@Test void priceAboveRedline_noWarning()
@Test void priceBelowRedline_returnsWarningNotException()  // HTTP 200 warn 字段
@Test void priceExactlyAtRedline_noWarning()               // 边界 =
@Test void actualCostNull_skipRedlineCheck()               // 无成本不校验
@Test void targetMarginRateMissing_defaultsTen()           // 未配置默认 10%
```

**分发卡 C2**：

```
卡 C2 → Sonnet in-harness（后端部分）+ Composer 2.5（web 预警 badge）
-- 后端子卡（Sonnet）--
目标: validatePriceRedline service 方法 + PriceRedlineViolationException（HTTP 200 warn）
允许改: service/inventory/impl/SalesOrderServiceImpl.java,
        exception/PriceRedlineViolationException.java
禁改: 现有订单创建事务主链（不卡死流程，warn 不抛 4xx）
验收: mvn test -Dtest=SalesOrderRedlineTest
⛔ 收尾约束: 仅 PR; Opus 终审"不卡死流程"实现

-- 前端子卡（Composer）--
目标: web-admin 下单页 sales price input blur → 调后端预警端点 → 红色 badge
允许改: web-admin/src/views/sales/orders/（create.vue 或 list.vue）
禁改: 其他页面
验收: vue build 无 error + 下单页 unitPrice 低于红线时显示红色 warning badge（截图证据）
⛔ 收尾约束: 仅 PR
```

---

### Phase D：web-admin 三价对比视图

---

#### Task D1 — FinanceCostBreakdown 三价视图组件

**改动文件**：
- `web-admin/src/views/sales/orders/list.vue` 或财审详情（现有文件，扩展三价列）
- `web-admin/src/components/ThreePriceCostBreakdown.vue`（新建可复用卡片组件）

**先写测试**（Vue 单元测试）：
```
ThreePriceCostBreakdown.spec.ts
  - belowThreshold=false 时渲染红色 badge + alarmMessage
  - belowThreshold=null 时渲染灰色"数据不完整"
  - @PriceSensitive 字段为 null 时不渲染成本数字（权限门控情况）
  - variancePct 保留 2 位小数
```

**分发卡 D1**：

```
卡 D1 → Composer 2.5
目标: 三价对比展示组件（标准成本/实际均价/销售价/超支 badge），可复用于财审+销售订单
worktree: feat/sp3-three-price-cost
允许改: web-admin/src/views/sales/ 现有视图,
        web-admin/src/components/ThreePriceCostBreakdown.vue（新建）
禁改: 后端 API、@PriceSensitive、其他视图
防呆规则（内联）:
  - 超支 alarmMessage: duration:0 + showClose:true（sticky，见 fool-proof-design Rule 铁律位c）
  - belowThreshold=null → 显示"成本数据不完整"灰色提示 + next action（位d）
  - @PriceSensitive 字段为 null → 不渲染数字，显示"--"（无权限）
验收:
  npx vitest run src/components/ThreePriceCostBreakdown.spec.ts
  vue build 无 error
  截图证据：超支红色 badge + 正常绿色 badge + null 灰色状态
并行: ❌ D1 依赖 C1（API DTO 新字段需先存在）
⛔ 收尾约束: 仅 PR
```

---

### Phase E：集成测试 + 安全审计

---

#### Task E1 — 集成测试 + @PriceSensitive 安全 IT

**改动文件**（测试文件）：
- `src/test/java/.../MovingAvgCostIT.java`（新建）
- `src/test/java/.../VarianceAlarmIT.java`（新建）
- `src/test/java/.../PriceSensitiveIT.java`（扩展，覆盖新字段）

**分发卡 E1**：

```
卡 E1 → Sonnet in-harness（🔒 安全 IT 需规则意识）
目标: 端到端集成测试（submitYieldReport→unitCost→costUnitPrice→breakdown 三价）
      + @PriceSensitive 安全验证（sales 角色看不到成本数字）
允许改: src/test/java/... 测试文件
禁改: 主代码（只写测试）
验收:
  mvn test -Dtest=MovingAvgCostIT,VarianceAlarmIT,PriceSensitiveIT
  所有测试通过
⛔ 收尾约束: 仅 PR; 🔒 Opus 终审 @PriceSensitive 覆盖完整性（红线）
```

---

## 验收标准（全 Phase 完成后）

### 功能验收

| 场景 | 期望 |
|------|------|
| 提交报工 → `SemiFinishedInventory.unitCost` 更新 | unitCost = (oldQty×oldCost + inQty×inCost)/(oldQty+inQty)，scale-4 HALF_UP |
| 材料无价格 → inCost=null | unitCost 保持原值；`SalesOrderItem.costUnitPrice` 不被覆盖为 null（诚实跳过） |
| `getOrderCostBreakdown()` | 返回 variancePct/belowThreshold/alarmMessage 三字段 |
| 实际成本超标 11%（阈值 10%） | belowThreshold=false + alarmMessage 含 "11.00%（阈值 10.00%）" |
| 实际/标准任一为 null | belowThreshold=null（非 false）|
| sales 角色请求 breakdown | 所有 @PriceSensitive 字段为 null，belowThreshold + alarmMessage 可见 |
| 下单价格低于红线 | HTTP 200 + 警告字段，不返回 4xx，不卡死流程 |
| Flyway 迁移 apply（测试数据库） | V20260910_20/21 成功 apply，无 checksum 冲突 |

### 测试命令（验收证据）

```bash
# 单元 + 集成全套
mvn test -pl backend/java/cretas-api \
  -Dtest="ProductCostVarianceConfigRepositoryTest,LaborCostConfigDtoRoundtripTest,\
          CostVarianceServiceTest,MovingAvgCostEngineTest,CostBackfillIT,\
          CostVarianceAlarmTest,SalesOrderRedlineTest,MovingAvgCostIT,\
          VarianceAlarmIT,PriceSensitiveIT"

# Web build
cd web-admin && npm run build

# 部署前 Flyway 号段查重（Opus 执行）
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d
# 期望：空输出（无重复）
```

---

## 分发总览

| Task | 推荐模型 | effort | orchestration | 分支 | 🔒红线 | 阻塞 |
|------|---------|--------|--------------|------|--------|------|
| A1 Flyway + Entity | Sonnet in-harness | high | inline | feat/sp3-three-price-cost | | 依赖 SP1 |
| A2 CRUD API + web 框架 | Sonnet(后端) + Composer(前端) | high | inline | 同上 | | 依赖 A1 |
| B1 移动均价引擎 | Sonnet in-harness | high | inline | 同上 | | 依赖 A1 |
| B2 事件链 + 回填 | Sonnet in-harness | high | inline | 同上 | | 依赖 B1 |
| C1 三价 breakdown | Sonnet in-harness | high | inline | 同上 | 🔒@PriceSensitive | 依赖 B1+B2 |
| C2 毛利红线 | Sonnet(be)+Composer(fe) | high | inline | 同上 | | 依赖 C1 |
| D1 三价视图组件 | Composer 2.5 | default | inline | 同上 | | 依赖 C1 |
| E1 集成 + 安全 IT | Sonnet in-harness | high | inline | 同上 | 🔒 Opus 终审 | 依赖 C1+D1 |

> **Fleet 现状**（2026-06-09）：Codex/GPT 暂停，只有 Composer 出池 CLI/E2E。Sonnet in-harness 承接所有 Java 后端任务。

---

## 闭环交接流程

```
Opus organizer 出分发卡
    ↓ Steve courier 分派
Sonnet in-harness（每 Task 独立 subagent）
  实现 + TDD 通过 + PR off origin/main
    ↓
git diff origin/main...HEAD --stat 确认 scope 干净（无 sister 文件夹带）
    ↓
🔒 Opus 终审（重点：@PriceSensitive 完整性 + Flyway 号段无撞车 + 并发锁实现 + null 诚实性）
    ↓
merge main → Opus 从 main 部署 prod → 核对运行中 jar 含 variancePct 字段
```

---

## 已知风险备忘

| 风险 | 处理方式 |
|------|---------|
| SP1 未完成 → B1 无法实现 | 严格 Wave 2 串行；B1 worktree 在 SP1 合 main 后才建 |
| Flyway 撞号 | 每次 merge 前 `uniq -d` 查重；发现撞 → 重编号未 apply 的 |
| `fail-soft catch 救不回 doomed 事务` | B2 @Async 监听器使用 `REQUIRES_NEW` 隔离，不共享父事务 |
| DTO roundtrip 静默丢失 | A1 Task 单测覆盖 4 处；Sonnet brief 内联规则 `feedback_dto_roundtrip_silent_drop` |
| @PriceSensitive 遗漏新字段 | C1 + E1 专项测试；Opus 终审时逐字段 grep `@PriceSensitive` |
