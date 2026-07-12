/**
 * Headed E2E: product process Workflow -> clerk 过程单 (process-sheet) -> real inventory (2B).
 *
 * Design doc: docs/superpowers/plans/2026-07-11-product-process-workflow-runtime-2b-clerk-implementation.md
 * §Testing "Headed web-admin E2E (F006) — the acceptance", steps 1-5 (step 6, the multi-output
 * activation-guard rejection, is covered by backend unit tests per that doc and is NOT
 * re-verified here — this script proves the happy-path integration end to end in the browser).
 *
 * Prerequisite: run tests/e2e-yield-mixed-sku/workflow-clerk-setup.mjs first (creates the
 * WorkProcess catalog rows, raw material + batch, 3 ProductTypes, the workflow definition
 * [published + activated], a SAFETY_STOCK plan, and 转批次's it — see that file's header for
 * the exact endpoints). This script reads that setup's JSON summary (env WF_SETUP_FILE, or
 * auto-discovers the newest .playwright-mcp/workflow-clerk-setup-*.json).
 *
 * What this proves, tied to the design doc's acceptance:
 *   3. Open 过程单 -> the 3 workflow processes appear as tabs with the planned-output banner
 *      (.sp-workflow-banner, F2's fool-proof Rule 2/3 read-only "计划产出" display).
 *   4. Fill a row per process (raw batch+qty -> semi1; feed semi1 -> semi2; feed semi2 ->
 *      finished) and save each — asserts NO 409 WORKFLOW_ROW_OUTPUT_KIND_MISMATCH /
 *      _UNIT_MISMATCH (the exact regression the F1/F2 fixes in the design doc's adversarial
 *      review resolved — see that doc's "F1"/"F2" rows).
 *   -   After the 3 saves: GET .../production-batches/{batchId}/workflow-runtime -> all 3
 *       WorkProcessTasks COMPLETED (proves B3's F3 task-stamp fix).
 *   5. 小结 (interim-settle) -> asserts real MaterialBatch.usedQuantity increased on the raw
 *      batch, and that SemiFinishedInventory / FinishedGoodsBatch rows exist with available
 *      quantity for the intermediate + finished products (real inventory moved, not mocked).
 *
 * ⚠️ Written without a live run against this branch — every selector below is best-effort,
 * derived by reading ProcessSheet.vue / ProcessDataTable.vue / PROCESS_SHEET_CONFIG.ts and
 * mirroring proven patterns from sibling scripts in this directory (headed-config-to-production.mjs,
 * headed-crossplan-wip.mjs, headed-matrix-fullchain.mjs). See the README's "watch for" section
 * for the specific spots most likely to need a small fix.
 *
 * Follows .claude/rules/playwright-headed-mode.md: headless:false, zh-CN, 1920x1080, video —
 * all inherited from _headed-helpers.mjs's startHeaded().
 *
 * Env:
 *   E2E_ADMIN_URL, E2E_FACTORY_ID, E2E_USERNAME, E2E_PASSWORD  — same as workflow-clerk-setup.mjs
 *   WF_SETUP_FILE   optional — path to a specific workflow-clerk-setup-*.json. Default:
 *                    newest file matching that glob under .playwright-mcp/.
 *   PLAYWRIGHT_PORT optional — per playwright-headed-mode.md multi-chat convention.
 */
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { startHeaded, APP, FACTORY, arr, num } from './_headed-helpers.mjs';

const OUT = path.resolve(`.playwright-mcp/workflow-clerk-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-workflow-clerk-result.json');

const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
  return !!pass;
};
const note = (label, data = {}) => {
  assertions.push({ pass: null, label: `[note] ${label}`, ...data });
  console.log(`[note] ${label}${Object.keys(data).length ? '  ' + JSON.stringify(data) : ''}`);
};

// -----------------------------------------------------------------------
// Load the setup summary produced by workflow-clerk-setup.mjs
// -----------------------------------------------------------------------
async function loadSetupSummary() {
  const explicit = process.env.WF_SETUP_FILE;
  if (explicit) {
    return JSON.parse(await readFile(explicit, 'utf8'));
  }
  const dir = path.resolve('.playwright-mcp');
  let files;
  try {
    files = (await readdir(dir)).filter((f) => /^workflow-clerk-setup-.*\.json$/.test(f));
  } catch {
    files = [];
  }
  if (files.length === 0) {
    throw new Error(
      'No workflow-clerk-setup-*.json found under .playwright-mcp/, and WF_SETUP_FILE not set. '
      + 'Run tests/e2e-yield-mixed-sku/workflow-clerk-setup.mjs first.',
    );
  }
  files.sort(); // timestamp-suffixed base36, lexicographic sort ~= chronological
  const newest = path.join(dir, files[files.length - 1]);
  return JSON.parse(await readFile(newest, 'utf8'));
}

const setup = await loadSetupSummary();
console.log(`Loaded setup summary: ts=${setup.ts} plan=${setup.plan?.planNumber} batchId=${setup.batch?.batchId}`);
console.log(`Processes: ${(setup.processes || []).map((p) => `${p.processOrder}:${p.processName}`).join(' -> ')}`);

const ctx = await startHeaded(OUT);
const { page, api, shot, helpers, consoleErrors } = ctx;
const { selectByText, fillNum, waitSaved } = helpers;
const drawer = () => page.locator('.el-drawer__body');
const activePane = () => drawer().locator('.el-tab-pane:visible').first();

async function gotoTab(name) {
  const tab = drawer().locator('.el-tabs__item').filter({ hasText: name }).first();
  const visible = await tab.isVisible().catch(() => false);
  if (!visible) return false;
  await tab.click();
  await page.waitForTimeout(1300);
  return true;
}

async function ycByOrder(planId, order) {
  const cards = arr(await api(
    'GET',
    `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/process-sheet/inventory/yield-card`,
  ).catch(() => []));
  return cards.filter((c) => Number(c.processOrder) === Number(order));
}

try {
  const planId = String(setup.plan.planId);
  const planNumber = String(setup.plan.planNumber);
  const [proc1, proc2, proc3] = setup.processes;
  ok(!!proc1 && !!proc2 && !!proc3, '设置摘要含 3 道 workflow 工序', {
    names: setup.processes?.map((p) => p.processName),
  });
  if (!proc1 || !proc2 || !proc3) throw new Error('setup summary missing processes[0..2]');

  // -----------------------------------------------------------------------
  // Force grid view (ProcessSheet.vue defaults to card view — 'sp-f-process-sheet-view'
  // localStorage key unset -> 'card'; this script uses the well-tested `table.sp-grid`
  // selectors that sibling scripts in this dir rely on). Set before opening the drawer.
  // -----------------------------------------------------------------------
  await page.evaluate(() => localStorage.setItem('sp-f-process-sheet-view', 'grid'));

  // -----------------------------------------------------------------------
  // Step 2/3: navigate to the plan, open 逐道录入 (过程单) drawer.
  // -----------------------------------------------------------------------
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  // Poll for the plan row (default sort is newest-first so it is on page 1; the status
  // badges + action buttons hydrate async, so wait for the row to actually carry a
  // 逐道录入 button before asserting). No search needed.
  let planRowFound = false;
  for (let i = 0; i < 20 && !planRowFound; i++) {
    await page.waitForTimeout(1500);
    planRowFound = await page.evaluate((pn) => {
      const rows = [...document.querySelectorAll('.el-table__row')];
      const row = rows.find((r) => r.textContent.replace(/\s+/g, '').includes(pn.replace(/\s+/g, '')));
      if (!row) return false;
      const btn = [...row.querySelectorAll('button,.el-button,a')]
        .find((b) => b.textContent.trim().includes('逐道录入'));
      return !!btn;
    }, planNumber).catch(() => false);
  }
  ok(planRowFound, '计划行在列表中可见', { planNumber });
  if (!planRowFound) throw new Error(`plan row not visible for ${planNumber} — check search/plan creation`);

  await page.evaluate((pn) => {
    const rows = [...document.querySelectorAll('.el-table__row')];
    const row = rows.find((r) => r.textContent.replace(/\s+/g, '').includes(pn.replace(/\s+/g, '')));
    const btn = [...row.querySelectorAll('button,.el-button,a')]
      .find((b) => b.textContent.trim().includes('逐道录入'));
    btn.click();
  }, planNumber);
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
  await shot('drawer-opened');

  // -----------------------------------------------------------------------
  // Acceptance step 3: the workflow processes appear as tabs with planned-output banner.
  // -----------------------------------------------------------------------
  for (const proc of [proc1, proc2, proc3]) {
    const opened = await gotoTab(proc.processName);
    ok(opened, `工序 tab 可见: ${proc.processName}`, { processOrder: proc.processOrder });
    if (!opened) continue;
    const pane = activePane();
    const banner = pane.locator('.sp-workflow-banner').first();
    const bannerVisible = await banner.isVisible().catch(() => false);
    ok(bannerVisible, `${proc.processName}: workflow 计划产出 banner 可见 (F2)`, {});
    if (bannerVisible) {
      const text = await banner.innerText().catch(() => '');
      ok(/计划产出/.test(text), `${proc.processName}: banner 含"计划产出"文案`, { text: text.slice(0, 120) });
      const expectFinished = proc.output?.finished === true;
      const tagOk = expectFinished ? /成品/.test(text) : /半成品/.test(text);
      ok(tagOk, `${proc.processName}: banner 类型标签正确 (${expectFinished ? '成品' : '半成品'})`, {
        text: text.slice(0, 120),
      });
    }
  }
  await shot('tabs-and-banners');

  // -----------------------------------------------------------------------
  // Acceptance step 4a: 首道 (raw intake / xiuyou archetype) — raw batch + outWeight -> output.
  // -----------------------------------------------------------------------
  await gotoTab(proc1.processName);
  await page.waitForTimeout(700);
  {
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(800);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await selectByText(row.locator('.el-select').first(), setup.rawMaterial.batch.batchNumber);
    const nums = row.locator('.el-input-number');
    // nums[0]=出库重量(kg) (outWeight -> rawInput.quantity), nums[1]=产出数量(kg) (output -> outputQuantity)
    await fillNum(nums.nth(0), 1.0);
    await fillNum(nums.nth(1), 0.9);
    await shot('proc1-row-filled');
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const saved = await waitSaved();
    const noRegression = !/WORKFLOW_ROW_OUTPUT_KIND_MISMATCH|WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH|409/.test(saved.toast || '');
    ok(saved.saved, `${proc1.processName}: 首道保存成功`, { toast: saved.toast });
    ok(noRegression, `${proc1.processName}: 无 409 workflow kind/unit mismatch (F1/F2 回归检查)`, { toast: saved.toast });
    await page.waitForTimeout(1400);
  }

  const proc1Cards = await ycByOrder(planId, proc1.processOrder);
  const semi1Wip = proc1Cards[0];
  ok(!!semi1Wip?.batchNumber, `${proc1.processName}: 产出 WIP 批次可查`, { batchNumber: semi1Wip?.batchNumber, remaining: semi1Wip?.remaining });
  if (!semi1Wip?.batchNumber) throw new Error('proc1 did not produce a locatable WIP batch — cannot continue to proc2');

  // -----------------------------------------------------------------------
  // Acceptance step 4b: 卤制 (single upstream, before/after archetype) — feed part of semi1,
  // deliberately leave a leftover buffer so post-settle SemiFinishedInventory assertions have
  // something available (not fully consumed downstream).
  // -----------------------------------------------------------------------
  const semi1Remaining = num(semi1Wip.remaining) ?? 0.9;
  const proc2Feed = Math.max(0.1, Math.min(0.5, semi1Remaining * 0.6));
  const proc2Out = Math.round(proc2Feed * 0.9 * 100) / 100;

  await gotoTab(proc2.processName);
  await page.waitForTimeout(700);
  {
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(800);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await selectByText(row.locator('.el-select').first(), semi1Wip.batchNumber);
    const nums = row.locator('.el-input-number');
    // nums[0]=投入(kg) (before -> inputQuantity), nums[1]=产出(kg) (after -> outputQuantity)
    await fillNum(nums.nth(0), proc2Feed);
    await fillNum(nums.nth(1), proc2Out);
    await shot('proc2-row-filled');
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const saved = await waitSaved();
    const noRegression = !/WORKFLOW_ROW_OUTPUT_KIND_MISMATCH|WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH|409/.test(saved.toast || '');
    ok(saved.saved, `${proc2.processName}: 二道(单上游)保存成功`, { toast: saved.toast, feed: proc2Feed, out: proc2Out });
    ok(noRegression, `${proc2.processName}: 无 409 workflow kind/unit mismatch (F1/F2 回归检查)`, { toast: saved.toast });
    await page.waitForTimeout(1400);
  }

  const proc2Cards = await ycByOrder(planId, proc2.processOrder);
  const semi2Wip = proc2Cards[0];
  ok(!!semi2Wip?.batchNumber, `${proc2.processName}: 产出 WIP 批次可查`, { batchNumber: semi2Wip?.batchNumber, remaining: semi2Wip?.remaining });
  if (!semi2Wip?.batchNumber) throw new Error('proc2 did not produce a locatable WIP batch — cannot continue to proc3');

  // -----------------------------------------------------------------------
  // Acceptance step 4c: 包装 (finishing / qidiao archetype, forced because
  // workflowContext.output.finished===true — see ProcessSheet.vue mapWorkflowProcesses).
  // Minimum fields for save to be enabled: usedWeight != null AND
  // calcSumBoxes(row) = storage+sample+remainBox+claim > 0 — filling storage alone suffices.
  // -----------------------------------------------------------------------
  const semi2Remaining = num(semi2Wip.remaining) ?? proc2Out;
  const proc3UsedWeight = Math.max(0.05, Math.min(0.3, semi2Remaining * 0.6));

  await gotoTab(proc3.processName);
  await page.waitForTimeout(700);
  {
    const pane = activePane();
    await pane.locator('button').filter({ hasText: /新增行/ }).first().click().catch(() => null);
    await page.waitForTimeout(800);
    const row = pane.locator('table.sp-grid tbody tr.sp-tr').last();
    await selectByText(row.locator('.el-select').first(), semi2Wip.batchNumber);
    const nums = row.locator('.el-input-number');
    // Column order for the qidiao archetype (PROCESS_SHEET_CONFIG.ts):
    // 0=storage(入库盒) 1=sample(留样盒) 2=remainBox(剩余盒) 3=claim(领用盒)
    // 4=productWeight(成品重kg) 5=trimmings(料头kg) 6=usedWeight(使用重量kg)
    // 7=boxWeight(单盒克重g) 8=workerPrice(工时单价元/h)
    await fillNum(nums.nth(0), 5); // storage -> actualProd (盒数) = outputQuantity
    await fillNum(nums.nth(6), proc3UsedWeight); // usedWeight -> inputQuantity
    await shot('proc3-row-filled');
    await row.locator('button').filter({ hasText: '保存' }).first().click();
    const saved = await waitSaved();
    const noRegression = !/WORKFLOW_ROW_OUTPUT_KIND_MISMATCH|WORKFLOW_ROW_OUTPUT_UNIT_MISMATCH|409/.test(saved.toast || '');
    ok(saved.saved, `${proc3.processName}: 成品道(单上游)保存成功`, { toast: saved.toast, usedWeight: proc3UsedWeight, storage: 5 });
    ok(noRegression, `${proc3.processName}: 无 409 workflow kind/unit mismatch (F1/F2 回归检查) — 这是设计文档 F1/F2 修复要解决的确切 bug`, { toast: saved.toast });
    await page.waitForTimeout(1500);
  }

  // -----------------------------------------------------------------------
  // F3 task-stamp check: workflow-runtime should show all 3 tasks COMPLETED now.
  // -----------------------------------------------------------------------
  const batchId = setup.batch.batchId;
  const runtime = await api('GET', `/${FACTORY}/production-batches/${encodeURIComponent(batchId)}/workflow-runtime`).catch((e) => {
    note('workflow-runtime 查询失败', { error: e.message });
    return null;
  });
  const tasks = arr(runtime?.tasks);
  const completedCount = tasks.filter((t) => String(t?.task?.status) === 'COMPLETED').length;
  ok(tasks.length === 3, 'workflow-runtime 返回 3 个工序任务', { count: tasks.length });
  ok(completedCount === 3, `所有 3 个 WorkProcessTask 状态 COMPLETED (F3 回写)`, {
    statuses: tasks.map((t) => t?.task?.status),
  });

  // -----------------------------------------------------------------------
  // Close the drawer before triggering 小结 (the button lives on the plan list row, not
  // inside the drawer).
  // -----------------------------------------------------------------------
  await page.locator('.el-drawer__close-btn, .el-drawer__headerbtn').first().click().catch(() => null);
  await page.waitForTimeout(1000);

  // -----------------------------------------------------------------------
  // Acceptance step 5: 小结 (interim-settle) -> real inventory drawdown/creation.
  // -----------------------------------------------------------------------
  const planRow2 = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  const settleBtn = planRow2.locator('button, .el-button').filter({ hasText: /^小结$/ }).first();
  const settleBtnVisible = await settleBtn.isVisible().catch(() => false);
  ok(settleBtnVisible, '计划行"小结"按钮可见 (SAFETY_STOCK)', {});
  if (settleBtnVisible) {
    await settleBtn.click();
    await page.waitForTimeout(600);
    const confirmBtn = page.locator('.el-message-box button').filter({ hasText: /确认小结/ }).first();
    if (await confirmBtn.isVisible().catch(() => false)) {
      await confirmBtn.click();
    }
    let settleToast = '';
    try {
      const el = await page.waitForSelector('.el-message--success, .el-message--warning, .el-message--error', { timeout: 12000 });
      settleToast = await el.innerText().catch(() => '');
    } catch { /* handled by ok() below */ }
    ok(/已小结|成功/.test(settleToast) && !/失败|错误/.test(settleToast), '小结成功 toast', { toast: settleToast });
    await shot('after-interim-settle');
    await page.waitForTimeout(2000);
  } else {
    note('跳过小结相关断言 (按钮未找到 — 见 README 手动回退)');
  }

  // -----------------------------------------------------------------------
  // Real-inventory assertions (via API, robust to any UI copy drift).
  // -----------------------------------------------------------------------
  const rawBatchId = setup.rawMaterial.batch.id;
  const rawAfter = await api('GET', `/${FACTORY}/material-batches/${encodeURIComponent(rawBatchId)}`).catch((e) => {
    note('raw MaterialBatch 回读失败', { error: e.message });
    return null;
  });
  if (rawAfter) {
    const usedQty = num(rawAfter.usedQuantity) ?? 0;
    ok(usedQty > 0, '原料 MaterialBatch.usedQuantity 增加 (真实扣减, 非 mock)', {
      usedQuantity: rawAfter.usedQuantity, status: rawAfter.status,
    });
  }

  const semi1Inv = arr(await api(
    'GET',
    `/${FACTORY}/semi-finished/inventory?productTypeId=${encodeURIComponent(setup.productTypes.semi1.id)}`,
  ).catch(() => []));
  const semi1Available = semi1Inv.reduce((s, x) => s + (num(x.availableQuantity) ?? 0), 0);
  ok(semi1Inv.length > 0, '半成品一 (semi1) 在 SemiFinishedInventory 中有记录', { rows: semi1Inv.length, totalAvailable: semi1Available });

  const semi2Inv = arr(await api(
    'GET',
    `/${FACTORY}/semi-finished/inventory?productTypeId=${encodeURIComponent(setup.productTypes.semi2.id)}`,
  ).catch(() => []));
  const semi2Available = semi2Inv.reduce((s, x) => s + (num(x.availableQuantity) ?? 0), 0);
  ok(semi2Inv.length > 0, '半成品二 (semi2) 在 SemiFinishedInventory 中有记录', { rows: semi2Inv.length, totalAvailable: semi2Available });

  const fgInv = arr(await api(
    'GET',
    `/${FACTORY}/finished-goods/inventory?productTypeId=${encodeURIComponent(setup.productTypes.finished.id)}`,
  ).catch(() => []));
  const fgAvailable = fgInv.reduce((s, x) => s + (num(x.availableQuantity) ?? 0), 0);
  ok(fgInv.length > 0 && fgAvailable > 0, '成品在 FinishedGoodsBatch/inventory 中有可用库存', { rows: fgInv.length, totalAvailable: fgAvailable });

  note(
    'MaterialConsumption/BatchLineageEdge lineage 未直接断言 — 未找到面向此的读端点; '
    + '上面的 usedQuantity/SFI/FG 数量增量 + workflow-runtime 任务 COMPLETED 是可观测的代理证据',
  );

  const fails = assertions.filter((a) => a.pass === false);
  const status = fails.length === 0 ? 'PASS' : (fails.length <= 2 ? 'PARTIAL' : 'FAIL');
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-workflow-clerk',
    depth: 'deep',
    target: `${APP} (${FACTORY})`,
    status,
    setupSummaryTs: setup.ts,
    planNumber,
    batchId,
    assertions,
    consoleErrors,
  }, null, 2), 'utf8').catch(() => null);
  console.log(`\n${JSON.stringify({ status, total: assertions.length, pass: assertions.filter((a) => a.pass).length, failures: fails.map((f) => f.label) }, null, 2)}`);
  console.log(`Result written to: ${resultFile}`);
  if (status !== 'PASS') process.exitCode = 1;
} catch (error) {
  ok(false, 'headed-workflow-clerk 脚本异常', { error: error.message });
  await shot('error').catch(() => null);
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-workflow-clerk', status: 'FAIL', error: error.message, stack: error.stack, assertions, consoleErrors,
  }, null, 2), 'utf8').catch(() => null);
  console.error('ERROR:', error.message, error.stack);
  process.exitCode = 1;
} finally {
  await ctx.browser.close().catch(() => null);
}
