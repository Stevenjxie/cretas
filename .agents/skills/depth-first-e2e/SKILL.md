---
name: depth-first-e2e
description: >
  Use when designing, running, or auditing multi-round E2E campaigns; enforcing
  numeric coverage thresholds; writing L3/L4 deep tests; a deep E2E test finds
  a real bug; reviewing E2E depth/coverage; or deciding whether an E2E round is
  test-complete versus delivery-complete.
---

# Depth-First E2E

Use this skill to prevent shallow page checks from being reported as deep business coverage. It applies to multi-round E2E campaigns, L3/L4 tests, coverage audits, and bug-fix rounds triggered by E2E failures.

For concrete Playwright implementation patterns, use `project-playwright-e2e`. For ordinary one-off UI verification, use `e2e-web-admin`.

## Core Rule

Every E2E record must include a `depth` field:

| depth | Criteria |
|---|---|
| `smoke` | Page renders, keyword exists, table visible, or loose row count. No real submit/persistence/detail verification. |
| `medium` | Form filled and submitted, API/toast captured, but no full detail/readback verification. |
| `deep` | Fill + submit + API/toast + fresh list/persistence + detail or downstream readback. |

If prerequisite data is missing and the test silently skips verification, downgrade it to `medium` or `smoke`. A PASS+WARN with no real data is not deep.

## Hard Rules

1. Every multi-round campaign must keep a coverage matrix by module and depth.
2. Each round must add at least one new `depth: "deep"` L4 or equivalent business-flow test.
3. Do not satisfy numeric targets by adding smoke tests that look like L4.
4. Audit must report depth breakdown: smoke, medium, deep.
5. A deep test that finds a real bug requires a same-cause sweep before claiming the fix is complete.
6. A committed branch is not delivered. Track push, PR/merge path, deployment plan, backlog tickets, and CI status before calling a round delivery-complete.
7. Avoid "next round will handle it" unless there is a concrete tracked ticket, owner, and test design.

## Deep Test Checklist

A deep L4 test should include:

- Prerequisite data created by the test or fail-fast checked.
- Real navigation to the create/workflow entry point.
- Form/dialog opened and required fields filled.
- Submit action with precise API or UI success evidence.
- Exact toast or response captured.
- Fresh navigation or refresh after submit.
- Strict persistence check, usually list delta exactly `+1` or stable business-key lookup.
- Detail page or downstream module readback of submitted values.
- Screenshot or structured evidence path.

If any core step is missing, label the test `medium` or `smoke`.

## Audit Questions

For each L4/deep-claimed test, answer:

- Would it fail if the backend endpoint returned 500?
- Would it fail if the frontend form rendered but submit was broken?
- Would it fail if persistence silently failed?
- Would it fail if a downstream business state was wrong?
- Did it create/check prerequisite data instead of silently skipping?
- Has this test ever caught or could it catch a real app bug?

If most answers are "no", it is not deep.

## Same-Cause Sweep

When a deep test finds a real bug:

1. Identify the root cause as a searchable pattern.
2. Search the relevant code area and likely sibling modules with `rg`.
3. Report patterns searched, files/lines matched, and verdicts: `vulnerable`, `safe`, or `needs verification`.
4. Fix vulnerable sibling instances in the same round when feasible.
5. If deferring any vulnerable sibling, create a concrete tracked item with file references and a test design.

Do not commit or call the round complete without this sweep.

## Required Round Summary

Use a schema like:

```json
{
  "round": 1,
  "specTotal": 30,
  "effectiveTotal": 28,
  "actualExecuted": 24,
  "actualPass": 23,
  "depthBreakdown": {
    "smoke": 14,
    "medium": 7,
    "deep": 3
  },
  "actualBugsFound": 1,
  "sameCauseSweep": "done | not_applicable | blocked",
  "deliveryStatus": "test_complete | delivery_complete | blocked"
}
```

Do not report only `{ pass, total }`; it hides shallow coverage.

## References

Load only what is needed:

- `references/depth-checklist.md`: detailed deep-test checklist.
- `references/audit-rules.md`: audit gates and report format.
- `references/anti-patterns.md`: shallow-test anti-patterns.
- `references/deep-test-patterns.md`: Element Plus/Playwright patterns learned from prior rounds.
- `references/case-r3-incomplete-fix.md`: same-cause sweep failure case.
- `references/case-r5-delivery-gap.md`: test-complete versus delivery-complete case.
- `references/case-r7-rating-bug-sweep.md`: sweep example.
