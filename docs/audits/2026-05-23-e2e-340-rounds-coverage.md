# Canvas E2E 17-Tab × 20-Round Coverage Matrix

**Generated:** 2026-05-22T23:44:04
**Source:** `tests/v1-e2e/test-results/canvas-results.json`
**Test env:** http://139.196.165.140:8097 (test) → backend 10011 (Java) / 8084 (Python)
**Auth:** f006_admin / F006 (FACTORY)

---

## Summary

- **Total rounds:** 340
- **PASS:** 331 (97.4%)
- **FAIL:** 9
- **SKIP:** 0

**Target:** >=320/340 (>=95%)
**Status:** MET threshold (actual 97.4%)

---

## Per-Tab Matrix

| Tab | File | PASS | FAIL | SKIP | Total | Pass% |
|-----|------|------|------|------|-------|-------|
| alerts | alerts-20rounds.spec.ts | 20 | 0 | 0 | 20 | 100% |
| business-rules | business-rules-20rounds.spec.ts | 19 | 1 | 0 | 20 | 95% |
| cron | cron-20rounds.spec.ts | 17 | 3 | 0 | 20 | 85% |
| food-safety | food-safety-20rounds.spec.ts | 20 | 0 | 0 | 20 | 100% |
| indicators | indicators-20rounds.spec.ts | 19 | 1 | 0 | 20 | 95% |
| notify | notify-20rounds.spec.ts | 19 | 1 | 0 | 20 | 95% |
| preexisting-tabs | preexisting-tabs-20rounds.spec.ts | 180 | 0 | 0 | 180 | 100% |
| pricing | pricing-20rounds.spec.ts | 18 | 2 | 0 | 20 | 90% |
| thresholds | thresholds-20rounds.spec.ts | 19 | 1 | 0 | 20 | 95% |

---

## Failures

- **[business-rules]** Round 15: BOUNDARY-5: priority -1 (out of range)
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp

- **[cron]** Round 12: BOUNDARY "cron malformed (only 3 fields)"
  - error: Error: axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexpected[39m[2m) // Object.is equality[22m  Expected: [32mtrue[39m Received: [31mfalse[39m  

- **[cron]** Round 15: BOUNDARY "SQL injection attempt in cron"
  - error: Error: axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexpected[39m[2m) // Object.is equality[22m  Expected: [32mtrue[39m Received: [31mfalse[39m  

- **[cron]** Round 16: WIRE idempotent update cycle 1
  - error: Error: [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexpected[39m[2m) // Object.is equality[22m  Expected: [32m"0 0 [7m4[27m * * ?"[39m Received: [31m"0 0 [7m2[27m * * ?"[39m    155 /       const refetch = await callApi(r

- **[indicators]** Round 13: BOUNDARY-3: bad computeStrategy
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp

- **[notify]** Round 12: BOUNDARY-2: bodyTemplate length=10001
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp

- **[pricing]** Round 12: BOUNDARY-2: discountPct 101% (out of range)
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp

- **[pricing]** Round 13: BOUNDARY-3: discountPct -10 (negative)
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp

- **[thresholds]** Round 15: BOUNDARY-5: value 999 > maxValue 1
  - error: Error: axis1=PASS n/a for this round / axis2=PASS n/a for this round / axis3=PASS n/a for this round / axis4=FAIL success=true, HTTP=200, expected success=false on validation error.  [2mexpect([22m[31mreceived[39m[2m).[22mtoBe[2m([22m[32mexp


---

## 4-Axis Coverage Notes

Each round verifies up to 4 axes:
- Axis 1 (Modify Accepted): mutation API returns 2xx + refetch shows change
- Axis 2 (Target Uses New): runtime list endpoint reflects new entity
- Axis 3 (No Regression): pre-existing control entity unchanged
- Axis 4 (4位一体 UX): boundary 4xx + specific message + actionHint

Phase 2-5 CRUD-driven Tabs (alerts/notify/business-rules/pricing/cron) exercise all 4 axes.
Phase A Tabs (thresholds/food-safety/indicators) exercise axes 1+3+4.
Pre-existing read-mostly Tabs (workflow/approval/triggers/validation/fields/permissions/
module-permissions/tools/scheduler-v2) primarily exercise axes 3 (stability) + 4 (boundary).

---

## Bugs / Findings

**Real backend validation gaps caught: 8** (P1 candidates)
**Adapter shape drift or test issues: 0** (test-side)
**Other: 1**

### P1 candidates — backend accepted invalid payload

- **[business-rules]** Round 15: BOUNDARY-5: priority -1 (out of range)
- **[cron]** Round 12: BOUNDARY "cron malformed (only 3 fields)"
- **[cron]** Round 15: BOUNDARY "SQL injection attempt in cron"
- **[indicators]** Round 13: BOUNDARY-3: bad computeStrategy
- **[notify]** Round 12: BOUNDARY-2: bodyTemplate length=10001
- **[pricing]** Round 12: BOUNDARY-2: discountPct 101% (out of range)
- **[pricing]** Round 13: BOUNDARY-3: discountPct -10 (negative)
- **[thresholds]** Round 15: BOUNDARY-5: value 999 > maxValue 1

These mean a boundary check passed validation (HTTP 200) when it should have rejected.
Recommended next step: open backend issues per Tab + add server-side @Valid annotations.

### Other failures

- **[cron]** Round 16: WIRE idempotent update cycle 1


---

End of report.