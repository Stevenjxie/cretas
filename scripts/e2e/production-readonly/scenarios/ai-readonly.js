'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ai-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ai-readonly',
    path: ROUTES.products,
    landmarks: ['SKU'],
    inspect: async (page, body) => ({
      aiEntryVisible: /AI/.test(body),
      note: 'AI chat is not sent in production; /ai/chat is intentionally not whitelisted.',
      buttonCount: await page.getByRole('button').count(),
    }),
  }),
};
