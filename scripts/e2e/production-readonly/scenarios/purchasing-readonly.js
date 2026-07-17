'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'purchasing-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'purchasing-readonly',
    path: ROUTES.purchasing,
    landmarks: ['采购'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};
