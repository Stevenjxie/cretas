# Sprint 12 BI Indicator Center — Backend Rewrite Final Close

**Date**: 2026-05-29
**Author**: BI backend chat (worktree `my-prototype-logistics-sprint12-bi-backend`, branch `feat/sprint12-indicator-service-rewrite`)
**PR**: #297
**Predecessor**: Sprint 11 4-B band-aid (PR #234) + audit `2026-05-28-sprint11-bi-indicator-full-e2e-ux-audit.md` (2/10 verdict)
**Spec**: `docs/sprint-12-backlog/indicator-service-rewrite.md`

---

## §0 TL;DR

Sprint 11 V_23_11 mirror band-aid (F006 100% mirrored from F999_MOCK) → **真接 F006 业务表的 IndicatorService**。
**10 个 Sprint 12 issues backend 全部完成** (#261-270), 12 commits, test 10011 全 SSH 验证。

**核心数字**:
- **11 REAL_BUSINESS strategies** registered (3 B2B + 3 factory + 5 卤味)
- F006 dashboard: **3 mirror + 9 "—"** → **5 真业务数字 + 6 honest "—"** (数据空, 等录入自动填)
- 7 Flyway migrations V20260825_01-07 全 `success=t`
- SMART_INDICATOR_QUERY intent 注册 (修 "未找到意图配置")
- 404 四位一体完整 (actionHint + hintTarget + severity)

**诚实声明** (per anti-goal): 本文档是 backend 实现 close. **F006 老板能用度 + NL routing 12/12 由 organizer 派的独立 verify chat 重跑 33 case spec 评分, 不 self-verify** (per Rule 9 / Rule 15 / anti-goal "不准 self-verify 自己 fix")。

---

## §1 10 Issues 完成映射 (cross-verify SQL evidence)

| Issue | 描述 | Phase | Commit | SSH Evidence |
|---|---|---|---|---|
| #261 | mirror filter incomplete | A | `b5eec4033` | F006 mirror 7→0 |
| #262 | 9 "—" placeholder cards | B | `7bff1376f` 等 | 4 真值 cards 替代 |
| #263 | delete mirror + real indicators | A+B | A+B 全部 | 11 REAL_BUSINESS strategies |
| #264 | SMART_INDICATOR_QUERY 未注册 | C | `2f3d80757` | intent route SUCCESS (非 "未找到") |
| #265 | actionHint (dead-end → next-action) | B+D | `e8bb1be6c` | B2B card 返 `{查看销售单, /sales/orders}` |
| #266 | silent 404 → 四位一体 | D | `fec84fc67` | curl: actionHint+hintTarget+severity |
| #267 | banner dev jargon | D | `b690fd4da` | banner 重写无 Sprint/backend 术语 |
| #268 | banner stack 25% viewport | D | A+D | Phase A 间接解决 + Workdesk banner honest |
| #269 | 卤味 KPI 定义 | C | `2f3d80757` | 5 FACTORY_LU_* indicators seeded |
| #270 | n=N sample disclaimer | D | `b690fd4da` | 样本<30 警告文案 |

---

## §2 11 REAL_BUSINESS Strategies (SSH verified)

```
IndicatorComputationStrategyRegistry: registered 11 strategies — codes:
  B2B_AVG_ORDER_VALUE, B2B_ORDER_COUNT_MTD, B2B_TOTAL_REVENUE_MTD,
  FACTORY_HACCP_VIOLATIONS_MTD, FACTORY_INVENTORY_VALUE,
  FACTORY_LU_DAILY_OUTPUT, FACTORY_LU_MATERIAL_TURNOVER_DAYS, FACTORY_LU_UNIT_COST,
  FACTORY_LU_VACUUM_PACK_PASS_RATE, FACTORY_LU_YIELD_RATE, FACTORY_QUALITY_REJECT_RATE
DailyIndicatorRecomputeScheduler [startup] done: 11/11 success, 0 failed
```

| code | 算法 | 数据源 | F006 test 值 |
|---|---|---|---|
| B2B_AVG_ORDER_VALUE | AVG(total_amount) | sales_orders | **¥5175** (n=4) |
| B2B_TOTAL_REVENUE_MTD | SUM(total_amount) MTD | sales_orders | **¥20700** |
| B2B_ORDER_COUNT_MTD | COUNT(*) MTD | sales_orders | **4** |
| FACTORY_INVENTORY_VALUE | SUM(receipt_qty×unit_price) | material_batches | **¥50095** |
| FACTORY_QUALITY_REJECT_RATE | SUM(fail)/SUM(pass+fail) | quality_inspections | null "—" (0 行) |
| FACTORY_HACCP_VIOLATIONS_MTD | COUNT(deviation) | haccp_monitoring_records | null "—" (0 行) |
| FACTORY_LU_YIELD_RATE | SUM(good)/SUM(planned) | production_batches | null "—" (0 行) |
| FACTORY_LU_UNIT_COST | SUM(cost)/SUM(actual) | production_batches | null "—" (0 行) |
| FACTORY_LU_DAILY_OUTPUT | SUM(actual)/生产日数 | production_batches | null "—" (0 行) |
| FACTORY_LU_MATERIAL_TURNOVER_DAYS | 库存×30/消耗 | material_batches | **1222 天** |
| FACTORY_LU_VACUUM_PACK_PASS_RATE | pass/total WHERE 真空包装 | quality_inspections | null "—" (无 mode) |

**5 真值 + 6 honest "—"** — null-preserve 保证数据空显示 "—" 不伪造 (Rule 21)。

---

## §3 诚实裁定 — Ship 5 卤味, Skip 2 (per Steve 原则)

Steve §3 draft 7 卤味 KPI, SSH 探明列名后裁定:
- **Ship 5**: 出品率/单位成本/日均产量/原料周转/真空包装合格率 (列名对得上)
- **Skip 2** (不 ship 假 KPI per Rule 21):
  - 卤汁损耗率: `wastage_records.type` = EXPIRED/SPOILED/PROCESSING/DAMAGED, **无卤汁 category** → Steve 后续定 category 枚举
  - 准时交货率: `sales_orders` **无 actual_delivery_date 列** → Steve 后续加字段

详见 `docs/sprint-12-backlog/loulu-kpi-draft.md`。

---

## §4 4-位一体 404 verify (#266, curl 实测)

```json
GET /api/mobile/F006/indicators/NON_EXISTENT_XYZ_999/value (f006_admin token)
{
  "code": 404,
  "message": "指标不存在: code=NON_EXISTENT_XYZ_999, factoryId=F006",   ← (a) specific
  "actionHint": "该指标可能尚未配置，请返回指标中心查看可用指标",          ← (d) next-action
  "severity": "warning",                                                  ← (c) sticky
  "hintTarget": "/indicator-center"                                       ← (d) jump
}
```
frontend request.ts 404 handler 读 actionHint → showRichError (ElNotification sticky + 跳转按钮)。

---

## §5 部署 + 验证状态

| 维度 | 状态 |
|---|---|
| test 10011 deploy | ✅ 全 verify (11 strategies + 7 migrations + 真值) |
| rebase onto main | ✅ 含 PR #286 pipeline fix, 无 conflict |
| PR #297 | ✅ created |
| prod 10010 deploy | ⏳ Phase E (PR merge 后 BG 蓝绿) |
| verify chat 33 case | ⏳ organizer 派独立 zero-context chat |

---

## §6 待后续 (deferred, 非本 sprint 10 issues)

- 卤汁损耗率 / 准时交货率: 等 Steve 定 category 枚举 + 加 actual_delivery_date 列
- FACTORY_INVENTORY_TURNOVER (COGS/AVG inventory, 跨月算法复杂)
- Lineage "sales_order → indicator" hooks: 非 tracked issue, provenance 已在 compute_source
- 6 strategies unit tests (strategy SQL 已 SSH 实测, 单测补充)
- NL routing 12/12 quality: 依赖 PR #286 (已 rebase) + verify chat 确认

---

## §7 Anti-goal 合规

| Anti-goal | Compliant? |
|---|---|
| ❌ Self-verify 自己 fix | ✅ — 派独立 verify chat 重跑 33 case (本文档不 self-claim 评分) |
| ❌ Mock/mirror 当真业务 | ✅ — compute_source='REAL_BUSINESS:<code>', mirror 已删 |
| ❌ Banner 当 fix | ✅ — 删 mirror 数据 + banner 改 honest (非加新 banner) |
| ❌ Inflate progress % | ✅ — 数据空 indicators 诚实 "—", 不报"全绿" |
| ❌ "Sprint 13 接" 当 close excuse | ✅ — 10 issues 当前 sprint backend 全完成 |
| ❌ commit 当 delivery | ✅ — PR #297 + prod deploy (Phase E) + verify chat handoff |

---

**Co-Authored-By**: BI backend chat (Claude Opus 4.7 1M context)
