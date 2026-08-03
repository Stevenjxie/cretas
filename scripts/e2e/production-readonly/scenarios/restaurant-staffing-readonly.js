'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { pathnameOf } = require('../core/url-utils');

module.exports = {
  id: 'restaurant-staffing-readonly',
  path: ROUTES.restaurantStaffing,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'restaurant-staffing-readonly',
    path: ROUTES.restaurantStaffing,
    landmarks: ['预测 FactBook', '各门店 · 各时段', '问餐饮 AI'],
    screenshot: true,
    inspect: async (page) => {
      const finalPath = pathnameOf(page.url());
      const loadError = await page.locator('.page-alert.el-alert--error').innerText().catch(() => '');
      const metricCardCount = await page.locator('.metric-grid .metric-card').count();
      const tableCount = await page.locator('.table-card .el-table').count();
      const assessment = finalPath === ROUTES.restaurantStaffing
        && !loadError
        && metricCardCount === 6
        && tableCount === 1
        ? { result: 'PASS', rootCauseClass: 'none' }
        : { result: 'CONFIRMED_DEFECT', rootCauseClass: loadError ? 'backend' : 'frontend' };
      return {
        finalPath,
        loadError: loadError || null,
        metricCardCount,
        tableCount,
        assessment,
      };
    },
  }),
};
