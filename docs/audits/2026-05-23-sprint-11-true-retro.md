# Sprint 11 真实 Retrospective — 修正 30% → 10% (4 chat 协作真相)

**Date**: 2026-05-23
**Author**: AI 工厂 chat coordinator (worktree `sprint11-indicator`)
**Status**: Supersedes previous retros (主因 Item 2 audit P0-3 finds Composite blank, invalidates 餐饮 chat #211 retro 的 "Composite shipped done" claim)

---

## TL;DR

| Metric | Previously claimed | Real (verified) |
|---|---|---|
| Sprint 11 progress | 30% (multiple chat retros) | **~10%** (only backend ship, content/真数据/routing 全 blocked) |
| BI Tools ship | 4/4 (PR #192 merge) | 4 Tool backend LIVE, 3/4 单独 callable, 但 data 100% F999_MOCK mirror per Item 1 |
| Routing accuracy | claimed working | **6/10 misroute** per Item 2 + 3 P0 bugs |
| Composite Tool (PR #186) | shipped, ready for demo | **BLANK content** ("三项数据不可用") per Item 2 case #9+#10 — P0-3 |
| SMART_INDICATOR_QUERY Skill | D6a 5-day ahead of schedule | **intent 漏 ship**, 404 on direct call — P0-1 |
| LLM 防幻觉 | implied compliant | **600-char hallucinated production plan** for "食安通过率怎么样" — P0-2 critical violation |
| F006 Indicator data | shipped | **100% F999_MOCK mirror** (per Item 1 commit `61746d7a3`), no real F006 算法 |

---

## 4 chat ship 进度 (诚实)

| Chat | Worktree | Real shipped this Sprint 11 | False claims | Action needed |
|---|---|---|---|---|
| **餐饮 chat** | `mealclaw-pm-coord` | Phase 4 brief written, RESTAURANT_ECONOMICS_ANALYSIS code merged (PR #186) | "Composite shipped done" → Item 2 prove blank | P0-3 rebuild OR brief 改 Path B; update false retro `2026-05-22-mealclaw-retrospective.md` |
| **BI chat** | `sprint11-d5-alert-tool-2026-05-22` | 4 BI Tool code merged (PR #192), D6a smart Skill code merged (a671ed7e9), V_23_04/05/06 + V_23_07 migrations LIVE prod | "D6a 5 天提前 ship done" → 但 SMART_INDICATOR_QUERY intent 漏 ship | P0-1 fix intent migration; P0-2 (optional owner) LLM 防幻觉 guard |
| **Canvas chat** | `canvas-*` various | Phase B+P3 8 entities wrapped (PR #196/#198/#201), 17-Tab E2E framework (#210) | None caught | Not blocking Sprint 11 BI work |
| **AI 工厂 chat (this)** | `sprint11-indicator` | (1) PR #199 V_23_11 F006 seed (mirror); (2) PR #203 V_23_12 + SalesOwner UI 接入; (3) PR #205/208/209/212 hotfix chain; (4) PR #220 validation audit 6/6 items (this audit prove the false claims above); (5) PR #224 STOP signal | (1) Earlier "Sprint 11 30%" claim — corrected this session | Coordinator role: P3 tracker + this retro + P5 hygiene |

---

## 3 chat 撞车 (concurrent edit + cherry-pick chain pain)

1. **PR #200/#205/#208/#209/#212** — Cherry-pick partial bug (PR #155 Controller cherry-picked alone, missing PR #154 Service + PR #153 Repo method) → main mvn compile broken for 90+ min → AI 工厂 chat had to write 3 successive hotfix PRs.
   - Lesson: new HARD rule `feedback_cherry_pick_pr_verify_dep_chain.md` (memory updated PR #220)
2. **Worktree concurrent edit yank** (May 22 incident) — Sister chat `git checkout` 删 ~1000 LOC untracked WIP. Per `feedback_concurrent_session_branch_yank.md` HARD.
3. **111 worktrees** + multiple chats writing to same `web-admin/` source → near-yank misses but no incident this session.

---

## 6 P0/BLOCKER inventory

| # | Severity | Source | Owner |
|---|---|---|---|
| Item 1 BLOCKER | 🔴 | AI 工厂 PR #220 | Sprint 12: rewrite IndicatorQueryService 算法 from F006 真源 |
| P0-1 | 🟡 | Item 2 case #11+#12 | BI chat (P0-1 dispatch sent) |
| P0-2 | 🔴 | Item 2 case #13 | BI chat OR AI 工厂 chat (Steve assigns) |
| P0-3 | 🔴🔴🔴 | Item 2 case #9+#10 | 餐饮 chat (P0-3 dispatch sent) — 客户演示 BLOCKER |
| Item 4 routing fundamental | 🟡 | AI 工厂 PR #220 | Sprint 12: IntentKnowledgeBase 13+ shortcut overhaul |
| Sprint 10 carryover Loop 4 P1 | 🟡 (unchanged) | Sprint 10 retro | Sprint 12: backend ApprovalActionExecuteTool optimistic-lock |

---

## 14 case routing 6/10 真实

Per Item 2 evidence (`docs/audits/sprint-11-validation/bi-tool-output-validation.md`):
- 4 BI Tool explicit intentCode: 3/4 work (1/4 LINEAGE_QUERY needs batch_id)
- 4 BI Tool natural language: 4/8 ✅ (INDICATOR_QUERY 2/4, COMPARISON 2/2, ALERT 2/2, LINEAGE 0/2)
- 2 Composite explicit + NL: status SUCCESS but content blank → 0/2 业务有用
- 2 smart Skill explicit + NL: 0/2 (intent missing)
- 2 extra INDICATOR_QUERY NL hard cases: 0/2 (1 misroute, 1 LLM hallucination)

**总: 8/14 routing OK, 0/14 真业务正确 (data mirror), 3 P0 bugs**

---

## Sprint 12 必修 list (priority)

| Priority | Item | Owner | From |
|---|---|---|---|
| P0 | P0-3 餐饮 Composite fix (客户演示 blocker) | 餐饮 chat | this retro + STOP signal |
| P0 | P0-2 LLM 防幻觉 guard | BI / AI 工厂 chat | this retro |
| P0 | P0-1 SMART_INDICATOR_QUERY intent migration | BI chat | this retro |
| P0 | Item 1 BLOCKER fix — F006 真算法 indicator | Sprint 12 backend lead | AI 工厂 audit |
| P1 | Item 4 routing — IntentKnowledgeBase overhaul + INDICATOR_QUERY domain tag | Sprint 12 routing lead | AI 工厂 audit |
| P1 | Loop 4 ApprovalActionExecuteTool optimistic-lock fix | Sprint 12 backend | Sprint 10 carryover |
| P1 | 111 worktree cleanup (defer plan written here) | Steve / cleanup chat | this retro |
| P2 | F006 客户 ≥1 闭环真用 smoke | Steve | Sprint 10 retro |
| P2 | 接 FinanceManager + QualityManager Workdesk to BI | TBD | original Sprint 11 main goal #2 |

---

## 跟 previous retros 的修正

### 餐饮 chat retro (`2026-05-22-mealclaw-retrospective.md`) 修正
- **原 claim**: "Composite Tool shipped via PR #186, ready for client demo"
- **真实**: Composite returns blank "三项数据不可用" (Item 2 case #9+#10)
- **PR #186 实际 ship**: code structure + 3 sub-Tool wiring framework, **没真数据接入**
- **餐饮 chat 行动**: 必须 (a) update this retro doc 标 "Composite shipped code only, content blocked" (b) 或 P0-3 Option A 真接通 data, retro 标 "demo-ready"

### BI chat D6a retro 修正
- **原 claim**: "D6a smart-indicator-query Skill 5 天提前 ship done"
- **真实**: Skill code merged but intent 漏 ship (Item 2 case #11)
- **BI chat 行动**: P0-1 add intent migration → re-test passes → update retro 标 "Skill + intent both LIVE"

### Sprint 11 整体 progress claim
- **原 claim**: "Sprint 11 30%, BI Tools + smart Skill + Composite + Round 3 E2E 10/10 全 LIVE"
- **真实**: Sprint 11 ~10%
  - Backend code LIVE (Tools + Skill class registration)
  - 数据 100% mirror (Item 1 BLOCKER)
  - Routing 6/10 misroute (Item 2 + Item 4)
  - 3 P0 content/intent bugs (Item 2 #9-13)
  - SalesOwner UI wiring OK 但 显 mirror data (Item 3 spec PASS w/ caveat)

---

## Coordinator next phases

- ✅ P1 STOP signal (PR #224 merged)
- ✅ P2 3 P0 dispatch prompts (this commit batch)
- ✅ P3 this tracker doc
- ✅ P4 this retro doc
- 🔜 P5 hygiene + Sprint 12 plan + MEMORY index update
- 🔜 6h checkin loop in tracker doc

---

## Signature

**Coordinator**: AI 工厂 chat
**Skills applied**: verification-before-completion HARD, depth-first-e2e Rule 1/2/8/10, smoke_validates_usefulness HARD, concurrent-edit-safety Rule 1/2, fool-proof-design R5
**Evidence chain**: PR #220 audit + PR #224 STOP + 3 dispatch docs + this tracker + this retro — all in same coord PR
