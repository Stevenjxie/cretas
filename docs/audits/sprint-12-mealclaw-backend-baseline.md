# Sprint 12 餐饮 backend — Phase A baseline

**Date**: 2026-05-29 (Phase A.2 SSH SQL + health probe)
**Author**: sprint12-mealclaw-backend chat (worktree `my-prototype-logistics-sprint12-mealclaw-backend`)
**Branch**: `feat/sprint12-mealclaw-backend` @ `916ec774d` (2 commits ahead of `origin/main 17649817e`)
**Scope**: SQL evidence + source-data baseline before Phase B/C/D wiring work begins.

---

## TL;DR

| 指标 | Brief 假设 | 实测 (postgres RLS-bypass) | 状态 |
|---|---|---|---|
| `smart_bi_finance_data` RES_3101_009 REVENUE rows | 365 | **365** (Jan 1 – Dec 31 2025) | ✅ 一致 |
| Dec 2025 REVENUE rows | 31 | **31** | ✅ 一致 |
| `smart_bi_finance_data` RES_3101_009 **COST** rows | (PR #242 标题含 COST_FOOD) | **0** | 🔴 ETL COST_FOOD 路径未产生行 |
| `smart_bi_finance_data` F006 rows | 0 (待 backfill) | **0** | ✅ 一致 |
| `fact_pos_transaction` RES_3101_009 (source) | 140K (per spec §0.2) | **140,541** | ✅ source data 在 |
| `fact_restaurant_wastage` (Silver) RES_3101_009 | 6 | **6** (2026-03-31 → 2026-04-23) | ✅ |
| `agg_restaurant_product_cost` RES_3101_009 | 136 dishes | **136** | ✅ recipe BOM 在 |
| `wastage_records` (cretas business) RES_3101_009 | 6 | **6** | ✅ |
| `wastage_records` F006 | (unspecified) | **0** | 🔴 F006 几乎无源数据 |
| `processing_batches` table | (brief 假设存在, Phase C source) | **不存在** | 🔴 brief 假表名错 |

**核心结论**: Phase F.1 ETL **只产 REVENUE 行, COST 路径没跑/没产生行**. Phase B/C 真业务源 data 在 RES_3101_009 稀疏 (6 wastage / 0 material_batches / 0 food_samples); F006 几乎全空; 其他 factory (F001 / R_GML_DEMO / R_XMX_CHAIN) 有 POS data 但缺 wastage/cost source.

---

## 1. Evidence (verification-before-completion HARD)

### 1.1 prod 健康

```
$ curl http://localhost:10010/api/mobile/health        → 200
$ curl http://localhost:8083/health                     → 200
$ curl POST /api/mobile/ai-tools/execute (anon)         → 401 {"未授权"}
```

Composite Tool curl baseline 阻于 JWT (待 Steve 提供测试账号 OR Phase B 用 admin context 跑) — defer 到 Phase A.3.

### 1.2 `smart_bi_finance_data` 当前 state (smartbi_prod_db, smartbi_user RLS view)

```
record_type | rows | min_date   | max_date
------------+------+------------+-----------
REVENUE     |  365 | 2025-01-01 | 2025-12-31

Dec 2025 only: REVENUE = 31 rows
Sample (2025-12-01 to 12-03):
  category="营业收入", actual_amount=¥50K-¥55K/day
  total_cost=0, material_cost=0, labor_cost=0, overhead_cost=0
  upload_id=0 (ETL sentinel per spec §3.1)
  created_at=2026-05-23 (ETL backfill date)

Cross-factory: 只 RES_3101_009 有任何 row. F006 = 0.
```

**关键**: REVENUE side 有完整 365 行真实日营收数据 (~¥50K/天), 但**所有 cost columns 全 0**. PR #242 ETL COST_FOOD branch (per spec §3.1 wastage rollup + POS×recipe COGS) 在 prod 没产生过行.

### 1.3 Source data 状态 (postgres superuser, RLS-bypass)

#### smartbi_prod_db (Silver/Gold layer)

```
fact_pos_transaction RES_3101_009: 140,541 rows  ← REVENUE ETL source ✓
fact_pos_item        RES_3101_009: 646,946 rows  ← 也在
fact_restaurant_wastage RES_3101_009:    6 rows (2026-03-31 → 2026-04-23) ← wastage ETL source 仅 6
agg_restaurant_product_cost RES_3101_009: 136 dishes ← recipe BOM 在
```

#### cretas_prod_db (Bronze layer, business tables)

```
wastage_records   RES_3101_009: 6 rows (2026-03-31 → 2026-04-23)
material_batches  RES_3101_009: 0 rows
food_samples      RES_3101_009: 0 rows
processing_batches:            表不存在 (brief 假表名错)
recipes           RES_3101_009: 383 rows (BOM 配方在)
```

#### Cross-factory POS data (postgres bypass)

```
fact_pos_transaction by factory:
  F001          → 140,541 txns
  RES_3101_009  → 140,541 txns
  R_GML_DEMO    →  16,213 txns
  R_XMX_CHAIN   →     141 txns

wastage_records by factory (cretas business):
  F002          →  13 rows
  RES_3101_009  →   6 rows
  R_XMX_CHAIN   →   4 rows
  F001          →   1 row
  F006          →   0 rows
```

### 1.4 RLS investigation note

`smartbi_user` 看不见 `fact_pos_transaction` (`SELECT COUNT(*) = 0`) 即使 `SET LOCAL app.factory_id` 因 `SET LOCAL` 需 transaction block (warning 抑制了). 但 Python ETL script `restaurant_finance_etl.py` 内 `await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)` 在 transaction 内, 实际 ETL 跑成功 (365 REVENUE 行就是证据). RLS 设计 OK, 但 Sprint 12 Phase D extend ETL 时必须保持 GUC 在 transaction 内.

---

## 2. Phase B/C/D 真实可行性 (revised vs brief)

### Phase B — Wire Shrinkage sub-Tool

Brief assumed: "查 waste_records / material_batches / food_samples" 用 Java fetcher 模式.

**真实路径**:
- Python `shrinkage_analysis` section (`backend/python/smartbi/services/restaurant/sections/shrinkage_analysis.py`) 期望 caller 传 `shrinkage_rows: [{department, standardCost, actualCost}, ...]` 入 params
- Java `RestaurantShrinkageAnalysisTool` 通过 `AbstractRestaurantDiagnosticTool.buildSectionParams(factoryId, params, context)` override 组装该 dict (而非 brief 说的"新 Java fetcher class")
- 源数据组装: `标准成本 = recipe.standard_qty × dim_ingredient.unit_price`, `实际成本 = 月度盘点 OR wastage 累加`
- 现 RES_3101_009 仅 6 wastage rows + 0 material_batches + 0 food_samples + 0 stocktaking_records → Phase B 在 prod 跑能产输出但 topItems 只有 1-2 行 (单"档口"差异), demo 价值低
- F006 0 数据 → Phase B 跑 = SKIPPED ("未提供 shrinkage_rows")

**调整建议**: Phase B 实现 Java `buildSectionParams` override (~80 LOC), 但 ETL/source 同时 extend 到至少 1 个 factory 有 ≥20 wastage records, 否则 demo 没有故事.

### Phase C — Wire CostRigidity sub-Tool

Brief assumed: "查 processing_batches (人工成本/工时) / material_costs / recipe_versions"

**真实路径**:
- Python `cost_rigidity` section 期望 `financial_data: {current: {revenue, labor_cost}, previous: {revenue, labor_cost}}`
- 现 `smart_bi_finance_data` labor_cost = 0 全行
- `processing_batches` 表不存在 in cretas_prod_db → brief 假表名错
- Java `buildSectionParams` 需 query 别的 source 拼 labor_cost; 但**目前 prod 没 labor_cost 数据来源** (没 payroll_records, 没 expense_requests labor, 没 processing_batches)

**调整建议**: Phase C 阻于 labor_cost source data 缺. 三个选项:
- C.1 ETL extend 加 LABOR COST 行从 `payroll_records` (但该表 RES = 0) — 不可行
- C.2 加 cretas 端 labor input 表 (e.g. 餐饮门店人工排班) + Phase C ETL — 大工作
- C.3 Phase C scope-out Sprint 12 → 降级"未实现"标注, Composite Tool 返"成本刚性数据不可用" — 跟 Steve 决策 1 兼容

### Phase D — Cross-factory ETL extend

Brief assumed: "F006 + 其他 N factory 自动 backfill".

**真实路径**:
- Other factories with POS data 适合 ETL extend: F001 (140K txns), R_GML_DEMO (16K), R_XMX_CHAIN (141)
- **F006: 0 POS, 0 wastage** — ETL extend 产生 0 行, 不能用真实业务数据 demo
- ETL loop 工作量小 (~10 LOC factory iteration); 关键是 source data 真实性

**调整建议**: Phase D **改为 ETL extend 到 F001 + R_GML_DEMO + R_XMX_CHAIN** (有 POS data 的 factory). F006 标"无业务数据可显示" 或单独 Phase F backfill 同步真实数据.

### Phase F.1 ETL COST_FOOD 缺失 (P0 gap)

PR #242 标题 "REVENUE + COST_FOOD" 但 prod 0 COST 行. 待 Phase A.3 排查:
- COST_FOOD ETL 代码在 `restaurant_finance_etl.py` 是否真在 `run_full_etl()` 调用链?
- 是 ETL 静默跳过 (e.g. wastage = 0 → 0 COST 行) 还是 ETL 报错没插入?
- 跑 admin trigger `POST /api/restaurant-etl/trigger` 重跑能否补 COST_FOOD?

---

## 3. Composite Tool 当前预测行为 (基于 SQL 推导)

无 JWT 实测, 但基于 SQL state 可推 4 phrase baseline:

| Phrase | `summary` (P&L) | `topItems` (Shrinkage) | `recommendations` (CostRigidity) | dataAvailable |
|---|---|---|---|---|
| "上月损益分析" (默认 2026-04) | revenue=0 (Apr 2026 无数据), 跨月 N/A | SKIPPED 无 shrinkage_rows | SKIPPED 无 financial_data | **false** |
| "2025年12月哪个菜亏钱" | revenue=¥1,935,193 (31 行 sum); foodCost=0 | SKIPPED | SKIPPED | **false (部分)** ← P&L card 渲染 demo path |
| "成本分析" (默认 2026-04) | revenue=0 | SKIPPED | SKIPPED | **false** |
| "损溢分析" (默认 2026-04) | revenue=0 | SKIPPED | SKIPPED | **false** |

唯一 demo-ready phrase = "**2025年12月**" 明示 + Workdesk P&L card render (Sprint 11 B.6 path, 已 cherry-pick 在本 worktree).

---

## 4. Open questions for Steve

| # | Question | 影响 phase |
|---|---|---|
| **Q1** | Phase C labor_cost 源数据缺. 选 C.1/C.2/C.3? (C.3 = scope-out + 标注"不可用") | Phase C |
| **Q2** | Phase D extend factories 改为 F001 + R_GML_DEMO + R_XMX_CHAIN (有 POS data) 而非 brief 的 F006? F006 单独 Phase F? | Phase D |
| **Q3** | Phase F.1 COST_FOOD ETL 0 行 — Sprint 12 内修 (重跑 admin trigger + 排查 ETL log) OR 留待 sister chat? | Phase A.3 |
| **Q4** | Composite Tool curl baseline 测试需 JWT. Steve 提供 RES_3101_009 / F001 admin token, OR 我从 Java 端 mock JwtAuthenticationFilter 跑测试? | Phase A.3 |

---

## 5. Phase B/C/D revised 工时估算

| Phase | Brief 原估 | 调整后 | Δ |
|---|---|---|---|
| B Shrinkage wire | 3-5d | 3-5d (Java buildSectionParams + ~1d source data audit) | 0 |
| C CostRigidity wire | 3-5d | C.3 (scope-out + 标注) 0.5d **OR** C.2 (新加 labor source) 5-8d | 0 OR +3-5d |
| D Cross-factory ETL | 2-3d | 1-2d (factory loop simple) + 1d Phase F.1 COST_FOOD 排查 | -1d 或 平 |
| E Cache sync + ship | 1d | 1d | 0 |
| **总** | 9-14d | 6-15d (取决 Q1) | 取决 Q1 |

C.3 scope-out: 6-7d 总; C.2 全实现: 14-15d 总.

---

## 6. 已 cherry-pick 至 worktree (long-term preserved)

```
383affc40  feat(sprint-11): Workdesk render P&L card (Q6 Option B.6) — DEMO-READY
           web-admin/src/views/workdesk/WarehouseKeeperWorkdesk.vue (+214)
           docs/audits/sprint-11-mealclaw-q6-option-b6-pnl-card.md + 4 PNG
916ec774d  chore(sprint12-mealclaw): cherry-pick Playwright headed-mode rule + config
           .claude/rules/playwright-headed-mode.md (NEW 142 LOC)
           web-admin/playwright.config.ts (+44/-1, 保留 main #273 sprint11-ai-workdesk-full)
```

Skipped pieces from `worktree-mealclaw-pm-coord`:
- audit PNG / demo brief / spec docs (main 已有 不同 SHA superseded)
- Vue `cleanCachedFormattedText` 32 LOC (main PR #246 后端 fallback 层 supersede)

---

## 7. Next action (待 Steve Q1-Q4 答完)

1. 答 Q1-Q4 解锁 Phase C scope + Phase D 范围 + Phase F.1 COST_FOOD 排查 ownership
2. Phase A.3: 如 Q4 有 JWT, curl 4 phrase 真测; 如无 JWT, mock JwtAuthenticationFilter integration test 跑 Composite Tool 看现 dataAvailable=false 三维度的真 message
3. Phase B impl start: Java `RestaurantShrinkageAnalysisTool.buildSectionParams` override + 单测
4. (parallel) Phase D ETL extend factory loop (F001 / R_GML_DEMO / R_XMX_CHAIN)

---

**Phase A.2 状态**: ✅ SQL baseline + source data + RLS investigation 完. ⏸ curl baseline + COST_FOOD 缺失排查 推到 Phase A.3 待 Q3/Q4 答案. Steve answer needed to unblock B/C scope.
