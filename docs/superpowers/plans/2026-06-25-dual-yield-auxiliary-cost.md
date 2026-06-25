# 双出成率 + 辅料标准单价双锚点倒推核对 — 实施计划（三批分发）

> **For agentic workers:** 本计划分三批独立交付，各自 PR off `origin/main`，🔒 成本/迁移项 Opus 终审 + 只从 main 部署。
> 设计依据：`docs/superpowers/specs/2026-06-25-dual-yield-auxiliary-cost-design.md`。

**Goal:** 逐工序显示「对上工序 / 对原料」双出成率；辅料按每工序标准单价(元/kg)双锚点倒推、核对结单时对账。

**Architecture:** 复用现有 `YieldCalculationServiceImpl`(cumulative+跨单位)、`ProcessSheet` 库存卡、工序链；辅料走标准单价分摊(非实时BOM)，与主料实际领用引擎并列。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA/Flyway + PostgreSQL；web-admin Vue3 + element-plus。

---

## 三批 + 路由

| 批 | 内容 | 大小 | 🔒 | 执行 | 分支 |
|---|---|---|---|---|---|
| **A** | variance 跨单位 guard 修复(getSettlementPrefill L1646-1661 误报超产) | 小 | 🔒成本/单位 | **Opus keystone**(本 chat，systematic-debugging) | feat/f006-variance-unit-fix |
| **段1** | 双出成率：cumulative 逐工序 + DTO 2字段 + 库存卡2列 | 中 | — | **Sonnet in-harness**(rule-aware，读现有码) | feat/f006-dual-yield |
| **段2** | 辅料：新表 ProcessAuxiliaryRate + 配置列 + 双锚点引擎 + 核对页双口径 | 大 | 🔒迁移+成本 | Opus keystone(引擎/Flyway) + Sonnet(配置UI/脚手架) | feat/f006-aux-cost |

> 顺序 A → 段1 → 段2。段2 依赖段1 的 cumulative 逐工序产出（倒推 kg 复用）。

---

## 批 A — variance 跨单位 guard（Opus keystone，先做）

**Files:**
- Modify: `backend/java/cretas-api/.../service/impl/ProductionPlanServiceImpl.java` `getSettlementPrefill()` L1646-1661
- Test: 对应 settlement prefill 测试类（新增跨单位用例）

**根因**：`actualFinished`(末道产出 份/盒) 与 `planned`(plan.plannedQuantity，注释自承"无单位字段") 裸比 → 跨单位误报超产 BLOCKER(实测 plan 24a0954c 4618 vs 1912)。

**修法（systematic-debugging 先确认单位可判定来源后定）**：
- 判定 cross-unit：末道报工 `output_unit` 是否 ≠ 计划/批次基准单位（参考 `ProductionBatch.plannedUnit`/`crossUnit` 现有模式）。
- 跨单位时：**不报 QUANTITY_VARIANCE_OVER_PLAN**，varianceReason 留空让人确认（诚实），不臆造。
- 同单位：行为零变化。
- TDD：先写跨单位用例(4618份 vs 1912kg → 不报超产)红→修→绿；同单位超产用例仍报。

**验收**：跨单位计划 prefill 不再误报超产 BLOCKER；同单位超产仍正确拦；headed 验 plan 24a0954c。

---

## 段1 — 双出成率（Sonnet in-harness brief）

**Files:**
- Modify: `backend/java/.../service/yield/impl/YieldCalculationServiceImpl.java`（cumulative 从整批扩到逐工序：每工序 `cumYield_i = output_i(折原料单位)/firstInput`，复用现有 `standardGramsPerUnit` 跨单位）
- Modify: `backend/java/.../dto/processentry/ProcessSheetInventoryItem.java`（加 `stepYieldRate`、`cumulativeYieldRate`，BigDecimal 可空）
- Modify: 库存卡端点 service（填充两字段）
- Modify: `web-admin/src/views/production/components/processSheet/InventoryTable.vue`（加「对上工序」「对原料」两列）+ `api/processSheet.ts` interface
- Test: cumulative 逐工序单测

**要点**：
- 锚点=首道(min processOrder)投入；首道无损耗时两率相同；跨单位/数据缺失留空(`—`)不臆造。
- 逐工序电子表格现有「出成率(%)」列**不动**。
- 字段命名 camelCase(JSON)/snake_case(列)；不破坏现有库存卡。

**验收**：库存卡每行显示两率；猪舌链焯水行对上工序≠对原料(分叉)；跨单位末道留空；现有功能零回归。

---

## 段2 — 辅料标准单价双锚点倒推核对（Opus keystone + Sonnet）

> 到达此批时出独立细化（依赖段1 cumulative 落地后的真实接口）。骨架：

1. **Flyway 新表 `process_auxiliary_rate`**（Opus，🔒）：(factory_id, product_type_id, process_order/code, aux_unit_price, BaseEntity 字段)。编号查 origin/main 最高号防撞。租户隔离按现有 factoryId。
2. **配置列**（Sonnet）：工序配置页加「辅料单价(元/kg)」列，未配=空=0。
3. **双锚点引擎 `AuxiliaryCostService`**（Opus keystone，🔒）：
   - 投料锚点正推 / 产出锚点反推 → 各工序 kg → ×rate(缺省0) → Σ → ÷份数。
   - BigDecimal 全程 HALF_UP，中间步先 quantize。
4. **核对页双口径**（Opus + Sonnet）：成品出厂核算/核对结单显示 AUX_F、AUX_B、差异%；超阈值(默认5%，工厂可配)预警(防呆四位一体 sticky+next action)。
5. **测试**：双锚点一致性(比例固定→AUX_F≈AUX_B)、无辅料工序=0不崩、差异超阈预警、白卤猪舌对齐 Excel(分摊 0.58~1.66/份)。

**验收**：核对页两口径+差异；headed 验；🔒 Opus 终审从 main 部署。

---

## Self-Review（spec 覆盖）

- 段1 双出成率 ✓(spec §4)　辅料双锚点 ✓(§5)　核对 ✓(§5.3)　主辅同框架 ✓(§5.4)　防呆 ✓(§7)　A关系 ✓(§6)。
- 4 项已定决策(§9)已落入段2(粒度产品×工序/配置列/阈值工厂可配/三批顺序)。
- 无占位：A 有根因+TDD方向；段1有文件+字段；段2骨架到达时细化(依赖段1接口，避免预编不存在签名)。
