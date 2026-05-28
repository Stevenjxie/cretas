# Sprint 11 BI Chat — Final Handoff Book

**Date**: 2026-05-28
**Status**: 真 close (Steve approval 2026-05-28)
**Final score**: 22% → **30%** (audit + 3 polish PRs)
**Worktree**: `my-prototype-logistics-sprint11-d5` (保留 until Steve cleanup)
**Reopen trigger**: Sister AI 工厂 chat Sprint 12 `indicator-service-rewrite` ship 完 → Steve dispatch verify chat

---

## 1. 完整 PR 列表 (8 to main)

| # | PR | Commit | Scope | What |
|---|---|---|---|---|
| 1 | #234 | `3430d5ab2` | `/indicator-center` | 4-B band-aid: B2BRealDataSection + filter 7 mirror codes + 大字 banner |
| 2 | #237 | `420e4fdb3` | docs | Sprint 11 close doc + Sprint 12 ownership handoff |
| 3 | #241 | `2c85c263e` | docs/worktree | Punch list (git reset + hash footnote + .webm waiver) |
| 4 | #243 | `102e0714b` | audit | Deep audit 22% honest verdict (6 维度 cross-check) |
| 5 | #249 | `646ca3525` | `/workdesk` | Workdesk Dim 1 fix (撒谎 header "F006 真数据" 删除) |
| 6 | #255 | `d3aaf707a` | tests | E2E spec 10 scenarios (Dim 6 #4 fix) |
| 7 | #257 | `1b91585e9` | screenshots | 1920×1080 desktop screenshots (Dim 6 #5 双适配) |

Plus pre-Sprint-11-BI-close PRs (#192/#199/#200/#217/#222/#228/#231/#232) — see audit doc §1 timeline.

---

## 2. 6 维度评分 (Final)

| 维度 | Audit baseline | Final (polish 后) | Delta | Owner for upgrade |
|---|---|---|---|---|
| 1 Workdesk 哲学 | 3/10 | **6/10** | +3 | (BI done; further upgrade needs sister Sprint 12 NL routing) |
| 2 GuanData 5 specs | 1/10 | 1/10 | 0 | **Long-term (Phase 3, Month 3)** — design docs 还没写 |
| 3 F006 真业务数据 | 2/10 | 2/10 | 0 | **Sister AI 工厂 chat Sprint 12** indicator-service-rewrite |
| 4 食品垂直 (卤味) | 2/10 | 2/10 | 0 | **Steve** 需求定义 (出品率 / 卤汁损耗 / 真空包装合格率) |
| 5 AI × BI 融合 | 1/10 | 1/10 | 0 | **Sister AI 工厂 chat Sprint 12** NL routing + SMART intent 注册 |
| 6 Indicator Center 完成度 | 4/10 | **6/10** | +2 | (BI done; remaining Alert scheduler needs sister) |
| **Total** | **13/60 = 22%** | **18/60 = 30%** | **+5pp** | — |

BI chat self-polish 提升 5pp (22% → 30%). 剩 70% 全 BI scope 外 (sister + Steve + long-term).

---

## 3. 现状 — 真 ship 在 prod 8086

### `/indicator-center` (PR #234 + #241 + #243 + #249 + #255 + #257)

- ✅ 大字 banner "客户演示模式 · Sprint 12 接 backend 真算法"
- ✅ B2BRealDataSection 3 KPI cards (订单总数 5 / 平均订单金额 ¥1,225,510 / 销售总额 ¥6,127,550)
  - 数据源: `sales_orders` table, factory_id=F006, 5 rows (SSH 47 SQL verified)
  - 前端 reduce compute (band-aid, 不动 backend)
- ✅ 7 V_23_11 mirror codes 过滤隐藏 (UI 显 10 cards, API 仍返 17)
- ✅ Tree view 用 "已计算/待配置" honest labels (替代 "正常/关注/告警 全 0")
- ✅ Mobile 320/375 + Desktop 1920 三视口截图 evidence

### `/workdesk/sales-owner` (PR #249)

- ✅ B2BRealDataSection 替代 4 个 mirror IndicatorCards
- ✅ 撒谎 header "来源: BI IndicatorQueryTool · F006 真数据" 已删除
- ✅ 大字 banner + 临时方案 tag visible

### Audit + waiver docs in main

- `docs/audits/sprint-11-bi-prod-live.md` (PR #217 + hash footnote PR #241)
- `docs/audits/sprint-11-bi-deep-audit.md` (PR #228)
- `docs/audits/sprint-11-bi-4b-real-data-fix.md` (PR #234)
- `docs/audits/2026-05-23-bi-sprint-11-close.md` (PR #237)
- `docs/audits/sprint-11-bi-playwright-waiver.md` (PR #241)
- **`docs/audits/2026-05-23-bi-sprint-11-vs-original-requirements-audit.md`** (PR #243) ← 6 维度 deep audit
- `docs/sprint-12-backlog/indicator-service-rewrite.md` (PR #234) ← Sprint 12 spec for sister

---

## 4. 现状 — Sprint 11 BI 不能解决的 70%

### Sister AI 工厂 chat Sprint 12 owns

- **Backend IndicatorComputationStrategy** — 真接 sales_orders / production_batches / quality_inspections 算 7 个 F006 真 indicator
- **V_24_01 delete V_23_11 mirror migration** + clean indicator_versions 残留
- **SMART_INDICATOR_QUERY intent 注册** (sister Item 2 Bug A — DB query verified 未注册)
- **LLM 防幻觉 guard** for INDICATOR_QUERY-class queries (sister Item 2 Bug B — 食安通过率 → production-task 编造)
- **IntentKnowledgeBase 13+ shortcut overhaul** + INDICATOR_QUERY domain tagging (sister Item 4)
- **IndicatorRecomputeScheduler + Alert 闭环 hook** (Tool 写了但 0 reference in scheduler/)
- **Indicator domain ↔ foodsafety domain cross-link** (HACCP_VIOLATIONS 接 haccp_monitoring_records — 但 SSH verify F006 0 rows in haccp_monitoring_records, BLOCKER)

Spec: `docs/sprint-12-backlog/indicator-service-rewrite.md` (158 lines, Phase A-D, 3-5d estimate)

### Steve owns

- **卤味业态 indicator 需求定义** (出品率 / 卤汁损耗 / 真空包装合格率 / 等)
- **F006 老板真用 + 反馈视频** (客户联系 — BI chat 不能联系)
- **Sprint 12 ownership 决策** (BI vs sister 接 P0.5 续 / P1.3 等)

### Long-term (Phase 3, Month 3+)

- **GuanData 5 specs 写为 design doc**:
  - intent-architecture-3-layer-redesign
  - cretas-cli-mcp-server-design
  - attribution-analysis-skill-design
  - food-industry-indicator-center-design (Sprint 11 是 partial impl, no design doc)
  - ai-canvas-generation-design

仅 `docs/positioning/2026-05-22-cretas-vs-guandata-bi-comparison.md` (PR #159) 是 positioning doc, 不是 design spec.

---

## 5. Reopen trigger + verify steps

### When to reopen

Sister AI 工厂 chat Sprint 12 `indicator-service-rewrite` ship 完时, Steve dispatch 新 chat (BI OR 别人) 做 verify。

### Verify steps (~2-4h)

#### Step 1: Run E2E spec #255 (native Playwright)

```bash
cd /c/Users/Steve/my-prototype-logistics-sprint11-d5
git fetch origin main && git reset --hard origin/main  # sync latest
cd web-admin
npx playwright test tests/e2e-customer-journey/sprint-11-bi-dashboard.spec.ts \
  --workers=1 --reporter=html
```

Expected: 10/10 PASS. If any FAIL, sister Sprint 12 ship 不完整。

#### Step 2: SSH 47 verify F006 真业务数据接入

```bash
ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -A -F'|' -c \"
  SELECT 'V_23_11 mirror 还在?' AS check_name,
         COUNT(*) FROM indicators WHERE factory_id='F006'
         AND code IN ('AVG_TICKET_PRICE','TABLE_TURNOVER','DISH_GROSS_MARGIN',
                      'RAW_WASTAGE_RATE','FOOD_SAFETY_PASS_RATE',
                      'FACTORY_YIELD_RATE','FACTORY_PLAN_ACHIEVE_RATE');
\""
```

Expected: 0 rows (sister deleted V_23_11 via V_24_01 migration).

#### Step 3: Verify SMART_INDICATOR_QUERY intent registered

```bash
ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -A -F'|' -c \"
  SELECT intent_code, is_active FROM ai_intent_configs
  WHERE intent_code='SMART_INDICATOR_QUERY';
\""
```

Expected: 1 row, `is_active=t` (sister registered).

#### Step 4: 重跑 6 维度 audit (re-run #243)

按 `docs/audits/2026-05-23-bi-sprint-11-vs-original-requirements-audit.md` 6 维度 verify 命令重跑, 写新 audit doc `2026-XX-XX-bi-sprint-12-verify-audit.md`. Expected: Dim 3/5 升至 7-9/10, total 跳到 50%+.

#### Step 5: Sprint 12 close doc

写 `docs/audits/2026-XX-XX-bi-sprint-12-close.md` 含 reopen verdict + Sprint 13 backlog (if any).

### Anti-goal for verify chat

- ❌ Self-claim score (per sister rule: must cross-verify with sister Sprint 12 retro doc)
- ❌ Skip E2E spec run (per .webm waiver doc, native Playwright run is the substantive deliverable for Sprint 12)
- ❌ Re-shipping Sprint 11 BI work (already done — verify only)
- ❌ Touching backend (sister Sprint 12 owns; verify chat is read-only on backend)

---

## 6. Key file:line references

### Worktree state
- HEAD: `1b91585e9` (PR #257 merge) = origin/main as of 2026-05-28
- Branch: any feature branch from BI chat is finished + can be deleted

### Code refs (band-aid implementation)
- `web-admin/src/views/indicator-center/B2BRealDataSection.vue` (PR #234) — 真业务前端 compute
- `web-admin/src/views/indicator-center/IndicatorCenterDashboard.vue:246-265` (PR #234) — `MIRRORED_CODES` filter
- `web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue:74-78` (PR #249) — B2BRealDataSection 替代 mirror
- `web-admin/tests/e2e-customer-journey/sprint-11-bi-dashboard.spec.ts` (PR #255) — 10 scenarios

### Audit refs (cross-verify with sister)
- `docs/audits/2026-05-23-ai-factory-validation-session-retro.md:14` — Item 1 BLOCKER F006 100% mirror
- `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md:18-21` — 12/12 phrase fail (75% 错路由 + 25% 错误)
- `docs/audits/2026-05-23-bi-sprint-11-vs-original-requirements-audit.md` (PR #243) — 6 维度 22% verdict

### Memory refs
- `feedback_anti_goal_short_term_vs_long_term.md` — Sprint N anti-goal 是短期纪律不是长期原则
- `feedback_banner_not_a_fix.md` HARD — small-print banner 不算 fix
- `feedback_self_evidence_disqualified_cross_verify_required.md` HARD — self-claim X% disqualified

---

## 7. 4-chat 协作 模式总结 (this Sprint 11)

| Chat | Role | Final score | Notable |
|---|---|---|---|
| **BI chat** (本) | Indicator Center + Workdesk band-aid | 22% → 30% honest | 90% → 22% self-correction -68pp, 真改 UI #249 不只 paperwork (Steve verdict: 4 chat 里 self-audit 最严格 + 真改 UI 冠军) |
| Sister AI 工厂 chat | Backend + intent routing + UX audit verdict | 5% (cascade) | Item 1/2/4 audit, F006 100% mirror BLOCKER 抓 |
| 餐饮 chat | Restaurant Composite Tool + STOP signal | 16/35 = 46% | RESTAURANT_ECONOMICS_ANALYSIS Composite 空数据, STOP 客户演示 (PR #224) |
| Canvas chat | Phase 2-5 Canvas modules ship | 9/9 close-gate GREEN | Tab placeholder + 5 modules, ship marathon |

### Coordination patterns 总结

- **Cherry-pick PR chain** (sister #205/#208/#203/#204/#209/#212): BI chat 撞 6 次, 教训 graduate to memory `feedback_cherry_pick_pr_verify_dep_chain.md`
- **Worktree isolation** (per `concurrent-edit-safety.md` Rule 2): BI 单独 `sprint11-d5` worktree, sister 在 `sprint11-indicator`, 不直接撞
- **4-B band-aid pattern** (per `feedback_anti_goal_short_term_vs_long_term.md`): Sprint N 撞车时 short-term anti-goal "不准 backend" → 写 Sprint N+1 backlog + UI 明文标 "临时方案"
- **Cross-verify required** (per `feedback_self_evidence_disqualified_cross_verify_required.md`): 任何 X% 自评必 cross-check sister retro / 业务表 SQL

---

## 8. Sign-off

**Status**: 真 close. 不接新 task. BI chat session done.
**Worktree**: 保留 (Steve 手动 cleanup OR Sprint 12 verify 复用)
**Memory**: 已 saved 3 new HARD rules + 1 project entry (per memory rules)
**Reopen**: Sister Sprint 12 indicator-service-rewrite ship 完后 trigger
**Co-Authored-By**: BI chat (Claude Opus 4.7 1M context)
