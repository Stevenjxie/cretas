'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'finance-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'finance-readonly',
    path: ROUTES.finance,
    landmarks: ['财务'],
    inspect: async (page, body) => ({ hasPaymentMethodColumn: /支付方式|付款方式/.test(body), tableCount: await page.locator('.el-table').count() }),
  }),
};
