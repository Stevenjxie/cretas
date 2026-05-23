# Sprint 11 D1 — 数据源决定文档

**日期**: 2026-05-22
**决策**: **Layer 4 Mock (F999_MOCK)** — Layer 1 真数据严重不足, 不纠结直接跳 Layer 4。
**Per goal anti-goal**: "Layer 1 纠结超 D1 → 跳 Layer 4 (不浪费时间)"。

---

## Layer 1 实测 (SSH 47, cretas_prod_db, factory_id='F006')

| 源表 | 行数 | 阈值 (≥30) | 判定 |
|---|---:|---:|---|
| sales_orders | **5** | 30 | ❌ 严重不足 |
| production_batches | **2** | 30 | ❌ 严重不足 |
| bom_recipes | **2** | 30 | ❌ 严重不足 |
| material_batches | **3** | 30 | ❌ 严重不足 |
| quality_inspections | **3** | 30 | ❌ 严重不足 |
| finished_goods_batches | **1** | 30 | ❌ 严重不足 |
| food_samples | **0** | 30 | ❌ 完全空 |
| haccp_monitoring_records | **0** | 30 | ❌ 完全空 |
| pos_order_syncs | **0** | 30 | ❌ 完全空 |

**没有一个源表达 30 行阈值**, F006 完全无法基于 Layer 1 算 7 indicator。

## Layer 2-3 考虑

- **Layer 2 (本地 cretas_db seed)**: F006 seed migration 仅 V20260603_01 (test 数据), 跟 prod 同等空表。
- **Layer 3 (MallCenter 小程序真数据)**: F006 是卤制品制造厂, 不是餐饮 SaaS, 没 mall 订单流。
- **跳 Layer 2/3**: 都不够撑 7 indicator 30 天数据。

## Layer 4 Mock 选择 (默认, 推荐)

**新建** `scripts/mock/generate-f006-indicator-data.py`:

### 7 个 indicator 数据分布 (per Sprint 11 brief)

| Indicator | 均值 | 分布特征 |
|---|---|---|
| **客单价** (AVG_TICKET_PRICE) | 35 元 | 周末 +20%, 节假日 +30%, normal(0, 3) |
| **翻台率** (TABLE_TURNOVER) | 工作日 1.8, 周末 2.8 | 雨天 -25%, 午高峰 + 晚高峰 |
| **食材损耗率** (RAW_WASTAGE_RATE) | 4.5% | normal(0, 0.8), 偶发 8% 异常 |
| **良品率** (FACTORY_YIELD_RATE) | 96.5% | 工艺故障日 88% (D15-D17 触发 Alert) |
| **食安通过率** (FOOD_SAFETY_PASS_RATE) | 99.2% | 月底 D28 触发 95% 异常 |
| **计划达成率** (FACTORY_PLAN_ACHIEVE_RATE) | 102% | 大单日 85% 异常 |
| **菜品毛利** (DISH_GROSS_MARGIN) | 38% | 高峰日 +5pp |

### 隔离策略

- `factory_id = 'F999_MOCK'` 不混 F006 真数据
- Sprint 12 真接 prod 后只换数据源, 业务逻辑 / Tool / UI 不变
- UI 必标注 **"模拟数据 — 实际接入待 Sprint 12"** (per goal anti-goal)

### 30 天 × 7 indicator = 210 行 indicator_versions 落库

Mock 生成器写入:
- `indicators` (7 行 — code/category/computeStrategy='PRECOMPUTED'/factory_id='F999_MOCK')
- `indicator_versions` (210 行 — 30 天 × 7 indicator, 每行 value/period_start/period_end/computedAt)
- `indicator_thresholds` (~14 行 — 每 indicator 2 阈值: warning + alert)

---

## D2 验收标准 (per goal Stop hook check #1)

```sql
-- 验证 F999_MOCK 已 seed
SELECT COUNT(*) FROM indicators WHERE factory_id='F999_MOCK';  -- 期望 7
SELECT COUNT(*) FROM indicator_versions iv
JOIN indicators i ON iv.indicator_id = i.id
WHERE i.factory_id='F999_MOCK';  -- 期望 210
```

如 D2 EOD 这两个 SELECT 返 7 + 210, D1+D2 完成, D3 开始。

---

## 决策时间戳

- 2026-05-22 D1 (今天) SSH 验证完成
- 立即推进 D2 mock generator (无须用户确认 — per goal "立即决定不纠结")
