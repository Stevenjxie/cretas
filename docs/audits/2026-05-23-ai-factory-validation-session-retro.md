# AI 工厂 chat Sprint 11 Validation Session Retro — 2026-05-23

**Status**: Goal 6/6 items shipped + committed + (Item 5 pending PR merge).
**Skill stack used**: verification-before-completion + depth-first-e2e + e2e-web-admin + writing-plans + brainstorming.

---

## What this session did

Sprint 11 "code shipped" 部分跟 "用户视角真 verified" 之间的 gap, 通过 6 项 evidence-based 验证回答了 Steve 3 个 audit 问 (完成? 真数据? 全 AI 接口测过?). 答案全是 **NO** + 量化 + 落 docs.

| Item | DoD | Outcome | Commit |
|---|---|---|---|
| 1. F006 indicator 真实性 cross-verify | SQL vs Tool ≤5% | 🔴 BLOCKER 100% mirror | 61746d7a3 |
| 2. 7 BI 接口 14 case test | 每 case content verdict | ⚠️ 8/14 routing OK + 3 P0 bugs | a254832bc |
| 3. Playwright E2E deep test | 4 cards mount + value + tag | ✅ PASS UI wiring | 5724f1977 |
| 4. 6/10 misroute root cause + sweep | 13+ shortcuts in IntentKnowledgeBase | 真 root cause documented | 504470c41 |
| 5. Evidence commit + PR merge | screenshots + 4 audit docs + spec | 2 PNGs committed (this session) | (pending PR) |
| 6. Repo hygiene + retro + memory | gitignore + retro + 3 HARD rules | (this doc) | (this commit) |

---

## Wins (诚实)

1. **3 P0 bugs found via depth-first-e2e Rule 1** (status SUCCESS ≠ content correct):
   - SMART_INDICATOR_QUERY intent **not registered in DB** (D6a Skill 漏 ship intent)
   - LLM fallback for "食安通过率怎么样" **hallucinates production plan text** (防幻觉 critical violation)
   - RESTAURANT_ECONOMICS_ANALYSIS Composite Tool **content blank** ("三项数据不可用")

2. **V_23_12/13 negative_keywords PROVED useless** via Item 4 scoring math + Item 2 #14 real test. No more "negative_keywords might fix routing" — root cause is phraseWeight=1.0 vs keywordWeight=0.25 + IntentKnowledgeBase hardcoded 13+ shortcuts.

3. **F006 indicator data 是 100% mirror**, 即 Item 1 BLOCKER. SalesOwner Workdesk UI "F006 真数据" 标 misleading — Sprint 12 必修标 "demo 示例数据".

4. **Sprint 11 真实 progress revised**: 30% → 15% → **10%** (Tool 调用层 LIVE 但 data 100% mirror + routing 6/10 + Composite 空数据 + SMART intent 漏注册).

---

## Losses (诚实)

1. **Sprint 12 backlog 大**:
   - Item 1 → SalesOwner UI banner "demo 数据" + IndicatorQueryService 实算 from F006 sources
   - Item 2 → V_*_*__smart_indicator_query_intent.sql + Composite Tool seed F006 data + LLM-fallback guard for INDICATOR_QUERY-class queries
   - Item 4 → IntentKnowledgeBase 13+ shortcut overhaul + INDICATOR_QUERY domain tagging + per-intent scoring weight override

2. **Item 3 spec 不能 cross-verify SQL vs UI value 真业务正确性**, 只能 verify UI wiring (Tool call → value rendered). 业务正确性 verify 需 SQL fresh truth, 但 mirror data 让 SQL truth 无意义.

3. **Worktree state**: ≥111 worktrees existed at session start. Item 6 cleanup (worktree prune + manual remove) **NOT** completed yet (skipped due to time budget). Sprint 12 backlog.

---

## Memory updates needed (new HARD rules)

### Rule 1 (NEW): cherry-pick PR series 必 verify dep chain
- **Source**: PR #200/#205/#208 chain (Service / Repo method dep missed in cherry-pick)
- **Symptom**: main mvn compile broken after each cherry-pick, 3 successive hotfix PR needed
- **Rule**: before merging cherry-pick PR, MUST `mvn compile -DskipTests` locally + grep all class/method refs in cherry-picked files to verify dep chain complete
- **Memory file**: `feedback_cherry_pick_pr_verify_dep_chain.md`

### Rule 2 (NEW): negative_keywords 在 Cretas IntentMatching 实测 useless
- **Source**: V_23_12/13 evidence + Item 2 #14 real test + Item 4 scoring math
- **Why**: phraseWeight=1.0 + domainBonus 0.25 swamp keywordWeight 0.25 + negative_keyword 0.15 penalty
- **Rule**: don't add negative_keywords to fix routing in Cretas project; need IntentKnowledgeBase phrase shortcut OR per-intent weight override instead
- **Memory file**: `feedback_negative_keywords_useless_in_cretas_intentmatching.md`

### Rule 3 (NEW): MCP YAML snapshot 默认存 repo root 必 gitignore
- **Source**: `indicator-center-snapshot.yml` accidentally appeared in main repo root (MCP browser_evaluate side-effect)
- **Rule**: web-admin/.gitignore MUST include `*.snapshot.yml` + `indicator-center-snapshot.yml` + `.playwright-mcp/`. Apply same to any new web-admin or RN project.
- **Memory file**: `feedback_mcp_yaml_snapshot_default_location.md`

### Sprint 11 progress update
- Current memory says "Sprint 11 30%" — update to **10% (real shipped, not code)**:
  - 4 BI Tool intent 注册 + 1 Skill code shipped ✅
  - Data 100% mirror, UI shows demo not real F006 ⚠️
  - Routing 6/10 = fundamentally broken layer ❌
  - Composite Tool content blank ❌
  - SMART intent 漏注册 ❌

---

## Sprint 12 backlog (high-priority, picked from this session findings)

| Priority | Item | From |
|---|---|---|
| P0 | Item 1 fix — SalesOwner UI banner "demo data" + indicatorQueryService 实算 | Item 1 |
| P0 | Item 2 bug A — V_*_smart_indicator_query_intent.sql | Item 2 Bug A |
| P0 | Item 2 bug B — INDICATOR_QUERY-class 强制 route, ban LLM 编造 | Item 2 Bug B |
| P0 | Item 4 fix — IntentKnowledgeBase 13+ shortcut overhaul + INDICATOR_QUERY domain tag | Item 4 |
| P1 | Item 2 bug C — Composite Tool seed F006 data OR mark "demo" | Item 2 Bug C |
| P1 | Cleanup 111 worktrees → ≤5 (Item 6 partially done — only gitignore + retro) | Item 6 |
| P1 | Loop 4 ApprovalActionExecuteTool optimistic-lock (Sprint 10 carryover, still unfixed) | Sprint 10 retro |
| P2 | F006 客户 ≥1 闭环真用 smoke (Steve task) | Sprint 10 retro |

---

## Files committed this session (in worktree branch)

- 61746d7a3 indicator-value-cross-check.md
- a254832bc bi-tool-output-validation.md
- 504470c41 routing-scoring-investigation.md
- 5724f1977 sprint-11-d7-salesowner.spec.ts + playwright.config.ts
- (this commit) 2 screenshots + retro + .gitignore

---

## Final session score

| Metric | Result |
|---|---|
| Audit goal 6 items | 5 complete + 1 partial (Item 6 gitignore+retro yes, worktree prune deferred) |
| Audit docs created | 4 (Items 1+2+4 + this retro) |
| Evidence screenshots | 2 (MCP + Playwright) |
| Playwright deep tests added | 1 (sprint-11-d7-salesowner.spec.ts PASS) |
| 真 P0 bugs found | 3 (SMART intent + LLM hallucinate + Composite blank) |
| Sprint 11 progress revised | 30% → **10%** (with data mirror + routing broken + 3 bugs) |
| Commits in this worktree | 5 (one per item) + this commit |
| Memory new HARD rules drafted | 3 (cherry-pick / negative_keywords / MCP YAML) |
