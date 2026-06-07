# Case Study: R7 rating bug — retroactive same-cause sweep (Rule 8)

**Date**: 2026-04-14 (sweep applied retroactively per Rule 8)
**Round**: R7 (rating validation bug fix)
**Trigger**: R6 deep-4 customer EDIT caught `"评级1-5"` HTTP 400 error.

---

## Original bug

**File**: `factory_validation_rules` table (Canvas V2 DB-driven validation)
**Rule**: `customer RATING_RANGE UPDATE`
**Original condition**: `#rating < 1 OR #rating > 5`
**Bug**: SpEL evaluates `null < 1` as true → non-null guard causes spurious failure when `rating = null`.

**Original fix** (R7 commit `6c235f196`):
```sql
UPDATE factory_validation_rules
SET condition = '#rating != null AND (#rating < 1 OR #rating > 5)'
WHERE rule_code = 'RATING_RANGE' AND module_code IN ('customer', 'supplier');
```

Fixed 2 rows: customer + supplier RATING_RANGE.

---

## Retroactive same-cause sweep (Rule 8)

### Pattern defined

**Anti-pattern**: SpEL condition `#field < N OR #field > N` (or similar numeric comparison) without explicit `#field != null` guard.

Matching pattern means: when `field = null`, the rule fires unintentionally because SpEL treats null as numerically "less than" any value.

### Sweep query

```sql
SELECT id, factory_id, module_code, rule_code, operation, error_message, condition
FROM factory_validation_rules
WHERE condition LIKE '%<%' OR condition LIKE '%>%'
ORDER BY module_code, rule_code;
```

Returned 78 rows.

### Analysis by category

#### Category A: Already fixed (Rule 8 coverage) ✅

| id | module_code | rule_code | condition |
|----|-------------|-----------|-----------|
| 31 | customer | RATING_RANGE | `#rating != null AND (#rating < 1 OR #rating > 5)` ✅ |
| 28 | supplier | RATING_RANGE | `#rating != null AND (#rating < 1 OR #rating > 5)` ✅ |

Both fixed in original R7 commit.

#### Category B: Already has null guard ✅

| module_code | rule_code | condition |
|-------------|-----------|-----------|
| customer | customer_name_length | `#customerName != null && #customerName.length() < 3` ✅ |
| production_plan | fermentation_days_positive | `#cf_fermentation_days != null && #cf_fermentation_days <= 0` ✅ |

12 duplicate rows for `customer_name_length` (data pollution but functionally correct).

#### Category C: Safe by accident — DTO validation catches null first

| id | module_code | rule_code | condition | Verdict |
|----|-------------|-----------|-----------|---------|
| 9 | finance_ar | POSITIVE_AMOUNT | `#amount <= 0` | Safe: DTO `@NotNull` blocks null |
| 11 | finance_payment | POSITIVE_PAYMENT | `#amount <= 0` | Safe: DTO `@NotNull` blocks null |
| 32 | finance_ap | POSITIVE_AMOUNT | `#amount <= 0` | Safe: DTO `@NotNull` blocks null |
| 34 | finance_receipt | POSITIVE_AMOUNT | `#amount <= 0` | Safe: DTO `@NotNull` blocks null |
| 12 | finance_payment | EXCEED_BALANCE | `#amount > #remainingBalance` | Safe: both fields DTO-validated |
| 35 | finance_receipt | EXCEED_BALANCE | `#amount > #remainingBalance` | Safe: same |
| 5 | sales_order | POSITIVE_AMOUNT | `#totalAmount <= 0` | Safe: totalAmount is calculated, never null |

**Verdict**: These rules work correctly in practice because the DTO `@NotNull` annotation catches the null case before the SpEL evaluator sees it. But they're **fragile** — if the DTO annotation is ever removed or the field becomes nullable, the rule will start misfiring just like RATING_RANGE did.

**Recommendation**: Add explicit `#field != null AND` guards to all of these for defense-in-depth. Schedule for R12 sweep commit.

#### Category D: Broken SpEL syntax (rule never fires) 🚨

**This is where same-cause sweep paid off**: found 13 rules in 13 different factories with **invalid SpEL syntax**.

| id | factory_id | condition | Problem |
|----|-----------|-----------|---------|
| 37 | FOOD_3101_007 | `totalAmount >= 100` | Missing `#` prefix (not valid SpEL) + REVERSED logic (should be `< 100` not `>= 100`) |
| 38 | FOOD_3101_008 | `totalAmount >= 100` | Same |
| 39 | FOOD_3101_009 | `totalAmount >= 100` | Same |
| 40 | FOOD_3101_010 | `totalAmount >= 100` | Same |
| 41 | FOOD_3101_011 | `totalAmount >= 100` | Same |
| 42 | FOOD_3101_012 | `totalAmount >= 100` | Same |
| 43 | FOOD_3101_013 | `totalAmount >= 100` | Same |
| 44 | FOOD_3101_014 | `totalAmount >= 100` | Same |
| 45 | FOOD_3101_015 | `totalAmount >= 500` | Same |
| 46 | FOOD_3101_016 | `totalAmount >= 500` | Same |
| 47 | FOOD_3101_017 | `totalAmount >= 500` | Same |
| 48 | FOOD_3101_018 | `totalAmount >= 500` | Same |
| 49 | FOOD_3101_019 | `totalAmount >= 500` | Same |

**Root cause**: These 13 rules were created via some migration script that used raw field names instead of SpEL variable references (`#totalAmount`). When `ValidationRuleEvaluator` encounters invalid SpEL:

```java
try {
    validationRuleEvaluator.validate(factoryId, module, operation, context);
} catch (BusinessException e) {
    throw e;
} catch (Exception e) {
    log.warn("Canvas validation non-blocking error: {}", e.getMessage());
}
```

The `Exception` catch swallows the error → rule silently fails to fire → **business rule never enforced**.

**Impact**: Factories FOOD_3101_007 through FOOD_3101_019 (13 factories) do NOT enforce minimum order amount. They should be rejecting orders below 100 元 (007-014) or 500 元 (015-019), but they silently accept any amount including 0 or negative.

**Safe-by-accident status**: The catch-all prevents errors from bubbling, but the business rule is not enforced. This is **silent data loss** — the business rule exists on paper but not in practice.

**Verdict**: VULNERABLE. Schedule for user decision.

### Recommendation

**Do NOT auto-fix Category D without user consent** because:
1. Fixing 13 broken factories would suddenly enforce minimum amounts
2. Users in those factories may have pending orders below the threshold
3. Changes to production rules require customer communication

**Action**: Schedule as R13 (pending user decision):
- Option A: Fix all 13 rules with `#totalAmount < 500` syntax (strictest)
- Option B: Disable all 13 rules and rely on DTO `@Min` if exists
- Option C: Audit with each affected factory before fixing

**Category C defense-in-depth fix** is safer — add null guards without changing business logic:

```sql
UPDATE factory_validation_rules
SET condition = '#amount != null AND #amount <= 0'
WHERE module_code IN ('finance_ar', 'finance_ap', 'finance_payment', 'finance_receipt')
  AND rule_code LIKE 'POSITIVE_%';

-- Also sales_order POSITIVE_AMOUNT
UPDATE factory_validation_rules
SET condition = '#totalAmount != null AND #totalAmount <= 0'
WHERE id = 5;
```

This can be auto-applied — no behavior change.

---

## Lesson learned

The R6→R7 fix worked on the obvious sibling (supplier RATING_RANGE), but without Rule 8 enforcement, we would have missed:

1. **13 silently-broken factory rules** — latent "silent data loss" bugs
2. **7 defense-in-depth opportunities** — fragile-but-working rules

**These findings are valuable**:
- Not immediately actionable (production impact), but
- Create visibility into tech debt
- Prevent regression if DTO annotations change

### How Rule 8 applies going forward

Every time a deep test catches a real bug:

1. **Define the root cause as a searchable pattern** (SpEL null guard missing in this case)
2. **Grep the codebase + DB for all instances** of the pattern
3. **Classify each match**: fixed / safe by accident / vulnerable
4. **Document in audit doc with counts + verdicts**
5. **Vulnerable instances**: fix now or schedule concretely (never "TBD")

The pattern matters more than the specific rule. "SpEL null guard missing" is reusable across any Canvas V2 validation rule addition.

---

## Files modified

- None (this is documentation, not code changes)
- Retroactive — R7 original commit `6c235f196` is unchanged

## References

- R7 original commit: `6c235f196`
- Rule 8 definition: `.claude/skills/depth-first-e2e/SKILL.md:169-202`
- Canvas validation rule evaluator: `backend/java/cretas-api/src/main/java/com/cretas/aims/engine/ValidationRuleEvaluator.java`
- `factory_validation_rules` schema: PostgreSQL `cretas_prod_db`
