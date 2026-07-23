'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

const TOPOLOGY_KEYS = ['1_TO_1', '1_TO_MANY', 'MANY_TO_1', 'MANY_TO_MANY'];

function topologyKey(candidate) {
  const inputCount = new Set(candidate?.rootInputProductTypeIds || []).size;
  const outputCount = new Set((candidate?.terminalOutputs || []).map((item) => item.productTypeId).filter(Boolean)).size;
  if (candidate?.workflowType === 'RAW_MATERIAL_SPLIT') return '1_TO_MANY';
  if (candidate?.workflowType === 'JOINT_PRODUCTION') return 'MANY_TO_MANY';
  if (outputCount === 1 && inputCount <= 1) return '1_TO_1';
  // The resolver DTO exposes physical raw roots but not the EXACTLY_ONE logical
  // grouping needed to distinguish alternatives from simultaneous many-to-one.
  if (outputCount === 1 && inputCount > 1) return 'MULTI_RAW_TO_1_UNQUALIFIED';
  return 'UNQUALIFIED';
}

function chooseRepresentatives(inventory) {
  const byTopology = {};
  let ambiguous = null;
  let superset = null;
  for (const item of inventory) {
    if (!ambiguous && item.candidates.length > 1) ambiguous = item;
    for (const candidate of item.candidates) {
      if (!byTopology[candidate.topology]) byTopology[candidate.topology] = item;
      if (!superset && candidate.exactMatch === false) superset = item;
    }
  }
  return { byTopology, ambiguous, superset };
}

async function inventoryWorkflowRoutes(page, expectedFactoryId) {
  return page.evaluate(async ({ factoryId, topologyKeys }) => {
    const apiRoot = `/api/mobile/${encodeURIComponent(factoryId)}`;
    const readJson = async (url, init) => {
      const response = await fetch(url, { credentials: 'same-origin', ...init });
      const payload = await response.json().catch(() => null);
      return { ok: response.ok, status: response.status, payload };
    };
    const productResponse = await readJson(`${apiRoot}/product-types/active`);
    const rawRows = productResponse.payload?.data?.content || productResponse.payload?.data || [];
    const excluded = new Set(['RAW_MATERIAL', 'PACKAGING', 'SEASONING']);
    const products = (Array.isArray(rawRows) ? rawRows : [])
      .filter((row) => row?.id && !excluded.has(String(row.productCategory || '')))
      .map((row) => ({ id: String(row.id), name: String(row.name || row.id) }));
    const priority = /E2E|替代|熟成|联产|鸡|DEMO|测试/i;
    products.sort((left, right) => Number(priority.test(right.name)) - Number(priority.test(left.name)));

    const records = [];
    const coverage = new Set();
    let ambiguousFound = false;
    let supersetFound = false;
    let resolverCalls = 0;
    for (const product of products.slice(0, 200)) {
      const resolution = await readJson(`${apiRoot}/product-process-workflows/resolve-by-outputs`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ productTypeIds: [product.id] }),
      });
      resolverCalls += 1;
      const candidates = resolution.payload?.data?.candidates || [];
      if (!resolution.ok || !Array.isArray(candidates) || candidates.length === 0) continue;
      const normalized = candidates.map((candidate) => {
        const roots = [...new Set(candidate.rootInputProductTypeIds || [])];
        const outputs = (candidate.terminalOutputs || []).map((output) => ({
          id: String(output.productTypeId || ''),
          name: String(output.productName || output.productTypeId || ''),
        })).filter((output) => output.id);
        const workflowType = String(candidate.workflowType || '');
        const topology = workflowType === 'RAW_MATERIAL_SPLIT'
          ? '1_TO_MANY'
          : workflowType === 'JOINT_PRODUCTION'
            ? 'MANY_TO_MANY'
            : outputs.length === 1 && roots.length <= 1
              ? '1_TO_1'
              : outputs.length === 1 && roots.length > 1
                ? 'MULTI_RAW_TO_1_UNQUALIFIED'
                : 'UNQUALIFIED';
        coverage.add(topology);
        if (candidate.exactMatch === false) supersetFound = true;
        return {
          workflowId: candidate.workflowId,
          definitionVersion: candidate.definitionVersion,
          workflowType,
          topology,
          exactMatch: candidate.exactMatch === true,
          rootInputCount: roots.length,
          terminalOutputs: outputs,
          processSteps: (candidate.processSteps || []).map(String),
          previewNodeCount: (candidate.previewNodes || []).length,
          previewEdgeCount: (candidate.previewEdges || []).length,
        };
      });
      ambiguousFound ||= normalized.length > 1;
      records.push({ selection: [product], candidates: normalized });
      if (topologyKeys.every((key) => coverage.has(key)) && ambiguousFound && supersetFound) break;
    }
    return {
      activeFinishedGoodCount: products.length,
      resolverCalls,
      records,
      coverage: [...coverage],
      ambiguousFound,
      supersetFound,
    };
  }, { factoryId: expectedFactoryId, topologyKeys: TOPOLOGY_KEYS });
}

async function selectProducts(page, planDialog, names) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  const wrapper = productField.locator('.el-select__wrapper');
  const input = productField.locator('input.el-select__input');
  for (let index = 0; index < names.length; index += 1) {
    await wrapper.click();
    await input.waitFor({ state: 'attached', timeout: 8_000 });
    await input.fill(names[index]);
    const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: names[index] })
      .first();
    await option.waitFor({ state: 'visible', timeout: 8_000 });
    const finalResponse = index === names.length - 1
      ? page.waitForResponse((response) => response.request().method() === 'POST'
          && response.url().includes('/product-process-workflows/resolve-by-outputs'), { timeout: 15_000 })
      : null;
    await option.click();
    if (finalResponse) await finalResponse;
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.keyboard.press('Tab').catch(() => {});
  const dateLabel = planDialog.locator('.el-form-item__label').filter({ hasText: '计划生产日' }).first();
  if (await dateLabel.isVisible().catch(() => false)) await dateLabel.click();
  await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {});
  await page.getByText('正在解析工序图…').waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});
}

async function clearSelectedProducts(planDialog) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  for (let attempts = 0; attempts < 12; attempts += 1) {
    const closer = productField.locator('.el-tag__close').first();
    if (!await closer.isVisible().catch(() => false)) break;
    await closer.click();
  }
}

async function inspectUiCase(ctx, planDialog, item, caseId) {
  const names = item.selection.map((product) => product.name);
  await selectProducts(ctx.page, planDialog, names);
  const routeDialog = ctx.page.getByRole('dialog', { name: '选择本计划使用的生产工序路线' });
  const decisionVisible = await routeDialog.isVisible().catch(() => false);
  const selectedRoute = planDialog.locator('.selected-workflow-route');
  const autoResolved = await selectedRoute.isVisible().catch(() => false);
  const trigger = decisionVisible
    ? routeDialog.locator('.workflow-preview-trigger').first()
    : selectedRoute.getByRole('button', { name: '悬浮查看 Cell 图' }).first();
  let preview = { visible: false, nodeCount: 0, edgeCount: 0, labels: [] };
  if (await trigger.isVisible().catch(() => false)) {
    await trigger.scrollIntoViewIfNeeded();
    await trigger.hover();
    const previewRoot = ctx.page.locator('[data-testid="workflow-route-preview"]:visible').first();
    await previewRoot.waitFor({ state: 'visible', timeout: 8_000 }).catch(() => {});
    preview = {
      visible: await previewRoot.isVisible().catch(() => false),
      nodeCount: await previewRoot.locator('.preview-cell').count(),
      edgeCount: await previewRoot.locator('.preview-edge').count(),
      labels: (await previewRoot.locator('.preview-cell').allInnerTexts()).slice(0, 12),
    };
  }
  const screenshot = await ctx.screenshot(`production-plan-routing-${caseId}`);
  const evidence = {
    caseId,
    selectedProducts: names,
    expectedTopologies: [...new Set(item.candidates.map((candidate) => candidate.topology))],
    candidateCountFromResolver: item.candidates.length,
    decisionDialogVisible: decisionVisible,
    candidateCardCount: decisionVisible ? await routeDialog.locator('.workflow-candidate-card').count() : 0,
    autoResolved,
    preview,
    screenshot,
  };
  if (decisionVisible) await routeDialog.getByRole('button', { name: '暂不选择' }).click();
  await clearSelectedProducts(planDialog);
  return evidence;
}

module.exports = {
  id: 'production-plan-routing-readonly',
  topologyKey,
  chooseRepresentatives,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'production-plan-routing-readonly',
    path: ROUTES.productionPlans,
    landmarks: ['生产计划管理'],
    timeoutMs: 60_000,
    inspect: async (page) => {
      await page.getByRole('button', { name: '新建计划', exact: true }).click();
      const planDialog = page.getByRole('dialog', { name: '新建生产计划' });
      await planDialog.waitFor({ state: 'visible', timeout: 10_000 });
      const manualSource = planDialog.getByRole('radio', { name: '手动', exact: true });
      await manualSource.check({ force: true });
      if (!await manualSource.isChecked()) throw new Error('Manual production source radio did not become checked');
      const productionField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
      await productionField.waitFor({ state: 'visible', timeout: 10_000 });
      await productionField.locator('.el-select__wrapper').waitFor({ state: 'visible', timeout: 10_000 });
      const inventory = await inventoryWorkflowRoutes(page, ctx.expectedFactoryId);
      const representatives = chooseRepresentatives(inventory.records);

      const uiCases = [];
      const usedSelections = new Set();
      for (const topology of TOPOLOGY_KEYS) {
        const item = representatives.byTopology[topology];
        if (!item) continue;
        const key = item.selection.map((product) => product.id).sort().join(',');
        if (usedSelections.has(key)) continue;
        usedSelections.add(key);
        uiCases.push(await inspectUiCase(ctx, planDialog, item, topology.toLowerCase()));
      }
      if (representatives.ambiguous) {
        const key = representatives.ambiguous.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.ambiguous, 'ambiguous'));
        }
      }
      if (representatives.superset) {
        const key = representatives.superset.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.superset, 'superset'));
        }
      }
      await planDialog.getByRole('button', { name: '取消', exact: true }).click();

      const topologyCoverage = Object.fromEntries(TOPOLOGY_KEYS.map((key) => [key, inventory.coverage.includes(key)]));
      const previewCases = uiCases.filter((item) => item.preview.visible && item.preview.nodeCount > 0).length;
      const expectedCaseCount = Object.values(topologyCoverage).filter(Boolean).length;
      const complete = Object.values(topologyCoverage).every(Boolean)
        && uiCases.length >= expectedCaseCount
        && previewCases === uiCases.length;
      return {
        activeFinishedGoodCount: inventory.activeFinishedGoodCount,
        resolverCalls: inventory.resolverCalls,
        topologyCoverage,
        unqualifiedMultiRawSingleOutputFound: inventory.coverage.includes('MULTI_RAW_TO_1_UNQUALIFIED'),
        ambiguousFound: inventory.ambiguousFound,
        supersetFound: inventory.supersetFound,
        uiCaseSummaries: uiCases.map((item) => ({
          caseId: item.caseId,
          selectedProducts: item.selectedProducts,
          expectedTopologies: item.expectedTopologies,
          candidateCountFromResolver: item.candidateCountFromResolver,
          decisionDialogVisible: item.decisionDialogVisible,
          candidateCardCount: item.candidateCardCount,
          autoResolved: item.autoResolved,
          previewVisible: item.preview.visible,
          previewNodeCount: item.preview.nodeCount,
          previewEdgeCount: item.preview.edgeCount,
          previewLabels: item.preview.labels,
        })),
        note: 'The create dialog was cancelled; no production plan was submitted.',
        screenshots: uiCases.map((item) => item.screenshot),
        assessment: complete
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'UNVERIFIED', rootCauseClass: 'data' },
      };
    },
  }),
};
