---
name: depth-first-e2e
description: Use when designing or executing multi-round E2E test cycles with numeric thresholds (spec §8.2 style), when a deep E2E test catches a real app bug, when auditing E2E test depth or coverage, or when an E2E round is committed but not yet deployed. Triggers on "5 轮测试", "E2E 综合测试", "多轮 E2E", "spec §8.2 阈值", "测试覆盖率", "轮次迭代", "same-cause sweep", "深度测试", "E2E bug found", "depth-first".
---

# Depth-First E2E Testing

Prevent the "shallow test padding" trap that turns multi-round E2E into a numbers game instead of a bug-discovery tool.

## Why this skill exists

We ran a 5-round E2E test cycle against web-admin. Spec §8.2 all thresholds met (L1 100% / L2 100% / L3 100% / L4 85.7%). **But we discovered only 1 real app bug in 1181 test points**. Every "L4 business flow" test was actually L1 "page accessibility" in disguise.

Root cause: every round kept saying "next round will do deep tests", but next round always had higher-priority fixes, so deep tests never happened.

This skill enforces rules that make that failure mode impossible.

## When to apply

Apply this skill when:
- User asks to run a multi-round E2E test cycle (especially with numeric thresholds)
- Agent-team audit is being used to review E2E plans/results
- Writing new L3/L4 tests (the vulnerable layers)
- Designing a new E2E test round

Do NOT apply to:
- Single ad-hoc E2E tests
- Pure L1 page navigation smoke tests (they're allowed to be shallow)
- Unit tests (different contract)

## The core hard rules

### Rule 1: Every test has a `depth` label

Every E2E test record MUST include a `depth` field. Valid values:

| depth | Criteria |
|-------|----------|
| **smoke** | Page renders / keyword exists / table visible / row count change — no submit, no persistence, no detail verification |
| **medium** | Fill + submit + API 200 captured — but no detail page verification |
| **deep** | Fill + submit + toast captured + list +1 exact + detail page field readback |

**Data-prerequisite clause**: A test that silently skips verification steps because required seed data (FG batches / related entities / feature-flag state) is missing **downgrades to medium** regardless of intent. A "deep" test with `batchRowCount: 0` recorded as PASS+WARN is not deep — it verified UI wiring only. See Rule 2's prerequisite-data checklist item.

Example:
```js
record('L4', '25', 'so_spec_box_fields', 'PASS', {
  depth: 'smoke',  // ← REQUIRED FIELD
  evidence: { hasSpecification: true, hasBoxQuantity: true },
});
```

Tests without `depth` field are considered INVALID and rejected by the audit.

### Rule 2: Each round must produce at least 1 new deep L4 test

Regardless of what the round's main focus is (infrastructure / bug fix / audit), each round MUST write at least 1 new `depth: 'deep'` L4 test.

The deep test MUST pass this checklist:
- [ ] Prerequisite data (seed SQL / FG batch / related entities) is either created by the test itself or documented as a setup precondition with a fail-fast check at the top of the test (not silently skipped — per Rule 1 data-prerequisite clause)
- [ ] `navigateTo(createPath)` — real navigation
- [ ] `await clickButton(page, '新建')` — open create dialog
- [ ] `await waitForDialog(page)` — wait for dialog
- [ ] `await fillAllRequiredFields(page, baseName)` — real field fill
- [ ] `await submitAndCheckResponse(page, [...], { factoryId, module })` — real submit with precise API filter
- [ ] `expect(submitResult.status).toBe(200)` — API status check
- [ ] `const toastText = await page.waitForSelector('.el-message--success').then(el => el.innerText())` — capture exact toast text
- [ ] `await navigateTo(listPath)` — back to list page (fresh nav, not router.push)
- [ ] `const rowsAfter = await countTableRows(page)` — count fresh
- [ ] `expect(rowsAfter - rowsBefore).toBe(1)` — strict delta
- [ ] `await page.click('table tr:first-child .edit-button')` — open detail
- [ ] `const detailFields = await page.evaluate(() => ({ ... }))` — read field values
- [ ] `expect(detailFields.name).toBe(testName)` — verify roundtrip

If even 1 step is missing, it's not deep, it's medium or smoke.

### Rule 3: Audit MUST question bug-discovery capability

Each round's audit (step ② Agent independent audit) MUST answer for each L4 test:

```
对每条 L4 测试, 逐个回答:
1. 如果被测 backend API 整个返回 500, 这条测试会 FAIL 吗?
2. 如果被测 frontend 组件崩溃不渲染, 这条测试会 FAIL 吗?
3. 如果该功能真有 bug 但 UI 表面正常, 这条测试会 FAIL 吗?
4. 这条测试发现过任何真实 bug 吗?
5. 这条测试的 prereq 数据已 seed 了吗? 如果 prereq 缺失会静默跳过验证步骤 (记 PASS+WARN), 这条测试实际只验证了 UI wiring, 按 Rule 1 data-prerequisite clause 降为 medium, 不是 deep.

如果 4 个问题答案全是"不会/没有", 这条测试是 smoke 不是 L4.
如果 Q5 答"静默跳过", 这条测试是 medium 不是 deep.
```

Audit output must include:
```
### Depth Analysis
Total L4: N
- smoke (⚠️): K tests
- medium: M tests
- deep (✅): D tests

Bug-discovery capability:
- Can catch backend API failure: X tests
- Can catch frontend render failure: Y tests
- Actual bugs found this round: Z
```

### Rule 4: "Next round" is a red flag

Audit reports must not contain phrases like:
- ❌ "deferred to next round"
- ❌ "will be done in Phase 2"
- ❌ "R4 will handle this"
- ❌ "下一轮做"

Allowed alternatives:
- ✅ "not done in this round because [specific technical blocker: X]"
- ✅ "accepted as smoke test, not targeting deep" (with explicit depth: 'smoke' label)
- ✅ "this round BLOCKED, rework required"

If any "next round" phrase appears, the audit MUST treat it as a hard stop and demand the depth work be done in-round.

### Rule 5: Critic must scrutinize depth, not just feasibility

The audit ② step's independent Critic subagent prompt MUST include:

```
MANDATORY depth scrutiny — answer these BEFORE discussing feasibility/math:

1. How many tests are depth:smoke vs medium vs deep?
2. Would any test fail if its backend API returns 500?
3. Has the plan's L4 expansion added any deep tests, or just smoke tests?
4. Does the plan commit to at least 1 new deep test per round?
5. Is Analyst recommending a path that hits numeric targets via smoke padding?

If smoke padding detected, CHALLENGE the plan, even if numerically compliant.
```

### Rule 6: Spec §1.3 hard rules beat spec §8.2 numeric targets

When spec §8.2 (e.g. "L4 ≥85%") and spec §1.3 hard rules (e.g. "filled + toast + list after 三行缺一不可") conflict:

- **Spec §1.3 wins** — test must have `filled`, `toast`, `list after` evidence
- **Don't compromise on §1.3 to hit §8.2 numbers**
- **If hitting §8.2 requires violating §1.3, report as BLOCKED and escalate to user**

A round passes the audit only if both §8.2 numbers AND §1.3 hard rules pass.

### Rule 7: Summary must report spec-denominator, not script-denominator

Results JSON summary must use:
```json
{
  "round": N,
  "schema_v3": {
    "specTotal": 30,
    "p2Deferred": ["L4-16", "L4-19"],
    "expectedFail": ["L4-03"],
    "effectiveTotal": 28,
    "actualExecuted": 24,
    "actualPass": 24,
    "depthBreakdown": {
      "smoke": 22,
      "medium": 0,
      "deep": 2
    },
    "pctOfSpec": 85.7,
    "pctDeep": 7.1
  }
}
```

**Do not** report just `{pass: 24, total: 24}` — it hides the depth problem.

### Rule 8: Bug-fix completeness — same-cause sweep before commit

When a deep test catches a real bug, **the fix MUST include a same-root-cause audit before the round can commit**. The bug is a symptom; the underlying anti-pattern may exist in sibling locations.

**Mandatory steps before commit:**

1. **Identify the root cause as a searchable pattern**, e.g.:
   - "raw `JdbcTemplate.update` in a method without `@Transactional`"
   - "`@RequireRole` missing on POST controller"
   - "`Map<String,Object>` deserialization without size limit"
   - "`page.evaluate` returning `text.includes(keyword)` as evidence"

2. **Use Grep/Glob to find ALL instances** in the relevant code area. Cast a wide net — the same controller class, sibling endpoints, the calling service, the same package.
   - **Subagent caveat**: if delegating the sweep to an Explore/general-purpose subagent, expect 50-80% false-positive rate on cross-file pattern searches. Subagents miss URL-template construction (e.g. `url: \`/\${factoryId}/\${action}\``), dictionary-dispatched action handlers, and reflection/annotation patterns. Hand-verify every "orphan" / "vulnerable" match with a direct Grep before counting it. See auto-memory `feedback_subagent_code_search_unreliable.md`.

3. **Report findings in the audit doc** under a "Same-cause sweep" section:
   - Patterns searched (with grep commands)
   - Files/lines matched
   - Verdict per match: **vulnerable** / **safe** / **needs verification**

4. **Vulnerable instances must be either:**
   - Fixed in the same round (preferred), OR
   - Explicitly scheduled as deep tests for the next round, with file:line citations + concrete test design (not vague "to be done")

**The R{N} commit is BLOCKED if:**
- Same-cause sweep was not performed
- Sweep found vulnerable instances and they are neither fixed nor scheduled
- The "schedule for next round" is vague (no test design, no file refs)

**Audit step ⑤ MUST verify** by checking:
- Does the audit doc include a "Same-cause sweep" section?
- Does the section list grep patterns + count of matches + verdict per match?
- If absent → audit FAILs → re-do sweep before commit

**Why**: R3 of canvas-security-e2e fixed `setCustomFields` missing `@Transactional` but left 3 sibling endpoints (`addSubTableRow` / `updateSubTableRow` / `deleteSubTableRow`) with the **identical** bug. The framework completed R3 with all metrics green; the bugs were only caught when R4-prep manually invoked a sibling-scan. Without this rule, the 3 latent silent-data-loss bugs would have survived all 5 rounds and shipped to production. See `references/case-r3-incomplete-fix.md`.

### Rule 9: Critic must be a separate agent, not self-impersonation

For the audit's Critic phase (independent-agent review; the old agent-team skill's 4-phase flow was retired 2026-07-28 — native subagents below are the standard mechanism):

1. **The Critic phase MUST be executed via a separate Agent invocation**, not inline by the Manager writing "Critic challenges" sections themselves.

2. **Why**: Self-impersonated Critic suffers from confirmation bias. The Manager already believes the fix is complete when writing the Critic phase, so the challenges they generate are softball questions whose answers they already know. Independent Critic is what makes 4-phase audit valuable.

3. **Standard mechanism**: run a single-agent `Explore` or `code-reviewer` sub-agent that has zero conversation context with the Manager. Pass it:
   - The fix diff (`git diff` output)
   - The test that caught the bug (file path + testId)
   - The question: "What does this fix NOT cover? What's the most damaging same-pattern bug that would survive this fix?"
   The agent's answer must be pasted **verbatim** into the audit doc (not paraphrased).

4. **Round completion is BLOCKED if** audit doc has "Critic challenges" or similar section but no evidence of independent agent execution (no agent ID, no timestamp, no separate output block).

5. **Self-Critic is acceptable for**:
   - Step ① 方案自审 (initial plan brainstorm)
   - Daily progress notes
   - Anything *outside* the formal R{N}-② or R{N}-⑤ audit phases
   But **not** for the Critic phase itself.

**Why**: R3 of canvas-security-e2e skipped the independent Critic phase. The Manager wrote 4 self-impersonated "Critic challenges" all of which validated the existing plan. Independent Critic would have asked "are there sibling endpoints?" — exactly the question the Manager was unconsciously avoiding. See `references/case-r3-incomplete-fix.md`.

### Rule 10: Commit ≠ delivery — detection without delivery is the final anti-pattern

A round that commits fixes on a development branch has completed **detection**, not delivery. Production is still running broken code until the fixes actually reach it.

**Mandatory before claiming a round "complete":**

1. **Branch pushed to remote** — `git push -u origin <branch>` has been executed, OR explicit reason documented (e.g., "local-only experiment", "pre-review sandbox")
2. **PR opened** — `gh pr create --base main` (or equivalent) with link recorded in round summary, OR explicit reason (e.g., "trunk-based dev, commits directly land")
3. **Production deployment plan** — one of:
   - Scheduled deploy with timestamp, responsible party, and rollback plan, OR
   - Explicit "this round is test-env-only, no prod impact, here's why" with specific reasoning (not vague "later")
4. **R{N+1} backlog ticketed** — each deferred item exists as a real ticket (GitHub issue / Linear / internal tracker), NOT just a bullet in an audit doc. Audit docs disappear from attention; tickets don't.
5. **CI integration status** — if the test suite isn't running in CI, the round summary must state when it will be + who owns that integration (or "ADR-X says CI is out of scope because Y").

**Failure modes this rule prevents:**

- **5-round completion theater** — "R1-R5 all green" while production still has the bugs we found
- **Backlog amnesia** — R6 items buried in markdown files nobody re-opens
- **Branch graveyard** — development branches sitting on local machine unnoticed, getting lost when rebased or deleted
- **CI blind spot** — next regression doesn't surface until someone manually runs the suite weeks later

**Round cannot be claimed "complete" if any Rule 10 item is skipped without documented reason.** "Documented reason" means a real rationale like "prod deploy requires PM approval scheduled for 2026-04-20" — NOT "we'll do it later" or silent omission.

**Why**: Canvas-security-e2e R1-R5 (Apr 14-15 2026) completed with 91/91 PASS, found 2 real P0 silent-data-loss bugs, and **shipped none of them to production**. Branch `e2e/v1-framework` sat unpushed, PR was never opened, production still ran pre-R3 code. The framework's Step ⑦ was interpreted as "round done" but actually only delivered an unshipped commit on a local branch. R6 backlog items (AggregateFormulaExecutor fix, prod orphan migration) had "explicit technical reasons" for deferral — but nobody tracked them as tickets, so they existed only as audit doc bullets. If a developer reading this skill thinks "5 rounds shipped", they're wrong — 5 rounds **detected**, delivery is a separate phase. See `references/case-r5-delivery-gap.md`.

### Rule 11: Breadth — module coverage matrix (prevents depth tunnel)

Depth without breadth is **depth tunnel**: you go arbitrarily deep in one module while the rest of the app has zero coverage. A framework that runs 12 rounds on sales while SmartBI / system admin / daily management / data analytics sit at zero is not a tested system — it's a well-tested subset of a test-less system.

**Mandatory for any multi-round campaign (3+ rounds):**

1. **Round 0 produces a module inventory** — list every major user-facing module (~8-20 in ERP-style systems). Inventory lives in round-results JSON or a dedicated `coverage-matrix.md`.
2. **Every round updates the coverage matrix**: each module has depth in `{none, smoke, medium, deep}`. Update before Step ⑦.
3. **No round may go deep on module X while any other module sits at `none`** unless the `none` module has a documented exemption (e.g., "mobile-only", "not yet released", "intentionally out of scope" — must name module, reason, owner).
4. **Hard stop**: if 3 consecutive rounds leave any module at `none` without exemption, the next round MUST be a **breadth round** covering every `none` module with at minimum smoke tests (happy-path load + render check).
5. **"Scheduled in triage doc" does NOT count as coverage**. The bug triage table with 13 rows and owners is scheduling, not coverage. Until a test actually runs against the module, its coverage stays at `none`.

**Rationalizations this rule explicitly blocks** (captured from baseline testing):

| Excuse | Reality |
|--------|---------|
| "Focus wins — R18 goes deep on the new bug cluster" | Blocks Rule 11.3. Pick 1 bug in an uncovered module + smoke the rest of `none` modules in same round. |
| "Breadth-first violates Rule 2 (1 deep L4/round)" | No. Breadth round still has ≥1 new deep L4 — it's just on a previously-uncovered module. |
| "Rule 8.4 lets us schedule siblings for later" | Rule 8.4 is for *same-cause siblings*, not for unrelated modules. Different skill mechanism. |
| "We'll cover SmartBI in a later round" | Already Rule 4 territory; Rule 11 extends it to modules. "Later" = never in practice. |

**Why**: Evidence from 2026-04-15 customer bug report — **0 of 16 bugs caught by 12 rounds of depth-first E2E**. 13 of those 16 bugs lived in modules at `none` coverage since Round 0 (SmartBI, 系统管理, 日常管理, 数据分析, 经营驾驶舱). Those bugs were structurally undetectable by our framework no matter how many more depth rounds we ran on sales. This is the cost of a depth-only discipline without a breadth complement.

**Cross-module scope note for Rule 8**: Rule 8's "relevant code area" phrasing is too narrow in practice. When the bug's root cause is a *pattern* (null guard missing / transaction missing / auth check missing), sweep scope is the **pattern**, not the module. R7 swept customer+supplier validation rules but would not have caught 盘点管理 (stock-take) null bug #11 from the same bug report — different module, same pattern. When sweeping, always ask: "could this same idiom exist in a completely unrelated module?"

## Round lifecycle with depth enforcement

### Step ① 审计A (Self-audit)
Plan must include:
- L4 deep tests planned this round: at least 1
- Depth distribution target: `{smoke: X, medium: Y, deep: Z}` with Z ≥ 1
- Self-Critic is acceptable here (per Rule 9.5)

### Step ② 审计B (Independent agent audit) — REQUIRED INDEPENDENT AGENT
- **Rule 9 enforced**: Critic phase MUST be a separate Agent invocation, not self-impersonation
- Critic must apply Rule 5's depth scrutiny checklist. If plan doesn't commit to deep tests, audit must BLOCK.
- Audit doc must include the agent's verbatim output + agent ID + timestamp

### Step ③ 修复 (Fix plan)
Adjust plan to include at least 1 deep test if missing.

### Step ④ 执行 (Execute)
Execute includes writing at least 1 deep test.

### Step ⑤ 审计结果 (Independent agent audit) — REQUIRED INDEPENDENT AGENT
- **Rule 9 enforced**: same independence requirement as Step ②
- Audit output includes Depth Analysis block per Rule 3
- **If a real bug was found**, Step ⑤ MUST verify the same-cause sweep happened (Rule 8)

### Step ⑥ 修复 bug (Fix bugs) — SAME-CAUSE SWEEP REQUIRED
- Real bugs (from deep tests) fixed here
- **Rule 8 enforced**: when a deep test catches a bug, a same-cause sweep is mandatory before commit
- Sweep must be documented in the audit doc with grep patterns + match list + verdict per match
- Vulnerable sibling instances must be either fixed in this round or scheduled with concrete test design
- **Non-trivial fix diffs (>50 LOC, or touching async / interceptor / transaction boundaries, or adding new UI flows) MUST be reviewed by an independent agent (`superpowers:code-reviewer` or a separate Explore agent with zero conversation context) before Step ⑦ commit.** Same anti-confirmation-bias rationale as Rule 9 — self-review of fix code finds < 50% of the bugs a clean reviewer finds (one session: self-review 0/3, reviewer 3/3 caught unreachable-branch + edge-case + missing-guard).

### Step ⑦ 验证修复 (Verify fixes) — branch commit, NOT terminal
- Must include rerun of deep test to confirm bug fix
- **If Step ⑥ same-cause sweep found additional vulnerable instances**, the rerun must include the new tests covering those instances (or those instances must be scheduled into the next round per Rule 8.4)
- **Commit to development branch only** — Step ⑦ does NOT include push, merge, or deploy
- **Round cannot close until Step ⑧ delivery plan is written** (Rule 10 enforced)

### Step ⑧ 交付 (Delivery plan) — REQUIRED FINAL STEP
- Commit on a development branch is an intermediate artifact, not a shipped fix
- Step ⑧ produces an explicit delivery plan with concrete owners and dates:
  - **Branch push**: `git push -u origin <branch>` executed (or explicit reason not to)
  - **PR creation**: `gh pr create --base main` with link in round summary
  - **Production deployment**: scheduled + responsible party + rollback plan (or explicit reason round doesn't need prod deploy)
  - **R{N+1} backlog tickets**: each deferred item has a tracked ticket (GitHub issue / Linear / internal tracker), NOT just a bullet in an audit doc
  - **CI integration check**: if test suite isn't in CI, Step ⑧ must document why + when it will be

### About "round complete"

A round with committed tests + zero FAILs + zero WARNs is **NOT automatically delivery-ready**. Delivery is the last mile. The round is "test-complete" at Step ⑦, but "delivery-complete" only at Step ⑧.

If Step ⑧ is skipped / postponed, the fixes on the branch are **not in production** — customers are still affected by the bugs the framework found. This is the "detection without delivery" anti-pattern.

## Detection: Is this E2E suite compromised?

Run this diagnostic on any existing E2E test suite:

```bash
# Check 1: How many tests have depth field?
grep -c "depth:" tests/e2e-comprehensive/e2e-L3L4-flows.mjs
# If 0 → Rule 1 violated

# Check 2: How many tests submit forms?
grep -c "submitAndCheckResponse\|submitBtn.click" tests/e2e-comprehensive/e2e-L3L4-flows.mjs
# Divide by total L4 function count. If < 50% → suspicious

# Check 3: How many tests read detail pages?
grep -c "navigateTo.*detail\|/detail/\|click.*row.*edit" tests/e2e-comprehensive/e2e-L3L4-flows.mjs
# If 0 → no deep tests exist

# Check 4: How many tests use body.innerText.includes?
grep -c "innerText.*includes\|text.includes\|textContent.*includes" tests/e2e-comprehensive/e2e-L3L4-flows.mjs
# If high → lots of shallow keyword checks

# Check 5: checkDropdownContains usage?
grep -c "checkDropdownContains(" tests/e2e-comprehensive/e2e-L3L4-flows.mjs
# If 0 and function is defined → dead code / facade engineering
```

If any check fails, the suite has the "shallow padding" problem and needs remediation.

## Bug patterns to watch for (seen in the wild)

### Pattern 1: `hasFormField` facade

```js
// ❌ Shallow — matches any label containing "客户" substring
async function hasFormField(page, labelText) {
  return page.evaluate((text) => {
    const labels = Array.from(document.querySelectorAll('.el-form-item__label'));
    return labels.some(l => l.textContent.includes(text));
  }, labelText);
}
```

"客户" matches "客户经理", "客户备注", "客户等级", and the sidebar menu item. Passes even if the form has no customer dropdown.

### Pattern 2: "Click new + count labels"

```js
// ❌ Shallow — never submits
async function L4_N_Test(page) {
  await navigateTo(page, '/some/page');
  await clickButton(page, '新建');
  await page.waitForTimeout(3000);
  const fields = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('.el-form-item__label'))
      .map(l => l.textContent).slice(0, 15);
  });
  record('L4', 'N', 'xxx', fields.length > 0 ? 'PASS' : 'FAIL', { fieldCount: fields.length });
}
```

Never calls submit. If backend `POST /orders` is totally broken, this test still passes.

### Pattern 3: `text.includes('关键字')` keyword matching

```js
// ❌ Shallow — checks for substring anywhere on page
const hasFields = await page.evaluate(() => ({
  hasSpec: text.includes('规格'),
  hasBox: text.includes('箱数'),
}));
```

"规格" appears in page title, column headers, placeholder text, sidebar, help tooltips. Passes even if those fields don't exist in the form.

### Pattern 4: `rowsAfter > rowsBefore` lax persistence

```js
// ❌ Shallow — accepts delta=6 from dirty data
const persisted = rowsAfter > rowsBefore;
```

Correct form: `rowsAfter === rowsBefore + expectedDelta` where `expectedDelta` is usually 1.

### Pattern 5: `checkDropdownContains` defined but not called

```js
// Defined at line 111 ...
async function checkDropdownContains(page, selectSelector, searchText) { ... }

// ... but nowhere in the file is it called
```

Dead code / facade engineering. Either wire it up to L3-1/L3-2 or delete.

## Correct patterns

### Correct: Deep L4 test template

```js
async function L4_deepTemplate(page) {
  const testId = 'L4-deep-N';
  console.log(`\n--- ${testId} ---`);

  // Step 1: Navigate to list (record baseline)
  const nav1 = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (nav1 !== 'OK') { record('L4', 'deep-N', 'navigate', 'FAIL', { result: nav1 }); return; }
  const beforeResult = await countTableRows(page);
  if (beforeResult.error) { record('L4', 'deep-N', 'list', 'FAIL', { error: beforeResult.error }); return; }
  const rowsBefore = beforeResult.count;

  // Step 2: Click create button
  const clicked = await clickButton(page, '新建');
  if (!clicked) { record('L4', 'deep-N', 'click_create', 'FAIL', { depth: 'deep' }); return; }

  // Step 3: Wait for dialog
  const dialog = await waitForDialog(page);
  if (!dialog) { record('L4', 'deep-N', 'open_dialog', 'FAIL', { depth: 'deep' }); return; }

  // Step 4: Fill all required fields (handles Element Plus compound fields)
  const testName = `E2E_DEEP_${Date.now().toString(36)}`;
  await fillDialogInput(page, testName);
  const filledFields = await fillAllRequiredFields(page, testName);

  // Step 5: Submit with precise API filter
  const submitResult = await submitAndCheckResponse(page, ['确定', '保存', '提交'], {
    factoryId: FACTORY_ID,
    module: 'sales/orders',
  });
  if (!submitResult.ok) {
    record('L4', 'deep-N', 'submit', 'FAIL', {
      depth: 'deep',
      reason: submitResult.reason,
      status: submitResult.status,
      filledFields,
    });
    return;
  }

  // Step 6: Capture toast text
  let toastText = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toastText = await toast.innerText();
  } catch { toastText = '(not captured)'; }

  // Step 7: Navigate back to list with fresh goto (not router.push)
  await page.goto(`${BASE}/sales/orders`, { waitUntil: 'domcontentloaded' });
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper')) break;
  }
  await page.waitForTimeout(1000);

  // Step 8: Verify list +1 exactly
  const afterResult = await countTableRows(page);
  const delta = afterResult.count - rowsBefore;
  if (delta !== 1) {
    record('L4', 'deep-N', 'persistence', delta > 1 ? 'WARNING' : 'FAIL', {
      depth: 'deep',
      delta,
      rowsBefore,
      rowsAfter: afterResult.count,
    });
    return;
  }

  // Step 9: Open detail and verify roundtrip
  await page.click('.el-table__body-wrapper .el-table__row:first-child');
  await page.waitForTimeout(2000);
  const detailValue = await page.evaluate((name) => {
    return document.body.innerText.includes(name);
  }, testName);

  // Step 10: Record PASS with full evidence
  record('L4', 'deep-N', 'full_roundtrip', detailValue ? 'PASS' : 'FAIL', {
    depth: 'deep',
    filled: testName,
    filledFields,
    apiStatus: submitResult.status,
    apiUrl: submitResult.url,
    toastText,
    rowsBefore,
    rowsAfter: afterResult.count,
    delta: 1,
    detailRoundtrip: detailValue,
  });
}
```

### Correct: Deep L3 test template (real dropdown)

```js
async function L3_deep_customerDropdown(page) {
  // Step 1: Create customer (first, via deep flow)
  const customerName = `E2E_Cust_DEEP_${Date.now()}`;
  await createEntityDeep(page, '/sales/customers', customerName);

  // Step 2: Navigate to SO create
  await navigateTo(page, '/sales/orders');
  await clickButton(page, '新建');
  await page.waitForTimeout(2000);

  // Step 3: Click customer select to open dropdown
  const customerSelect = await page.$('.el-dialog .el-select[placeholder*="客户"], .el-drawer .el-select[placeholder*="客户"]');
  if (!customerSelect) {
    record('L3', 'deep-1', 'find_customer_select', 'FAIL', { depth: 'deep' });
    return;
  }
  await customerSelect.click();
  await page.waitForTimeout(1500);

  // Step 4: Type customer name to filter
  const searchInput = await page.$('.el-select-dropdown__search input, .el-popover input');
  if (searchInput) {
    await searchInput.fill(customerName);
    await page.waitForTimeout(1000);
  }

  // Step 5: Verify customer appears in dropdown options
  const options = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .map(o => o.textContent?.trim())
      .filter(Boolean);
  });
  const found = options.some(o => o.includes(customerName));

  record('L3', 'deep-1', 'customer_in_so_dropdown', found ? 'PASS' : 'FAIL', {
    depth: 'deep',
    searchedFor: customerName,
    optionsFound: options.length,
    optionsSample: options.slice(0, 5),
    customerInDropdown: found,
  });
}
```

## Remediation for existing compromised suites

If a suite has the shallow padding problem:

1. **Don't delete existing tests** — they have smoke value
2. **Relabel them with `depth: 'smoke'`**
3. **Add new `depth: 'deep'` tests alongside** (minimum 3-5 per L4 major area)
4. **Update summary schema** to show depth breakdown
5. **Rewrite the final report** to honestly present depth distribution
6. **Mark old rounds as "framework + smoke tests"** rather than "complete E2E"

Don't pretend the old rounds were deep. Own the gap, fix it forward.

## References

- `references/anti-patterns.md` — 5 shallow test anti-patterns with examples
- `references/depth-checklist.md` — 12-step deep test checklist
- `references/audit-rules.md` — Round-by-round audit rules enforcing depth
- `references/case-r3-incomplete-fix.md` — Real R3 incident where R8/R9 (same-cause sweep + independent Critic) would have saved 2 rounds and prevented 3 latent P0 bugs from shipping
- `references/case-r5-delivery-gap.md` — Real R5 incident: 5 rounds completed with 91/91 PASS but 0 of the 2 P0 bugs found were delivered to production. Led to Rule 10 (commit ≠ delivery)
- `references/case-r7-rating-bug-sweep.md` — Real R7 example: retroactive Rule 8 sweep found 13 latent broken sales_order rules + 5 defense-in-depth opportunities. Shows how Rule 8 catches 20× more issues than the original fix.
- `references/deep-test-patterns.md` — **R6-R11 patterns (Element Plus/Playwright specific)**: dialog investigation, el-input-number native setters, :visible locators for teleport popovers, el-select filterable search, ElMessageBox confirmation, stable row lookup by orderNumber, cross-entity money consistency verification, createSOQuick helper composition. 15 concrete patterns learned from 11 successful deep tests.

## Activation rules

**MUST apply when**:
- User mentions "5 轮 / 多轮 E2E 测试"
- User mentions "spec §8.2 阈值" or numeric pass rate targets
- Agent-team skill is being used for E2E audit
- Writing new L3/L4 test functions
- Reviewing E2E test results
- **A deep test caught a real bug** (Rule 8 trigger — same-cause sweep MUST follow)

**CANNOT skip**:
- Rule 2 (min 1 deep per round). If user asks to skip depth work, respond:
  > "I cannot write another round of shallow tests. Spec §1.3 hard rules 3-4 require deep testing. If we hit blockers on deep tests, let's document them and slow down, but I won't add more smoke tests that look like L4."
- **Rule 8 (same-cause sweep). When a deep test catches a bug, refuse to commit the fix until the sibling sweep is documented:**
  > "I cannot commit this bug fix without first running a same-cause sweep. The bug we found is one symptom of an anti-pattern that may have sibling instances. Per depth-first-e2e Rule 8, I need to grep for the pattern across the relevant code area and report findings before this round can close."
- **Rule 9 (independent Critic). When the user asks to skip the Critic agent and just write challenges inline:**
  > "I cannot self-impersonate the Critic phase per Rule 9. Self-Critic suffers from confirmation bias — I already believe my fix works, so my 'challenges' will be softball questions. I'll dispatch a separate agent and paste its verbatim output."
- **Rule 10 (commit ≠ delivery). When the round is committed but not pushed/merged/deployed and the user asks to move on:**
  > "The commit is on a development branch. Per Rule 10, the round is test-complete but not delivery-complete. Before I mark this truly done, we need: (a) branch pushed, (b) PR opened, (c) prod deploy plan with owner + date, (d) R{N+1} backlog items as tracked tickets, (e) CI integration status. If any item has a documented reason to skip, say which and why."

**Escalate to user when**:
- Spec §8.2 and §1.3 are in conflict
- Deep test is blocked by real technical constraint (e.g. missing backend feature)
- Round audit reveals depth: 0 after executing
- Same-cause sweep finds vulnerable instances and the user wants to "ship the surgical fix and address siblings later" — Rule 8 forbids this without a concrete schedule
