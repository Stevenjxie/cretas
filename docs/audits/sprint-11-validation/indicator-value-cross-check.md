# Item 1: F006 Indicator 数据真实性 Cross-Verify

**Date**: 2026-05-23
**Skill**: verification-before-completion HARD (fresh-command evidence)
**Verdict**: 🔴 **BLOCKER — Mirror data 100% 不真**

---

## 1. Real F006 source table values (fresh SSH SQL, 2026-05-23)

```sql
-- F006 sales_orders (源 for AVG_TICKET_PRICE)
SELECT count(*), AVG(total_amount/item_count) FROM sales_orders
WHERE factory_id='F006' AND deleted_at IS NULL AND item_count > 0;
```

| Table | rows | avg / metric |
|---|---|---|
| **sales_orders (F006)** | **5** | avg per order ¥1,225,510 (B2B 工厂订单) |
| finished_goods_batches (F006) | 1 | (insufficient for FACTORY_YIELD_RATE) |
| quality_inspections (F006) | (0 verified visible) | (insufficient for FOOD_SAFETY_PASS_RATE) |
| production_batches (F006) | (need re-query) | (insufficient) |

---

## 2. Indicator Tool returned values (post-V_23_11 mirror)

```sql
SELECT i.code, count(*), avg(iv.value) FROM indicators i
JOIN indicator_versions iv ON iv.indicator_id=i.id
WHERE i.factory_id='F006' AND i.code IN (4 BI codes) GROUP BY i.code;
```

| indicator_code | versions | mirror avg | Source per indicators table |
|---|---|---|---|
| AVG_TICKET_PRICE | 30 | **¥37.73** | `mirrored_from_F999_MOCK` (per V_23_11) |
| TABLE_TURNOVER | 30 | **2.05** | `mirrored_from_F999_MOCK` |
| FOOD_SAFETY_PASS_RATE | 30 | **99.02%** | `mirrored_from_F999_MOCK` |
| DISH_GROSS_MARGIN | 30 | **39.69%** | `mirrored_from_F999_MOCK` |

---

## 3. Cross-verify verdict

| Indicator | F006 真源 X | Tool Y | |X-Y|/X | Verdict |
|---|---|---|---|---|
| AVG_TICKET_PRICE | ¥1,225,510 (per-order B2B avg) | ¥37.73 (餐厅客单价 mirror) | **>99%** | 🔴 **BLOCKER** |
| FOOD_SAFETY_PASS_RATE | quality_inspections 数据不足 (≤0 rows verified) | 99.02% mirror | N/A | 🔴 **BLOCKER** |
| TABLE_TURNOVER | F006 是工厂不是餐厅, 无翻台概念 | 2.05 mirror | N/A | 🔴 **BLOCKER (业态错配)** |
| DISH_GROSS_MARGIN | F006 是工厂, 无菜品概念 (有产品成本但不叫毛利率) | 39.69% mirror | N/A | 🔴 **BLOCKER (业态错配)** |

---

## 4. Root cause

V_23_11 migration (commit 6647c0f2b, 2026-05-22) 把 **F999_MOCK 餐厅业态的 7 indicator + 210 versions** 全 INSERT 给 F006 (六腾门卤味厂), 通过 `compute_source = 'mirrored_from_F999_MOCK'` 标记.

这是 unblock SalesOwner Workdesk UI demo 的临时手段 (per Sprint 11 spec docs/sprint-11/data-source-decision.md "Layer 4 mock 默认数据源"). **当时已认知 F006 真实数据不足**, 这不是 bug — 是产品阶段决策。

但 **AI 工厂 chat 在 SalesOwner Workdesk UI 上把这数字标 "F006 真数据"** (IndicatorCard.vue "来源: BI IndicatorQueryTool · F006 真数据"), **这是 product overstatement, 是 bug**.

---

## 5. Impact

| Layer | 实际 | Workdesk UI 标 |
|---|---|---|
| 数字 ¥37.39 | F999_MOCK 餐厅 30 天历史均值 | "F006 真数据" |
| 业态 | F006 是工厂 (六腾门卤味厂) | 餐厅指标 (客单价/翻台率/菜品毛利) 完全错配 |
| F006 老板看到 | 一堆跟自己业务无关数字 | 以为是自己工厂经营数据 |
| 防幻觉 protocol | 应明确数据源 = "F999_MOCK 镜像示例" | 标 "F006 真数据" 是反向 |

---

## 6. Decision + Sprint 12 backlog

**Sprint 12 必修**:
1. SalesOwner Workdesk 4 IndicatorCard 加 demo banner: "示例数据 (F999_MOCK 模板), F006 真接入待 Sprint 12+"
2. OR 切到工厂业态正确指标 (FACTORY_YIELD_RATE / RAW_WASTAGE_RATE / FACTORY_PLAN_ACHIEVE_RATE 等 F006 已 mirror)
3. IndicatorQueryService 实施真 algorithm — 从 F006 源表 sales_orders/production_batches/quality_inspections 实算, 不再 mirror
4. PR #199 V_23_11 compute_source 改 'demo_mirror_pending_real_compute' (avoid future overstatement)

**Sprint 11 当前评分修正**: ~~30%~~ → **~10%** (UI 渲染 LIVE 但 数据全是镜像 demo, 业务真接入完全没做)

---

## 7. Evidence files
- This doc: `docs/audits/sprint-11-validation/indicator-value-cross-check.md`
- SQL queries: see § 1, 2
- Source code: V_23_11 migration (`backend/.../V20260823_11__f006_indicator_seed_and_priority_bump.sql`)
- UI screenshot misleading "F006 真数据" 标: `docs/audits/sprint-11-demos/d7-salesowner-indicators-live.png` (uncommitted, Item 5 commit)
