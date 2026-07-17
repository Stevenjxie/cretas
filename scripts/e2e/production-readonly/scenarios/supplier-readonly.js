'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'supplier-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'supplier-readonly',
    path: ROUTES.suppliers,
    landmarks: ['供应商'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};
