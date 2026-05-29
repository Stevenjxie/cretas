# Sprint 12 BI Backend — Verify Chat Handoff

**To**: organizer → 独立 zero-context verify chat
**From**: BI backend chat (PR #297)
**Date**: 2026-05-29
**Per**: brief anti-goal "❌ Self-verify 自己 fix — 必须 organizer 派新 verify chat (独立) 重跑"

---

## 任务

独立 (zero-context) 重跑 BI Indicator Center E2E spec, 验证 Sprint 12 backend rewrite 是否
让 F006 老板能用度从 2/10 (Sprint 11 audit) 提升到 ≥6/10。**不要信本 chat 的 self-claim**,
独立评分。

## 前置

1. PR #297 已 merge main + prod 10010 deploy 完成 (确认: `gh pr view 297 --json state`)
2. prod F006 数据现状 (SSH `cretas_prod_db`): production_batches/quality_inspections 可能空 →
   多个 indicator 显示 "—" 是**预期诚实行为** (null-preserve), 不算 fail

## 重跑 spec

```bash
cd web-admin
# 33 case BI indicator full E2E (Sprint 11 audit 同一 spec)
npx playwright test tests/e2e-customer-journey/sprint11-bi-indicator-full.spec.ts \
  --config playwright-bi.config.ts
```
账号: `f006_admin / 123456` (factoryId F006), prod 8086 (web-admin) or test.

## 评分维度 (per Sprint 11 audit §3 4-dim, 独立 reviewer)

| Dim | 检查 | Sprint 11 baseline | Sprint 12 target |
|---|---|---|---|
| A UI/UX | mirror cards 消失? 真值 cards 显示? "—" 有 actionHint? | 2/10 (9 ghost cards) | ≥6 |
| B 操作 | 5 秒看核心 KPI? actionHint 跳转可用? | — | ≥6 |
| C 逻辑 | AIChat "库存价值多少" 路由对? (SMART intent) | 0/10 (100% 错路由) | ≥6 |
| D 老板能用度 | 5-min 决策? 卤味业态匹配? | 2/10 | ≥6 |

## 重点验证 (本 chat backend 改动)

1. **mirror 真删**: F006 不再有 AVG_TICKET_PRICE/TABLE_TURNOVER/FACTORY_YIELD_RATE 等 7 mirror codes
2. **真值 cards**: B2B_AVG_ORDER_VALUE (¥5175 test / prod 看真单) / FACTORY_INVENTORY_VALUE (¥50095) 等显示真数字
3. **null-preserve "—"**: quality/haccp/卤味 数据空时显示 "—" (不是 "0%" 误导)
4. **404 四位一体**: 访问不存在 indicator → sticky toast + "返回指标中心" 按钮
5. **SMART intent**: AIChat 输入 "今天库存价值多少" → 路由到 indicator (不再 DAILY_CUSTOMER_FOLLOWUP / "未找到意图配置")
6. **banner**: 无 "Sprint 12/backend/IndicatorQueryService" dev jargon, 无 "F999_MOCK 镜像" false 陈述

## 独立 Critic (per depth-first Rule 9)

派 zero-context Explore agent 跑 §5 重点 + 给 verbatim verdict (老板能用度 X/10)。
**不准用本 chat 的 evidence 当结论** — 独立 SSH `cretas_prod_db` + Playwright 实测。

## 已知诚实 gap (不算 fail, 是预期)

- F006 prod production_batches 空 → 出品率/单位成本/日均产量 "—" (等老板录入)
- 卤汁损耗率/准时交货率 skip (列名/category 缺, Steve 后续补)
- NL routing 12/12 质量依赖 PR #286 (已 rebase 入 #297) + skill prompt 调优

## Evidence chain

- 本 chat 实现 close: `docs/audits/2026-05-29-sprint12-indicator-rewrite-final.md`
- 卤味 KPI draft: `docs/sprint-12-backlog/loulu-kpi-draft.md`
- PR #297 description (10 issues 映射)
