'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

async function selectTargetProduct(page) {
  if (await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false)) {
    return { selected: true, selectedLabel: null, failure: null };
  }

  const productSelect = page.locator('.bom-hero-card .el-select, .header-card .el-select').first();
  const productInput = productSelect.locator('input').first();
  if (!(await productSelect.isVisible().catch(() => false))
      || !(await productInput.isVisible().catch(() => false))) {
    return { selected: false, selectedLabel: null, failure: 'product selector missing' };
  }

  await productSelect.click();
  await productInput.fill('干式熟成鸡 400g');
  const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)');
  let targetOption = options.filter({ hasText: '干式熟成鸡 400g' }).first();
  await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});

  if (!(await targetOption.isVisible().catch(() => false))) {
    await productInput.fill('干式熟成鸡');
    targetOption = options.filter({ hasText: /干式熟成鸡.*400g/ }).first();
    await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  }
  if (!(await targetOption.isVisible().catch(() => false))) {
    await page.keyboard.press('Escape');
    const selectedBomLoaded = await page.locator(
      '[data-testid="bom-version-lifecycle"], .bom-summary-grid, .recipe-status-card',
    ).first().isVisible().catch(() => false);
    if (selectedBomLoaded) {
      const selectedLabel = (
        await page.locator('.bom-hero__sku').first().innerText().catch(() => '')
        || await productInput.inputValue().catch(() => '')
      ).trim() || null;
      return {
        selected: true,
        selectedLabel,
        selectionMode: 'deterministic-page-selection',
        failure: null,
      };
    }
    return { selected: false, selectedLabel: null, selectionMode: null, failure: 'target product option missing' };
  }

  const selectedLabel = (await targetOption.innerText()).trim();
  await targetOption.click();
  await page.getByText('BOM 配方版本', { exact: true })
    .waitFor({ state: 'visible', timeout: 15_000 })
    .catch(() => {});
  return {
    selected: await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false),
    selectedLabel,
    selectionMode: 'target-search',
    failure: null,
  };
}

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const productSelection = await selectTargetProduct(page);
      const versionHistoryButton = page.getByRole('button', { name: '版本历史', exact: true }).first();
      if (await versionHistoryButton.isVisible().catch(() => false)) {
        await versionHistoryButton.click();
        await page.getByText('BOM 配方版本', { exact: true })
          .waitFor({ state: 'visible', timeout: 10_000 })
          .catch(() => {});
      }
      const activeRow = page.locator('.recipe-status-card .el-table__row')
        .filter({ hasText: '已生效' })
        .first();
      const lifecycle = page.locator('[data-testid="bom-version-lifecycle"]');
      let activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
        .isVisible()
        .catch(() => false);
      if (!activeViewOpened && await activeRow.isVisible().catch(() => false)) {
        const viewActive = activeRow.getByRole('button', { name: '查看', exact: true }).first();
        if (await viewActive.isVisible().catch(() => false)) {
          await viewActive.click();
          await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .waitFor({ state: 'visible', timeout: 10_000 })
            .catch(() => {});
          activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .isVisible()
            .catch(() => false);
        }
      }
      const currentBody = await page.locator('body').innerText();
      const obsoleteControls = ['对话微调', 'Excel 导入', '一键重算出成率', 'kg/份']
        .filter((label) => currentBody.includes(label));
      const contract = {
        productSelection,
        hasVersionTable: currentBody.includes('BOM 配方版本'),
        hasHistoricalYield: currentBody.includes('系统历史出成率'),
        obsoleteControls,
        hasPricingUnit: /元\/(?:kg|g|袋|盒|箱)/.test(currentBody),
        hasSkuCostBasis: /元\/(?:袋|盒|箱|件|只|份)/.test(currentBody),
        tableCount: await page.locator('.el-table').count(),
        activeViewOpened,
        activeVersionVisible: /已生效/.test(currentBody),
        readOnlyGuidanceVisible: /当前生效.*内容已锁定|历史版本.*仅供查看/.test(currentBody),
        cloneActionVisible: false,
        addRawButtonVisible: false,
      };

      const rawTab = page.locator('.el-tabs__item').filter({ hasText: /^原料/ }).first();
      if (await rawTab.isVisible().catch(() => false)) await rawTab.click();
      const addRaw = page.getByRole('button', { name: /添加原料/ }).first();
      contract.addRawButtonVisible = await addRaw.isVisible().catch(() => false);
      contract.cloneActionVisible = await page.getByRole('button', {
        name: /克隆为新版本.*修改|前往 v\d+ 草稿.*修改|继续修改 v\d+ 草稿|新建版本/,
      }).first().isVisible().catch(() => false);
      const screenshots = [await ctx.screenshot('bom-active-readonly')];

      const failures = [];
      if (!productSelection.selected) failures.push(productSelection.failure || 'product selection failed');
      if (!contract.hasVersionTable) failures.push('BOM version table missing');
      if (!contract.hasHistoricalYield) failures.push('system historical yield missing');
      if (contract.obsoleteControls.length) failures.push('obsolete BOM controls visible');
      if (!contract.activeVersionVisible) failures.push('active BOM version missing');
      if (!contract.activeViewOpened) failures.push('active BOM view did not open');
      if (!contract.readOnlyGuidanceVisible) failures.push('active BOM read-only guidance missing');
      if (!contract.cloneActionVisible) failures.push('clone-to-new-version action missing');
      if (contract.addRawButtonVisible) failures.push('active BOM exposes raw material mutation action');
      return {
        ...contract,
        contractFailures: failures,
        screenshots,
        assessment: failures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'PASS', rootCauseClass: 'none' },
      };
    },
  }),
};
