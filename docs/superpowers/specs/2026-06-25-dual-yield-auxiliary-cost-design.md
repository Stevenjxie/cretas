# 设计：双出成率显示 + 辅料标准单价双锚点倒推核对

**日期**: 2026-06-25
**客户**: F006 六膳门食品科技（真客户；产品"叮咚好食光"系列；白卤猪舌 `4e345886-52e4-494a-bcb3-3f0ee9e126b2`）
**驱动**: 客户张权微信语音 + 辅料成本 Excel（`白卤猪舌(1).xlsx`）+ 逐工序录入截图
**证据**:
- 转录 `docs/customer/2026-06-25-六膳门-辅料成本-语音转录.md`
- 关联 memory `project_2026_06_25_f006_cost_optimization_and_picker`、`project_2026_06_25_cost_terminal_keying_deep_test`
**红线**: 🔒 成本口径 / 库存 → Opus 终审，只从 main 部署 prod。

---

## 1. 背景与客户实际算法（非臆造，逐条来自转录）

客户用 Excel 手算辅料成本，方法明确：

1. **锚点二选一**：辅料无法在中间工序精确称量，只能锚定两端——**投料**（源头 kg）或**产出**（成品份数）。
2. **辅料单价 = 每工序固定 元/kg**，由客户**离线按配方预先算好**（系统**不实时 BOM 计算**）。例：滚揉调料 `16.60/8.68 = 1.91 元/kg`、预煮 `102.03/140 = 0.73 元/kg`、熟制 `246.63/140 = 1.76 元/kg`。
3. **倒推**：份数 + 出成率链 → 倒推各阶段 kg → ×各工序辅料单价 → Σ = 总辅料成本 → ÷份数 = **分摊费用/份**。
4. **只要总量**：不必每工序向用户报辅料明细。
5. **比例固定**：固定 kg 原料对应固定辅料；批次出成率波动导致的偏差极小，忽略。
6. **工序无辅料不崩**：产品有某工序但未配辅料 → 算 0，不报错。工序是**通用模块**，产品非标、来去频繁，禁止一品一维护。
7. **主辅料同逻辑**："先跑主料"只因辅料项多手算烦，非技术分步 → 同一引擎跑主+辅。

**Steve 拍板（2026-06-25）**：锚点不二选一，**两个都算，核对结单时对账**——两条独立倒推路径理论一致（比例固定），**差异即预警**。

---

## 2. 现有代码地基（Explore 已勘）

| 能力 | 位置 | 现状 |
|---|---|---|
| 步出成率（对上工序） | 前端 `web-admin/src/views/production/components/processSheet/ProcessDataTable.vue` `calcYield()`；后端 `service/processentry/impl/ProcessSheetServiceImpl.java` `yieldRate()`（output/input×100，scale4 HALF_UP） | ✅ 已有（逐工序「出成率(%)」列） |
| **累计出成率（对原料）** | `service/yield/impl/YieldCalculationServiceImpl.java` `calculateBatchYield()` L304-310：`cumulative = lastOutput/firstInput`，**含跨单位**（盒→kg via `standardGramsPerUnit`） | ✅ 整批级已实现，需扩到**逐工序** |
| 半成品库存卡 | `ProcessSheet/InventoryTable.vue` + DTO `dto/processentry/ProcessSheetInventoryItem`（batchNumber/produced/used/remaining/unitPrice/status）；端点 `GET /{factoryId}/production-plans/{planId}/process-sheet/inventory` | ✅ 显示位（加列处） |
| 工序链 | `ProductWorkProcess.processOrder`；前端 `ProcessSheet.vue resolveProcesses()` 按 processOrder 排序 | ✅ 倒推按序行走 |
| 主料成本引擎 | `OrderCostBreakdownService`（实际领用、图遍历、副产/diamond，已严格压测） | ✅ 主料走实际领用，辅料走标准单价（本设计新增），同框架 |
| 核对结单预填 | `service/impl/ProductionPlanServiceImpl.java` `getSettlementPrefill()` L1589；variance 比较 L1646-1661 | ⚠️ 现 variance 裸比 plan.plannedQuantity（见 §6 A 项），核对页是双锚点对账落点 |

---

## 3. 范围（两段）

- **段1 双出成率显示**——小，算法已存在，客户已明确要。
- **段2 辅料标准单价 + 双锚点倒推 + 核对**——新子系统，消费段1的出成率。

**非目标**（明确不做）：
- ❌ 实时 BOM 辅料成本计算（客户明确拒绝）。
- ❌ 追踪实际辅料批次/领用（几十种调料，非标，不维护）。
- ❌ 辅料按工序向用户报明细（只要总量）。
- ❌ 批次出成率波动的精确补偿（差异极小，忽略）。

---

## 4. 段1 — 双出成率显示

### 4.1 计算
- **对上工序出成率** = 本道产出 ÷ 本道投入（= 现有 `yieldRate`，前端已 auto 算）。
- **对原料出成率（cumulative）** = 本道产出（折成原料单位）÷ 首道（min processOrder）投入。
  - 复用 `YieldCalculationServiceImpl.calculateBatchYield` 的跨单位逻辑（`standardGramsPerUnit`），但产出**逐工序**而非仅整批：对每个工序 i，`cumYield_i = output_i(折原料单位) / firstInput`。
  - 锚点（首道投入）= min processOrder 工序的 `inputQuantity`（猪舌 = 拆包出库重量）。

### 4.2 显示（方案 A：半成品库存卡加两列）
- DTO `ProcessSheetInventoryItem` 加 2 字段：`stepYieldRate`（对上工序）、`cumulativeYieldRate`（对原料），均 `BigDecimal` 百分比、可空。
- `InventoryTable.vue` 加两列「对上工序」「对原料」。
- 逐工序电子表格的现有「出成率(%)」列**不动**。
- 跨单位/数据不全的工序 → 对应率留空（`—`），不臆造（镜像现有 cross-unit null 语义）。

### 4.3 边界
- 首道（拆包）通常无损耗 → 两率相同（截图 100%/100%）；从有损耗工序起分叉。
- 末道跨单位（份/盒）→ cumulative 用 `standardGramsPerUnit` 折算；折不出 → 留空。

---

## 5. 段2 — 辅料标准单价 + 双锚点倒推 + 核对

### 5.1 数据模型：每工序辅料单价（元/kg）
- 新增配置实体 `ProcessAuxiliaryRate`（工作名）：`(factoryId, productTypeId, processOrder/processCode, auxUnitPrice 元/kg, effectiveDate?)`。
  - 挂在**工序**上（通用模块），按 (产品 × 工序) 配置；未配 → 视为 0。
  - 标准单价，离线算好录入；系统不反算 BOM。
- 继承 `BaseEntity`（created_at/updated_at/deleted_at），Flyway 新表 + RLS/GRANT（smartbi 不涉及；cretas 主库按现有租户隔离）。
- ⚠️ 红线：新表 + 迁移 → Opus 终审，Flyway 编号查最高号防撞。

### 5.2 双锚点倒推引擎
对一个生产计划/批次，已知逐工序的 `inputQuantity_i` / `outputQuantity_i` / `processOrder` / `auxUnitPrice_i` / `份数 N`：

- **投料锚点（正推）**：以首道实际投料 `firstInput` 为基，沿出成率链正向乘，得各工序 kg `kgF_i`。
- **产出锚点（反推）**：以末道实际产出/份数为基，沿出成率链反向除，得各工序 kg `kgB_i`。
- 各工序辅料成本 = `kg_i × auxUnitPrice_i`（未配单价 → 0，不崩）。
- 总辅料（投料口径）`AUX_F = Σ kgF_i × rate_i`；总辅料（产出口径）`AUX_B = Σ kgB_i × rate_i`。
- 分摊/份：`AUX_F / N`、`AUX_B / N`。

> 实现：扩展或新增 `AuxiliaryCostService`，复用 `YieldCalculationServiceImpl` 的逐工序 kg + 出成率链；与 `OrderCostBreakdownService`（主料实际领用）并列，核对页同时取两者。

### 5.3 核对（对账）
- 成品出厂核算 / 核对结单页同时显示 `AUX_F`、`AUX_B` 及**差异率** `|AUX_F − AUX_B| / max(AUX_F,AUX_B)`。
- 差异 ≤ 阈值（默认 5%，与现有 settlement variance 阈值对齐）→ 视为一致，取其一（或均值）入账。
- 差异 > 阈值 → 预警（BLOCKER 或提示，按防呆四位一体：sticky + 含 next action「请核对出成率/投料/产出数据」），人确认。
- 计算精度：`BigDecimal` 全程，HALF_UP，divide 中间步先 quantize（python-java-port Rule 10 同类，Java 侧亦遵循）。

### 5.4 主辅料同框架
- 主料成本：现有 `OrderCostBreakdownService`（实际领用 × 移动均价）。
- 辅料成本：本设计标准单价 × 倒推 kg。
- 核算页合并展示：主料（实际）+ 辅料（标准分摊）= 单位成本，份数分摊。

---

## 6. 与 Option A（variance 单位 bug）的关系

`getSettlementPrefill` L1646-1661 现状：`actualFinished`(末道产出，份/盒) 与 `plannedQuantity`(无单位字段，可能 kg) **裸比** → 误报超产 BLOCKER（实测 plan 24a0954c：4618 vs 1912）。

- 本设计的**双锚点核对**提供独立口径，可作为 variance 的佐证基础。
- A 的**最小修**仍需：variance 比较前加 cross-unit guard（复用 `ProductionBatch.plannedUnit`/`crossUnit` 模式），跨单位时不报超产、留空让人确认。
- A 作为**独立小修**随段1一起出（同属出成率/单位口径域），不阻塞段2。

---

## 7. 防呆（fool-proof，客户原话驱动）

| 规则 | 落地 |
|---|---|
| 工序无辅料单价 → 算 0 不崩 | rate 缺省 0，整链不抛异常 |
| 混批不影响 | 比例固定，按工序 kg × 单价，不依赖批次身份 |
| 出成率波动忽略 | 不做精确补偿，标准单价 × 倒推 kg |
| 核对差异预警 | sticky toast + next action 提示，含具体数字 |
| 跨单位诚实留空 | cumulative 折不出 → `—`，不臆造 |

---

## 8. 测试

- **段1**：逐工序 cumulative 出成率单测（线性链、跨单位末道、首道无损耗两率相等、数据缺失留空）；库存卡渲染。
- **段2**：双锚点倒推一致性（比例固定时 AUX_F ≈ AUX_B）；无辅料工序=0 不崩；差异超阈预警；BigDecimal 精度（HALF_UP、中间步 quantize）。
- 用白卤猪舌真实链（猪舌 7000g + 调料；出成率 解冻1.0/滚揉0.89/二次滚揉0.83/焯水1.05/预煮0.75/熟制0.55）对齐 Excel 数字（分摊费用/份 0.58~1.66 区间）。
- 🔒 prod 验证：headed 看核对页两口径 + 差异；只从 main 部署。

---

## 9. 已定决策 / 待确认

**已定**：
- 双出成率显示在半成品库存卡（方案 A）。
- 辅料 = 每工序标准单价（元/kg），非实时 BOM。
- 双锚点都算 + 核对对账（非二选一）。
- 主辅料同框架；工序无辅料算 0。

**待 Steve spec review 时确认**：
- `ProcessAuxiliaryRate` 配置粒度：按 (产品×工序) 还是 (工序模块全局默认 + 产品覆盖)？倾向前者（产品非标，辅料差别大）。
- 辅料单价录入入口：工序配置页加一列 vs 独立辅料单价维护页？
- 核对差异阈值：默认 5%（与 settlement variance 对齐），可否工厂级可配？
- 段1 / 段2 / A修 的出 PR 顺序与是否合并部署。

---

## 10. 实施顺序（writing-plans 细化）

1. **A 修**（variance cross-unit guard，小，独立）。
2. **段1 双出成率**（cumulative 逐工序 + 库存卡两列）。
3. **段2 辅料**：① Flyway 新表 `ProcessAuxiliaryRate` + 配置 UI；② `AuxiliaryCostService` 双锚点倒推；③ 核对页双口径 + 差异预警。
