# Sprint 11 D1-D5 Snapshot (AI 工厂 chat 视角) — 2026-05-22

**Status**: Session-scoped handoff doc. Sprint 11 真实进度 by sister chats, this chat's parallel attempt yanked.

---

## 1. Sprint 11 真实 git state (D1-D6a shipped by sister chat)

**Current branch**: `feat/sprint11-e2e-round3-2026-05-22` @ `b0b1fbb99`

**Sister chat Sprint 11 commits** (chronological, branch `feat/sprint11-d1-d2-mock-data-2026-05-22`):

| Commit | Day | Scope |
|---|---|---|
| `b465a3bbe` | D1+D2 | mock generator + 210 versions for F999_MOCK |
| `9a482850e` | D3 | **IndicatorQueryTool** (353 行) + 单测 + V20260823_04 intent |
| `3c185b418` | D4 | **LineageQueryTool + IndicatorComparisonTool** + V20260823_05 |
| `26ad31fc3` | D4-D5 | BI LLM wrapper + Path B windowing + composite endpoint (Phase 2 BI) |
| `8cccf7060` | D5 | **IndicatorAlertTool** + V20260823_06 intent |
| `a671ed7e9` | D6a | **smart-indicator-query Skill** (4-Tool 智能路由) |
| `7c800a21e` (PR #186) | D6+ | **MealClaw Composite Tool** + LLM whitelist + intent (Phase 2 AI 工厂层) |
| `599660491` | E2E | **Round 1 E2E 10/10 PASS** + P0/P1 documented |
| `b0b1fbb99` | E2E | **Round 3 E2E 10/10 PASS** + 3 new P1s found |

**Sister chat Sprint 11 active branches** (parallel work):
- `feat/sprint11-bi-llm-wrapper-2026-05-22`
- `feat/sprint11-d1-d2-mock-data-2026-05-22` (BI core, 上述 commits 在此)
- `feat/sprint11-d5-alert-tool-2026-05-22`
- `feat/sprint11-e2e-round1-2026-05-22`
- `feat/sprint11-e2e-round3-2026-05-22` (current HEAD)
- `feat/sprint11-restaurant-economics-composite-tool`
- `feat/sprint11-round2-fixes-2026-05-22`
- `feat/sprint11-round4-fixes-2026-05-22`
- `feat/sprint11-round6-fixes-2026-05-22`

**Sprint 11 主成功标准 真实达成度**:

| # | Criterion | Status | Owner |
|---|---|---|---|
| 1 | 4 BI Tools (IndicatorQuery + Lineage + Comparison + Alert) | ✅ D3-D5 shipped | sister chat (BI) |
| 2 | smart-indicator-query Skill (4-Tool 智能路由) | ✅ D6a a671ed7e9 | sister chat |
| 3 | MealClaw Composite Tool (Phase 2 AI 工厂层) | ✅ PR #186 | sister chat |
| 4 | ≥10 闭环 Playwright prod E2E PASS | ✅ Round 1 + Round 3 各 10/10 | sister chat |
| 5 | 3 Workdesk Vue 接 BI 4 Tools (D7 EOD) | ⏳ in flight | sister chat (next) |
| 6 | PR #131 hack 拆除 + 6/6 re-smoke PASS PNG | ⏳ pending | sister chat |
| 7 | AI 输出格式协议 + 防幻觉单测 | ⏳ pending | sister chat |
| 8 | 2 剧本视频 (D13) | ⏳ pending | sister chat / Steve |
| 9 | 审计 UI ship | ⏳ pending | sister chat |
| 10 | 讯飞 SDK 语音输入 demo | ⏳ pending | sister chat |
| 11 | F006 老板 ≥1 闭环真用 (D14) | ⏳ pending | Steve |
| 12 | Sprint 11 retro doc + 7.5/10→8.5/10 | ⏳ D14 EOD | sister chat |

**Score**: 4/12 fully shipped this session (sister chat shipped, AI 工厂 chat 此 session verified via git log). 8 remaining D6-D14 work.

---

## 2. AI 工厂 chat 此 session 的工作 (D1-D3 yanked)

**Origin**: 此 session 当 "AI 工厂 chat" 起步 D1 (草 specs + spike), D3 推到 code refactor (V_23_10 migration + IntentRoutingTest.java + DynamicToolSelectionService refactor).

**Yank event** (Sprint 11 D1-D3 阶段中后期):
- Sister chat (BI) 在 shared repo 跑 `git checkout feat/sprint11-e2e-round3-2026-05-22`
- 我之前在 `audit/workdesk-ai-output-quality-2026-05-22` branch 写的 untracked Sprint 11 文件 (5 specs + 1 migration + 1 test + 1 Vue 组件 + 1 TS composable) 没 commit, 被 checkout 物理 unlink
- 文件未在 Cursor / VSCode 打开 (我用 Write tool 直接写 to disk), 所以 editor history 没备份
- `grep -rl` Cursor + VSCode history dirs → 0 hits

**Yanked files inventory** (D1-D3, 14 files):
- `docs/superpowers/specs/sprint-11/output-format-protocol.md` (216 lines)
- `docs/superpowers/specs/sprint-11/intent-routing-refactor-design.md` (281 lines)
- `docs/superpowers/specs/sprint-11/e2e-10-scenarios-design.md` (206 lines)
- `docs/superpowers/specs/sprint-11/D2-progress.md` (103 lines)
- `docs/superpowers/specs/sprint-11/D3-progress.md` (134 lines)
- `backend/java/cretas-api/src/main/resources/db/flyway/V20260823_10__intent_skill_binding.sql` (~80 lines)
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentRoutingTest.java` (~110 lines)
- `web-admin/src/components/workdesk/IndicatorCard.vue` (~200 lines)
- `web-admin/src/composables/useVoiceInput.ts` (~180 lines)
- AIIntentConfig.java skillName field add (~15 lines edit)
- DynamicToolSelectionService.java refactor (~15 lines edit, deprecated convention method)

**Verification of yank** (per `feedback_subagent_file_claims_need_ls_verify` HARD):
```
$ ls docs/superpowers/specs/sprint-11/
ls: cannot access ...: No such file or directory
```
Confirmed all 9+ files (specs + migration + test + Vue + TS) 不在 disk.

**Recovery attempt**:
- Cursor `%APPDATA%\Cursor\User\History\` exists 但 0 file matches yanked content
- VSCode `%APPDATA%\Code\User\History\` exists 但 0 file matches
- Windows Volume Shadow Copy: no recent shadows
- Git reflog: untracked files 不在 reflog
- **Recovery rate: 0%**

**Impact assessment**:
- 大部分 yanked work 是 duplicative — sister chat 已用 different approach ship 等价功能 (4 BI Tools, smart routing, MealClaw Composite)
- Unique loss: `V_23_10` migration + `IntentRoutingTest.java` (PR #131 hack 拆除 — 仍是 Sprint 11 backlog, sister chat 可重做)
- Spec docs are designs not code — Sister chat shipped 实质 BI Tools 已超过我 design 范围

---

## 3. Sprint 12 防 Yank Protocol (graduated to project rule)

**Root cause**: Shared single repo + multiple concurrent Claude Code sessions on different branches + Write tool writes untracked files + sister chat `git checkout` unlinks untracked files matching no-tracking-no-recovery path.

**Why HARD rule alone (commit every 15-30min) insufficient**: When deep in design work (writing 5 specs + 1 migration in parallel BG runs), the "single atomic logical unit" boundary is fuzzy. By the time of natural commit point, 90+ minutes can elapse.

**Sprint 12 mandatory protocol** (additive to existing HARD rules):

### Rule A: Worktree default for any session estimated >30min
```bash
# Sprint 12 default — any session starting now MUST do:
git worktree add -b "ai-factory-$(date +%Y-%m-%d-%H%M)" \
    "../my-prototype-logistics-ai-factory-$(date +%Y-%m-%d-%H%M)" main
cd "../my-prototype-logistics-ai-factory-$(date +%Y-%m-%d-%H%M)"
# All Write tool calls now safe — sister chat checkout in MAIN repo doesn't touch worktree
```

### Rule B: Pre-commit hook for untracked-WIP detection
- `.git/hooks/pre-checkout` (if Git supported it — actually `git checkout` doesn't have a pre hook)
- **Workaround**: any session that writes untracked files MUST run `git status --short` every 5 file writes; if 5+ untracked files exist, force `git stash -u` before any other operation.

### Rule C: WIP commit cadence
- "Commit every 15-30min" upgraded to "Commit after every 3 file writes OR every 15 min OR before any git command"
- WIP commit message format: `wip: ai-factory chat sprint-11 D{n} {phase} (untracked safety)`
- Squash before PR/merge.

### Rule D: File creation manifest
- After each Write tool call, immediately append to `.local-session-manifest.txt` (ignored by .gitignore but tracked in session memory)
- Allows quick `git stash push -u --pathspec-from-file=.local-session-manifest.txt` recovery move

### Rule E: Process-level lock
- Future work: explore `flock` on a `.cretas-session.lock` file in repo root — sessions register; new session detects existing lock and warns "Concurrent session detected, use worktree".

---

## 4. Sprint 10 carryover ownership confirmation

**Carryover items per Sprint 10 retrospective** (`docs/audits/2026-05-22-sprint-10-retro.md`):

| Item | Status | Owner | Sister chat visibility |
|---|---|---|---|
| Loop 4 `ApprovalActionExecuteTool` optimistic-lock P1 | Sprint 11 期间 backend fix | backend chat | Visible — commit `8bcd0bfa6` (PR #182) shipped "Sprint 10.5 P0 #1 — Skill 失败 surface 真实错误" which addresses generic-error masking (related but not same root). Real optimistic-lock fix still pending. |
| F006 客户 ≥1 闭环真用 smoke | D14 task | Steve | Steve scope — Sister chat cannot delegate |
| Loop 2 systemic Tool TX rollback (same optimistic-lock class) | Sprint 11 期间 fix | backend chat | Same fix pattern as Loop 4 |
| Loop 5 spec WRITE flow (parameters vs context envelope, cache, skipSlotFilling) | Sprint 11 spec rewrite | sister chat | Visible — MealClaw Composite Tool PR #186 has whitelist + LLM wrapper pattern that addresses similar slot-filling concern |
| Loop 3 spec ESM __dirname + cache bypass | Sprint 11 spec rewrite | sister chat | Visible — E2E Round 1+3 commits show modern spec pattern used |
| cleanup-sprint-10-test-data.sh approval_history column name bug | Sprint 11 backlog | maintenance | Low-priority |
| 6 WORKDESK intent PR #131 convention hack 拆除 | Sprint 11 D3-D4 backlog | sister chat (was AI 工厂 me, yanked) | **Gap** — sister chat shipped 8bcd0bfa6 PR #182 which surfaces real Skill errors but PR #131 convention hack itself (`intentCodeToSkillName` UPPER_SNAKE→kebab) STILL EXISTS in DynamicToolSelectionService. V_23_10 migration to add skill_name column + DB-driven binding NOT yet done by sister chat per git log. |

**Action needed for Sprint 11 closeout**:
- Sister chat picking up PR #131 拆除 should reference original design in this snapshot doc § 1 + retro doc (Sprint 10 retro) + handoff doc (PR #178 docs/superpowers/handoffs/2026-05-22-sprint-10-mid-handoff.md)
- Loop 4 optimistic-lock fix needs Java backend chat — NOT covered by current AI 工厂 chat capacity

---

## 5. Session closing note

此 session (AI 工厂 chat) 主要价值:
- **Sprint 10 closeout**: 4/5 闭环 verified via direct API (Loop 1 ✓ from handoff + Loop 2/3/5 verified this session via MCP browser + Loop 4 partial). DB row + ai_meta + idempotent all verified, screenshots in `docs/audits/sprint-10-demos/`.
- **Sprint 11 D1-D3 attempted but yanked** — sister chat 用 different approach ship 等价功能, my work loss is duplicative not blocking.
- **Sprint 11 真实 状态 documented** in this snapshot doc.

Sister chats are 主力 driving Sprint 11 ship. 此 session 完成 4 goal items + git commit, then auto-clear.

---

## 6. File commit log (per yank-prevention HARD)

This document committed immediately after write to avoid yank.

```
git add docs/audits/2026-05-22-sprint-11-d1-d5-snapshot-from-ai-factory-chat.md
git commit -m "docs(sprint-11): D1-D5 snapshot + yank protocol + carryover ownership (AI 工厂 chat session)"
```
