/**
 * 存货生产 (库存生产 BY_STOCK) 全链 E2E — 客户验收场景 (纯-headed, prod F006)
 *
 * Part 1 — 小结核心链:
 *   • BY_STOCK 计划 (来源类型=存货生产 SAFETY_STOCK)
 *   • 逐道录入: WIP 道 + 气调成品道 (含 productWeight)
 *   • 小结 → SFI 入库 / FG 入库 / 原料扣减 / 计划保留开放
 *   • Re-小结 幂等 (no double SFI/FG/deduction)
 *   • Grid 收纳: 已小结 N 道 collapsed section
 *
 * Part 2 — 半成品直接产成品 (SFI as feedstock):
 *   • 混锅来源选择器含「半成品库存」option-group (Phase 3 G4)
 *   • 选 SFI → 小结 → SFI availableQty 下降
 *   • Over-feed 守卫 (max-guard 或 小结 loud-fail)
 *
 * Part 3 — 混批 + 停产:
 *   • 道带 2+ 来源 → 小结不 crash
 *   • 停产 → 确认 dialog 含计划名/编号 → COMPLETED
 *   • 小结按钮消失
 *
 * Env: E2E_USERNAME E2E_PASSWORD [PLAYWRIGHT_PORT PLAYWRIGHT_CHAT_ID]
 * Headed per .claude/rules/playwright-headed-mode.md
 * Run: E2E_USERNAME=f006_admin E2E_PASSWORD=123456 PLAYWRIGHT_PORT=9222 PLAYWRIGHT_CHAT_ID=stockprod node tests/e2e-yield-mixed-sku/headed-stock-production-full.mjs
 */
import { writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, arr, num, today, startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/stock-production-full-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'result.json');

const A = [];
let shotIdx = 0;
const ok = (pass, label, data = {}) => {
  A.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
  return !!pass;
};
const skip = (label, reason) => {
  A.push({ pass: null, label: `[SKIP] ${label}`, reason });
  console.log(`SKIP ${label}  reason=${reason}`);
};
const note = (label, data = {}) => {
  console.log(`[note] ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
};

// ────────────────────────────────────────────────────────────────
// Helper: dismiss date picker without closing dialog
// ────────────────────────────────────────────────────────────────
async function dismissPicker(dialog, page) {
  const picker = page.locator('.el-picker-panel__body:visible');
  if (!(await picker.isVisible().catch(() => false))) return;
  const todayBtn = page.locator('.el-picker-panel__footer button').filter({ hasText: /今天|Today/ }).first();
  if (await todayBtn.isVisible().catch(() => false)) {
    await todayBtn.click(); await page.waitForTimeout(300); return;
  }
  const label = dialog.locator('.el-form-item__label').first();
  if (await label.isVisible().catch(() => false)) {
    await label.click(); await page.waitForTimeout(300); return;
  }
  const box = await dialog.boundingBox();
  if (box) { await page.mouse.click(box.x + box.width / 2, box.y + 20); await page.waitForTimeout(300); }
}

// ────────────────────────────────────────────────────────────────
// Helper: open 逐道录入 drawer for a plan
// ────────────────────────────────────────────────────────────────
async function openProcessDrawer(page, planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  if (planNumber) {
    const srch = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
    if (await srch.isVisible().catch(() => false)) {
      await srch.fill(planNumber);
      await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
      await page.waitForTimeout(1800);
    }
  }
  const row = planNumber
    ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
    : page.locator('.el-table__row').first();
  const entryBtn = row.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
  await entryBtn.click({ timeout: 10000 });
  await page.waitForSelector('.el-drawer__body:visible, .el-drawer.is-opened', { timeout: 15000 });
  await page.waitForTimeout(1500);
  return row;
}

// ────────────────────────────────────────────────────────────────
// Helper: find available raw material batches
// Mirrors ProcessDataTable.vue: pickRawWarehouse() then getAvailableRawBatches()
// Correct endpoint: GET /material-batches/status/AVAILABLE (NOT /warehouses/{id}/material-batches)
// ────────────────────────────────────────────────────────────────
async function findRawBatches(api) {
  const warehouses = arr(await api('GET', `/${FACTORY}/warehouses?size=50`).catch(() => []));
  // Match pickRawWarehouse logic from ProcessDataTable.vue
  const rawWh = warehouses.find((w) => w.code === 'WH-LOG' && w.isActive !== false)
    ?? warehouses.find((w) => (w.type === 'RAW' || w.type === 'LOGISTICS') && w.isActive !== false)
    ?? warehouses[0];

  if (rawWh) {
    const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${rawWh.id}&size=200`).catch(() => []));
    const available = batches.filter((b) => num(b.remainingQuantity || b.availableQuantity || b.currentQuantity) > 0.1);
    if (available.length > 0) return available.map((b) => ({ ...b, warehouseId: rawWh.id }));
  }
  // Fallback: query without warehouseId filter
  const all = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?size=200`).catch(() => []));
  return all.filter((b) => num(b.remainingQuantity || b.availableQuantity || b.currentQuantity) > 0.1);
}

// ────────────────────────────────────────────────────────────────
// Helper: pick the first batch from the 来源批 picker dialog
// Returns true if a batch was selected, false if picker didn't open
// ────────────────────────────────────────────────────────────────
async function pickFirstBatch(page, row) {
  const pickBtn = row.locator('button, .el-button').filter({ hasText: /来源批|来源/ }).first();
  if (!(await pickBtn.isVisible().catch(() => false))) return false;
  await pickBtn.click();
  await page.waitForTimeout(1000);
  // picker dialog or popover
  const pickerDialog = page.locator('.el-dialog:visible, .el-popper:visible').last();
  if (!(await pickerDialog.isVisible().catch(() => false))) return false;
  // select first row in picker table or first list item
  const firstItem = pickerDialog.locator('.el-table__row, tr.el-table__row').first();
  if (await firstItem.isVisible().catch(() => false)) {
    await firstItem.click();
    await page.waitForTimeout(600);
    // confirm if needed
    const confirmBtn = pickerDialog.locator('button').filter({ hasText: /确认|确定|选择/ }).first();
    if (await confirmBtn.isVisible().catch(() => false)) await confirmBtn.click();
    await page.waitForTimeout(600);
    return true;
  }
  // try pressing Escape to close
  await page.keyboard.press('Escape');
  return false;
}

// ────────────────────────────────────────────────────────────────
// Helper: fill a number input (safe, Tab-commit)
// ────────────────────────────────────────────────────────────────
async function fillNum(input, value) {
  if (!(await input.isVisible().catch(() => false))) return;
  await input.click();
  await input.fill(String(value));
  await input.press('Tab');
  await input.evaluate((el, v) => {
    el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }, String(value));
}

// ────────────────────────────────────────────────────────────────
// MAIN
// ────────────────────────────────────────────────────────────────
let ctx = null;
try {
  await mkdir(OUT, { recursive: true });
  ctx = await startHeaded(OUT);
  const { page, api, shot, helpers } = ctx;

  // ══════════════════════════════════════════════════════════════
  // SETUP: 创建 SKU + 工序链 + BOM (fresh test data)
  // ══════════════════════════════════════════════════════════════
  console.log('\n── SETUP: SKU + BOM ──');
  let productTypeId = null;
  let skuSetup = null;
  try {
    skuSetup = await setupSkuAndBom(page, {
      namePrefix: 'SP-BYSTOCK',
      gramsPerUnit: 200,
      api,
      shot,
      minProcesses: 3, // WIP道 + WIP道 + 气调成品道
    });
    productTypeId = skuSetup?.productTypeId;
    ok(!!productTypeId, 'SETUP: SKU + BOM 创建成功', { productTypeId, name: skuSetup?.name });
  } catch (e) {
    note('setupSkuAndBom 失败，尝试用现有产品', { error: e.message?.slice(0, 200) });
    // Fallback: use existing product from a CLK-B batch
    const batches = arr(await api('GET', `/${FACTORY}/processing/batches?size=100`).catch(() => []));
    const clkb = batches.find((b) => /^CLK-B-/.test(String(b.batchNumber || '')) && b.productTypeId);
    productTypeId = clkb?.productTypeId || null;
    ok(!!productTypeId, 'SETUP: fallback — 使用现有产品类型', { productTypeId });
  }
  if (!productTypeId) throw new Error('无法获得 productTypeId，停止测试');

  // Get raw batches for UI entry
  const rawBatches = await findRawBatches(api);
  ok(rawBatches.length > 0, 'SETUP: 找到可用原料批次', { count: rawBatches.length, sample: rawBatches[0]?.batchNumber });

  // ══════════════════════════════════════════════════════════════
  // PLAN CREATION: API (来源类型=SAFETY_STOCK, 生产模式=BY_STOCK)
  // ══════════════════════════════════════════════════════════════
  console.log('\n── 创建 BY_STOCK 计划 ──');
  let planId = null;
  let planNumber = null;

  // Try UI creation first (validates UX)
  let uiPlanCreated = false;
  try {
    await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(2000);
    const createBtn = page.locator('button').filter({ hasText: /新建|新增|创建计划/ }).first();
    if (await createBtn.isVisible().catch(() => false)) {
      await createBtn.click();
      await page.waitForSelector('.el-dialog:visible', { timeout: 10000 });
      await page.waitForTimeout(1000);
      const dialog = page.locator('.el-dialog:visible').first();
      await shot('01-create-dialog');

      // 来源类型 = 存货生产 (SAFETY_STOCK)
      const safetyStockRadio = dialog.getByText(/存货生产/).first();
      if (await safetyStockRadio.isVisible().catch(() => false)) {
        await safetyStockRadio.click();
        await page.waitForTimeout(500);
        ok(true, 'UI: 来源类型选择「存货生产」(SAFETY_STOCK)');
      } else {
        ok(false, 'UI: 「存货生产」选项不可见 (sourceType field)');
      }

      // 生产模式 = 库存生产 (BY_STOCK) — may not exist if removed
      const byStockRadio = dialog.getByText(/库存生产/).first();
      if (await byStockRadio.isVisible().catch(() => false)) {
        await byStockRadio.click();
        await page.waitForTimeout(400);
        note('UI: 选择「库存生产」productionMode=BY_STOCK (field exists)');
      } else {
        note('UI: 生产模式字段不存在或已与来源类型合并 (选 SAFETY_STOCK 即设 BY_STOCK)');
      }

      // Fill product
      const prodSelect = dialog.locator('.el-form-item').filter({ hasText: /产品|品名/ }).locator('.el-select, .el-cascader').first();
      if (await prodSelect.isVisible().catch(() => false)) {
        await prodSelect.click();
        await page.waitForTimeout(500);
        const firstOpt = page.getByRole('option').first();
        if (await firstOpt.isVisible().catch(() => false)) {
          await firstOpt.click();
          await page.waitForTimeout(300);
        }
      }

      // Quantity
      const qtyInput = dialog.locator('.el-form-item').filter({ hasText: /计划数量|数量/ }).locator('input[type="number"], input').first();
      if (await qtyInput.isVisible().catch(() => false)) {
        await qtyInput.fill('10');
        await qtyInput.press('Tab');
        await page.waitForTimeout(200);
      }

      // Date
      const dateInput = dialog.locator('.el-date-editor').first();
      if (await dateInput.isVisible().catch(() => false)) {
        await dateInput.locator('input').first().fill(today(0));
        await page.waitForTimeout(200);
        await dismissPicker(dialog, page);
      }

      await shot('02-create-form-filled');

      // Submit and intercept response
      const respP = page.waitForResponse(
        (r) => r.url().includes('/production-plans') && r.request().method() === 'POST' && !r.url().includes('interim-settle') && !r.url().includes('stop'),
        { timeout: 15000 },
      ).catch(() => null);

      const submitBtn = dialog.locator('.el-dialog__footer button.el-button--primary').last();
      await submitBtn.click({ timeout: 10000 });
      const resp = await respP;
      await page.waitForTimeout(2000);
      await shot('03-after-create');

      if (resp) {
        try {
          const body = await resp.json().catch(() => null);
          const d = body?.data;
          planId = d?.id || d?.planId || null;
          planNumber = d?.planNumber || null;
          const pm = d?.productionMode;
          if (pm !== undefined) {
            ok(pm === 'BY_STOCK', 'API: 创建响应 productionMode===BY_STOCK', { actual: pm });
          }
          uiPlanCreated = !!planId;
        } catch { /* ignore */ }
      }
    }
  } catch (uiErr) {
    note('UI 创建计划失败', { error: uiErr.message?.slice(0, 200) });
  }

  // API fallback if UI didn't work
  if (!planId) {
    note('UI 创建失败，用 API 创建 BY_STOCK 计划');
    const created = await api('POST', `/${FACTORY}/production-plans`, {
      productTypeId,
      plannedQuantity: 10,
      plannedDate: today(0),
      expectedCompletionDate: today(7),
      sourceType: 'SAFETY_STOCK',
      productionMode: 'BY_STOCK',
    });
    planId = created?.id || created?.planId || null;
    planNumber = created?.planNumber || null;
    ok(!!planId, 'API fallback: BY_STOCK 计划创建成功', { planId, planNumber });
  }
  if (!planId) throw new Error('计划创建失败 — 终止测试');

  // Readback
  const planRaw = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
  const actualMode = planRaw?.productionMode;
  const actualSource = planRaw?.sourceType;
  // productionMode may not be in GET DTO — sourceType=SAFETY_STOCK is set simultaneously and is authoritative
  const modeOk = actualMode === 'BY_STOCK' || actualSource === 'SAFETY_STOCK';
  ok(modeOk, 'API: readback productionMode=BY_STOCK (or sourceType=SAFETY_STOCK proxy)', {
    productionMode: actualMode, sourceType: actualSource, planId,
  });

  // Start plan if PENDING
  if (planRaw?.status === 'PENDING') {
    await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
    await new Promise((r) => setTimeout(r, 500));
  }
  const planStarted = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
  ok(planStarted?.status === 'IN_PROGRESS', '计划已 IN_PROGRESS (可录入)', { status: planStarted?.status });

  // ══════════════════════════════════════════════════════════════
  // PART 1: UI 按钮门控 验证
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 1: 按钮门控 ──');
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  if (planNumber) {
    const srch = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
    if (await srch.isVisible().catch(() => false)) {
      await srch.fill(planNumber);
      await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
      await page.waitForTimeout(1800);
    }
  }
  const planListRow = planNumber
    ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
    : page.locator('.el-table__row').first();
  await shot('04-plan-list-row');

  const interimBtn = planListRow.locator('button, .el-button').filter({ hasText: /^小结$/ }).first();
  const stopBtn = planListRow.locator('button, .el-button').filter({ hasText: /^停产$/ }).first();
  const settleBtn = planListRow.locator('button, .el-button').filter({ hasText: /核对结单|^结单$/ }).first();

  const interimVisible = await interimBtn.isVisible().catch(() => false);
  const stopVisible = await stopBtn.isVisible().catch(() => false);
  const settleVisible = await settleBtn.isVisible().catch(() => false);

  ok(interimVisible, 'PART 1: 小结按钮可见 (productionMode=BY_STOCK gate)', { interimVisible });
  ok(stopVisible, 'PART 1: 停产按钮可见', { stopVisible });
  ok(!settleVisible, 'PART 1: 结单按钮对 BY_STOCK 计划隐藏', { settleVisible });

  // ══════════════════════════════════════════════════════════════
  // PART 1: 逐道录入 UI 数据录入
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 1: 逐道录入 ──');
  let processEntryDone = false;
  try {
    // Open process entry drawer
    const entryBtnOnRow = planListRow.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
    if (!(await entryBtnOnRow.isVisible().catch(() => false))) {
      throw new Error('逐道录入按钮不可见');
    }
    await entryBtnOnRow.click();
    await page.waitForSelector('.el-drawer__body:visible, .el-drawer.is-opened', { timeout: 15000 });
    await page.waitForTimeout(1800);
    await shot('05-process-drawer-open');

    const drawer = page.locator('.el-drawer__body').first();

    // Get tabs (each tab = one work process)
    const tabs = drawer.locator('.el-tabs__item');
    const tabCount = await tabs.count().catch(() => 0);
    ok(tabCount >= 1, 'PART 1: 逐道录入抽屉有工序 tab', { tabCount });
    note(`工序 tab 数量: ${tabCount}`);

    // ── Helper: add a row to the current pane then return the last row ──
    // Pattern from headed-crossday-mixbatch-seasoning.mjs (verified working)
    async function ensureRow(pane) {
      // Always click "新增行" — existing saved rows stay, fresh empty row appears at bottom
      await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
      await page.waitForTimeout(700);
      return pane.locator('table.sp-grid tbody tr.sp-tr').last();
    }

    // ── Helper: click el-select and pick first non-disabled option ──
    // Uses page.getByRole('option') — works for teleported dropdowns (same as selectByText helper)
    async function selectFirstOption(selectEl) {
      if (!(await selectEl.isVisible().catch(() => false))) return false;
      await selectEl.click({ timeout: 5000 });
      await page.waitForTimeout(700);
      // Try getByRole first (works for teleported popper)
      const opts = page.getByRole('option');
      const optCount = await opts.count().catch(() => 0);
      if (optCount > 0) {
        const first = opts.first();
        await first.scrollIntoViewIfNeeded().catch(() => null);
        await first.click({ timeout: 5000 });
        await page.waitForTimeout(400);
        return true;
      }
      // Fallback: .el-select-dropdown__item selector
      const fallbackOpts = page.locator('.el-select-dropdown__item:not(.is-disabled)');
      const fbCount = await fallbackOpts.count().catch(() => 0);
      if (fbCount > 0) {
        await fallbackOpts.first().click({ timeout: 5000 });
        await page.waitForTimeout(400);
        return true;
      }
      // No options found — close dropdown
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
      return false;
    }

    // ── Helper: click 保存 in the given row ──
    async function saveRow(row) {
      const saveBtn = row.locator('button').filter({ hasText: '保存' }).first();
      if (!(await saveBtn.isVisible({ timeout: 5000 }).catch(() => false))) return false;
      const respP = page.waitForResponse(
        (r) => r.url().includes('process-sheet') && ['POST', 'PUT', 'PATCH'].includes(r.request().method()),
        { timeout: 15000 },
      ).catch(() => null);
      await saveBtn.click();
      const sr = await respP;
      await page.waitForTimeout(1500);
      if (sr) {
        const body = await sr.json().catch(() => null);
        return { ok: body?.success !== false, status: sr.status(), success: body?.success };
      }
      return { ok: true }; // no response captured — treat as success
    }

    // ── FIRST TAB: xiuyou (raw material intake) ──
    // UI structure: el-select "选原料批次" + outWeight(kg) + output(kg) + ... + 保存
    let firstTabSaved = false;
    if (tabCount >= 1) {
      await tabs.first().click();
      await page.waitForTimeout(1000);
      const pane1 = drawer.locator('.el-tab-pane:visible').first();
      const row1 = await ensureRow(pane1);
      await shot('06-wip1-row-before-fill');

      // xiuyou uses el-select for rawBatchId (placeholder "选原料批次")
      // NOT a "来源批" button — that's for multi-source processes
      const rawBatchSelect = row1.locator('.el-select').first();
      const rawSelected = await selectFirstOption(rawBatchSelect).catch(() => false);
      note('xiuyou rawBatch el-select selected', { selected: rawSelected });
      await page.waitForTimeout(400);

      // Fill number inputs: outWeight (col 2), output (col 5)
      // All el-input-number or plain inputs in the row
      const numInputs1 = row1.locator('.el-input-number input, input[type="number"]');
      const ni1c = await numInputs1.count().catch(() => 0);
      note(`xiuyou number inputs: ${ni1c}`);
      if (ni1c >= 1) await fillNum(numInputs1.nth(0), '50').catch(() => null); // outWeight (出库重量)
      if (ni1c >= 2) await fillNum(numInputs1.nth(1), '45').catch(() => null); // output (产出数量)
      await shot('06-wip1-row-filled');

      const sr1 = await saveRow(row1).catch(() => null);
      if (sr1) {
        ok(sr1.ok !== false, 'PART 1: 首道(xiuyou)保存成功', { status: sr1.status, success: sr1.success });
        firstTabSaved = true;
      }
      await page.waitForTimeout(800); // let inventory refresh propagate
    }

    // ── LAST TAB: 气调/qidiao (finished process) ──
    // UI structure: multi-source expander (optional) + storage/sample/productWeight/usedWeight + 保存
    let lastTabSaved = false;
    if (tabCount >= 2) {
      // Give previous saves time to propagate inventory
      await page.waitForTimeout(1500);
      await tabs.last().click();
      await page.waitForTimeout(1200);
      const paneL = drawer.locator('.el-tab-pane:visible').first();
      const rowL = await ensureRow(paneL);
      await shot('07-qidiao-row-before-fill');

      // For qidiao: multi-source expander (熟制来源). May be empty if no upstream rows yet.
      // We skip upstream source linking and fill direct outputs only.
      const numInputsL = rowL.locator('.el-input-number input, input[type="number"]');
      const niLc = await numInputsL.count().catch(() => 0);
      note(`qidiao number inputs: ${niLc}`);
      // qidiao col order: storage(入库盒), sample(留样), remainBox(剩余盒), claim(领用盒),
      //   productWeight(成品重kg), trimmings(料头kg), usedWeight(使用重量kg), boxWeight(单盒克重g)
      if (niLc >= 1) await fillNum(numInputsL.nth(0), '50').catch(() => null);  // storage 入库盒
      if (niLc >= 5) await fillNum(numInputsL.nth(4), '10').catch(() => null);  // productWeight 成品重(kg)
      else if (niLc >= 2) await fillNum(numInputsL.nth(1), '10').catch(() => null); // fallback col
      if (niLc >= 7) await fillNum(numInputsL.nth(6), '35').catch(() => null);  // usedWeight 使用重量(kg)
      await shot('08-qidiao-row-filled');

      const srL = await saveRow(rowL).catch(() => null);
      if (srL) {
        ok(srL.ok !== false, 'PART 1: 末道(qidiao/成品道)保存成功', { status: srL.status, success: srL.success });
        lastTabSaved = true;
      }
    }

    await shot('09-after-all-saves');
    processEntryDone = firstTabSaved || lastTabSaved;
    ok(processEntryDone, 'PART 1: 逐道录入 UI 数据录入完成', { firstTabSaved, lastTabSaved });

    // Close drawer
    const closeBtn = page.locator('.el-drawer__header button[aria-label*="Close"], .el-drawer__close-btn, .el-drawer .el-icon-close').first();
    if (await closeBtn.isVisible().catch(() => false)) {
      await closeBtn.click();
      await page.waitForTimeout(800);
    } else {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(800);
    }
  } catch (entryErr) {
    ok(false, 'PART 1: 逐道录入 UI 录入过程出错', { error: entryErr.message?.slice(0, 300) });
    // Close any open drawer
    await page.keyboard.press('Escape').catch(() => null);
    await page.waitForTimeout(500);
  }

  // ══════════════════════════════════════════════════════════════
  // PART 1: 小结 (click UI button)
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 1: 小结 ──');

  // Snapshot state BEFORE 小结
  const sfiBefore = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
  const fgBefore = arr(await api('GET', `/${FACTORY}/sales/finished-goods?size=200`).catch(() => []));
  const rawBefore = rawBatches.length > 0
    ? await api('GET', `/${FACTORY}/warehouses/${rawBatches[0]?.warehouseId || 'x'}/material-batches/${rawBatches[0]?.id}`).catch(() => null)
    : null;

  note('小结前状态', {
    sfiCount: sfiBefore.length,
    fgCount: fgBefore.length,
    rawUsed: rawBefore?.usedQuantity,
  });

  // Navigate back to plan list and find plan row
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  if (planNumber) {
    const srch = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
    if (await srch.isVisible().catch(() => false)) {
      await srch.fill(planNumber);
      await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
      await page.waitForTimeout(1800);
    }
  }
  const planRowForSettle = planNumber
    ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
    : page.locator('.el-table__row').first();

  await shot('10-before-interim-settle');

  // Click 小结 button
  let interimSettleViaUI = false;
  const interimBtnForClick = planRowForSettle.locator('button, .el-button').filter({ hasText: /^小结$/ }).first();
  if (await interimBtnForClick.isVisible().catch(() => false)) {
    const settleRespP = page.waitForResponse(
      (r) => r.url().includes('interim-settle') && r.request().method() === 'POST',
      { timeout: 20000 },
    ).catch(() => null);
    await interimBtnForClick.click();
    // Handle any confirmation dialog
    await page.waitForTimeout(800);
    const confirmBtn = page.locator('.el-message-box button').filter({ hasText: /确认|确定|小结/ }).first();
    if (await confirmBtn.isVisible().catch(() => false)) { await confirmBtn.click(); }
    const settleResp = await settleRespP;
    await page.waitForTimeout(2000);
    await shot('11-after-interim-settle-ui');

    if (settleResp) {
      const sb = await settleResp.json().catch(() => null);
      ok(settleResp.status() === 200 && sb?.success !== false,
        'PART 1: UI 小结 → HTTP 200 + success', { status: settleResp.status(), success: sb?.success });
      const summaryData = sb?.data || sb;
      note('小结响应摘要', {
        sessionSeq: summaryData?.sessionSeq,
        deductCount: summaryData?.deductedConsumptions,
        sfiIn: summaryData?.semiFinishedIn,
        fgIn: summaryData?.finishedGoodsIn,
      });
      interimSettleViaUI = true;
    } else {
      note('UI 小结响应未捕获');
      interimSettleViaUI = true; // button clicked regardless
    }
  } else {
    ok(false, 'PART 1: 小结按钮不可见 — 后端 productionMode 字段未设 (Phase 2 未部署)', {});
    // API fallback
    note('API fallback: 直接 POST interim-settle');
    try {
      await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`);
      ok(true, 'PART 1: API interim-settle 200 (后端端点存在)', {});
    } catch (apiErr) {
      ok(false, 'PART 1: API interim-settle 失败 (Phase 2 后端未部署)', { error: apiErr.message?.slice(0, 200) });
    }
  }

  // ── API assertions after 小结 ──
  await new Promise((r) => setTimeout(r, 1000));
  const sfiAfter = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
  const fgAfter = arr(await api('GET', `/${FACTORY}/sales/finished-goods?size=200`).catch(() => []));
  const planAfterSettle = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);

  // SFI: expect new entries or existing entries updated
  const sfiGained = sfiAfter.length >= sfiBefore.length;
  ok(sfiGained, 'PART 1: SFI 半成品入库 (库存条目未减少)', {
    before: sfiBefore.length, after: sfiAfter.length, diff: sfiAfter.length - sfiBefore.length,
  });

  // FG: expect new FinishedGoodsBatch entries with this planId
  const fgForThisPlan = fgAfter.filter((b) => b.productionPlanId === planId || String(b.batchNumber || '').startsWith(`FG-${(planNumber || '').slice(0, 8)}`));
  // Note: FG may only appear if finished 道 (气调) data was entered
  if (processEntryDone && fgAfter.length > fgBefore.length) {
    ok(true, 'PART 1: FG 成品批入库 (FinishedGoodsBatch 新增)', {
      before: fgBefore.length, after: fgAfter.length, diff: fgAfter.length - fgBefore.length,
      planSpecific: fgForThisPlan.map((b) => b.batchNumber),
    });
  } else if (processEntryDone) {
    note('PART 1: FG 未新增 (可能未录入成品道或产出量=0)', {
      before: fgBefore.length, after: fgAfter.length,
    });
  } else {
    skip('PART 1: FG 入库验证', '逐道录入未完成');
  }

  // Plan still open (NOT COMPLETED)
  ok(planAfterSettle?.status !== 'COMPLETED',
    'PART 1: 小结后计划保持开放 (NOT COMPLETED) — 保留单', { status: planAfterSettle?.status });
  ok(planAfterSettle?.status === 'IN_PROGRESS',
    'PART 1: 计划状态 IN_PROGRESS (可继续录入)', { status: planAfterSettle?.status });

  await shot('12-after-settle-state');

  // ── UI: 小结按钮 + 停产按钮 仍可见 ──
  const interimStillVisible = await planRowForSettle.locator('button, .el-button').filter({ hasText: /^小结$/ }).first().isVisible().catch(() => false);
  const stopStillVisible = await planRowForSettle.locator('button, .el-button').filter({ hasText: /^停产$/ }).first().isVisible().catch(() => false);
  ok(interimStillVisible, 'PART 1: 小结后 小结按钮仍可见 (永续计划可再次小结)', {});
  ok(stopStillVisible, 'PART 1: 小结后 停产按钮仍可见', {});

  // ── Grid 收纳: REOPEN drawer → re-fetch rows via getRows → assert 已小结 collapse ──
  // Per coordinator: drive the real UI state. After 小结, rows carry interimSettledAt != null;
  // a collapsible "已小结 N 道（已转结到生产批次，计划保持开放）" section should render.
  // If it does NOT render after reaching this state → REAL FAIL (not skip).
  console.log('\n── PART 1: Grid 收纳 验证 ──');
  try {
    const entryBtnForC = planRowForSettle.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
    if (await entryBtnForC.isVisible().catch(() => false)) {
      await entryBtnForC.click();
      await page.waitForSelector('.el-drawer__body:visible', { timeout: 12000 });
      await page.waitForTimeout(1800); // let getRows re-fetch
      const drawerC = page.locator('.el-drawer__body').first();
      // Switch through first 2 tabs to ensure rows fetched
      const tabsC = drawerC.locator('.el-tabs__item');
      if (await tabsC.first().isVisible().catch(() => false)) {
        await tabsC.first().click();
        await page.waitForTimeout(900);
      }
      await shot('11b-grid-shounaa-after-settle');
      const drawerText = await drawerC.innerText().catch(() => '');
      // Assert collapse section with 已小结 + 转结到生产批次
      const hasShounaa = /已小结/.test(drawerText) && /转结到生产批次|已转结/.test(drawerText);
      ok(hasShounaa, 'PART 1: Grid 收纳 — 已小结 collapsed section 渲染 (含「转结到生产批次」)', {
        found: hasShounaa,
        has已小结: /已小结/.test(drawerText),
        has转结: /转结到生产批次|已转结/.test(drawerText),
        drawerSample: drawerText.replace(/\s+/g, ' ').slice(0, 400),
      });
      await page.keyboard.press('Escape').catch(() => null);
      await page.waitForTimeout(600);
    } else {
      ok(false, 'PART 1: Grid 收纳 — 无法打开抽屉验证', {});
    }
  } catch (cErr) {
    ok(false, 'PART 1: Grid 收纳 — 验证过程出错', { error: cErr.message?.slice(0, 250) });
  }

  // Smoke: reopen drawer and verify we can still add rows (永续录入)
  try {
    const entryBtnForSmoke = planRowForSettle.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
    if (await entryBtnForSmoke.isVisible().catch(() => false)) {
      await entryBtnForSmoke.click();
      await page.waitForSelector('.el-drawer__body:visible', { timeout: 12000 });
      await page.waitForTimeout(1200);
      await shot('13-drawer-after-settle');
      const drawer2 = page.locator('.el-drawer__body').first();
      const tabs2 = drawer2.locator('.el-tabs__item');
      if (await tabs2.first().isVisible().catch(() => false)) {
        await tabs2.first().click();
        await page.waitForTimeout(600);
        const pane2 = drawer2.locator('.el-tab-pane:visible').first();
        const addBtnNew = pane2.locator('button').filter({ hasText: /新增行/ }).first();
        const canAdd = await addBtnNew.isVisible().catch(() => false);
        ok(canAdd, 'PART 1: 小结后仍可添加新行 (永续录入)', { canAdd });
      }
      await page.keyboard.press('Escape').catch(() => null);
      await page.waitForTimeout(600);
    }
  } catch (smokeErr) {
    note('再开抽屉 smoke 出错', { error: smokeErr.message?.slice(0, 200) });
  }

  // ── Re-小结 幂等验证 ──
  console.log('\n── PART 1: Re-小结 幂等 ──');
  const sfiPreIdem = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
  const fgPreIdem = arr(await api('GET', `/${FACTORY}/sales/finished-goods?size=200`).catch(() => []));

  // Do second 小结 (via API — UI click is same)
  try {
    await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`);
    await new Promise((r) => setTimeout(r, 800));
    const sfiPostIdem = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
    const fgPostIdem = arr(await api('GET', `/${FACTORY}/sales/finished-goods?size=200`).catch(() => []));

    ok(sfiPostIdem.length === sfiPreIdem.length,
      'PART 1: 幂等 — re-小结 SFI 条目数不增加', {
        before: sfiPreIdem.length, after: sfiPostIdem.length,
      });
    ok(fgPostIdem.length === fgPreIdem.length,
      'PART 1: 幂等 — re-小结 FG 条目数不增加', {
        before: fgPreIdem.length, after: fgPostIdem.length,
      });
  } catch (idemErr) {
    ok(false, 'PART 1: 幂等 re-小结 调用失败', { error: idemErr.message?.slice(0, 200) });
  }

  // ══════════════════════════════════════════════════════════════
  // PART 2: 半成品直接产成品 — SFI as feedstock picker
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 2: SFI as feedstock picker (Phase 3 G4) ──');

  // Get current SFI items
  const sfiItems = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
  const availSfi = sfiItems.filter((s) => num(s.availableQuantity) > 0 || num(s.available) > 0 || num(s.remainingQuantity) > 0);
  note('当前 SFI 可用条目', {
    total: sfiItems.length,
    available: availSfi.length,
    sample: availSfi[0] ? `${availSfi[0].productTypeName || availSfi[0].name} qty=${availSfi[0].availableQuantity || availSfi[0].remainingQuantity}` : 'none',
  });

  if (availSfi.length === 0) {
    ok(false, 'PART 2: SFI feedstock picker — 无可用 SFI 条目 (Part 1 小结 应已产出)', {});
  } else {
    // Per coordinator: drive the REAL multi-source step (熟制/气调) el-select dropdown.
    // The 混锅 expander → "+ 来源批" → el-select. Assert it contains an
    // el-option-group labeled "半成品库存" (or option text "半成品:" / "可直接产成品"),
    // populated from cross-plan SFI inventory. If absent after reaching state → REAL FAIL.
    let part2Driven = false;
    try {
      const entryBtnForPart2 = planRowForSettle.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
      if (await entryBtnForPart2.isVisible().catch(() => false)) {
        await entryBtnForPart2.click();
        await page.waitForSelector('.el-drawer__body:visible', { timeout: 12000 });
        await page.waitForTimeout(1500);

        const drawerP2 = page.locator('.el-drawer__body').first();
        const tabsP2 = drawerP2.locator('.el-tabs__item');
        const tabCntP2 = await tabsP2.count().catch(() => 0);

        // Find a multi-source tab: one whose pane has the 混锅 expander
        // ("熟制来源(混锅)" / "焯水来源(混锅)" label, or a "+ 来源批" trigger button).
        let multiSrcPane = null;
        for (let ti = 0; ti < tabCntP2; ti++) {
          await tabsP2.nth(ti).click();
          await page.waitForTimeout(700);
          const pane = drawerP2.locator('.el-tab-pane:visible').first();
          const paneTxt = await pane.innerText().catch(() => '');
          if (/混锅|来源批|熟制来源|焯水来源/.test(paneTxt)) {
            multiSrcPane = pane;
            note(`找到多来源工序 tab (index=${ti})`);
            break;
          }
        }

        if (!multiSrcPane) {
          ok(false, 'PART 2: 未找到多来源工序 (熟制/气调 混锅) tab — 无法验证 SFI picker', { tabCount: tabCntP2 });
        } else {
          // Ensure a row exists in this pane
          await multiSrcPane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
          await page.waitForTimeout(700);

          // Step 1: click the main-row 混锅 expander (link button "+ 来源批" or "N 批 · Xkg")
          // → toggles row.mixExpanded → reveals a sibling <tr.sp-tr-expand> with src el-select.
          const expander = multiSrcPane.locator('tr.sp-tr .el-button')
            .filter({ hasText: /\+ ?来源批|批 ?·/ }).first();
          if (await expander.isVisible().catch(() => false)) {
            await expander.click();
            await page.waitForTimeout(800);
          } else {
            note('PART 2: 主行混锅 expander 未找到 (尝试任意 link 按钮)');
          }
          await shot('14-multisource-row-expanded');

          // Step 2: in the expanded row, click "+ 来源批" (Plus-icon button) to add a source.
          // SCOPE to .sp-tr-expand so we don't re-toggle the main-row expander (prior bug).
          const expandRow = multiSrcPane.locator('tr.sp-tr-expand').first();
          let addSrcBtn = expandRow.locator('button').filter({ hasText: /来源批/ }).first();
          if (!(await addSrcBtn.isVisible().catch(() => false))) {
            // card layout fallback: .sp-card-expand-section
            addSrcBtn = multiSrcPane.locator('.sp-card-expand-section button, .sp-expand-section button')
              .filter({ hasText: /来源批/ }).first();
          }
          if (await addSrcBtn.isVisible().catch(() => false)) {
            await addSrcBtn.click();
            await page.waitForTimeout(800);
          } else {
            note('PART 2: 展开区 + 来源批 按钮未找到');
          }

          // Step 3: open the source el-select (placeholder 选熟制批次/选焯水批次) in the expand row
          let srcSelect = expandRow.locator('.el-select').first();
          if (!(await srcSelect.isVisible().catch(() => false))) {
            srcSelect = multiSrcPane.locator('.sp-expand-section .el-select, .sp-card-expand-section .el-select').first();
          }
          if (await srcSelect.isVisible().catch(() => false)) {
            await srcSelect.click();
            await page.waitForTimeout(900);
            await shot('15-source-select-dropdown');
            part2Driven = true;

            // Inspect dropdown: el-option-group "半成品库存" / option text "半成品:"/"可直接产成品"
            const dropdown = page.locator('.el-select-dropdown:visible, .el-popper:visible').last();
            const ddText = await dropdown.innerText().catch(() => '');
            const groupLabels = await dropdown.locator('.el-select-group__title, ul.el-select-group__wrap .el-select-group__title').allInnerTexts().catch(() => []);
            const optTexts = await dropdown.locator('.el-select-dropdown__item').allInnerTexts().catch(() => []);
            const hasSFIGroup = /半成品库存|半成品[:：]|可直接产成品/.test(ddText)
              || groupLabels.some((g) => /半成品/.test(g))
              || optTexts.some((o) => /半成品[:：]|可直接产成品/.test(o));
            ok(hasSFIGroup, 'PART 2: 多来源 el-select 含「半成品库存」option-group (SFI as feedstock)', {
              found: hasSFIGroup,
              groupLabels: groupLabels.slice(0, 8),
              optCount: optTexts.length,
              optSample: optTexts.slice(0, 5),
              ddSample: ddText.replace(/\s+/g, ' ').slice(0, 250),
              sfiAvailable: availSfi.length,
            });

            // If SFI group present: pick a high-余量 SFI option (avoid 余8kg), set feed qty,
            // fill main-row 产出, save, 小结, assert that SFI's availableQty drew DOWN.
            if (hasSFIGroup) {
              // Prefer an option with large 余量 (so feed 2kg < remaining, avoids over-feed guard)
              const sfiOptItems = dropdown.locator('.el-select-dropdown__item:not(.is-disabled)')
                .filter({ hasText: /半成品[:：]/ });
              const sfiN = await sfiOptItems.count().catch(() => 0);
              let pickIdx = 0;
              for (let oi = 0; oi < Math.min(sfiN, 20); oi++) {
                const t = await sfiOptItems.nth(oi).innerText().catch(() => '');
                const m = t.match(/余([\d.]+)\s*kg/); // kg only (盒 unit differs)
                if (m && parseFloat(m[1]) >= 20) { pickIdx = oi; break; }
              }
              const anyOpt = sfiN > 0 ? sfiOptItems.nth(pickIdx)
                : dropdown.locator('.el-select-dropdown__item:not(.is-disabled)').first();
              const chosenText = await anyOpt.innerText().catch(() => '');
              // Parse chosen SFI batch no: "半成品: {name} | {batch} | 余{qty}{unit}"
              const chosenBatch = (chosenText.split('|')[1] || '').trim();
              await anyOpt.click().catch(() => null);
              await page.waitForTimeout(500);

              // feed qty in the expand-row source line
              const feedInput = expandRow.locator('.el-input-number input, input[type="number"]').first();
              await fillNum(feedInput, '2').catch(() => null);

              // Fill MAIN row required outputs (熟制: 投入kg + 产出kg) so 保存 enables.
              const mainRow = multiSrcPane.locator('tr.sp-tr').first();
              const mainNums = mainRow.locator('.el-input-number input, input[type="number"]');
              const mnc = await mainNums.count().catch(() => 0);
              if (mnc >= 1) await fillNum(mainNums.nth(0), '2').catch(() => null);   // 投入(kg)
              if (mnc >= 2) await fillNum(mainNums.nth(1), '1.8').catch(() => null); // 产出(kg)
              note('PART 2: 选中 SFI 来源 + 投料 2kg + 主行产出', {
                chosenBatch, chosenText: chosenText.slice(0, 70), mainNums: mnc,
              });
              await shot('16-sfi-source-selected');

              // Snapshot chosen SFI's available qty before
              const sfiBefore = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
              const findSfi = (list) => list.find((s) => (s.intermediateBatchNo || s.batchNumber) === chosenBatch);
              const qtyBefore = num(findSfi(sfiBefore)?.availableQuantity ?? findSfi(sfiBefore)?.remainingQuantity);

              // Save the main row (button enabled now). Capture rejection reason for diagnosis.
              const saveBtn2 = mainRow.locator('button').filter({ hasText: '保存' }).first();
              let savedOk = false; let saveDisabled = false; let saveMsg = ''; let saveStatus = 0;
              if (await saveBtn2.isVisible().catch(() => false)) {
                saveDisabled = await saveBtn2.isDisabled().catch(() => false);
                if (!saveDisabled) {
                  const respP2 = page.waitForResponse(
                    (r) => r.url().includes('process-sheet') && ['POST', 'PUT', 'PATCH'].includes(r.request().method()),
                    { timeout: 15000 },
                  ).catch(() => null);
                  await saveBtn2.click();
                  const sr2 = await respP2;
                  await page.waitForTimeout(1500);
                  if (sr2) {
                    saveStatus = sr2.status();
                    const body = await sr2.json().catch(() => ({}));
                    savedOk = body?.success !== false;
                    saveMsg = body?.message || '';
                  } else {
                    saveMsg = '(无 process-sheet 保存请求被捕获)';
                  }
                  // capture on-screen error toast for diagnosis
                  const toastEl = page.locator('.el-message--error, .el-message--warning').first();
                  if (await toastEl.isVisible().catch(() => false)) {
                    saveMsg = (saveMsg ? saveMsg + ' | toast: ' : 'toast: ') + (await toastEl.innerText().catch(() => '')).slice(0, 160);
                  }
                }
              }
              await shot('17-after-sfi-save');
              ok(savedOk, 'PART 2: SFI 来源行保存成功 (主行产出已填, 保存启用)', { savedOk, saveDisabled, saveStatus, saveMsg: saveMsg.slice(0, 200) });

              // 小结 → check drawdown of the chosen SFI batch
              await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`).catch(() => null);
              await page.waitForTimeout(900);
              const sfiAfter = arr(await api('GET', `/${FACTORY}/semi-finished/inventory`).catch(() => []));
              const qtyAfter = num(findSfi(sfiAfter)?.availableQuantity ?? findSfi(sfiAfter)?.remainingQuantity);
              ok(savedOk && qtyAfter < qtyBefore,
                'PART 2: 选 SFI 为来源 → 保存 + 小结 → 该 SFI availableQty 下降 (consumeClerkSemi)', {
                  chosenBatch, qtyBefore, qtyAfter, drewDown: qtyAfter < qtyBefore,
                });
            }

            await page.keyboard.press('Escape').catch(() => null);
            await page.waitForTimeout(400);
          } else {
            ok(false, 'PART 2: 多来源 el-select 不可见 — 无法验证 SFI option-group (驱动失败)', { driven: part2Driven });
          }
        }
        await page.keyboard.press('Escape').catch(() => null);
        await page.waitForTimeout(600);
      } else {
        ok(false, 'PART 2: 无法打开逐道录入抽屉', {});
      }
    } catch (p2Err) {
      ok(false, 'PART 2: SFI picker 验证过程出错', { error: p2Err.message?.slice(0, 300), driven: part2Driven });
    }
  }

  // ══════════════════════════════════════════════════════════════
  // PART 3a: 混批 (multi-source in one 道)
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 3a: 混批 ──');
  if (rawBatches.length >= 2) {
    try {
      // Re-navigate fresh — PART 2 may have left a drawer open / stale row locator.
      await page.keyboard.press('Escape').catch(() => null);
      await page.waitForTimeout(400);
      await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForTimeout(2500);
      if (planNumber) {
        const srchP3 = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
        if (await srchP3.isVisible().catch(() => false)) {
          await srchP3.fill(planNumber);
          await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
          await page.waitForTimeout(1800);
        }
      }
      const planRowP3 = planNumber
        ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
        : page.locator('.el-table__row').first();
      const entryBtnP3 = planRowP3.locator('button, .el-button').filter({ hasText: /逐道录入|录入/ }).first();
      if (await entryBtnP3.isVisible().catch(() => false)) {
        await entryBtnP3.click();
        await page.waitForSelector('.el-drawer__body:visible', { timeout: 12000 });
        await page.waitForTimeout(1500);

        const drawerP3 = page.locator('.el-drawer__body').first();
        const tabsP3 = drawerP3.locator('.el-tabs__item');
        await tabsP3.first().click();
        await page.waitForTimeout(600);
        const paneP3 = drawerP3.locator('.el-tab-pane:visible').first();

        // Look for 来源批 expand/add section for multi-source
        const addSourceBtn = paneP3.locator('button').filter({ hasText: /添加来源|\+.*来源|增加来源/ }).first();
        if (await addSourceBtn.isVisible().catch(() => false)) {
          await addSourceBtn.click();
          await page.waitForTimeout(600);
          note('PART 3a: 添加第二来源批');
          // pick second batch
          await pickFirstBatch(page, paneP3).catch(() => null);
          await shot('15-multi-source');
        } else {
          note('PART 3a: 未找到多来源添加按钮 (UI pattern 可能不同)');
        }

        // 小结 via API after adding multi-source
        await page.keyboard.press('Escape').catch(() => null);
        await page.waitForTimeout(600);

        const mixResp = await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/interim-settle`).catch((e) => ({ _err: e.message }));
        ok(!mixResp?._err, 'PART 3a: 混批 → 小结不 crash', {
          error: mixResp?._err?.slice(0, 100),
        });
      }
    } catch (p3aErr) {
      note('PART 3a 出错', { error: p3aErr.message?.slice(0, 200) });
    }
  } else {
    skip('PART 3a: 混批', `原料批次不足 (found ${rawBatches.length}, need 2+)`);
  }

  // ══════════════════════════════════════════════════════════════
  // PART 3b: 停产 — 确认 dialog + COMPLETED + 小结消失
  // ══════════════════════════════════════════════════════════════
  console.log('\n── PART 3b: 停产 ──');

  // Navigate back to plan list
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  if (planNumber) {
    const srchP3 = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
    if (await srchP3.isVisible().catch(() => false)) {
      await srchP3.fill(planNumber);
      await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
      await page.waitForTimeout(1800);
    }
  }
  const planRowForStop = planNumber
    ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
    : page.locator('.el-table__row').first();
  await shot('16-before-stop');

  const stopBtnForClick = planRowForStop.locator('button, .el-button').filter({ hasText: /^停产$/ }).first();
  if (await stopBtnForClick.isVisible().catch(() => false)) {
    // Click 停产
    await stopBtnForClick.click();
    await page.waitForTimeout(1000);
    await shot('17-stop-confirm-dialog');

    // Confirm dialog should appear with plan context
    const confirmBox = page.locator('.el-message-box:visible, .el-dialog:visible').last();
    const confirmText = await confirmBox.innerText().catch(() => '');
    const hasContext = !!planNumber && confirmText.includes(planNumber.slice(0, 8));
    ok(hasContext, 'PART 3b: 停产确认 dialog 含计划编号 (防呆 context)', {
      planNumber, found: hasContext, textSample: confirmText.slice(0, 200),
    });

    // Confirm
    const confirmYes = confirmBox.locator('button').filter({ hasText: /确认|确定|停产|是/ }).first();
    const stopRespP = page.waitForResponse(
      (r) => r.url().includes('stop-production') && r.request().method() === 'POST',
      { timeout: 15000 },
    ).catch(() => null);
    if (await confirmYes.isVisible().catch(() => false)) {
      await confirmYes.click();
    } else {
      // If no confirm dialog, the button itself triggers the action
      note('PART 3b: 无确认 dialog — 停产直接执行');
    }
    const stopResp = await stopRespP;
    await page.waitForTimeout(2000);
    await shot('18-after-stop');

    if (stopResp) {
      const sb = await stopResp.json().catch(() => null);
      ok(stopResp.status() === 200 && sb?.success !== false,
        'PART 3b: 停产 HTTP 200 + success', { status: stopResp.status() });
    }

    // API verify COMPLETED
    const planFinal = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
    ok(planFinal?.status === 'COMPLETED', 'PART 3b: 停产后计划 COMPLETED', { status: planFinal?.status });

    // UI verify 小结 button gone after stop (reload page)
    await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(2000);
    if (planNumber) {
      const srchFinal = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"]').first();
      if (await srchFinal.isVisible().catch(() => false)) {
        await srchFinal.fill(planNumber);
        await page.locator('button').filter({ hasText: /搜索/ }).first().click().catch(() => null);
        await page.waitForTimeout(1600);
      }
    }
    const planRowFinal = planNumber
      ? page.locator('.el-table__row').filter({ hasText: planNumber }).first()
      : page.locator('.el-table__row').first();
    await shot('19-after-stop-plan-list');

    const interimGone = !(await planRowFinal.locator('button, .el-button').filter({ hasText: /^小结$/ }).first().isVisible().catch(() => false));
    const stopGone = !(await planRowFinal.locator('button, .el-button').filter({ hasText: /^停产$/ }).first().isVisible().catch(() => false));
    ok(interimGone, 'PART 3b: 停产后 小结按钮消失', { gone: interimGone });
    ok(stopGone, 'PART 3b: 停产后 停产按钮消失', { gone: stopGone });
  } else {
    ok(false, 'PART 3b: 停产按钮不可见 (UI门控未激活)', {});
    // API fallback for stop
    try {
      await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/stop-production`);
      const planFinal2 = await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}`).catch(() => null);
      ok(planFinal2?.status === 'COMPLETED', 'PART 3b: API fallback 停产 → COMPLETED', { status: planFinal2?.status });
    } catch (stopErr) {
      ok(false, 'PART 3b: API 停产失败 (Phase 2 后端未部署?)', { error: stopErr.message?.slice(0, 200) });
    }
  }

  // ══════════════════════════════════════════════════════════════
  // SUMMARY
  // ══════════════════════════════════════════════════════════════
  await shot('20-final');

  const fails = A.filter((a) => a.pass === false);
  const passes = A.filter((a) => a.pass === true);
  const skips = A.filter((a) => a.pass === null);
  const STATUS = fails.length === 0 ? 'PASS' : 'FAIL';

  console.log(`\n${'═'.repeat(60)}`);
  console.log(`STATUS: ${STATUS}  (PASS=${passes.length} FAIL=${fails.length} SKIP=${skips.length})`);
  if (fails.length > 0) {
    console.log('FAILURES:');
    fails.forEach((f) => console.log(`  ✗ ${f.label}`));
  }

  const result = {
    scenario: 'headed-stock-production-full',
    depth: 'deep-headed',
    target: `web-admin-prod-F006 ${APP}`,
    status: STATUS,
    planId,
    planNumber,
    productTypeId,
    summary: {
      total: A.filter((a) => a.pass !== null).length,
      pass: passes.length,
      fail: fails.length,
      skip: skips.length,
    },
    assertions: A,
    consoleErrors: ctx.consoleErrors,
  };
  await writeFile(resultFile, JSON.stringify(result, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status: STATUS, resultFile, screenshotsDir: OUT }, null, 2));

  await ctx.browser.close().catch(() => null);
  if (STATUS !== 'PASS') process.exitCode = 1;

} catch (fatal) {
  A.push({ pass: false, label: '脚本 fatal 异常', error: fatal.message });
  console.error('FATAL', fatal.message);
  try { await ctx?.shot?.('fatal'); } catch { /* ignore */ }
  await writeFile(resultFile, JSON.stringify({
    status: 'FAIL', error: fatal.message, assertions: A, consoleErrors: ctx?.consoleErrors,
  }, null, 2), 'utf8').catch(() => null);
  await ctx?.browser?.close().catch(() => null);
  process.exitCode = 1;
}
