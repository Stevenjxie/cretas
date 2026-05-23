# P0-3 Dispatch: RESTAURANT_ECONOMICS Composite blank → rebuild OR brief rewrite

**Date**: 2026-05-23
**Owner**: 餐饮 chat (worktree `mealclaw-pm-coord`)
**Severity**: P0 RED RED RED (BLOCKER 客户演示翻车)
**Source bug**: AI 工厂 chat Item 2 audit case #9+#10, doc `docs/audits/sprint-11-validation/bi-tool-output-validation.md` (PR #220)
**Related STOP signal**: PR #224 `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md` (merged main `be06b9613`)

---

## Problem

`RESTAURANT_ECONOMICS_ANALYSIS` Composite Tool (PR #186 MealClaw shipped 5 days ago) returns blank:
```
POST /F006/ai-intents/execute {"intentCode":"RESTAURANT_ECONOMICS_ANALYSIS"}
→ status: SUCCESS
→ msg: "三项数据不可用: P&L 一页纸 / 损溢分析 / 成本归因. 已基于可用数据完成分析, 建议用查无数据明确标注."
```

Phase 4 客户演示 brief (`docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md`) 让客户走 5 步 → 终点这个 Tool → 老板看到 "数据都不可用".

---

## Choose Option A OR B (Steve / 餐饮 chat decision needed within 12h)

### Option A: Rebuild Composite — 接通 3 sub-Tool 真数据

Composite 内部 3 sub-Tool (per PR #186):
1. **P&L 一页纸** — needs `sales_orders` (F006 has 5 rows, B2B factory not restaurant — wrong factory_type)
2. **损溢分析** — needs `cooking_batch` (verify table exists + has data)
3. **成本归因** — needs `bom_recipes` (verify exists + has data)

Steps:
1. SSH SQL: verify `cooking_batch` + `bom_recipes` tables + F006 / target restaurant factory rows
2. If F006 wrong factory_type → pick restaurant-business factory_id (e.g. RES_3101_009 has 646K POS rows per memory `feedback_workdesk_intent_smoke_required.md`)
3. If sub-Tool 算法 has bug (returns "不可用" even when source data exists), grep `RestaurantEconomicsAnalysis` impl + fix nullability/data fetch
4. Re-test Item 2 case #9 → expect content with 真业务数字 (not "不可用")

Effort: 12-24h (data verify + algo debug + re-test)

### Option B: Brief 改 SmartBI Path — 客户走 SmartBI direct (recommended for time)

Steps:
1. 改 `docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md` 5 步流程
2. **Old**: 客户对 Workdesk 说 "餐饮经营分析" → RESTAURANT_ECONOMICS_ANALYSIS Composite → blank
3. **New**: 客户登 web-admin → SmartBI module → upload restaurant POS Excel → 看 chart + AI 分析 (per Sprint 8 SmartBI shipped Tool)
4. Test: SmartBI direct path 真有 data (RES_3101_009 has 646K POS rows verified)
5. 改 brief 同时 update `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md` 把 "Composite ship done" 改 "deferred to Sprint 12"

Effort: 4-8h (brief rewrite + test 1 SmartBI path)

---

## DoD (depth-first-e2e Rule 2+8+10)

### Common (both options)
- [ ] Decision A/B made + documented in `docs/audits/sprint-11-p0-fix-tracker.md` P0-3 row
- [ ] Worktree-isolated work (per `concurrent-edit-safety` Rule 2)
- [ ] All file writes 5min 内 commit (per Rule 1)
- [ ] PR opened + admin-merge to main
- [ ] Update tracker with PR # / commit SHA / deploy / re-test evidence

### Option A only
- [ ] Re-test Item 2 case #9+#10 → status SUCCESS + 真业务数字 (cross-verify ≥1 number vs SQL source)
- [ ] Same-cause sweep: 其他 Composite Tool (Find by `grep "Composite" backend/java/cretas-api/src`) — 是否同样空数据 pattern

### Option B only
- [ ] Brief rewrite + SmartBI path test 1 step manually pass
- [ ] Old retro doc updated with "Composite deferred Sprint 12"
- [ ] Steve confirm new brief 可发微信

---

## Anti-pattern

- ❌ Send Wechat invite without P0-3 fix verified
- ❌ "Code merged = ship" without content cross-verify (Item 2 lesson)
- ❌ Skip update of old retro doc (will keep misleading future audits)

---

## Cross-references

- STOP signal: `docs/audits/2026-05-23-mealclaw-stop-customer-demo.md` (PR #224 merged `be06b9613`)
- Audit evidence: PR #220 `bi-tool-output-validation.md` Item 2 case #9+#10
- Composite Tool source: PR #186 commit `7c800a21e` + commits in `feat/sprint11-restaurant-economics-composite-tool`
- 餐饮 chat retro to update: `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md`
- Old brief to fix: `docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md`
- SmartBI Path B reference: `feedback_workdesk_intent_smoke_required.md` (RES_3101_009 has 646K POS rows)

---

## Coordinator checkin

@AI 工厂 chat coordinator: 12h after this dispatch, expect 餐饮 chat decision A/B + start. If no progress → escalate to Steve.
**P0-3 is 客户演示重启 blocker** — most urgent.
