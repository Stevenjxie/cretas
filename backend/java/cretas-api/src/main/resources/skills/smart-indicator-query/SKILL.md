---
name: smart-indicator-query
displayName: 智能指标分析
description: AI 工厂 chat 入口 — 老板问指标相关问题 (单查/对比/告警/溯源), 自动选择最合适的 Tool 调度. Sprint 11 D6 落地, 关联 4 个 indicator/lineage Tool.
version: 1.0.0
triggers:
  - "指标"
  - "客单价"
  - "翻台率"
  - "良品率"
  - "食材损耗"
  - "食安通过"
  - "计划达成"
  - "菜品毛利"
  - "看一下今天"
  - "今天怎么样"
  - "现在状态如何"
  - "有什么需要关注"
  - "几个红灯"
  - "对比"
  - "并排看"
  - "几个指标"
  - "哪个有问题"
  - "这批从哪来"
  - "原料用了哪些"
  - "召回查谁"
  - "溯源"
tools:
  - indicator_query
  - indicator_comparison
  - indicator_alert
  - lineage_query
contextNeeded:
  - factoryId
category: indicator
priority: 90
errorStrategy: CONTINUE_ON_ERROR
---

# 智能指标分析 Skill

## 任务

AI 工厂 chat 中老板提到指标/溯源相关问题时, 智能选择最合适的 Tool 调度并整合返回结构化结果。覆盖 Sprint 11 D3-D5 4 个 Tool:

| 用户场景 | 调度 Tool | 触发模式 |
|---|---|---|
| 单一指标查询 | `indicator_query` | 提到具体 1 个指标 (例 "客单价多少") |
| 多指标横向对比 | `indicator_comparison` | 2+ 指标并列 (例 "客单价和翻台率") |
| 告警扫描 | `indicator_alert` | 无具体指标 ("几个红灯" / "今天怎么样") |
| 批次溯源 | `lineage_query` | 提到批次号 / 召回 / "从哪来" |

## 用户查询

{{userQuery}}

## 路由决策规则

请按以下优先级判断:

1. **批次溯源识别**: 用户提到 `RES_*` / `MB-*` / `SHP-*` 批次号, 或问 "这批从哪来" / "用了哪些原料" / "召回" / "客户追溯"
   → 调度 `lineage_query` (params: batch_type + batch_id [+ direction])

2. **单一指标查询**: 用户明确提到 1 个指标名 (例 "客单价" / "良品率" / "翻台率")
   → 调度 `indicator_query` (params: indicator_code)

3. **多指标对比**: 用户连续提到 2+ 指标 (例 "客单价和翻台率" / "把 3 个指标一起看")
   → 调度 `indicator_comparison` (params: indicator_codes = list)

4. **全局告警扫描**: 用户问 "几个红灯" / "什么需要关注" / "今天状态如何" / "有问题吗" (无具体指标名)
   → 调度 `indicator_alert` (params: min_severity = WARNING by default)

5. **歧义场景**: 默认调度 `indicator_alert` (factory-wide 总览, 信息密度高)

## 指标编码映射

中文 → 编码 (大写下划线). Sprint 12 更新: V_23_11 mirror codes (AVG_TICKET_PRICE/TABLE_TURNOVER/
FACTORY_YIELD_RATE 等) 已删, 改 map 到 Phase B/C 真接业务表的 REAL_BUSINESS codes:

| 中文 | 编码 | 数据源 |
|---|---|---|
| 客单价 / 平均订单金额 | B2B_AVG_ORDER_VALUE | sales_orders |
| 销售额 / 本月营收 | B2B_TOTAL_REVENUE_MTD | sales_orders |
| 订单数 / 本月订单 | B2B_ORDER_COUNT_MTD | sales_orders |
| 库存价值 / 库存 | FACTORY_INVENTORY_VALUE | material_batches |
| 不合格率 / 质检不合格 | FACTORY_QUALITY_REJECT_RATE | quality_inspections |
| HACCP违规 / 违规次数 | FACTORY_HACCP_VIOLATIONS_MTD | haccp_monitoring_records |
| 出品率 | FACTORY_LU_YIELD_RATE | production_batches |
| 单位成本 / 成本 | FACTORY_LU_UNIT_COST | production_batches |
| 日均产量 / 产量 | FACTORY_LU_DAILY_OUTPUT | production_batches |
| 原料周转 / 周转天数 | FACTORY_LU_MATERIAL_TURNOVER_DAYS | material_batches |
| 真空包装合格率 / 真空包装 | FACTORY_LU_VACUUM_PACK_PASS_RATE | quality_inspections |

注: 卤味工厂 (F006) 不再用餐饮 codes (翻台率/菜品毛利). 若数据为空指标返 "—" (null-preserve),
属正常 — 等老板录入生产/质检数据后自动填.

## 输出格式

请返回 JSON 格式 (单一 tool 调度):

```json
{
  "tool": "indicator_query | indicator_comparison | indicator_alert | lineage_query",
  "params": { ... },
  "reasoning": "为什么选这个 Tool 的简要说明 (一句话)"
}
```

例 1 — 单查出品率:
```json
{
  "tool": "indicator_query",
  "params": { "indicator_code": "FACTORY_LU_YIELD_RATE" },
  "reasoning": "用户明确问出品率, 单一指标查询"
}
```

例 2 — 对比 3 个:
```json
{
  "tool": "indicator_comparison",
  "params": { "indicator_codes": ["B2B_AVG_ORDER_VALUE", "FACTORY_INVENTORY_VALUE", "FACTORY_LU_UNIT_COST"] },
  "reasoning": "用户列出 3 个指标横向比"
}
```

例 3 — 全局告警:
```json
{
  "tool": "indicator_alert",
  "params": { "min_severity": "WARNING" },
  "reasoning": "用户问 \"今天怎么样\" 无具体指标, 默认 factory-wide 扫描"
}
```

例 4 — 批次溯源:
```json
{
  "tool": "lineage_query",
  "params": { "batch_type": "FINISHED_BATCH", "batch_id": "RES_3101_009", "direction": "BOTH" },
  "reasoning": "用户提批次号 RES_3101_009, 走溯源链路"
}
```

## 数据源说明

Sprint 12 (Phase A-C) 已把 F999_MOCK mirror 删除, 改为真接业务表:
- B2B_* ← sales_orders (真销售数据)
- FACTORY_INVENTORY_VALUE ← material_batches
- FACTORY_QUALITY_REJECT_RATE ← quality_inspections (无数据返 "—")
- FACTORY_HACCP_VIOLATIONS_MTD ← haccp_monitoring_records (无数据返 "—")
- FACTORY_LU_* (卤味业态) ← production_batches / quality_inspections (无数据返 "—")

ratio/百分比类指标无数据时返 null → UI "—" (null-preserve, 诚实不伪造).
indicator_query Tool 通过 IndicatorComputationStrategy 真算, compute_source='REAL_BUSINESS:<code>'.
不再有 "模拟数据" 标注 — 数字都是真业务 (或诚实的 "—").
