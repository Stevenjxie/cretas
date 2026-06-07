# Shallow Test Anti-Patterns (Observed in the wild)

5 concrete anti-patterns from the web-admin 5-round E2E project. Avoid these.

---

## Anti-Pattern 1: "Click button + count labels"

**Source**: `tests/e2e-comprehensive/e2e-L3L4-flows.mjs:282-307` (L4_4_SOCreateFlow)

```js
// ❌ SHALLOW — declared as L4 but never submits
async function L4_4_SOCreateFlow(page) {
  console.log('\n--- L4-4: Sales Order Create Flow ---');
  const nav = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (nav !== 'OK') { record('L4', '4', 'navigate', 'FAIL', { result: nav }); return; }

  const rowsBefore = rowsOf(await countTableRows(page));
  record('L4', '4', 'so_list', 'PASS', { rows: rowsBefore });

  // Try to create SO
  const clicked = await clickButton(page, '新建', '新增', '创建订单', '创建');
  if (!clicked) {
    record('L4', '4', 'so_create_button', 'SKIP', { reason: 'No create button' });
    return;
  }
  await page.waitForTimeout(3000);

  // Check if form has key fields
  const fields = await page.evaluate(() => {
    const labels = Array.from(document.querySelectorAll('.el-form-item__label, label'));
    return labels.map(l => l.textContent?.trim()).filter(Boolean).slice(0, 15);
  });
  record('L4', '4', 'so_form_fields', fields.length > 0 ? 'PASS' : 'FAIL', {
    fieldCount: fields.length,
    fields: fields.slice(0, 8),
  });
}
```

### Why it's wrong

1. Opens create dialog but **never fills** fields
2. **Never calls submit**
3. Just counts how many `.el-form-item__label` elements exist (≥ 1 = PASS)
4. `fields` includes filter tabs from the main page, not just the dialog's form labels
5. If backend `POST /api/mobile/{factoryId}/sales/orders` is completely broken, this test still passes

### Why it was written

- "The main goal is to verify form has fields" (no it isn't — that's L1 value)
- "Filling real data is too much work, save for next round" (next round never came)
- "At least the click worked" (clicks without verification don't prove anything)

### The fix

Replace with deep template from SKILL.md. Must include:
- fillAllRequiredFields
- submitAndCheckResponse
- Toast capture
- Persistence verify (delta === 1)
- Detail page readback

---

## Anti-Pattern 2: `text.includes('关键字')` keyword matching

**Source**: `tests/e2e-comprehensive/e2e-L3L4-flows.mjs:378-397` (L4_25_SOSpecBoxFields)

```js
// ❌ SHALLOW — checks for substring anywhere on page
async function L4_25_SOSpecBoxFields(page) {
  console.log('\n--- L4-25: SO 规格+箱数字段 ---');
  const nav = await navigateTo(page, '/sales/orders', { waitForTable: true });
  if (nav !== 'OK') { record('L4', '25', 'navigate', 'FAIL', { result: nav }); return; }
  const clicked = await clickButton(page, '新建', '新增', '创建订单');
  if (!clicked) { record('L4', '25', 'create_button', 'SKIP', { reason: 'no create button' }); return; }
  await page.waitForTimeout(3000);

  // Verify form has specification + box_quantity related fields in items table header
  const hasFields = await page.evaluate(() => {
    const text = document.body.innerText || '';
    return {
      hasSpecification: text.includes('规格'),
      hasBoxQuantity: text.includes('箱数') || text.includes('箱'),
    };
  });
  record('L4', '25', 'so_spec_box_fields', (hasFields.hasSpecification && hasFields.hasBoxQuantity) ? 'PASS' : 'WARNING', hasFields);
}
```

### Why it's wrong

1. Checks `document.body.innerText.includes('规格')` — matches:
   - Column headers ("规格 | 数量 | 单价")
   - Placeholders ("请输入规格")
   - Tooltips ("支持自定义规格")
   - Sidebar menu items
   - Help modals
   - **Any text anywhere on page**
2. "箱数" matches "箱数" in help text but also "装箱数量" in other contexts
3. Doesn't verify these are **input fields** you can actually **fill and submit**

### Why it was written

- "Quick way to verify feature exists" (but doesn't verify it works)
- "Frontend already has the fields, just need to check they show up" (false — needs to check they're usable)

### The fix

```js
// ✅ CORRECT — verify specific input elements exist AND can be filled
async function L4_25_SOSpecBoxFields_DEEP(page) {
  await navigateTo(page, '/sales/orders');
  await clickButton(page, '新建');
  await waitForDialog(page);

  // Click "添加明细" to add an item row
  const addItemBtn = await page.$('button:has-text("添加明细"), button:has-text("添加")');
  if (!addItemBtn) { record('L4', '25', 'add_item_btn', 'FAIL', { depth: 'deep' }); return; }
  await addItemBtn.click();
  await page.waitForTimeout(500);

  // Find specification input IN THE ITEM ROW (not anywhere)
  const specInput = await page.$('.el-dialog .el-table tr:last-child input[placeholder*="规格"]');
  const boxInput = await page.$('.el-dialog .el-table tr:last-child input[placeholder*="箱"]');

  if (!specInput || !boxInput) {
    record('L4', '25', 'spec_box_inputs_exist', 'FAIL', { depth: 'deep', specInput: !!specInput, boxInput: !!boxInput });
    return;
  }

  // Actually fill them
  await specInput.fill('500ml x 24');
  await boxInput.fill('10');

  // Verify values are in the form model (Vue reactive)
  const values = await page.evaluate(() => {
    const row = document.querySelector('.el-dialog .el-table tr:last-child');
    return {
      spec: row.querySelector('input[placeholder*="规格"]').value,
      box: row.querySelector('input[placeholder*="箱"]').value,
    };
  });

  record('L4', '25', 'spec_box_deep', (values.spec === '500ml x 24' && values.box === '10') ? 'PASS' : 'FAIL', {
    depth: 'deep',
    values,
  });
}
```

---

## Anti-Pattern 3: `hasFormField` loose string matching

**Source**: `tests/e2e-comprehensive/e2e-L3L4-flows.mjs:95-100`

```js
// ❌ SHALLOW — matches any label containing substring
async function hasFormField(page, labelText) {
  return page.evaluate((text) => {
    const labels = Array.from(document.querySelectorAll('.el-form-item__label, label'));
    return labels.some(l => l.textContent.includes(text));
  }, labelText);
}

// Used in L3-1
const hasField = await hasFormField(page, '客户');
record('L3', '1', 'so_customer_field', hasField ? 'PASS' : 'FAIL', {
  evidence: hasField ? 'Customer field found in SO form' : 'No customer field',
});
```

### Why it's wrong

"客户" matches:
- "客户" (actual customer field label)
- "客户经理" (customer account manager)
- "客户备注" (customer notes)
- "客户等级" (customer level)
- "客户类型" (customer type)
- "签约客户" (contracted customer)

Any of these substrings triggers PASS. Even if the actual customer dropdown is missing.

### Why it was written

- "Quick L3 check"
- "Don't want to deal with opening el-select dropdown"
- "checkDropdownContains is defined but not called" (true — defined but never wired up, the classic facade engineering pattern)

### The fix

Use `checkDropdownContains` (from anti-pattern 5 below, fixed version):

```js
// ✅ CORRECT — actually open the dropdown and verify content
async function L3_1_deep_customerDropdown(page) {
  // Create customer first
  const customerName = `E2E_C_${Date.now()}`;
  await createEntityDeep(page, '/sales/customers', customerName);

  // Navigate to SO create
  await navigateTo(page, '/sales/orders');
  await clickButton(page, '新建');
  await waitForDialog(page);

  // Click customer select to open dropdown
  const selectElement = await page.$('.el-dialog .el-select[placeholder*="客户"], .el-drawer .el-select[placeholder*="客户"]');
  if (!selectElement) {
    record('L3', '1', 'find_customer_select', 'FAIL', { depth: 'deep' });
    return;
  }
  await selectElement.click();
  await page.waitForTimeout(1000);

  // Type to filter (if filterable)
  const searchInput = await page.$('.el-select-dropdown__search input');
  if (searchInput) {
    await searchInput.fill(customerName);
    await page.waitForTimeout(500);
  }

  // Extract dropdown options
  const options = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .map(el => el.textContent?.trim())
      .filter(Boolean);
  });

  const found = options.some(o => o.includes(customerName));
  record('L3', '1', 'customer_in_so_dropdown_deep', found ? 'PASS' : 'FAIL', {
    depth: 'deep',
    searchedFor: customerName,
    optionsFound: options.length,
    customerInDropdown: found,
  });
}
```

---

## Anti-Pattern 4: `rowsAfter > rowsBefore` lax persistence

**Source**: `tests/e2e-comprehensive/e2e-L2-crud.mjs:131` (R2 original, fixed in R2 REDO)

```js
// ❌ SHALLOW — accepts any positive delta
const persisted = rowsAfter > rowsBefore;
record(module, 'persistence', persisted ? 'PASS' : 'WARNING', {
  rowsBefore,
  rowsAfter,
  delta: rowsAfter - rowsBefore,
});
```

### Why it's wrong

In R2, this logic gave PASS for:
- `rowsBefore=0, rowsAfter=6, delta=6` (customers)
- `rowsBefore=0, rowsAfter=9, delta=9` (suppliers)

Both should have failed. The 6/9 extra rows were accumulated dirty data from prior failed test runs.

### Why it was written

- "Just want to verify something was created"
- "Delta variance is OK, focus on direction" (no — direction without magnitude is useless)

### The fix (R2 REDO)

```js
// ✅ CORRECT — strict delta === expected
export function verifyPersistence(rowsBefore, rowsAfter, expectedDelta = 1) {
  const delta = rowsAfter - rowsBefore;
  if (delta === expectedDelta) {
    return { status: 'PASS', rowsBefore, rowsAfter, delta, note: `expected delta=${expectedDelta}` };
  }
  if (delta === 0) {
    return { status: 'FAIL', rowsBefore, rowsAfter, delta, note: 'No persistence — record not created' };
  }
  return { status: 'WARNING', rowsBefore, rowsAfter, delta,
    note: `Unexpected delta (expected ${expectedDelta}, got ${delta}). Possible duplicate submit / dirty data / pagination issue.` };
}
```

Also added: DB-level cleanup before each run (`DELETE WHERE name LIKE 'E2E_%'`).

---

## Anti-Pattern 5: `checkDropdownContains` defined but never called (Facade engineering)

**Source**: `tests/e2e-comprehensive/e2e-L3L4-flows.mjs:105-130`

```js
/**
 * Check if a select dropdown contains an option matching partial text.
 */
async function checkDropdownContains(page, selectSelector, searchText) {
  const select = await page.$(selectSelector || '.el-select');
  if (!select) return { found: false, reason: 'no_select_element' };

  await select.click();
  await page.waitForTimeout(1500);

  const items = await page.evaluate((text) => {
    const options = document.querySelectorAll('.el-select-dropdown__item, .el-select-dropdown__wrap li');
    const all = Array.from(options).map(o => o.textContent?.trim() || '');
    const match = all.some(t => t.includes(text));
    return { all: all.slice(0, 10), match, count: all.length };
  }, searchText);

  await page.keyboard.press('Escape');
  return items;
}

// ... rest of file ...
// Grep "checkDropdownContains(" → 0 matches. Function is NEVER called.
```

### Why it's wrong

The function is correctly designed — it **would** do a deep dropdown verification if called. But it's **dead code**. The test file imports it, declares it, but no test ever invokes it.

This is "facade engineering": looks like the capability exists, actually it doesn't.

### Why it was written

- Someone (me, in R2 REDO) knew the need existed
- Wrote the function as infrastructure prep
- **Never wired it into any test** because it would require real dropdown interaction (slow, complex)
- Left a TODO comment implicitly ("R3 will use this")
- R3 forgot

### The detection

```bash
# Find dead helpers
grep -oE "^(export )?(async )?function ([a-zA-Z]+)" helpers.mjs |
  awk '{print $NF}' |
  while read fn; do
    count=$(grep -c "${fn}(" tests/e2e-comprehensive/)
    if [ "$count" -lt 2 ]; then  # defined once + called at most 0 times
      echo "DEAD: $fn"
    fi
  done
```

### The fix

Either wire it up (per L3_1_deep_customerDropdown above) or delete it. **Never leave helpers defined-but-unused**.

---

## Summary: The common theme

All 5 anti-patterns share one root: **the test never triggers the code path it claims to verify**.

- Anti-pattern 1: Doesn't submit → doesn't verify submit
- Anti-pattern 2: Doesn't check specific elements → doesn't verify elements
- Anti-pattern 3: Doesn't open dropdown → doesn't verify dropdown
- Anti-pattern 4: Doesn't check exact delta → doesn't verify single create
- Anti-pattern 5: Never runs the deep helper → doesn't verify anything deep

### The detection question

For each test, ask: **"What code path does this test trigger?"**

- If the answer is "just `fetch('/api/...')` for page load data" → it's L1 smoke, not L4
- If the answer is "only DOM query selectors on already-loaded page" → it's L1 smoke
- If the answer is "click button that opens a dialog" → still just UI interaction, not L4
- If the answer is "click submit which triggers POST /api/mobile/..." → **this is the start of L4**

### The recovery plan

1. Grep your suite for any of the 5 anti-patterns
2. Classify them as `depth: smoke` (honest)
3. Write at least 1 matching deep test to replace each
4. Update summary schema to show depth distribution
5. Never again claim smoke tests hit the spec §8.2 L4 threshold
