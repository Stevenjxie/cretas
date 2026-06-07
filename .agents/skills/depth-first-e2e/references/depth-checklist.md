# Deep Test Checklist (12 steps)

A test qualifies as `depth: 'deep'` only if it passes ALL 12 steps below.
If any step is missing, the test is `depth: 'medium'` (missing detail verification) or `depth: 'smoke'` (no submit or no fill).

---

## The 12 steps

### Step 1: Clean baseline
```js
// DB-level cleanup BEFORE the test (not just in setup)
await cleanupTestData({ factoryId, namePrefix: 'E2E_' });
```
**Why**: Dirty data from prior runs corrupts delta checks.

### Step 2: Record baseline state
```js
const nav1 = await navigateTo(page, listPath, { waitForTable: true });
if (nav1 !== 'OK') { record('...', 'navigate', 'FAIL', { result: nav1, depth: 'deep' }); return; }

const beforeResult = await countTableRows(page);
if (beforeResult.error) { record('...', 'list', 'FAIL', { error: beforeResult.error, depth: 'deep' }); return; }
const rowsBefore = beforeResult.count;
```
**Why**: Must know exact starting count for delta check.

### Step 3: Open create dialog
```js
const clicked = await clickButton(page, '新建', '新增', '创建');
if (!clicked) { record('...', 'open_dialog', 'FAIL', { depth: 'deep' }); return; }

const dialog = await waitForDialog(page);
if (!dialog) { record('...', 'dialog_visible', 'FAIL', { depth: 'deep' }); return; }
```
**Why**: Verify the create flow can be initiated.

### Step 4: Fill primary field (unique name)
```js
const testName = `E2E_DEEP_${Date.now().toString(36)}`;
const filled = await fillDialogInput(page, testName);
if (!filled) { record('...', 'fill_name', 'FAIL', { depth: 'deep' }); return; }
```
**Why**: Unique name makes detail roundtrip verification possible.

### Step 5: Fill all required fields (handle compound types)
```js
const filledFields = await fillAllRequiredFields(page, testName);
// filledFields includes: input / textarea / el-select / el-date-picker / el-checkbox / el-radio
```
**Why**: Element Plus rules use `trigger: 'blur'` — empty required fields = form rejection.

### Step 6: Submit with precise API filter
```js
const submitResult = await submitAndCheckResponse(page, ['确定', '保存', '提交'], {
  factoryId: FACTORY_ID,
  module: 'sales/orders',  // ← precise filter
});
```
**Why**: Wide filter (`/api/`) catches wrong POST (e.g. token refresh, dropdown lazy-load).

### Step 7: Verify API response
```js
if (!submitResult.ok) {
  record('...', 'submit', 'FAIL', {
    depth: 'deep',
    reason: submitResult.reason,
    status: submitResult.status,
    errors: submitResult.errors,
    filledFields,
  });
  return;
}
```
**Why**: HTTP 200 is necessary but not sufficient — also check `body.success !== false`.

### Step 8: Capture toast text (Spec §1.3 hard rule 3)
```js
let toastText = '';
try {
  const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
  toastText = await toast.innerText();
} catch {
  toastText = '(not captured)';
  // Not auto-FAIL — some flows show toast briefly, but record the gap
}
```
**Why**: Spec §1.3 hard rule 3 explicitly requires toast capture. Skipping = violation.

### Step 9: Navigate back to list (fresh goto, not router.push)
```js
await page.goto(`${BASE}${listPath}`, { waitUntil: 'domcontentloaded' });
for (let i = 0; i < 20; i++) {
  await page.waitForTimeout(500);
  if (await page.$('.el-table__body-wrapper')) break;
}
await page.waitForTimeout(1000); // extra for row data load
```
**Why**: `router.push()` may not re-fetch list data. `goto()` guarantees fresh data.

### Step 10: Verify strict delta === 1
```js
const afterResult = await countTableRows(page);
if (afterResult.error) { record('...', 'post_count', 'FAIL', { error: afterResult.error, depth: 'deep' }); return; }
const delta = afterResult.count - rowsBefore;
if (delta !== 1) {
  record('...', 'persistence', delta > 1 ? 'WARNING' : 'FAIL', {
    depth: 'deep',
    rowsBefore,
    rowsAfter: afterResult.count,
    delta,
    note: delta > 1 ? 'Possible duplicate submit or dirty data' : 'Record not created',
  });
  return;
}
```
**Why**: Exact `=== 1` catches duplicate submits and dirty data accumulation.

### Step 11: Open detail page for roundtrip verification
```js
// Click the newly created row (usually first row after sort by createdAt DESC)
const firstRow = await page.$('.el-table__body-wrapper .el-table__row:first-child');
if (!firstRow) { record('...', 'find_new_row', 'FAIL', { depth: 'deep' }); return; }

// Prefer clicking the "查看" / "编辑" link if it exists
const viewLink = await firstRow.$('button:has-text("查看"), button:has-text("详情"), a.el-link');
if (viewLink) {
  await viewLink.click();
} else {
  await firstRow.click();
}
await page.waitForTimeout(2000);
```
**Why**: Detail page verifies the data actually persisted with correct values.

### Step 12: Verify field roundtrip (name must match)
```js
const detailRoundtrip = await page.evaluate((expectedName) => {
  const text = document.body.innerText || '';
  const hasName = text.includes(expectedName);
  // Also try common selectors for name display
  const nameField = document.querySelector('[class*="name"], .el-descriptions__content');
  return {
    nameInText: hasName,
    nameFieldText: nameField?.textContent?.trim() || '',
  };
}, testName);

if (!detailRoundtrip.nameInText) {
  record('...', 'detail_roundtrip', 'FAIL', {
    depth: 'deep',
    expected: testName,
    detailRoundtrip,
  });
  return;
}
```
**Why**: The fundamental L4 test: can we write and read back the same data?

### Final: Record PASS with full evidence
```js
record('...', 'full_deep_flow', 'PASS', {
  depth: 'deep',   // ← REQUIRED
  filled: testName,
  filledFields,    // ← required fields filled
  apiStatus: submitResult.status,
  apiUrl: submitResult.url,
  toastText,        // ← toast captured
  rowsBefore,
  rowsAfter: afterResult.count,
  delta: 1,         // ← strict delta
  detailRoundtrip: true,
});
```

---

## Self-diagnostic

Given a test, count how many of the 12 steps it performs:

| Steps performed | Classification |
|-----------------|----------------|
| 12/12 | `depth: 'deep'` ✅ |
| 7-11 | `depth: 'medium'` |
| 1-6 | `depth: 'smoke'` |
| 0 | Not a test — delete |

If your L4 tests have average depth score < 10/12, you have the shallow padding problem.

---

## Common shortcuts that downgrade deep to medium

| Shortcut | Effect |
|----------|--------|
| Skip Step 1 (DB cleanup) | Dirty data may pass wrong delta checks |
| Skip Step 5 (compound fields) | Form may have `.is-required` el-select that blocks submit |
| Skip Step 6's precise filter | `waitForResponse` catches wrong POST |
| Skip Step 8 (toast capture) | No Spec §1.3 hard rule 3 evidence |
| Skip Step 11-12 (detail page) | Only confirms "DB has 1 more row", not "field values correct" |

**The test is not `deep` if any of 11-12 is skipped.** Detail roundtrip is the non-negotiable marker of deep L4.

---

## Example: Deep customer create (full 12 steps)

```js
async function L4_deep_customerCreate(page) {
  const testId = 'L4-deep-1';
  console.log(`\n--- ${testId}: customer create full flow ---`);

  // Step 1: Clean baseline (outside this function, done at round start)

  // Step 2: Record baseline
  const nav1 = await navigateTo(page, '/sales/customers', { waitForTable: true });
  if (nav1 !== 'OK') { record('L4', 'deep-1', 'navigate', 'FAIL', { result: nav1, depth: 'deep' }); return; }
  const beforeResult = await countTableRows(page);
  if (beforeResult.error) { record('L4', 'deep-1', 'list', 'FAIL', { error: beforeResult.error, depth: 'deep' }); return; }
  const rowsBefore = beforeResult.count;

  // Step 3: Open dialog
  const clicked = await clickButton(page, '新建', '新增');
  if (!clicked) { record('L4', 'deep-1', 'open_dialog', 'FAIL', { depth: 'deep' }); return; }
  const dialog = await waitForDialog(page);
  if (!dialog) { record('L4', 'deep-1', 'dialog_visible', 'FAIL', { depth: 'deep' }); return; }

  // Step 4 + 5: Fill name + all required fields
  const testName = `E2E_C_DEEP_${Date.now().toString(36)}`;
  await fillDialogInput(page, testName);
  const filledFields = await fillAllRequiredFields(page, testName);

  // Step 6 + 7: Submit with precise filter
  const submitResult = await submitAndCheckResponse(page, ['确定', '保存'], {
    factoryId: FACTORY_ID,
    module: 'customers',
  });
  if (!submitResult.ok) {
    record('L4', 'deep-1', 'submit', 'FAIL', {
      depth: 'deep',
      reason: submitResult.reason,
      status: submitResult.status,
      filledFields,
    });
    return;
  }

  // Step 8: Capture toast
  let toastText = '';
  try {
    const toast = await page.waitForSelector('.el-message--success', { timeout: 3000 });
    toastText = await toast.innerText();
  } catch { toastText = '(timeout)'; }

  // Step 9: Fresh navigate to list
  await page.goto(`${BASE}/sales/customers`, { waitUntil: 'domcontentloaded' });
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500);
    if (await page.$('.el-table__body-wrapper')) break;
  }
  await page.waitForTimeout(1000);

  // Step 10: Strict delta check
  const afterResult = await countTableRows(page);
  const delta = afterResult.count - rowsBefore;
  if (delta !== 1) {
    record('L4', 'deep-1', 'persistence', delta > 1 ? 'WARNING' : 'FAIL', {
      depth: 'deep',
      rowsBefore,
      rowsAfter: afterResult.count,
      delta,
    });
    return;
  }

  // Step 11: Open detail (click first row)
  const firstRow = await page.$('.el-table__body-wrapper .el-table__row:first-child');
  if (!firstRow) { record('L4', 'deep-1', 'find_row', 'FAIL', { depth: 'deep' }); return; }
  await firstRow.click();
  await page.waitForTimeout(2000);

  // Step 12: Verify roundtrip
  const detailRoundtrip = await page.evaluate((expectedName) => {
    return document.body.innerText.includes(expectedName);
  }, testName);

  if (!detailRoundtrip) {
    record('L4', 'deep-1', 'detail_roundtrip', 'FAIL', {
      depth: 'deep',
      expected: testName,
    });
    return;
  }

  // Final: Record PASS
  record('L4', 'deep-1', 'full_customer_deep_flow', 'PASS', {
    depth: 'deep',
    filled: testName,
    filledFields,
    apiStatus: submitResult.status,
    apiUrl: submitResult.url,
    toastText,
    rowsBefore,
    rowsAfter: afterResult.count,
    delta: 1,
    detailRoundtrip: true,
  });
}
```

**This is a single deep test**. If your R4 Phase 2 added 17 "L4 tests" in 1-2 hours, they were not deep — each deep test takes 30-60 minutes to write correctly.
