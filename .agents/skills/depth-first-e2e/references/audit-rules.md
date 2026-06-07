# Round Audit Rules (for each round's 7 steps)

Rules that each round's audit MUST enforce. Based on the 5-round web-admin retrospective lessons.

---

## The 7-step round (enhanced)

```
① 审计A: 方案自审 (coverage + depth plan)
② 审计B: Agent 独立审计 (with depth scrutiny)
③ 审计C: 修复方案 (include depth adjustments)
④ 执行 (must include at least 1 new deep test)
⑤ 审计E2E结果 (depth breakdown analysis)
⑥ 修复 bug (from deep tests, not smoke)
⑦ 验证修复 (rerun deep test to confirm)
```

Each step has enhanced rules below.

---

## Step ① Audit A (self-audit) rules

### Rule 1.1: Plan must declare depth targets

Every round plan must include:

```markdown
## Round N depth targets

| Layer | smoke | medium | deep | Total |
|-------|-------|--------|------|-------|
| L2    | N1    | N2     | **N3 ≥ 1** | M |
| L3    | X1    | X2     | **X3 ≥ 1** | Y |
| L4    | P1    | P2     | **P3 ≥ 1** | Q |

deep minimum: at least 1 new deep test per layer per round
```

If plan doesn't have this table, audit rejects.

### Rule 1.2: Plan must not defer deep to next round

Forbidden phrases:
- "deep tests → R{N+1}"
- "R{N+1} will handle this"
- "Phase 2 later"
- "next round does deep"

Replace with:
- "deep test: [specific test name], [specific file location], [expected steps]"
- "blocked because: [specific technical reason: missing backend feature X / missing test data Y]"
- "accepted as smoke (depth: 'smoke'), documented that this doesn't meet §1.3 hard rule 3"

### Rule 1.3: Plan must cite previous round's depth deficit

If R{N-1} had < target deep tests, R{N} plan must explicitly address:
- "R{N-1} achieved depth: [smoke: X, medium: Y, deep: Z]"
- "R{N} will add at least [delta] new deep tests to approach target"
- "If R{N} cannot close the gap, decisive reason: [specific blocker]"

This prevents silent carryover.

---

## Step ② Audit B (Agent audit) rules

### Rule 2.1: Critic must answer depth scrutiny first

Before the Critic does feasibility / math analysis, it must answer:

```markdown
## Depth Scrutiny (mandatory first section of Critic output)

### Q1: What is the depth distribution of the plan?
[Breakdown by layer]

### Q2: For each planned test, would it FAIL if backend API is broken?
[Walk through 3-5 sample tests]

### Q3: Are smoke tests being used to hit spec §8.2 numeric targets?
[YES/NO with evidence]

### Q4: Does the plan commit to at least 1 new deep test per layer?
[YES/NO]

### Q5: Is the "next round" phrase used anywhere in the plan?
[YES/NO — if YES, FLAG as violation]
```

Only after Q1-Q5 can the Critic discuss math/feasibility.

### Rule 2.2: Critic must check previous rounds' audit for depth failures

If any previous round audit mentioned "tests are shallow" / "need depth" / "L4 is page rendering only", the Critic MUST verify this round addresses it, not just notes it.

Tracking table:
```markdown
| Round | Depth complaint | Addressed in this round? |
|-------|----------------|---------------------------|
| R1 | L4 4 tests page-rendering only | R2 added 5 more, same depth — NO |
| R2 | 9 L4 tests, 0 deep | R3 focused on helpers — NO |
| R3 | still 0 deep | R4 Phase 2 added 17 smoke — NO |
| R4 | still 0 deep | R5 merged with R4 — NO |
```

If pattern shows "always said later, never done", Critic MUST block the round.

### Rule 2.3: Integrator must not dilute Critic's depth challenge

When Integrator synthesizes Analyst + Critic, it must preserve the Critic's depth challenge as Top-1 recommendation if Critic flagged depth issues.

Forbidden Integrator behavior:
- Burying depth concerns in "Open Questions" section
- Framing depth as "Analyst Option B is fine, Critic has some depth concerns"
- Picking Option B (more smoke tests) when Critic recommended Option A (fewer but deeper)

---

## Step ③ 修复 rules

### Rule 3.1: Plan adjustments must add depth, not more smoke

If audit finds 0 deep tests planned, the fix is to add deep tests, not to reclassify existing smoke tests.

Forbidden fix:
```diff
- L4-25 SO spec + box fields (shallow)
+ L4-25 SO spec + box fields (depth: deep)  ← just relabeling
```

Allowed fix:
```diff
- L4-25 SO spec + box fields (smoke)
+ L4-25 SO spec + box fields (smoke) + L4-deep-1 customer create full flow (deep, new)
```

### Rule 3.2: Plan must have a "deep test delivery contract"

Each round plan commits to:

```markdown
## Deep test delivery contract for Round N

| Test ID | Description | Steps | File location | Assigned |
|---------|-------------|-------|---------------|----------|
| L4-deep-1 | Customer create full roundtrip | All 12 steps | e2e-L3L4-flows.mjs L_deep_1 | R{N} |
| L4-deep-2 | Supplier create full roundtrip | All 12 steps | e2e-L3L4-flows.mjs L_deep_2 | R{N} |
```

Fill all columns. If "Assigned" is "R{N+1}" → reject.

---

## Step ④ 执行 rules

### Rule 4.1: Deep tests must run first

Execute order: deep tests → medium tests → smoke tests. This ensures deep tests get priority if time runs out.

### Rule 4.2: Each deep test must pass the 12-step checklist

See `depth-checklist.md`. If a test claims `depth: 'deep'` but misses steps, it must be reclassified on the spot.

### Rule 4.3: Actual results must update the delivery contract

After execute, update the table:

```markdown
| Test ID | Description | Steps | Status | Actual depth |
|---------|-------------|-------|--------|--------------|
| L4-deep-1 | Customer create full roundtrip | All 12 steps | ✅ PASS | deep (confirmed) |
| L4-deep-2 | Supplier create full roundtrip | All 12 steps | ❌ FAIL | deep (genuine fail) |
```

Don't hide failures. Depth failures are the most valuable — they reveal real bugs.

---

## Step ⑤ 审计 E2E 结果 rules

### Rule 5.1: Result audit must include depth breakdown

Required section in R{N}-results audit:

```markdown
## Depth Analysis

Total tests executed: X

| depth | count | PASS | FAIL | WARN | SKIP |
|-------|-------|------|------|------|------|
| smoke | Y1 | a1 | b1 | c1 | d1 |
| medium | Y2 | a2 | b2 | c2 | d2 |
| deep | Y3 | a3 | b3 | c3 | d3 |

Deep test PASS rate: a3 / Y3 = Z%
```

### Rule 5.2: Summary must distinguish "spec §8.2 compliance" vs "depth compliance"

Forbidden summary:
```
L4: 24/28 = 85.7% PASS ✅ (meets spec §8.2 R4 target)
```

Allowed summary:
```
L4 spec §8.2 compliance: 24/28 = 85.7% ✅
L4 depth compliance (§1.3 hard rule 3): 0/24 deep tests

Status: §8.2 MET, §1.3 VIOLATED. Numerical target hit via smoke padding.
```

### Rule 5.3: Audit must track bug discovery

Required metric:

```markdown
## Bug Discovery This Round

- New real app bugs found: X
- Test infrastructure issues fixed: Y
- Audit issues addressed: Z

Ratio: real bugs / total tests = X / TOTAL = %
```

If `real bugs = 0` across multiple rounds, the test suite is not providing value, regardless of PASS rate.

---

## Step ⑥ 修复 bug rules

### Rule 6.1: Only real bugs need R{N}⑥

Test infrastructure issues (shallow tests, reporting bugs, test data issues) are `test-debt` fixed in ①②③, not ⑥.

Step ⑥ is exclusively for web-admin app bugs discovered by deep tests.

### Rule 6.2: If ⑥ has nothing to fix, the deep tests were insufficient

If R{N} ran and ⑥ has 0 bugs to fix, ask:
- Did the deep tests actually exercise the right code paths?
- Is the web-admin really 100% bug-free?
- Or are the tests too shallow to find bugs?

Usually the answer is "tests too shallow" — that's a red flag for R{N+1}.

---

## Step ⑦ 验证修复 rules

### Rule 7.1: Verification must rerun the failing deep test

If ⑥ fixed a bug discovered by L4-deep-1, then ⑦ must rerun L4-deep-1 and confirm PASS.

Rerunning smoke tests does not validate deep bug fixes.

### Rule 7.2: Regression check must include all previous deep tests

R{N} ⑦ must run:
- R1 deep tests (if any)
- R2 deep tests (if any)
- ...
- R{N} deep tests

And confirm all still PASS. Depth regression is worse than smoke regression — it means real bugs came back.

---

## Meta-rule: The round is only "done" when all 7 steps have depth commitment

A round is NOT done if:
- Step ① plan has no deep target
- Step ② audit didn't run depth scrutiny
- Step ④ execute didn't write 1 new deep test
- Step ⑤ result audit missing depth breakdown
- Step ⑦ didn't rerun regression for all deep tests

If any of these is missing, the round is incomplete, regardless of spec §8.2 numbers.

---

## Enforcement summary

The depth-first-e2e skill replaces "spec §8.2 compliance = round done" with "spec §8.2 compliance + depth commitment + 1 new deep test = round done".

Numeric targets without depth = theater.
Depth without numeric targets = random improvement.
Both together = real progress.
