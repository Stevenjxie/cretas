'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

/**
 * BOM 页的成本口径: 人工成本与均摊费用已从配置期整体下掉 —— 它们是报工之后
 * 结算/成本分析阶段才产生的数据, 在 BOM 配置期填的是拍脑袋的数。
 *
 * 页面上应只剩「当前归集成本」这一张卡(只聚包材; 原料实际用量待生产报工),
 * 「人工成本」「均摊费用」两块不应再出现在 BOM 页。
 *
 * 这条与后端 calculateProductCost 只对 PACKAGING 求和是同一口径的两个承载面;
 * 后端那面由 Java 单测钉住, 这里钉前端这面。
 */
const RETIRED_COST_BLOCKS = ['人工成本', '均摊费用', '人工与均摊'];

module.exports = {
  id: 'bom-cost-caliber-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-cost-caliber-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (_page, body) => {
      const aggregatedCostCardVisible = body.includes('当前归集成本');
      const retiredBlocksPresent = RETIRED_COST_BLOCKS.filter((label) => body.includes(label));

      const evidence = {
        aggregatedCostCardVisible,
        retiredBlocksPresent,
        retiredBlocksChecked: RETIRED_COST_BLOCKS,
        packagingCardVisible: body.includes('包材'),
        auxiliaryCardVisible: body.includes('工序辅料'),
        note: 'Read-only: no product is created, cloned, or saved.',
      };

      // 数据前提: BOM 页要先落到产品上下文才会渲染成本卡组。
      if (!body.includes('配方管理')) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: 'BOM page did not reach a product context; cost cards are not rendered',
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const contractFailures = [];
      if (!aggregatedCostCardVisible) contractFailures.push('aggregated cost card 「当前归集成本」 missing');
      for (const label of retiredBlocksPresent) {
        contractFailures.push(`retired cost block 「${label}」 still rendered on the BOM page`);
      }

      return {
        ...evidence,
        contractFailures,
        assessment: contractFailures.length === 0
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};
