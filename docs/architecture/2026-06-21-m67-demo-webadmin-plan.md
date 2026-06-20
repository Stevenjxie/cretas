# M67 多批次出成率+成本 Demo — web-admin 落地计划 + 分发卡

**日期**: 2026-06-21
**配套**: [正确算法规格](./2026-06-20-multibatch-yield-cost-correct-algorithm.md) · [开发任务/测试](./2026-06-20-multibatch-yield-cost-dev-tasks.md)
**目标**: 在现有 web-admin 演示客户 M67(卤牛肉)的 3 个核心数：**出成率 / 单盒成本 / 人工成本**，外加批次溯源可视化。

---

## 关键发现：后端 80% 已存在，不从零造

superpowers 探查确认：

- ✅ **后端已有完整出成率+成本框架**：`OrderYieldController.getOrderYieldSummary(factoryId, orderId)` 已返回 **整体出成率 + 逐道出成率(StepYieldDTO) + 人工成本 + 材料成本 + 总成本**。
  - 数据契约：`ProductionReport`(report_type='YIELD', 按 process_order) ← `ProductionBatch`(plan_id) ← `ProductionPlan`(source_order_id) ← `SalesOrder`。
  - `YieldCalculationServiceImpl.calculateBatchYield` 逐道 `output/input`、累计连乘、per-step labor/material cost。
- ✅ **前端组件几乎全有**：`KPICard.vue`、`SankeyChart.vue`(溯源图)、`ThreePriceCostBreakdown.vue`(成本拆解)、`ExcelUpload.vue`、`BatchLineagePane.vue`、`AIQuery.vue`、`AlertDashboard.vue`。
- ❌ **真缺口 = demo 数据**：现有 demo 租户有 ProductionBatch 但**零 YIELD ProductionReport / 零 MaterialConsumption** → 现成 API 返回空。必须 seed 一份 M67 链。

**结论**：Demo = 灌 M67 数据(喂现成 API) + 拼一个前端页面 + 补「调料/包装/每盒」三项展示。**不需要**先建 dev-tasks 的 T1-T7 全套（那是把多批"混锅"算到字节级正确的后续工程；demo 用现成的订单级聚合即可呈现 3 个数 + 溯源）。

---

## Demo 范围(本次)

| 块 | 内容 | 复用/新建 |
|---|---|---|
| A | 本计划文档 + 分发卡 | 文档 |
| B1 | **seed M67 数据**：SalesOrder→Plan→Batch→6 道 YIELD ProductionReport(修油/滚揉/焯水/熟制/气调/包装) + 原料 MaterialBatch(unitPrice) + MaterialConsumption 边(供溯源/混批) | NEW seed 脚本 |
| B2 | **前端页面**「成品出厂核算」：调 `getOrderYieldSummary` → KPI 卡(出成率/单盒成本/人工) + 逐道出成率表 + 成本四拆 + 溯源图 | 拼现成组件 |
| B3 | **调料+包装+每盒** 展示：辅料成本/包装成本/单盒成本(总成本÷盒数) | 前端算 or 薄后端扩展 |
| B4 | 部署(java 不变/web-admin/seed) + headed 验证 | — |

**多批混锅(熟制0614=焯水0613+0614)**：本次以**溯源图 + MaterialConsumption 边**定性展示"一锅来自多批"；字节级正确的 split 成本(dev-tasks T1-T3)列为后续。对客户演示足够。

---

## 数据契约(seed 必须喂对)

```
SalesOrder(id=SO-M67-xxx, factory_id)
  └ ProductionPlan(source_order_id=SO-M67-xxx, factory_id)
      └ ProductionBatch(id, production_plan_id, factory_id, batch_number)
          └ ProductionReport × 6 (report_type='YIELD', deleted_at=NULL)
               process_order: 1修油 2滚揉 3焯水 4熟制 5气调 6包装
               字段: input_quantity / output_quantity / labor_cost / material_cost
                     total_work_minutes / total_workers / work_process_task_id / product_name
原料: MaterialBatch(unit_price) ; 边: MaterialConsumption(production_batch_id←batch_id, quantity, source_type, unit_price)
```
真实 M67 数(6-16 批可复刻)：修油 90.7% · 滚揉 119.9% · 焯水 72.8% · 熟制 74% · 气调 100% → 整批 ~58.6%；人工 ~0.61 元/盒、调料 ~0.24、包装 ~0.49。

---

## 🚦 分发卡(若并行)

| 卡 | 任务 | 模型 | worktree | 🔒 |
|---|---|---|---|---|
| 1 | seed M67 脚本(schema 严按 information_schema，FK 真实解析，幂等 marker) | Opus/Sonnet | feat/m67-yield-cost-demo | 🔒(写 prod demo 数据) |
| 2 | 前端「成品出厂核算」页(拼 KPICard/SankeyChart/ThreePriceCostBreakdown) | Composer/Sonnet | 同上 | |
| 3 | (可选)后端 DTO 补 调料/包装/每盒 字段 | Opus brief→Sonnet | 同上 | |

> 红线：seed 写 prod demo 租户(新 DEMO_M67 或 F004，**绝不碰 F006/qhj 真客户**)；部署从 main。

---

## 演示脚本(给客户)
1. 打开「成品出厂核算」选 M67 订单 → 出成率 58.6% + 单盒成本拆解(原料+人工+调料+包装)。
2. 溯源图：成品 ← 气调 ← 熟制 ← 焯水 ← 滚揉 ← 修油 ← 原料。
3. 混锅：熟制批次来自两批焯水(溯源图分叉)。
4. 焯水出成率偏低自动标红。
5. 导入客户 M67 Excel 重算(复用 ExcelUpload)。
