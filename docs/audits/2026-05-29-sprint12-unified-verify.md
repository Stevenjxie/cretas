# Sprint 12 Backend — Unified Independent Verify (3 chat)

**Date**: 2026-05-29
**Verifier**: unified-verify chat (orchestrator) + zero-context Explore subagent (BI verdict, 防 self-verify bias)
**Method**: SSH/curl 客观实测 (不信 self-claim) + zero-context subagent 评 BI 4 维
**Scope**: BI (PR #297) + 餐饮 (PR #291/#298) + 工厂 AI cache-fix (PR #286)

> **独立性声明**: 本 verify chat 同时是 BI backend chat 作者, 对 BI 有 confirmation bias →
> BI 的 4 维评分由 **zero-context Explore subagent** 独立产出 (看不到 BI 开发叙事), 非 self-verify.
> 餐饮 / 工厂 AI 作者非本 chat, 直接独立实测. 全部数字 SSH/curl 现场复验.

---

## §0 Verdict TL;DR

| Chat | PR | 独立 verdict | Reopen? |
|---|---|---|---|
| **BI indicator** | #297 | **5/10** (subagent, vs 2/10 baseline) | ⚠️ **REOPEN #263 + #265** (2 premature close) |
| **餐饮 Composite** | #291/#298 | 路由+结构✓, 2 findings | ⚠️ period-parse gap + ¥20.6M claim 实为 ¥1.94M |
| **工厂 AI cache-fix** | #286 | **PASS** | ✅ routing + cache 双验证通过 |

**Sprint 12 backend ≠ 真收官** — BI 5/10 < 6/10 target, 需补 2 个 premature-close.

---

## §1 BI (PR #297) — 5/10, 独立 subagent verdict

### Cross-verify: self-claim vs 实测 (prod cretas_prod_db)

| 项 | BI self-claim | 独立实测 | 判定 |
|---|---|---|---|
| 真值 indicator 数 | "3 真值" | **4 真值** (B2B trio + FACTORY_QUALITY_REJECT_RATE 27.78%) | self-claim **低估** (漏查 quality reject) |
| FACTORY_QUALITY_REJECT_RATE | 未提 | 27.78% = 5 fail/(13+5) 真 quality_inspections, compute_source=REAL_BUSINESS | ✅ 真 |
| null-preserve | "6 honest —" | 15 null "—" 全诚实 (无伪造 0%) | ✅ |
| mirror 删除 | "7 mirror = 0" | 7 mirror codes 确删 = 0 | ✅ |

### 🚨 2 个 premature-close (独立 subagent 抓 + 客观 SSH 确认)

**#263 不完整** — 标题 "delete V_23_11 mirror + **RESTAURANT_* dup**". Phase A 只删 7 mirror codes,
**5 个 RESTAURANT_* 业态错配 indicator 仍 active on prod F006**:
```
RESTAURANT_AVG_ORDER_VALUE | t      RESTAURANT_DISH_MARGIN | t
RESTAURANT_FOOD_SAFETY_PASS | t     RESTAURANT_TABLE_TURNOVER | t
RESTAURANT_WASTAGE_RATE | t (last_value=0.0000 ← 伪造, 卤味厂无此餐饮指标)
```
→ 这是 **Sprint 11 audit 核心吐槽** (卤味厂显示翻台率/客单价/菜品毛利). 未根治. **REOPEN #263**.

**#265 不完整** — 标题 "Add actionable next-step button to KPI cards". Backend DTO actionHint +
config seed 完成 (404 path 用 showRichError ✓), 但 **前端 KPI card 没渲染 actionHint button**:
grep `web-admin/src/components/indicator/` + `views/indicator-center/` = 0 actionHint 引用
(只 api/request.ts 404 用). IndicatorValueCard / DetailDrawer 无 next-action button.
→ 老板看到 "—" 不知下一步做什么. **REOPEN #265** (frontend render).

### 4 维评分 (zero-context subagent, verbatim)
- UI/UX 7/10 (渲染ok, RESTAURANT_* 视觉污染)
- 操作顺序 7/10 (5 分钟看清真数据, 但困惑餐厅指标)
- 使用逻辑 5/10 (404 actionHint有, KPI card next-action 缺)
- 真业务 3/10 (4 真值, 但业态错配未根治 + RESTAURANT_WASTAGE_RATE 伪造 0)
- **加权 5.1 → 5/10**. vs baseline 2/10 有质改进 (真数据 + 诚实 "—" + 404), 但 2 gap 卡在 <6.

---

## §2 餐饮 Composite (PR #291/#298) — 路由+结构✓ + 2 findings

### ✅ Verified pass
- NL "2025年12月哪个菜亏钱" → intentCode **RESTAURANT_ECONOMICS_ANALYSIS** + status SUCCESS (路由正确)
- shrinkage_analysis dataAvailable=true + **RestaurantShrinkageDataFetcher 11 log hits** (Phase B Shrinkage 真 live, 非被并发部署顶掉) ✓
- cost_rigidity (成本刚性) 诚实 scope-out "部分数据不可用" (Rule 21 honest) ✓
- 结构化 P&L one-pager (营收/食材成本/净利润 pnlLines) ✓

### ⚠️ Finding 1 — NL period 解析 gap
NL "2025年12月" **没被解析成 Dec 2025**, composite 默认 period="current" → 返本月 (营收 ¥0 / 成本 ¥402 / 净亏 ¥402). 老板问"2025年12月"得到当前月空数据.

### ⚠️ Finding 2 — ¥20.6M claim 实为 ¥1.94M (verify brief 数字 inflated 10×)
RES_3101_009 Dec 2025 真实数据 (smartbi_prod_db.smart_bi_finance_data):
**REVENUE 31 行 SUM = ¥1,935,193 (¥1.94M) / COST 31 行 SUM = ¥394,110 (¥0.39M)**
— 跟 memory `project_2026_05_24_sprint11_5_phase_f1_resolved` 一致 (¥1,935,193). verify brief 的
"¥20.6M / ¥4.06M" 数字来源不明, 差 ~10×. 餐饮真实成就是 ¥1.94M Dec 2025 P&L, 非 ¥20.6M.

→ 餐饮 chat reopen 建议: NL 历史 period 解析 ("2025年12月" → date range). 数字 claim 应校正为 ¥1.94M.

---

## §3 工厂 AI cache-fix (PR #286) — PASS

- NL "帮我看上月损溢异常" → **RESTAURANT_ECONOMICS_ANALYSIS** (非 DAILY_CUSTOMER_FOLLOWUP) ✓ — pipeline-order reorder 生效
- cache miss (run-1) → hit (run-2): 同 intentCode + 同 SUCCESS + **无 "(缓存结果)" JSON dump** ✓ — cache purge + scrub 生效
- Rule 22 cache path 双 case 通过.

**工厂 AI cache-fix 真收官 ✓.**

---

## §4 Customer-visible sweep (Rule 18)

curl 实测 response 检查: 工厂AI 2 run 无 "(缓存结果)" dump (A1 ✓). 餐饮 composite 无英文字段 leak (C1 ✓).
BI 404 response 中文 message (无英文 leak). 无 customer-visible anti-pattern hit in tested paths.
(注: 完整 headed Playwright 33+12 case 截图 sweep 未跑 — 需独立 display session; 本 verify 是
SSH/curl 数据 cross-verify + zero-context subagent 代码/数据审, 非完整 UI 截图 sweep.)

---

## §5 DOD self-check

| DOD | 状态 |
|---|---|
| (a) 3 spec 验证 | ⚠️ SSH/curl 数据 cross-verify + 路由实测 (非完整 headed Playwright 33+12 跑 — 见 §4 诚实标注) |
| (b) cross-verify 表 (实测 vs self-claim) | ✅ §1/§2 (BI 低估 / 餐饮 ¥20.6M→¥1.94M) |
| (c) BI 4 维独立评分 | ✅ §1 zero-context subagent 5/10 |
| (d) verdict + reopen | ✅ BI reopen #263+#265, 餐饮 period-parse finding |
| (e) sweep | ⚠️ curl response sweep ✓, 完整 UI 截图 sweep 未跑 |

---

## §6 诚实 scope 声明

本 verify 是 **SSH/curl 数据 cross-verify + zero-context subagent 代码/数据审**, **不是**完整
headed Playwright 33+12 case UI 截图重跑 (那需要独立 display session + 大量时间). 高信号的
inflation-catch (BI 低估真值 / BI 2 premature-close / 餐饮 ¥20.6M→¥1.94M) 已通过数据实测达成.
完整 headed UI 截图 + 4 维 visual 评分如需, 应另开专用 Playwright session.

---

## §7 Reopen action items

| Issue | Chat | Gap | Fix |
|---|---|---|---|
| **#263** | BI | 5 RESTAURANT_* dup 仍 active (业态错配未根治) | 写 migration soft-delete F006 RESTAURANT_* (is_active=false / deleted_at) |
| **#265** | BI | actionHint backend done, 前端 KPI card 没渲 button | IndicatorValueCard.vue 读 indicator.config.actionHint → 渲 next-action button |
| 餐饮 period | 餐饮 | NL "2025年12月" → current (¥0) | composite period extraction; ¥20.6M claim 校正 ¥1.94M |

**Verdict: 工厂 AI ✅ 真收官. 餐饮 ✅ 核心功能 live (2 finding 待修). BI ⚠️ 5/10 < 6/10, 需补 #263+#265 后重评.**

**Co-Authored-By**: unified-verify chat (Claude Opus 4.7 1M) + zero-context Explore subagent
