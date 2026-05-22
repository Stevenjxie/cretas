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

中文 → 编码 (大写下划线):

| 中文 | 编码 |
|---|---|
| 客单价 | AVG_TICKET_PRICE |
| 翻台率 | TABLE_TURNOVER |
| 食材损耗率 / 损耗率 | RAW_WASTAGE_RATE |
| 良品率 / 工厂良品率 | FACTORY_YIELD_RATE |
| 食安通过率 / 食安合格率 | FOOD_SAFETY_PASS_RATE |
| 计划达成率 / 完成率 | FACTORY_PLAN_ACHIEVE_RATE |
| 菜品毛利 / 毛利率 | DISH_GROSS_MARGIN |

## 输出格式

请返回 JSON 格式 (单一 tool 调度):

```json
{
  "tool": "indicator_query | indicator_comparison | indicator_alert | lineage_query",
  "params": { ... },
  "reasoning": "为什么选这个 Tool 的简要说明 (一句话)"
}
```

例 1 — 单查良品率:
```json
{
  "tool": "indicator_query",
  "params": { "indicator_code": "FACTORY_YIELD_RATE" },
  "reasoning": "用户明确问良品率, 单一指标查询"
}
```

例 2 — 对比 3 个:
```json
{
  "tool": "indicator_comparison",
  "params": { "indicator_codes": ["AVG_TICKET_PRICE", "TABLE_TURNOVER", "FOOD_SAFETY_PASS_RATE"] },
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

Sprint 11 D2 已 seed F999_MOCK 7 indicator × 30 天 = 210 行 indicator_versions
(mock 数据, 见 docs/sprint-11/data-source-decision.md). UI 必标注 "模拟数据 —
实际接入待 Sprint 12". 4 Tools 共用同一数据源, 切回 prod 后业务逻辑不变.
