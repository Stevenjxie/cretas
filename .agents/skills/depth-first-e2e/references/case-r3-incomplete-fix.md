# Case Study: R3 Incomplete Fix — Why Rules 8 & 9 Exist

**Date**: 2026-04-14 / 2026-04-15
**Suite**: `tests/canvas-security-e2e/` (Canvas V3 security testing, 5-round quality loop)
**Outcome**: 3 latent P0 silent-data-loss bugs nearly survived all 5 rounds because the framework lacked Rules 8 (same-cause sweep) and 9 (independent Critic).

---

## Timeline

### R3 happens (the right thing)

R3 of the canvas-security-e2e cycle adds a depth-first test (Phase E custom field roundtrip) per Rule 2. The test does:
1. GET `/{factoryId}/sales_order/{recordId}/custom-fields` — read baseline
2. PUT same path with `{dlv_priority_e2e_X: "TEST_VALUE"}` — write
3. GET again — assert `customField === "TEST_VALUE"` (mutation visible)
4. Cleanup

On first execution, **the assertion FAILs**: PUT returned HTTP 200 success, but the GET still showed `null`. The Manager investigates and discovers:
- `application-pg-prod.properties:spring.datasource.hikari.auto-commit=false`
- `DynamicFieldService.setDynamicFields()` uses raw `JdbcTemplate.update("UPDATE...")`
- Neither the controller nor the service has `@Transactional`
- → JdbcTemplate update runs in the connection's implicit transaction, never gets committed, rolls back when the connection returns to the pool

**The Manager fixes the bug**: adds `@Transactional` to `DynamicFieldController.setCustomFields`. Re-runs the test. PASS. Two independent runs confirm. R3 closes with 79/79 PASS.

This is exactly what depth-first-e2e is supposed to produce: a deep test catching a real bug, fix shipped same round, regression locked in. **Rule 1-7 worked perfectly.**

### Where the framework failed

R3 audit doc lists the fix and notes under "R4 Carryover":
> 2. (MED) Audit other `JdbcTemplate.update/execute` callers in same package for the same latent bug pattern (`setSubTableRow`, `deleteSubTableRow`, `updateRow`)

The Manager:
- ✅ Recognized the same anti-pattern might exist elsewhere
- ✅ Wrote it down as a future task
- ❌ **Did not actually grep for it before commit**
- ❌ Self-wrote the R3-⑤ "Critic challenges" section with 4 softball questions all defending the existing fix
- ❌ Committed R3 with the fix scoped to 1 endpoint
- ❌ Moved to "R4 ① 方案自审", which started with "is there anything substantive left to do?"

The framework completed R3 with all metrics green. The Manager mentally classified the remaining work as "next round" and moved on.

### What saved us (almost too late)

Before starting R4, the user pushed back: **"先确认 R4 真没值得审计的内容"** ("first confirm R4 truly has nothing worth auditing"). Manager dispatched an Explore agent to investigate.

The first investigation answered immediately:

| Endpoint | HTTP | Bug status |
|---|---|---|
| `addSubTableRow` | POST | **VULNERABLE** — silent INSERT loss, returns 200+row data, row disappears |
| `updateSubTableRow` | PUT | **VULNERABLE** — silent UPDATE loss, identical to setCustomFields bug |
| `deleteSubTableRow` | DELETE | **VULNERABLE** — silent DELETE loss, returns 204, row remains in DB |

Three sibling endpoints with the **identical** root cause. R3's surgical fix had touched 1 of 4 affected endpoints. The other 3 would have continued silently losing production data through R4 + R5 + ship.

### The post-mortem

If the user hadn't pushed back, what would have happened?
- R4 ① would have been written as "no substantive content, hit R5 thresholds already" → fold into R5
- R5 would have been process-only (final report) → close framework
- 4 weeks later: customer reports "我创建的子表行不见了" → emergency hotfix
- Bug history records: "5 rounds of E2E quality loop didn't catch this"

The framework had:
- ✅ A deep test that caught the symptom
- ✅ A correct fix for the symptom
- ❌ No mechanism forcing the fix to scale to siblings
- ❌ No independent Critic to ask "what does this fix NOT cover?"

---

## What Rule 8 (same-cause sweep) would have done

Per Rule 8, after Step ⑥ fix:
1. Manager identifies the root cause as a searchable pattern: `JdbcTemplate.update` calls in methods without `@Transactional`
2. Manager runs `Grep -r "jdbcTemplate.update\|jdbcTemplate.execute" backend/.../engine/` and `Grep -B 5 -A 1 "jdbcTemplate.update" engine/Dynamic*.java`
3. Manager finds: `DynamicTableService.addRow / updateRow / deleteRow` all match
4. Manager checks each for `@Transactional` (on method or class) — finds 0/3 protected
5. **Commit BLOCKED until either:**
   - All 3 sibling methods get `@Transactional` (preferred — same round)
   - OR explicit deep tests scheduled with file:line + design (R4 task list, not vague "future audit")

Outcome: R3 ships the complete fix (4/4 endpoints) OR R4 has a concrete, scoped task list.

## What Rule 9 (independent Critic) would have done

Per Rule 9, in Step ⑤ result audit:
1. Manager **must** dispatch a separate `Explore` or `code-reviewer` agent for the Critic phase
2. The agent receives:
   - Diff: `git diff` showing the @Transactional addition to `setCustomFields`
   - Test: `j1-lifecycle.mjs:phaseE_customFieldRoundtrip` — the test that caught the bug
   - Question: "What does this fix NOT cover? What's the most damaging same-pattern bug that would survive this fix?"
3. The agent has zero context — it sees `setCustomFields` as one method in a class with `addSubTableRow / updateSubTableRow / deleteSubTableRow` siblings
4. Independent Critic answer (almost certainly): "the fix only patches setCustomFields. The 3 sub-table endpoints have the same pattern and same risk. Verify each is wrapped in @Transactional."
5. The Critic's verbatim output is pasted into the R3 audit doc → Manager sees it before commit

Outcome: even without Rule 8, Rule 9 alone would have surfaced the 3 sibling bugs.

---

## The mental model error Rule 8 prevents

Manager's R3 mental model:
> "I have a failing test. I made the test pass. The bug is fixed. Ship it."

This is the same error as: "I have a stack trace pointing to line 42. I fixed line 42. The bug is fixed."

The correct mental model:
> "I have a failing test. The test caught a symptom. The symptom is one instance of an anti-pattern. The fix is complete only when the anti-pattern is eliminated everywhere it appears."

Rule 8 **forces** this shift because the commit is blocked until the sweep is documented. The Manager can't skip it by being optimistic.

## The mental model error Rule 9 prevents

Manager's R3 mental model:
> "I'm efficient. I'll write the Critic challenges myself. I know what the weak spots are."

This is wrong because:
- The Manager just spent 30 minutes convincing themselves the fix is correct
- "What does this fix NOT cover?" is exactly the question they're least equipped to answer in that mental state
- Self-Critic produces softball questions → those questions get easy answers → false confidence

Rule 9 **forces** the Manager to outsource the question to an agent without that mental loading. The agent has no investment in the fix being complete and asks the obvious question.

---

## Cost / benefit analysis

**Cost of running Rule 8 on every round**:
- 5-15 minutes per round (1 grep + read 5-10 file headers + write a sweep table)
- Sometimes the sweep finds nothing → still cheap

**Cost of running Rule 9 on every audit**:
- 1 agent dispatch per audit phase = ~30s of manager time + ~2 min agent time
- Agent answer is usually 100-300 words → fast to read

**Benefit of having both rules during the R3 incident**:
- Would have saved the entire R4 round of investigation
- Would have prevented 3 P0 production bugs from shipping
- Would have shortened the framework from 5 rounds to 4 rounds
- Most important: **would have made the framework actually work as designed** (find bugs, fix bugs, ship clean code)

**Conclusion**: Rules 8 and 9 are cheap insurance against high-cost failure modes. They don't slow rounds down meaningfully, and they prevent the exact failure mode that almost shipped 3 P0 bugs in this case.

---

## Recognition signals (when to remember this case)

You're in this failure mode if:
- You just wrote "deferred to R4" / "R{N+1} carryover" / "future audit" in a round commit
- You wrote your own "Critic challenges" section with bullets that all defend the plan
- You're about to commit a fix that touches 1 file and you haven't grep'd for the same pattern elsewhere
- A test caught a bug, you fixed it, and you don't know where else the same root cause exists

Stop. Run the sweep (Rule 8). Dispatch the Critic (Rule 9). Then commit.

---

**Source commits**: `6fe099863` (R3 with the incomplete fix), R4 sibling-bug discovery happened during R4-① pre-investigation (pre-commit, no commit hash).

**Source audit docs**:
- `.claude/agent-team-outputs/2026-04-14_canvas-e2e-r3-results-audit.md` (the audit that should have caught this)
- `.claude/agent-team-outputs/2026-04-14_canvas-e2e-r4-pre-investigation.md` (the report that did, after user pushback)
