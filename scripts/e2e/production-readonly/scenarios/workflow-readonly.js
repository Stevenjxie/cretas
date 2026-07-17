'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'workflow-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'workflow-readonly',
    path: ROUTES.workflow,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => ({
      rawCellEntryVisible: /原料 Cell|原料 SKU/.test(body),
      publishControlVisible: /发布/.test(body),
      quantityRelationVisible: body.includes('投入产出数量关系'),
      inputUnitChips: await page.getByTestId('input-unit-chip').allInnerTexts(),
      note: 'Publish/save/apply controls are never clicked by this scenario.',
      selectCount: await page.locator('.el-select').count(),
    }),
  }),
};
