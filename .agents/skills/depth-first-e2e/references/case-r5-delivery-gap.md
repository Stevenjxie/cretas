# Case Study: R5 Delivery Gap — Why Rule 10 Exists

**Date**: 2026-04-14 / 2026-04-15
**Suite**: `tests/canvas-security-e2e/` (5-round quality loop R1–R5)
**Outcome**: 5 rounds completed with 91/91 PASS, found **2 real P0 silent-data-loss bugs**, but **shipped ZERO of them to production** — because the framework had no step for "deliver". Led to Rule 10 being added post-R5.

---

## What happened

Across 5 rounds the framework:
- ✅ Found 2 real P0 bugs (R3: `setCustomFields` missing `@Transactional`; R4: sub-table `parent_id` hardcoded UUID)
- ✅ Rule 8 sweep found 3 sibling instances of the R3 bug, R4 fixed all of them
- ✅ Added 17 new deep round-trip tests (phaseE/F/G/H + J4-9/10/11)
- ✅ Skill itself upgraded mid-cycle (commit `2620e0801`) adding Rules 8 + 9
- ✅ All 5 rounds committed: `734fae813`, `57c19ea8d`, `6fe099863`, `7b23217b0`, `f90650e77`
- ✅ All 3 independent Critic audits validated the cycle as "HONEST-AND-VALUABLE, zero metric inflation"

At this point the Manager said "5-round framework complete 🎉" to the user. That framing was **wrong**.

When the user asked "so there are no problems at all?", the following gaps became visible:

## The delivery gap (what was NOT done)

| Gap | Reality |
|-----|---------|
| Branch pushed to remote? | **No** — `e2e/v1-framework` was local-only |
| PR opened? | **No** — fixes never reached main branch review |
| Production deployed? | **No** — test env 10011 was the only deploy target. Production (10010) still ran pre-R3 code |
| R6 backlog tracked as tickets? | **No** — deferred items only existed as bullets in audit markdown files |
| CI integration? | **No** — test suite runs only when someone manually invokes it |
| Customers affected by the 2 P0 bugs? | **Yes, still** — production still has the silent-data-loss + UUID-cast bugs the framework found |

The framework had framing that was technically accurate ("commits made, tests pass") but **systemically misleading** ("round complete = customer protected"). A reader seeing "5 rounds, 91/91 PASS" would reasonably assume bugs were fixed in production. They weren't.

## Why the framework missed this

The pre-Rule-10 framework's 7 steps per round were:

1. 方案自审 (self-audit plan)
2. Agent-team 4 阶段审计 (independent audit)
3. 修方案 + code-reviewer
4. 执行测试 (execute)
5. Agent-team 审计结果
6. 修复 bug (fix bug)
7. Verification + commit

**None of these steps is "deliver"**. Step ⑦ ended with a commit on a development branch. Whether that commit ever reached production was... out of scope? implicit? Never defined.

The Manager also chose narrow per-round scope explicitly to avoid "scope creep" (a reaction to the parallel chat's padding experience). But "narrow scope per round" compounded across 5 rounds into "never addressed delivery".

Individual defer decisions were each technically justified:
- AggregateFormulaExecutor → "needs aggregate formula test harness, 1-2 days"
- setClauses.isEmpty() Option B → "needs 8-caller audit, breaking change"
- Prod sub-table back-migration → "needs DBA maintenance window, not Claude work"
- Branch push / PR → never even discussed as a step

Rule 4 (no "next round syndrome") forbids vague "defer to next round". But it **allows** "defer with specific technical reason". Each above reason passes Rule 4 individually. But 4 rule-compliant defers + 1 undiscussed delivery step compounded into zero delivery.

## The user's question

The Manager said "R5 complete, 5-round cycle complete 🎉" on 2026-04-15. The user responded:

> 所以没有任何问题了吗? ("So there are no problems at all?")

That single question surfaced the entire delivery gap. Without that question, the Manager would have moved on, leaving:
- Branch unpushed (could be lost if someone rebased)
- Prod bugs still live (customers silently affected)
- R6 backlog buried in markdown nobody re-reads

**The framework should not rely on the user asking the right question to avoid shipping nothing.**

## What Rule 10 enforces

Rule 10 adds a mandatory Step ⑧ (Delivery plan) after verification + commit. The round is **test-complete** at ⑦ but **delivery-complete** only at ⑧.

Step ⑧ requires:
1. Branch push executed (or explicit documented reason not to)
2. PR opened (or explicit reason)
3. Production deployment plan with owner + date + rollback (or documented "test-env-only, reason: X")
4. R{N+1} backlog as tracked tickets (NOT markdown bullets)
5. CI integration status (scheduled or documented ADR-exception)

Without Rule 10, frameworks that claim to "improve quality" can complete their entire cycle while silently leaving customers exposed to the bugs they detected. That's the detection-without-delivery anti-pattern.

## Recognition signals (when to remember this case)

You're in the delivery gap if:
- You just said "round/cycle complete 🎉"
- Your last action was `git commit`, not `git push`
- Your R{N+1} backlog is a bulleted list in a markdown file
- The branch has accumulated commits the user probably hasn't seen yet
- You mentally classified "ship to prod" as "someone else's problem" or "later"
- You equated "tests pass in test env" with "customer bug fixed"

**Stop. Don't call the round complete.** Run through the Rule 10 checklist first. Push the branch. Open the PR. Write a deploy plan. Track the tickets. Only then can you honestly claim completion.

## The lesson for frameworks

A test framework that leaves detected bugs in production is a **test theater**. The output looks impressive (91/91 PASS, 2 P0 bugs caught, Rule 8 sweep validated) but the customer outcome is indistinguishable from "did nothing".

Rule 10 prevents that outcome by making delivery a mandatory, enumerated step — not an implicit follow-up that falls through the cracks.

---

**Source commits**: `734fae813` → `57c19ea8d` → `6fe099863` → `2620e0801` → `7b23217b0` → `f90650e77` (all on branch `e2e/v1-framework`, none reached `main` at round closure)

**Audit doc trail**: `.claude/agent-team-outputs/2026-04-14_canvas-e2e-r{1,2,3,4,5}-*.md`

**Related rules**:
- Rule 4 (no next-round-syndrome) — still applies, but was insufficient alone
- Rule 8 (same-cause sweep) — helped find sibling bugs in code, didn't help find sibling gaps in process
- Rule 9 (independent Critic) — final R5-⑤ Critic didn't flag the delivery gap because it wasn't in their prompt scope

**Related skills**:
- `superpowers:finishing-a-development-branch` — dedicated skill for the "I have commits, now what?" question. Rule 10 essentially requires invoking a flow like this one.
- `engineering:deploy-checklist` — pre-deploy verification. Rule 10 should integrate with it when prod deploy is part of the round.
