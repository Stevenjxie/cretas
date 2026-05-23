# Sprint 12 Plan + Hygiene Backlog

**Date**: 2026-05-23
**Source**: AI 工厂 chat coordinator role + Sprint 11 true retro
**Status**: Planning doc, awaiting Steve approval before Sprint 12 kickoff

---

## Sprint 12 主目标

1. **真客户演示 unblock** (P0-3 fix) — 餐饮 chat Composite rebuild OR brief Path B
2. **3 P0 close** (P0-1 SMART intent, P0-2 LLM 防幻觉, P0-3 Composite)
3. **F006 真算法 indicator** (Item 1 BLOCKER) — IndicatorQueryService 从 F006 真源算
4. **Routing fundamental fix** (Item 4) — IntentKnowledgeBase 13+ shortcut overhaul + INDICATOR_QUERY domain tag
5. **Loop 4 backend P1** — ApprovalActionExecuteTool optimistic-lock (Sprint 10 carryover)

---

## 4 chat 协作模式总结 (Sprint 11 教训)

### 撞车 pattern
1. **Cherry-pick partial dep chain** — PR #200/#205/#208/#209/#212 教训, HARD `feedback_cherry_pick_pr_verify_dep_chain.md`
2. **Concurrent worktree edit yank** — sister `git checkout` 删 untracked WIP. HARD `feedback_concurrent_session_branch_yank.md`
3. **Code merge != content verified** — Composite Tool / SMART intent / Item 1 mirror data 都是 "code shipped + retro 标 done + 真测 blank". HARD `feedback_smoke_validates_usefulness_not_just_no_error.md`
4. **111 worktree bloat** — 累计 stale worktree 110+, junction risk per `concurrent-edit-safety Rule 7`

### 防撞 protocol (Sprint 12 enforce)
1. Sprint 12 start: prune 111 → ≤5 worktree (1 per active chat)
2. Cherry-pick PR: 必 `mvn compile -DskipTests` 本地 + grep dep refs before merge
3. Multi-chat ship coordination: shared tracker doc (per this Sprint 11 P0 tracker pattern)
4. Content verify before claim ship: any PR claiming "feature shipped" 必 cross-link to evidence doc with cmd output

---

## Hygiene plan (defer 但写 plan)

### Worktree cleanup (~111 → 5)

Manual steps deferred to Sprint 12 kickoff. 1-2h work.

### Untracked file scan
- Repo root: indicator-center-snapshot.yml (gitignored now)
- All worktrees: `for wt in $(git worktree list | awk '{print $1}'); do cd $wt && git status --short; done`

### Stale branches cleanup
- Branches merged >14 days, no recent push → list + delete

---

## Sprint 12 ticket backlog

| # | Title | Owner | Effort |
|---|---|---|---|
| S12-001 | P0-3 Composite Tool fix (rebuild data OR Path B brief rewrite) — 客户演示 BLOCKER | 餐饮 chat | 12-24h (A) / 4-8h (B) |
| S12-002 | P0-2 LLM 防幻觉 guard + unit test | BI / AI 工厂 chat | 6-12h |
| S12-003 | P0-1 SMART_INDICATOR_QUERY intent migration | BI chat | 2-4h |
| S12-004 | Item 1 — IndicatorQueryService 真算法 from F006 sources | Sprint 12 backend lead | 16-24h |
| S12-005 | Item 4 — IntentKnowledgeBase 13+ shortcut overhaul + INDICATOR_QUERY domain tag | Sprint 12 routing lead | 12-20h |
| S12-006 | Loop 4 ApprovalActionExecuteTool optimistic-lock fix (Sprint 10 carryover) | Sprint 12 backend | 4-8h |
| S12-007 | 111 worktree cleanup → ≤5 | cleanup chat | 1-2h |
| S12-008 | F006 客户 ≥1 闭环真用 smoke | Steve | manual |
| S12-009 | 接 FinanceManager + QualityManager Workdesk to BI Tool | TBD | 8-12h each |

---

## Coordinator final summary

Per goal 5 真 DOD:
- (a) ✅ STOP doc merged (PR #224 `be06b9613`)
- (b) ✅ 3 P0 dispatch prompts committed (will be in single coord PR)
- (c) ✅ tracker doc written
- (d) ✅ true retro written
- (e) 🔜 MEMORY.md index updated (next, separate edit)

Coordinator role mostly complete. 6h checkin cadence kicks in next.
