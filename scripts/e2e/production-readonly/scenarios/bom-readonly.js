'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const obsoleteControls = ['对话微调', 'Excel 导入', '一键重算出成率', 'kg/份', '元/份']
        .filter((label) => body.includes(label));
      const contract = {
        hasVersionTable: body.includes('BOM 配方版本'),
        hasHistoricalYield: body.includes('系统历史出成率'),
        obsoleteControls,
        hasPricingUnit: /元\/(?:kg|g|袋|盒|箱)/.test(body),
        hasSkuCostBasis: /元\/(?:袋|盒|箱|件|只)/.test(body),
        tableCount: await page.locator('.el-table').count(),
        addRawButtonVisible: false,
        rawDialogOpened: false,
        rawSelectionVisible: false,
        forbiddenRawDialogFields: [],
        dialogCancelled: false,
      };

      const rawTab = page.locator('.el-tabs__item').filter({ hasText: /^原料/ }).first();
      if (await rawTab.isVisible().catch(() => false)) await rawTab.click();
      const addRaw = page.getByRole('button', { name: /添加原料/ }).first();
      contract.addRawButtonVisible = await addRaw.isVisible().catch(() => false);
      const screenshots = [];
      if (contract.addRawButtonVisible) {
        await addRaw.click();
        const dialog = page.locator('.el-dialog:visible').last();
        contract.rawDialogOpened = await dialog.isVisible().catch(() => false);
        if (contract.rawDialogOpened) {
          const dialogText = await dialog.innerText();
          contract.rawSelectionVisible = dialogText.includes('选择原料');
          contract.forbiddenRawDialogFields = ['物料类别', '成品用量', '出成率%', '单价（含税）', '税率%']
            .filter((label) => dialogText.includes(label));
          screenshots.push(await ctx.screenshot('bom-raw-dialog'));
          const cancel = dialog.getByRole('button', { name: '取消' }).first();
          if (await cancel.isVisible().catch(() => false)) {
            await cancel.click();
            await dialog.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});
            contract.dialogCancelled = !(await dialog.isVisible().catch(() => false));
          }
        }
      }

      const failures = [];
      if (!contract.hasVersionTable) failures.push('BOM version table missing');
      if (!contract.hasHistoricalYield) failures.push('system historical yield missing');
      if (contract.obsoleteControls.length) failures.push('obsolete BOM controls visible');
      if (!contract.addRawButtonVisible) failures.push('add raw material button missing');
      if (!contract.rawDialogOpened || !contract.rawSelectionVisible) failures.push('raw material selection dialog contract missing');
      if (contract.forbiddenRawDialogFields.length) failures.push('obsolete raw material fields visible');
      if (contract.rawDialogOpened && !contract.dialogCancelled) failures.push('raw material dialog did not cancel cleanly');
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
