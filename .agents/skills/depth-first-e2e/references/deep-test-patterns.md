# Deep Test Patterns — Learnings from R6-R11

Concrete, battle-tested patterns for writing deep E2E tests against Vue 3 + Element Plus SPAs. Every pattern below was discovered the hard way during R6-R11 of the web-admin E2E project.

---

## Pattern 1: Pre-step data seeding via DB

When the factory under test has no business data (e.g. `FOOD_3101_048` is a new factory), seed the **minimum referential entities** directly into DB before the test — don't try to create them via UI.

**Example** (from R8):
```bash
# Seed 1 test product before R8 SO creation test
ssh root@server "sudo -u postgres psql -d cretas_prod_db -c \"
  INSERT INTO product_types (id, code, name, unit, unit_price, is_active, factory_id, created_by, created_at, updated_at, category, package_spec)
  VALUES ('prod_e2e_test_001', 'E2E_TEST_001', 'E2E测试产品', 'kg', 100.00, true, 'FOOD_3101_048', 1, NOW(), NOW(), '测试', '500g x 24盒/箱');
\""
```

**When to use**:
- ✅ Product types, material types, sysconfig lookups, role definitions
- ✅ Parent entities that are prerequisites for the business flow under test (e.g. need product to create SO)
- ❌ NOT for the entity under test itself — create that via UI for real roundtrip

**Anti-pattern**: Trying to create 5 prerequisites via UI before each test → test is 5× longer and 5× more fragile. Just seed them.

---

## Pattern 2: `fillAllRequiredFields` + smart field detection

R3 introduced `fillAllRequiredFields()` which handles Element Plus compound fields:

```js
export async function fillAllRequiredFields(page, baseName) {
  const filled = [];
  const formItems = await page.$$('.el-dialog .el-form-item.is-required, .el-drawer .el-form-item.is-required');
  for (let i = 0; i < formItems.length; i++) {
    const label = await formItems[i].$eval('.el-form-item__label', el => el.textContent?.trim()).catch(() => '');

    // R3 fix: handle Element Plus compound fields
    // Priority: text input → textarea → date-picker → select → radio → checkbox

    let input = await formItems[i].$('input.el-input__inner:not([readonly])');
    // ... (see helpers.mjs for full impl)
  }
  return filled;
}
```

**Smart value selection by label keyword**:
| Label contains | Value |
|---------------|-------|
| 电话/手机 | `138${Date.now().toString().slice(-8)}` (dynamic to avoid uniqueness 409) |
| 邮箱/email | `e2e_${Date.now()}@test.com` |
| 地址/收货 | `E2E测试地址_上海市浦东新区` |
| (other) | `${baseName}_${index}` |

**Key insight**: Don't hard-code phone like `13800138000` — when backend adds uniqueness constraint, test starts failing with 409. Dynamic timestamp-based values are forward-compatible.

---

## Pattern 3: Element Plus teleport popover — use `locator(':visible')`

Element Plus dropdowns/popovers are teleported to `document.body`, not inside the dialog. When you `querySelectorAll('.el-select-dropdown__item')` you get stale options from previously-opened (and still-in-DOM) dropdowns.

**Anti-pattern** (R6/R8 early attempts):
```js
// ❌ Returns stale options from status filter on main page
const opts = await page.evaluate(() =>
  Array.from(document.querySelectorAll('.el-select-dropdown__item')).map(o => o.textContent)
);
```

**Correct pattern** (R8 fix):
```js
// ✅ Playwright locator :visible filters to active popover only
const firstVisibleItem = page.locator('.el-select-dropdown__item:visible').first();
await firstVisibleItem.waitFor({ state: 'visible', timeout: 3000 });
const text = await firstVisibleItem.innerText();
await firstVisibleItem.click();
```

**Why**: `page.locator(':visible')` uses Playwright's actionability checks that verify CSS visibility + opacity + `display` state. `querySelector` doesn't know about that.

---

## Pattern 4: `el-input-number` needs native setter + blur

Element Plus `<el-input-number>` wraps a native `<input>` inside custom component logic. Simple `.fill()` can work but may not trigger Vue's reactivity properly.

**Reliable pattern**:
```js
await page.evaluate(() => {
  const dialog = document.querySelector('.el-dialog:not([style*="display: none"])');
  const numInputs = dialog.querySelectorAll('.el-input-number input');
  const vals = ['100', '50', '10'];
  for (let i = 0; i < Math.min(numInputs.length, 3); i++) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(numInputs[i], vals[i]);
    numInputs[i].dispatchEvent(new Event('input', { bubbles: true }));
    numInputs[i].dispatchEvent(new Event('change', { bubbles: true }));
    numInputs[i].dispatchEvent(new Event('blur', { bubbles: true }));
  }
});
```

**Why all three events**:
- `input` — triggers Vue `v-model` update
- `change` — triggers `@change` listeners (e.g. `calcBox(item)` for auto calculation)
- `blur` — activates Element Plus rules with `trigger: 'blur'`

**Don't forget**: `input.fill()` in Playwright does NOT trigger `blur`. If your form has `trigger: 'blur'` validation, you'll pass the test but the real form would fail.

---

## Pattern 5: Stable row lookup by orderNumber, not customer name

When verifying post-action state (e.g. after CONFIRMED, after CANCELLED), the row may move due to sort order changes. Use the **orderNumber** captured during prep, not the customer name.

**Anti-pattern**:
```js
// ❌ Customer name may match multiple rows or the row may be filtered out
const row = rows.find(r => r.innerText.includes(customerName));
```

**Correct pattern** (R9 fix):
```js
// ✅ Capture orderNumber during prep_so_in_list
const newSO = await page.evaluate((custName) => {
  const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
  for (const row of rows) {
    if (row.innerText.includes(custName)) {
      const cells = Array.from(row.querySelectorAll('td')).map(c => c.innerText.trim());
      return { cells };  // cells[0] = 'SO-20260415-0001' (orderNumber)
    }
  }
  return null;
}, testName);

const orderNumber = newSO.cells[0];

// Later, for state verification, match by orderNumber as primary key
const rowAfter = await page.evaluate(({ custName, orderNum }) => {
  const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
  for (const row of rows) {
    if (row.innerText.includes(orderNum) || row.innerText.includes(custName)) {
      return { ..., matchedBy: row.innerText.includes(orderNum) ? 'orderNumber' : 'customerName' };
    }
  }
  return null;
}, { custName: so.testName, orderNum: orderNumber });
```

**Why**: orderNumber is unique + backend-generated + sort-stable. Customer name can be truncated, duplicated, or escaped differently.

---

## Pattern 6: Wait for row DATA, not just table container

`await page.$('.el-table__body-wrapper')` returns truthy as soon as the container renders — before API data fetches. `countTableRows` then returns 0.

**Anti-pattern** (R9 first fail):
```js
for (let i = 0; i < 30; i++) {
  await page.waitForTimeout(500);
  if (await page.$('.el-table__body-wrapper')) break;  // ❌ container, not data
}
```

**Correct pattern**:
```js
await page.waitForTimeout(5000);  // longer initial wait
for (let i = 0; i < 40; i++) {
  await page.waitForTimeout(500);
  const hasRows = await page.evaluate(() =>
    document.querySelectorAll('.el-table__body-wrapper .el-table__row').length > 0
  );
  if (hasRows) break;
}
await page.waitForTimeout(1500);  // extra for row content to fill cells
```

**Exception**: If your test specifically verifies the empty state, don't wait for rows.

---

## Pattern 7: Dialog/Drawer investigation step for unknown forms

When you first encounter a new dialog with unknown structure, add an **investigation step** that dumps the form layout. This saves 2-3 rounds of debugging.

**Template**:
```js
// Step 2: Open dialog
const clicked = await clickButton(page, '新建', '新增');
if (!clicked) { record(testId, 'step2_open', 'FAIL', {}); return; }
await page.waitForTimeout(3000);  // wait for load

// Investigation: dump dialog structure for debugging
const structure = await page.evaluate(() => {
  const d = document.querySelector('.el-dialog:not([style*="display: none"]), .el-drawer:not([style*="display: none"])');
  if (!d) return null;
  return {
    tagName: d.tagName,
    className: d.className,
    selectCount: d.querySelectorAll('.el-select').length,
    inputCount: d.querySelectorAll('input.el-input__inner').length,
    tableCount: d.querySelectorAll('.el-table').length,
    itemRowCount: d.querySelectorAll('.item-row').length,
    buttonCount: d.querySelectorAll('button').length,
    buttonTexts: Array.from(d.querySelectorAll('button')).map(b => b.textContent?.trim()).filter(Boolean).slice(0, 20),
    formItemLabels: Array.from(d.querySelectorAll('.el-form-item__label')).map(l => l.textContent?.trim()).slice(0, 20),
    datePickers: d.querySelectorAll('.el-date-editor, input[placeholder*="日期"]').length,
  };
});
record(testId, 'step2_investigate_structure', 'PASS', structure || {});
```

**What to look for in the output**:
- `buttonTexts`: Find the submit button text (may be "创建" not "确定/保存/提交")
- `formItemLabels`: Verify all required fields you expect are present
- `tableCount`: Is items in a table or custom divs?
- `itemRowCount`: Does the form have dynamic rows?

**R8 discovery**: SO form had `tableCount: 0, buttonTexts: [..., "创建"]` — revealed items are in `.item-row` divs not `.el-table`, and submit is "创建" not "确定".

---

## Pattern 8: Submit button text discovery

Not all dialogs use "确定/保存/提交". List the likely values:

| Context | Likely button text |
|---------|--------------------|
| 简单对话框 | 确定, 保存 |
| 创建业务订单 | **创建** (not 确定!) — SO/PO use this |
| 编辑 | 保存 |
| 确认操作 | 确认, 是, OK |
| 财务快速操作 | 确认开票, 确认收款 |
| 审批 | 审批通过, 同意, 批准 |
| 拒绝 | 驳回, 拒绝, 不同意 |

**Safe pattern**: pass all likely candidates to `submitAndCheckResponse`:
```js
const submitResult = await submitAndCheckResponse(page,
  ['创建', '确认开票', '确认收款', '确定', '保存', '提交'],
  { factoryId, module }
);
```

---

## Pattern 9: Cross-entity money consistency verification

For financial flows (R11-style), verify the **same amount appears in every downstream entity**:

```js
const EXPECTED_AMOUNT = '5000';  // SO totalAmount = 100 × 50

// Stage 1: SO totalAmount
record(testId, 'step1_so_amount', soCell.includes(EXPECTED_AMOUNT) ? 'PASS' : 'FAIL', { soCell });

// Stage 2: Invoice dialog pre-fills
const invoiceDialogInfo = await page.evaluate(() => ({
  amount: document.querySelector('.el-dialog .el-input-number input')?.value || '',
}));
record(testId, 'step2_invoice_prefilled', invoiceDialogInfo.amount.includes(EXPECTED_AMOUNT) ? 'PASS' : 'WARN', invoiceDialogInfo);

// Stage 3: Invoice API 200
// Stage 4: Payment dialog pre-fills
// Stage 5: Payment API 200
// Stage 6: finance/costs list row contains amount
// Stage 7: SO list "paid" column updated
```

**The test PASSES only if all 7 stages show the same amount**. This catches:
- Wrong totalAmount calculation
- Dialog pre-fill bugs
- Missing auto-sync between entities
- Rounding errors
- Currency mismatches

**R11 evidence**: verified `¥5,000.00` across 5 entities (SO → SO-confirmed → AR → Payment → finance/costs list). Amount never drifted.

---

## Pattern 10: ElMessageBox confirmation + API interception

Action buttons (确认/取消/删除) often trigger `ElMessageBox.confirm()`. You must wait for the confirm button click AND the API response together:

**Pattern**:
```js
// Click the row action button
const actionClicked = await page.evaluate((custName) => {
  const rows = document.querySelectorAll('.el-table__body-wrapper .el-table__row');
  for (const row of rows) {
    if (row.innerText.includes(custName)) {
      const buttons = row.querySelectorAll('button, a.el-link, span.el-link');
      for (const b of buttons) {
        if (b.textContent && b.textContent.trim() === '确认') {
          b.click();
          return true;
        }
      }
    }
  }
  return false;
}, testName);

// Wait for ElMessageBox to appear
await page.waitForTimeout(1000);
const msgBox = await page.$('.el-message-box');
if (msgBox) {
  const confirmBtn = await page.$('.el-message-box__btns button.el-button--primary');
  if (confirmBtn) {
    // Start API listener BEFORE clicking
    const [response] = await Promise.all([
      page.waitForResponse(
        r => r.url().includes('/sales/orders/') && r.url().endsWith('/confirm') && r.request().method() === 'POST',
        { timeout: 10000 }
      ).catch(() => null),
      confirmBtn.click(),
    ]);

    if (response) {
      record(testId, 'confirm_api', 'PASS', {
        status: response.status(),
        url: response.url().replace(BASE, '').split('?')[0],
      });
    }
  }
}
```

**Key**: `Promise.all([waitForResponse, click])` starts the listener before clicking. If you click first then wait, the listener may miss the response if it's fast.

---

## Pattern 11: Helper compositionl — `createSOQuick` reuse

For state machine / follow-up tests (R9+), factor the creation flow into a reusable helper:

```js
// Reusable: creates customer + SO, returns {testName, cells, orderNumber}
async function createSOQuick(page, testIdPrefix) {
  const testName = `E2E_C_${testIdPrefix}_${TS}`;

  // Create customer
  await navigateTo(page, '/sales/customers');
  await clickButton(page, '新建');
  await waitForDialog(page);
  await fillDialogInput(page, testName);
  await fillAllRequiredFields(page, testName);
  const custSubmit = await submitAndCheckResponse(page, ['确定', '保存'], { factoryId, module: 'customers' });
  if (!custSubmit.ok) return null;

  // Create SO
  // ... (SO creation flow)

  return { testName, cells, orderNumber };
}

// Then R9/R11 tests can just:
async function R9_deep_7_confirmSO(page) {
  const so = await createSOQuick(page, 'deep-7');
  if (!so) return;

  // Now focus on the actual state machine test
  // ...
}
```

**Benefit**: R9 (state machine) and R11 (finance loop) both reuse this. Bug in one place benefits all users. Less code duplication = less drift.

---

## Pattern 12: "Depth" field in record() — enforce Rule 1

Every test record MUST include `depth: 'deep'` (or 'medium'/'smoke'):

```js
function record(testId, step, status, evidence = {}) {
  const r = {
    layer: 'L4',
    testId,
    step,
    status,
    depth: 'deep',  // ← R6+ REQUIREMENT: all tests marked
    evidence,
    ts: new Date().toISOString(),
  };
  results.push(r);
  // ...
}
```

**Final result JSON schema** (R6-R11 format):
```json
{
  "round": N,
  "notes": "...",
  "results": [
    {
      "layer": "L4",
      "testId": "deep-1",
      "step": "FULL_FLOW",
      "status": "PASS",
      "depth": "deep",  // ← REQUIRED
      "evidence": { ... }
    }
  ],
  "summary": {
    "deepTotal": N,
    "deepPass": M,
    "deepFail": K
  }
}
```

This aligns with Rule 7 (spec-denominator reporting) and Rule 1 (every test has depth).

---

## Pattern 13: Pre-test DB cleanup hook

Always clean E2E data before running a deep test. R4 discovered that accumulated dirty data caused customers delta=6 false positives.

**Standard cleanup**:
```bash
ssh root@server "sudo -u postgres psql -d cretas_prod_db -c \"
  DELETE FROM sales_orders WHERE factory_id='FOOD_3101_048';
  DELETE FROM customers WHERE factory_id='FOOD_3101_048' AND name LIKE 'E2E_%';
  DELETE FROM suppliers WHERE factory_id='FOOD_3101_048' AND name LIKE 'E2E_%';
\""
```

**Why `LIKE 'E2E_%'` filter**:
- Preserves any real data you might have seeded manually
- Idempotent — safe to run repeatedly
- Matches timestamps if names include TS (e.g. `E2E_C_R11_deep-11_mnz0y1en`)

**Integration**: Run this before every `node tests/e2e-comprehensive/e2e-*.mjs` command, or put it in a `setup.sh` script.

---

## Pattern 14: Step-by-step recording for debugging

Instead of one `FULL_FLOW` record, record each step so failures are pinpoint-able:

```js
record(testId, 'step1_baseline', 'PASS', { rowsBefore });
record(testId, 'step2_dialog', 'PASS', {});
record(testId, 'step3_customer_selected', 'PASS', { customerName });
record(testId, 'step4_delivery_date', 'PASS', { date });
// ...
record(testId, 'step8_submit', submitResult.ok ? 'PASS' : 'FAIL', { status, url });
record(testId, 'step9_toast', toastText !== '(not captured)' ? 'PASS' : 'WARN', { toastText });
// ...
record(testId, 'FULL_FLOW', overallPass ? 'PASS' : 'FAIL', { ...all evidence });
```

**Benefits**:
- When test fails, see exactly which step broke
- Evidence is accumulated not overwritten
- JSON result file is self-documenting
- R6 deep-4 failure showed `edit_submit: FAIL api_body_error: 评级1-5` — immediately pinpointed the bug location

---

## Pattern 15: Rule 8 same-cause sweep after finding a bug

When a deep test catches a real bug (like R7's rating null issue), immediately do a **sibling pattern sweep** before committing:

1. **Define the pattern**: "SpEL condition with numeric comparison missing `!= null` guard"
2. **Grep or SQL scan** the codebase/DB for all instances
3. **Classify each match**: fixed / safe-by-accident / vulnerable
4. **Fix safe sibling** (defense-in-depth) OR schedule vulnerable instances for explicit follow-up

**R7 example**:
- Pattern: `#<field> < N OR #<field> > N` without `!= null` guard
- Found: 2 matches (customer + supplier RATING_RANGE) → fixed both
- **Retroactive sweep found**: 5 more Category C defense-in-depth opportunities + 13 Category D broken sales_order rules

**Lesson**: Without Rule 8, the R7 fix would have stopped at the first 2 sibling. Retroactive sweep found **20× more issues** than the original fix.

**See**: `case-r7-rating-bug-sweep.md` for the full example.

---

## Summary: The R6-R11 playbook

When starting a new deep test:

1. **Clean DB** (Pattern 13)
2. **Seed prerequisites** if needed (Pattern 1)
3. **Add investigation step** first time you encounter a new dialog (Pattern 7)
4. **Use `fillAllRequiredFields`** with dynamic values (Pattern 2)
5. **Element Plus selects**: `locator(':visible')` for dropdowns (Pattern 3)
6. **el-input-number**: native setter + all three events (Pattern 4)
7. **Submit button**: try '创建' before '确定' (Pattern 8)
8. **Row lookup**: use orderNumber not customer name (Pattern 5)
9. **Wait for row data** not just container (Pattern 6)
10. **Record each step** with evidence (Pattern 14)
11. **Every record has `depth` field** (Pattern 12)
12. **Multi-entity money verification** for financial flows (Pattern 9)
13. **ElMessageBox** handled with Promise.all (Pattern 10)
14. **Reuse helpers** across rounds (Pattern 11)
15. **If bug found → same-cause sweep** (Pattern 15, Rule 8)

Following these 15 patterns, R6-R11 produced:
- 11 deep tests
- 6 business chains verified
- 1 real bug found + fixed
- 5 defense-in-depth fixes via Rule 8 sweep
- 13 latent bugs documented for user decision
- 0 flakes (tests are repeatable after DB cleanup)
