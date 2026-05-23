# STOP — 餐饮 Phase 4 客户演示不能发微信

**Date**: 2026-05-23
**Severity**: BLOCKER (客户演示翻车 risk)
**From**: AI 工厂 chat (coordinator role, post Sprint 11 validation audit Goal 6/6)
**To**: @ 餐饮 chat owner (worktree `mealclaw-pm-coord`) + Steve (微信 sender)
**Action required**: **不要发微信邀请客户走 5 步演示** — 走完会看到 "三项数据不可用", 客户失望率 90%+

---

## TL;DR — 1 行

**RESTAURANT_ECONOMICS_ANALYSIS Composite Tool 在 prod 真返 "三项数据不可用: P&L 一页纸 / 损溢分析 / 成本归因"** — 即 Phase 4 brief 让客户走的 5 步终点的 AI Tool **没真数据**, 走完 = AI 告诉客户 "数据都不可用".

---

## 证据 (fresh command, per verification-before-completion HARD)

### 1. AI 工厂 chat Sprint 11 validation Item 2 audit (just shipped today)

- Doc: `docs/audits/sprint-11-validation/bi-tool-output-validation.md` (commit `a254832bc`, merged in PR #220 → main `6d8ff095a`)
- Item 2 case #9 (Path A explicit):
  - Query: "餐饮经营分析", `intentCode=RESTAURANT_ECONOMICS_ANALYSIS`
  - Tool: `restaurant_economics_analysis` (MealClaw Composite, PR #186)
  - Status: SUCCESS
  - **但 message content**: "三项数据不可用: P&L 一页纸 / 损溢分析 / 成本归因. 已基于可用数据完成分析, 建议用查无数据明确标注."
- Item 2 case #10 (Path B natural language):
  - Query: "帮我看上月损溢异常"
  - 同结果: SUCCESS but blank content

### 2. 为什么 "SUCCESS 但 blank" 是翻车

- 客户 (Steve 微信邀请的真实餐饮老板) 看到 "数据都不可用" → 第一印象 "Cretas 的 AI 没数据 / 不能用"
- 走完 5 步 brief = 5 次 "数据不可用" 屏 = 90%+ 客户失望
- 这是 `feedback_smoke_validates_usefulness_not_just_no_error` HARD 的典型违反: status SUCCESS != 内容 useful

### 3. Sprint 11 progress 真实 ~10% (not 30%)

完整 evidence 在 `docs/audits/2026-05-23-ai-factory-validation-session-retro.md` (PR #220 merged). 5 项 BLOCKER/P0 全 commit + cross-referenced.

---

## 餐饮 chat 现状 (per Steve message)

- Worktree: `mealclaw-pm-coord`
- 当前 status: hook fire 等 Steve 发微信邀请客户演示 (5 步 brief)
- Brief 文件: `docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md`
- 之前 retro 误标 done: `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md`
- 误判原因: brief + retro 在 PR #186 Composite Tool merged 后写的, 但**没真测 Composite 内容** (per Item 2 audit), 误以为 "code merged = 能演示"

---

## STOP signal — 3 next actions (per fool-proof-design R5 dead-end → next action)

### Action 1 (immediately, Steve): **不要发微信**
- Steve 之前微信邀请 brief 准备好了, 但 **不能发**.
- 如果已发 → 立刻发后续微信 "演示推迟 24-48h, 数据问题排查中" + 给客户预期 next-step

### Action 2 (餐饮 chat, P0-3 fix): 二选一
- **Option A (rebuild)**: 接通 3 sub-Tool 真数据
  - `sales_orders` (F006 5 rows, B2B factory not restaurant — 改餐饮场景 factory_id)
  - `cooking_batch` (need verify exist)
  - `bom_recipes` (need verify exist)
- **Option B (改 brief)**: 让客户走 Path B (SmartBI direct, F006 真有 646K POS 数据 per `feedback_workdesk_intent_smoke_required.md` HARD reference)
  - Brief 改: 不再调 RESTAURANT_ECONOMICS_ANALYSIS Composite
  - 客户体验: 直接 SmartBI 上传 POS Excel → 看分析结果 (真 data)

### Action 3 (AI 工厂 chat coordinator, P3 tracker): monitor + checkin
- 每 6h check 餐饮 chat Option A 或 B 进度
- Tracker doc: `docs/audits/sprint-11-p0-fix-tracker.md` (P3 deliverable)
- 客户演示重启条件: P0-3 fix verified + Composite 真返业务数据 OR brief 改 Path B 验证 SmartBI 数据返业务有用

---

## P0 全局 list (4 chat ownership, 见 sprint-11-p0-fix-tracker.md)

| P0 | Bug | Owner chat | Severity |
|---|---|---|---|
| P0-1 | SMART_INDICATOR_QUERY intent 漏 ship (D6a Skill code merged, intent migration 漏) | BI chat (worktree `sprint11-d5`) | YELLOW (Skill 404, 客户不会触发但 audit 暴) |
| P0-2 | LLM hallucinates "食安通过率怎么样" → 600-char unrelated production plan text | BI 或 AI 工厂 chat | RED (防幻觉 critical violation, 老板看到误以为真分析) |
| P0-3 | **RESTAURANT_ECONOMICS Composite blank** ← this doc, blocks 客户演示 | 餐饮 chat (worktree `mealclaw-pm-coord`) | RED RED RED (BLOCKER 客户翻车) |

P0-3 是最 urgent, 客户演示直接 blocker.

---

## Cross-references

- AI 工厂 chat validation retro: `docs/audits/2026-05-23-ai-factory-validation-session-retro.md`
- Item 2 evidence: `docs/audits/sprint-11-validation/bi-tool-output-validation.md`
- 餐饮 chat brief (要修): `docs/superpowers/dispatch/2026-05-22-mealclaw-phase4-customer-demo-brief.md`
- 餐饮 chat false retro (要修): `docs/superpowers/handoffs/2026-05-22-mealclaw-retrospective.md`
- PR #186 Composite Tool merged (源头): https://github.com/Stevenjxie/cretas/pull/186
- PR #220 AI 工厂 audit (BLOCKER call): https://github.com/Stevenjxie/cretas/pull/220

---

## Coordinator 后续 (本 doc 仅 P1, 还有 P2-P5)

- P2 (next): 3 P0 dispatch prompt 写到 `docs/superpowers/dispatch/2026-05-23-*.md` x 3
- P3 (next): tracker doc
- P4 (next): Sprint 11 真实 retro (修正餐饮 chat #211 retro)
- P5 (next): hygiene + Sprint 12 plan + MEMORY update

Per Steve check-in cadence: 每 phase 完成 → 6h checkin → confirm 再进下个 phase.

---

## Signature

**Coordinator**: AI 工厂 chat (worktree `sprint11-indicator-keywords-seed-2026-05-22`)
**Skills applied**: verification-before-completion HARD, fool-proof-design R5 (next-action), smoke_validates_usefulness HARD
**Evidence chain**: fresh `gh pr view 220` + commit SHA `a254832bc` + Item 2 case #9 + #10 raw JSON
