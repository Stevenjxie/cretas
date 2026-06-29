import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  APP, FACTORY, COUNT_UNIT_RE, WEIGHT_UNIT_RE, arr, num, today,
  startHeaded, setupSkuAndBom,
} from './_headed-helpers.mjs';

/**
 * Verify-first conclusions for this test:
 *
 * 1. SEASONING cost category lands on StepEntry.processCategory through the
 *    production process chain, not through the visible process category label.
 *    ProductWorkProcess.defaultCostCategory=SEASONING is copied from the real
 *    F006 template "叮咚好食光轻卤门腔（猪舌）120g". ProcessSheet role mode maps
 *    that step to processCode=shuzhi; ProcessDataTable sends seasoningStep=true;
 *    ProcessSheetServiceImpl then stores StepEntry.processCategory="SEASONING".
 *
 * 2. BOM seasoning timing is DRAFT-only for writes:
 *    POST /bom/recipes creates DRAFT + isCurrent=true, then PUT /seasoning is
 *    allowed while DRAFT, then POST /activate makes it ACTIVE + isCurrent=true.
 *    computeSeasoningCost reads the current BOM recipe and bom_seasoning_items.
 *
 * 3. F006 real customer products checked before this task, including 叮咚猪舌 /
 *    卤猪蹄 variants, had zero current seasoning rows, so this test must create
 *    a fresh SKU and configure real seasoning rows before reporting.
 */

const OUT = path.resolve(`.playwright-mcp/seasoning-cost-${new Date().toISOString().replace(/[:.]/g, '-')}`);
const resultFile = path.join(OUT, 'headed-seasoning-cost-result.json');

const COOKING_DOSAGE_G_PER_KG = 100;
const COOKING_PRICE_1 = 20;
const COOKING_PRICE_2 = 18;
const SUBSEQUENT_POT_RATIO = 0.5;
const SHUZHI_FEED_KG = 0.6;
const SHUZHI_OUTPUT_KG = 0.5;

const assertions = [];
const ok = (pass, label, data = {}) => {
  assertions.push({ pass: !!pass, label, ...data });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${label} ${Object.keys(data).length ? JSON.stringify(data) : ''}`);
};
const approx = (actual, expected, tolerance, label, data = {}) => {
  const pass = actual != null && Math.abs(Number(actual) - Number(expected)) <= tolerance;
  ok(pass, label, { actual, expected, tolerance, ...data });
};

const ctx = await startHeaded(OUT);
try {
  const { page, api, shot, helpers } = ctx;

  const setup = await setupSkuAndBom(page, {
    namePrefix: 'HT-SEA',
    api,
    shot,
    minProcesses: 6,
    createRecipeForProcessEntry: false,
  });

  const processes = setup.processes
    .filter((p) => p.isActive !== false)
    .sort((a, b) => Number(a.processOrder || 0) - Number(b.processOrder || 0));
  const shuzhi = processes.find((p) => String(p.defaultCostCategory || '').toUpperCase() === 'SEASONING'
    || /熟制|卤制/.test(String(p.processName || '')));
  ok(!!shuzhi, 'new SKU copied a SEASONING/shuzhi process', {
    product: setup.name,
    template: setup.processTemplate?.name,
    processes: processes.map((p) => ({
      order: p.processOrder,
      name: p.processName,
      defaultCostCategory: p.defaultCostCategory,
      processCategory: p.processCategory,
    })),
  });
  if (!shuzhi) throw new Error('missing SEASONING process on copied SKU');

  const shuzhiIndex = processes.findIndex((p) => String(p.id) === String(shuzhi.id));
  if (shuzhiIndex < 1) throw new Error(`SEASONING process order is not reachable: ${shuzhi.processName}`);
  const chainToShuzhi = processes.slice(0, shuzhiIndex + 1);

  const draftRecipe = await createDraftBomRecipe(api, setup);
  ok(!!draftRecipe?.id, 'created DRAFT BOM recipe for seasoning write', {
    recipeId: draftRecipe?.id,
    status: draftRecipe?.status,
    isCurrent: draftRecipe?.isCurrent,
  });

  await fillSeasoningRecipeHeaded(page, helpers, shot, setup.productTypeId);
  let seasoningReadback = await api('GET', `/${FACTORY}/bom/recipes/by-product/${encodeURIComponent(setup.productTypeId)}/seasoning`);
  const cookingRows = arr(seasoningReadback.seasoningItems).filter((i) => i.section === 'COOKING');
  ok(cookingRows.length >= 1, 'seasoning UI save persisted COOKING row by API readback', {
    recipeId: seasoningReadback.bomRecipeId,
    status: seasoningReadback.status,
    cookingRows,
  });

  const activatedRecipe = await api('POST', `/${FACTORY}/bom/recipes/${encodeURIComponent(draftRecipe.id)}/activate`);
  seasoningReadback = await api('GET', `/${FACTORY}/bom/recipes/by-product/${encodeURIComponent(setup.productTypeId)}/seasoning`);
  ok(String(seasoningReadback.status) === 'ACTIVE', 'activated seasoning BOM recipe after DRAFT-only PUT', {
    activatedRecipeId: activatedRecipe?.id,
    readbackStatus: seasoningReadback.status,
    isCurrent: seasoningReadback.isCurrent,
  });

  const plan = await createStartedPlan(api, setup.productTypeId);
  ok(!!plan.planId, 'API prereq created and started production plan', plan);

  await openProcessDrawer(page, plan.planNumber);
  await shot('seasoning-drawer-open');
  const rawBatch = await findRawBatch(api, setup.rawMaterial);
  ok(!!rawBatch?.batchNumber, 'found weight-unit raw batch, excluding count-unit packaging', {
    batchNumber: rawBatch?.batchNumber,
    unit: rawBatch?.quantityUnit || rawBatch?.unit,
    material: rawBatch?.materialName || rawBatch?.materialTypeName,
  });
  if (!rawBatch?.batchNumber) throw new Error('missing eligible raw batch');

  const plannedRows = buildChainQuantities(chainToShuzhi);
  let upstreamBatchNumber = null;
  for (let i = 0; i < chainToShuzhi.length; i += 1) {
    const process = chainToShuzhi[i];
    if (i === 0) {
      await saveFirstProcessRow(page, helpers, process, rawBatch, plannedRows[i].input, plannedRows[i].output);
    } else if (String(process.id) === String(shuzhi.id)) {
      const save = await saveShuzhiRow(page, helpers, process, upstreamBatchNumber, SHUZHI_FEED_KG, SHUZHI_OUTPUT_KG);
      ok(!/未设置调料配方|调料成本暂记\s*0|调料成本暂记0/.test(save.toast || ''), 'shuzhi save toast proves seasoning config branch was used', {
        toast: save.toast,
      });
    } else {
      await saveSingleUpstreamRow(page, helpers, process, upstreamBatchNumber, plannedRows[i].input, plannedRows[i].output);
    }
    await shot(`seasoning-row-${i + 1}`);
    const cards = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(plan.planId)}/process-sheet/inventory/yield-card`));
    const card = cards.find((c) => Number(c.processOrder) === Number(process.processOrder));
    if (!card?.batchNumber) throw new Error(`no yield-card batch after saving ${process.processName}`);
    upstreamBatchNumber = card.batchNumber;
  }

  const yieldCard = arr(await api('GET', `/${FACTORY}/production-plans/${encodeURIComponent(plan.planId)}/process-sheet/inventory/yield-card`));
  const shuzhiCard = yieldCard.find((c) => Number(c.processOrder) === Number(shuzhi.processOrder));
  ok(!!shuzhiCard?.batchNumber, 'plan-scoped yield-card contains shuzhi batch', {
    batchNumber: shuzhiCard?.batchNumber,
    addedCost: shuzhiCard?.addedCost,
    inheritedCost: shuzhiCard?.inheritedCost,
    rowTotalCost: shuzhiCard?.rowTotalCost,
  });

  const oracle = seasoningOracle({
    inputKg: SHUZHI_FEED_KG,
    dosageGPerKg: COOKING_DOSAGE_G_PER_KG,
    price1: COOKING_PRICE_1,
    price2: COOKING_PRICE_2,
  });
  const actualSeasoningCost = round2(num(shuzhiCard?.addedCost));
  approx(actualSeasoningCost, oracle.totalScale2, 0.01, 'seasoning cost truth: yield-card addedCost == RecipeCostCalculator oracle', {
    batchNumber: shuzhiCard?.batchNumber,
    oracle,
  });

  let costAnalysis = null;
  if (shuzhiCard?.batchId || shuzhiCard?.id) {
    const batchId = shuzhiCard.batchId || shuzhiCard.id;
    costAnalysis = await api('GET', `/${FACTORY}/processing/batches/${encodeURIComponent(batchId)}/cost-analysis`).catch(() => null);
  }

  const rendered = await page.locator('.el-drawer__body').innerText().catch(() => '');
  ok(rendered.includes(String(shuzhi.processName || '')), 'DOM rendered shuzhi process tab/content', {
    processName: shuzhi.processName,
  });

  await finish('PASS', {
    setup,
    research: {
      seasoningCategoryPath: 'ProductWorkProcess.defaultCostCategory=SEASONING -> ProcessSheet shuzhi -> seasoningStep=true -> StepEntry.processCategory=SEASONING',
      recipeTiming: 'create DRAFT/isCurrent -> PUT seasoning while DRAFT -> activate ACTIVE/isCurrent',
      f006CustomerDataNote: 'Observed real F006 customer SKUs have no current seasoning rows; production seasoning costs are likely not counted until data is configured.',
    },
    recipe: seasoningReadback,
    plan,
    shuzhi: {
      process: shuzhi,
      card: shuzhiCard,
      costAnalysis,
    },
    oracle,
    yieldCard,
  });
} catch (error) {
  ok(false, 'seasoning cost script exception', { error: error.message });
  await ctx.shot('seasoning-error').catch(() => null);
  await finish('FAIL', { error: error.message, stack: error.stack });
}

async function finish(forcedStatus, extra = {}) {
  const failures = assertions.filter((a) => !a.pass);
  const status = forcedStatus === 'PASS' && failures.length === 0 ? 'PASS' : 'FAIL';
  await writeFile(resultFile, JSON.stringify({
    scenario: 'headed-seasoning-cost',
    depth: 'deep',
    target: 'web-admin-prod-F006',
    status,
    assertions,
    consoleErrors: ctx.consoleErrors,
    ...extra,
  }, null, 2), 'utf8').catch(() => null);
  console.log(JSON.stringify({ status, resultFile, failures: failures.map((f) => f.label) }, null, 2));
  await ctx.browser.close().catch(() => null);
  if (status !== 'PASS') process.exitCode = 1;
}

async function createDraftBomRecipe(api, setup) {
  const items = arr(setup.bomItems);
  if (!items.length) throw new Error('cannot create recipe without bom_items');
  return await api('POST', `/${FACTORY}/bom/recipes`, {
    productTypeId: setup.productTypeId,
    productName: setup.product?.name || setup.name,
    overallYieldRate: 100,
    outputQuantityPerUnit: 1,
    outputUnit: setup.product?.unit || 'pcs',
    sourceType: 'MANUAL',
    notes: 'headed seasoning cost DRAFT recipe',
    items: items.map((item, idx) => ({
      materialTypeId: item.materialTypeId,
      standardQuantity: Number(item.standardQuantity ?? 0),
      yieldRate: item.yieldRate == null ? null : Number(item.yieldRate),
      unit: item.unit || (item.materialCategory === 'PACKAGING' ? 'pcs' : 'g'),
      unitPrice: item.unitPrice == null ? null : Number(item.unitPrice),
      taxRate: item.taxRate == null ? null : Number(item.taxRate),
      materialCategory: item.materialCategory || 'RAW',
      sortOrder: item.sortOrder ?? idx,
      isOptional: item.isOptional ?? false,
      substituteGroup: item.substituteGroup ?? null,
      remark: item.notes ?? null,
      perPortion: item.perPortion ?? false,
      semiFinishedRefCode: item.semiFinishedRefCode ?? null,
      subProductTypeId: item.subProductTypeId ?? null,
    })),
  });
}

async function fillSeasoningRecipeHeaded(page, helpers, shot, productTypeId) {
  const seasoningUrlPart = `/bom/recipes/by-product/${productTypeId}/seasoning`;
  const waitLoad = page.waitForResponse((r) => r.url().includes(seasoningUrlPart), { timeout: 20000 }).catch(() => null);
  await page.goto(`${APP}/production/product-recipes?productTypeId=${encodeURIComponent(productTypeId)}`, {
    waitUntil: 'domcontentloaded',
    timeout: 45000,
  });
  await waitLoad;
  await page.waitForTimeout(1800);
  await shot('seasoning-recipe-loaded');

  const view = page.locator('.recipe-view').first();
  if (!(await view.isVisible().catch(() => false))) throw new Error('product recipe view not visible');

  const nums = view.locator('.el-card').filter({ hasText: /锅序参数|閿呭簭/ }).first().locator('.el-input-number');
  if (await nums.nth(0).isVisible().catch(() => false)) await helpers.fillNum(nums.nth(0), 0);
  if (await nums.nth(1).isVisible().catch(() => false)) await helpers.fillNum(nums.nth(1), 100);
  if (await nums.nth(2).isVisible().catch(() => false)) await helpers.fillNum(nums.nth(2), SUBSEQUENT_POT_RATIO);

  await view.locator('button').filter({ hasText: /添加熟制料|娣诲姞鐔熷埗/ }).first().click();
  await page.waitForTimeout(700);
  const cookingCard = view.locator('.el-card').filter({ hasText: /熟制配方|COOKING|鐔熷埗/ }).last();
  const row = cookingCard.locator('.el-table__body-wrapper tbody tr').last();
  await row.locator('.el-input input').first().fill(`E2E-cook-spice-${Date.now().toString(36).slice(-4)}`);
  const rowNums = row.locator('.el-input-number');
  await helpers.fillNum(rowNums.nth(0), COOKING_DOSAGE_G_PER_KG);
  await helpers.fillNum(rowNums.nth(1), COOKING_PRICE_1);
  await helpers.fillNum(rowNums.nth(2), COOKING_PRICE_2);
  await shot('seasoning-recipe-filled');

  await view.locator('button').filter({ hasText: /保存调料配方|淇濆瓨璋冩枡/ }).last().click();
  const toast = await waitToast(page, /调料配方已保存|璋冩枡閰嶆柟.*淇濆瓨|成功|鎴愬姛/, 12000);
  if (/失败|错误|澶辫触|閿欒/.test(toast)) throw new Error(`seasoning save failed: ${toast}`);
  await page.waitForTimeout(1200);
  await shot('seasoning-recipe-saved');
}

async function createStartedPlan(api, productTypeId) {
  const plan = await api('POST', `/${FACTORY}/production-plans`, {
    productTypeId,
    plannedQuantity: 5,
    plannedDate: today(0),
    expectedCompletionDate: today(2),
    sourceType: 'MANUAL',
    skipProcessReporting: false,
    customerOrderNumber: `SEA-${Date.now()}`,
    notes: 'headed seasoning cost truth',
  });
  const planId = String(plan.id || plan.planId);
  const planNumber = String(plan.planNumber || planId);
  await api('POST', `/${FACTORY}/production-plans/${encodeURIComponent(planId)}/start`).catch(() => null);
  return { planId, planNumber };
}

async function openProcessDrawer(page, planNumber) {
  await page.goto(`${APP}/production/plans`, { waitUntil: 'domcontentloaded', timeout: 45000 });
  await page.waitForTimeout(2500);
  const search = page.locator('input[placeholder*="搜索"], input[placeholder*="计划编号"], input[placeholder*="鎼滅储"], input[placeholder*="璁″垝缂栧彿"]').first();
  if (await search.isVisible().catch(() => false)) {
    await search.fill(planNumber);
    await page.locator('button').filter({ hasText: /搜索|鎼滅储/ }).first().click().catch(() => null);
    await page.waitForTimeout(1800);
  }
  const row = page.locator('.el-table__row').filter({ hasText: planNumber }).first();
  if (!(await row.isVisible().catch(() => false))) throw new Error(`plan row not visible: ${planNumber}`);
  await row.locator('button, .el-button').filter({ hasText: /逐道录入|閫愰亾褰曞叆/ }).first().click();
  await page.waitForSelector('.el-drawer__body', { timeout: 15000 });
  await page.waitForTimeout(2200);
}

async function gotoTab(page, processName) {
  const drawer = page.locator('.el-drawer__body');
  const tab = drawer.locator('.el-tabs__item').filter({ hasText: processName }).first();
  if (!(await tab.isVisible().catch(() => false))) throw new Error(`process tab not visible: ${processName}`);
  await tab.click();
  await page.waitForTimeout(1200);
}

function activePane(page) {
  return page.locator('.el-drawer__body .el-tab-pane:visible').first();
}

async function addSheetRow(pane) {
  await pane.locator('button').filter({ hasText: /新增行|鏂板琛?/ }).first().click().catch(() => null);
  await pane.page().waitForTimeout(800);
  return pane.locator('table.sp-grid tbody tr.sp-tr').last();
}

async function saveFirstProcessRow(page, helpers, process, rawBatch, input, output) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  const row = await addSheetRow(pane);
  await helpers.selectByText(row.locator('.el-select').first(), rawBatch.batchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), input);
  await helpers.fillNum(nums.nth(1), output);
  await clickSaveAndWait(page, helpers, row, `first row save failed: ${process.processName}`);
}

async function saveSingleUpstreamRow(page, helpers, process, upstreamBatchNumber, input, output) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  const row = await addSheetRow(pane);
  await helpers.selectByText(row.locator('.el-select').first(), upstreamBatchNumber);
  const nums = row.locator('.el-input-number');
  await helpers.fillNum(nums.nth(0), input);
  await helpers.fillNum(nums.nth(1), output);
  await clickSaveAndWait(page, helpers, row, `single upstream row save failed: ${process.processName}`);
}

async function saveShuzhiRow(page, helpers, process, upstreamBatchNumber, feedKg, outputKg) {
  await gotoTab(page, process.processName);
  const pane = activePane(page);
  const row = await addSheetRow(pane);
  await row.locator('button').filter({ hasText: /\+?\s*来源批|\+?\s*鏉ユ簮/ }).first().click();
  await page.waitForTimeout(500);
  const expand = pane.locator('tr.sp-tr-expand:visible').last();
  await expand.locator('button').filter({ hasText: /\+?\s*来源批|\+?\s*鏉ユ簮/ }).first().click();
  await page.waitForTimeout(500);
  await helpers.selectByText(expand.locator('.el-select').first(), upstreamBatchNumber);
  await helpers.fillNum(expand.locator('.el-input-number').first(), feedKg);
  const rowNums = row.locator('.el-input-number');
  await helpers.fillNum(rowNums.nth(0), feedKg);
  await helpers.fillNum(rowNums.nth(1), outputKg);
  return await clickSaveAndWait(page, helpers, row, `shuzhi row save failed: ${process.processName}`);
}

async function clickSaveAndWait(page, helpers, row, errorPrefix) {
  await row.locator('button').filter({ hasText: /保存|淇濆瓨/ }).first().click();
  const saved = await helpers.waitSaved();
  if (!saved.saved) throw new Error(`${errorPrefix}: ${saved.toast}`);
  await page.waitForTimeout(1500);
  return saved;
}

async function findRawBatch(api, rawMaterial) {
  if (rawMaterial?._preferredBatch?.batchNumber) return rawMaterial._preferredBatch;
  const warehouses = arr(await api('GET', `/${FACTORY}/factory/warehouses`));
  const rawWh = warehouses.find((w) => w.code === 'WH-LOG') || warehouses.find((w) => ['RAW', 'LOGISTICS'].includes(String(w.type)));
  if (!rawWh?.id) throw new Error('no raw/logistics warehouse');
  const batches = arr(await api('GET', `/${FACTORY}/material-batches/status/AVAILABLE?warehouseId=${encodeURIComponent(rawWh.id)}&size=200`));
  return batches.find((b) => {
    const unit = String(b.quantityUnit || b.unit || '');
    return num(b.currentQuantity ?? b.quantity) > 1
      && !/^WIP-|^CLK-/.test(String(b.batchNumber || ''))
      && WEIGHT_UNIT_RE.test(unit)
      && !COUNT_UNIT_RE.test(unit)
      && String(b.materialTypeId || '') === String(rawMaterial?.id || '');
  });
}

function buildChainQuantities(chain) {
  const outputs = [0.9, 0.82, 0.74, 0.68, SHUZHI_FEED_KG];
  return chain.map((_, i) => {
    if (i === 0) return { input: 1, output: outputs[0] };
    if (i === chain.length - 1) return { input: SHUZHI_FEED_KG, output: SHUZHI_OUTPUT_KG };
    return { input: outputs[i - 1], output: outputs[i] ?? SHUZHI_FEED_KG };
  });
}

function seasoningOracle({ inputKg, dosageGPerKg, price1, price2 }) {
  const price = Math.max(Number(price1), Number(price2));
  const perKg = round4((Number(dosageGPerKg) / 1000) * price);
  const totalScale4 = round4(Number(inputKg) * perKg);
  return {
    section: 'COOKING',
    potCount: 1,
    inputKg,
    dosageGPerKg,
    price,
    perKg,
    subsequentPotRatio: SUBSEQUENT_POT_RATIO,
    totalScale4,
    totalScale2: round2(totalScale4),
  };
}

function round4(v) {
  return Math.round((Number(v) + Number.EPSILON) * 10000) / 10000;
}

function round2(v) {
  if (v == null || Number.isNaN(Number(v))) return null;
  return Math.round((Number(v) + Number.EPSILON) * 100) / 100;
}

async function waitToast(page, pattern, timeout = 10000) {
  const start = Date.now();
  let last = '';
  while (Date.now() - start < timeout) {
    const messages = await page.locator('.el-message, .el-notification').allInnerTexts().catch(() => []);
    last = messages.join('\n');
    if (pattern.test(last)) return last;
    await page.waitForTimeout(250);
  }
  throw new Error(`toast not found: ${pattern}; last=${last}`);
}
