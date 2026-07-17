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
    inspect: async (page, body) => ({
      hasPricingUnit: /元\/(?:kg|g|袋|盒|箱)/.test(body),
      hasSkuCostBasis: /元\/(?:袋|盒|箱|件|只)/.test(body),
      tableCount: await page.locator('.el-table').count(),
    }),
  }),
};
