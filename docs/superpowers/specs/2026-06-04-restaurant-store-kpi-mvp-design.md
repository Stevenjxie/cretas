# 店长经营 KPI 看板 (single-store 直营 MVP) — Design

**日期**: 2026-06-04
**分支**: `feat/restaurant-store-kpi-mvp`
**范围**: 单店直营 MVP — 店长 6-KPI 经营看板 (P1)。Steve 明确选小 MVP，**不建**多店/加盟 schema、`user_managed_stores` 映射、新餐饮角色、`X-Store-Scope` 网关。单店 = 工厂级聚合，复用现有 gold + RBAC。

---

## 0. origin/main 现状核对 (read-only agent 设计的 X exists 必须 re-verify)

| 任务 brief 说 | origin/main 实际 | 处置 |
|---|---|---|
| 存在 "组织KPI/role-kpi" 设计文档 | **不存在** (grep specs 无命中) | 用 brief 给的 6-KPI fallback 列表 |
| `store_kpi_dashboard.py` 是 stub，要求喂 dicts | ✓ 存在 (3 维度健康度: financial/operational/external，无任一 dict → SKIPPED) | 改造为 self-query gold，保留 dict 喂入向后兼容 |
| 要新建 `StoreKpiDashboardTool.java` | **已存在** (diagnostic 包，绑 section `store_kpi_dashboard`，返回 3 维度) | 扩展现有 tool (转发 X-User-Role + follow-ups)，不新建 |
| Flyway frontier V20260926_01，可能被抢到 927 | Java flyway dir frontier = `V20260926_01`，无 927，无重复 | `V20260927_01` 安全 |
| 复用 `restaurant_ops_gold.py` query helpers | 实际是 `smartbi/api/restaurant_ops_gold.py` (API router) + `smartbi/gold/queries.py` + `smartbi/gold/restaurant_ops_router.py` (resolver) | 复用 queries.py + resolver，不重写 SQL |

---

## 1. 6 个店长 KPI 数据来源 (全部复用现有 helper)

| KPI | 公式 | 来源 helper / 表 | 敏感 🔒 |
|---|---|---|---|
| 日营收 | `SUM(agg_daily.net_amount) / day_count` (日均) + 区间总营收 | `queries.kpi_summary` → `revenue` / `day_count` | 🔒 金额 |
| 客单价 | `revenue / bill_count` | `queries.kpi_summary` → `avg_bill_value` | 🔒 金额 |
| 订单数 | `SUM(agg_daily.bill_count)` | `queries.kpi_summary` → `bill_count` | 否 (计数) |
| 毛利率 | `平均毛利率` (仅有成本菜口径) + `has_price_data` 诚实标注 | `restaurant_ops_router.resolve_gross_margin` → kpis[平均毛利率].rawValue + meta{total_dishes, missing_cost_count} | 比率本身不剥；总营收/总毛利金额剥 |
| 食材成本率 | `requisition_cost_total / net_amount` | `agg_restaurant_daily_totals.requisition_cost_total` (SUM) ÷ `agg_daily.net_amount` (SUM) | 比率本身不剥；分子 requisition_cost 金额剥 |
| 目标完成率 | `实际营收 / target_value` by period_key | `queries.daily_achievement_summary(kpi_kind=revenue, level=month)` | 否 (比率) |

**毛利率诚实性**: `resolve_gross_margin` 返回 `total_dishes` / `missing_cost_count`。
`dish_count_with_cost = total_dishes - missing_cost_count`。
- 若 `total_dishes == 0` 或 `dish_count_with_cost == 0` → KPI status = `INSUFFICIENT`，value 显示 "成本数据不足"，不编造率。
- 否则 value = 平均毛利率，附 `priced_ratio = dish_count_with_cost / total_dishes`，UI 提示 "基于 X/Y 个有配方成本的菜品，±15% Layer1 精度"。

**食材成本率诚实性**: requisition_cost_total / revenue 是真实领料成本占营收比 (Layer1)，与配方理论 COGS 不同口径，文案明确为"领料成本率"。若 revenue=0 → INSUFFICIENT。

---

## 2. 健康度阈值 (per KPI badge: GOOD绿 / WARNING黄 / CRITICAL红)

固定阈值 (MVP，无需配置表)，目标完成率走 `restaurant_alert_config`：

| KPI | GOOD | WARNING | CRITICAL |
|---|---|---|---|
| 日营收 | — (信息型，无阈值 → GOOD) | — | — |
| 客单价 | — (信息型 → GOOD) | — | — |
| 订单数 | — (信息型 → GOOD) | — | — |
| 毛利率 | ≥ 55% | 40–55% | < 40% |
| 食材成本率 | ≤ 40% | 40–50% | > 50% |
| 目标完成率 | ≥ warn_threshold | crit ≤ x < warn | < crit_threshold；**无 alert_config → GOOD + config_exists=false** |

目标完成率阈值优先读 `restaurant_alert_config (kpi_kind=revenue, level=month)` 的 `warn_threshold`/`critical_threshold`；
无配置 → 默认 warn=0.9 / crit=0.7 (与 alert_preview fallback 一致)，并标 `config_exists=false`。
**目标未配置** (无 target row) → status = `NO_TARGET`，UI 走防呆 Rule 5 (去配置目标按钮)。

overall_health = CRITICAL > WARNING > GOOD (取最差的非信息型 KPI)。

---

## 3. 架构 — 单一 compute 函数，两个调用入口

为避免重复 SQL，引入**共享 compute 函数** `compute_store_kpi_dashboard(pool, factory_id, date_range, role) -> dict`，放在 section 文件内 (或 helper)。两个入口都调它：

```
入口 A (web-admin 直连):  GET /api/smartbi/gold/store-kpi-dashboard
   gold_reads.py → compute_store_kpi_dashboard → _apply_rbac_strip(role from JWT)

入口 B (AI Tool / 月末复盘):  StoreKpiDashboardTool.java
   → callRestaurantSection("store_kpi_dashboard", req+X-User-Role)
   → restaurant_sections.compute_section → StoreKpiDashboardHandler.compute
   → (self-query gold path) compute_store_kpi_dashboard → RBAC strip by role-in-params
```

**为什么共享 compute 而非各写**: 复用 python-java-port rule + DRY；两入口同一数据形状，便于测试。

### 3.1 Section handler 改造 (向后兼容)

`StoreKpiDashboardHandler.compute`:
1. 若 `params` 含 `financial`/`operational`/`external` 任一 → **保留旧 3 维度逻辑** (向后兼容，已有调用方/测试不破)。
2. 否则 → **self-query gold**：`asyncio.run(compute_store_kpi_dashboard(...))` (value_summary.py 同款 async 桥接)，返回 6-KPI 形状。
3. role 从 `params.get("role")` 读 (Java tool 经 buildSectionParams 注入)，传给 compute 内做 RBAC strip。
4. 数据全空 (无 agg_daily 行) → SKIPPED "未检测到经营数据，请先上传 POS/财务数据"。

### 3.2 RLS 租户上下文 (关键，FORCE RLS 不设 GUC 返 0 行)

`agg_daily` / `agg_restaurant_daily_totals` / `agg_restaurant_product_cost` 均 **FORCE ROW LEVEL SECURITY**，policy `USING (factory_id = current_setting('app.factory_id'))`。
- 入口 A (gold_reads via FastAPI)：JWT 中间件已 `set_factory_id` 进 contextvar，pool setup 自动 SET → 无需手动。
- 入口 B (section via asyncio.run)：section dispatch 是同步路径，contextvar 可能未设。compute 内部**首行** `set_factory_id(factory_id)` (contextvars 在 asyncio.run 内继承调用线程 context)，pool setup 读到。`resolve_gross_margin` 内部已自带 `set_config app.factory_id`，daily_achievement_summary 自带 `_set_target_tenant`，故主要是 kpi_summary + daily_totals 查询需 GUC。

### 3.3 RBAC strip (金额剥零，比率/计数保留)

复用 `smartbi_compat._rbac_strip.strip_price_for_role` (gold_reads `_apply_rbac_strip` 同款)。
- 金额字段 (日营收 value/rawValue、客单价 value/rawValue、毛利率卡的总营收/总毛利、食材成本率卡的 requisition_cost 分子) → 非 PRICE_VIEW_ROLES 置 `null` (非 0，保留 missing-vs-zero)。
- 比率 (毛利率%、食材成本率%、目标完成率%)、计数 (订单数)、健康 badge → **保留可见**。
- fail-closed: role=None/未知 → 剥。
- **缓存安全**: section `_cache` 的 cache_key 必须含 role (否则非 PRICE 角色缓存被 PRICE 角色读到剥零数据)。改 `cache_key` override 加 role 维度，或 compute 后 strip 在 cache 之外做。MVP 选: section handler 对自查路径**不进 _cache** (跳过 cache，每次重算) 以彻底规避缓存投毒；自查 gold 已快 (单 factory 聚合)。

---

## 4. Intent migration `V20260927_01__store_kpi_intents.sql`

绑定关键词 经营看板 / 我的看板 / 店长KPI / 门店经营情况 → `restaurant_store_kpi_dashboard` tool。

```sql
INSERT INTO ai_intent_config (id, intent_code, intent_name, intent_category,
  tool_name, keywords, is_active, sensitivity_level, business_type, priority)
VALUES (gen_random_uuid(), 'RESTAURANT_STORE_KPI_DASHBOARD', '店长经营KPI看板', 'RESTAURANT',
  'restaurant_store_kpi_dashboard',
  '["经营看板","我的看板","店长KPI","门店经营情况","经营KPI","店长看板"]',
  true, 'LOW', 'RESTAURANT', 115)
ON CONFLICT (intent_code) DO UPDATE SET tool_name = EXCLUDED.tool_name, keywords = EXCLUDED.keywords, ...;
```

注释说明: 新意图需 embedding backfill 才能走语义层 (keyword/phrase 匹配立即可用)。
列名以现有 `ai_intent_config` 表实际 schema 为准 (实现时 re-verify business_type / priority 列存在)。

---

## 5. web-admin view `role-kpi-dashboard.vue`

- 路径 `/restaurant/analytics/role-kpi` (router name `RestaurantRoleKpiDashboard`)，侧边栏「餐饮运营」组加「经营看板」。
- 6 KPI cards grid + per-card 健康 badge (el-tag: GOOD success / WARNING warning / CRITICAL danger) + 目标完成率 progress bar。
- 金额剥零 (null) → 显示 "—" (非 0)。无 PRICE 权限的金额卡显示 "—" + "无价格权限"。
- 数据获取: `pythonFetch('/api/smartbi/gold/store-kpi-dashboard?factory_id=...')` (JWT 转发 role → Python RBAC)。
- 防呆 Rule 2: 每卡 header 带 店名 + 期间 + 角色 (page header 三元素)。
- 防呆 Rule 5: 目标完成率 NO_TARGET / config 缺 → "去配置目标" 按钮 → `router.push('/restaurant/analytics/targets')`。
- 毛利率 INSUFFICIENT → 卡显示 "成本数据不足" + "去录入配方" 提示。
- 复用既有 KPI card 视觉 (el-card / el-statistic 风格，对齐 store-comparison.vue)。

---

## 6. 测试

**pytest** (`backend/python/tests/test_store_kpi_dashboard.py`):
- compute 6 KPI 正常路径 (mock kpi_summary / resolve_gross_margin / daily_totals / achievement)。
- 毛利率 has_price_data=false (dish_count_with_cost=0) → INSUFFICIENT，不编率。
- 食材成本率 revenue=0 → INSUFFICIENT。
- 目标完成率 无 target → NO_TARGET；无 alert_config → 默认阈值 + config_exists=false。
- 健康阈值边界 (毛利 55/40、成本率 40/50)。
- RBAC strip: 非 PRICE role → 金额 null，比率/计数保留；PRICE role → 全可见。fail-closed role=None。
- 向后兼容: 喂 financial dict → 旧 3 维度路径仍工作。

**Java** (`StoreKpiDashboardToolTest`):
- tool 装配 section params (factory_id + role 注入) + 调 callRestaurantSection。
- formatResult / followUps。

**Headed E2E plan** (qhj_prod RES_3101_009，prod 8086，不实跑除非快)：written plan，本地/test 验证足够。

---

## 7. Discipline

- worktree off origin/main (已建 `../cretas-storekpi`)。
- 里程碑 commit，commit 前 `git status`，`git commit -- <explicit paths>`。
- web-admin deps: `npm install --prefer-offline --legacy-peer-deps` (禁 mklink /J)。
- 不部署 prod。PR to main。
