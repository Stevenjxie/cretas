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

**Steve 拍板（2026-06-25，经 3-agent 审计澄清）**：核对**不是**同一辅料成本算两遍对账（那样会望远镜收敛恒等 0，无信号——审计②已证）。真实意图是 **投料端「应投」(标准出成率推算) vs 出成端「实际」→ 抓「多投 / 误差 / 浪费」**。一端用**标准配方率**、一端用**实际报工**，两者不符即信号。这需要新增「标准出成率」配置（见 §5.1）。

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

## 5. 段2 — 标准出成率 + 投料-产出对账（抓多投/误差）+ 辅料标准单价

> **审计②修正**：双锚点若两端都用「实际报工出成率」走链会望远镜收敛恒等 0（无信号）。Steve 真实意图 = **投料端用「标准配方率」推算应投 vs 出成端「实际」→ 抓多投/误差**。故必须引入「标准出成率」，对账两端用**不同信息源**（标准 vs 实际）。

### 5.1 数据模型：标准出成率 + 辅料单价（per 产品×工序）
- 新增 (产品×工序) 配置表（工作名 `ProcessCostConfig`）：
  - `standardYieldRate`（标准出成率，配方率，来自客户配方/Excel；**对账基准**）
  - `auxUnitPrice`（辅料单价 元/kg）
  - `auxBasis`（`INPUT|OUTPUT`，元/kg 乘**该工序投入侧还是产出侧 kg**——保水工序 output>input，必须显式，见审计 B-1）
- join key = **`process_order`**（钉死，不用 code，避免 role-mode 撞名）。
- 未配 `auxUnitPrice` → 视为 0，不崩；未配 `standardYieldRate` → 该工序对账跳过（标 INFO 不阻塞）。
- 标准值，离线/配方录一次；系统**不反算 BOM**。继承 `BaseEntity`；Flyway 新表；cretas 主库 `factoryId` 租户隔离。~~effectiveDate~~（审计 SCOPE-2，YAGNI，砍）。
- ⚠️ 红线：新表 + 迁移 → **Opus 终审**，Flyway 编号查 origin/main 最高号防撞。

### 5.2 投料-产出对账引擎（核心信号 = 多投/误差）
已知逐工序 `actualInput_i` / `actualOutput_i` / `processOrder` / `standardYieldRate_i` / 实际首道投料 `actualFirstInput` / 实际末道产出 `actualOutput` / 份数 `N`：

- **标准应投**：`stdFirstInput = actualOutput(折首道单位) ÷ Π(standardYieldRate_i)`（用标准率反推应投多少料）。
- **多投/误差** = `actualFirstInput − stdFirstInput`；差异率 = `(actualFirstInput − stdFirstInput) / stdFirstInput`。
  - >阈值 → **多投预警**（投了料没出对应成品 = 浪费/异常出成；该料没产出却进了成本）。
- 各工序**标准 kg** = 从锚点沿 `standardYieldRate` 推；各工序**实际 kg** = 报工 `actualInput_i/actualOutput_i`。
- ⚠️ **铁律**（审计②）：对账两端必须**一端标准、一端实际**——**禁止两端都用实际率**（否则恒等 0 无信号）。

> 实现：新增 `CostReconcileService`，标准侧用新 `standardYieldRate` 走链，实际侧复用 `YieldCalculationServiceImpl` 报工率；与 `OrderCostBreakdownService`（主料实际领用）并列。

### 5.3 辅料成本（标准单价分摊）+ 多投辅料
- **标准辅料** = `Σ 各工序标准 kg × auxUnitPrice_i`（按 `auxBasis` 取投/产侧 kg）`÷ N`。
- **实际口径辅料** = `Σ 各工序实际 kg × auxUnitPrice_i ÷ N`。
- **多投辅料** = 实际 − 标准（原料多投 → 辅料按固定比例同步放大）。
- 核算页显示：标准辅料 / 实际辅料 / **多投差异**；差异 > 阈值（默认 5%，**工厂级可配**）→ 预警（防呆四位一体：sticky + next action「请核对投料/产出/出成率」），人确认。
- 精度：`BigDecimal` 全程 HALF_UP，divide 中间步先 quantize。

### 5.4 主辅料同框架
- 主料成本：现有 `OrderCostBreakdownService`（实际领用 × 移动均价）。
- 辅料成本：标准单价 × 倒推 kg（本设计）。
- 核算页合并：主料（实际）+ 辅料（标准分摊）= 单位成本，份数分摊；并列多投对账。

### 5.5 边界与已知限制（审计 H1/H3/MISSING）
- **混批/diamond**：辅料倒推用线性 `YieldCalculationServiceImpl`（`steps.get(0)` 锚点），**本期只支持线性链**；混批/diamond 辅料对账 **defer**（成本引擎 `OrderCostBreakdownService` 有 path-scoped diamond 遍历，未来复用）。诚实标限制，不静默错算。
- **注射多段工序**：一道工序内分多段、各段独立费率 → 单 (产品×工序) 单价表达不了，**已知限制**（白卤猪舌无注射）；未来按「工序-段」细化。
- **跨单位**：份→kg 用 `ProductType.gramsPerUnit`（可空）；空 → 对账留空**不误报**（审计 H2）。
- **保水工序 (>100%)**：output>input（焯水/滚揉吸水）数学无溢出，但 `auxBasis` 必须显式（乘干投入 vs 吸水产出）。
- **partial-pot**：元/kg 费率 batch-size 无关，部分锅（客户"一锅装120"）正常。

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
- **段2**：投料-产出对账信号（**标准率 vs 实际率不同 → 差异非 0**；禁两端同源恒等 0）；多投预警触发；无辅料工序=0 不崩；跨单位 gramsPerUnit 空 → 留空不误报；BigDecimal 精度（HALF_UP、中间步 quantize）。
- **测试数据两端分清**：标准侧 = 白卤猪舌**配方标准率**（解冻1.0/滚揉0.89/二次滚揉0.83/焯水1.05/预煮0.75/熟制0.55，Excel sheet2/3）；实际侧 = 报工实际（repo `YieldGoldStandardIT` 滚揉保水 135% 是某批实际）。**两者之差 = 要抓的多投/偏差信号，不是 bug**（审计 H1 澄清）。辅料费率（滚揉1.91/预煮0.73/熟制1.76 元/kg，Excel sheet1）× 标准/实际 kg → 分摊费用/份（Excel N 列 0.58~1.66）。
- 🔒 prod 验证：headed 看核对页两口径 + 差异；只从 main 部署。

---

## 9. 已定决策 / 待确认

**已定**：
- 双出成率显示在半成品库存卡（方案 A）。
- 辅料 = 每工序标准单价（元/kg），非实时 BOM。
- 双锚点都算 + 核对对账（非二选一）。
- 主辅料同框架；工序无辅料算 0。

**已定（Steve 2026-06-25「按推荐做」+ 审计澄清后确认「选 B」）**：
- **段2 = B**：投料端标准率推算「应投」 vs 出成端实际 → **抓多投/误差**（非单锚点，非同源对账）。需 `standardYieldRate` 配置（审计②证明 + Steve 确认核对真实意图）。
- 配置：一张 `ProcessCostConfig`（per 产品×工序）含 `standardYieldRate` + `auxUnitPrice` + `auxBasis(INPUT|OUTPUT)`；join key = **`process_order`**。
- 录入入口：**工序配置页加列**（标准率 / 辅料单价 / 基准）。
- 差异阈值：**默认 5%，工厂级可配**。
- `auxBasis`（元/kg 乘投入侧 or 产出侧 kg）**显式配置**（审计 B-1，保水工序必须）。
- 混批辅料：**本期线性，混批 defer**（审计 H3）；注射多段：已知限制（审计 MISSING-3）。
- ~~effectiveDate~~ 砍（YAGNI，审计 SCOPE-2）。
- 出 PR 顺序：**A修 → 段1双出成率 → 段2(B)**，三批各自 PR off origin/main，Opus 终审从 main 部署。

---

## 10. 实施顺序（writing-plans 细化）

1. **A 修**（variance cross-unit guard，小，独立）。
2. **段1 双出成率**（cumulative 逐工序 + 库存卡两列）。
3. **段2(B)**：① Flyway 新表 `ProcessCostConfig`（standardYieldRate + auxUnitPrice + auxBasis）+ 配置列 UI；② `CostReconcileService`（标准侧走 standardYieldRate / 实际侧报工率）+ `AuxiliaryCostService`；③ 核算页 标准/实际/多投 三栏 + 差异预警。

---

## 11. 审计发现与处置（3-agent 对抗审计，2026-06-25）

| # | 发现 | 严重 | 处置 |
|---|---|---|---|
| 代码 grounding | 6 条声明全 TRUE/PARTIAL，A 修单位信号可取（batches[].plannedUnit / reports[].outputUnit），cumulative 可扩逐工序 | ✅ | 段1+A修 判绿，直接做 |
| **②核对 no-op** | 双锚点若两端同用实际率 → 望远镜收敛恒等 0，无信号 | **CRITICAL** | **改 B**：投料端标准率 vs 出成端实际（§5.2 铁律），引入 standardYieldRate |
| H1 出成率链 | §8 链（滚揉0.89）与 repo 实际（135%保水）冲突 | HIGH | 澄清：0.89=配方标准、135%=某批实际，**两者之差正是信号**（§8 重写） |
| B-1 元/kg 基准 | 费率分母不一（滚揉8.68 / 预煮140），乘哪道 kg 未定 | HIGH | 加 `auxBasis(INPUT\|OUTPUT)` 显式配置（§5.1） |
| H3 混批 | 辅料倒推复用线性引擎，无 diamond | HIGH | 本期线性，混批 defer（§5.5） |
| H2 跨单位 | 份→kg 用 gramsPerUnit 可空 | MED | 空 → 留空不误报（§5.5） |
| MISSING-3 注射多段 | 单 (产品×工序) 单价表达不了多段 | MED | 已知限制注明（§5.5） |
| SCOPE-2 effectiveDate | 投机版本化 | LOW | 砍（§5.1） |
| MISSING-1 partial-pot | 客户提"一锅装120" | LOW | 元/kg batch-size 无关，注一句（§5.5） |

**审计净价值**：在写码前抓出段2 核对的结构性死穴（恒等 0），经 Steve 澄清真实意图（抓多投/误差）重定为 B 方案；同时坐实 A修+段1 安全。
