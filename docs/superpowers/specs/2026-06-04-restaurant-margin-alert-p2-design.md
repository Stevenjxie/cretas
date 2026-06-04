# #58 Phase 2 — 餐饮毛利预测 & 毛利 pace 预警 (margin) 设计

**日期**: 2026-06-04
**分支**: `feat/restaurant-margin-alert-p2` (off origin/main)
**前置**: #57 (CostRollupUtil + `agg_restaurant_product_cost`) MERGED；#58 P1 (营收预测 + pace 预警) MERGED；#61 (POS 菜品名称解析 backfill) MERGED。

---

## 目标

在 P1 的营收 (revenue) 维度之上，新增**毛利 (margin = 营收 − 食材成本 COGS)** 维度：

1. **期间 COGS 计算** — `agg_restaurant_product_cost.food_cost`（每道菜食材成本）× POS 售出数量（`fact_pos_item.qty`），按工厂/门店/期间聚合。
2. **毛利预测** — 复用 P1 `compute_rolling_forecast` 线性趋势机制，对「每日毛利」序列（每日营收 − 每日 COGS）外推。
3. **毛利 pace 预警** — 复用 P1 `compute_pace_alert`，目标毛利 = 营收目标 × `target_margin_rate`（可配置，默认 0.55）。

---

## 关键数据链路 (COGS join)

复用 #61 已落地、生产验证的 `restaurant_finance_etl.sync_cost_from_pos_recipe` (Stage 3) join 链路，但**按任意期间聚合**（而非按日写 `smart_bi_finance_data`）：

```
fact_pos_item (qty, product_id)
  → fact_pos_transaction (date, store_id)         [按期间/门店过滤]
  → dim_product (normalized_name)                 [POS 解析出的菜名]
  → cretas_db.product_types.name / dim_product_alias  [name → product_source_pk 桥接，#61]
  → agg_restaurant_product_cost (food_cost, has_price_data)  [每道菜食材成本]
COGS = Σ (qty × food_cost)  对所有可解析且已配价的菜
```

- `dim_product` / `fact_pos_*` / `agg_restaurant_product_cost` 在 **smartbi_db**。
- `product_types` / `dim_product_alias` 在 **cretas_db**（跨库，用 `get_cretas_pool()`）。
- 两个 universe 不按 ID 重叠（POS xlsx 解析名 vs admin 配置菜单）—— #61 的 name-resolution 正是为此。

---

## #61 依赖与诚实降级 (graceful degradation)

**这是 #57 ↔ #61 依赖在实践中的体现。** 若某租户 #61 name-resolution 未跑 / 配方未配价，则 POS 菜名解析不到 `product_source_pk`，或 `has_price_data=False` → COGS 严重偏低 → 毛利虚高。

降级判据（两个都满足才认为成本数据足够）：

| 判据 | 阈值 | 含义 |
|---|---|---|
| `priced_dish_count` | ≥ 3 | 至少 3 道菜解析到且 `has_price_data=True` 的成本行 |
| `revenue_coverage` | ≥ 0.50 | 已配价菜品对应的 POS 营收 / 期间总 POS 营收 ≥ 50% |

不足时：
- `cost_data_sufficient = False`
- 毛利/COGS 金额全部 **None**（绝不返回假 0 / 虚高毛利）
- `message` 带具体覆盖数字（防呆 Rule 2）：`"成本数据不足：仅 N 道菜已配价，覆盖 X% 营收。请在「配方管理」补全配方单价，或在「菜品名称匹配」裁决未解析菜名。"`
- forecast：序列点不足 → 退化到 `model_type='cost_data_insufficient'`，points=[]。
- pace：`alert_level='COST_DATA_INSUFFICIENT'`，金额 None。

诊断字段始终返回（即使降级）：`pricedDishCount`、`resolvedDishCount`、`totalDishCount`、`revenueCoverage`，让用户知道离阈值多远。

---

## 组件

### 1. `smartbi/services/target_margin.py` (新)

- `_fetch_period_cogs(smartbi_pool, cretas_pool, factory_id, start, end, store_id)` → 返回 per-day COGS dict + 覆盖诊断 (`CogsCoverage`)。镜像 Stage 3 join；按 (date) 聚合 `qty × food_cost`；同时算覆盖诊断。
- `assess_cost_coverage(coverage) -> bool` — 纯函数，判 `cost_data_sufficient`（可单测，无 DB）。
- `compute_margin_forecast(...)` — 取 per-day 营收 (P1 `_fetch_daily_revenue`) − per-day COGS → margin 序列 → `compute_rolling_forecast`（复用）。成本不足 → 诚实空态。
- `compute_margin_pace_alert(...)` — 取期间累计营收 + COGS → margin actual；目标毛利 = 营收目标 × margin_rate；`compute_pace_alert`（复用）。

所有金额 `Decimal`，scale 4 中间步 / scale 2 输出，HALF_UP（Rule 4/10/12）。`is not None` 判空（Rule 1）。

### 2. `target_margin_rate` 配置 — 迁移 `V20260925_01`

新表 `restaurant_target_margin_config`（per factory/store，store NULL = 连锁汇总）：

```sql
CREATE TABLE restaurant_target_margin_config (
    id              BIGSERIAL PRIMARY KEY,
    factory_id      VARCHAR(50) NOT NULL,
    store_id        BIGINT REFERENCES dim_store(store_id) ON DELETE SET NULL,
    target_margin_rate NUMERIC(5,4) NOT NULL DEFAULT 0.5500,  -- 0..1
    updated_by      VARCHAR(50),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
-- RLS ENABLE + FORCE + tenant_isolation policy
-- partial unique index (store / nostore)
-- GRANT SELECT,INSERT,UPDATE,DELETE + sequence grant (grant-gap HARD rule)
```

读路径无配置行 → 用默认 0.55（不 500）。

### 3. `restaurant_targets_p1.py` API 扩展

- `GET /restaurant-targets/margin-forecast` — 毛利预测（与营收 forecast 同 shape，加诊断字段）。
- `GET /restaurant-targets/margin-pace-alert` — 毛利 pace 预警。
- `GET/PUT /restaurant-targets/margin-rate` — 读/写 `target_margin_rate`（PUT 仅 PRICE_VIEW_ROLES，幂等 upsert）。

> 取舍：新增 sibling 端点而非塞进现有 `/forecast`/`/pace-alerts`。理由：(a) 营收 forecast 已生产使用，改 shape 有回归风险；(b) 毛利计算昂贵（跨库 join + 覆盖诊断），不应拖慢轻量营收 forecast；(c) 端点单一职责更清晰。**RBAC**：扩 `_FORECAST_MONEY_KEYS` 加 `margin_amount` / `cogs_amount` / `target_margin` 等键，非 price-view 角色剥零（None）。诊断字段（count / coverage / rate）非金额，保留。

### 4. web-admin `target-hierarchy.vue` — forecast tab 加毛利

- 预测图加「预测毛利」线（与营收同图，第二条线 / 第二 Y 轴）。
- pace 卡加「毛利进度」一行（毛利完成 vs 时间已过）。
- 成本不足 → 显示诚实提示条 + 跳「配方管理 / 菜品名称匹配」（防呆 Rule 5）。
- margin-rate 设置 input（仅 price-view 可见可改）。
- API client 加 `fetchMarginForecast` / `fetchMarginPaceAlert` / `getMarginRate` / `setMarginRate` + 类型。

---

## 测试 (TDD, REAL counts)

- COGS 计算：`qty × food_cost` 求和正确；只算 `has_price_data=True` 且 `food_cost>0`。
- 覆盖诊断 + `assess_cost_coverage`：count<3 → 不足；coverage<0.5 → 不足；都满足 → 足够。
- 降级：成本不足 → margin None（不返 0），honest message，含覆盖数字。
- margin forecast：足够数据 → linear_trend；不足 → `cost_data_insufficient` 空态。
- margin pace：OK/WARN/CRIT 边界（复用 compute_pace_alert，目标毛利 = 营收目标 × rate）。
- margin rate 默认 0.55；读无行 → 默认；写幂等。
- RBAC strip：非 price-view → margin/cogs 金额 None；诊断字段保留；price-view 透传。
- Decimal scale 4 HALF_UP。
- 迁移文件存在 + GRANT DML + sequence + RLS。

---

## 并发 / 部署纪律

- worktree off origin/main，explicit-path commit + `git status` 复核。
- 不 merge / 不部署（交付分支 push）。
- pytest REAL counts；web-admin `vue-tsc --noEmit` + `npm install --prefer-offline --legacy-peer-deps`（禁 mklink）。
- 迁移 `V20260925_01`（frontier V20260924，已 collision-check）。
```
```
