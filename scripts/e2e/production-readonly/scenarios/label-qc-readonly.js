'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'label-qc-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'label-qc-readonly',
    path: ROUTES.labelQc,
    landmarks: ['包装标签拍检', '待人工审核', '已审核整理', '归档记录'],
    screenshot: true,
    inspect: async (page) => ({
      reviewButtonCount: await page.getByRole('button', { name: '人工审核' }).count(),
      visibleTableCount: await page.locator('.el-table:visible').count(),
      note: 'Read-only page load only. Review, retry, archive, restore, backup, export, and training actions are intentionally not invoked.',
    }),
  }),
};
