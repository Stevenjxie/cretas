'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ui-stability',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ui-stability',
    path: ROUTES.dashboard,
    landmarks: ['工作台'],
    inspect: async (page, body) => ({
      visibleToastCount: await page.locator('.el-message:visible, .el-notification:visible').count(),
      rawEnglishEnums: (body.match(/\b[A-Z][A-Z_]{3,}\b/g) || []).slice(0, 20),
      horizontalOverflowCount: await page.locator('*').evaluateAll((elements) => elements.filter((element) => {
        const style = getComputedStyle(element);
        return element.scrollWidth > element.clientWidth + 5 && ['auto', 'scroll'].includes(style.overflowX);
      }).length),
    }),
  }),
};
